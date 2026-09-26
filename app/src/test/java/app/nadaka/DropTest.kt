package app.nadaka

import app.nadaka.drop.BarometerAnalyzer
import app.nadaka.drop.DepthInput
import app.nadaka.drop.DepthVerdict
import app.nadaka.drop.DropDepthAnalyzer
import app.nadaka.drop.DropEvidenceFusion
import app.nadaka.drop.DropHaptic
import app.nadaka.drop.DropOutput
import app.nadaka.drop.DropPipeline
import app.nadaka.drop.DropState
import app.nadaka.drop.EdgeAnalyzer
import app.nadaka.drop.EdgeCandidate
import app.nadaka.drop.FloorGeometry
import app.nadaka.drop.GroundPlaneAnalyzer
import app.nadaka.drop.GroundResult
import app.nadaka.drop.DepthResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/**
 * Layer 3 drop-off, on synthetic scenes ray-cast through the same floor model the app uses: a camera
 * [Settings.cameraHeightM] above the floor, pitched down, a height field (surface height relative to the floor
 * I stand on, negative = lower) and an albedo field for the grey image. Depth = relative disparity scale / z.
 */
class DropTest {
    private val W = 120; private val H = 160; private val S = 160
    private val cam get() = Settings.cameraHeightM

    class Scene(val gray: FloatArray, val depth: DepthInput)

    /** [height](ahead, lateral) in metres; [albedo](ahead, lateral, height) in 0..255. */
    private fun scene(
        pitchDeg: Float = 22f,
        seed: Int = 1,
        depthNoise: Float = 0.02f,
        height: (Float, Float) -> Float = { _, _ -> 0f },
        albedo: (Float, Float, Float) -> Float = { _, _, _ -> 120f },
        depthOverride: ((Float, Float, Float) -> Float)? = null, // (x, y, true disparity) -> what the model reports
    ): Scene {
        val pitch = Math.toRadians(pitchDeg.toDouble()).toFloat()
        val rnd = Random(seed)
        fun cast(x: Float, y: Float): Triple<Float, FloatArray, Float> { // (z, point, surface height)
            val ra = (y - 0.5f) * Settings.vfovRad
            val ca = (x - 0.5f) * Settings.hfovRad
            val a = pitch + ra
            var r = 0.2f
            while (r < 25f) {
                val ahead = r * cos(a); val z = r * cos(ra)
                val lat = z * tan(ca)
                val h = cam - r * sin(a) // ray height above my floor
                val surf = height(ahead, lat)
                if (h <= surf) return Triple(z, floatArrayOf(ahead, lat), surf)
                r *= 1.004f
            }
            return Triple(Float.NaN, floatArrayOf(Float.NaN, Float.NaN), 0f)
        }
        val gray = FloatArray(W * H)
        for (j in 0 until H) for (i in 0 until W) {
            val (z, p, s) = cast((i + 0.5f) / W, (j + 0.5f) / H)
            val g = if (z.isNaN()) 200f else albedo(p[0], p[1], s)
            gray[j * W + i] = (g + rnd.nextFloat() * 8f - 4f).coerceIn(0f, 255f)
        }
        val raw = FloatArray(S * S)
        for (j in 0 until S) for (i in 0 until S) {
            val x = (i + 0.5f) / S; val y = (j + 0.5f) / S
            val (z) = cast(x, y)
            var d = if (z.isNaN()) 0.02f else 1f / z
            d *= 1f + (rnd.nextFloat() * 2 - 1) * depthNoise
            raw[j * S + i] = depthOverride?.invoke(x, y, d) ?: d
        }
        return Scene(gray, DepthInput(raw, S, 1f, true, 30))
    }

    private fun flat() = scene()
    /** A step down of [drop] m at [at] m ahead, lower surface darker (a different surface). */
    private fun step(at: Float = 2f, drop: Float = 0.18f, pitchDeg: Float = 22f, seed: Int = 1) = scene(
        pitchDeg, seed,
        height = { a, _ -> if (a > at) -drop else 0f },
        albedo = { _, _, h -> if (h < -0.01f) 80f else 125f },
    )
    private fun stairsDown(at: Float = 2f, seed: Int = 1) = scene(
        seed = seed,
        height = { a, _ -> if (a <= at) 0f else -0.17f * (1 + ((a - at) / 0.28f).toInt()) },
        albedo = { a, _, h -> if (h > -0.01f) 125f else if (((a - at) / 0.28f).toInt() % 2 == 0) 70f else 95f },
    )

