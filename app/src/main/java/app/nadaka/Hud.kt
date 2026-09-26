package app.nadaka

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Everything the screen shows; built on the analysis thread, drawn on the UI thread. */
data class HudState(
    val mode: String = "WALKING",
    val backend: String = "",
    val depthBackend: String = "",
    val fps: Int = 0,
    val detMs: Long = 0,
    val depthMs: Long = 0,
    val health: Health = Health.OK,
    val heat: HeatTier = HeatTier.NOMINAL,
    val rec: String = "",
    val tracks: List<Track> = emptyList(),
    val hazards: Hazards = Hazards(),
    val said: String = "",
    val level: Buzz? = null,
    val depth: Array<FloatArray>? = null,
    val imgW: Int = 3,
    val imgH: Int = 4,
)

/**
 * The sighted view: judges, trainers and family via Office Kit mirroring. A blind user never needs
 * it; the caption at the bottom is exactly what they hear.
 */
class Hud(ctx: Context) : View(ctx) {
    private var s = HudState()
    private val dp = resources.displayMetrics.density

    // Colours come from the chosen palette (A11y.kt); high-contrast palettes use one fg/bg pair only.
    private var amber = 0
    private var red = 0
    private var orange = 0
    private var green = 0
    private var grey = 0
    private var ink = 0
    private var card = 0
    private var txt = Color.WHITE // main text
    private var onDanger = Color.WHITE // text on a danger/red fill
    private var ts = 1f // text scale
    private var weight = 1f // outline weight

    private fun applyPalette() {
        val p = Prefs.palette
        ts = Prefs.textScale
        if (p.highContrast) {
            amber = p.fg; red = p.fg; orange = p.fg; green = p.fg; grey = p.fg; txt = p.fg
            ink = p.bg; onDanger = p.bg; card = p.bg; weight = 1.8f
        } else {
            amber = 0xFFFFB300.toInt(); red = 0xFFFF453A.toInt(); orange = 0xFFFF9F0A.toInt(); green = 0xFF32D74B.toInt()
            grey = 0xFFAEB7C2.toInt(); ink = 0xFF101820.toInt(); card = 0xE6101820.toInt(); txt = Color.WHITE; onDanger = Color.WHITE
            weight = 1f
        }
        body.color = grey
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = grey; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val scrim = Paint()

    fun show(state: HudState) {
        s = state
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        applyPalette()
        val scale = min(width / s.imgW.toFloat(), height / s.imgH.toFloat())
        val ox = (width - s.imgW * scale) / 2
        val oy = (height - s.imgH * scale) / 2
        val iw = s.imgW * scale
        val ih = s.imgH * scale

        // Readability scrims over the camera image.
        scrim.shader = LinearGradient(0f, 0f, 0f, 170 * dp, 0xCC000000.toInt(), 0, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), 170 * dp, scrim)
        scrim.shader = LinearGradient(0f, height - 320 * dp, 0f, height.toFloat(), 0, 0xE6000000.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, height - 320 * dp, width.toFloat(), height.toFloat(), scrim)

        s.hazards.dropAtM?.let {
            fill.color = 0x40FF453A
            c.drawRect(ox + iw * 0.3f, oy + ih * 0.55f, ox + iw * 0.7f, oy + ih, fill)
        }
        // Only what the user would be told about: no clutter from shaky or far detections.
        for (t in s.tracks) if (worthShowing(t)) drawTrack(c, t, ox, oy, iw, ih)

        drawHeader(c)
        drawHazardBanner(c)
        drawCaption(c)
        drawRadar(c)
        drawDepthThumb(c)
    }

    private fun worthShowing(t: Track) = t.approaching || (!t.metres.isNaN() && t.metres < Settings.veryCloseM) ||
        (t.sure && !t.metres.isNaN() && t.metres <= if (t.moving) Settings.movingRangeM else Settings.staticRangeM)

    private fun colorOf(t: Track) = when {
        t.approaching -> red
        t.metres < Settings.closeM -> orange
        else -> amber
    }

