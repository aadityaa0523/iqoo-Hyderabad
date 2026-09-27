package app.nadaka

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** A local vision-language model the voice assistant can ask about the camera image. */
interface Vlm {
    val ready: Boolean
    val status: String
    fun load()
    fun ask(prompt: String, image: Bitmap?, onAnswer: (String?) -> Unit)
}

/**
 * Qwen3-VL-2B-Instruct (GGUF, Q4_K_M + Q8_0 vision projector) served by the official llama.cpp Android
 * build (ggml-org release, Snapdragon variant: Adreno OpenCL + Hexagon). The server binary ships inside the
 * APK as a native library and listens on 127.0.0.1 only: nothing leaves the phone.
 *
 * Same safety rules as Gemma: never used for "is it safe?", answers are scanned for green-lights.
 */
class Qwen(private val ctx: Context) : Vlm {
    private val worker = Executors.newSingleThreadExecutor()
    private var process: Process? = null
    @Volatile override var status = "not loaded"
        private set
    @Volatile override var ready = false
        private set
    @Volatile private var stopping = false
    private var restarts = 0 // consecutive; reset once the server has run for a while
    private var watchdog: Thread? = null

    private val dir get() = File(ctx.getExternalFilesDir(null), "qwen")
    private val model get() = File(dir, Settings.qwenModelFile)
    private val mmproj get() = File(dir, Settings.qwenMmprojFile)
    val installed get() = model.exists() && mmproj.exists()

    override fun load() = worker.execute {
        if (!installed) { status = "model not found in ${dir.path}"; Log.w(TAG, "qwen: $status"); return@execute }
        try {
            status = "starting"
            val lib = ctx.applicationInfo.nativeLibraryDir
            val t0 = System.currentTimeMillis()
            val cmd = listOf(
                "$lib/libllama_server.so",
                "-m", model.path, "--mmproj", mmproj.path,
                "--host", "127.0.0.1", "--port", Settings.qwenPort.toString(),
                "-c", Settings.qwenContext.toString(), "-np", "1", "-ngl", "99", "--device", Settings.qwenDevice,
                "--image-max-tokens", Settings.qwenImageTokens.toString(), "-t", "6", "--no-webui",
            )
            process = ProcessBuilder(cmd).redirectErrorStream(true).apply {
                environment()["LD_LIBRARY_PATH"] = "$lib:/vendor/lib64:/system/lib64" // OpenCL + FastRPC (Hexagon) are vendor libraries
                environment()["ADSP_LIBRARY_PATH"] = lib // Hexagon skel libraries live next to the others
            }.start()
            Thread { // keep the pipe drained; log only the lines worth reading
                process?.inputStream?.bufferedReader()?.forEachLine { if (!ready || "error" in it) Log.i(TAG, "qwen-srv: $it") } // all of startup, then errors only
            }.start()
            // Wait for the model to load (health turns 200 when ready).
            while (System.currentTimeMillis() - t0 < 60_000) {
                if (process?.isAlive != true) { status = "server exited"; Log.e(TAG, "qwen: server exited"); restartLater(); return@execute }
                if (runCatching { get("/health") == 200 }.getOrDefault(false)) {
                    ready = true
                    status = "ready on ${Settings.qwenDevice}"
                    Log.i(TAG, "qwen: ready in ${System.currentTimeMillis() - t0} ms on ${Settings.qwenDevice}")
                    startWatchdog()
                    return@execute
                }
                Thread.sleep(300)
            }
            status = "load timed out"
        } catch (t: Throwable) {
            status = "failed: ${t.message}"
            Log.e(TAG, "qwen: load failed", t)
        }
    }

    fun stop() { stopping = true; ready = false; watchdog?.interrupt(); process?.destroy(); process = null }

