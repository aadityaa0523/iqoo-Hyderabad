package app.nadaka.drop

import kotlin.math.abs
import app.nadaka.drop.DropOffConfig as C

/**
 * Depth verdict for one candidate edge, from Depth Anything V2 relative disparity.
 *
 * Relative disparity has no metric meaning on its own, so every sample is normalised by the disparity a flat
 * floor would have at that image row (the existing floor ruler): ratio = observed / expected. On the floor the
 * ratio is ~1 everywhere; beyond a real drop the surface is lower, hence farther, hence ratio < 1, while the
 * near side (the surface you stand on) stays ~1.
 *
 * SUPPORTS only with enough valid, consistent samples and a trusted ruler. Missing, invalid or inconsistent
 * depth is UNRELIABLE, never SUPPORTS: missing depth is not a hole.
 */
class DropDepthAnalyzer {
    private val near = FloatArray(C.DEPTH_SAMPLE_OFFSETS.size * C.DEPTH_SAMPLE_COLUMNS)
    private val far = FloatArray(near.size)
    private val dev = FloatArray(near.size)

    fun analyze(depth: DepthInput?, geo: FloorGeometry?, edge: EdgeCandidate): DepthResult {
        if (depth == null || geo == null || !depth.floorTrusted || geo.scale.isNaN() || depth.ageMs > C.DEPTH_MAX_AGE_MS)
            return DepthResult.UNRELIABLE
        var nn = 0; var nf = 0; var total = 0
        val pts = ArrayList<Pair<Float, Float>>(near.size * 2)
        val inset = (edge.x1 - edge.x0) * 0.1f
        for (i in 0 until C.DEPTH_SAMPLE_COLUMNS) {
            val x = edge.x0 + inset + (edge.x1 - edge.x0 - 2 * inset) * i / (C.DEPTH_SAMPLE_COLUMNS - 1)
            for (off in C.DEPTH_SAMPLE_OFFSETS) {
                total += 2
                ratio(depth, geo, x, edge.y + off)?.let { near[nn++] = it } // below the edge = nearer = where I stand
                ratio(depth, geo, x, edge.y - off)?.let { far[nf++] = it }  // above the edge = farther surface
                pts += x to edge.y + off; pts += x to edge.y - off
            }
        }
        val valid = (nn + nf).toFloat() / total
        if (nn < 3 || nf < 3) return DepthResult(DepthVerdict.UNRELIABLE, 0f, validRatio = valid, samples = pts)
        val rn = medianOf(near, nn)
        val rf = medianOf(far, nf)
        val madN = mad(near, nn, rn)
        val madF = mad(far, nf, rf)
        val confidence = valid * (1 - clamp01((madN + madF) / 0.25f)) * (1 - clamp01(abs(rn - 1f) / 0.4f))
        val jump = rn - rf
        val verdict = when {
            valid < C.DEPTH_MIN_VALID || confidence < C.DEPTH_MIN_CONFIDENCE -> DepthVerdict.UNRELIABLE
            jump >= C.DEPTH_JUMP_THRESHOLD && rf < 0.95f -> DepthVerdict.SUPPORTS
            abs(jump) < C.DEPTH_CONTINUITY_TOLERANCE && abs(rf - 1f) < 0.2f -> DepthVerdict.CONTRADICTS // floor continues
            rf > rn + 0.1f -> DepthVerdict.CONTRADICTS // far side is nearer: an obstacle or wall, not a drop
            else -> DepthVerdict.UNRELIABLE
        }
        return DepthResult(verdict, confidence, jump, valid, rn, rf, pts)
    }

    private fun ratio(depth: DepthInput, geo: FloorGeometry, x: Float, y: Float): Float? {
        if (y !in 0f..1f) return null
        val d = depth.at(x, y)
        val e = geo.expectedDisparity(y)
        if (!(d > 0f) || !d.isFinite() || e.isNaN() || !(e > 0f)) return null // invalid / zero depth is skipped, not "far"
        return d / e
    }

    private fun mad(v: FloatArray, n: Int, m: Float): Float {
        for (i in 0 until n) dev[i] = abs(v[i] - m)
        return medianOf(dev, n)
    }
}

/**
 * "Is this the floor I'm standing on, and does the surface beyond the edge break away from it?"
 * Fits height = c0 + c1*ahead + c2*lateral to 3D points (through the floor ruler) in the lower-centre region,
 * then measures how far below that plane the region just beyond the candidate edge lies.
 */
class GroundPlaneAnalyzer {
    private val px = FloatArray(64); private val py = FloatArray(64); private val pz = FloatArray(64)
    private val far = FloatArray(32)

