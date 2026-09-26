package app.nadaka.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.widget.TextView
import app.nadaka.Palette
import app.nadaka.Prefs

/**
 * One design system for every screen. Dark neutral base, one accent (cyan), and two safety colours
 * (amber caution, red danger) that only ever reinforce shape + words, never replace them.
 * High-contrast palettes collapse everything to one fg/bg pair; states are then told apart by
 * fill vs outline vs dashed outline, the glyph shape and the words.
 */
class Theme(val ctx: Context) {
    private val pal = Prefs.palette
    val hc = pal.highContrast

    val bg = if (hc) pal.bg else 0xFF0B0F14.toInt()
    val surface = if (hc) pal.bg else 0xFF161C24.toInt()
    val surface2 = if (hc) pal.bg else 0xFF222B36.toInt()
    val text = if (hc) pal.fg else Color.WHITE
    val textDim = if (hc) pal.fg else 0xFFC9D1DA.toInt()   // 11:1 on surface
    val accent = if (hc) pal.fg else 0xFF6FD3FF.toInt()    // active / selected / calm
    val onAccent = if (hc) pal.bg else 0xFF06131A.toInt()
    val caution = if (hc) pal.fg else 0xFFFFB020.toInt()
    val onCaution = if (hc) pal.bg else 0xFF1A1200.toInt()
    val danger = if (hc) pal.fg else 0xFFB00020.toInt()    // white on it: 7.3:1
    val dangerLine = if (hc) pal.fg else 0xFFFF4B3E.toInt()
    val onDanger = if (hc) pal.bg else Color.WHITE
    val scrim = if (hc) (pal.bg and 0x00FFFFFF) or (0xE0 shl 24) else 0xD90B0F14.toInt()

    val dp = ctx.resources.displayMetrics.density
    /** User text size x system font scale; everything text-related multiplies by this. */
    val scale = Prefs.textScale
    val fontScale = ctx.resources.configuration.fontScale
    val big get() = scale * fontScale >= 1.3f

    fun px(v: Float) = v * dp
    fun ipx(v: Float) = (v * dp).toInt()

    // Type scale (sp, before the user's text size). Nothing on screen is smaller than LABEL.
    companion object {
        const val DISPLAY = 60f   // STOP
        const val HEADLINE = 40f  // PATH CLEAR / DROP AHEAD
        const val DISTANCE = 44f
        const val TITLE = 28f
        const val BODY = 22f
        const val LABEL = 20f
        const val RADIUS = 28f
        const val GAP = 16f
        const val TOUCH = 80f      // minimum control height, dp
    }

    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val black: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    fun size(tv: TextView, sp: Float) = tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * scale)

    fun label(text: String = "", sp: Float = BODY, color: Int = this.text, face: Typeface = regular) = TextView(ctx).apply {
        this.text = text
        setTextColor(color)
        typeface = face
        size(this, sp)
        includeFontPadding = false
        setLineSpacing(0f, 1.1f)
    }

    /** Fits one line by shrinking (never below LABEL) instead of clipping at huge text sizes. */
    fun fit(tv: TextView, maxSp: Float) {
        tv.maxLines = 1
        val max = (maxSp * scale * fontScale).toInt().coerceAtLeast(LABEL.toInt())
        tv.setAutoSizeTextTypeUniformWithConfiguration(LABEL.toInt(), max, 1, TypedValue.COMPLEX_UNIT_SP)
    }

    fun box(fill: Int, stroke: Int = 0, strokeDp: Float = 0f, radiusDp: Float = RADIUS, dashed: Boolean = false) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = px(radiusDp)
        if (strokeDp > 0f) {
            if (dashed) setStroke(ipx(strokeDp), stroke, px(18f), px(10f)) else setStroke(ipx(strokeDp), stroke)
        }
    }

    /** Contrast choice as the user sees it: Standard / High / Maximum. */
    fun contrastIndex() = when (pal) { Palette.STANDARD -> 0; Palette.WHITE_ON_BLACK -> 1; else -> 2 }
}
