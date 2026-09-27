package app.nadaka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticsTest {
    @Test fun parkingSensorFasterWhenCloser() {
        assertNull(pulseIntervalMs(Float.NaN))
        assertNull(pulseIntervalMs(3f)) // beyond 2.5 m: silence
        val far = pulseIntervalMs(Settings.pulseMaxM)!!
        val mid = pulseIntervalMs(1.8f)!!
        val near = pulseIntervalMs(1f)!!
        assertTrue(far > mid && mid > near)
        assertEquals(1000L, far)
        assertEquals(300L, near)
        assertEquals(150L, pulseIntervalMs(0.5f)) // about to touch: near-continuous
    }

    @Test fun threeIntensityLevelsOnly() {
        assertEquals(setOf(0.65f, 0.85f, 1f), listOf(2.2f, 1.2f, 0.5f).map { pulseAmplitude(it) }.toSet())
    }

    @Test fun ticksWhileApproachingThenStopWhenStandingStill() {
        val p = ProximityPulse()
        var ticks = 0
        // Walk from 2.4 m to 1.2 m over 3 s: ticks, getting faster.
        for (i in 0..60) if (p.update(i * 50L, 2.4f - i * 0.02f) != null) ticks++
        assertTrue("ticks while approaching: $ticks", ticks >= 4)
        // Stand at 1.2 m for 10 s (desk, wall): ticking must stop 1 s after progress stops.
        var late = 0
        for (i in 61..260) if (p.update(i * 50L, 1.2f) != null && i * 50L > 3000 + Settings.pulseStaleMs) late++
        assertEquals(0, late)
        // But touching range always ticks.
        assertTrue((0..20).any { p.update(20_000L + it * 50L, 0.5f) != null })
    }

    @Test fun newObjectRestartsTicking() {
        val p = ProximityPulse()
        for (i in 0..200) p.update(i * 50L, 2f) // standing: goes quiet
        assertNull(p.update(10_100, 2f))
        p.update(10_200, Float.NaN) // it's gone
        assertTrue((0..40).any { p.update(10_300L + it * 50L, 2.3f - it * 0.03f) != null }) // walking at something new
    }

    @Test fun hazardsSpeakShortWordsAndApproachingIsVibrationOnly() {
        val track = Track(1, "person", Box(0.75f, 0.3f, 0.85f, 0.9f), 0).also {
            it.hits = 9; it.score = 0.9f; it.approaching = true; it.ttc = 2f; it.distance = 4f
        }
        val alerts = AlertPolicy().decide(listOf(track), Health.OK, 0, Hazards(dropAtM = 1.5f))
        val drop = alerts.first()
        assertNull(drop.tacton) // DropHapticController vibrates, independent of speech
        assertEquals("Stop. Drop.", drop.short)
        assertEquals(1, alerts.size) // the drop wins; one alert at a time
        val coming = AlertPolicy().decide(listOf(track), Health.OK, 0).first { it.tacton == Tacton.APPROACH }
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
