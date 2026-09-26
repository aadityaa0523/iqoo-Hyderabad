package app.nadaka.drop

import app.nadaka.Settings
import kotlin.math.cos
import kotlin.math.sin

enum class DepthVerdict { SUPPORTS, CONTRADICTS, UNRELIABLE }

enum class EvidenceClass { NONE, WEAK_PRESENT, PRESENT, STRONG }

enum class DropState { SAFE, POSSIBLE_DROP, CONFIRMED_DROP, SENSOR_BLOCKED, PATH_NOT_TRAVERSABLE }

enum class BaroStatus { UNAVAILABLE, STABLE, DESCENDING, ASCENDING }

/** A horizontal edge in the lower view. Coordinates normalised to the frame. */
data class EdgeCandidate(
    val y: Float, val x0: Float, val x1: Float,
    val score: Float, val strength: Float, val continuity: Float, val texture: Float,
)

data class DepthResult(
    val verdict: DepthVerdict,
    val confidence: Float,
    val jump: Float = Float.NaN,          // near ratio - far ratio (positive = far side lower/farther)
    val validRatio: Float = 0f,
    val nearRatio: Float = Float.NaN,
    val farRatio: Float = Float.NaN,
    val samples: List<Pair<Float, Float>> = emptyList(), // (x, y) of sample points, for the debug overlay
) {
    companion object { val UNRELIABLE = DepthResult(DepthVerdict.UNRELIABLE, 0f) }
}

data class GroundResult(val score: Float, val reliable: Boolean, val breakAway: Boolean, val residualM: Float = Float.NaN, val belowM: Float = Float.NaN) {
    companion object { val UNRELIABLE = GroundResult(0f, false, false) }
}

/**
 * Evidence for ONE evaluated frame. Not a decision: DropStateMachine decides from the history.
 */
data class DropEvidence(
    val timestamp: Long,
    val edgeScore: Float,
    val depthVerdict: DepthVerdict,
    val depthConfidence: Float,
    val groundPlaneScore: Float,
    val objectSuppressionScore: Float,
    val barometerScore: Float,
    val candidateEdgeX: Float,
    val candidateEdgeY: Float,
    val edgeDetected: Boolean,
    val groundPlaneBreak: Boolean,
    val objectOnEdge: Boolean,
    val descendingConfirmed: Boolean,
    val evidenceClass: EvidenceClass,
    val confidence: Float,
) {
    companion object {
        fun none(t: Long, baro: Float = 0f, descending: Boolean = false) = DropEvidence(
            t, 0f, DepthVerdict.UNRELIABLE, 0f, 0f, 0f, baro, Float.NaN, Float.NaN,
            false, false, false, descending, EvidenceClass.NONE, 0f,
        )
    }
}

/**
 * The floor as a ruler (same model as the existing DepthAnalyzer): a camera [Settings.cameraHeightM] above a
 * flat floor, pitched [pitch] rad down. Relative disparity d relates to optical-axis depth z by z = [scale] / d.
 */
class FloorGeometry(val scale: Float, val pitch: Float) {
    fun rowAngle(y: Float) = (y - 0.5f) * Settings.vfovRad // + = below the optical axis
    fun colAngle(x: Float) = (x - 0.5f) * Settings.hfovRad

    /** Optical-axis depth where row [y] meets a flat floor, NaN above the horizon. */
    fun floorZ(y: Float): Float {
        val ra = rowAngle(y)
        val a = pitch + ra
        return if (a < Math.toRadians(3.0)) Float.NaN else Settings.cameraHeightM * cos(ra) / sin(a)
    }

    fun expectedDisparity(y: Float) = floorZ(y).let { if (it.isNaN()) Float.NaN else scale / it }

    /** Horizontal distance ahead to where row [y] meets the floor. */
    fun floorAheadM(y: Float): Float {
        val z = floorZ(y)
        if (z.isNaN()) return Float.NaN
        val ra = rowAngle(y)
        return z / cos(ra) * cos(pitch + ra)
    }

    /** (ahead m, lateral m, height above floor m) of a depth sample, or null if invalid. */
    fun point(x: Float, y: Float, d: Float): FloatArray? {
        if (!(d > 0f) || !d.isFinite()) return null
        val z = scale / d
        val ra = rowAngle(y)
        val a = pitch + ra
        val range = z / cos(ra)
        return floatArrayOf(range * cos(a), z * kotlin.math.tan(colAngle(x)), Settings.cameraHeightM - range * sin(a))
    }
}

/** The existing Depth Anything V2 output, exposed before pooling. */
class DepthInput(val raw: FloatArray, val size: Int, val scale: Float, val floorTrusted: Boolean, val ageMs: Long) {
    fun at(x: Float, y: Float): Float {
        val c = (x * size).toInt().coerceIn(0, size - 1)
        val r = (y * size).toInt().coerceIn(0, size - 1)
        return raw[r * size + c]
    }
}

internal fun medianOf(v: FloatArray, n: Int): Float {
    if (n == 0) return Float.NaN
    val s = v.copyOf(n); s.sort(); return s[n / 2]
}

internal fun clamp01(v: Float) = v.coerceIn(0f, 1f)
