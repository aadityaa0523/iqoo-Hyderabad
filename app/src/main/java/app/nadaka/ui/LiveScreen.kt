package app.nadaka.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Outline
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.nadaka.HeatTier
import app.nadaka.HudState
import app.nadaka.Prefs

/** What the screen can ask the app to do. */
class Actions(
    val say: (String) -> Unit,
    val testHaptic: () -> Unit,
    val testAudio: () -> Unit,
    val lesson: () -> Unit,
    val displayChanged: () -> Unit,
    val recordToggle: () -> Unit,
    val openBenchmark: () -> Unit,
    val calibrate: () -> Unit,
    val addContact: () -> Unit,
    val removeContact: (Int) -> Unit,
    val testSms: () -> Unit,
)

/**
 * The live safety screen: header (is it on?), hero camera/depth view, one giant safety state, objects,
 * and three large controls. Real views, so TalkBack reads every part. Settings and Diagnostics are
 * full-screen layers on top. All updates arrive via [post] from the analysis thread.
 */
class LiveScreen(private val act: Activity, private val preview: View, private val actions: Actions) {
    private var t = Theme(act)
    val root = FrameLayout(act)
    private val column = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

    // Header
    private val brand = TextView(act)
    private val status = TextView(act)
    private val header = LinearLayout(act)

    // Hero
    private val hero = FrameLayout(act)
    private val heroView = HeroView(act)
    private val heroBadge = TextView(act)
    private val objects = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

    // Safety card
    private val card = LinearLayout(act)
    private val glyph = GlyphView(act)
    private val headline = TextView(act)
    private val subject = TextView(act)
    private val distance = TextView(act)
    private val direction = TextView(act)
    private val distRow = LinearLayout(act)
    private val detail = TextView(act)