    fun analyze(depth: DepthInput?, geo: FloorGeometry?, edge: EdgeCandidate?): GroundResult {
        if (depth == null || geo == null || !depth.floorTrusted || geo.scale.isNaN() || depth.ageMs > C.DEPTH_MAX_AGE_MS) return GroundResult.UNRELIABLE
        val yTop = maxOf((edge?.y ?: 0.72f) + 0.04f, 0.6f)
        var n = 0
        for (i in 0 until 8) for (j in 0 until 8) {
            val x = 0.3f + 0.4f * i / 7f
            val y = yTop + (0.97f - yTop) * j / 7f
            val p = geo.point(x, y, depth.at(x, y)) ?: continue
            if (p[0] !in 0.3f..5f) continue
            px[n] = p[0]; py[n] = p[1]; pz[n] = p[2]; n++
        }
        if (n < C.GROUND_MIN_POINTS) return GroundResult.UNRELIABLE // not enough geometry: no decision
        val c = fitPlane(n) ?: return GroundResult.UNRELIABLE
        var rss = 0f
        for (k in 0 until n) { val r = pz[k] - (c[0] + c[1] * px[k] + c[2] * py[k]); rss += r * r }
        val rms = kotlin.math.sqrt(rss / n)
        val reliable = rms <= C.GROUND_MAX_RESIDUAL_M && abs(c[1]) <= C.GROUND_MAX_SLOPE && abs(c[0]) <= 0.3f
        if (!reliable || edge == null) return GroundResult(0f, reliable, false, rms)
        var nf = 0
        for (i in 0 until 8) for (off in floatArrayOf(0.02f, 0.04f, 0.06f, 0.08f)) {
            val x = edge.x0 + (edge.x1 - edge.x0) * (i + 0.5f) / 8f
            val y = edge.y - off
            if (y < 0f || nf >= far.size) continue
            val p = geo.point(x, y, depth.at(x, y)) ?: continue
            far[nf++] = p[2] - (c[0] + c[1] * p[0] + c[2] * p[1]) // height relative to my floor plane
        }
        if (nf < 6) return GroundResult(0f, true, false, rms)
        val below = -medianOf(far, nf)
        val brk = below >= C.GROUND_BREAK_M
        return GroundResult(if (brk) clamp01(below / C.GROUND_FULL_DROP_M) else 0f, true, brk, rms, below)
    }

    /** Least squares for z = c0 + c1 x + c2 y (3x3 normal equations, Cramer's rule). */
    private fun fitPlane(n: Int): FloatArray? {
        var sx = 0.0; var sy = 0.0; var sz = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0; var sxz = 0.0; var syz = 0.0
        for (k in 0 until n) {
            val x = px[k].toDouble(); val y = py[k].toDouble(); val z = pz[k].toDouble()
            sx += x; sy += y; sz += z; sxx += x * x; syy += y * y; sxy += x * y; sxz += x * z; syz += y * z
        }
        val m = arrayOf(doubleArrayOf(n.toDouble(), sx, sy), doubleArrayOf(sx, sxx, sxy), doubleArrayOf(sy, sxy, syy))
        val b = doubleArrayOf(sz, sxz, syz)
        fun det(a: Array<DoubleArray>) = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) -
            a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
        val d = det(m)
        if (abs(d) < 1e-9) return null
        return FloatArray(3) { col -> (det(Array(3) { r -> DoubleArray(3) { c -> if (c == col) b[r] else m[r][c] } }) / d).toFloat() }
    }
}

/**
 * Stairs going UP: the height profile along the walking corridor (through the floor ruler) first leaves the
 * floor, then keeps rising at a stair-like slope. A wall rises almost vertically (slope far above stairs),
 * a ramp too gently, a single kerb stops rising. Distance = where the first step starts. NaN = none.
 */
class StairsUpAnalyzer {
    private val ahead = FloatArray(80); private val height = FloatArray(80); private val tmpA = FloatArray(5); private val tmpH = FloatArray(5)

