package app.nadaka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerTest {
    /** Box of an object of real height [h] m at [d] m, centred at x=[cx], feet at y=0.9. */
    private fun det(label: String, h: Float, d: Float, cx: Float = 0.5f): Detection {
        val bh = h / (d * Settings.vfovRad)
        val bw = bh * 0.4f
        return Detection(label, 0.9f, Box(cx - bw / 2, 0.9f - bh, cx + bw / 2, 0.9f))
    }

    /** Runs 1.5 s at 20 fps with gap closing at [closing] m/s from [start] m. */
    private fun run(label: String, h: Float, start: Float, closing: Float, ego: Ego): Track {
        val tr = Tracker()
        var last: List<Track> = emptyList()
        for (i in 0..30) {
            val t = i * 50L
            last = tr.update(listOf(det(label, h, start - closing * t / 1000f)), 1000 + t, ego)
        }
        return last.single()
    }

    @Test fun personWalkingAtStandingUserIsApproaching() {
        val t = run("person", 1.7f, 6f, 1.2f, Ego(speed = 0f, yawRate = 0f, pitchRate = 0f))
        assertTrue(t.approaching)
        assertEquals(1.2f, t.objSpeed, 0.2f)
    }

    @Test fun staticChairWhileIWalkIsNotApproaching() {
        val t = run("chair", 0.9f, 5f, 1.0f, Ego(speed = 1.0f, yawRate = 0f, pitchRate = 0f))
        assertFalse(t.approaching)
        assertEquals(0f, t.objSpeed, 0.2f) // growth fully explained by my own walking
    }

    @Test fun turningKeepsTheSameTrackThanksToGyroCompensation() {
        val tr = Tracker()
        val yaw = 1.5f // rad/s, a quick body turn
        val ids = (0..10).map { i ->
            val cx = 0.2f + Settings.yawSign * yaw * (i * 0.05f) / Settings.hfovRad // scene slides with the turn
            tr.update(listOf(det("chair", 0.9f, 3f, cx)), 1000L + i * 50, Ego(0f, yaw, 0f)).single().id
        }
        assertEquals(setOf(0), ids.toSet())
    }
}
