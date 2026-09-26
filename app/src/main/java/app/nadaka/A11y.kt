package app.nadaka

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

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

    val lowVision get() = palette.highContrast && textScale >= 1.5f

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("nadaka", Context.MODE_PRIVATE)
        palette = runCatching { Palette.valueOf(p.getString("palette", "STANDARD")!!) }.getOrDefault(Palette.STANDARD)
        textScale = p.getFloat("textScale", 1f)
        camera = runCatching { CameraView.valueOf(p.getString("camera", "NORMAL")!!) }.getOrDefault(CameraView.NORMAL)
        announce = p.getBoolean("announce", false)
        wizardDone = p.getBoolean("wizardDone", false)
        Settings.hapticsFirst = p.getBoolean("hapticsFirst", true)
        Settings.chatty = p.getBoolean("chatty", false)
    }

    fun save(ctx: Context) = ctx.getSharedPreferences("nadaka", Context.MODE_PRIVATE).edit()
        .putString("palette", palette.name).putFloat("textScale", textScale).putString("camera", camera.name)
        .putBoolean("announce", announce).putBoolean("wizardDone", wizardDone)
        .putBoolean("hapticsFirst", Settings.hapticsFirst).putBoolean("chatty", Settings.chatty).apply()

    /** One switch for the whole "Low Vision / Senior" profile. */
    fun applyLowVision(on: Boolean) {
        if (on) { palette = Palette.YELLOW_ON_BLACK; textScale = 1.5f; camera = CameraView.HIGH_CONTRAST; announce = true }
        else { palette = Palette.STANDARD; textScale = 1f; camera = CameraView.NORMAL; announce = false }
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

/**
 * Large-button settings panel and first-run wizard, with live preview (the screen behind updates as you
 * choose). Real Android views, so TalkBack reads every button. Buttons optionally announce themselves.
 */
class SettingsPanel(
    private val ctx: Context,
    private val say: (String) -> Unit,
    private val onChange: () -> Unit,
    private val onLesson: () -> Unit,
) {
    private val dp = ctx.resources.displayMetrics.density
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt()) }
    val view = ScrollView(ctx).apply { addView(list); visibility = View.GONE; isFillViewport = false }
    val open get() = view.visibility == View.VISIBLE
    var onOpenChange: (Boolean) -> Unit = {}

    fun show(wizard: Boolean = false) {
        build(wizard)
        view.visibility = View.VISIBLE
        onOpenChange(true)
        if (wizard) say("Welcome to Nadaka. Display setup is open. For big, high contrast text, tap Low vision preset. Otherwise tap Done, at the bottom. Voice and vibration work without the screen.")
    }

    fun hide() {
        view.visibility = View.GONE
        onOpenChange(false)
        if (!Prefs.wizardDone) { Prefs.wizardDone = true; Prefs.save(ctx) }
    }

    private fun build(wizard: Boolean) {
        val pal = Prefs.palette
        val fg = if (pal.highContrast) pal.fg else Color.WHITE
        val bg = if (pal.highContrast) pal.bg else 0xF2101820.toInt()
        val accent = if (pal.highContrast) pal.fg else 0xFFFFB300.toInt()
        view.setBackgroundColor(bg)
        list.removeAllViews()
        list.addView(TextView(ctx).apply {
            text = if (wizard) "Welcome to Nadaka\nSet up the display" else "Settings"
            setTextColor(fg); textSize(26f); setPadding(0, 0, 0, (12 * dp).toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        fun item(label: String, action: () -> Unit) = list.addView(Button(ctx).apply {
            text = label
            isAllCaps = false
            textSize(20f)
            setTextColor(if (pal.highContrast) pal.bg else 0xFF101820.toInt())
            background = GradientDrawable().apply { setColor(accent); cornerRadius = 18 * dp; setStroke((3 * dp).toInt(), fg) }
            minHeight = (64 * dp * Prefs.textScale).toInt()
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding((18 * dp).toInt(), 0, (18 * dp).toInt(), 0)
            contentDescription = label
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (12 * dp).toInt() }
            setOnClickListener {
                action()
                Prefs.save(ctx)
                onChange()
                build(wizard) // re-render in the new palette/size: live preview
                if (Prefs.announce) say(announceText())
            }
        })
        item("Low vision / senior preset: ${if (Prefs.lowVision) "ON" else "OFF"}") { Prefs.applyLowVision(!Prefs.lowVision) }
        item("Colours: ${pal.label}") { Prefs.palette = Palette.entries[(pal.ordinal + 1) % Palette.entries.size] }
        item("Text size: ${Prefs.textScale}×") { Prefs.textScale = when (Prefs.textScale) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f } }
        item("Camera view: ${Prefs.camera.label}") { Prefs.camera = CameraView.entries[(Prefs.camera.ordinal + 1) % CameraView.entries.size] }
        item("Announce buttons: ${if (Prefs.announce) "ON" else "OFF"}") { Prefs.announce = !Prefs.announce }
        item("Alerts: ${if (Settings.hapticsFirst) "vibration first" else "speech"}") { Settings.hapticsFirst = !Settings.hapticsFirst }
        item("Teach me the vibrations") { onLesson() }
        item("Done") { hide() }
    }

    private fun announceText() = listOf(
        if (Prefs.lowVision) "Low vision preset on" else "Low vision preset off",
        Prefs.palette.label, "text ${Prefs.textScale} times", "camera ${Prefs.camera.label}",
    ).joinToString(", ")

    private fun TextView.textSize(sp: Float) = setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * Prefs.textScale)
}
