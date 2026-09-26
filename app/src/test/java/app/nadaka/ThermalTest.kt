package app.nadaka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThermalTest {
    private val nan = Float.NaN

    /** Feeds [n] frames of [ms] each, 50 ms apart, starting at [t0]. Returns the last change. */
    private fun ThermalGovernor.feed(n: Int, ms: Long, t0: Long = 0, os: Int = 0, head: Float = nan, batt: Float = nan): HeatTier? {
        var change: HeatTier? = null
        for (i in 0 until n) update(t0 + i * 50L, os, head, batt, ms)?.let { change = it }
        return change
    }

    @Test fun slowColdStartIsNotOverheating() {
        val g = ThermalGovernor()
        assertNull(g.feed(Settings.heatWarmupFrames, 200)) // warm-up frames are slow, ignored
        assertEquals(HeatTier.NOMINAL, g.tier)
    }

    @Test fun learnsBaselineThenDetectsSlowdown() {
        val g = ThermalGovernor()
        g.feed(Settings.heatWarmupFrames + Settings.heatWindow * 2, 30) // cool: baseline 30 ms
        assertEquals(HeatTier.HOT, g.feed(Settings.heatWindow, 70, t0 = 100_000)) // > 2x baseline
    }

    @Test fun worstSignalWinsAndEscalatesImmediately() {
        val g = ThermalGovernor()
        assertEquals(HeatTier.WARM, g.update(0, 0, nan, 43f, 30))       // battery 43 C
        assertEquals(HeatTier.CRITICAL, g.update(50, 0, 0.99f, 43f, 30)) // headroom forecast
    }

    @Test fun coolsDownOnlyAfterSustainedCalmOneStepAtATime() {
        val g = ThermalGovernor()
        g.update(0, 3, nan, nan, 30) // OS says severe -> HOT
        assertNull(g.update(5_000, 0, nan, nan, 30))
        assertNull(g.update(Settings.heatCalmMs - 1, 0, nan, nan, 30))
        assertEquals(HeatTier.WARM, g.update(5_000 + Settings.heatCalmMs, 0, nan, nan, 30))
        assertEquals(HeatTier.NOMINAL, g.update(5_000 + 2 * Settings.heatCalmMs, 0, nan, nan, 30))
    }

    @Test fun briefThrottleCannotPoisonTheBaseline() {
        val g = ThermalGovernor()
        g.feed(Settings.heatWarmupFrames + Settings.heatWindow, 90, os = 2) // hot phase: not recorded
        g.feed(600, 30, t0 = 100_000)                                        // 30 s cool: baseline 30 ms, tier back to nominal
        assertEquals(HeatTier.WARM, g.feed(Settings.heatWindow, 50, t0 = 200_000)) // 1.67x
    }

    @Test fun safetyFloorNeverStops() {
        assertEquals(3, HeatTier.CRITICAL.detectEvery)
        assertEquals(6, HeatTier.CRITICAL.depthEvery)
    }
}
