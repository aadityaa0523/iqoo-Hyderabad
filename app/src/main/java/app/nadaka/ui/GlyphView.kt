package app.nadaka.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** The state symbol. Each state has its own outline shape, so it reads in greyscale and at a glance. */
class GlyphView(ctx: Context) : View(ctx) {
    var glyph = Glyph.CHECK; set(v) { field = v; invalidate() }
    var color = 0; set(v) { field = v; invalidate() }
    var ink = 0; set(v) { field = v; invalidate() }   // colour of the mark inside a filled shape
    var filled = false; set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO } // the card reads the words

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        val cx = width / 2f; val cy = height / 2f; val r = s * 0.44f
        val sw = s * 0.08f
        val shape = Path()
        when (glyph) {
            Glyph.CHECK, Glyph.WAIT, Glyph.OFF, Glyph.PAUSE -> shape.addCircle(cx, cy, r, Path.Direction.CW)
            Glyph.WARN -> { shape.moveTo(cx, cy - r); shape.lineTo(cx + r * 1.05f, cy + r * 0.8f); shape.lineTo(cx - r * 1.05f, cy + r * 0.8f); shape.close() }
            Glyph.STOP -> {
                for (i in 0..7) {
                    val a = Math.PI / 8 + i * Math.PI / 4
                    val x = cx + r * cos(a).toFloat(); val y = cy + r * sin(a).toFloat()
                    if (i == 0) shape.moveTo(x, y) else shape.lineTo(x, y)
                }
                shape.close()
            }
        }
        p.color = color
        p.style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
        p.strokeWidth = sw
        c.drawPath(shape, p)
        p.color = if (filled) ink else color
        p.style = Paint.Style.STROKE
        p.strokeWidth = sw * 1.1f
        when (glyph) {
            Glyph.CHECK -> { val m = Path(); m.moveTo(cx - r * 0.45f, cy); m.lineTo(cx - r * 0.1f, cy + r * 0.35f); m.lineTo(cx + r * 0.5f, cy - r * 0.35f); c.drawPath(m, p) }
            Glyph.WARN, Glyph.STOP -> {
                val top = if (glyph == Glyph.WARN) cy - r * 0.3f else cy - r * 0.5f
                c.drawLine(cx, top, cx, cy + r * 0.15f, p)
                p.style = Paint.Style.FILL
                c.drawCircle(cx, cy + r * (if (glyph == Glyph.WARN) 0.48f else 0.45f), sw * 0.7f, p)
            }
            Glyph.WAIT -> { p.style = Paint.Style.FILL; for (i in -1..1) c.drawCircle(cx + i * r * 0.4f, cy, sw * 0.7f, p) }
            Glyph.OFF -> c.drawLine(cx - r * 0.62f, cy - r * 0.62f, cx + r * 0.62f, cy + r * 0.62f, p)
            Glyph.PAUSE -> { c.drawLine(cx - r * 0.22f, cy - r * 0.35f, cx - r * 0.22f, cy + r * 0.35f, p); c.drawLine(cx + r * 0.22f, cy - r * 0.35f, cx + r * 0.22f, cy + r * 0.35f, p) }
        }
    }
}
