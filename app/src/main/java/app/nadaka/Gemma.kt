package app.nadaka

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * Gemma 4 E2B (the multimodal model already installed on the phone by Google AI Edge Gallery),
 * run locally with LiteRT-LM on the GPU. It looks at the camera frame for "what's ahead?",
 * sign/text reading and free-form questions.
 *
 * Never used for safety: "is it safe?" is answered by SafetyGate before Gemma sees anything, and
 * every Gemma answer is scanned for movement green-lights (SafetyGate.greenLight) before it is spoken.
 * Money and medicine stay on deterministic OCR rules (Reader.kt).
 */
class Gemma(private val ctx: Context) {
    private val worker = Executors.newSingleThreadExecutor()
    private var engine: Engine? = null
    @Volatile var status = "not loaded"
        private set
    val ready get() = engine != null

    private val modelFile get() = File(ctx.getExternalFilesDir(null), Settings.gemmaModelFile)

    /** Loads in the background (a few seconds; the GPU program cache makes later launches faster). */
    fun load() = worker.execute {
        val f = modelFile
        if (!f.exists()) { status = "model not found at ${f.path}"; Log.w(TAG, "gemma: $status"); return@execute }
        try {
            status = "loading"
            val t0 = System.currentTimeMillis()
            val e = Engine(
                EngineConfig(
                    modelPath = f.path,
                    backend = Backend.GPU(),
                    visionBackend = Backend.GPU(),
                    maxNumImages = 1,
                    cacheDir = ctx.cacheDir.path,
                )
            )
            e.initialize()
            engine = e
            status = "ready on GPU"
            Log.i(TAG, "gemma: loaded ${f.name} in ${System.currentTimeMillis() - t0} ms")
        } catch (t: Throwable) {
            status = "failed: ${t.message}"
            Log.e(TAG, "gemma: load failed", t)
        }
    }

    /** One question about [image] (may be null). [onAnswer] gets null when Gemma can't answer. Runs on the Gemma thread. */
    fun ask(prompt: String, image: Bitmap?, onAnswer: (String?) -> Unit) = worker.execute {
        val e = engine ?: return@execute onAnswer(null)
        try {
            val t0 = System.currentTimeMillis()
            e.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(SYSTEM),
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.2, seed = 0),
                )
            ).use { c ->
                val parts = ArrayList<Content>()
                image?.let { parts += Content.ImageBytes(jpeg(it)) }
                parts += Content.Text(prompt)
                val reply = c.sendMessage(Contents.of(parts))
                val text = reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }.trim()
                Log.i(TAG, "gemma: ${System.currentTimeMillis() - t0} ms: $text")
                onAnswer(text.ifBlank { null })
            }
        } catch (t: Throwable) {
            Log.e(TAG, "gemma: ask failed", t)
            onAnswer(null)
        }
    }

    private fun jpeg(b: Bitmap): ByteArray {
        val s = Settings.gemmaImagePx.toFloat() / maxOf(b.width, b.height)
        val small = if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b
        return ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    companion object {
        const val SYSTEM = "You are the eyes of a blind pedestrian, speaking through their phone. " +
            "Describe only what you can see in the image. Answer in at most two short sentences, plain words, no lists. " +
            "Give directions as clock positions (12 o'clock is straight ahead) and rough distances in metres. " +
            "Never say whether it is safe to walk, move, cross or go, and never say the path or way is clear."

        fun describePrompt(facts: String) =
            "What is in front of me? Mention the most important things for walking. " +
                (if (facts.isNotBlank()) "My obstacle sensor also reports: $facts" else "")

        const val READ_PROMPT = "Read the sign or text in this image word for word, then say in one short sentence what it means. " +
            "If it is in Hindi, Telugu or another language, read it and give the English meaning. " +
            "If there is no readable text, say exactly: No text I can read."

        fun questionPrompt(q: String) = "Question from the user about what is in front of them: $q"
    }
}
