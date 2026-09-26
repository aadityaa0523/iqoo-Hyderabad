package app.nadaka.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import app.nadaka.DEPTH_COLS
import app.nadaka.DEPTH_ROWS
import app.nadaka.HudState
import app.nadaka.Prefs
import app.nadaka.drop.DropState
import app.nadaka.drop.EvidenceClass
import kotlin.math.min

/** What fills the hero. */
enum class HeroMode(val label: String) { BLEND("Depth + camera"), DEPTH("Depth"), CAMERA("Camera") }

/**
 * The hero visualisation, laid exactly over the camera preview (same size, same 3:4 frame).
 * Depth is drawn as brightness bands with contour lines (near = bright), so it reads without colour.
 * Annotations are words + thick shapes: FLOOR, DROP EDGE, LOWER LEVEL, head height, objects.
 */
class HeroView(ctx: Context) : View(ctx) {
    companion object { const val SHOW_DEPTH = false } // the contour bands read as moving "waves": camera only

    private var s = HudState()
    private var safety = safetyOf(s)
    private var t = Theme(ctx)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // Depth map: 48x32 grid -> upsampled 4x, banded, contoured. Rebuilt only when a new grid arrives.
    private val up = 4
    private val bw = DEPTH_COLS * up
    private val bh = DEPTH_ROWS * up
    private val depthBmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
    private val px = IntArray(bw * bh)
    private val band = IntArray(bw * bh)
    private var lastGrid: Array<FloatArray>? = null
    private var lastStyleHc = false

    fun show(state: HudState, sf: Safety) {
        s = state; safety = sf
        invalidate()
    }

