package app.nadaka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LensTest {
    @Test fun closeQuartersWhileWalkingGoesWide() {
        val l = LensPolicy()
        assertEquals(true, l.update(10_000, reading = false, walking = true, finding = false, nearestM = 1.5f, edgeNear = false))
    }

    @Test fun openSpaceGoesBackToMainAfterItStaysClear() {
        val l = LensPolicy()
        l.update(10_000, false, true, false, 1.5f, false) // wide
        assertNull(l.update(12_000, false, true, false, 6f, false)) // just cleared: wait
        assertEquals(false, l.update(16_500, false, true, false, 6f, false))
    }

    @Test fun readingNeedsTheMainLensImmediately() {
        val l = LensPolicy()
        l.update(10_000, false, true, false, 1f, false)
        assertEquals(false, l.update(10_100, reading = true, walking = true, finding = false, nearestM = 1f, edgeNear = false))
    }

    @Test fun noFlappingWithinTheDwellTime() {
        val l = LensPolicy()
        l.update(10_000, false, true, false, 1f, false) // wide
        assertNull(l.update(11_000, false, true, true, 1f, false))
        l.update(20_000, false, true, false, Float.NaN, false)
        assertNull(l.update(21_000, false, true, false, 1f, false)) // wants wide again but within dwell after switch... only if switched
    }

    @Test fun findingAndEdgeObjectsUseTheWideLens() {
        assertEquals(true, LensPolicy().update(10_000, false, false, finding = true, nearestM = Float.NaN, edgeNear = false))
        assertEquals(true, LensPolicy().update(10_000, false, true, false, Float.NaN, edgeNear = true))
    }

    @Test fun ultraWideFovIsWider() {
        val w = Math.toDegrees(zoomedFov(52f, 0.6f).toDouble())
        assertEquals(78.0, w, 2.0)
        assertEquals(52.0, Math.toDegrees(zoomedFov(52f, 1f).toDouble()), 0.01)
    }
}
