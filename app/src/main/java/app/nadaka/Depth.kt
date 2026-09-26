package app.nadaka

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

import kotlin.math.cos
import kotlin.math.sin

const val DEPTH_ROWS = 48
const val DEPTH_COLS = 32

/**
 * Depth Anything V2 (Qualcomm AI Hub, float, 518x518) on the Hexagon NPU in FP16.
 * Output is relative inverse depth ("disparity": bigger = closer), pooled to a 48x32 grid.
 * Single-threaded (analysis thread).
 */
class DepthModel(ctx: Context, only: Backend? = null) {
    private val interpreter: Interpreter
    val backend: String
    private val size: Int
    private val input: ByteBuffer
    private val output: ByteBuffer
    private val pixels: IntArray
    private val raw: FloatArray
    /** Raw 518x518 relative disparity of the last run (before pooling), for drop-off edge sampling. Read-only use. */
    val rawDisparity: FloatArray get() = raw
    val rawSize: Int get() = size
    var lastRunMs = 0L
        private set
    private val rgbF: FloatArray
    private val delegate: AutoCloseable?
    var preMs = 0.0; var inferMs = 0.0; var postMs = 0.0
    val initMs: Double

    init {
        val fd = ctx.assets.openFd("depth.tflite")
        val model = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        val t0 = System.nanoTime()
        val o = openInterpreter(ctx, model, fp16 = true, token = "depth_anything_v2_fp32_aihub_v0.63.0_htpfp16", only = only)
        interpreter = o.interpreter
        backend = o.backend
        delegate = o.delegate
        initMs = (System.nanoTime() - t0) / 1e6
        size = interpreter.getInputTensor(0).shape()[1]
        input = ByteBuffer.allocateDirect(size * size * 3 * 4).order(ByteOrder.nativeOrder())
        output = ByteBuffer.allocateDirect(interpreter.getOutputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
        pixels = IntArray(size * size)
        raw = FloatArray(size * size)
        rgbF = FloatArray(size * size * 3)
        Log.i(TAG, "depth on $backend, input ${size}x$size")
    }

    /** Disparity grid [row][col], rows top->bottom of the (upright) frame. */
    fun close() { interpreter.close(); runCatching { delegate?.close() } }

    fun run(frame: Bitmap): Array<FloatArray> {
        val t0 = System.nanoTime()
        Bitmap.createScaledBitmap(frame, size, size, true).getPixels(pixels, 0, size, 0, 0, size, size)
        // Plain array + one bulk copy (per-element FloatBuffer.put was ~0.8 M calls a frame).
        var k = 0
        val inv = 1f / 255f
        for (p in pixels) { rgbF[k++] = (p shr 16 and 0xFF) * inv; rgbF[k++] = (p shr 8 and 0xFF) * inv; rgbF[k++] = (p and 0xFF) * inv }
        input.rewind()
        input.asFloatBuffer().put(rgbF)
        output.rewind()
        val t1 = System.nanoTime()
        interpreter.run(input, output)
        val t2 = System.nanoTime()
        output.rewind()
        output.asFloatBuffer().get(raw)
        lastRunMs = android.os.SystemClock.elapsedRealtime()
        return Array(DEPTH_ROWS) { r ->
            FloatArray(DEPTH_COLS) { c ->
                val y0 = r * size / DEPTH_ROWS; val y1 = (r + 1) * size / DEPTH_ROWS
                val x0 = c * size / DEPTH_COLS; val x1 = (c + 1) * size / DEPTH_COLS
                var s = 0f
                for (y in y0 until y1) for (x in x0 until x1) s += raw[y * size + x]
                s / ((y1 - y0) * (x1 - x0))
            }
        }.also {
            val t3 = System.nanoTime()
            preMs = (t1 - t0) / 1e6; inferMs = (t2 - t1) / 1e6; postMs = (t3 - t2) / 1e6
        }
    }
}

/**
 * Turns relative disparity into metres and hazards using the floor as a ruler:
 * the camera height (Settings.cameraHeightM) and tilt (gravity) predict how far the floor
 * should be in every image row. The near floor rows fix the unknown depth scale; then
 *  - floor looks FARTHER than predicted  -> the floor drops away (stairs down, drain, kerb)
 *  - floor looks NEARER than predicted   -> something is standing on it (wall, pole, box)
 *  - near points at head height with free space below -> head-height hazard (branch, awning)
 * Pure logic, unit-tested with synthetic scenes. See docs/ego-motion.md for the geometry notes.
 */
class DepthAnalyzer {
    /** Metric scale: metres = scale / disparity. NaN until the floor has been seen. */
    var scale = Float.NaN
        private set
    private val pending = ArrayList<Float>() // candidate floor scales before the ruler is trusted
    private var mismatch = 0 // flat floor frames that disagree with the trusted ruler

