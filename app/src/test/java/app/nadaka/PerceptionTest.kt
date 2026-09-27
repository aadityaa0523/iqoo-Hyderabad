package app.nadaka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerceptionTest {
    @Test fun portraitIsPaddedNotStretched() {
        val (s, px, py) = letterbox(480, 640, 640)
        assertEquals(1f, s, 1e-6f); assertEquals(80f, px, 1e-6f); assertEquals(0f, py, 1e-6f)
        // A box edge at model x = 80 is the frame's left edge; x = 560 its right edge.
        assertEquals(0f, unletterbox(80f, px, s, 480), 1e-6f)
        assertEquals(1f, unletterbox(560f, px, s, 480), 1e-6f)
        assertEquals(0.5f, unletterbox(320f, px, s, 480), 1e-6f)
    }

    @Test fun categoriesAndThresholds() {
        assertEquals(Category.VEHICLE, categoryOf("motorcycle"))
        assertEquals(Category.ANIMAL, categoryOf("cow"))
        assertEquals(Category.SEAT, categoryOf("couch"))
        assertEquals(Category.OTHER, categoryOf("backpack"))
        assertTrue(keepDetection("person", 0.46f, 0.45f))
        assertFalse(keepDetection("bed", 0.5f, 0.45f))        // noisy indoors: needs 0.6
        assertTrue(keepDetection("bed", 0.65f, 0.45f))
        assertFalse(keepDetection("toothbrush", 0.99f, 0.45f)) // irrelevant for walking
    }

    @Test fun flickeringLabelKeepsOneTrackAndTheMajorityLabel() {
        val tr = Tracker()
        val box = Box(0.3f, 0.4f, 0.6f, 0.9f)
        val ego = Ego(0f, 0f, 0f)
        var tracks = emptyList<Track>()
        listOf("chair", "couch", "chair", "chair", "bench", "chair").forEachIndexed { i, l ->
            tracks = tr.update(listOf(Detection(l, 0.7f, box)), i * 100L, ego)
        }
        assertEquals(1, tracks.size)
        assertEquals(6, tracks[0].hits)          // never restarted: reaches the 5 frames needed to speak
        assertEquals("chair", tracks[0].label)
    }
}
