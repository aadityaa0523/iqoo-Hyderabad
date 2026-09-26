package app.nadaka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewFeaturesTest {
    private fun scores(vararg hot: Pair<Int, Float>) = FloatArray(521).also { s -> hot.forEach { (i, v) -> s[i] = v } }

    @Test fun hornIsHeardOnceThenRateLimited() {
        val p = SoundPolicy()
        assertEquals(Danger.HORN, p.update(0, scores(302 to 0.7f)))
        assertNull(p.update(500, scores(302 to 0.7f)))
        assertEquals(Danger.HORN, p.update(Settings.soundRepeatMs + 1000, scores(302 to 0.7f)))
    }

    @Test fun barkingMustPersistAndQuietSoundsAreIgnored() {
        val p = SoundPolicy()
        assertNull(p.update(0, scores(70 to 0.6f)))
        assertEquals(Danger.DOG, p.update(500, scores(70 to 0.6f)))
        assertNull(SoundPolicy().update(0, scores(390 to 0.1f))) // faint siren: below threshold
        assertEquals(Danger.SIREN, SoundPolicy().update(0, scores(318 to 0.5f, 302 to 0.2f)))
    }

    @Test fun findTargets() {
        assertEquals("chair" to "chair", findTarget("find a chair"))
        assertEquals("phone" to "cell phone", findTarget("where is my phone"))
        assertEquals("door" to null, findTarget("take me to the door")) // not a detector class: Gemma
        assertEquals(Ask.FIND, intentOf("find the bottle"))
        assertEquals(Ask.SAFETY, intentOf("where is it safe to cross")) // safety still wins
    }

    @Test fun finderGuidesThenArrives() {
        val f = Finder("chair", 0)
        val chair = { m: Float, cx: Float -> Track(1, "chair", Box(cx - 0.05f, 0.4f, cx + 0.05f, 0.9f), 0).also { it.hits = 5; it.distance = m } }
        assertEquals("Chair, 1 o'clock, 3 metres.", f.update(100, listOf(chair(3f, 0.8f))))
        assertNull(f.update(1000, listOf(chair(2.5f, 0.7f)))) // not every frame
        assertTrue(f.update(2000, listOf(chair(0.5f, 0.5f)))!!.startsWith("Chair, right in front of you"))
        assertTrue(f.done)
    }

    @Test fun finderSaysWhenNothingIsInViewAndTimesOut() {
        val f = Finder("bottle", 0)
        assertEquals("No bottle in view. Turn slowly.", f.update(4000, emptyList()))
        assertEquals("Stopped looking for the bottle.", f.update(Settings.findTimeoutMs + 1, emptyList()))
    }

    @Test fun voiceSetModesAreStickyAndLetsGoEndsThem() {
        val d = ActivityDetector()
        d.force(Activity.SITTING, 0)
        assertNull(d.update(20_000, lastStepMs = 20_000, vibration = 0f)) // steps ignored while sticky
        assertEquals(Activity.SITTING, d.current)
        assertEquals(Ask.SIT, intentOf("I'm sitting"))
        assertEquals(Ask.VEHICLE, intentOf("I'm on the bus"))
        assertEquals(Ask.WALK, intentOf("let's go"))
        assertEquals(Ask.EMERGENCY, intentOf("help me"))
    }

    @Test fun sittingOnlyWarnsAboutWhatComesAtYou() {
        val chair = Track(1, "chair", Box(0.45f, 0.5f, 0.55f, 0.9f), 0).also { it.hits = 9; it.score = 0.9f; it.distance = 1.2f }
        val person = Track(2, "person", Box(0.45f, 0.2f, 0.55f, 0.9f), 0).also { it.hits = 9; it.score = 0.9f; it.distance = 3f; it.approaching = true; it.ttc = 2f }
        assertEquals(0, AlertPolicy().decide(listOf(chair), Health.OK, 0, activity = Activity.SITTING).size)
        assertEquals(1, AlertPolicy().decide(listOf(person), Health.OK, 0, activity = Activity.SITTING).size)
    }
}
