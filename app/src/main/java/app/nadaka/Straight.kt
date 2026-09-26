package app.nadaka

import kotlin.math.abs

/** What the walk-straight assistant wants to tell the user. */
enum class Veer { DRIFT_LEFT, DRIFT_RIGHT, BACK_ON_LINE, RELOCKED, DONE }

/**
 * Walk straight across open space (a road crossing, a hall, a park) where there is nothing to follow and
 * people veer without noticing. Locks the heading when asked; cues a drift that lasts, confirms the return,
 * re-locks after a deliberate turn, ends after [Settings.veerMaxMs]. Pure logic, unit-tested.
 */
class StraightLine(startHeadingDeg: Float, private val startMs: Long) {
    var lockedDeg = startHeadingDeg
        private set
    private var side = 0            // -1 left of the line, +1 right, 0 on it
    private var sideSinceMs = startMs
    private var cued = 0            // the side last cued (0 = none)
    private var lastCueMs = -1_000_000L
    private var turnSinceMs = -1L

    /** [headingDeg]: compass-like heading of the camera, clockwise. Returns a cue to give now, or null. */
    fun update(now: Long, headingDeg: Float): Veer? {
        if (now - startMs > Settings.veerMaxMs) return Veer.DONE
        val d = wrap(headingDeg - lockedDeg)
        // A big, lasting change of direction is a deliberate turn (a corner): follow the new line.
        if (abs(d) > Settings.veerTurnDeg) {
            if (turnSinceMs < 0) turnSinceMs = now
            if (now - turnSinceMs >= Settings.veerTurnMs) { lockedDeg = headingDeg; reset(now); return Veer.RELOCKED }
            return null
        }
        turnSinceMs = -1
        val s = when {
            d > Settings.veerDeg -> 1
            d < -Settings.veerDeg -> -1
            abs(d) < Settings.veerDeg / 2 -> 0
            else -> side // in between: hysteresis, keep the last state
        }
        if (s != side) { side = s; sideSinceMs = now }
        if (now - sideSinceMs < Settings.veerHoldMs) return null
        if (side == 0) {
            if (cued != 0) { cued = 0; return Veer.BACK_ON_LINE }
            return null
        }
        if (side == cued && now - lastCueMs < Settings.veerRepeatMs) return null
        cued = side; lastCueMs = now
        return if (side > 0) Veer.DRIFT_RIGHT else Veer.DRIFT_LEFT
    }

    private fun reset(now: Long) { side = 0; sideSinceMs = now; cued = 0; turnSinceMs = -1 }

    companion object {
        fun wrap(d: Float): Float { var x = d % 360f; if (x > 180f) x -= 360f; if (x < -180f) x += 360f; return x }

        fun words(v: Veer) = when (v) {
            Veer.DRIFT_LEFT -> "Drifting left. Turn right a little."
            Veer.DRIFT_RIGHT -> "Drifting right. Turn left a little."
            Veer.BACK_ON_LINE -> "Straight."
            Veer.RELOCKED -> "New direction. Keeping you straight."
            Veer.DONE -> "Walk straight off."
        }
    }
}
