package app.nadaka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPolicyTest {
    private fun track(
        id: Int, label: String = "chair", h: Float = 0.3f, cx: Float = 0.5f, hits: Int = 5,
        approaching: Boolean = false, metres: Float = 3f,
    ) = Track(id, label, Box(cx - 0.05f, 0.9f - h, cx + 0.05f, 0.9f), 0L).also {
        it.hits = hits; it.approaching = approaching; it.ttc = 2f; it.distance = metres
    }

    private fun AlertPolicy.say(tracks: List<Track>, now: Long, health: Health = Health.OK, hz: Hazards = Hazards()) =
        decide(tracks, health, now, hz).map { it.text }

    @Test fun oneFrameFlickerIsIgnored() {
        assertEquals(emptyList<String>(), AlertPolicy().say(listOf(track(1, hits = 1)), 0))
    }

    @Test fun distanceIsSpoken() {
        assertEquals(listOf("chair, 3 metres, 12 o'clock."), AlertPolicy().say(listOf(track(1, metres = 3.1f)), 0))
        assertEquals("very close", metres(0.4f))
        assertEquals("1 metre", metres(1.1f))
        assertEquals("2.5 metres", metres(2.4f))
    }

    @Test fun sameFarChairIsNotRepeatedUntilItIsMuchCloser() {
        val p = AlertPolicy()
        assertEquals(1, p.say(listOf(track(1, h = 0.3f)), 0).size)
        assertEquals(0, p.say(listOf(track(1, h = 0.32f)), 10_000).size)
        assertEquals(1, p.say(listOf(track(1, h = 0.45f)), 20_000).size)
    }

    @Test fun closeAndApproachingAreBothAnnounced() {
        val said = AlertPolicy().say(
            listOf(track(1, metres = 1.0f), track(2, "person", cx = 0.8f, approaching = true, metres = 4f)), 0,
        )
        assertEquals(listOf("person approaching, 4 metres, 1 o'clock.", "chair close, 1 metre, 12 o'clock."), said)
    }

    @Test fun closeObjectKeepsRepeatingWhileClose() {
        val p = AlertPolicy()
        val near = listOf(track(1, metres = 0.9f))
        assertEquals(1, p.say(near, 0).size)
        assertEquals(0, p.say(near, 500).size)
        assertEquals(1, p.say(near, Settings.closeRepeatMs).size)
    }

    @Test fun dropOffComesFirst() {
        val said = AlertPolicy().say(listOf(track(1, metres = 1f)), 0, hz = Hazards(dropAtM = 1.5f))
        assertEquals("Stop. Drop ahead, 1.5 metres.", said.first())
    }

    @Test fun headHeightHazard() {
        val said = AlertPolicy().say(emptyList(), 0, hz = Hazards(overheadAtM = 1.2f, overheadBearing = 0f))
        assertEquals(listOf("Head height obstacle, 1 metre, 12 o'clock."), said)
    }

    @Test fun unnamedFloorObstacleFromDepth() {
        assertEquals(listOf("Obstacle ahead, 1 metre."), AlertPolicy().say(emptyList(), 0, hz = Hazards(floorObstacleAtM = 1.1f)))
    }

    @Test fun crowdIsSummarised() {
        val people = (1..5).map { track(it, "person", cx = 0.1f + it * 0.15f) }
        assertEquals(listOf("Crowd ahead."), AlertPolicy().say(people, 0))
    }

    @Test fun darkMustPersistThenSilencesObjects() {
        val p = AlertPolicy()
        val chair = listOf(track(1))
        assertEquals(1, p.say(chair, 0, Health.DARK).size) // not yet persisted: still trusted
        assertEquals(listOf(Health.DARK.message), p.say(chair, Settings.healthPersistMs, Health.DARK))
        assertEquals(0, p.say(chair, Settings.healthPersistMs + 100, Health.DARK).size)
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
