package app.nadaka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.tan

/** Synthetic scenes rendered as disparity (= K / optical-axis depth) with the same camera model the analyzer uses. */
class DepthTest {
    private val k = 7.3f // unknown scale the analyzer must recover
    private val pitchDeg = 10f
    private val h = Settings.cameraHeightM

    /** [hit] returns the horizontal distance a ray (angle below horizontal [a]) travels before hitting something. */
    private fun scene(hit: (a: Float) -> Float): Array<FloatArray> {
        val pitch = Math.toRadians(pitchDeg.toDouble()).toFloat()
        return Array(DEPTH_ROWS) { r ->
            val ra = ((r + 0.5f) / DEPTH_ROWS - 0.5f) * Settings.vfovRad
            val a = pitch + ra
            val ahead = hit(a)
            val z = ahead / cos(a) * cos(ra)
            FloatArray(DEPTH_COLS) { k / z }
        }
    }

    private fun floor(a: Float, extraDrop: Float = 0f, dropAt: Float = Float.MAX_VALUE): Float {
        if (a <= 0.01f) return 20f // far wall
        val d = h / tan(a)
        return if (d < dropAt) d else ((h + extraDrop) / tan(a)).coerceAtMost(20f)
    }

    /** Calibrate on a flat floor first (as when walking up to a hazard), then see the hazard for depthHits frames. */
    private fun twice(s: Array<FloatArray>): Hazards = DepthAnalyzer().let {
        it.analyze(scene { a -> floor(a) }, pitchDeg)
        var hz = Hazards()
        repeat(Settings.depthHits) { _ -> hz = it.analyze(s, pitchDeg) }
        hz
    }

    @Test fun flatFloorIsQuietAndRecoversScale() {
        val an = DepthAnalyzer()
        val hz = an.analyze(scene { floor(it) }, pitchDeg)
        assertEquals(k, an.scale, k * 0.05f)
        assertNull(hz.dropAtM); assertNull(hz.overheadAtM); assertNull(hz.floorObstacleAtM)
    }

    @Test fun stairsGoingDownAreADropOff() {
        val hz = twice(scene { floor(it, extraDrop = 0.8f, dropAt = 1.8f) })
        assertNotNull(hz.dropAtM)
        assertEquals(1.8f, hz.dropAtM!!, 0.4f)
    }

    @Test fun oneNoisyFrameIsNotADropOff() {
        val an = DepthAnalyzer()
        an.analyze(scene { floor(it) }, pitchDeg)
        assertNull(an.analyze(scene { floor(it, extraDrop = 0.8f, dropAt = 1.8f) }, pitchDeg).dropAtM)
    }

    @Test fun headHeightBoardWithSpaceUnderneath() {
        val hz = twice(scene { a ->
            val board = if (a < 0) (1.7f - h) / tan(-a) else Float.MAX_VALUE // ray rising to the board's underside
            if (board in 1.0f..1.4f) board else floor(a)
        })
        assertNotNull(hz.overheadAtM)
        assertEquals(1.2f, hz.overheadAtM!!, 0.35f)
        assertNull(hz.dropAtM)
    }

    @Test fun standingPersonIsNotAHeadHeightHazard() {
        // A person/wall 2 m ahead fills head AND body height: an obstacle, not an overhang.
        val hz = twice(scene { a -> val d = floor(a); if (d > 2f) 2f else d })
        assertNull(hz.overheadAtM)
        assertNotNull(hz.floorObstacleAtM)
        assertEquals(2f, hz.floorObstacleAtM!!, 0.4f)
    }

    @Test fun noFloorSeenMeansNoClaims() {
        val an = DepthAnalyzer()
        val hz = an.analyze(scene { 20f }, -20f) // phone pointing at the sky
        assertEquals(Hazards(), hz)
        assert(an.scale.isNaN())
    }
}
