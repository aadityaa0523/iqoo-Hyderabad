package app.nadaka

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/** How a message should feel in the hand. */
enum class Buzz { SIDE, AHEAD, APPROACH, WARN }

data class Alert(val text: String, val buzz: Buzz)

/** Is the camera image usable at all? Checked before trusting any detection. */
enum class Health(val message: String) {
    OK(""),
    BLOCKED("Camera blocked. Use your cane."),
    DARK("Too dark, I can't see. Use your cane."),
    BLURRY("Camera is blurry. Wipe the lens."),
    TILTED("Straighten the phone."),
}

/** Mean luma (0..255) and Laplacian variance (sharpness) of a small ARGB image. */
fun frameStats(px: IntArray, w: Int, h: Int): Pair<Float, Float> {
    val g = FloatArray(px.size) { val p = px[it]; 0.299f * (p shr 16 and 255) + 0.587f * (p shr 8 and 255) + 0.114f * (p and 255) }
    val mean = g.average().toFloat()
    var sum = 0.0
    var sq = 0.0
    var n = 0
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
        val i = y * w + x
        val lap = 4 * g[i] - g[i - 1] - g[i + 1] - g[i - w] - g[i + w]
        sum += lap; sq += lap * lap; n++
    }
    val m = sum / n
    return mean to (sq / n - m * m).toFloat()
}

/** Camera pitch (+ = looking down) and roll in degrees from the gravity vector (device axes). */
fun tiltDegrees(gx: Float, gy: Float, gz: Float): Pair<Float, Float> =
    Math.toDegrees(atan2(gz, gy).toDouble()).toFloat() to Math.toDegrees(atan2(gx, gy).toDouble()).toFloat()

fun assess(luma: Float, sharpness: Float, pitch: Float, roll: Float): Health = when {
    luma < Settings.blockedLuma && sharpness < Settings.blurVar -> Health.BLOCKED // hand or pocket over the lens
    luma < Settings.darkLuma -> Health.DARK
    sharpness < Settings.blurVar -> Health.BLURRY
    pitch !in Settings.minPitchDeg..Settings.maxPitchDeg || abs(roll) > Settings.maxRollDeg -> Health.TILTED
    else -> Health.OK
}

/** Orientation-and-mobility style direction: 12 = straight ahead. */
fun clock(bearingRad: Float): String {
    val hour = (Math.toDegrees(bearingRad.toDouble()) / 30).roundToInt()
    return "${if (hour == 0) 12 else (12 + hour - 1) % 12 + 1} o'clock"
}

/** Depth-model hazards for this frame (Depth.kt). Null = not present. */
data class Hazards(
    val dropAtM: Float? = null,
    val overheadAtM: Float? = null,
    val overheadBearing: Float = 0f,
    val floorObstacleAtM: Float? = null,
)

/** "2.5 metres", "1 metre", "very close"; "" when unknown. */
fun metres(m: Float): String {
    if (m.isNaN()) return ""
    if (m < Settings.veryCloseM) return "very close"
    val r = kotlin.math.round(m * 2) / 2
    return when {
        r == 1f -> "1 metre"
        r % 1f == 0f -> "${r.toInt()} metres"
        else -> "$r metres"
    }
}

private fun phrase(vararg parts: String) = parts.filter { it.isNotEmpty() }.joinToString(", ") + "."

/**
 * Decides what to say each frame, most urgent first, up to [Settings.maxAlerts] messages:
 * unusable camera > drop-off > head-height > approaching > close by > crowd > new obstacle.
 * Approaching and close-by are separate channels, so both are announced when both happen.
 * Handles flicker (min hits), nagging (habituation per track), and crowds. Pure logic, unit-tested.
 */
class AlertPolicy {
    private var health = Health.OK
    private var healthSince = 0L
    private var healthSaid = Health.OK
    private var healthSaidMs = 0L
    private var lastInfoMs = -1_000_000L
    private var lastApproachMs = -1_000_000L
    private var lastCloseMs = -1_000_000L
    private var lastDropMs = -1_000_000L
    private var lastOverheadMs = -1_000_000L
    private var lastCrowdMs = -1_000_000L
    private val spokenHeight = HashMap<Int, Float>()

