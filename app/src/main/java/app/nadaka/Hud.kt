package app.nadaka

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Everything the screen shows; built on the analysis thread, drawn on the UI thread. */
data class HudState(
    val mode: String = "WALK",
    val backend: String = "",
    val depthBackend: String = "",
    val fps: Int = 0,
    val detMs: Long = 0,
    val depthMs: Long = 0,
    val walkMps: Float = 0f,
    val health: Health = Health.OK,
    val heat: HeatTier = HeatTier.NOMINAL,
    val rec: String = "",
    val tracks: List<Track> = emptyList(),
    val hazards: Hazards = Hazards(),
    val said: String = "",
    val depth: Array<FloatArray>? = null,
    val imgW: Int = 3,
    val imgH: Int = 4,
)

/**
 * The sighted view (judges, trainers, caregivers via Office Kit mirroring). The blind user never
 * needs it: the caption at the bottom is exactly what they hear.
 */
class Hud(ctx: Context) : View(ctx) {
    private var s = HudState()
    private val dp = resources.displayMetrics.density

    private val amber = 0xFFFFB300.toInt()
    private val red = 0xFFFF453A.toInt()
    private val orange = 0xFFFF9F0A.toInt()
    private val green = 0xFF32D74B.toInt()
    private val blue = 0xFF64D2FF.toInt() // unsure
    private val card = 0xD910151C.toInt()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB8C2CC.toInt(); typeface = Typeface.DEFAULT }

    fun show(state: HudState) {
        s = state
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val scale = min(width / s.imgW.toFloat(), height / s.imgH.toFloat())
        val ox = (width - s.imgW * scale) / 2
        val oy = (height - s.imgH * scale) / 2
        val iw = s.imgW * scale
        val ih = s.imgH * scale

        // Floor band tint when the floor drops away.
        s.hazards.dropAtM?.let {
            fill.color = 0x55FF453A
            c.drawRect(ox + iw * 0.3f, oy + ih * 0.55f, ox + iw * 0.7f, oy + ih, fill)
        }

        for (t in s.tracks) drawTrack(c, t, ox, oy, iw, ih)
        drawTopBar(c)
        drawRadar(c)
        drawHazardBanner(c)
        drawDepthThumb(c)
        drawCaption(c)
    }

    private fun drawTrack(c: Canvas, t: Track, ox: Float, oy: Float, iw: Float, ih: Float) {
        val r = RectF(ox + t.box.left * iw, oy + t.box.top * ih, ox + t.box.right * iw, oy + t.box.bottom * ih)
        val close = t.metres < Settings.closeM
        val col = when { t.approaching -> red; close -> orange; !t.sure -> blue; else -> amber }
        stroke.color = col
        stroke.strokeWidth = (if (t.approaching) 4f else if (t.sure) 2.5f else 1.5f) * dp
        c.drawRoundRect(r, 10 * dp, 10 * dp, stroke)

        val label = buildString {
            append(t.label)
            if (!t.sure) append("?")
            if (!t.metres.isNaN()) append("  %.1f m".format(t.metres))
            if (t.moving && !t.approaching) append("  moving")
            if (t.approaching) append("  ▲ %.1fs".format(t.ttc))
        }
        text.textSize = 13 * dp
        val w = text.measureText(label) + 16 * dp
        val top = (r.top - 26 * dp).coerceAtLeast(0f)
        fill.color = col
        c.drawRoundRect(RectF(r.left, top, r.left + w, top + 22 * dp), 8 * dp, 8 * dp, fill)
        text.color = if (t.approaching) Color.WHITE else Color.BLACK
        c.drawText(label, r.left + 8 * dp, top + 16 * dp, text)
        text.color = Color.WHITE
    }

    private fun chip(c: Canvas, x: Float, y: Float, label: String, bg: Int, fg: Int = Color.BLACK): Float {
        text.textSize = 12 * dp
        val w = text.measureText(label) + 18 * dp
        fill.color = bg
        c.drawRoundRect(RectF(x, y, x + w, y + 24 * dp), 12 * dp, 12 * dp, fill)
        text.color = fg
        c.drawText(label, x + 9 * dp, y + 16.5f * dp, text)
        text.color = Color.WHITE
        return x + w + 6 * dp
    }

    private fun drawTopBar(c: Canvas) {
        val top = 36 * dp
        fill.color = card
        c.drawRoundRect(RectF(12 * dp, top, width - 12 * dp, top + 64 * dp), 18 * dp, 18 * dp, fill)
        text.textSize = 20 * dp
        text.color = amber
        c.drawText("Nadaka", 26 * dp, top + 27 * dp, text)
        text.color = Color.WHITE
        small.textSize = 11 * dp
        c.drawText("%d fps  ·  speed %.1f m/s".format(s.fps, s.walkMps), 26 * dp, top + 47 * dp, small)

        var x = width * 0.44f
        x = chip(c, x, top + 8 * dp, s.mode, amber)
        if (s.backend.isNotEmpty()) x = chip(c, x, top + 8 * dp, "YOLO ${s.backend} ${s.detMs}ms", if (s.backend == "NPU") green else orange)
        var y2 = top + 36 * dp
        var x2 = width * 0.44f
        if (s.depthBackend.isNotEmpty()) x2 = chip(c, x2, y2, "DEPTH ${s.depthBackend} ${s.depthMs}ms", if (s.depthBackend == "NPU") green else orange)
        if (s.rec.isNotEmpty()) chip(c, x2, y2, s.rec, red, Color.WHITE)
        var x3 = width * 0.44f
        if (s.health != Health.OK) x3 = chip(c, x3, top + 68 * dp, s.health.name, red, Color.WHITE)
        if (s.heat != HeatTier.NOMINAL) chip(c, x3, top + 68 * dp, "HEAT ${s.heat.name}", if (s.heat == HeatTier.WARM) orange else red, Color.WHITE)
    }

    /** Clock-face radar: 10 to 2 o'clock, rings at 1, 2, 3 m. */
    private fun drawRadar(c: Canvas) {
        val rad = 64 * dp
        val cx = width - 20 * dp - rad
        val cy = 36 * dp + 64 * dp + 16 * dp + rad
        fill.color = card
        c.drawRoundRect(RectF(cx - rad - 8 * dp, cy - rad - 8 * dp, cx + rad + 8 * dp, cy + 18 * dp), 16 * dp, 16 * dp, fill)
        stroke.color = 0x55FFFFFF
        stroke.strokeWidth = 1 * dp
        for (m in 1..3) {
            val rr = rad * m / 3f
            c.drawArc(RectF(cx - rr, cy - rr, cx + rr, cy + rr), 210f, 120f, false, stroke)
        }
        small.textSize = 9 * dp
        for (h in listOf(10, 11, 12, 1, 2)) {
            val deg = (if (h == 12) 0 else if (h < 12) (h - 12) else h) * 30f
            val a = Math.toRadians((deg - 90).toDouble())
            c.drawText("$h", cx + (rad + 1 * dp) * cos(a).toFloat() - 4 * dp, cy + (rad + 1 * dp) * sin(a).toFloat(), small)
        }
        for (t in s.tracks) {
            val m = t.metres
            if (m.isNaN()) continue
            val a = t.bearing - Math.PI.toFloat() / 2
            val rr = rad * (m.coerceAtMost(3.2f) / 3.2f)
            fill.color = when { t.approaching -> red; m < Settings.closeM -> orange; else -> amber }
            c.drawCircle(cx + rr * cos(a), cy + rr * sin(a), 5 * dp, fill)
        }
        fill.color = Color.WHITE
        c.drawCircle(cx, cy, 4 * dp, fill) // me
    }

    private fun drawHazardBanner(c: Canvas) {
        val msg = s.hazards.dropAtM?.let { "DROP-OFF AHEAD  ·  %.1f m".format(it) }
            ?: s.hazards.overheadAtM?.let { "HEAD HEIGHT  ·  %.1f m".format(it) }
            ?: return
        val y = height * 0.42f
        fill.color = red
        c.drawRoundRect(RectF(24 * dp, y, width - 24 * dp, y + 48 * dp), 14 * dp, 14 * dp, fill)
        text.textSize = 18 * dp
        val w = text.measureText(msg)
        c.drawText(msg, (width - w) / 2, y + 31 * dp, text)
    }

    private fun drawDepthThumb(c: Canvas) {
        val g = s.depth ?: return
        val w = 84 * dp
        val h = w * DEPTH_ROWS / DEPTH_COLS
        val x0 = 16 * dp
        val y0 = height - 206 * dp - h
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (row in g) for (v in row) { if (v < lo) lo = v; if (v > hi) hi = v }
        val cw = w / DEPTH_COLS
        val chh = h / DEPTH_ROWS
        for (r in 0 until DEPTH_ROWS) for (col in 0 until DEPTH_COLS) {
            val n = ((g[r][col] - lo) / (hi - lo + 1e-6f)).coerceIn(0f, 1f) // 1 = near
            fill.color = Color.rgb((40 + 215 * n).toInt(), (60 + 120 * n * (1 - n) * 4).toInt().coerceAtMost(255), (200 * (1 - n)).toInt())
            c.drawRect(x0 + col * cw, y0 + r * chh, x0 + (col + 1) * cw + 1, y0 + (r + 1) * chh + 1, fill)
        }
        stroke.color = 0x88FFFFFF.toInt()
        stroke.strokeWidth = 1 * dp
        c.drawRect(x0, y0, x0 + w, y0 + h, stroke)
        small.textSize = 10 * dp
        c.drawText("depth", x0 + 4 * dp, y0 - 4 * dp, small)
    }

    private fun drawCaption(c: Canvas) {
        val h = 110 * dp
        val top = height - h - 76 * dp // clear of the navigation bar
        fill.color = card
        c.drawRoundRect(RectF(12 * dp, top, width - 12 * dp, top + h), 20 * dp, 20 * dp, fill)
        small.textSize = 11 * dp
        c.drawText("THE USER HEARS", 28 * dp, top + 24 * dp, small)
        text.textSize = 20 * dp
        val words = s.said.ifEmpty { "…" }.split(" ")
        var line = ""
        var y = top + 52 * dp
        for (wd in words) {
            val next = if (line.isEmpty()) wd else "$line $wd"
            if (text.measureText(next) > width - 60 * dp) { c.drawText(line, 28 * dp, y, text); line = wd; y += 26 * dp }
            else line = next
        }
        c.drawText(line, 28 * dp, y, text)
    }
}