    /** The ground under my feet matched the trusted ruler in the last analysed depth frame. */
    var floorTrusted = false
        private set

    /** New lens or camera height: learn the floor ruler again (takes Settings.scaleLockFrames frames). */
    fun relearn() { scale = Float.NaN; pending.clear(); dropHits = 0; overheadHits = 0; lastDropM = Float.NaN; mismatch = 0 }
    private var dropHits = 0
    private var lastDropM = Float.NaN
    private var lastDropMs = 0L
    private var overheadHits = 0
    private var grid: Array<FloatArray>? = null

    private fun rowAngle(r: Int) = ((r + 0.5f) / DEPTH_ROWS - 0.5f) * Settings.vfovRad // + = below the optical axis
    private fun colAngle(c: Int) = ((c + 0.5f) / DEPTH_COLS - 0.5f) * Settings.hfovRad

    /** Optical-axis depth at which row [r] would hit a flat floor, or NaN if the ray doesn't reach the floor. */
    private fun floorZ(r: Int, pitch: Float): Float {
        val a = pitch + rowAngle(r) // angle below horizontal
        return if (a < Math.toRadians(3.0)) Float.NaN else Settings.cameraHeightM * cos(rowAngle(r)) / sin(a)
    }

    private fun median(v: List<Float>) = v.sorted().let { if (it.isEmpty()) Float.NaN else it[it.size / 2] }