    private fun run(p: DropPipeline, frames: List<Scene>, tracks: List<Track> = emptyList(), health: Health = Health.OK, pitchDeg: Float = 22f, t0: Long = 0): List<DropOutput> =
        frames.mapIndexed { i, s -> p.core(t0 + i * 70L, s.gray, s.depth, pitchDeg, tracks, health) }

    private fun states(o: List<DropOutput>) = o.map { it.state }

    // ---------- analyzers ----------

    @Test fun edgeAnalyzerFindsTheStepEdgeAtTheRightRow() {
        val c = EdgeAnalyzer().analyze(step().gray).first()
        val g = FloorGeometry(1f, Math.toRadians(22.0).toFloat())
        assertEquals(2f, g.floorAheadM(c.y), 0.25f)
        assertTrue("edge score ${c.score}", c.score >= app.nadaka.drop.DropOffConfig.EDGE_STRONG_SCORE)
    }

    @Test fun edgeAnalyzerIgnoresUniformFloor() {
        assertTrue(EdgeAnalyzer().analyze(flat().gray).isEmpty())
    }

    @Test fun depthSupportsARealStepAndContradictsAPaintedLine() {
        val g = FloorGeometry(1f, Math.toRadians(22.0).toFloat())
        val s = step()
        val c = EdgeAnalyzer().analyze(s.gray).first()
        assertEquals(DepthVerdict.SUPPORTS, DropDepthAnalyzer().analyze(s.depth, g, c).verdict)
        assertEquals(DepthVerdict.CONTRADICTS, DropDepthAnalyzer().analyze(flat().depth, g, c).verdict)
    }

    @Test fun unreliableDepthNeverSupports() {
        val g = FloorGeometry(1f, Math.toRadians(22.0).toFloat())
        val s = step()
        val c = EdgeAnalyzer().analyze(s.gray).first()
        val an = DropDepthAnalyzer()
        // Missing (zeros), untrusted ruler, stale, noisy.
        val zeros = DepthInput(FloatArray(S * S), S, 1f, true, 30)
        assertEquals(DepthVerdict.UNRELIABLE, an.analyze(zeros, g, c).verdict)
        assertEquals(DepthVerdict.UNRELIABLE, an.analyze(DepthInput(s.depth.raw, S, 1f, false, 30), g, c).verdict)
        assertEquals(DepthVerdict.UNRELIABLE, an.analyze(DepthInput(s.depth.raw, S, 1f, true, 900), g, c).verdict)
        assertEquals(DepthVerdict.UNRELIABLE, an.analyze(null, g, c).verdict)
        val noisy = scene(depthNoise = 0.6f, height = { a, _ -> if (a > 2f) -0.18f else 0f })
        assertNotEquals(DepthVerdict.SUPPORTS, an.analyze(noisy.depth, g, c).verdict)
    }

    @Test fun groundPlaneSeesTheBreakOnlyAtAStep() {
        val g = FloorGeometry(1f, Math.toRadians(22.0).toFloat())
        val s = step(drop = 0.3f)
        val c = EdgeAnalyzer().analyze(s.gray).first()
        assertTrue(GroundPlaneAnalyzer().analyze(s.depth, g, c).breakAway)
        val f = GroundPlaneAnalyzer().analyze(flat().depth, g, c)
        assertTrue(f.reliable); assertTrue(!f.breakAway)
    }

    @Test fun objectOverlapReducesConfidenceButKeepsEvidence() {
        val edge = EdgeCandidate(0.62f, 0.2f, 0.8f, 0.8f, 0.8f, 0.6f, 0.5f)
        val depth = DepthResult(DepthVerdict.SUPPORTS, 0.9f, 0.2f, 1f, 1f, 0.8f)
        val ground = GroundResult(0.8f, true, true)
        val b = BarometerAnalyzer(false)
        val clear = DropEvidenceFusion.fuse(0, edge, depth, ground, 0f, b)
        val onObject = DropEvidenceFusion.fuse(0, edge, depth, ground, 0.9f, b)
        assertTrue(onObject.confidence < clear.confidence)
        assertTrue(onObject.confidence > 0f)
    }

    // ---------- temporal rules ----------

    @Test fun oneStrongFrameIsNotConfirmed() {
        val p = DropPipeline(false)
        val o = run(p, listOf(step()) + List(3) { flat() })
        assertTrue(states(o).toString(), states(o).all { it == DropState.SAFE })
    }