    fun restyle() { t = Theme(context); lastGrid = null; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f) return
        val mode = Prefs.heroMode
        if (SHOW_DEPTH && mode != HeroMode.CAMERA) s.depth?.let { g ->
            buildDepth(g)
            bmpPaint.alpha = if (mode == HeroMode.DEPTH || Prefs.camera == app.nadaka.CameraView.HIDDEN) 255 else 150
            c.drawBitmap(depthBmp, null, Rect(0, 0, width, height), bmpPaint)
        }
        val simplified = Prefs.simplified
        val danger = safety.level == Level.DANGER
        if (safety.level == Level.ERROR || safety.level == Level.INFO) return // nothing trustworthy to annotate
        if (!simplified) drawFloor(c, w, h)
        drawDrop(c, w, h)
        s.hazards.overheadAtM?.let { drawHeadHeight(c, w, h, it) }
        if (!simplified && !danger) for (tr in relevant(s.tracks).take(3)) drawObject(c, tr, w, h)
        if (danger) { // the whole view becomes the warning frame; the scene dims so the words dominate
            fill.color = (t.bg and 0x00FFFFFF) or (0x66 shl 24)
            c.drawRect(0f, 0f, w, h, fill)
            line.pathEffect = null; line.color = t.dangerLine; line.strokeWidth = t.px(10f)
            c.drawRect(t.px(5f), t.px(5f), w - t.px(5f), h - t.px(5f), line)
            drawDrop(c, w, h)
        }
    }

    private fun buildDepth(g: Array<FloatArray>) {
        val hcStyle = Prefs.depthHc || t.hc
        if (g === lastGrid && hcStyle == lastStyleHc) return
        lastGrid = g; lastStyleHc = hcStyle
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (r in g) for (v in r) { if (v < lo) lo = v; if (v > hi) hi = v }
        val span = (hi - lo).coerceAtLeast(1e-6f)
        val levels = 6
        for (y in 0 until bh) {
            val gy = ((y + 0.5f) / up - 0.5f).coerceIn(0f, DEPTH_ROWS - 1f)
            val y0 = gy.toInt(); val y1 = min(y0 + 1, DEPTH_ROWS - 1); val fy = gy - y0
            for (x in 0 until bw) {
                val gx = ((x + 0.5f) / up - 0.5f).coerceIn(0f, DEPTH_COLS - 1f)
                val x0 = gx.toInt(); val x1 = min(x0 + 1, DEPTH_COLS - 1); val fx = gx - x0
                val v = (g[y0][x0] * (1 - fx) + g[y0][x1] * fx) * (1 - fy) + (g[y1][x0] * (1 - fx) + g[y1][x1] * fx) * fy
                band[y * bw + x] = (((v - lo) / span) * levels).toInt().coerceIn(0, levels - 1) // 5 = nearest
            }
        }
        for (i in px.indices) {
            val b = band[i]
            val x = i % bw
            val edge = (x + 1 < bw && band[i + 1] != b) || (i + bw < px.size && band[i + bw] != b)
            px[i] = if (edge) (if (hcStyle) t.text else 0xFFFFFFFF.toInt()) else shade(b, levels, hcStyle)
        }
        depthBmp.setPixels(px, 0, bw, 0, 0, bw, bh)
    }

    /** Near = bright. Standard: dark navy to cyan-white. High contrast: greys between bg and fg. */
    private fun shade(b: Int, levels: Int, hc: Boolean): Int {
        val f = (b + 0.5f) / levels
        if (hc) return mix(t.bg, t.text, 0.08f + 0.6f * f)
        return mix(0xFF0B1A2A.toInt(), 0xFFBFEFFF.toInt(), f * f)
    }

    private fun mix(a: Int, b: Int, f: Float): Int {
        fun ch(sh: Int) = ((a shr sh and 255) * (1 - f) + (b shr sh and 255) * f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun drawFloor(c: Canvas, w: Float, h: Float) {
        val d = s.drop ?: return
        if (!s.floorTrusted || d.state != DropState.SAFE) return // only a floor we actually measured; else the edge speaks
        val top = h * d.groundRoiTop
        val p = Path().apply { moveTo(w * 0.36f, top); lineTo(w * 0.64f, top); lineTo(w * 0.8f, h * 0.97f); lineTo(w * 0.2f, h * 0.97f); close() }
        line.color = (t.accent and 0x00FFFFFF) or (0xB0 shl 24)
        line.strokeWidth = t.px(3f)
        line.pathEffect = DashPathEffect(floatArrayOf(t.px(14f), t.px(10f)), 0f)
        c.drawPath(p, line)
        line.pathEffect = null
        chip(c, "FLOOR", w / 2, (top + h * 0.97f) / 2, t.surface, t.text, center = true, sp = Theme.LABEL)
    }

    private fun drawDrop(c: Canvas, w: Float, h: Float) {
        val d = s.drop ?: return
        val e = d.candidate ?: return
        if (d.state != DropState.POSSIBLE_DROP && d.state != DropState.CONFIRMED_DROP) return
        val confirmed = d.state == DropState.CONFIRMED_DROP
        val col = if (confirmed) t.dangerLine else t.caution
        val y = h * e.y
        val x0 = w * e.x0; val x1 = w * e.x1
        // LOWER LEVEL: hatched band beyond the edge (hatching = meaning without colour).
        val bandTop = (y - h * 0.14f).coerceAtLeast(0f)
        c.save(); c.clipRect(x0, bandTop, x1, y)
        line.color = (col and 0x00FFFFFF) or (0x90 shl 24); line.strokeWidth = t.px(4f); line.pathEffect = null
        var k = x0 - (y - bandTop)
        while (k < x1) { c.drawLine(k, y, k + (y - bandTop), bandTop, line); k += t.px(18f) }
        c.restore()
        // DROP EDGE: a thick line with a contrasting outline so it reads on any background.
        line.color = t.bg; line.strokeWidth = t.px(if (confirmed) 16f else 12f)
        c.drawLine(x0, y, x1, y, line)
        line.color = col; line.strokeWidth = t.px(if (confirmed) 10f else 7f)
        c.drawLine(x0, y, x1, y, line)
        chip(c, "DROP EDGE", x0, y + t.px(14f), col, if (confirmed) t.onDanger.takeIf { !t.hc } ?: t.bg else t.onCaution, sp = Theme.LABEL)
        val dist = if (d.dropAheadM.isNaN()) "" else "  ↓ ${metresShort(d.dropAheadM)}"
        chip(c, "LOWER LEVEL$dist", x0, (bandTop + t.px(8f)).coerceAtMost(y - t.px(60f)).coerceAtLeast(t.px(8f)), t.surface, t.text, sp = Theme.LABEL)
    }

    private fun drawHeadHeight(c: Canvas, w: Float, h: Float, m: Float) {
        line.color = t.dangerLine; line.strokeWidth = t.px(8f); line.pathEffect = null
        c.drawLine(w * 0.1f, h * 0.2f, w * 0.9f, h * 0.2f, line)
        chip(c, "HEAD HEIGHT  ${metresShort(m)}", w * 0.1f, h * 0.2f + t.px(14f), t.surface, t.text, sp = Theme.LABEL)
    }

    private fun drawObject(c: Canvas, tr: app.nadaka.Track, w: Float, h: Float) {
        val r = RectF(tr.box.left * w, tr.box.top * h, tr.box.right * w, tr.box.bottom * h)
        val col = if (tr.approaching) t.caution else t.accent
        val k = min(r.width(), r.height()) * 0.25f
        line.pathEffect = null
        for ((wd, cl) in listOf(t.px(9f) to t.bg, t.px(5f) to col)) {
            line.strokeWidth = wd; line.color = cl
            for (corner in 0..3) {
                val x = if (corner % 2 == 0) r.left else r.right
                val y = if (corner < 2) r.top else r.bottom
                val dx = if (corner % 2 == 0) k else -k
                val dy = if (corner < 2) k else -k
                c.drawLine(x, y, x + dx, y, line); c.drawLine(x, y, x, y + dy, line)
            }
        }
        val dist = if (tr.metres.isNaN()) "" else "  ${metresShort(tr.metres)}"
        chip(c, "${tr.label.uppercase()}$dist", r.left, (r.top - t.px(52f)).coerceAtLeast(t.px(8f)), t.surface, t.text, sp = Theme.LABEL)
    }

    /** A solid label plate: text never sits directly on the camera image. */
    private fun chip(c: Canvas, s: String, x: Float, y: Float, bg: Int, fg: Int, center: Boolean = false, sp: Float) {
        text.textSize = sp * t.scale * t.fontScale * t.dp
        text.typeface = t.bold
        text.color = fg
        val pad = t.px(12f)
        val tw = text.measureText(s)
        val left = (if (center) x - tw / 2 - pad else x).coerceIn(t.px(6f), (width - tw - 2 * pad - t.px(6f)).coerceAtLeast(t.px(6f)))
        val hgt = text.textSize + 2 * pad * 0.8f
        fill.color = bg
        c.drawRoundRect(RectF(left, y, left + tw + 2 * pad, y + hgt), hgt / 2, hgt / 2, fill)
        line.color = t.text; line.strokeWidth = t.px(2f); line.pathEffect = null
        if (bg == t.surface) c.drawRoundRect(RectF(left, y, left + tw + 2 * pad, y + hgt), hgt / 2, hgt / 2, line)
        c.drawText(s, left + pad, y + hgt / 2 - (text.ascent() + text.descent()) / 2, text)
    }
}
