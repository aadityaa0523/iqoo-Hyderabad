package app.nadaka

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Debug
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.io.File
import kotlin.concurrent.thread

/**
 * Repeatable benchmark: each model x backend, fresh interpreter, 10 warm-up + 100 timed runs on the same
 * frame, stages timed separately. Runs with no camera, so nothing else competes for the NPU.
 * Delegation is taken from the runtime's own "Replacing X out of Y node(s)" log, read from this process.
 * Report: files/bench.md (adb pull /sdcard/Android/data/app.nadaka/files/bench.md).
 */
class BenchActivity : ComponentActivity() {
    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f; setPadding(32, 120, 32, 32); typeface = android.graphics.Typeface.MONOSPACE }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(out) })
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val runs = intent.getIntExtra("runs", 100)
        thread(name = "bench") { runAll(runs) }
    }

    private fun show(s: String) = runOnUiThread { out.append(s + "\n") }

    private fun frame(): Bitmap {
        val f = File(getExternalFilesDir(null), "bench.jpg") // a real camera frame pushed by adb, if present
        return BitmapFactory.decodeFile(f.path)?.let { Bitmap.createScaledBitmap(it, 480, 640, true) }
            ?: Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
    }

    private class Stats(v: List<Double>) {
        private val s = v.sorted()
        private fun q(p: Double) = s[((s.size - 1) * p).toInt()]
        val median = q(0.5); val p90 = q(0.9); val p95 = q(0.95); val min = s.first(); val max = s.last()
        fun row() = "%.2f | %.2f | %.2f | %.2f | %.2f".format(median, p90, p95, min, max)
    }

    /** "Replacing 317 out of 317 node(s) with delegate (TfLiteQnnDelegate)" lines this process logged since [sinceMs]. */
    private fun delegation(since: String): String {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "--pid=${android.os.Process.myPid()}", "-T", since))
        val lines = p.inputStream.bufferedReader().readLines().filter { "Replacing" in it && "node" in it }
        return lines.lastOrNull()?.substringAfter("Replacing ")?.substringBefore(" node")?.replace(" out of ", "/")
            ?.let { it + " (" + (lines.last().substringAfter("delegate (", "").substringBefore(")")) + ")" } ?: "no delegate log"
    }

    private fun now() = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())

    private fun runAll(runs: Int) {
        val img = frame()
        val rows = ArrayList<String>()
        rows += "# Nadaka inference benchmark"
        rows += "Device: ${android.os.Build.MODEL} (${android.os.Build.SOC_MODEL}), Android ${android.os.Build.VERSION.RELEASE}; " +
            "frame ${img.width}x${img.height}; $runs timed runs after 10 warm-up; ms"
        rows += ""
        rows += "| Model | Backend | Delegated ops | Init ms | Stage | Median | P90 | P95 | Min | Max |"
        rows += "|---|---|---|---|---|---|---|---|---|---|"
        show(rows.joinToString("\n"))
        val analyzer = DepthAnalyzer()
        for (model in listOf("YOLOX", "Depth")) for (b in Backend.entries) {
            val since = now()
            val memBefore = Debug.getNativeHeapAllocatedSize()
            try {
                val pre = ArrayList<Double>(); val inf = ArrayList<Double>(); val post = ArrayList<Double>(); val tot = ArrayList<Double>()
                val (init, backend) = if (model == "YOLOX") {
                    val d = Detector(this, b)
                    repeat(10) { d.detect(img, Settings.minScore) }
                    repeat(runs) { d.detect(img, Settings.minScore); pre += d.preMs; inf += d.inferMs; post += d.postMs; tot += d.preMs + d.inferMs + d.postMs }
                    d.close(); d.initMs to d.backend
                } else {
                    val d = DepthModel(this, b)
                    repeat(10) { d.run(img) }
                    repeat(runs) {
                        val g = d.run(img)
                        val a0 = System.nanoTime(); analyzer.analyze(g, 10f); val a = (System.nanoTime() - a0) / 1e6
                        pre += d.preMs; inf += d.inferMs; post += d.postMs + a; tot += d.preMs + d.inferMs + d.postMs + a
                    }
                    d.close(); d.initMs to d.backend
                }
                val deleg = if (backend == "CPU") "XNNPACK (CPU)" else delegation(since)
                val mem = (Debug.getNativeHeapAllocatedSize() - memBefore) / 1_000_000
                for ((stage, v) in listOf("pre" to pre, "inference" to inf, "post" to post, "total" to tot)) {
                    val r = "| $model | $backend | $deleg | ${"%.0f".format(init)} | $stage | ${Stats(v).row()} |"
                    rows += r; show(r)
                }
                Log.i(TAG, "bench: $model $backend done (native heap delta ${mem} MB)")
            } catch (t: Throwable) {
                val r = "| $model | $b | FAILED: ${t.javaClass.simpleName}: ${t.message?.take(80)} | | | | | | | |"
                rows += r; show(r)
            }
        }
        File(getExternalFilesDir(null), "bench.md").writeText(rows.joinToString("\n") + "\n")
        show("\nDONE: files/bench.md")
    }
}