    @Test fun twoOfThreeIsPossibleThreeOfFiveIsConfirmed() {
        val p = DropPipeline(false)
        val o = run(p, listOf(step(seed = 1), step(seed = 2), step(seed = 3)))
        assertEquals(listOf(DropState.SAFE, DropState.POSSIBLE_DROP, DropState.CONFIRMED_DROP), states(o))
        assertEquals(DropHaptic.POSSIBLE_PULSE, o[1].haptic)
        assertEquals(DropHaptic.CONFIRMED_ESCALATING, o[2].haptic)
    }

    @Test fun recoveryNeedsManyCleanFramesNotOne() {
        val p = DropPipeline(false)
        val o = run(p, List(4) { step(seed = it) } + List(12) { flat() })
        assertEquals(DropState.CONFIRMED_DROP, o[4].state) // one clean frame never clears it
        assertEquals(DropState.SAFE, o.last().state)
    }

    @Test fun edgeAloneNeverConfirms() {
        val p = DropPipeline(false)
        val s = step()
        val noDepth = run(p, List(10) { Scene(s.gray, DepthInput(FloatArray(S * S), S, 1f, true, 30)) })
        assertTrue(states(noDepth).all { it == DropState.SAFE })
        val p2 = DropPipeline(false)
        assertTrue(List(10) { i -> p2.core(i * 70L, s.gray, null, 22f, emptyList(), Health.OK).state }.all { it == DropState.SAFE })
    }

    @Test fun barometerAloneNeverConfirms() {
        val p = DropPipeline(true)
        var hPa = 1000f
        val o = List(40) { i -> hPa += 0.05f; p.barometer.update(hPa); p.core(i * 70L, flat().gray, flat().depth, 22f, emptyList(), Health.OK) }
        assertTrue(p.barometer.descendingConfirmed)
        assertTrue(states(o).none { it == DropState.CONFIRMED_DROP || it == DropState.POSSIBLE_DROP })
    }

    @Test fun descendingMakesTheConfirmedAlertMaximum() {
        val p = DropPipeline(true)
        var hPa = 1000f
        repeat(30) { hPa += 0.05f; p.barometer.update(hPa) }
        // Visual evidence first; descent only raises the alert to maximum intensity.
        val o = run(p, List(3) { step(seed = it) })
        assertEquals(DropState.CONFIRMED_DROP, o.last().state)
        assertEquals(DropHaptic.CONFIRMED_MAX, o.first { it.haptic != DropHaptic.NONE && it.state == DropState.CONFIRMED_DROP }.haptic)
    }

    @Test fun blockedCameraIsSensorBlockedNotDrop() {
        val o = run(DropPipeline(false), List(5) { step(seed = it) }, health = Health.BLOCKED)
        assertTrue(states(o).all { it == DropState.SENSOR_BLOCKED })
    }

    @Test fun wallIsPathNotTraversable() {
        val wall = scene(height = { a, _ -> if (a > 0.9f) 3f else 0f }, albedo = { _, _, h -> if (h > 0.01f) 170f else 110f })
        val o = run(DropPipeline(false), List(5) { wall })
        assertEquals(DropState.PATH_NOT_TRAVERSABLE, o.last().state)
    }

    @Test fun blankViewWithoutDepthIsNotJudged() {
        val p = DropPipeline(false)
        val o = List(3) { p.core(it * 70L, FloatArray(W * H) { 128f }, null, 22f, emptyList(), Health.OK) }
        assertEquals(DropState.PATH_NOT_TRAVERSABLE, o.last().state)
    }

    // ---------- false positives: must stay SAFE ----------

    private fun assertSafe(name: String, frames: List<Scene>, tracks: List<Track> = emptyList()) {
        val o = run(DropPipeline(false), frames, tracks)
        assertTrue("$name: ${states(o)}", states(o).all { it == DropState.SAFE })
    }

    @Test fun shadow() = assertSafe("shadow", List(8) { scene(seed = it, albedo = { a, _, _ -> if (a > 2f) 55f else 130f }) })

    @Test fun paintedLine() = assertSafe("painted line", List(8) { scene(seed = it, albedo = { a, _, _ -> if (a in 2f..2.12f) 240f else 110f }) })

    @Test fun tileGrid() = assertSafe("tiles", List(8) { scene(seed = it, albedo = { a, l, _ ->
        if ((a % 0.6f) < 0.03f || (abs(l) % 0.6f) < 0.03f) 60f else 150f }) })

