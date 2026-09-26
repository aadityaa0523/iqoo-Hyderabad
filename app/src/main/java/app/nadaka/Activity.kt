package app.nadaka

/**
 * What the user is doing, from the phone's own motion sensors. Each mode changes what is worth
 * saying: a walker needs hazard alerts; someone standing needs only "something is coming";
 * on a bus, the vehicle's lurching fakes drop-offs and "approaching" objects, so those are paused.
 */
enum class Activity(val spoken: String) {
    WALKING("Walking."),
    STILL("Standing. Quiet mode."),
    VEHICLE("In a vehicle. Hazard alerts paused."),
}

/**
 * Auto-detects [Activity] with a grace period so a pause at a kerb doesn't flip modes.
 * Steps always win: walking on a shaking platform is still walking. Pure logic, unit-tested.
 */
class ActivityDetector {
    var current = Activity.WALKING
        private set
    private var candidate = Activity.WALKING
    private var candidateSince = 0L

    /** [lastStepMs] = time of the last footstep; [vibration] = smoothed residual acceleration energy. Returns a mode change, or null. */
    fun update(now: Long, lastStepMs: Long, vibration: Float): Activity? {
        val guess = when {
            now - lastStepMs < Settings.walkingStepMs -> Activity.WALKING
            vibration >= Settings.vehicleVibration -> Activity.VEHICLE
            else -> Activity.STILL
        }
        if (guess != candidate) { candidate = guess; candidateSince = now }
        // Resuming walking is immediate (safety); calming down needs the grace period.
        val grace = if (guess == Activity.WALKING) 0L else Settings.activityGraceMs
        if (candidate != current && now - candidateSince >= grace) {
            current = candidate
            return current
        }
        return null
    }
}