    // Controls
    private val audio = Button(act)
    private val haptic = Button(act)
    private val menu = Button(act)
    private val controls = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }

    val settings = SettingsScreen(act, this, actions)
    val diagnostics = DiagnosticsScreen(act, this, actions)

    private var state = HudState()
    private var safety = safetyOf(state)
    private var lastFrameMs = SystemClock.elapsedRealtime()
    private var shownLevel: Level? = null
    private var pulse: ValueAnimator? = null

    init {
        column.addView(header, LinearLayout.LayoutParams(-1, -2))
        column.addView(hero, LinearLayout.LayoutParams(-1, 0, 1f))
        column.addView(card, LinearLayout.LayoutParams(-1, -2))
        for (b in listOf(audio, haptic, menu)) controls.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        column.addView(controls, LinearLayout.LayoutParams(-1, -2))
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        root.addView(settings.view, FrameLayout.LayoutParams(-1, -1))
        root.addView(diagnostics.view, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            column.setPadding(b.left, b.top, b.right, b.bottom)
            settings.view.setPadding(b.left, b.top, b.right, b.bottom)
            diagnostics.view.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        header.orientation = LinearLayout.VERTICAL // status gets the full width: never clipped at large text
        header.addView(brand, LinearLayout.LayoutParams(-2, -2))
        header.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * act.resources.displayMetrics.density).toInt() })
        brand.text = "NADAKA"
        brand.contentDescription = "Nadaka"

        // Hero: preview and overlay share one 3:4 frame that covers the hero (centre crop).
        hero.addView(preview, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        hero.addView(heroView, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        hero.addView(heroBadge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        hero.addView(objects, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        hero.clipToOutline = true
        hero.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, t.px(Theme.RADIUS))
        }
        hero.addOnLayoutChangeListener { _, l, tp, r, b, _, _, _, _ -> coverFrame(r - l, b - tp) }
        hero.setOnLongClickListener { actions.recordToggle(); true }

        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.isFocusable = true
        val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        card.addView(glyph)
        card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        texts.addView(headline); texts.addView(subject)
        distRow.orientation = LinearLayout.HORIZONTAL
        distRow.gravity = Gravity.BOTTOM
        distRow.addView(distance); distRow.addView(direction)
        texts.addView(distRow); texts.addView(detail)
        for (v in listOf(headline, subject, distance, direction, detail)) v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        audio.setOnClickListener { Prefs.audioOn = !Prefs.audioOn; Prefs.save(act); restyleControls(); if (Prefs.audioOn) actions.say("Audio on.") }
        haptic.setOnClickListener { Prefs.hapticOn = !Prefs.hapticOn; Prefs.save(act); restyleControls(); if (Prefs.hapticOn) actions.testHaptic() }
        menu.setOnClickListener { settings.show() }

        restyle()
        root.post { root.post(watchdog) }
    }

    /** Theme, text size or contrast changed: rebuild every style (live preview behind the settings). */
    fun restyle() {
        t = Theme(act)
        root.setBackgroundColor(t.bg)
        val g = t.ipx(Theme.GAP)
        header.setPadding(t.ipx(24f), t.ipx(12f), t.ipx(24f), t.ipx(12f))
        brand.setTextColor(t.text); brand.typeface = t.black; t.size(brand, 24f); brand.letterSpacing = 0.12f
        status.typeface = t.bold; t.size(status, Theme.LABEL); status.maxLines = 2

        (hero.layoutParams as LinearLayout.LayoutParams).setMargins(g, 0, g, 0)
        hero.setBackgroundColor(t.surface)
        heroBadge.typeface = t.bold; t.size(heroBadge, Theme.LABEL)
        heroBadge.setTextColor(t.text); heroBadge.background = t.box(t.scrim, t.text, 2f, 999f)
        heroBadge.setPadding(t.ipx(16f), t.ipx(10f), t.ipx(16f), t.ipx(10f))
        (heroBadge.layoutParams as FrameLayout.LayoutParams).setMargins(g, g, g, g)
        heroBadge.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        objects.setPadding(g, 0, g, g)

        (card.layoutParams as LinearLayout.LayoutParams).setMargins(g, g, g, 0)
        card.setPadding(t.ipx(20f), t.ipx(20f), t.ipx(24f), t.ipx(20f))
        val gs = t.ipx(76f * minOf(t.scale, 1.3f))
        glyph.layoutParams = LinearLayout.LayoutParams(gs, gs).apply { rightMargin = t.ipx(20f) }
        headline.typeface = t.black; subject.typeface = t.black
        distance.typeface = t.black; direction.typeface = t.bold; detail.typeface = t.regular
        t.fit(subject, Theme.HEADLINE)
        t.size(direction, Theme.TITLE); direction.setPadding(t.ipx(14f), 0, 0, t.ipx(6f))
        t.size(detail, Theme.BODY); detail.maxLines = 3; detail.ellipsize = TextUtils.TruncateAt.END
        detail.setPadding(0, t.ipx(6f), 0, 0)

        (controls.layoutParams as LinearLayout.LayoutParams).setMargins(g / 2, g / 2, g / 2, g / 2)
        for (b in listOf(audio, haptic, menu)) {
            b.isAllCaps = false; b.typeface = t.bold; b.stateListAnimator = null
            b.minHeight = t.ipx(Theme.TOUCH); b.minimumHeight = t.ipx(Theme.TOUCH)
            b.maxLines = 2
            b.setAutoSizeTextTypeUniformWithConfiguration(16, (Theme.BODY * t.scale * t.fontScale).toInt().coerceAtLeast(18), 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            (b.layoutParams as LinearLayout.LayoutParams).setMargins(g / 2, 0, g / 2, 0)
            b.setPadding(t.ipx(8f), t.ipx(12f), t.ipx(8f), t.ipx(12f))
        }
        restyleControls()
        heroView.restyle()
        shownLevel = null
        render()
        settings.restyle(); diagnostics.restyle()
    }

    fun restyleControls() {
        fun toggle(b: Button, name: String, on: Boolean, spokenName: String) {
            b.text = "$name\n${if (on) "ON" else "OFF"}"
            b.setTextColor(if (on) t.onAccent else t.text)
            b.background = if (on) t.box(t.accent, t.text, if (t.hc) 3f else 0f, 22f) else t.box(t.bg, t.text, 3f, 22f, dashed = true)
            b.contentDescription = "$spokenName ${if (on) "enabled" else "off"}. Double tap to turn ${if (on) "off" else "on"}."
        }
        toggle(audio, "AUDIO", Prefs.audioOn, "Audio alerts")
        toggle(haptic, "HAPTIC", Prefs.hapticOn, "Vibration alerts")
        menu.text = "MENU\n☰"
        menu.setTextColor(t.text)
        menu.background = t.box(t.surface2, t.text, 3f, 22f)
        menu.contentDescription = "Settings and accessibility"
    }

    fun post(s: HudState) = root.post { state = s; lastFrameMs = SystemClock.elapsedRealtime(); render() }

    /** Camera permission denied or the camera failed to open. */
    fun cameraError(why: String) = root.post { state = state.copy(cameraError = why, loading = false); render() }
    fun analysisError(why: String) = root.post { state = state.copy(error = why, loading = false); render() }

    /** Frames stopped arriving (camera lost) while the screen is up. */
    private val watchdog = object : Runnable {
        override fun run() {
            val stale = SystemClock.elapsedRealtime() - lastFrameMs > 4000
            if (stale && !state.loading && state.cameraError == null) { state = state.copy(cameraError = "The camera stopped."); render() }
            root.postDelayed(this, 1000)
        }
    }

    /** After a pause, give the camera a moment before calling it lost. */
    fun resumed() { lastFrameMs = SystemClock.elapsedRealtime(); if (state.cameraError == "The camera stopped.") state = state.copy(cameraError = null) }

    private fun coverFrame(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val unit = maxOf(w / 3f, h / 4f)
        val fw = (3 * unit).toInt(); val fh = (4 * unit).toInt()
        for (v in listOf(preview, heroView)) {
            val lp = v.layoutParams as FrameLayout.LayoutParams
            if (lp.width != fw || lp.height != fh) { lp.width = fw; lp.height = fh; v.post { v.layoutParams = lp } }
        }
    }

    private fun cycleHero() {
        Prefs.heroMode = HeroMode.entries[(Prefs.heroMode.ordinal + 1) % HeroMode.entries.size]
        Prefs.save(act)
        actions.say("View: ${Prefs.heroMode.label}.")
        render()
    }

    private fun setIfChanged(tv: TextView, s: CharSequence?) {
        val v = s ?: ""
        if (tv.text.toString() != v.toString()) tv.text = v
        tv.visibility = if (v.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun render() {
        val s = state
        val sf = safetyOf(s)
        safety = sf
        heroView.show(s, sf)

        // Header: is the system active? (filled vs hollow dot, and words)
        val heat = when (s.heat) { HeatTier.NOMINAL -> ""; HeatTier.WARM -> " · WARM"; else -> " · PHONE HOT" }
        setIfChanged(status, "${if (sf.statusOn) "●" else "○"} ${sf.status}$heat${if (s.rec.isNotEmpty()) " · REC" else ""}")
        status.setTextColor(if (sf.statusOn) t.accent else t.caution)
        status.contentDescription = "${sf.status.lowercase()}${if (heat.isNotEmpty()) ", phone ${s.heat.name.lowercase()}" else ""}"

        // Hero badge + description
        setIfChanged(heroBadge, null) // one view only now
        val edge = s.drop?.candidate != null && s.drop.state.let { it == app.nadaka.drop.DropState.POSSIBLE_DROP || it == app.nadaka.drop.DropState.CONFIRMED_DROP }
        hero.contentDescription = (if (edge) "Depth map showing a detected floor edge." else "Live ${Prefs.heroMode.label.lowercase()} view.") +
            ""

        renderObjects(s, sf)
        renderCard(sf)
        diagnostics.update(s)
    }

    private fun renderObjects(s: HudState, sf: Safety) {
        val list = if (sf.level == Level.DANGER || sf.level == Level.ERROR || sf.level == Level.INFO) emptyList()
        else relevant(s.tracks).take(if (Prefs.simplified) 1 else 2)
        while (objects.childCount > list.size) objects.removeViewAt(objects.childCount - 1)
        while (objects.childCount < list.size) objects.addView(objectRow())
        list.forEachIndexed { i, tr ->
            val row = objects.getChildAt(i) as LinearLayout
            val dist = if (tr.metres.isNaN()) "" else metresShort(tr.metres)
            val dir = directionOf(tr.bearing)
            setIfChanged(row.getChildAt(0) as TextView, dist)
            setIfChanged(row.getChildAt(1) as TextView, tr.label.uppercase() + if (tr.approaching) " · COMING" else "")
            setIfChanged(row.getChildAt(2) as TextView, dir)
            row.contentDescription = "${tr.label}${if (tr.approaching) ", coming toward you" else ""}${if (dist.isEmpty()) "" else ", ${metresWords(tr.metres)}"}, ${dir.lowercase()}"
        }
    }

    private fun objectRow() = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = t.box(t.scrim, t.text, 2f, 22f)
        setPadding(t.ipx(18f), t.ipx(10f), t.ipx(18f), t.ipx(10f))
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = t.ipx(10f) }
        val d = t.label("", Theme.TITLE, t.text, t.black).apply { minWidth = t.ipx(96f * t.scale) }
        val n = t.label("", Theme.BODY, t.text, t.bold).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val dir = t.label("", Theme.LABEL, t.textDim, t.bold)
        addView(d); addView(n, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = t.ipx(12f) }); addView(dir)
        for (v in listOf(d, n, dir)) v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun renderCard(sf: Safety) {
        val danger = sf.level == Level.DANGER
        val fg = when (sf.level) { Level.DANGER -> t.onDanger; else -> t.text }
        setIfChanged(headline, sf.headline)
        setIfChanged(subject, sf.subject)
        setIfChanged(distance, if (sf.distanceM.isNaN()) null else metresShort(sf.distanceM))
        setIfChanged(direction, if (sf.distanceM.isNaN()) null else sf.direction)
        distRow.visibility = if (sf.distanceM.isNaN()) View.GONE else View.VISIBLE
        setIfChanged(detail, sf.detail)
        card.contentDescription = sf.spoken
        // Without app audio, let TalkBack announce hazards itself.
        card.accessibilityLiveRegion = if (!Prefs.audioOn && (danger || sf.level == Level.CAUTION)) View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
            else View.ACCESSIBILITY_LIVE_REGION_NONE

        if (shownLevel == sf.level && glyph.glyph == sf.glyph) return
        shownLevel = sf.level
        for (v in listOf(headline, subject, distance, direction, detail)) v.setTextColor(fg)
        t.fit(headline, if (danger) Theme.DISPLAY else Theme.HEADLINE)
        t.fit(distance, Theme.DISTANCE)
        if (!danger) detail.setTextColor(t.textDim)
        card.background = when (sf.level) {
            Level.DANGER -> t.box(t.danger, t.dangerLine, 6f)                    // solid fill + heavy frame
            Level.CAUTION -> t.box(t.surface, t.caution, 5f, dashed = true)      // dashed heavy frame
            Level.ERROR -> t.box(t.surface, t.caution, 4f)
            else -> t.box(t.surface, t.textDim, if (t.hc) 3f else 1.5f)          // quiet
        }
        glyph.glyph = sf.glyph
        glyph.color = when (sf.level) { Level.DANGER -> t.onDanger; Level.CAUTION, Level.ERROR -> t.caution; Level.CALM -> t.accent; Level.INFO -> t.textDim }
        glyph.ink = t.danger
        glyph.filled = false
        animate(sf.level)
    }

    /** Calm breathing, slow caution pulse, firmer danger pulse. Scale only: no flashing. */
    private fun animate(level: Level) {
        pulse?.cancel(); glyph.scaleX = 1f; glyph.scaleY = 1f
        if (Prefs.reducedMotion) return
        val (amp, ms) = when (level) {
            Level.CALM -> 0.05f to 3000L
            Level.CAUTION -> 0.10f to 1400L
            Level.DANGER -> 0.14f to 900L
            Level.INFO -> if (safety.glyph == Glyph.WAIT) 0.08f to 1200L else return
            Level.ERROR -> return
        }
        pulse = ValueAnimator.ofFloat(1f, 1f + amp).apply {
            duration = ms / 2; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { val v = it.animatedValue as Float; glyph.scaleX = v; glyph.scaleY = v }
            start()
        }
    }

    val latest get() = state
    val settingsOpen get() = settings.open || diagnostics.open

    /** Back: close the top layer. Returns false when nothing was open. */
    fun back(): Boolean = when {
        diagnostics.open -> { diagnostics.hide(); true }
        settings.open -> { settings.hide(); true }
        else -> false
    }
}
