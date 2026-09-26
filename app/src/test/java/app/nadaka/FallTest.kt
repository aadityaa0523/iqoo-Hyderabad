package app.nadaka

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sin

class FallTest {
    private val g = 9.81f
    private var t = 0L

    /** Feeds [ms] of a constant reading at 50 Hz; returns how many falls were reported. */
    private fun FallDetector.hold(ms: Long, x: Float, y: Float, z: Float): Int {
        var n = 0
        val end = t + ms
        while (t < end) { if (update(t, x, y, z)) n++; t += 20 }
        return n
    }

    private fun fallSequence(d: FallDetector, endX: Float, endY: Float, endZ: Float): Int =
        d.hold(2000, 0f, g, 0f) +          // carried upright
            d.hold(420, 0f, 2f, 0f) +      // free fall, ~0.7 m
            d.hold(40, 0f, 30f, 5f) +      // impact
            d.hold(300, 5f, 12f, 8f) +     // bounce
            d.hold(3000, endX, endY, endZ) // lying still

    @Test fun fallIsDetectedOnce() = assertEquals(1, fallSequence(FallDetector(), 0f, 0f, g))

    @Test fun jumpLandingUprightIsNotAFall() = assertEquals(0, fallSequence(FallDetector(), 0f, g, 0f))

    @Test fun walkingIsNotAFall() {
        val d = FallDetector()
        var n = 0
        repeat(1000) { i -> if (d.update(t, 0f, g * (1f + 0.45f * sin(i * 0.6f)), 0f)) n++; t += 20 }
        assertEquals(0, n)
    }

    @Test fun stumbleWithoutImpactIsNotAFall() {
        val d = FallDetector()
        assertEquals(0, d.hold(2000, 0f, g, 0f) + d.hold(200, 0f, 2f, 0f) + d.hold(3000, 0f, 0f, g))
    }

    @Test fun shortDropIsNotAFall() { // ~20 cm: onto a sofa from the hand, then lying still
        val d = FallDetector()
        assertEquals(0, d.hold(2000, 0f, g, 0f) + d.hold(200, 0f, 1f, 0f) + d.hold(40, 0f, 30f, 5f) + d.hold(3000, 0f, 0f, g))
    }
}
