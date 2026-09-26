package app.nadaka

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.concurrent.thread

/**
 * Dangers you hear before you see: the camera only looks ahead, the microphone hears all around.
 * Class indices are YAMNet's AudioSet labels (assets/yamnet_labels.txt).
 */
enum class Danger(val spoken: String, val ids: Set<Int>) {
    HORN("Horn nearby.", setOf(302, 312, 325, 395)),       // car horn, air/truck horn, train horn, foghorn
    SIREN("Siren nearby.", setOf(316, 317, 318, 319, 390, 391)),
    REVERSING("Vehicle reversing nearby.", setOf(313)),
    BELL("Bicycle bell nearby.", setOf(198)),
    DOG("Dog barking nearby.", setOf(70)),
}

/**
 * Confirms a danger sound over consecutive 1 s windows (0.5 s hop) and rate-limits each kind.
 * Pure logic, unit-tested.
 */
class SoundPolicy {
    private var candidate: Danger? = null
    private var hits = 0
    private val saidMs = HashMap<Danger, Long>()

    fun update(now: Long, scores: FloatArray): Danger? {
        val best = Danger.entries
            .map { d -> d to d.ids.maxOf { scores.getOrElse(it) { 0f } } }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= Settings.soundMinScore }?.first
        if (best != candidate) { candidate = best; hits = 0 }
        if (best == null) return null
        hits++
        // A horn blast is short: one strong window is enough; barking needs to persist.
        val need = if (best == Danger.DOG) 2 else 1
        if (hits < need || now - (saidMs[best] ?: -1_000_000L) < Settings.soundRepeatMs) return null
        saidMs[best] = now
        return best
    }
}

/** YAMNet on the NPU (FP16), listening continuously; pauses whenever [paused] says so (voice questions). */
class SoundWatch(private val ctx: Context, private val paused: () -> Boolean, private val onDanger: (Danger) -> Unit) {
    @Volatile private var running = false
    @Volatile private var generation = 0 // each start() gets its own loop; older loops exit
    @Volatile var backend = ""
        private set

    @SuppressLint("MissingPermission") // started only after RECORD_AUDIO is granted
    fun start() {
        if (running) return
        running = true
        val gen = ++generation
        thread(name = "sounds", isDaemon = true) {
            try { loop(gen) } catch (t: Throwable) { Log.e(TAG, "sounds: stopped", t) }
        }
    }

    fun stop() { running = false; generation++ }

    @SuppressLint("MissingPermission")
    private fun loop(gen: Int) {
        fun alive() = running && gen == generation
        val fd = ctx.assets.openFd("yamnet.tflite")
        val model = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        // Tiny model (~10 ms on CPU): keep the NPU free for the vision models, which share it from other threads.
        val interp = org.tensorflow.lite.Interpreter(model, org.tensorflow.lite.Interpreter.Options().setNumThreads(2))
        backend = "CPU"
        Log.i(TAG, "sounds: YAMNet on CPU")
        var first = true
        val n = 15600 // 0.975 s at 16 kHz
        val hop = 8000
        val window = FloatArray(n)
        val chunk = ShortArray(hop)
        val input = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
        val out = Array(1) { FloatArray(521) }
        val policy = SoundPolicy()
        val labels = ctx.assets.open("yamnet_labels.txt").bufferedReader().readLines()
        var lastLogMs = 0L
        var rec: AudioRecord? = null
        while (alive()) {
            if (paused()) { rec?.release(); rec = null; Thread.sleep(200); continue } // let the recognizer have the mic
            var r = rec
            if (r == null) {
                // 16-bit PCM is supported everywhere (float capture is not, and failed silently here).
                r = AudioRecord(
                    MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), hop * 2 * 2),
                )
                if (r.state != AudioRecord.STATE_INITIALIZED) { Log.w(TAG, "sounds: mic not available, retrying"); r.release(); Thread.sleep(2000); continue }
                r.startRecording()
                rec = r
            }
            var got = 0
            var failed = false
            while (got < hop && alive() && !paused()) {
                val k = r.read(chunk, got, hop - got, AudioRecord.READ_BLOCKING)
                if (k < 0) { Log.w(TAG, "sounds: mic read error $k, reopening"); failed = true; break }
                got += k
            }
            if (failed) { r.release(); rec = null; Thread.sleep(500); continue }
            if (got < hop) continue
            System.arraycopy(window, hop, window, 0, n - hop)
            for (i in 0 until hop) window[n - hop + i] = chunk[i] / 32768f
            input.rewind(); input.asFloatBuffer().put(window)
            val t0 = android.os.SystemClock.elapsedRealtime()
            interp.run(input, out)
            if (first) { first = false; Log.i(TAG, "sounds: first inference ${android.os.SystemClock.elapsedRealtime() - t0} ms") }
            val nowMs = android.os.SystemClock.elapsedRealtime()
            if (nowMs - lastLogMs > 10000) { // what it hears, for tuning
                lastLogMs = nowMs
                val rms = kotlin.math.sqrt(window.fold(0.0) { a, v -> a + v * v } / n)
                val top = out[0].withIndex().sortedByDescending { it.value }.take(3).joinToString { "${labels.getOrElse(it.index) { "?" }} %.2f".format(it.value) }
                Log.i(TAG, "sounds: rms %.4f  top: $top".format(rms))
            }
            policy.update(android.os.SystemClock.elapsedRealtime(), out[0])?.let { d ->
                Log.i(TAG, "sounds: $d ${d.ids.maxOf { out[0][it] }}")
                onDanger(d)
            }
        }
        rec?.release()
        interp.close()
    }
}
