package app.nadaka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPolicyTest {
    private fun track(id: Int, label: String = "chair", h: Float = 0.3f, cx: Float = 0.5f, hits: Int = 5, approaching: Boolean = false) =
        Track(id, label, Box(cx - 0.05f, 0.9f - h, cx + 0.05f, 0.9f), 0L).also {
            it.hits = hits; it.approaching = approaching; it.ttc = 2f
        }

    @Test fun oneFrameFlickerIsIgnored() {
        assertNull(AlertPolicy().decide(listOf(track(1, hits = 1)), Health.OK, 0))
    }

    @Test fun sameChairIsNotRepeatedUntilItIsMuchCloser() {
        val p = AlertPolicy()
        assertEquals("chair, 12 o'clock", p.decide(listOf(track(1, h = 0.3f)), Health.OK, 0)?.text)
        assertNull(p.decide(listOf(track(1, h = 0.32f)), Health.OK, 10_000))
        assertEquals("chair, 12 o'clock", p.decide(listOf(track(1, h = 0.45f)), Health.OK, 20_000)?.text)
    }

    @Test fun approachingBeatsNearestObstacle() {
        val a = AlertPolicy().decide(listOf(track(1, h = 0.6f), track(2, "person", h = 0.2f, cx = 0.8f, approaching = true)), Health.OK, 0)
        assertEquals(Buzz.APPROACH, a?.buzz)
        assertTrue(a!!.text.startsWith("person approaching"))
    }

    @Test fun crowdIsSummarised() {
        val people = (1..5).map { track(it, "person", cx = 0.1f + it * 0.15f) }
        assertEquals("Crowd ahead.", AlertPolicy().decide(people, Health.OK, 0)?.text)
    }

    @Test fun darkMustPersistThenSilencesObjects() {
        val p = AlertPolicy()
        val chair = listOf(track(1))
        assertEquals("chair, 12 o'clock", p.decide(chair, Health.DARK, 0)?.text) // not yet persisted: still trusted
        assertEquals(Health.DARK.message, p.decide(chair, Health.DARK, Settings.healthPersistMs)?.text)
        assertNull(p.decide(chair, Health.DARK, Settings.healthPersistMs + 100)) // no object cues from a dark image
        assertTrue(p.blind)
    }

    @Test fun cameraHealthFromPixels() {
        val black = IntArray(64 * 48)
        val (l0, s0) = frameStats(black, 64, 48)
        assertEquals(Health.BLOCKED, assess(l0, s0, 10f, 0f))
        val checker = IntArray(64 * 48) { if ((it % 64 + it / 64) % 2 == 0) 0xFFFFFF else 0x202020 }
        val (l1, s1) = frameStats(checker, 64, 48)
        assertEquals(Health.OK, assess(l1, s1, 10f, 0f))
        assertEquals(Health.TILTED, assess(l1, s1, 80f, 0f)) // camera pointing at the floor
        assertEquals(Health.TILTED, assess(l1, s1, 10f, 45f))
    }

    @Test fun clockFace() {
        assertEquals("12 o'clock", clock(0f))
        assertEquals("1 o'clock", clock(Math.toRadians(30.0).toFloat()))
        assertEquals("11 o'clock", clock(Math.toRadians(-30.0).toFloat()))
    }
}
