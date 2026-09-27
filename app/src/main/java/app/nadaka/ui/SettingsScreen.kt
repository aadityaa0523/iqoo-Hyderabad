package app.nadaka.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.nadaka.CameraView
import app.nadaka.Palette
import app.nadaka.Prefs
import app.nadaka.Settings

/**
 * Full-screen settings. Every choice is a row of large option buttons; the chosen one is filled, bold,
 * and starts with a check mark (so selection never depends on colour). Changes apply live to the
 * screen underneath. The first run opens it as a short welcome.
 */
class SettingsScreen(private val act: Activity, private val screen: LiveScreen, private val actions: Actions) {
    private var t = Theme(act)
    private val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(act).apply { addView(list) }
    val view = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; isClickable = true }
    private val top = LinearLayout(act)
    private val title = TextView(act)
    private val done = Button(act)
    private var wizard = false
    val open get() = view.visibility == View.VISIBLE

    init {
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(done)
        view.addView(top, LinearLayout.LayoutParams(-1, -2))
        view.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        done.setOnClickListener { hide() }
        title.isAccessibilityHeading = true
    }

    fun show(wizard: Boolean = false) {
        this.wizard = wizard
        build()
        view.visibility = View.VISIBLE
        scroll.scrollTo(0, 0)
        if (wizard) actions.say("Welcome to Nadaka. Settings are open. For more accurate distances, tap Calibrate, 30 seconds. For bigger text, choose a text size. Tap Done at the top when finished. " + app.nadaka.FALL_GUIDE)
    }

    fun hide() {
        view.visibility = View.GONE
        if (!Prefs.wizardDone) { Prefs.wizardDone = true; Prefs.save(act) }
    }

    fun restyle() { if (open) build() }

    private fun changed() {
        Prefs.save(act)
        actions.displayChanged()
        screen.restyle() // re-renders this screen too: live preview
    }

    private fun build() {
        t = Theme(act)
        view.setBackgroundColor(t.bg)
        top.setPadding(t.ipx(24f), t.ipx(16f), t.ipx(16f), t.ipx(12f))
        title.text = if (wizard) "Welcome" else "Settings"
        title.setTextColor(t.text); title.typeface = t.black; t.size(title, 34f)
        done.text = "DONE"
        done.isAllCaps = false; done.typeface = t.bold; t.size(done, Theme.BODY); done.stateListAnimator = null
        done.setTextColor(t.onAccent); done.background = t.box(t.accent, t.text, if (t.hc) 3f else 0f, 22f)
        done.minHeight = t.ipx(Theme.TOUCH * 0.8f); done.setPadding(t.ipx(28f), 0, t.ipx(28f), 0)
        done.contentDescription = "Done. Close settings."
        list.removeAllViews()
        list.setPadding(t.ipx(20f), t.ipx(4f), t.ipx(20f), t.ipx(40f))

        section("Accessibility")
        choice("Text size", listOf("Small", "Large", "Extra large", "Maximum"), TEXT_SIZES.indexOfFirst { it >= Prefs.textScale - 0.01f }.coerceAtLeast(0)) {
            Prefs.textScale = TEXT_SIZES[it]
        }
        choice("Contrast", listOf("Standard", "High", "Maximum"), t.contrastIndex()) {
            Prefs.palette = listOf(Palette.STANDARD, Palette.WHITE_ON_BLACK, Palette.YELLOW_ON_BLACK)[it]
        }
        choice("Visual detail", listOf("Standard", "Simplified"), if (Prefs.simplified) 1 else 0) { Prefs.simplified = it == 1 }
        choice("Motion", listOf("Reduced", "Standard"), if (Prefs.reducedMotion) 0 else 1) { Prefs.reducedMotion = it == 0 }
        choice("Main view", listOf(HeroMode.CAMERA, HeroMode.BLEND, HeroMode.DEPTH).map { it.label },
            listOf(HeroMode.CAMERA, HeroMode.BLEND, HeroMode.DEPTH).indexOf(Prefs.heroMode)) {
            Prefs.heroMode = listOf(HeroMode.CAMERA, HeroMode.BLEND, HeroMode.DEPTH)[it]
        }
        if (Prefs.heroMode != HeroMode.CAMERA) choice("Depth map colours", listOf("Smooth", "High contrast"), if (Prefs.depthHc) 1 else 0,
            "Depth map colours. High contrast uses brightness bands and contour lines.") { Prefs.depthHc = it == 1 }
        choice("Camera image", CameraView.entries.map { it.label }, Prefs.camera.ordinal) { Prefs.camera = CameraView.entries[it] }
        toggle("Low vision preset", Prefs.lowVision, "Maximum contrast, extra large text, high contrast camera and depth.") { Prefs.applyLowVision(it) }

        section("Calibration")
        action("Calibrate  ·  30 seconds") { hide(); actions.calibrate() }
        list.addView(t.label(
            if (Prefs.calibrated) "Calibrated on " + java.text.DateFormat.getDateInstance().format(java.util.Date(Prefs.calibratedAtMs)) +
                (if (Prefs.bodyHeightM.isNaN()) "." else ", height ${"%.0f".format(Prefs.bodyHeightM * 100)} cm.")
            else "Not calibrated yet. Voice-guided: say your height, hold the phone, then two short walks: press volume down, walk, press again.",
            Theme.LABEL, t.textDim).apply { setPadding(t.ipx(4f), t.ipx(6f), 0, 0) })

        section("Cloud AI")
        choice("Answer questions", app.nadaka.AiMode.entries.map { it.label }, Prefs.aiMode.ordinal,
            "Where questions like what is this are answered") { Prefs.aiMode = app.nadaka.AiMode.entries[it] }

        section("Emergency contacts")
        Prefs.contacts.forEachIndexed { i, c -> action("Remove ${c.name}") { actions.removeContact(i); changed() } }
        if (Prefs.contacts.size < 2) action("Add emergency contact") { actions.addContact() }
        if (Prefs.contacts.isNotEmpty()) action("Send test message") { actions.testSms() }
        list.addView(t.label(
            if (Prefs.contacts.isEmpty()) "After a fall or an emergency, they get a text with a map link to where you are."
            else "They get a text with your location when the siren starts: " + Prefs.contacts.joinToString(", ") { it.name } + ".",
            Theme.LABEL, t.textDim).apply { setPadding(t.ipx(4f), t.ipx(6f), 0, 0) })

        section("Quick launch")
        list.addView(t.label(
            if (actions.quickLaunchOn()) "On: press volume up three times quickly to open Nadaka from anywhere."
            else "Press volume up three times to open Nadaka from anywhere. Turn on Nadaka quick launch in Android accessibility settings once.",
            Theme.LABEL, t.textDim).apply { setPadding(t.ipx(4f), t.ipx(6f), 0, 0) })
        if (!actions.quickLaunchOn()) action("Open accessibility settings") { actions.openAccessibility() }

        section("Alerts")
        choice("Voice language", app.nadaka.SpeechLang.entries.map { it.label }, Prefs.speechLang.ordinal,
            "Voice language for alerts") { Prefs.speechLang = app.nadaka.SpeechLang.entries[it]; actions.languageChanged() }
        toggle("Audio", Prefs.audioOn, if (Prefs.audioOn) null else "Spoken alerts are off.") { Prefs.audioOn = it }
        toggle("Haptic", Prefs.hapticOn, if (Prefs.hapticOn) null else "Vibration alerts are off.") { Prefs.hapticOn = it }
        if (!Prefs.audioOn && !Prefs.hapticOn) note("Audio and haptic are both off. Only the screen will warn you.")
        choice("Alert style", listOf("Vibration first", "Speak everything"), if (Settings.hapticsFirst) 0 else 1) { Settings.hapticsFirst = it == 0 }
        toggle("Announce buttons", Prefs.announce, null) { Prefs.announce = it }

        section("Try it")
        action("Test haptic") { actions.testHaptic() }
        action("Test audio") { actions.testAudio() }
        action("Teach me the vibrations") { actions.lesson() }
        action("How fall alerts work") { actions.say(app.nadaka.FALL_GUIDE) }

        section("Advanced")
        action("Diagnostics") { screen.diagnostics.show() }
    }

    private fun section(name: String) = list.addView(t.label(name.uppercase(), Theme.LABEL, t.accent, t.bold).apply {
        letterSpacing = 0.1f
        isAccessibilityHeading = true
        setPadding(0, t.ipx(28f), 0, t.ipx(4f))
    })

    private fun note(s: String) = list.addView(t.label(s, Theme.BODY, t.caution, t.bold).apply { setPadding(0, t.ipx(8f), 0, t.ipx(8f)) })

    /** Title + large option buttons. Side by side when they fit, stacked at large text. */
    private fun choice(name: String, options: List<String>, selected: Int, spoken: String? = null, pick: (Int) -> Unit) {
        list.addView(t.label(name, Theme.TITLE, t.text, t.bold).apply { setPadding(0, t.ipx(18f), 0, t.ipx(10f)); isAccessibilityHeading = true })
        val stacked = t.big || options.size > 2
        val row = LinearLayout(act).apply { orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        options.forEachIndexed { i, o ->
            val on = i == selected
            row.addView(option((if (on) "✓  " else "") + o, on).apply {
                contentDescription = "${spoken ?: name}: $o${if (on) ", selected" else ""}"
                setOnClickListener {
                    pick(i); changed()
                    if (Prefs.announce) actions.say("$name: $o.")
                }
            }, if (stacked) LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = t.ipx(10f) }
               else LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = t.ipx(10f) })
        }
        list.addView(row)
    }

    private fun toggle(name: String, on: Boolean, hint: String?, set: (Boolean) -> Unit) {
        list.addView(option("$name\n${if (on) "ON" else "OFF"}", on).apply {
            contentDescription = "$name ${if (on) "on" else "off"}. Double tap to turn ${if (on) "off" else "on"}."
            setOnClickListener {
                set(!on); changed()
                if (Prefs.announce) actions.say("$name ${if (!on) "on" else "off"}.")
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = t.ipx(14f) })
        hint?.let { list.addView(t.label(it, Theme.LABEL, t.textDim).apply { setPadding(t.ipx(4f), t.ipx(6f), 0, 0) }) }
    }

    private fun action(name: String, run: () -> Unit) = list.addView(option(name, false).apply {
        contentDescription = name
        setOnClickListener { run() }
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = t.ipx(12f) })

    private fun option(label: String, on: Boolean) = Button(act).apply {
        text = label
        isAllCaps = false
        stateListAnimator = null
        typeface = if (on) t.black else t.bold
        t.size(this, Theme.BODY)
        setTextColor(if (on) t.onAccent else t.text)
        background = if (on) t.box(t.accent, t.text, if (t.hc) 4f else 0f, 22f) else t.box(t.surface, t.textDim, 2f, 22f)
        minHeight = t.ipx(Theme.TOUCH); minimumHeight = t.ipx(Theme.TOUCH)
        gravity = Gravity.CENTER
        setPadding(t.ipx(16f), t.ipx(14f), t.ipx(16f), t.ipx(14f))
    }

    companion object {
        val TEXT_SIZES = listOf(0.85f, 1f, 1.3f, 1.6f)
    }
}
