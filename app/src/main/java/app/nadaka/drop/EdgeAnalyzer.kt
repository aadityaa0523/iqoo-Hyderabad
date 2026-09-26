package app.nadaka.drop

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import app.nadaka.drop.DropOffConfig as C

/**
 * Horizontal-edge lattice in the lower view. Not "strongest line": a candidate needs a strong vertical
 * intensity gradient, horizontal orientation, a long continuous run, a lower-frame location away from the
 * ROI borders, and a texture/brightness difference between the bands above and below it.
 * All buffers are allocated once; call [analyze] from one thread.
 */
class EdgeAnalyzer(private val w: Int = C.EDGE_IMG_W, private val h: Int = C.EDGE_IMG_H) {
    private val blur = FloatArray(w * h)
    private val gy = FloatArray(w * h)       // |vertical gradient| of horizontal-edge pixels, else 0
    private val band = BooleanArray(w)
    private val rowScore = FloatArray(h)
    private val rowStrength = FloatArray(h)
    private val rowRun = IntArray(h * 2)

    /** Grey-level spread over the ROI of the last frame (featureless-scene check). */
    var roiStd = 0f
        private set

    /** [gray] = luminance 0..255, row-major w x h. Returns up to 3 candidates, best first. */
    fun analyze(gray: FloatArray): List<EdgeCandidate> {
        val r0 = (C.ROI_TOP * h).toInt().coerceIn(2, h - 3)
        val r1 = (C.ROI_BOTTOM * h).toInt().coerceIn(r0 + 1, h - 3)
        val c0 = (C.ROI_LEFT * w).toInt().coerceIn(2, w - 3)
        val c1 = (C.ROI_RIGHT * w).toInt().coerceIn(c0 + 1, w - 3)

        // 1-3. Light denoise (3x3 box) and grey statistics over the ROI.
        var sum = 0.0; var sq = 0.0; var n = 0
        for (y in r0 - 2..r1 + 2) for (x in c0 - 2..c1 + 2) {
            var s = 0f
            for (dy in -1..1) for (dx in -1..1) s += gray[(y + dy) * w + x + dx]
            blur[y * w + x] = s / 9f
            if (y in r0..r1 && x in c0..c1) { sum += gray[y * w + x]; sq += gray[y * w + x] * gray[y * w + x]; n++ }
        }
        roiStd = sqrt(maxOf(0.0, sq / n - (sum / n) * (sum / n))).toFloat()

        // 4-5. Sobel gradients; keep only strong, near-horizontal edge pixels.
        for (y in r0 - 1..r1 + 1) for (x in c0 - 1..c1 + 1) {
            val i = y * w + x
            val gx = (blur[i - w + 1] + 2 * blur[i + 1] + blur[i + w + 1] - blur[i - w - 1] - 2 * blur[i - 1] - blur[i + w - 1]) / 4f
            val g = (blur[i + w - 1] + 2 * blur[i + w] + blur[i + w + 1] - blur[i - w - 1] - 2 * blur[i - w] - blur[i - w + 1]) / 4f
            gy[i] = if (abs(g) >= C.EDGE_PIXEL_GRAD && abs(g) >= C.EDGE_ORIENTATION_RATIO * abs(gx)) abs(g) else 0f
        }

        // 6-7. Per row: longest horizontal run of edge pixels (3-row band tolerates slight tilt), strength, texture.
        val roiW = (c1 - c0 + 1).toFloat()
        for (r in r0..r1) {
            rowScore[r] = 0f
            if (r - r0 < 3 || r1 - r < 3) continue // ROI/image border: not an edge in the scene
            for (x in c0..c1) band[x] = gy[(r - 1) * w + x] > 0f || gy[r * w + x] > 0f || gy[(r + 1) * w + x] > 0f
            var bestStart = -1; var bestLen = 0; var start = -1; var gap = 0; var last = -1
            for (x in c0..c1) {
                if (band[x]) {
                    if (start < 0) start = x
                    last = x; gap = 0
                    if (last - start + 1 > bestLen) { bestLen = last - start + 1; bestStart = start }
                } else if (start >= 0 && ++gap > C.EDGE_GAP_PX) { start = -1; gap = 0 }
            }
            val continuity = bestLen / roiW
            if (continuity < C.EDGE_MIN_LENGTH) continue // tiny / isolated edges
            var gs = 0f; var gn = 0
            for (x in bestStart until bestStart + bestLen) {
                val g = max(gy[(r - 1) * w + x], max(gy[r * w + x], gy[(r + 1) * w + x]))
                if (g > 0f) { gs += g; gn++ }
            }
            val strength = clamp01(gs / maxOf(gn, 1) / C.EDGE_GRAD_FULL)
            val texture = textureDiff(r, bestStart, bestStart + bestLen - 1, r0, r1)
            rowStrength[r] = strength
            rowScore[r] = 0.45f * strength + 0.35f * clamp01(continuity) + 0.20f * texture
            rowRun[2 * r] = bestStart; rowRun[2 * r + 1] = bestStart + bestLen - 1
        }

        // 8. Rank: local maxima (one per edge), best 3.
        val out = ArrayList<EdgeCandidate>(3)
        val used = BooleanArray(h)
        repeat(3) {
            var best = -1
            for (r in r0..r1) if (!used[r] && rowScore[r] >= C.EDGE_CANDIDATE_MIN && (best < 0 || rowScore[r] > rowScore[best])) best = r
            if (best < 0) return out
            for (r in best - 4..best + 4) if (r in 0 until h) used[r] = true
            val x0 = rowRun[2 * best]; val x1 = rowRun[2 * best + 1]
            out += EdgeCandidate(
                y = (best + 0.5f) / h, x0 = x0.toFloat() / w, x1 = (x1 + 1f) / w, score = rowScore[best],
                strength = rowStrength[best], continuity = (x1 - x0 + 1) / roiW, texture = textureDiff(best, x0, x1, r0, r1),
            )
        }
        return out
    }

    /** 0..1: how different the surfaces just above and just below the boundary look (brightness + texture). */
    private fun textureDiff(r: Int, x0: Int, x1: Int, r0: Int, r1: Int): Float {
        val b = C.EDGE_TEXTURE_BAND
        fun stats(ya: Int, yb: Int): Pair<Float, Float> {
            var s = 0f; var q = 0f; var n = 0
            for (y in ya.coerceAtLeast(r0 - 2)..yb.coerceAtMost(r1 + 2)) for (x in x0..x1) { val v = blur[y * w + x]; s += v; q += v * v; n++ }
            if (n == 0) return 0f to 0f
            val m = s / n
            return m to sqrt(maxOf(0f, q / n - m * m))
        }
        val (ma, sa) = stats(r - b - 2, r - 3)
        val (mb, sb) = stats(r + 3, r + b + 2)
        val tex = abs(sa - sb) / maxOf(sa, sb, 4f)
        val lum = abs(ma - mb) / 60f
        return clamp01(0.5f * clamp01(tex) + 0.5f * clamp01(lum))
    }
}