    /** The server can die (low memory kill, NPU reset). Check every 5 s; restart it, at most 3 times in a row. */
    private fun startWatchdog() {
        if (watchdog?.isAlive == true) return
        watchdog = Thread {
            var healthySinceMs = System.currentTimeMillis()
            try {
                while (!stopping) {
                    Thread.sleep(5000)
                    if (process?.isAlive == true) {
                        if (System.currentTimeMillis() - healthySinceMs > 60_000) restarts = 0
                        continue
                    }
                    if (stopping) break
                    ready = false
                    Log.w(TAG, "qwen: server died, restarting")
                    restartLater()
                    return@Thread // load() starts a new watchdog once ready
                }
            } catch (_: InterruptedException) { }
        }.apply { isDaemon = true; start() }
    }

    private fun restartLater() {
        if (stopping) return
        if (restarts >= 3) { status = "stopped after 3 crashes"; Log.e(TAG, "qwen: $status"); return }
        restarts++
        status = "restarting ($restarts)"
        Thread { Thread.sleep(2000L * restarts); if (!stopping) load() }.apply { isDaemon = true; start() }
    }

    override fun ask(prompt: String, image: Bitmap?, onAnswer: (String?) -> Unit) = worker.execute {
        if (!ready) return@execute onAnswer(null)
        try {
            val t0 = System.currentTimeMillis()
            val user = JSONArray()
            image?.let { user.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + jpeg64(it)))) }
            user.put(JSONObject().put("type", "text").put("text", prompt))
            val body = JSONObject()
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", (SYSTEM + " " + Prefs.speechLang.qwen).trim()))
                    .put(JSONObject().put("role", "user").put("content", user)))
                // Qwen3-VL recommended sampling for instruct models.
                .put("temperature", 0.7).put("top_p", 0.8).put("top_k", 20)
                .put("max_tokens", Settings.qwenMaxTokens)
            val reply = post("/v1/chat/completions", body.toString())
            val full = JSONObject(reply).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").trim()
            // Spoken to someone walking: at most two sentences, whatever the model does.
            val text = Regex("[^.!?]+[.!?]").findAll(full).take(2).joinToString(" ") { it.value.trim() }.ifBlank { full }
            Log.i(TAG, "qwen: ${System.currentTimeMillis() - t0} ms: $text")
            onAnswer(text.ifBlank { null })
        } catch (t: Throwable) {
            Log.e(TAG, "qwen: ask failed", t)
            onAnswer(null)
        }
    }

    companion object {
        const val SYSTEM = "You are the eyes of a blind person, looking through their phone camera. " +
            "First name the one main object in the centre of the photo, the thing they are pointing at or holding. " +
            "Then add at most one short detail that helps them: what it says, or one nearby obstacle. " +
            "One or two sentences, under 20 words. Direct, no greeting, no preamble, no description of the whole room. " +
            "Prices and money are in Indian rupees. " +
            "Speak to them as you. Use left, ahead or right. Never guess distances or numbers you cannot read. " +
            "Never say it is safe to walk, move, cross or go, and never say the path is clear. " +
            "Example of the style (not of the content): A glass door ahead, handle on your right."
    }

    private fun jpeg64(b: Bitmap): String {
        val s = Settings.gemmaImagePx.toFloat() / maxOf(b.width, b.height)
        val small = if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b
        val bytes = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun url(path: String) = URL("http://127.0.0.1:${Settings.qwenPort}$path")

    private fun get(path: String): Int = (url(path).openConnection() as HttpURLConnection).run {
        connectTimeout = 500; readTimeout = 1000
        try { responseCode } finally { disconnect() }
    }

    private fun post(path: String, json: String): String = (url(path).openConnection() as HttpURLConnection).run {
        requestMethod = "POST"; doOutput = true
        connectTimeout = 2000; readTimeout = 60_000
        setRequestProperty("Content-Type", "application/json")
        outputStream.use { it.write(json.toByteArray()) }
        try { inputStream.bufferedReader().readText() } finally { disconnect() }
    }
}
