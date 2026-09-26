package app.nadaka

import android.util.Log
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** Normalised 0..1 box. Plain class (not RectF) so tracking logic runs in JVM unit tests. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun width() = right - left
    fun height() = bottom - top
    fun centerX() = (left + right) / 2
    fun shifted(dx: Float) = Box(left + dx, top, right + dx, bottom)
}

fun iou(a: Box, b: Box): Float {
    val w = min(a.right, b.right) - max(a.left, b.left)
    val h = min(a.bottom, b.bottom) - max(a.top, b.top)
    if (w <= 0 || h <= 0) return 0f
    val inter = w * h
    return inter / (a.width() * a.height() + b.width() * b.height() - inter)
}

/** The user's own motion, from the IMU. */
data class Ego(val speed: Float, val yawRate: Float, val pitchRate: Float)

/** Rough real-world heights (m) for monocular distance. ponytail: fixed priors, calibrate per class if needed. */
private val HEIGHTS = mapOf(
    "person" to 1.7f, "bicycle" to 1.0f, "car" to 1.5f, "motorcycle" to 1.1f, "bus" to 3.0f, "truck" to 3.0f,
    "dog" to 0.5f, "cat" to 0.3f, "cow" to 1.4f, "horse" to 1.6f, "chair" to 0.9f, "bench" to 0.8f,
    "dining table" to 0.75f, "couch" to 0.8f, "bed" to 0.6f, "potted plant" to 0.6f, "suitcase" to 0.6f,
    "backpack" to 0.5f, "bottle" to 0.25f, "tv" to 0.5f, "laptop" to 0.25f, "refrigerator" to 1.7f,
)

class Track(val id: Int, val label: String, var box: Box, var seenMs: Long) {
    val heights = ArrayDeque<Pair<Long, Float>>()
    var growth = 0f            // 1/s, relative image growth ("looming")
    var ttc = Float.POSITIVE_INFINITY
    var distance = Float.NaN   // m, from class height (upper bound when the box is cut by the frame edge)
    var depthM = Float.NaN     // m, from the depth model when available (better than the class prior)
    /** Best distance estimate: depth model, else class-height prior. */
    val metres get() = if (!depthM.isNaN()) depthM else distance
    var closing = 0f           // m/s, how fast the gap shrinks
    var objSpeed = 0f          // m/s toward me after subtracting my own walking
    var approaching = false
    var hits = 0               // frames this object has been matched; flicker guard
    var score = 0f             // smoothed detector confidence
    var lateralMps = 0f        // sideways speed after removing my own turning
    val moving get() = objSpeed > Settings.movingMps || kotlin.math.abs(lateralMps) > Settings.movingMps

    /** Half-visible at the left/right edge: direction and size are unreliable. */
    val edge get() = box.left <= 0.02f || box.right >= 0.98f

    /** Depth-model distance and object-size distance roughly agree (a reflective surface breaks depth). */
    val consistent get() = depthM.isNaN() || distance.isNaN() || max(depthM, distance) / min(depthM, distance) < Settings.agreeRatio

    /**
     * SURE: seen steadily, confident, fully in view, depth and size agree. UNSURE objects are only
     * spoken when they matter (approaching, or practically touching).
     */
    val sure get() = hits >= Settings.sureHits && score >= Settings.sureScore && !edge && consistent
    var features = FloatArray(0)

    val bearing get() = (box.centerX() - 0.5f) * Settings.hfovRad
}

/** Feature order shared with training/train_ego.py and the CSV log. Do not reorder. */
val FEATURE_NAMES = listOf("growth", "closing", "ego_speed", "abs_yaw", "abs_pitch", "height", "off_center", "obj_speed", "ttc")

/**
 * Tracks YOLOX boxes across frames with gyro-compensated matching and estimates, per object,
 * time-to-contact and speed toward the user with the user's own motion removed.
 * Single-threaded (analysis thread).
 */
class Tracker(private val model: EgoModel? = null) {
    private var tracks = listOf<Track>()
    private var nextId = 0
    private var lastMs = 0L

