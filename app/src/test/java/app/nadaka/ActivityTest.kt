package app.nadaka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityTest {
    @Test fun shortPauseAtKerbStaysWalking() {
        val d = ActivityDetector()
        for (t in 0L..5_000L step 500) assertNull(d.update(10_000 + t, lastStepMs = 10_000, vibration = 0.02f))
        assertEquals(Activity.WALKING, d.current)
    }

    @Test fun longStillnessBecomesStillThenOneStepResumesWalking() {
        val d = ActivityDetector()
        var change: Activity? = null
        for (t in 0L..20_000L step 500) d.update(10_000 + t, lastStepMs = 10_000, vibration = 0.02f)?.let { change = it }
        assertEquals(Activity.STILL, change)
        assertEquals(Activity.WALKING, d.update(31_000, lastStepMs = 31_000, vibration = 0.02f)) // immediate
    }

    @Test fun vibrationWithoutStepsIsVehicle() {
        val d = ActivityDetector()
        var change: Activity? = null
        for (t in 0L..20_000L step 500) d.update(10_000 + t, lastStepMs = 0, vibration = 0.6f)?.let { change = it }
        assertEquals(Activity.VEHICLE, change)
    }

    @Test fun stepsWinOverVibration() {
        val d = ActivityDetector()
        for (t in 0L..20_000L step 500) assertNull(d.update(10_000 + t, lastStepMs = 10_000 + t, vibration = 0.9f))
        assertEquals(Activity.WALKING, d.current)
    }
}
