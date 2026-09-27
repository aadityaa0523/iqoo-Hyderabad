package app.nadaka

/**
 * Chooses between the main lens (1x) and the ultra-wide (0.6x). CameraX zoom below 1x switches to the
 * ultra-wide inside the same camera session, so there is no restart and no lost frames.
 *
 * Why each lens:
 *  - 0.6x ultra-wide: close quarters. ~78 x 96 deg view sees the floor near your feet, head height and both
 *    sides at once: things at the edge of the path, a doorway, stairs right in front, searching for an object.
 *  - 1x main: far awareness and reading. Objects 5-10 m away and text get ~1.7x more pixels, so they are
 *    detected farther and read more reliably.
 * Switches are rate-limited (dwell) because every switch re-learns the depth ruler and restarts tracking.
 * Pure logic, unit-tested.
 */
class LensPolicy {
    var wide = false
        private set
    private var lastSwitchMs = -1_000_000L
    private var clearSinceMs = -1L

    /**
     * [nearestM]: nearest trusted object in view (NaN = none). [edgeNear]: something close at the frame edge
     * (half out of view). Returns the new choice when it changes, else null.
     */
    fun update(now: Long, reading: Boolean, walking: Boolean, finding: Boolean, nearestM: Float, edgeNear: Boolean): Boolean? {
        if (reading) return set(false, now, force = true) // text needs pixels: immediately
        val closeQuarters = finding || edgeNear || (!nearestM.isNaN() && nearestM < Settings.wideNearM)
        if (closeQuarters) clearSinceMs = -1 else if (clearSinceMs < 0) clearSinceMs = now
        val want = when {
            closeQuarters && walking || finding -> true
            clearSinceMs >= 0 && now - clearSinceMs >= Settings.lensClearMs -> false // open space: look far
            else -> wide
        }
        return set(want, now, force = false)
    }

    private fun set(want: Boolean, now: Long, force: Boolean): Boolean? {
        if (want == wide) return null
        if (!force && now - lastSwitchMs < Settings.lensDwellMs) return null
        wide = want
        lastSwitchMs = now
        return want
    }
}

/** Field of view after zoom: tan(half-angle) scales with 1/zoom (base = the 1x lens). */
fun zoomedFov(baseDeg: Float, zoom: Float): Float =
    Math.toRadians(2 * Math.toDegrees(kotlin.math.atan(kotlin.math.tan(Math.toRadians(baseDeg / 2.0)) / zoom))).toFloat()

/**
 * Torch for dark scenes. The camera can't see the room's own light while the torch is on (it measures the torch),
 * so every [Settings.torchProbeMs] the torch goes off for [Settings.torchSettleMs] to look: room lit -> stay off,
 * still dark -> back on. Turns on only after [Settings.torchOnMs] of real darkness. Pure logic, unit-tested.
 */
class TorchPolicy {
    var on = false          // the torch is wanted (it may be briefly off for a probe)
        private set
    private var darkSinceMs = -1L
    private var lastProbeMs = 0L
    private var probeStartMs = -1L

    /** One analysed frame's mean brightness. Returns the torch state to set now, or null for no change. */
    fun update(now: Long, luma: Float): Boolean? {
        if (!on) {
            if (luma >= Settings.darkLuma) { darkSinceMs = -1; return null }
            if (darkSinceMs < 0) darkSinceMs = now
            if (now - darkSinceMs < Settings.torchOnMs) return null
            on = true; lastProbeMs = now; darkSinceMs = -1
            return true
        }
        if (probeStartMs >= 0) { // torch off for a moment: is the room itself lit?
            if (now - probeStartMs < Settings.torchSettleMs) return null // let the exposure settle
            probeStartMs = -1; lastProbeMs = now
            if (luma >= Settings.torchAmbientLuma) { on = false; return null } // already off: stay off
            return true
        }
        if (now - lastProbeMs >= Settings.torchProbeMs) { probeStartMs = now; return false }
        return null
    }

    /** App left the screen: the camera closes, so forget everything. */
    fun reset() { on = false; darkSinceMs = -1; probeStartMs = -1 }
}
