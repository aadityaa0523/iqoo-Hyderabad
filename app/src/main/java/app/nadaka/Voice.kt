package app.nadaka

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * "Is it safe to cross?" is the most dangerous question a blind user can ask a gadget.
 * Nadaka never answers it with yes: the question is caught here and answered from a fixed
 * template filled with the real sensor state, handing the decision back to the cane.
 * No language model sits anywhere in the answer path, so nothing can be talked into "go ahead".
 */
object SafetyGate {
    // Broad on purpose: catching a harmless question only costs a cautious answer (fail-safe).
    private val patterns = listOf(
        // English
        Regex("""\b(safe|clear|okay|ok|alright|fine)\b.*\b(cross|walk|go|move|step|proceed|continue)"""),
        Regex("""\b(can|should|may|could|shall|do)\s+i\s+(cross|walk|go|move|step|proceed|continue|keep)"""),
        Regex("""\b(anything|something|obstacle|hazard|danger|car|vehicle|bike|traffic)\b.*\b(ahead|front|way|path|coming|road)"""),
        Regex("""\b(is it safe|path clear|road clear|way clear|all clear|is it clear)"""),
        Regex("""\bsafe\b"""),
        // Hindi (Latin script)
        Regex("""\b(chal|jaa|ja|paar|cross)\w*\s*(sakta|sakti|sakte|sakoon|sakun)"""),
        Regex("""\b(safe|surakshit|theek)\s*(hai|he)\b"""),
        Regex("""\b(raasta|rasta|sadak)\b.*\b(saaf|khali|clear)"""),
        Regex("""\baage\b.*\b(kuch|koi)"""),
    )
    // Devanagari and Telugu. ponytail: Telugu forms to be checked with a native speaker.
    private val words = listOf(
        "सुरक्षित", "चल सकत", "जा सकत", "पार कर", "रास्ता साफ", "आगे कुछ", "आगे कोई",
        "సురక్షిత", "దాట", "వెళ్ళవచ్చా", "వెళ్ళగలనా", "నడవవచ్చా", "ముందు ఏమైనా",
    )

    fun isSafetyQuestion(text: String): Boolean {
        val t = " ${text.lowercase().trim()} "
        return patterns.any { it.containsMatchIn(t) } || words.any { it in t }
    }
}

/** Worst hazard state over the last [Settings.hazardMemoryMs], so a drop-off that flickered while asking is still reported. */
class HazardMemory {
    private val seen = HashMap<String, Long>() // description -> last time
    private var nowMs = 0L

    @Synchronized fun record(now: Long, hz: Hazards, tracks: List<Track>, health: Health) {
        nowMs = now
        hz.dropAtM?.let { seen["a drop-off ahead, ${metres(it)}"] = now }
        hz.overheadAtM?.let { seen["something at head height, ${metres(it)}"] = now }
        tracks.filter { it.approaching }.forEach { seen["${it.label} approaching, ${clock(it.bearing)}"] = now }
        tracks.filter { !it.metres.isNaN() && it.metres < Settings.closeM }.forEach { seen["${it.label} close, ${metres(it.metres)}"] = now }
        hz.floorObstacleAtM?.takeIf { it < Settings.closeM }?.let { seen["something blocking the path, ${metres(it)}"] = now }
        if (health != Health.OK) seen["the camera can't see clearly"] = now
    }

    /** Most severe first. */
    @Synchronized fun recent(): List<String> {
        seen.entries.removeAll { nowMs - it.value > Settings.hazardMemoryMs }
        val order = listOf("the camera", "a drop-off", "something at head", "something blocking")
        return seen.keys.sortedBy { k -> order.indexOfFirst { k.startsWith(it) }.let { if (it < 0) order.size else it } }
    }
}

object Answers {
    /** Never a yes. States what the sensors see and returns the decision to the person. */
    fun safety(recent: List<String>): String {
        val state = if (recent.isEmpty()) "I don't detect anything ahead, but I can miss things" else "I sense ${recent.take(3).joinToString("; ")}"
        return "I can't judge whether moving is safe. $state. Check with your cane, listen for traffic, and use your own judgement."
    }

    /** "What's ahead?" Up to three nearest things, nearest first. */
    fun describe(tracks: List<Track>, hz: Hazards): String {
        val parts = ArrayList<String>()
        hz.dropAtM?.let { parts += "drop-off, ${metres(it)}" }
        hz.overheadAtM?.let { parts += "head height obstacle, ${metres(it)}" }
        tracks.filter { it.hits >= Settings.minHits }.sortedBy { if (it.metres.isNaN()) 99f else it.metres }.take(3).forEach {
            parts += listOf(it.label + if (it.approaching) " approaching" else "", metres(it.metres), clock(it.bearing)).filter { p -> p.isNotEmpty() }.joinToString(", ")
        }
        return if (parts.isEmpty()) "I don't see anything nearby." else parts.joinToString(". ") + "."
    }
}

enum class Ask { SAFETY, DESCRIBE, READ, CHATTY, QUIET, SPEECH, HAPTIC, LEARN, HELP }

/** Deterministic intent grammar. Safety is checked FIRST and wins over everything. */
fun intentOf(text: String): Ask {
    if (SafetyGate.isSafetyQuestion(text)) return Ask.SAFETY
    val t = text.lowercase()
    return when {
        Regex("""\b(read|money|note|rupee|medicine|tablet|strip|padh|dawai|paisa)""").containsMatchIn(t) || "पढ़" in t || "చదువు" in t -> Ask.READ
        Regex("""\b(teach|learn|lesson)\b|\bvibrations?\b.*\bmean""").containsMatchIn(t) -> Ask.LEARN
        Regex("""\b(use speech|speak to me|talk to me|voice mode|speech mode)""").containsMatchIn(t) -> Ask.SPEECH
        Regex("""\b(use vibration|vibration mode|vibrate only|haptic)""").containsMatchIn(t) -> Ask.HAPTIC
        Regex("""\b(talk more|more detail|chatty|tell me everything)""").containsMatchIn(t) -> Ask.CHATTY
        Regex("""\b(quiet|less|silent|shut up|stop talking)""").containsMatchIn(t) -> Ask.QUIET
        Regex("""\b(what|see|ahead|around|front|describe|kya hai|dikh)""").containsMatchIn(t) || "क्या" in t || "ఏమి" in t -> Ask.DESCRIBE
        else -> Ask.HELP
    }
}

const val HELP_TEXT = "You can ask: what's ahead, is it safe, read this, use speech, use vibration, or teach me the vibrations."

/** On-device speech recognition (no network). One utterance per [listen] call; main thread only. */
class VoiceInput(ctx: Context, private val onText: (String) -> Unit, private val onFail: () -> Unit) : RecognitionListener {
    private val rec: SpeechRecognizer? = when {
        SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) -> SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        SpeechRecognizer.isRecognitionAvailable(ctx) -> SpeechRecognizer.createSpeechRecognizer(ctx)
        else -> null
    }?.also { it.setRecognitionListener(this) }

    val available get() = rec != null

    fun listen() {
        rec?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }) ?: onFail()
    }

    override fun onResults(b: Bundle) {
        b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onText) ?: onFail()
    }

    override fun onError(error: Int) = onFail()
    override fun onReadyForSpeech(p: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(v: Float) = Unit
    override fun onBufferReceived(b: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onPartialResults(b: Bundle?) = Unit
    override fun onEvent(t: Int, b: Bundle?) = Unit
}
