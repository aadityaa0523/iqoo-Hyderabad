package app.nadaka.ui

import app.nadaka.Health
import app.nadaka.HudState
import app.nadaka.Settings
import app.nadaka.Track
import app.nadaka.drop.DropState
import kotlin.math.abs

enum class Level { CALM, CAUTION, DANGER, INFO, ERROR }

/** Shape carries the meaning without colour: circle-check, triangle, octagon, ring, slashed circle, pause. */
enum class Glyph { CHECK, WARN, STOP, WAIT, OFF, PAUSE }

/**
 * The one thing the screen must say, derived from existing pipeline state. Pure: unit-tested.
 * [headline] is the biggest word, [subject] the hazard, [detail] one short sentence.
 */
data class Safety(
    val level: Level,
    val glyph: Glyph,
    val headline: String,
    val subject: String? = null,
    val detail: String? = null,
    val distanceM: Float = Float.NaN,
    val direction: String? = null,
    val status: String = "SAFETY ACTIVE",
    val statusOn: Boolean = true,
) {
    /** What TalkBack reads for the status card. */
    val spoken: String get() = buildString {
        append(when (level) {
            Level.DANGER -> "Warning. ${headline.lowercase().replaceFirstChar { it.uppercase() }}. ${subject.orEmpty().lowercase()}"
            Level.CAUTION -> "Caution. ${headline.lowercase()}"
            else -> "Safety status. ${headline.lowercase()}"
        })
        if (!distanceM.isNaN()) append(". Approximately ${metresWords(distanceM)} ${direction?.lowercase() ?: "ahead"}")
        detail?.let { append(". $it") }
    }
}

fun metresWords(m: Float) = "%.1f meters".format(m)
fun metresShort(m: Float) = if (m < 10f) "%.1f m".format(m) else "%.0f m".format(m)

/** LEFT / AHEAD / RIGHT from a track's bearing (radians, + = right). */
fun directionOf(bearingRad: Float) = when {
    bearingRad < -0.14f -> "LEFT"
    bearingRad > 0.14f -> "RIGHT"
    else -> "AHEAD"
}

/** Objects the user would be told about, nearest first. Mirrors the old HUD's filter. */
fun relevant(tracks: List<Track>) = tracks.filter {
    it.approaching || (!it.metres.isNaN() && it.metres < Settings.veryCloseM) ||
        (it.sure && !it.metres.isNaN() && it.metres <= if (it.moving) Settings.movingRangeM else Settings.staticRangeM)
}.sortedBy { app.nadaka.urgency(it) }

private fun inPath(t: Track) = abs(t.box.centerX() - 0.5f) < 0.22f

