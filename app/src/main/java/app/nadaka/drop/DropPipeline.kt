package app.nadaka.drop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import app.nadaka.Health
import app.nadaka.TAG
import app.nadaka.Track
import java.io.File
import app.nadaka.drop.DropOffConfig as C

/** Everything one evaluation produced: state, the evidence behind it, and what the debug view draws. */
data class DropOutput(
    val state: DropState,
    val evidence: DropEvidence,
    val transition: DropTransition?,
    val haptic: DropHaptic,
    val dropAheadM: Float,              // distance to the candidate edge (floor ruler), NaN if unknown
    val candidate: EdgeCandidate?,
    val depthSamples: List<Pair<Float, Float>>,
    val groundRoiTop: Float,
    val possibleCount: Int,
    val strongCount: Int,
    val recoveryCount: Int,
    val history: List<EvidenceClass>,
    val baro: BaroStatus,
    val pathReason: String,
    val timingsMs: FloatArray,          // edge, depth, ground, fusion, total
)

/**
 * Layer 3 drop-off detection. Runs on the existing analysis thread at <= ~15 Hz ([C.EVAL_INTERVAL_MS]),
 * reusing the Depth Anything V2 output and YOLOX tracks already computed for the frame. Never touches the camera.
 */
class DropPipeline(hasBarometer: Boolean, private val logFile: File? = null) {
    private val edgeAnalyzer = EdgeAnalyzer()
    private val depthAnalyzer = DropDepthAnalyzer()
    private val groundAnalyzer = GroundPlaneAnalyzer()
    val barometer = BarometerAnalyzer(hasBarometer)
    private val history = DropEvidenceHistory()
    private val machine = DropStateMachine()
    private val haptics = DropHapticController()
    private var lastEvalMs = -1_000_000L

    // Reused downscale buffers (no large per-frame allocations).
    private val small by lazy { Bitmap.createBitmap(C.EDGE_IMG_W, C.EDGE_IMG_H, Bitmap.Config.ARGB_8888) }
    private val canvas by lazy { Canvas(small) }
    private val dst by lazy { Rect(0, 0, C.EDGE_IMG_W, C.EDGE_IMG_H) }
    private val paint by lazy { Paint(Paint.FILTER_BITMAP_FLAG) }
    private val px = IntArray(C.EDGE_IMG_W * C.EDGE_IMG_H)
    private val gray = FloatArray(px.size)
    private var logRows = 0

    val state get() = machine.state

    /** Returns null when throttled (not yet time for the next evaluation). */
    fun evaluate(now: Long, frame: Bitmap, depth: DepthInput?, pitchDeg: Float, tracks: List<Track>, health: Health): DropOutput? {
        if (now - lastEvalMs < C.EVAL_INTERVAL_MS) return null
        lastEvalMs = now
        canvas.drawBitmap(frame, null, dst, paint)
        small.getPixels(px, 0, C.EDGE_IMG_W, 0, 0, C.EDGE_IMG_W, C.EDGE_IMG_H)
        for (i in px.indices) { val p = px[i]; gray[i] = 0.299f * (p shr 16 and 255) + 0.587f * (p shr 8 and 255) + 0.114f * (p and 255) }
        return core(now, gray, depth, pitchDeg, tracks, health)
    }

