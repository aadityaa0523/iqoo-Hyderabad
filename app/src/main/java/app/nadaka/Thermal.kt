package app.nadaka

/**
 * A phone strapped to a warm body with no airflow throttles within ~20 minutes, and a throttled
 * phone warns later. The governor keeps the safety loop fast by shedding everything else first.
 */
enum class HeatTier(val detectEvery: Int, val depthEvery: Int, val spoken: String) {
    NOMINAL(1, 2, "Cooled down. Back to full speed."),
    WARM(1, 3, "Phone is warm. Saving power, alerts slightly slower."),
    HOT(2, 4, "Phone is hot. Essential alerts only."),
    // Safety floor: even at critical, detection every 3rd frame and depth every 6th never stop.
    CRITICAL(3, 6, "Phone is very hot. Essential alerts only, please give it air."),
}

/**
 * Fuses four heat signals, worst one wins:
 *  1. Android thermal status (the OS verdict)   2. thermal headroom forecast (1.0 = throttling)
 *  3. battery temperature (skin proxy)           4. our own frame time vs this phone's learned cool speed
 * Escalates immediately; steps down one tier only after [Settings.heatCalmMs] of sustained calm.
 * Pure logic, unit-tested.
 */
class ThermalGovernor {
    var tier = HeatTier.NOMINAL
        private set
    private val times = ArrayDeque<Long>()
    private var frames = 0
    private var baseline = Long.MAX_VALUE // cool p90 frame time; only ever ratchets down
    private var calmSince = -1L

    /** Returns the new tier when it changes (to announce it), else null. [headroom]/[batteryC] may be NaN. */
    fun update(now: Long, osStatus: Int, headroom: Float, batteryC: Float, frameMs: Long): HeatTier? {
        frames++
        times.addLast(frameMs)
        if (times.size > Settings.heatWindow) times.removeFirst()

        val os = when {
            osStatus >= 4 -> HeatTier.CRITICAL // THERMAL_STATUS_CRITICAL and above
            osStatus == 3 -> HeatTier.HOT      // SEVERE
            osStatus == 2 -> HeatTier.WARM     // MODERATE
            else -> HeatTier.NOMINAL
        }
        val head = when {
            headroom.isNaN() -> HeatTier.NOMINAL
            headroom >= 0.98f -> HeatTier.CRITICAL
            headroom >= 0.93f -> HeatTier.HOT
            headroom >= 0.85f -> HeatTier.WARM
            else -> HeatTier.NOMINAL
        }
        val skin = when {
            batteryC.isNaN() -> HeatTier.NOMINAL
            batteryC >= 46f -> HeatTier.CRITICAL
            batteryC >= 44f -> HeatTier.HOT
            batteryC >= 42f -> HeatTier.WARM
            else -> HeatTier.NOMINAL
        }
        // Speed is only meaningful after the NPU has warmed up (first frames are slow for other reasons).
        var speed = HeatTier.NOMINAL
        if (frames > Settings.heatWarmupFrames && times.size == Settings.heatWindow) {
            val p90 = times.sorted()[times.size * 9 / 10]
            val othersCool = maxOf(os, head, skin) == HeatTier.NOMINAL
            if (othersCool && p90 < baseline) baseline = p90 // learned cool speed of THIS phone
            if (baseline != Long.MAX_VALUE) speed = when {
                p90 > baseline * 2 -> HeatTier.HOT
                p90 > baseline * 3 / 2 -> HeatTier.WARM
                else -> HeatTier.NOMINAL
            }
        }
        val worst = maxOf(os, head, skin, speed)

        if (worst > tier) { tier = worst; calmSince = -1; return tier } // get cautious fast
        if (worst < tier) {
            if (calmSince < 0) calmSince = now
            if (now - calmSince >= Settings.heatCalmMs) {
                tier = HeatTier.entries[tier.ordinal - 1]; calmSince = now // one step at a time
                return tier
            }
        } else calmSince = -1
        return null
    }
}
