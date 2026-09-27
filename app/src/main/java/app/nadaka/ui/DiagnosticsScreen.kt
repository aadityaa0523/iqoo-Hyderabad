package app.nadaka.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.nadaka.HudState

/**
 * Engineering view for demos, debugging and judges: backends, latency, NPU delegation (read from the
 * runtime's own log, never assumed), sensors and the drop-off evidence. Kept off the main screen.
 */
class DiagnosticsScreen(private val act: Activity, private val screen: LiveScreen, private val actions: Actions) {
    private var t = Theme(act)
    private val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    val view = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; isClickable = true }
    private val top = LinearLayout(act)
    private val title = TextView(act)
    private val close = Button(act)
    private val values = HashMap<String, TextView>()
    private var depthView: HeroView? = null
    @Volatile private var delegation: List<String> = emptyList() // "317/317 (TfLiteQnnDelegate)" in model-open order
    val open get() = view.visibility == View.VISIBLE

    init {
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(close)
        view.addView(top, LinearLayout.LayoutParams(-1, -2))
        view.addView(ScrollView(act).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        close.setOnClickListener { hide() }
        title.isAccessibilityHeading = true
    }

    fun show() {
        build()
        view.visibility = View.VISIBLE
        update(screen.latest)
        Thread { delegation = readDelegation(); view.post { update(screen.latest) } }.start()
    }

    fun hide() { view.visibility = View.GONE }
    fun restyle() { if (open) { build(); update(screen.latest) } }

    private fun build() {
        t = Theme(act)
        view.setBackgroundColor(t.bg)
        top.setPadding(t.ipx(24f), t.ipx(16f), t.ipx(16f), t.ipx(12f))
        title.text = "Diagnostics"; title.setTextColor(t.text); title.typeface = t.black; t.size(title, 34f)
        close.text = "CLOSE"; close.isAllCaps = false; close.typeface = t.bold; t.size(close, Theme.BODY); close.stateListAnimator = null
        close.setTextColor(t.onAccent); close.background = t.box(t.accent, t.text, if (t.hc) 3f else 0f, 22f)
        close.minHeight = t.ipx(Theme.TOUCH * 0.8f); close.setPadding(t.ipx(28f), 0, t.ipx(28f), 0)
        close.contentDescription = "Close diagnostics"
        list.removeAllViews(); values.clear()
        list.setPadding(t.ipx(20f), 0, t.ipx(20f), t.ipx(40f))

        // Live depth map from Depth Anything V2 on the NPU, with what the app reads from it drawn on top.
        section("Live depth  ·  near = bright")
        depthView = HeroView(act).apply {
            forceDepth = true
            contentDescription = "Live depth map from the depth model. Near things are bright, far things are dark."
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val want = v.width * 4 / 3 // the camera frame is 3:4
                if (want > 0 && v.height != want) v.post { v.layoutParams = v.layoutParams.apply { height = want } }
            }
        }
        list.addView(depthView, LinearLayout.LayoutParams(-1, t.ipx(400f)).apply { topMargin = t.ipx(8f) })

        section("AI engine · questions"); row("Answering now"); row("Why"); row("Last answer"); row("Network"); row("Always on the phone")
        section("YOLOX · objects"); row("Backend"); row("Latency"); row("Delegation"); row("Model")
        section("Depth Anything V2"); row("Depth backend"); row("Precision"); row("Depth latency"); row("Depth delegation")
        section("Pipeline"); row("Frame rate"); row("Lens"); row("Activity"); row("Thermal")
        section("Sensors"); row("Camera"); row("Barometer"); row("Motion sensor")
        section("Drop-off evidence")
        for (k in listOf("State", "Edge", "Depth", "Ground plane", "Object suppression", "Evidence", "History", "Possible frames",
            "Strong frames", "Recovery frames", "Path check", "Drop timing")) row(k)
        section("Voice"); row("Last spoken")
        list.addView(Button(act).apply {
            text = "Run NPU benchmark"; isAllCaps = false; typeface = t.bold; t.size(this, Theme.BODY); stateListAnimator = null
            setTextColor(t.text); background = t.box(t.surface2, t.text, 3f, 22f); minHeight = t.ipx(Theme.TOUCH)
            contentDescription = "Run NPU benchmark. Opens a separate benchmark screen."
            setOnClickListener { actions.openBenchmark() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = t.ipx(28f) })
    }

    private fun section(name: String) = list.addView(t.label(name.uppercase(), Theme.LABEL, t.accent, t.bold).apply {
        letterSpacing = 0.1f; isAccessibilityHeading = true; setPadding(0, t.ipx(28f), 0, t.ipx(6f))
    })

    private fun row(key: String) {
        val box = LinearLayout(act).apply {
            orientation = if (t.big) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setPadding(t.ipx(18f), t.ipx(14f), t.ipx(18f), t.ipx(14f))
            background = t.box(t.surface, t.textDim, if (t.hc) 2f else 0f, 18f)
            isFocusable = true
        }
        val k = t.label(key, Theme.LABEL, t.textDim)
        val v = t.label("—", Theme.BODY, t.text, t.bold).apply { gravity = if (t.big) Gravity.START else Gravity.END }
        box.addView(k, if (t.big) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(v, LinearLayout.LayoutParams(if (t.big) -1 else 0, -2, if (t.big) 0f else 1.4f))
        list.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = t.ipx(8f) })
        values[key] = v
    }

    private fun set(key: String, v: String) {
        val tv = values[key] ?: return
        if (tv.text.toString() != v) tv.text = v
        (tv.parent as View).contentDescription = "$key: $v"
    }

    private fun backend(b: String) = when (b) { "" -> "Not started"; "HTP" -> "HTP · Qualcomm QNN (NPU)"; else -> b }

    fun update(s: HudState) {
        if (!open) return
        depthView?.show(s, safetyOf(s))
        actions.route()?.let { a ->
            set("Answering now", if (a.cloudNow) "Cloud · OpenRouter (${app.nadaka.Settings.cloudModels.first().substringBefore(':')})" else "On-device · NPU")
            set("Why", a.why)
            set("Last answer", if (a.last.atMs == 0L) "—" else "${a.last.engine} · ${a.last.ms} ms · ${a.last.reason}")
            set("Network", a.network)
        }
        set("Always on the phone", "Object detection, depth, drop-offs, stairs, falls: real-time safety never waits for a network")
        set("Backend", backend(s.backend))
        set("Latency", if (s.backend.isEmpty()) "—" else "${s.detMs} ms")
        set("Delegation", delegation.getOrNull(0) ?: "Not in log")
        set("Model", "YOLOX · INT8 w8a8")
        set("Depth backend", backend(s.depthBackend))
        set("Precision", when (s.depthBackend) { "HTP", "GPU" -> "FP16"; "" -> "—"; else -> "FP32" })
        set("Depth latency", if (s.depthBackend.isEmpty()) "—" else "${s.depthMs} ms")
        set("Depth delegation", delegation.getOrNull(1) ?: "Not in log")
        set("Frame rate", "${s.fps} fps")
        set("Lens", if (s.lens < 1f) "0.6× ultra-wide" else "1× main")
        set("Activity", s.mode.lowercase().replaceFirstChar { it.uppercase() })
        set("Thermal", s.heat.name.lowercase().replaceFirstChar { it.uppercase() })
        set("Camera", s.cameraError ?: if (s.health.message.isEmpty()) "Healthy" else s.health.name.lowercase().replaceFirstChar { it.uppercase() })
        set("Barometer", if (s.baroHPa.isNaN()) "Not on this phone" else "%.2f hPa".format(s.baroHPa))
        set("Motion sensor", s.sensorError ?: "Healthy")
        val d = s.drop
        if (d == null) { set("State", "Waiting for frames"); return }
        val e = d.evidence
        set("State", d.state.name.replace('_', ' '))
        set("Edge", "%.2f".format(e.edgeScore))
        set("Depth", "${e.depthVerdict}  ·  %.2f".format(e.depthConfidence))
        set("Ground plane", "%.2f${if (e.groundPlaneBreak) "  ·  break" else ""}".format(e.groundPlaneScore))
        set("Object suppression", "%.2f".format(e.objectSuppressionScore))
        set("Evidence", "${e.evidenceClass}  ·  %.2f".format(e.confidence))
        set("History", d.history.joinToString(" ") { it.name.take(1) }.ifEmpty { "—" })
        set("Possible frames", "${d.possibleCount} / 3")
        set("Strong frames", "${d.strongCount} / 5")
        set("Recovery frames", "${d.recoveryCount} / 8")
        set("Path check", d.pathReason.ifEmpty { "Traversable" })
        set("Drop timing", "%.1f ms".format(d.timingsMs[4]))
        set("Last spoken", s.said.ifEmpty { "—" })
    }

    /** "Replacing 317 out of 317 node(s) with delegate (TfLiteQnnDelegate)", as logged by this process. */
    private fun readDelegation(): List<String> = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "--pid=${android.os.Process.myPid()}"))
        p.inputStream.bufferedReader().readLines().filter { "Replacing" in it && "node" in it }.map {
            it.substringAfter("Replacing ").substringBefore(" node").replace(" out of ", " / ") +
                "  ·  " + it.substringAfter("delegate (", "").substringBefore(")")
        }
    }.getOrDefault(emptyList())
}