fun safetyOf(s: HudState): Safety {
    // A fall / siren outranks everything, even camera problems: it is about the person, not the path.
    when (s.alarm) {
        "FALL" -> return Safety(Level.DANGER, Glyph.STOP, "FALL DETECTED", null, "Press a volume key if you are OK.", status = "FALL")
        "SIREN" -> return Safety(Level.DANGER, Glyph.STOP, "EMERGENCY", "CALLING FOR HELP", "Press a volume key to stop the alarm.", status = "EMERGENCY")
    }
    s.cameraError?.let { return Safety(Level.ERROR, Glyph.OFF, "CAMERA OFF", null, "$it Use your cane.", status = "SAFETY OFF", statusOn = false) }
    s.error?.let { return Safety(Level.ERROR, Glyph.OFF, "SAFETY PAUSED", null, "Something went wrong. Use your cane.", status = "SAFETY OFF", statusOn = false) }
    if (s.said == "EMERGENCY") return Safety(Level.DANGER, Glyph.STOP, "EMERGENCY", "ALARM ON", "Press a volume key to stop.", status = "EMERGENCY")
    s.calibrating?.let { return Safety(Level.INFO, Glyph.WAIT, "CALIBRATING", null, it, status = "CALIBRATING") }
    if (s.loading) return Safety(Level.INFO, Glyph.WAIT, "STARTING", null, "Loading on-device AI.", status = "STARTING", statusOn = false)
    s.sensorError?.let { return Safety(Level.ERROR, Glyph.OFF, "SENSOR OFF", null, "$it Drop alerts are off. Use your cane.", status = "LIMITED", statusOn = false) }
    if (s.mode == "READ") return Safety(Level.INFO, Glyph.PAUSE, "READING", null, "Hold the text in front of the camera.", status = "READING")

    val drop = s.drop
    if (s.health != Health.OK || drop?.state == DropState.SENSOR_BLOCKED) {
        val why = when (s.health) {
            Health.DARK -> "Too dark."
            Health.BLURRY -> "Image is blurry. Hold steady."
            else -> "Camera is covered."
        }
        return Safety(Level.ERROR, Glyph.OFF, "CAN'T SEE", null, "$why Use your cane.", status = "CAN'T SEE", statusOn = false)
    }
    if (s.mode == "VEHICLE") return Safety(Level.INFO, Glyph.PAUSE, "PAUSED", null, "In a vehicle.", status = "PAUSED", statusOn = false)

    if (drop?.state == DropState.CONFIRMED_DROP)
        return Safety(Level.DANGER, Glyph.STOP, "STOP", "DROP AHEAD", null, drop.dropAheadM, "AHEAD")
    s.hazards.overheadAtM?.let {
        return Safety(Level.DANGER, Glyph.STOP, "STOP", "HEAD HEIGHT", "Something at head height.", it, directionOf(s.hazards.overheadBearing))
    }
    val coming = s.tracks.filter { it.approaching && it.hits >= Settings.minHits }.minByOrNull { app.nadaka.urgency(it) }
    if (coming != null && (coming.metres.isNaN() || coming.metres < Settings.closeM))
        return Safety(Level.CAUTION, Glyph.WARN, "CAUTION", "${coming.label.uppercase()} COMING", null, coming.metres, directionOf(coming.bearing))
    if (drop?.state == DropState.POSSIBLE_DROP)
        return Safety(Level.CAUTION, Glyph.WARN, "POSSIBLE DROP", null, "Step ahead. Check with your cane.", drop.dropAheadM, "AHEAD")
    drop?.stairsUpM?.takeIf { !it.isNaN() }?.let {
        return Safety(Level.CAUTION, Glyph.WARN, "STAIRS UP", null, "Steps going up ahead.", it, "AHEAD")
    }
    if (drop?.state == DropState.PATH_NOT_TRAVERSABLE) {
        val r = drop.pathReason
        return if ("wall" in r) Safety(Level.CAUTION, Glyph.WARN, "BLOCKED AHEAD", null, "Something is right in front of you.")
        else Safety(Level.CAUTION, Glyph.WARN, "CAN'T JUDGE PATH", null, "Use your cane.")
    }
    val obstacle = s.tracks.filter { it.sure && inPath(it) && !it.metres.isNaN() && it.metres < Settings.closeM }.minByOrNull { app.nadaka.urgency(it) }
    if (obstacle != null) return Safety(Level.CAUTION, Glyph.WARN, obstacle.label.uppercase(), null, "In your path.", obstacle.metres, directionOf(obstacle.bearing))
    s.hazards.waistAtM?.let {
        return Safety(Level.CAUTION, Glyph.WARN, "OBSTACLE", null, "Something at waist height.", it, "AHEAD")
    }
    s.hazards.floorObstacleAtM?.takeIf { it < Settings.closeM }?.let {
        return Safety(Level.CAUTION, Glyph.WARN, "OBSTACLE", null, "Low object in your path.", it, "AHEAD")
    }
    if (s.mode == "SITTING") return Safety(Level.INFO, Glyph.PAUSE, "RESTING", null, "Sitting. Alerts are quiet.", status = "SAFETY ACTIVE")
    return Safety(Level.CALM, Glyph.CHECK, "PATH CLEAR", null, "Nothing in your path.")
}
