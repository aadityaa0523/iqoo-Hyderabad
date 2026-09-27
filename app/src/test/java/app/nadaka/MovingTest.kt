package app.nadaka

import org.junit.Assert.assertTrue
import org.junit.Test

class MovingTest {
    private fun person(m: Float, toward: Float, sideways: Float, approaching: Boolean = false) =
        Track(1, "person", Box(0.45f, 0.3f, 0.55f, 0.8f), 0).also {
            it.hits = 9; it.score = 0.9f; it.distance = m; it.objSpeed = toward; it.lateralMps = sideways; it.approaching = approaching
        }

    private fun said(t: Track) = AlertPolicy().decide(listOf(t), Health.OK, 10_000, Hazards(), Activity.WALKING).joinToString { it.text }

    @Test fun farOrNotComingCloserIsSilent() {
        assertTrue(said(person(9f, 0f, 1.2f)).isEmpty())            // crossing far ahead
        assertTrue(said(person(4f, -1.0f, 0f)).isEmpty())           // walking away
        assertTrue(said(person(9f, 1.0f, 0f, approaching = true)).isEmpty()) // "approaching" at 9 m: box jitter
    }

    @Test fun comingCloserNearbyIsSpoken() {
        assertTrue(said(person(5f, 1.0f, 0f)).contains("moving"))
        assertTrue(said(person(5f, 1.0f, 0f, approaching = true)).contains("approaching"))
    }
}