    /**
     * [walking]: only then is the near ground assumed to be the floor you walk on, and only then are
     * hazards reported. Distances (metresIn) still work when standing, using the trusted ruler.
     */
    fun analyze(g: Array<FloatArray>, pitchDeg: Float, walking: Boolean = true, now: Long = 0L, speedMps: Float = 0f): Hazards {
        grid = g
        floorTrusted = false
        if (pitchDeg !in Settings.hazardPitchMinDeg..Settings.hazardPitchMaxDeg) return Hazards()
        val pitch = Math.toRadians(pitchDeg.toDouble()).toFloat()
        val band = (DEPTH_COLS * 35 / 100) until (DEPTH_COLS * 65 / 100) // walking corridor
        val rowMed = FloatArray(DEPTH_ROWS) { r -> median(band.map { g[r][it] }) }

        // 1. Scale from the near floor (0.8-2 m ahead, bottom of the image). Skip if it doesn't look flat.
        val samples = (DEPTH_ROWS * 6 / 10 until DEPTH_ROWS).mapNotNull { r ->
            val z = floorZ(r, pitch)
            if (z.isNaN() || z !in Settings.floorCalMinM..Settings.floorCalMaxM || rowMed[r] <= 0f) null else rowMed[r] * z
        }
        var floorOk = false
        if (!walking) {
            // Standing still: check the floor against the trusted ruler (drop-off can still see an edge ahead),
            // but never learn the ruler here: a table top in view would poison it.
            if (!scale.isNaN() && samples.size >= 3) {
                val near = median(samples.takeLast(3)) / scale
                floorTrusted = near in (1 / Settings.scaleTolerance)..Settings.scaleTolerance
            }
            return Hazards()
        }
        if (samples.size >= 3) {
            val m = median(samples)
            val flat = (samples.max() - samples.min()) / m < Settings.floorFlatness
            if (flat && scale.isNaN()) {
                // Trust the ruler only after several frames agree: one glance at a table top can't set it.
                pending += m
                if (pending.size > Settings.scaleLockFrames) pending.removeAt(0)
                val med = median(pending)
                if (pending.size == Settings.scaleLockFrames && pending.all { it / med in 0.8f..1.25f }) { scale = med; pending.clear() }
            } else if (!scale.isNaN()) {
                // The ground right under my feet (nearest rows) must match the trusted ruler: a table or bed
                // top is much nearer than the floor and fails this. A drop further ahead doesn't matter here.
                val near = median(samples.takeLast(3)) / scale
                floorOk = near in (1 / Settings.scaleTolerance)..Settings.scaleTolerance
                if (floorOk && flat) scale = scale * 0.9f + m * 0.1f
                // A flat floor that keeps disagreeing for many frames means the ruler is stale (e.g. the phone
                // was re-mounted), not that I'm standing on a table: relearn instead of staying blind forever.
                mismatch = if (!floorOk && flat) mismatch + 1 else 0
                if (mismatch >= Settings.scaleLockFrames * 4) relearn()
            }
        }
        floorTrusted = !scale.isNaN() && floorOk
        if (scale.isNaN() || !floorOk) return Hazards() // honest: no trusted floor this frame, no depth claims

        // 2. Drop-off: several independent cues must agree (see dropCandidate).
        val cand = dropCandidate(g, pitch)
        // 2d. Tracking: a real edge comes closer at my walking speed; depth noise jumps around.
        val dt = (now - lastDropMs) / 1000f
        val consistent = cand != null && !lastDropM.isNaN() &&
            kotlin.math.abs(cand.first - (lastDropM - speedMps * dt)) < Settings.dropTrackTolM
        dropHits = when {
            cand == null -> 0
            dropHits == 0 || consistent -> dropHits + 1
            else -> 1 // jumped: start over
        }
        lastDropM = cand?.first ?: Float.NaN
        lastDropMs = now

        // Floor obstacle: walk up the corridor median from near to far.
        var obstacle: Float? = null
        var nearRun = 0
        for (r in DEPTH_ROWS - 1 downTo 0) {
            val zf = floorZ(r, pitch)
            if (zf.isNaN() || zf > Settings.dropMaxM) break
            if (zf < Settings.dropMinM || rowMed[r] <= 0f) continue
            val ratio = (scale / rowMed[r]) / zf
            nearRun = if (ratio < 1 - Settings.obstacleRatio) nearRun + 1 else 0
            if (nearRun == Settings.hazardRows) { obstacle = scale / rowMed[r]; break }
        }

        // 3. Head height: a near point 1.2-2.1 m above the floor with nothing at body height beneath it.
        var overhead: Float? = null
        var bearingSum = 0f
        var cols = 0
        for (c in DEPTH_COLS / 4 until DEPTH_COLS * 3 / 4) {
            var head = Float.MAX_VALUE
            var body = Float.MAX_VALUE
            for (r in 0 until DEPTH_ROWS) {
                if (g[r][c] <= 0f) continue
                val range = scale / g[r][c] / cos(rowAngle(r)) // along the ray
                val a = pitch + rowAngle(r)
                val ahead = range * cos(a)
                val height = Settings.cameraHeightM - range * sin(a)
                if (height in Settings.headMinM..Settings.headMaxM) head = minOf(head, ahead)
                if (height in 0.3f..1.0f) body = minOf(body, ahead)
            }
            if (head < Settings.overheadMaxM && body > head + Settings.overheadGapM) {
                overhead = minOf(overhead ?: Float.MAX_VALUE, head); bearingSum += colAngle(c); cols++
            }
        }
        if (cols < 2) overhead = null
        overheadHits = if (overhead != null) overheadHits + 1 else 0

        return Hazards(
            dropAtM = cand?.first?.takeIf { dropHits >= Settings.depthHits },
            dropIsStep = cand?.second == true,
            overheadAtM = overhead?.takeIf { overheadHits >= Settings.depthHits },
            overheadBearing = if (cols > 0) bearingSum / cols else 0f,
            floorObstacleAtM = obstacle,
        )
    }

