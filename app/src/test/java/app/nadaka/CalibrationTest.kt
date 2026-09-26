package app.nadaka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTest {
    @Test fun parsesSpokenHeights() {
        assertEquals(1.70f, Calibration.parseHeightM("170")!!, 0.01f)
        assertEquals(1.70f, Calibration.parseHeightM("170 centimetres")!!, 0.01f)
        assertEquals(1.72f, Calibration.parseHeightM("1.72 metres")!!, 0.01f)
        assertEquals(1.73f, Calibration.parseHeightM("5 foot 8")!!, 0.01f)
        assertEquals(1.52f, Calibration.parseHeightM("5 feet")!!, 0.01f)
        assertNull(Calibration.parseHeightM("skip"))
        assertNull(Calibration.parseHeightM("hello"))
        assertNull(Calibration.parseHeightM("12"))
    }

    /** Height, steady tilt, then press-3 steps-press, press-10 steps-press. */
    @Test fun walksAreBracketedByPressesAndTheClockStartsAtThePress() {
        val c = Calibration(0)
        c.heard("170", 1000)
        assertTrue(c.frame(2000, -10f, 0, false)!!.contains("down")) // looking too level: coach
        c.frame(6000, 20f, 0, false); c.frame(8100, 20f, 0, false)
        assertEquals(Calibration.Step.WALK, c.step)
        assertTrue(!c.walking)

        // A long pause before pressing costs nothing: the walk clock hasn't started.
        c.frame(8100 + Settings.calWalkTimeoutMs + 5000, 20f, 0, false)
        assertEquals(Calibration.Step.WALK, c.step)

        var t = 60_000L
        assertEquals("Go.", c.press(t, 100))
        assertTrue(c.walking); assertTrue(c.consumeRulerReset())
        t += 1800; c.press(t, 103)                                   // stop after 3 steps
        c.frame(t + 100, 20f, 103, true)                             // too soon: sensor still reporting
        assertTrue(c.walking)
        c.frame(t + Settings.calStepLatencyMs, 20f, 103, true)       // walk 1 done: 3 sensed
        assertTrue(!c.walking)

        t += 5000; c.press(t, 103)
        t += 6000; c.press(t, 111)                                   // 10 actual, sensor saw 8
        val done = c.frame(t + Settings.calStepLatencyMs, 20f, 111, true)
        assertEquals(Calibration.DONE_PROMPT, done)
        assertEquals(13f / 11f, c.stepFactor, 0.01f)
    }

    @Test fun failsHonestlyWhenTheFloorNeverLocks() {
        val c = Calibration(0)
        c.press(10, 0)                     // skip height
        c.press(20, 0)                     // skip mount coaching
        c.press(30, 0); c.press(2000, 3); c.frame(2000 + Settings.calStepLatencyMs, 20f, 3, false)
        c.press(3000, 3); c.press(9000, 13)
        c.frame(9000 + Settings.calStepLatencyMs, 20f, 13, false)
        assertEquals(Calibration.Step.FAILED, c.step)
    }
}