    /** Returns the tracks seen this frame. */
    fun update(dets: List<Detection>, now: Long, ego: Ego): List<Track> {
        val dt = if (lastMs == 0L) 0f else (now - lastMs) / 1000f
        lastMs = now
        // Turning pans the camera; predict where old boxes moved in the image.
        val shift = Settings.yawSign * ego.yawRate * dt / Settings.hfovRad
        val free = tracks.toMutableList()
        val seen = ArrayList<Track>()
        for (d in dets.sortedByDescending { it.score }) {
            val match = free.filter { it.label == d.label }
                .maxByOrNull { iou(it.box.shifted(shift), d.box) }
                ?.takeIf { iou(it.box.shifted(shift), d.box) >= Settings.trackIou }
            val t = match?.also { free.remove(it) } ?: Track(nextId++, d.label, d.box, now)
            if (match != null && dt > 0) {
                val lateral = (d.box.centerX() - match.box.shifted(shift).centerX()) * Settings.hfovRad * t.metres / dt
                if (!lateral.isNaN()) t.lateralMps = 0.7f * t.lateralMps + 0.3f * lateral
            }
            t.score = if (t.hits == 0) d.score else 0.7f * t.score + 0.3f * d.score
            t.box = d.box
            t.seenMs = now
            t.hits++
            measure(t, now, ego)
            seen += t
        }
        // Briefly missed tracks survive a few frames so a flicker doesn't reset their history.
        tracks = seen + free.filter { now - it.seenMs < Settings.trackKeepMs }.map { it.apply { box = box.shifted(shift) } }
        return seen
    }

    private fun measure(t: Track, now: Long, ego: Ego) {
        val b = t.box
        // Cut by the frame edge: the true box is taller, so this is an upper bound ("at most this far").
        t.distance = (HEIGHTS[t.label] ?: 1f) / (b.height() * Settings.vfovRad)
        t.heights.addLast(now to b.height())
        while (now - t.heights.first().first > Settings.growthWindowMs) t.heights.removeFirst()
        val (t0, h0) = t.heights.first()
        val span = (now - t0) / 1000f
        if (span < Settings.minGrowthSpanS) return

        t.growth = ln(b.height() / h0) / span
        t.ttc = if (t.growth > 0.01f) 1 / t.growth else Float.POSITIVE_INFINITY
        val clipped = b.top <= 0.01f || b.bottom >= 0.99f
        // Distance change over the window (same class-height model at both ends): unbiased even when
        // the object closes in fast, unlike current-distance x average growth. A cut box is meaningless.
        t.closing = if (clipped) 0f else (HEIGHTS[t.label] ?: 1f) / Settings.vfovRad * (1 / h0 - 1 / b.height()) / span
        t.objSpeed = t.closing - ego.speed * cos(t.bearing) // my walking explains this much of the growth
        t.features = floatArrayOf(
            t.growth, t.closing, ego.speed, abs(ego.yawRate), abs(ego.pitchRate), b.height(),
            abs(b.centerX() - 0.5f), t.objSpeed, min(t.ttc, 10f),
        )
        t.approaching = model?.approaching(t.features)
            ?: (t.objSpeed > Settings.approachMps && t.ttc < Settings.approachTtcS)
    }
}

/** Logistic regression trained by training/train_ego.py (ego_model.json). */
class EgoModel(json: String) {
    private val mean: FloatArray
    private val std: FloatArray
    private val w: FloatArray
    private val b: Float
    private val threshold: Float

    init {
        val o = JSONObject(json)
        val names = o.getJSONArray("features").let { a -> List(a.length()) { a.getString(it) } }
        require(names == FEATURE_NAMES) { "ego_model.json features $names != app $FEATURE_NAMES" }
        fun arr(k: String) = o.getJSONArray(k).let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
        mean = arr("mean"); std = arr("std"); w = arr("w")
        b = o.getDouble("b").toFloat()
        threshold = o.optDouble("threshold", 0.5).toFloat()
    }

    fun probability(x: FloatArray): Float {
        var z = b
        for (i in x.indices) z += w[i] * (x[i] - mean[i]) / std[i]
        return 1 / (1 + exp(-z))
    }

    fun approaching(x: FloatArray) = probability(x) >= threshold

    companion object {
        fun loadOrNull(open: () -> String): EgoModel? = try {
            EgoModel(open()).also { Log.i(TAG, "ego model loaded") }
        } catch (e: Exception) {
            Log.i(TAG, "no ego model, using physics rule: ${e.message}")
            null
        }
    }
}
