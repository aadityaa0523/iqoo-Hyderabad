package app.nadaka

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * A bigger vision model in the cloud through OpenRouter (OpenAI-compatible chat API), for questions only.
 * Free models are often busy (HTTP 429), so several are asked in order in one request (OpenRouter "models"
 * fallback); anything failing or slow returns null and the phone answers instead. Same prompt and green-light
 * filter as the on-device model. The key comes from BuildConfig (local.properties), never from the source.
 */
class CloudVlm : Vlm {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile override var status = if (BuildConfig.OPENROUTER_KEY.isBlank()) "no key in local.properties" else "ready"
        private set
    /** The model OpenRouter actually used for the last answer. */
    @Volatile var servedBy = ""
        private set
    override val ready get() = BuildConfig.OPENROUTER_KEY.isNotBlank()
    override fun load() = Unit

    override fun ask(prompt: String, image: Bitmap?, onAnswer: (String?) -> Unit) = worker.execute {
        if (!ready) return@execute onAnswer(null)
        try {
            val user = JSONArray()
            image?.let { user.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + jpeg64(it)))) }
            user.put(JSONObject().put("type", "text").put("text", prompt))
            val body = JSONObject()
                .put("model", Settings.cloudModels.first())
                .put("models", JSONArray(Settings.cloudModels)) // tried in order when one is busy
                .put("max_tokens", 200)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", (Qwen.SYSTEM + " " + Prefs.speechLang.qwen).trim()))
                    .put(JSONObject().put("role", "user").put("content", user)))
            val c = (URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 3000; readTimeout = Settings.cloudTimeoutMs.toInt()
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${BuildConfig.OPENROUTER_KEY}")
                setRequestProperty("X-Title", "Nadaka")
            }
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code != 200) { status = "HTTP $code" + if (code == 429) " (free models busy)" else ""; c.disconnect(); return@execute onAnswer(null) }
            val j = JSONObject(c.inputStream.bufferedReader().readText()); c.disconnect()
            servedBy = j.optString("model")
            val text = j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").trim()
            // Spoken to someone walking: at most two sentences.
            val short = Regex("[^.!?]+[.!?]").findAll(text).take(2).joinToString(" ") { it.value.trim() }.ifBlank { text }
            status = "ok"
            onAnswer(short.ifBlank { null })
        } catch (t: Throwable) {
            status = if (t is java.net.SocketTimeoutException) "too slow" else "error: ${t.javaClass.simpleName}"
            Log.w(TAG, "cloud: $status")
            onAnswer(null)
        }
    }

    private fun jpeg64(b: Bitmap): String {
        val s = Settings.gemmaImagePx.toFloat() / maxOf(b.width, b.height)
        val small = if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b
        return Base64.encodeToString(ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray(), Base64.NO_WRAP)
    }
}

/** Which engine answered the last question, and why (shown in Diagnostics). */
data class Route(val engine: String, val reason: String, val ms: Long = 0, val atMs: Long = 0)

/** Diagnostics snapshot: the last answer, where the next one would go and why, and the network. */
data class AiStatus(val last: Route, val cloudNow: Boolean, val why: String, val network: String)

/**
 * Automatic cloud / on-device switching for questions (describe, read, find, bus and lift fallbacks).
 * Online + key + Auto mode -> cloud (keeps the phone's NPU and battery cool). Offline, no key, on-device mode,
 * or the cloud failing -> Qwen on the Hexagon NPU. A failed cloud request is answered on the phone at once;
 * two failures in a row -> the cloud is skipped for a minute. Safety alerts never go through here.
 */
class HybridVlm(val local: Vlm, private val cloud: CloudVlm, private val ctx: Context, private val heat: () -> HeatTier) : Vlm {
    @Volatile var route = Route("—", "No question asked yet")
        private set
    @Volatile private var failures = 0
    @Volatile private var skipCloudUntilMs = 0L

    override val ready get() = local.ready || cloudChoice().first
    override val status get() = "${local.status}; cloud ${cloud.status}"
    override fun load() = local.load()

    /** Network in words, for Diagnostics: "Wi-Fi", "Mobile data", "Offline". */
    fun network(): String {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Offline"
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return "No internet"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            else -> "Online"
        }
    }

    /** (use cloud?, why). */
    fun cloudChoice(): Pair<Boolean, String> {
        val net = network()
        return when {
            Prefs.aiMode == AiMode.ON_DEVICE -> false to "On-device is chosen in Settings"
            !cloud.ready -> false to "No cloud key in this build"
            net == "Offline" || net == "No internet" -> false to "$net: using the phone's NPU"
            SystemClock.elapsedRealtime() < skipCloudUntilMs -> false to "Cloud failed twice, trying the phone for a minute"
            else -> true to "$net is available: cloud keeps the phone cool" + if (heat() > HeatTier.NOMINAL) " (phone is ${heat().name.lowercase()})" else ""
        }
    }

    fun snapshot(): AiStatus = cloudChoice().let { (c, why) -> AiStatus(route, c, why, network()) }

    override fun ask(prompt: String, image: Bitmap?, onAnswer: (String?) -> Unit) {
        val (useCloud, why) = cloudChoice()
        val t0 = SystemClock.elapsedRealtime()
        if (!useCloud) { onDevice(prompt, image, why, t0, onAnswer); return }
        cloud.ask(prompt, image) { a ->
            val ms = SystemClock.elapsedRealtime() - t0
            if (a != null) {
                failures = 0
                route = Route("Cloud · ${cloud.servedBy.ifBlank { "OpenRouter" }}", why, ms, SystemClock.elapsedRealtime())
                onAnswer(a); return@ask
            }
            failures++
            if (failures >= 2) { skipCloudUntilMs = SystemClock.elapsedRealtime() + Settings.cloudBackoffMs; failures = 0 }
            onDevice(prompt, image, "Cloud ${cloud.status}: answered on the phone instead", t0, onAnswer)
        }
    }

    private fun onDevice(prompt: String, image: Bitmap?, why: String, t0: Long, onAnswer: (String?) -> Unit) {
        if (!local.ready) { route = Route("None", "$why, but the on-device model isn't ready", 0, SystemClock.elapsedRealtime()); onAnswer(null); return }
        local.ask(prompt, image) { a ->
            route = Route(if (local is Qwen) "On-device · Qwen3-VL on the Hexagon NPU" else "On-device · Gemma", why,
                SystemClock.elapsedRealtime() - t0, SystemClock.elapsedRealtime())
            onAnswer(a)
        }
    }
}

/** Where questions are answered. */
enum class AiMode(val label: String) { ON_DEVICE("On-device"), AUTO("Cloud when online") }