    /**
     * One frame's drop-off evidence, per column of the walking corridor, returning (distance, isStep) or null.
     *  a. the floor beyond some row looks farther than a flat floor would, for [Settings.hazardRows] rows;
     *  b. WIDTH: most corridor columns agree (a dark tile, shadow or bag strap covers only a few);
     *  c. LIP: the jump happens suddenly at one row (a real edge), not as a slow drift (depth-model error);
     * then: big drop (>45 % farther) needs a+b; a step or kerb (>8 %) needs a+b+c.
     */
    private fun dropCandidate(g: Array<FloatArray>, pitch: Float): Pair<Float, Boolean>? {
        val band = (DEPTH_COLS * 35 / 100) until (DEPTH_COLS * 65 / 100)
        val dists = ArrayList<Float>()
        val ratios = ArrayList<Float>()
        var lips = 0
        for (c in band) {
            var run = 0
            var start = -1
            var prevRatio = Float.NaN
            var lipHere = false
            for (r in DEPTH_ROWS - 1 downTo 0) {
                val zf = floorZ(r, pitch)
                if (zf.isNaN() || zf > Settings.dropMaxM) break
                if (zf < Settings.dropMinM || g[r][c] <= 0f) continue
                val ratio = (scale / g[r][c]) / zf
                if (ratio > 1 + Settings.stepRatio) {
                    if (run == 0) { start = r; lipHere = !prevRatio.isNaN() && ratio - prevRatio >= Settings.lipJump }
                    run++
                    if (run == Settings.hazardRows) {
                        dists += floorZ(start, pitch)
                        ratios += (0 until run).map { (scale / g[start - it][c]) / floorZ(start - it, pitch) }.sorted()[run / 2]
                        if (lipHere) lips++
                        break
                    }
                } else run = 0
                prevRatio = ratio
            }
        }
        if (dists.size < kotlin.math.ceil(band.count() * Settings.dropWidthFrac).toInt()) return null // b
        val ratio = median(ratios)
        val lip = lips * 2 >= dists.size
        val big = ratio > 1 + Settings.dropRatio
        if (!big && !lip) return null // small and gradual: depth-model drift, not an edge
        return median(dists) to !big
    }

    /** Median metric depth inside a box, or NaN. */
    fun metresIn(b: Box): Float {
        val g = grid ?: return Float.NaN
        if (scale.isNaN()) return Float.NaN
        // Centre half of the box, lower-middle part: that is the front object when boxes overlap,
        // and it avoids the background showing around the object's outline.
        val bh = b.bottom - b.top
        val bw = b.right - b.left
        val r0 = ((b.top + bh * 0.4f) * DEPTH_ROWS).toInt().coerceIn(0, DEPTH_ROWS - 1)
        val r1 = ((b.top + bh * 0.9f) * DEPTH_ROWS).toInt().coerceIn(r0, DEPTH_ROWS - 1)
        val c0 = ((b.left + bw * 0.25f) * DEPTH_COLS).toInt().coerceIn(0, DEPTH_COLS - 1)
        val c1 = ((b.right - bw * 0.25f) * DEPTH_COLS).toInt().coerceIn(c0, DEPTH_COLS - 1)
        val v = ArrayList<Float>()
        for (r in r0..r1) for (c in c0..c1) if (g[r][c] > 0f) v += g[r][c]
        // Top quartile of disparity = the nearest surface in that patch.
        val d = v.sorted().let { if (it.isEmpty()) return Float.NaN else it[it.size * 3 / 4] }
        return scale / d
    }

    /** Latest grid for the on-screen depth thumbnail. */
    fun latest() = grid
}

private val SURFACES = setOf("dining table", "bed", "couch", "bench", "desk", "chair", "toilet", "sink")

/**
 * Looking across a table or bed, its far edge looks exactly like a drop-off. If furniture covers the
 * bottom-centre of the view, the "floor" there is its top: drop-off and floor-obstacle claims are dropped.
 */
fun withoutFurnitureFloor(hz: Hazards, tracks: List<Track>): Hazards {
    val covered = tracks.any { t ->
        t.label in SURFACES && t.box.bottom > 0.7f && t.box.left < 0.6f && t.box.right > 0.4f
    }
    return if (covered) hz.copy(dropAtM = null, floorObstacleAtM = null) else hz
}