    @Test fun rug() = assertSafe("rug", List(8) { scene(seed = it,
        height = { a, l -> if (a in 1.8f..3.2f && abs(l) < 0.9f) 0.012f else 0f },
        albedo = { a, l, _ -> if (a in 1.8f..3.2f && abs(l) < 0.9f) 60f else 150f }) })

    @Test fun doormat() = assertSafe("doormat", List(8) { scene(seed = it,
        height = { a, l -> if (a in 1.7f..2.3f && abs(l) < 0.45f) 0.02f else 0f },
        albedo = { a, l, _ -> if (a in 1.7f..2.3f && abs(l) < 0.45f) 45f else 140f }) })

    @Test fun doorThreshold() = assertSafe("threshold", List(8) { scene(seed = it,
        height = { a, _ -> if (a in 2f..2.06f) 0.025f else 0f },
        albedo = { a, _, _ -> if (a > 2f) 90f else 140f }) })

    @Test fun puddleWhereDepthSeesTheSurface() = assertSafe("puddle", List(8) { scene(seed = it,
        albedo = { a, l, _ -> if (a in 2f..2.8f && abs(l) < 0.6f) 40f else 135f }) })

    @Test fun marble() = assertSafe("marble", List(8) { scene(seed = it,
        albedo = { a, l, _ -> 130f + 45f * sin(a * 9f + l * 3f + sin(l * 7f) * 2f) }) })

    @Test fun furnitureEdge() {
        val table = scene(height = { a, l -> if (a in 1.8f..2.6f && abs(l) < 0.6f) 0.45f else 0f },
            albedo = { _, _, h -> if (h > 0.01f) 70f else 140f })
        val t = Track(1, "bench", Box(0.25f, 0.45f, 0.75f, 0.7f), 0).also { it.hits = 9; it.score = 0.9f }
        assertSafe("furniture", List(8) { table }, listOf(t))
    }

    @Test fun movingPerson() {
        val frames = List(8) { i ->
            val at = 2.6f - i * 0.08f
            scene(seed = i, height = { a, l -> if (a in at..at + 0.3f && abs(l - 0.1f) < 0.25f) 1.7f else 0f },
                albedo = { _, _, h -> if (h > 0.01f) 50f else 140f })
        }
        val p = Track(2, "person", Box(0.4f, 0.1f, 0.65f, 0.75f), 0).also { it.hits = 9; it.score = 0.9f }
        assertSafe("moving person", frames, listOf(p))
    }

    // ---------- positives ----------

    private fun assertConfirmed(name: String, frames: List<Scene>, pitchDeg: Float = 22f) {
        val o = run(DropPipeline(false), frames, pitchDeg = pitchDeg)
        assertTrue("$name: ${states(o)} ${o.map { it.evidence.evidenceClass }}", states(o).contains(DropState.CONFIRMED_DROP))
    }

    @Test fun stepDown() = assertConfirmed("step", List(5) { step(seed = it) })
    @Test fun kerb() = assertConfirmed("15 cm kerb", List(5) { step(drop = 0.15f, at = 1.8f, seed = it) })
    @Test fun stairs() = assertConfirmed("stairs", List(5) { stairsDown(seed = it) })
    @Test fun walkingTowardStairs() = assertConfirmed("walking", List(8) { stairsDown(at = 3f - it * 0.15f, seed = it) })
    @Test fun stationaryAtStairs() = assertConfirmed("stationary", List(6) { stairsDown(at = 1.7f, seed = it) })
    @Test fun lowPitch() = assertConfirmed("pitch 10", List(5) { step(at = 2.4f, drop = 0.3f, pitchDeg = 10f, seed = it) }, pitchDeg = 10f)

    @Test fun confirmedReportsDistanceFromTheFloorRuler() {
        val o = run(DropPipeline(false), List(5) { step(at = 2f, seed = it) })
        assertEquals(2f, o.last().dropAheadM, 0.3f)
    }

    @Test fun evaluationIsFast() {
        val p = DropPipeline(false)
        val s = step()
        repeat(20) { p.core(it * 70L, s.gray, s.depth, 22f, emptyList(), Health.OK) } // warm up
        val t = System.nanoTime()
        repeat(50) { p.core(2000 + it * 70L, s.gray, s.depth, 22f, emptyList(), Health.OK) }
        val ms = (System.nanoTime() - t) / 50e6
        assertTrue("$ms ms per evaluation", ms < 20)
    }
}