    private fun drawTrack(c: Canvas, t: Track, ox: Float, oy: Float, iw: Float, ih: Float) {
        val r = RectF(ox + t.box.left * iw, oy + t.box.top * ih, ox + t.box.right * iw, oy + t.box.bottom * ih)
        val col = colorOf(t)
        // Corner brackets: mark the object without hiding it.
        stroke.color = col
        stroke.strokeWidth = 3.5f * dp * weight
        val k = min(r.width(), r.height()) * 0.22f
        for (corner in 0..3) {
            val x = if (corner % 2 == 0) r.left else r.right
            val y = if (corner < 2) r.top else r.bottom
            val dx = if (corner % 2 == 0) k else -k
            val dy = if (corner < 2) k else -k
            c.drawLine(x, y, x + dx, y, stroke)
            c.drawLine(x, y, x, y + dy, stroke)
        }
        val label = buildString {
            append(t.label.replaceFirstChar { it.uppercase() })
            if (!t.metres.isNaN()) append("  ·  %.1f m".format(t.metres))
            if (t.approaching) append("  ·  approaching") else if (t.moving) append("  ·  moving")
        }
        bold.textSize = 13 * dp * ts
        val w = bold.measureText(label) + 20 * dp
        val top = (r.top - 30 * dp).coerceAtLeast(150 * dp)
        fill.color = col
        c.drawRoundRect(RectF(r.left, top, r.left + w, top + 24 * dp), 12 * dp, 12 * dp, fill)
        bold.color = if (t.approaching) onDanger else ink
        c.drawText(label, r.left + 10 * dp, top + 16.5f * dp, bold)
        bold.color = txt
    }

    private fun pill(c: Canvas, right: Float, y: Float, label: String, bg: Int, fg: Int): Float {
        bold.textSize = 12 * dp * ts
        val w = bold.measureText(label) + 24 * dp
        fill.color = bg
        c.drawRoundRect(RectF(right - w, y, right, y + 28 * dp), 14 * dp, 14 * dp, fill)
        bold.color = fg
        c.drawText(label, right - w + 12 * dp, y + 18.5f * dp, bold)
        bold.color = txt
        return right - w - 8 * dp
    }

    private fun drawHeader(c: Canvas) {
        val y = 44 * dp
        // Mark: three sonar arcs over a dot, as in the app icon.
        val mx = 32 * dp
        val my = y + 26 * dp
        stroke.color = amber
        stroke.strokeWidth = 2.5f * dp
        for (i in 1..3) {
            val rr = (4 + i * 5) * dp
            stroke.alpha = 255 - i * 55
            c.drawArc(RectF(mx - rr, my - rr, mx + rr, my + rr), 225f, 90f, false, stroke)
        }
        stroke.alpha = 255
        fill.color = txt
        c.drawCircle(mx, my, 3 * dp, fill)

        bold.textSize = 24 * dp * ts
        c.drawText("Nadaka", 56 * dp, y + 22 * dp, bold)
        body.textSize = 12 * dp * ts
        fill.color = if (s.backend == "NPU" || s.backend.isEmpty()) green else orange
        c.drawCircle(60 * dp, y + 38 * dp, 3.5f * dp, fill)
        val tech = if (s.backend.isEmpty()) "Reading, on-device"
        else "On-device ${s.backend}  ·  detect ${s.detMs} ms  ·  depth ${s.depthMs} ms  ·  ${s.fps} fps"
        c.drawText(tech, 70 * dp, y + 42 * dp, body)

        val modeLabel = when {
            s.said == "EMERGENCY" -> "EMERGENCY"
            s.said.startsWith("Finding") -> "FINDING"
            else -> when (s.mode) { "STILL" -> "STANDING"; "VEHICLE" -> "IN VEHICLE"; "READ" -> "READING"; else -> s.mode }
        }
        var right = width - 16 * dp
        right = pill(c, right, y + 2 * dp, modeLabel, amber, ink)
        if (s.rec.isNotEmpty()) pill(c, right, y + 2 * dp, s.rec, red, onDanger)
        var right2 = width - 16 * dp
        if (s.health != Health.OK) right2 = pill(c, right2, y + 60 * dp, s.health.name.lowercase().replaceFirstChar { it.uppercase() }, red, onDanger)
        Prefs.status().takeIf { it.isNotEmpty() }?.let { st -> // caregivers can verify the profile is on
            body.textSize = 11 * dp * ts
            c.drawText(st, 20 * dp, y + 118 * dp * ts, body)
        }
        if (s.heat != HeatTier.NOMINAL) pill(c, right2, y + 60 * dp, "Heat: ${s.heat.name.lowercase()}", if (s.heat == HeatTier.WARM) orange else red, onDanger)
    }

    private fun drawHazardBanner(c: Canvas) {
        val msg = s.hazards.dropAtM?.let { "Drop-off ahead  ·  %.1f m".format(it) }
            ?: s.hazards.overheadAtM?.let { "Head-height obstacle  ·  %.1f m".format(it) }
            ?: return
        val y = height * 0.40f
        fill.color = red
        c.drawRoundRect(RectF(24 * dp, y, width - 24 * dp, y + 52 * dp * ts), 26 * dp, 26 * dp, fill)
        bold.textSize = 18 * dp * ts
        bold.color = onDanger
        c.drawText(msg, (width - bold.measureText(msg)) / 2, y + 33 * dp * ts, bold)
        bold.color = txt
    }

