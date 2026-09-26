package app.nadaka

import app.nadaka.drop.BaroStatus
import app.nadaka.drop.DropEvidence
import app.nadaka.drop.DropHaptic
import app.nadaka.drop.DropOutput
import app.nadaka.drop.DropState
import app.nadaka.ui.Glyph
import app.nadaka.ui.Level
import app.nadaka.ui.directionOf
import app.nadaka.ui.safetyOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyUiTest {
    private fun drop(state: DropState, m: Float = 0.8f, reason: String = "") = DropOutput(
        state, DropEvidence.none(0), null, DropHaptic.NONE, m, null, emptyList(), 0.7f, 0, 0, 0, emptyList(),
        BaroStatus.UNAVAILABLE, reason, FloatArray(5),
    )
    private fun live(d: DropOutput? = null, health: Health = Health.OK, mode: String = "WALKING", hz: Hazards = Hazards()) =
        HudState(loading = false, drop = d, health = health, mode = mode, hazards = hz)

    @Test fun everyStateHasWordsAndItsOwnShape() {
        val safe = safetyOf(live(drop(DropState.SAFE)))
        assertEquals("PATH CLEAR", safe.headline); assertEquals(Glyph.CHECK, safe.glyph); assertEquals(Level.CALM, safe.level)

        val possible = safetyOf(live(drop(DropState.POSSIBLE_DROP, 1.2f)))
        assertEquals("POSSIBLE DROP", possible.headline); assertEquals(Glyph.WARN, possible.glyph)
        assertTrue(possible.spoken, possible.spoken.contains("Approximately 1.2 meters"))

        val confirmed = safetyOf(live(drop(DropState.CONFIRMED_DROP, 0.8f)))
        assertEquals("STOP", confirmed.headline); assertEquals("DROP AHEAD", confirmed.subject)
        assertEquals(Glyph.STOP, confirmed.glyph); assertEquals(Level.DANGER, confirmed.level)
        assertEquals(0.8f, confirmed.distanceM, 1e-6f)
        assertTrue(confirmed.spoken, confirmed.spoken.startsWith("Warning. Stop"))
    }

    @Test fun confirmedDropBeatsEverythingButCameraProblems() {
        val coming = Track(1, "person", Box(0.45f, 0.2f, 0.55f, 0.9f), 0).also { it.hits = 9; it.score = 0.9f; it.approaching = true; it.distance = 1f }
        assertEquals("STOP", safetyOf(live(drop(DropState.CONFIRMED_DROP)).copy(tracks = listOf(coming))).headline)
        assertEquals("CAN'T SEE", safetyOf(live(drop(DropState.CONFIRMED_DROP), health = Health.BLOCKED)).headline)
        assertEquals("CAN'T SEE", safetyOf(live(drop(DropState.SENSOR_BLOCKED))).headline)
    }

    @Test fun errorLoadingAndUnavailableStates() {
        assertEquals("STARTING", safetyOf(HudState()).headline)
        assertEquals("CAMERA OFF", safetyOf(HudState(cameraError = "Camera permission is off.")).headline)
        assertEquals(Level.ERROR, safetyOf(HudState(loading = false, error = "X")).level)
        assertEquals("SENSOR OFF", safetyOf(HudState(loading = false, sensorError = "No motion sensor.")).headline)
        assertEquals("WALL AHEAD", safetyOf(live(drop(DropState.PATH_NOT_TRAVERSABLE, reason = "wall ahead"))).headline)
        assertEquals("PAUSED", safetyOf(live(mode = "VEHICLE")).headline)
    }

    @Test fun headHeightIsAStop() {
        val s = safetyOf(live(hz = Hazards(overheadAtM = 1.1f)))
        assertEquals("STOP", s.headline); assertEquals("HEAD HEIGHT", s.subject)
    }

    @Test fun directions() {
        assertEquals("LEFT", directionOf(-0.4f)); assertEquals("AHEAD", directionOf(0f)); assertEquals("RIGHT", directionOf(0.4f))
    }

    /** Standard theme pairs (ui/Theme.kt) meet WCAG AAA 7:1 for text. */
    @Test fun themeTextContrast() {
        val surface = 0xFF161C24.toInt(); val bg = 0xFF0B0F14.toInt()
        listOf(
            0xFFFFFFFF.toInt() to surface, 0xFFC9D1DA.toInt() to surface, 0xFF6FD3FF.toInt() to bg,
            0xFF06131A.toInt() to 0xFF6FD3FF.toInt(), 0xFF1A1200.toInt() to 0xFFFFB020.toInt(),
            0xFFFFFFFF.toInt() to 0xFFB00020.toInt(), 0xFFFFB020.toInt() to surface,
        ).forEach { (fg, b) -> assertTrue("%08X on %08X = %.1f".format(fg, b, contrast(fg, b)), contrast(fg, b) >= 7.0) }
    }
}
