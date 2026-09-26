package app.nadaka

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View

/**
 * Screen palettes for low-vision users. Every text/background pair is >= 7:1 contrast (WCAG AAA):
 * yellow on black 19.6:1, white on black 21:1, black on yellow 19.6:1.
 * STANDARD is the sighted/judge view (amber on dark cards, >= 9:1 for text).
 */
enum class Palette(val label: String, val fg: Int, val bg: Int) {
    STANDARD("Standard", Color.WHITE, 0xFF101820.toInt()),
    YELLOW_ON_BLACK("Yellow on black", 0xFFFFFF00.toInt(), Color.BLACK),
    WHITE_ON_BLACK("White on black", Color.WHITE, Color.BLACK),
    BLACK_ON_YELLOW("Black on yellow", Color.BLACK, 0xFFFFFF00.toInt());

    val highContrast get() = this != STANDARD
}

enum class CameraView(val label: String) { NORMAL("Normal"), HIGH_CONTRAST("High contrast"), INVERTED("Inverted"), HIDDEN("Hidden") }

/** WCAG relative-luminance contrast ratio, used by the tests to prove every palette pair is >= 7:1. */
fun contrast(a: Int, b: Int): Double {
    fun lum(c: Int): Double {
        fun ch(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(c shr 16 and 255) + 0.7152 * ch(c shr 8 and 255) + 0.0722 * ch(c and 255)
    }
    val (x, y) = lum(a) to lum(b)
    return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
}

/** Settings a user (or caregiver) sets once; they survive restarts. */
object Prefs {
    var palette = Palette.STANDARD
    var textScale = 1f
    var camera = CameraView.NORMAL
    var announce = false // speak each button's name/state when pressed
    var wizardDone = false
    var audioOn = true
    var hapticOn = true
    var simplified = false      // hide annotations and object list: safety state only
    var depthHc = false         // depth map as brightness bands + contour lines
    var reducedMotion = false
    var heroMode = app.nadaka.ui.HeroMode.BLEND

    val lowVision get() = palette.highContrast && textScale >= 1.3f

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("nadaka", Context.MODE_PRIVATE)
        palette = runCatching { Palette.valueOf(p.getString("palette", "STANDARD")!!) }.getOrDefault(Palette.STANDARD)
        textScale = p.getFloat("textScale", 1f)
        camera = runCatching { CameraView.valueOf(p.getString("camera", "NORMAL")!!) }.getOrDefault(CameraView.NORMAL)
        announce = p.getBoolean("announce", false)
        wizardDone = p.getBoolean("wizardDone", false)
        Settings.hapticsFirst = p.getBoolean("hapticsFirst", true)
        Settings.chatty = p.getBoolean("chatty", false)
        audioOn = p.getBoolean("audioOn", true)
        hapticOn = p.getBoolean("hapticOn", true)
        simplified = p.getBoolean("simplified", false)
        depthHc = p.getBoolean("depthHc", false)
        val systemNoMotion = android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        reducedMotion = p.getBoolean("reducedMotion", systemNoMotion)
        heroMode = runCatching { app.nadaka.ui.HeroMode.valueOf(p.getString("heroMode", "BLEND")!!) }.getOrDefault(app.nadaka.ui.HeroMode.BLEND)
    }

    fun save(ctx: Context) = ctx.getSharedPreferences("nadaka", Context.MODE_PRIVATE).edit()
        .putString("palette", palette.name).putFloat("textScale", textScale).putString("camera", camera.name)
        .putBoolean("announce", announce).putBoolean("wizardDone", wizardDone)
        .putBoolean("hapticsFirst", Settings.hapticsFirst).putBoolean("chatty", Settings.chatty)
        .putBoolean("audioOn", audioOn).putBoolean("hapticOn", hapticOn).putBoolean("simplified", simplified)
        .putBoolean("depthHc", depthHc).putBoolean("reducedMotion", reducedMotion).putString("heroMode", heroMode.name).apply()

    /** One switch for the whole "Low Vision / Senior" profile. */
    fun applyLowVision(on: Boolean) {
        if (on) { palette = Palette.YELLOW_ON_BLACK; textScale = 1.3f; camera = CameraView.HIGH_CONTRAST; announce = true; depthHc = true }
        else { palette = Palette.STANDARD; textScale = 1f; camera = CameraView.NORMAL; announce = false; depthHc = false }
    }

    /** What caregivers see in the header so they can verify the profile is active. */
    fun status(): String = if (palette == Palette.STANDARD && textScale == 1f && camera == CameraView.NORMAL && !announce) ""
    else listOfNotNull(
        if (lowVision) "Low vision ON" else null, palette.label, "${fmt(textScale)}× text",
        "camera ${camera.label.lowercase()}", if (announce) "announce" else null,
    ).joinToString(" · ")

    private fun fmt(f: Float) = if (f % 1f == 0f) f.toInt().toString() else f.toString()
}

/** Camera preview filter: contrast boost (edges stand out) or colour inversion; or hide the image. */
fun applyCameraView(preview: View) {
    preview.visibility = if (Prefs.camera == CameraView.HIDDEN) View.INVISIBLE else View.VISIBLE
    val m = when (Prefs.camera) {
        CameraView.HIGH_CONTRAST -> ColorMatrix().apply {
            setSaturation(0f) // greyscale, then stretch contrast 1.8x around mid-grey
            postConcat(ColorMatrix(floatArrayOf(1.8f, 0f, 0f, 0f, -102f, 0f, 1.8f, 0f, 0f, -102f, 0f, 0f, 1.8f, 0f, -102f, 0f, 0f, 0f, 1f, 0f)))
        }
        CameraView.INVERTED -> ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
        else -> null
    }
    val paint = m?.let { Paint().apply { colorFilter = ColorMatrixColorFilter(it) } }
    if (preview is android.view.TextureView) preview.setLayerPaint(paint) // TextureView is always a hardware layer
    else preview.setLayerType(if (m == null) View.LAYER_TYPE_NONE else View.LAYER_TYPE_HARDWARE, paint)
}