    private fun captionTop() = height - (54 + 96 * ts) * dp // clear of the navigation bar

    private fun drawCaption(c: Canvas) {
        val top = captionTop()
        val box = RectF(16 * dp, top, width - 16 * dp, top + 96 * dp * ts)
        fill.color = card
        c.drawRoundRect(box, 22 * dp, 22 * dp, fill)
        fill.color = when (s.level) { Buzz.WARN, Buzz.APPROACH -> red; Buzz.AHEAD -> orange; else -> amber }
        c.drawRoundRect(RectF(box.left + 12 * dp, top + 16 * dp, box.left + 17 * dp, top + 80 * dp), 3 * dp, 3 * dp, fill)
        body.textSize = 11 * dp * ts
        c.drawText(if (Settings.hapticsFirst) "WHAT THE USER FEELS AND HEARS  ·  vibration first" else "WHAT THE USER HEARS", box.left + 30 * dp, top + 26 * dp, body)
        bold.textSize = 19 * dp * ts
        var line = ""
        var y = top + 54 * dp
        for (wd in s.said.ifEmpty { "Watching the path." }.split(" ")) {
            val next = if (line.isEmpty()) wd else "$line $wd"
            if (bold.measureText(next) > box.width() - 48 * dp) { c.drawText(line, box.left + 30 * dp, y, bold); line = wd; y += 24 * dp }
            else line = next
        }
        c.drawText(line, box.left + 30 * dp, y, bold)
    }

    /** Clock-face radar scaled to the alert ranges: inner ring 5 m (static), outer 10 m (moving). */
    private fun drawRadar(c: Canvas) {
        val rad = 58 * dp
        val cx = width - 24 * dp - rad
        val cy = captionTop() - 20 * dp
        fill.color = card
        c.drawRoundRect(RectF(cx - rad - 12 * dp, cy - rad - 22 * dp, cx + rad + 12 * dp, cy + 12 * dp), 20 * dp, 20 * dp, fill)
        stroke.strokeWidth = 1 * dp
        stroke.color = 0x66FFFFFF
        for (m in listOf(5f, 10f)) {
            val rr = rad * m / 10f
            c.drawArc(RectF(cx - rr, cy - rr, cx + rr, cy + rr), 210f, 120f, false, stroke)
        }
        body.textSize = 9 * dp * ts
        c.drawText("5 m", cx + 3 * dp, cy - rad / 2 - 3 * dp, body)
        c.drawText("10 m", cx + 3 * dp, cy - rad - 3 * dp, body)
        for (t in s.tracks) {
            if (!worthShowing(t) || t.metres.isNaN()) continue
            val a = t.bearing - Math.PI.toFloat() / 2
            val rr = rad * (t.metres.coerceAtMost(10f) / 10f)
            fill.color = colorOf(t)
            c.drawCircle(cx + rr * cos(a), cy + rr * sin(a), 5 * dp, fill)
        }
        fill.color = txt
        c.drawCircle(cx, cy, 4 * dp, fill)
    }

    private fun drawDepthThumb(c: Canvas) {
        val g = s.depth ?: return
        val w = 70 * dp
        val h = w * DEPTH_ROWS / DEPTH_COLS
        val x0 = 24 * dp
        val y0 = captionTop() - 20 * dp - h
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (row in g) for (v in row) { if (v < lo) lo = v; if (v > hi) hi = v }
        val cw = w / DEPTH_COLS
        val ch = h / DEPTH_ROWS
        for (r in 0 until DEPTH_ROWS) for (col in 0 until DEPTH_COLS) {
            val n = ((g[r][col] - lo) / (hi - lo + 1e-6f)).coerceIn(0f, 1f) // 1 = near
            fill.color = Color.rgb((30 + 225 * n).toInt(), (40 + 140 * n).toInt(), (120 * (1 - n) + 40).toInt())
            c.drawRect(x0 + col * cw, y0 + r * ch, x0 + (col + 1) * cw + 1, y0 + (r + 1) * ch + 1, fill)
        }
        stroke.color = 0x88FFFFFF.toInt()
        stroke.strokeWidth = 1 * dp
        c.drawRoundRect(RectF(x0, y0, x0 + w, y0 + h), 6 * dp, 6 * dp, stroke)
        body.textSize = 10 * dp * ts
        c.drawText("Depth · ${s.depthBackend}", x0, y0 - 6 * dp, body)
    }
}