    /** True while the camera has been unusable long enough that detections are not trusted. */
    val blind get() = health != Health.OK

    fun decide(tracks: List<Track>, raw: Health, now: Long, hz: Hazards = Hazards()): List<Alert> {
        // Camera health, with persistence so one dark frame doesn't cry wolf.
        if (raw != health) { health = raw; healthSince = now }
        if (health != Health.OK && now - healthSince >= Settings.healthPersistMs) {
            if (healthSaid != health || now - healthSaidMs > Settings.healthRepeatMs) {
                healthSaid = health; healthSaidMs = now
                return listOf(Alert(health.message, Buzz.WARN))
            }
            return emptyList() // detections from a bad image are not trusted
        }
        if (health == Health.OK) healthSaid = Health.OK

        val out = ArrayList<Alert>()
        val stable = tracks.filter { it.hits >= Settings.minHits } // never trust one frame

        // Depth hazards: the things a cane can't find in time.
        hz.dropAtM?.let {
            if (now - lastDropMs >= Settings.hazardRepeatMs) { lastDropMs = now; out += Alert(phrase("Stop. Drop ahead", metres(it)), Buzz.WARN) }
        }
        hz.overheadAtM?.let {
            if (now - lastOverheadMs >= Settings.hazardRepeatMs) {
                lastOverheadMs = now
                out += Alert(phrase("Head height obstacle", metres(it), clock(hz.overheadBearing)), Buzz.WARN)
            }
        }

        // Approaching: my own walking already removed (ego-motion).
        val coming = stable.filter { it.approaching }.minByOrNull { it.ttc }
        if (coming != null && now - lastApproachMs >= Settings.approachCooldownMs) {
            lastApproachMs = now
            out += Alert(phrase("${coming.label} approaching", metres(coming.metres), clock(coming.bearing)), Buzz.APPROACH)
        }

        // Close by: repeats while close, even if already announced (it is a collision risk).
        val close = stable.filter { it !== coming && it.metres < Settings.closeM }.minByOrNull { it.metres }
        if (close != null && now - lastCloseMs >= Settings.closeRepeatMs) {
            lastCloseMs = now
            spokenHeight[close.id] = close.box.height()
            out += Alert(phrase("${close.label} close", metres(close.metres), clock(close.bearing)), Buzz.AHEAD)
        } else if (close == null && coming == null) {
            hz.floorObstacleAtM?.takeIf { it < Settings.closeM && now - lastCloseMs >= Settings.closeRepeatMs }?.let {
                lastCloseMs = now
                out += Alert(phrase("Obstacle ahead", metres(it)), Buzz.AHEAD) // something YOLO can't name (wall, pole)
            }
        }
        if (out.isNotEmpty()) return out.take(Settings.maxAlerts)

        // Calm information: crowd summary, or one new obstacle with its distance.
        if (now - lastInfoMs < Settings.speechGapMs) return out
        val crowd = stable.count { it.label == "person" } >= Settings.crowdCount
        if (crowd && now - lastCrowdMs > Settings.crowdRepeatMs) {
            lastCrowdMs = now; lastInfoMs = now
            return listOf(Alert("Crowd ahead.", Buzz.AHEAD))
        }
        val t = stable.filter { !(crowd && it.label == "person") }.maxByOrNull { it.box.height() } ?: return out
        val said = spokenHeight[t.id]
        if (said != null && t.box.height() < said * Settings.habituationGrowth) return out
        spokenHeight[t.id] = t.box.height()
        lastInfoMs = now
        val c = clock(t.bearing)
        return listOf(Alert(phrase(t.label, metres(t.metres), c), if (c == "12 o'clock") Buzz.AHEAD else Buzz.SIDE))
    }
}