    /** Pure core: grey image (EDGE_IMG_W x EDGE_IMG_H) + depth -> state. Used directly by the unit tests. */
    fun core(now: Long, gray: FloatArray, depth: DepthInput?, pitchDeg: Float, tracks: List<Track>, health: Health): DropOutput {
        val t0 = System.nanoTime()
        val geo = depth?.takeIf { !it.scale.isNaN() }?.let { FloorGeometry(it.scale, Math.toRadians(pitchDeg.toDouble()).toFloat()) }
        val cands = edgeAnalyzer.analyze(gray)
        val t1 = System.nanoTime()

        // Each candidate gets its own depth / ground / object evidence; the most convincing one is kept.
        var best: DropEvidence? = null
        var bestCand: EdgeCandidate? = null
        var bestDepth = DepthResult.UNRELIABLE
        var tDepth = 0L; var tGround = 0L; var tFuse = 0L
        for (c in cands) {
            val a = System.nanoTime()
            val d = depthAnalyzer.analyze(depth, geo, c)
            val b = System.nanoTime()
            val g = groundAnalyzer.analyze(depth, geo, c)
            val e = System.nanoTime()
            val ev = DropEvidenceFusion.fuse(now, c, d, g, ObjectSuppression.score(c, tracks), barometer)
            tDepth += b - a; tGround += e - b; tFuse += System.nanoTime() - e
            if (best == null || ev.evidenceClass > best.evidenceClass ||
                (ev.evidenceClass == best.evidenceClass && ev.confidence > best.confidence)) { best = ev; bestCand = c; bestDepth = d }
        }
        val evidence = best ?: DropEvidence.none(now, barometer.descentConfidence, barometer.descendingConfirmed)

        val sensorBlocked = health == Health.BLOCKED
        val pathReason = pathBlockedReason(depth, geo, health)
        if (!sensorBlocked && pathReason.isEmpty()) history.add(evidence)
        val transition = machine.update(now, history, sensorBlocked, pathReason.isNotEmpty(), barometer.descendingConfirmed)
        val haptic = haptics.update(now, machine.state, barometer.descendingConfirmed)
        val total = (System.nanoTime() - t0) / 1e6f
        val out = DropOutput(
            machine.state, evidence, transition, haptic,
            bestCand?.let { geo?.floorAheadM(it.y) } ?: Float.NaN, bestCand, bestDepth.samples,
            maxOf((bestCand?.y ?: 0.72f) + 0.04f, 0.6f),
            history.possibleCount(now), history.strongCount(now), machine.recoveryCount, history.classes(),
            barometer.status, pathReason,
            floatArrayOf((t1 - t0) / 1e6f, tDepth / 1e6f, tGround / 1e6f, tFuse / 1e6f, total),
        )
        log(out)
        return out
    }

    /** Forward traversal can't be judged: never reported as a drop. "" when the path is judgeable. */
    private fun pathBlockedReason(depth: DepthInput?, geo: FloorGeometry?, health: Health): String {
        if (health == Health.BLURRY) return "severe blur"
        if (depth != null && depth.ageMs <= C.DEPTH_MAX_AGE_MS) {
            val centre = patch(depth, 0.4f, 0.6f, 0.35f, 0.55f)
            val bottom = patch(depth, 0.4f, 0.6f, 0.85f, 0.95f)
            if (centre > 0f && bottom > 0f && centre / bottom >= C.WALL_RATIO) return "wall ahead" // no floor perspective at all
            if (geo != null && depth.floorTrusted && centre > 0f && geo.scale / centre < C.WALL_NEAR_M) return "wall very close"
        }
        // Blank image and no trusted depth to check the floor with: nothing to judge the path by.
        if (edgeAnalyzer.roiStd < C.FEATURELESS_STD && (depth == null || !depth.floorTrusted)) return "featureless view"
        return ""
    }

    private fun patch(depth: DepthInput, x0: Float, x1: Float, y0: Float, y1: Float): Float {
        val v = FloatArray(25); var n = 0
        for (i in 0 until 5) for (j in 0 until 5) {
            val d = depth.at(x0 + (x1 - x0) * i / 4f, y0 + (y1 - y0) * j / 4f)
            if (d > 0f && d.isFinite()) v[n++] = d
        }
        return if (n < 10) Float.NaN else medianOf(v, n)
    }

    /** Transitions always go to logcat; per-frame rows go to a CSV only when a log file was given (debug builds). */
    private fun log(o: DropOutput) {
        o.transition?.let { runCatching { Log.i(TAG, "drop: ${it.from} -> ${it.to}  edge=%.2f depth=${o.evidence.depthVerdict} conf=%.2f".format(o.evidence.edgeScore, o.evidence.confidence)) } } // runCatching: plain-JVM unit tests have no Log
        val f = logFile ?: return
        if (logRows++ > 20_000) return // cap the file
        runCatching {
            if (!f.exists()) f.writeText("t_ms,edge,depth_verdict,depth_conf,ground,object,baro,class,confidence,state,path,edge_ms,depth_ms,ground_ms,fusion_ms,total_ms\n")
            val e = o.evidence
            f.appendText("${e.timestamp},%.3f,${e.depthVerdict},%.3f,%.3f,%.3f,%.3f,${e.evidenceClass},%.3f,${o.state},${o.pathReason.replace(',', ' ')},%.2f,%.2f,%.2f,%.2f,%.2f\n".format(
                e.edgeScore, e.depthConfidence, e.groundPlaneScore, e.objectSuppressionScore, e.barometerScore, e.confidence,
                o.timingsMs[0], o.timingsMs[1], o.timingsMs[2], o.timingsMs[3], o.timingsMs[4]))
        }
    }
}
