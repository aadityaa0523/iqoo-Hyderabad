package app.nadaka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticsTest {
    @Test fun parkingSensorFasterWhenCloser() {
        assertNull(pulseIntervalMs(Float.NaN))
        assertNull(pulseIntervalMs(4f)) // beyond 3 m: silence
        val far = pulseIntervalMs(3f)!!
        val mid = pulseIntervalMs(2f)!!
        val near = pulseIntervalMs(1f)!!
        assertTrue(far > mid && mid > near)
        assertEquals(1000L, far)
        assertEquals(250L, near)
        assertEquals(120L, pulseIntervalMs(0.5f)) // about to touch: continuous
    }

    @Test fun threeIntensityLevelsOnly() {
        assertEquals(setOf(0.4f, 0.7f, 1f), listOf(2.5f, 1.2f, 0.5f).map { pulseAmplitude(it) }.toSet())
    }

    @Test fun hazardsSpeakShortWordsAndApproachingIsVibrationOnly() {
        val track = Track(1, "person", Box(0.75f, 0.3f, 0.85f, 0.9f), 0).also {
            it.hits = 9; it.score = 0.9f; it.approaching = true; it.ttc = 2f; it.distance = 4f
        }
        val alerts = AlertPolicy().decide(listOf(track), Health.OK, 0, Hazards(dropAtM = 1.5f))
        val drop = alerts.first()
        assertEquals(Tacton.DROP, drop.tacton)
        assertEquals("Stop. Drop.", drop.short)
        val coming = alerts.first { it.tacton == Tacton.APPROACH }
        assertNull(coming.short) // felt, not heard
    }

    @Test fun cameraProblemIsFeltAndHeard() {
        val p = AlertPolicy()
        p.decide(emptyList(), Health.BLOCKED, 0)
        val a = p.decide(emptyList(), Health.BLOCKED, Settings.healthPersistMs).single()
        assertEquals(Tacton.CANT_SEE, a.tacton)
        assertEquals(Health.BLOCKED.message, a.short)
    }

    @Test fun voiceCommandsForFeedbackMode() {
        assertEquals(Ask.LEARN, intentOf("teach me the vibrations"))
        assertEquals(Ask.SPEECH, intentOf("use speech"))
        assertEquals(Ask.HAPTIC, intentOf("use vibration"))
        assertEquals(Ask.SAFETY, intentOf("teach me, can I cross")) // safety still wins
    }

    @Test fun lessonCoversEveryPattern() {
        assertEquals(Tacton.entries.toSet(), LESSON.map { it.second }.toSet())
    }
}