    fun analyze(depth: DepthInput?, geo: FloorGeometry?): Float {
        if (depth == null || geo == null || !depth.floorTrusted || depth.ageMs > C.DEPTH_MAX_AGE_MS) return Float.NaN
        var n = 0
        var y = 0.96f
        while (y > 0.30f && n < ahead.size) { // bottom (near) to top (far)
            var k = 0
            for (x in floatArrayOf(0.42f, 0.46f, 0.5f, 0.54f, 0.58f)) {
                val p = geo.point(x, y, depth.at(x, y)) ?: continue
                tmpA[k] = p[0]; tmpH[k] = p[2]; k++
            }
            if (k >= 3) { ahead[n] = medianOf(tmpA, k); height[n] = medianOf(tmpH, k); if (ahead[n] in 0.3f..6f) n++ }
            y -= 0.01f
        }
        if (n < 10) return Float.NaN
        // First rise off the floor, after some floor right in front of the feet.
        var i0 = -1
        for (i in 3 until n) if (height[i] > C.STAIRS_RISE_M && (0 until 3).all { kotlin.math.abs(height[it]) < C.STAIRS_RISE_M }) { i0 = i; break }
        if (i0 < 0) return Float.NaN
        val start = ahead[i0]
        // Least-squares slope of height over distance across the next ~1.2 m.
        var sx = 0f; var sy = 0f; var sxx = 0f; var sxy = 0f; var m = 0; var top = 0f
        for (i in i0 until n) {
            if (ahead[i] < start - 0.05f || ahead[i] > start + C.STAIRS_SPAN_M) continue
            sx += ahead[i]; sy += height[i]; sxx += ahead[i] * ahead[i]; sxy += ahead[i] * height[i]; m++
            top = maxOf(top, height[i])
        }
        if (m < 5 || top < C.STAIRS_MIN_TOP_M) return Float.NaN
        val den = m * sxx - sx * sx
        if (kotlin.math.abs(den) < 1e-6f) return Float.NaN // no spread in distance: a wall
        val slope = (m * sxy - sx * sy) / den
        return if (slope in C.STAIRS_SLOPE_MIN..C.STAIRS_SLOPE_MAX) start else Float.NaN
    }
}

/**
 * Existing YOLOX tracks overlapping the candidate edge (bag, box, furniture, person...). A strong overlap reduces
 * drop evidence (the "edge" is probably the object's outline), but never removes it: stairs with a person on them
 * are still stairs. COCO has no rug/doormat/threshold class; those rely on the depth CONTRADICTS verdict.
 */
object ObjectSuppression {
    private val OUTLINE_LABELS = setOf(
        "bench", "chair", "couch", "bed", "dining table", "suitcase", "backpack", "handbag", "potted plant",
        "toilet", "sink", "refrigerator", "tv", "laptop", "book", "bottle", "cup", "bowl", "vase",
    )

    fun score(edge: EdgeCandidate, tracks: List<app.nadaka.Track>): Float {
        var best = 0f
        for (t in tracks) {
            if (t.hits < 3) continue
            val b = t.box
            if (edge.y < b.top - 0.02f || edge.y > b.bottom + 0.03f) continue
            val overlap = (minOf(edge.x1, b.right) - maxOf(edge.x0, b.left)) / (edge.x1 - edge.x0)
            if (overlap <= 0f) continue
            // The object's bottom outline on the floor is the classic fake "edge".
            val atBase = if (abs(edge.y - b.bottom) < 0.05f) 1f else 0.7f
            val kind = if (t.label in OUTLINE_LABELS) 1f else 0.5f
            best = maxOf(best, clamp01(overlap) * atBase * kind)
        }
        return best
    }
}

/**
 * Barometric descent: raw pressure -> short EMA -> long EMA (baseline) -> delta -> persistence/hysteresis.
 * Supporting evidence only: it can upgrade POSSIBLE to CONFIRMED, never create a drop. The iQOO I2501 has no
 * pressure sensor, so there it stays UNAVAILABLE and has no effect.
 */
class BarometerAnalyzer(val available: Boolean) {
    var filteredPressure = Float.NaN; private set
    var baselinePressure = Float.NaN; private set
    var pressureDelta = 0f; private set
    private var persist = 0

    val status get() = when {
        !available || filteredPressure.isNaN() -> BaroStatus.UNAVAILABLE
        descendingConfirmed -> BaroStatus.DESCENDING
        pressureDelta <= -C.BAROMETER_DESCENT_THRESHOLD -> BaroStatus.ASCENDING
        else -> BaroStatus.STABLE
    }
    val descendingConfirmed get() = available && persist >= C.BAROMETER_PERSIST_SAMPLES
    val descentConfidence get() = if (!available) 0f else clamp01(pressureDelta / C.BAROMETER_DESCENT_THRESHOLD)

    /** Pressure rises as you go down (~0.12 hPa per metre near sea level). */
    fun update(hPa: Float) {
        if (!available || !hPa.isFinite()) return
        if (filteredPressure.isNaN()) { filteredPressure = hPa; baselinePressure = hPa }
        filteredPressure += C.BAROMETER_SHORT_ALPHA * (hPa - filteredPressure)
        baselinePressure += C.BAROMETER_LONG_ALPHA * (hPa - baselinePressure)
        pressureDelta = filteredPressure - baselinePressure
        persist = when {
            pressureDelta >= C.BAROMETER_DESCENT_THRESHOLD -> persist + 1
            pressureDelta < C.BAROMETER_DESCENT_THRESHOLD * 0.5f -> 0 // hysteresis
            else -> persist
        }
    }
}
