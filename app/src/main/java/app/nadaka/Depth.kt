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
class DepthModel(ctx: Context) {
    private val interpreter: Interpreter
    val backend: String
    private val size: Int
    private val input: ByteBuffer
    private val output: ByteBuffer
    private val pixels: IntArray
    private val raw: FloatArray

    init {
        val fd = ctx.assets.openFd("depth.tflite")
        val model = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        val (i, b) = openInterpreter(ctx, model, fp16 = true)
        interpreter = i
        backend = b
        size = interpreter.getInputTensor(0).shape()[1]
        input = ByteBuffer.allocateDirect(size * size * 3 * 4).order(ByteOrder.nativeOrder())
        output = ByteBuffer.allocateDirect(interpreter.getOutputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
        pixels = IntArray(size * size)
        raw = FloatArray(size * size)
        Log.i(TAG, "depth on $backend, input ${size}x$size")
    }

    /** Disparity grid [row][col], rows top->bottom of the (upright) frame. */
    fun run(frame: Bitmap): Array<FloatArray> {
        Bitmap.createScaledBitmap(frame, size, size, true).getPixels(pixels, 0, size, 0, 0, size, size)
        input.rewind()
        val f = input.asFloatBuffer()
        for (p in pixels) {
            f.put((p shr 16 and 0xFF) / 255f); f.put((p shr 8 and 0xFF) / 255f); f.put((p and 0xFF) / 255f)
        }
        output.rewind()
        interpreter.run(input, output)
        output.rewind()
        output.asFloatBuffer().get(raw)
        return Array(DEPTH_ROWS) { r ->
            FloatArray(DEPTH_COLS) { c ->
                val y0 = r * size / DEPTH_ROWS; val y1 = (r + 1) * size / DEPTH_ROWS
                val x0 = c * size / DEPTH_COLS; val x1 = (c + 1) * size / DEPTH_COLS
                var s = 0f
                for (y in y0 until y1) for (x in x0 until x1) s += raw[y * size + x]
                s / ((y1 - y0) * (x1 - x0))
            }
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
    private var dropHits = 0
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

    fun analyze(g: Array<FloatArray>, pitchDeg: Float): Hazards {
        grid = g
        val pitch = Math.toRadians(pitchDeg.toDouble()).toFloat()
        val band = (DEPTH_COLS * 35 / 100) until (DEPTH_COLS * 65 / 100) // walking corridor
        val rowMed = FloatArray(DEPTH_ROWS) { r -> median(band.map { g[r][it] }) }

        // 1. Scale from the near floor (0.8-2 m ahead, bottom of the image). Skip if it doesn't look flat.
        val samples = (DEPTH_ROWS * 6 / 10 until DEPTH_ROWS).mapNotNull { r ->
            val z = floorZ(r, pitch)
            if (z.isNaN() || z !in Settings.floorCalMinM..Settings.floorCalMaxM || rowMed[r] <= 0f) null else rowMed[r] * z
        }
        if (samples.size >= 3) {
            val m = median(samples)
            val flat = (samples.max() - samples.min()) / m < Settings.floorFlatness
            if (flat) scale = if (scale.isNaN()) m else scale * 0.8f + m * 0.2f
        }
        if (scale.isNaN()) return Hazards() // honest: no ruler yet, no depth claims

        // 2. Drop-off / floor obstacle: walk up the corridor from near to far.
        var drop: Float? = null
        var obstacle: Float? = null
        var farRun = 0
        var nearRun = 0
        for (r in DEPTH_ROWS - 1 downTo 0) {
            val zf = floorZ(r, pitch)
            if (zf.isNaN() || zf > Settings.dropMaxM) break
            if (zf < Settings.dropMinM || rowMed[r] <= 0f) continue
            val ratio = (scale / rowMed[r]) / zf
            farRun = if (ratio > 1 + Settings.dropRatio) farRun + 1 else 0
            nearRun = if (ratio < 1 - Settings.obstacleRatio) nearRun + 1 else 0
            if (farRun == Settings.hazardRows && drop == null) drop = zf
            if (nearRun == Settings.hazardRows && obstacle == null) obstacle = scale / rowMed[r]
            if (drop != null || obstacle != null) break // the nearest one matters
        }
        dropHits = if (drop != null) dropHits + 1 else 0

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
            dropAtM = drop?.takeIf { dropHits >= Settings.depthHits },
            overheadAtM = overhead?.takeIf { overheadHits >= Settings.depthHits },
            overheadBearing = if (cols > 0) bearingSum / cols else 0f,
            floorObstacleAtM = obstacle,
        )
    }

    /** Median metric depth inside a box, or NaN. */
    fun metresIn(b: Box): Float {
        val g = grid ?: return Float.NaN
        if (scale.isNaN()) return Float.NaN
        val r0 = (b.top * DEPTH_ROWS).toInt().coerceIn(0, DEPTH_ROWS - 1); val r1 = (b.bottom * DEPTH_ROWS).toInt().coerceIn(r0, DEPTH_ROWS - 1)
        val c0 = (b.left * DEPTH_COLS).toInt().coerceIn(0, DEPTH_COLS - 1); val c1 = (b.right * DEPTH_COLS).toInt().coerceIn(c0, DEPTH_COLS - 1)
        val v = ArrayList<Float>()
        for (r in r0..r1) for (c in c0..c1) if (g[r][c] > 0f) v += g[r][c]
        // Top quartile of disparity = the object's near surface, not the background around it.
        val d = v.sorted().let { if (it.isEmpty()) return Float.NaN else it[it.size * 3 / 4] }
        return scale / d
    }

    /** Latest grid for the on-screen depth thumbnail. */
    fun latest() = grid
}

