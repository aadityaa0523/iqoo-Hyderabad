package app.nadaka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StraightBusTest {
    @Test fun driftIsCuedOnlyWhenItLasts() {
        val s = StraightLine(90f, 0)
        assertNull(s.update(100, 104f))            // 14 deg right, just now
        assertEquals(Veer.DRIFT_RIGHT, s.update(1200, 104f))
        assertNull(s.update(1500, 104f))           // not repeated right away
        assertEquals(Veer.DRIFT_RIGHT, s.update(5300, 104f))
        assertNull(s.update(5400, 91f))
        assertEquals(Veer.BACK_ON_LINE, s.update(6500, 91f))
        assertNull(s.update(6600, 85f))            // small wobble: nothing
    }

    @Test fun leftDriftAcrossNorthWraps() {
        val s = StraightLine(3f, 0)
        s.update(0, 349f)
        assertEquals(Veer.DRIFT_LEFT, s.update(1100, 349f)) // 3 -> 349 is 14 deg left, not 346 right
    }

    @Test fun deliberateTurnRelocksAndItEnds() {
        val s = StraightLine(0f, 0)
        s.update(0, 90f)
        assertEquals(Veer.RELOCKED, s.update(2100, 90f))
        assertEquals(90f, s.lockedDeg, 0.01f)
        assertEquals(Veer.DONE, s.update(Settings.veerMaxMs + 1, 90f))
    }

    @Test fun routeNumbersNotPlates() {
        assertEquals(listOf("218"), BusReader.routeNumbers("218 SECUNDERABAD"))
        assertEquals(listOf("10H"), BusReader.routeNumbers("10H KOTI"))
        assertEquals(listOf("5K/1"), BusReader.routeNumbers("5K/1 LB NAGAR"))
        assertEquals(emptyList<String>(), BusReader.routeNumbers("TS 09 UB 1234"))
        assertEquals(emptyList<String>(), BusReader.routeNumbers("TS09UB1234"))
    }
}
