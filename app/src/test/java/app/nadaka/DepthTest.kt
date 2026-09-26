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

    /** Walk on flat floor long enough to trust the ruler. */
    private fun DepthAnalyzer.calibrate() = repeat(Settings.scaleLockFrames) { analyze(scene { a -> floor(a) }, pitchDeg) }

    /** Calibrate on a flat floor first (as when walking up to a hazard), then see the hazard for depthHits frames. */
    private fun twice(s: Array<FloatArray>): Hazards = DepthAnalyzer().let {
        it.calibrate()
        var hz = Hazards()
        repeat(Settings.depthHits) { _ -> hz = it.analyze(s, pitchDeg) }
        hz
    }

    @Test fun flatFloorIsQuietAndRecoversScale() {
        val an = DepthAnalyzer()
        an.calibrate()
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
        an.calibrate()
        assertNull(an.analyze(scene { floor(it, extraDrop = 0.8f, dropAt = 1.8f) }, pitchDeg).dropAtM)
    }

    /** Like [scene] but the hit distance may depend on the column too (patches, partial drops). */
    private fun sceneCols(hit: (a: Float, c: Int) -> Float): Array<FloatArray> {
        val pitch = Math.toRadians(pitchDeg.toDouble()).toFloat()
        return Array(DEPTH_ROWS) { r ->
            val ra = ((r + 0.5f) / DEPTH_ROWS - 0.5f) * Settings.vfovRad
            val a = pitch + ra
            FloatArray(DEPTH_COLS) { c -> k / (hit(a, c) / cos(a) * cos(ra)) }
        }
    }

    private fun DepthAnalyzer.seeFor(s: Array<FloatArray>, frames: Int, speed: Float = 0f, dropFrom: Float = 0f): Hazards {
        var hz = Hazards()
        repeat(frames) { i -> hz = analyze(s, pitchDeg, now = 1000L + i * 200, speedMps = speed) }
        return hz
    }

    @Test fun kerbIsAStepDown() {
        val an = DepthAnalyzer(); an.calibrate()
        val hz = an.seeFor(scene { floor(it, extraDrop = 0.15f, dropAt = 1.6f) }, Settings.depthHits)
        assertNotNull("15 cm kerb missed", hz.dropAtM)
        assertEquals(1.6f, hz.dropAtM!!, 0.4f)
        assert(hz.dropIsStep)
        val big = DepthAnalyzer().also { it.calibrate() }.seeFor(scene { floor(it, extraDrop = 0.8f, dropAt = 1.8f) }, Settings.depthHits)
        assert(!big.dropIsStep)
    }

    @Test fun narrowDarkPatchIsNotADrop() {
        val an = DepthAnalyzer(); an.calibrate()
        // Only 2 of the corridor columns look like a hole (a dark tile or a shadow).
        val patch = sceneCols { a, c -> if (c in 14..15) floor(a, extraDrop = 0.8f, dropAt = 1.5f) else floor(a) }
        assertNull(an.seeFor(patch, Settings.depthHits + 2).dropAtM)
    }

    @Test fun gradualDepthDriftIsNotAStep() {
        val an = DepthAnalyzer(); an.calibrate()
        // Depth model slowly over-estimating far floor (up to +25 % at 3 m): no sudden lip, not big.
        val drift = scene { a -> val d = floor(a); if (d < 20f) d * (1f + 0.12f * (d - 1f).coerceAtLeast(0f)) else d }
        assertNull(an.seeFor(drift, Settings.depthHits + 2).dropAtM)
    }

    @Test fun dropThatJumpsAroundIsNotConfirmed() {
        val an = DepthAnalyzer(); an.calibrate()
        var hz = Hazards()
        repeat(Settings.depthHits * 2) { i ->
            val at = if (i % 2 == 0) 1.6f else 3.0f // edge "moves" 1.4 m every frame: noise, not a real edge
            hz = an.analyze(scene { floor(it, extraDrop = 0.8f, dropAt = at) }, pitchDeg, now = 1000L + i * 200)
        }
        assertNull(hz.dropAtM)
    }

    @Test fun realEdgeApproachingAtWalkingSpeedIsConfirmed() {
        val an = DepthAnalyzer(); an.calibrate()
        var hz = Hazards()
        repeat(Settings.depthHits) { i ->
            val at = 2.8f - i * 0.2f // walking 1 m/s, a frame every 200 ms
            hz = an.analyze(scene { floor(it, extraDrop = 0.8f, dropAt = at) }, pitchDeg, now = 1000L + i * 200, speedMps = 1f)
        }
        assertNotNull(hz.dropAtM)
    }

    @Test fun oneGlanceCannotSetTheRuler() {
        val an = DepthAnalyzer()
        an.analyze(scene { floor(it) }, pitchDeg)
        assert(an.scale.isNaN())
    }

    /** Phone above a table: the table top (0.55 m below the lens) fills the near view, its edge at 1 m, floor beyond. */
    private fun tableScene() = scene { a ->
        if (a <= 0.01f) 20f else { val top = 0.55f / tan(a); if (top < 1f) top else h / tan(a) }
    }

    @Test fun tableEdgeIsNotADropOff() {
        val an = DepthAnalyzer()
        an.calibrate()
        repeat(Settings.depthHits + 2) { assertNull("table top taken for floor", an.analyze(tableScene(), pitchDeg).dropAtM) }
    }

    @Test fun noHazardsWhenNotWalkingOrLookingDown() {
        val an = DepthAnalyzer()
        an.calibrate()
        val stairs = scene { floor(it, extraDrop = 0.8f, dropAt = 1.8f) }
        repeat(Settings.depthHits + 1) { assertNull(an.analyze(stairs, pitchDeg, walking = false).dropAtM) }
        repeat(Settings.depthHits + 1) { assertNull(an.analyze(stairs, 60f).dropAtM) } // phone pointed at the floor/desk
    }

    @Test fun furnitureInFrontSuppressesFloorClaims() {
        val table = Track(1, "dining table", Box(0.1f, 0.5f, 0.9f, 1f), 0)
        val hz = withoutFurnitureFloor(Hazards(dropAtM = 1f, overheadAtM = 1.2f, floorObstacleAtM = 0.8f), listOf(table))
        assertNull(hz.dropAtM); assertNull(hz.floorObstacleAtM)
        assertEquals(1.2f, hz.overheadAtM)
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
