package app.nadaka

import android.content.Context
import android.media.AudioManager
import android.os.VibrationEffect
import android.os.VibratorManager

/** Everyday words -> the detector's COCO labels. Targets not listed here (door, stairs, exit) go to Gemma. */
private val FINDABLE = mapOf(
    "chair" to "chair", "seat" to "chair", "sofa" to "couch", "couch" to "couch", "table" to "dining table",
    "person" to "person", "someone" to "person", "somebody" to "person", "people" to "person", "man" to "person", "woman" to "person",
    "bottle" to "bottle", "water" to "bottle", "cup" to "cup", "glass" to "cup", "mug" to "cup",
    "phone" to "cell phone", "mobile" to "cell phone", "bag" to "backpack", "backpack" to "backpack", "handbag" to "handbag",
    "bench" to "bench", "bicycle" to "bicycle", "cycle" to "bicycle", "car" to "car", "bike" to "motorcycle", "scooter" to "motorcycle",
    "motorcycle" to "motorcycle", "bus" to "bus", "dog" to "dog", "cat" to "cat", "cow" to "cow", "tv" to "tv", "television" to "tv",
    "laptop" to "laptop", "book" to "book", "toilet" to "toilet", "sink" to "sink", "bed" to "bed", "clock" to "clock",
    "umbrella" to "umbrella", "suitcase" to "suitcase", "keyboard" to "keyboard", "remote" to "remote", "plant" to "potted plant",
    "fridge" to "refrigerator", "refrigerator" to "refrigerator", "scissors" to "scissors", "keys" to "", "door" to "", "stairs" to "",
)

/** What the user asked to find: the words after "find / where is / look for", and the detector label if it has one. */
fun findTarget(text: String): Pair<String, String?>? {
    val m = Regex("""\b(find|where is|where's|wheres|look for|search for|locate|take me to)\s+(?:my |the |a |an |some )?([a-z ]+)""")
        .find(normalise(text)) ?: return null
    val words = m.groupValues[2].trim().split(" ").filter { it.isNotEmpty() }.take(3)
    if (words.isEmpty()) return null
    val label = words.firstNotNullOfOrNull { w -> FINDABLE[w] ?: FINDABLE[w.removeSuffix("s")] }?.ifEmpty { null }
    return words.joinToString(" ") to label
}

/**
 * Guides the user to the nearest object with [label]: clock direction and distance every few
 * seconds, "right in front of you" when it is within reach. Pure logic, unit-tested.
 */
class Finder(val label: String, private val startMs: Long) {
    private var lastSaidMs = -1_000_000L
    private var lastSeenMs = startMs
    var done = false
        private set

    fun update(now: Long, tracks: List<Track>): String? {
        if (done) return null
        val name = label.replaceFirstChar { it.uppercase() }
        if (now - startMs > Settings.findTimeoutMs) { done = true; return "Stopped looking for the $label." }
        val t = tracks.filter { it.label == label && it.hits >= 3 && !it.metres.isNaN() }.minByOrNull { it.metres }
        if (t != null) {
            lastSeenMs = now
            if (t.metres < Settings.veryCloseM) { done = true; return "$name, right in front of you, ${clock(t.bearing)}." }
            if (now - lastSaidMs < Settings.findRepeatMs) return null
            lastSaidMs = now
            return "$name, ${clock(t.bearing)}, ${metres(t.metres)}."
        }
        if (now - lastSeenMs > 3000 && now - lastSaidMs > Settings.findRepeatMs * 2) {
            lastSaidMs = now
            return "No $label in view. Turn slowly."
        }
        return null
    }
}

/**
 * Hold both volume keys for 2 s (or say "emergency"): a loud alarm and strong vibration to draw
 * people nearby, plus a spoken call for help. Any volume key stops it. ponytail: no auto-calling
 * (an accidental 112 call during a demo is worse); an emergency-contact SMS can be added later.
 */
class Emergency(ctx: Context) {
    private val vibrator = ctx.getSystemService(VibratorManager::class.java).defaultVibrator
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private var savedVolume = -1
    private var siren: android.media.AudioTrack? = null
    var active = false
        private set

    fun start() {
        if (active) return
        active = true
        // A siren nobody can hear is useless: alarm stream to maximum while it sounds, restored on stop.
        savedVolume = runCatching { audio.getStreamVolume(AudioManager.STREAM_ALARM) }.getOrDefault(-1)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
        siren = wail().also { it.play() } // continuous: loops with no gap until stop()
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 1000), intArrayOf(255, 255), 0)) // continuous
    }

    /**
     * A police-style wail: pitch sweeps 650 -> 1500 -> 650 Hz over 1.2 s, as one seamless loop
     * (phase-continuous, starts and ends at the same pitch), on the alarm channel.
     */
    private fun wail(): android.media.AudioTrack {
        val rate = 44_100
        val n = rate * 12 / 10
        val pcm = ShortArray(n)
        // Pitch sweeps up and back down; scaled a hair so the total phase is a whole number of cycles:
        // the last sample leads straight into the first, so the loop has no gap and no click.
        val f = DoubleArray(n) { i -> 650 + 850 * (1 - kotlin.math.cos(2 * Math.PI * i / n)) / 2 }
        val cycles = f.sum() / rate
        val k = kotlin.math.round(cycles) / cycles
        var phase = 0.0
        for (i in 0 until n) {
            pcm[i] = (kotlin.math.sin(phase) * 0.9 * Short.MAX_VALUE).toInt().toShort()
            phase += 2 * Math.PI * f[i] * k / rate
        }
        val t = android.media.AudioTrack.Builder()
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(rate)
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(android.media.AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(n * 2)
            .build()
        t.write(pcm, 0, n)
        t.setLoopPoints(0, n, -1) // forever
        return t
    }

    fun stop() {
        if (!active) return
        active = false
        siren?.run { runCatching { stop() }; release() }; siren = null
        vibrator.cancel()
        if (savedVolume >= 0) runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) }
    }
}
