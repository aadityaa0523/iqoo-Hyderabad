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

/**
 * Picks at most ONE thing to say per frame, in priority order:
 * unusable camera > something approaching > crowd > nearest new obstacle.
 * Handles flicker (min hits), nagging (habituation per track), and crowds.
 * Pure logic, single-threaded, unit-tested.
 */
class AlertPolicy {
    private var health = Health.OK
    private var healthSince = 0L
    private var healthSaid = Health.OK
    private var healthSaidMs = 0L
    private var lastSpeakMs = -1_000_000L
    private var lastApproachMs = -1_000_000L
    private var lastCrowdMs = -1_000_000L
    private val spokenHeight = HashMap<Int, Float>()

    /** True while the camera has been unusable long enough that detections are not trusted. */
    val blind get() = health != Health.OK

    fun decide(tracks: List<Track>, raw: Health, now: Long): Alert? {
        // 1. Camera health, with persistence so one dark frame doesn't cry wolf.
        if (raw != health) { health = raw; healthSince = now }
        if (health != Health.OK && now - healthSince >= Settings.healthPersistMs) {
            if (healthSaid != health || now - healthSaidMs > Settings.healthRepeatMs) {
                healthSaid = health; healthSaidMs = now
                return Alert(health.message, Buzz.WARN)
            }
            return null // detections from a bad image are not trusted
        }
        if (health == Health.OK) healthSaid = Health.OK

        val stable = tracks.filter { it.hits >= Settings.minHits } // never trust one frame

        // 2. Something coming at me, with my own walking already removed (ego-motion).
        stable.filter { it.approaching }.minByOrNull { it.ttc }?.let {
            if (now - lastApproachMs < Settings.approachCooldownMs) return null
            lastApproachMs = now; lastSpeakMs = now
            return Alert("${it.label} approaching, ${clock(it.bearing)}", Buzz.APPROACH)
        }

        // 3. Crowd: one summary instead of "person, person, person".
        val crowd = stable.count { it.label == "person" } >= Settings.crowdCount
        if (crowd && now - lastCrowdMs > Settings.crowdRepeatMs) {
            lastCrowdMs = now; lastSpeakMs = now
            return Alert("Crowd ahead.", Buzz.AHEAD)
        }

        // 4. Nearest obstacle, once per object unless it gets much closer (habituation).
        val t = stable.filter { !(crowd && it.label == "person") }.maxByOrNull { it.box.height() } ?: return null
        val said = spokenHeight[t.id]
        if (said != null && t.box.height() < said * Settings.habituationGrowth) return null
        if (now - lastSpeakMs < Settings.speechGapMs) return null
        spokenHeight[t.id] = t.box.height()
        lastSpeakMs = now
        val c = clock(t.bearing)
        return Alert("${t.label}, $c", if (c == "12 o'clock") Buzz.AHEAD else Buzz.SIDE)
    }
}
