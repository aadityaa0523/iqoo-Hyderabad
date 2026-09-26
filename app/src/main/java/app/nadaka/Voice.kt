package app.nadaka

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.util.Log
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

    // A language model must never talk anyone into moving. Any answer containing these is discarded.
    private val greenLights = Regex(
        """\b(safe to (cross|walk|go|move|proceed)|you can (go|cross|walk|move|proceed|continue)|go ahead|""" +
            """(path|way|road|street) (is|looks) (clear|free|safe|open)|all clear|it'?s clear|it is clear|""" +
            """no obstacles?|nothing (in|blocking) (your|the) (way|path)|it'?s safe|it is safe)\b"""
    )

    fun greenLight(answer: String) = greenLights.containsMatchIn(answer.lowercase())

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

enum class Ask { SAFETY, EMERGENCY, FIND, STOP, SIT, VEHICLE, WALK, DESCRIBE, SIGN, READ, CHATTY, QUIET, SPEECH, HAPTIC, LEARN, HELP }

/** Deterministic intent grammar. Safety is checked FIRST and wins over everything. */
/** Typical recognizer slips on short commands, normalised before matching. */
private val FIXUPS = listOf(
    Regex("""\bwhat'?s a head\b""") to "what's ahead", Regex("""\bwhat is a head\b""") to "what is ahead",
    Regex("""\bwatts?\b""") to "what's", Regex("""\bwhats\b""") to "what's",
    Regex("""\bred (this|it|that)\b""") to "read $1", Regex("""\breed\b""") to "read",
    Regex("""\bsave\b""") to "safe", Regex("""\bkross\b""") to "cross",
)

fun normalise(text: String): String {
    var t = text.lowercase().replace(Regex("""[?!.,;:]"""), " ").replace(Regex("""\s+"""), " ").trim()
    FIXUPS.forEach { (r, to) -> t = r.replace(t, to) }
    return t
}

/** Deterministic intent grammar. Safety is checked FIRST and wins over everything. */
fun intentOf(text: String): Ask {
    val t = normalise(text)
    if (SafetyGate.isSafetyQuestion(t)) return Ask.SAFETY
    return when {
        Regex("""\b(emergency|sos|help me|i need help|call for help|bachao)\b""").containsMatchIn(t) -> Ask.EMERGENCY
        findTarget(t) != null -> Ask.FIND
        Regex("""^(stop|cancel|stop looking|never mind)\b""").containsMatchIn(t) -> Ask.STOP
        Regex("""\b(i'?m|i am|we'?re)\s+(sitting|seated)|\bsitting down\b""").containsMatchIn(t) -> Ask.SIT
        Regex("""\b(i'?m|i am|we'?re)\s+(on|in)\s+(a |an |the )?(bus|car|auto|train|metro|cab|taxi|vehicle|rickshaw)""").containsMatchIn(t) -> Ask.VEHICLE
        Regex("""\b(let'?s go|lets go|i'?m walking|i am walking|start walking|walking mode)\b""").containsMatchIn(t) -> Ask.WALK
        Regex("""\b(sign|signs|board|written|writing|label|poster|menu|text|what does (it|that|this) say)\b""").containsMatchIn(t) -> Ask.SIGN
        Regex("""\b(read|money|note|notes|rupee|rupees|medicine|tablet|strip|currency|cash|padh|dawai|paisa)""").containsMatchIn(t) || "पढ़" in t || "చదువు" in t -> Ask.READ
        Regex("""\b(teach|learn|lesson)\b|\bvibrations?\b.*\bmean""").containsMatchIn(t) -> Ask.LEARN
        Regex("""\b(use speech|speak to me|talk to me|voice mode|speech mode|speak everything)""").containsMatchIn(t) -> Ask.SPEECH
        Regex("""\b(use vibration|vibration mode|vibrate only|haptic)""").containsMatchIn(t) -> Ask.HAPTIC
        Regex("""\b(talk more|more detail|chatty|tell me everything)""").containsMatchIn(t) -> Ask.CHATTY
        Regex("""\b(quiet|less|silent|shut up|stop talking)""").containsMatchIn(t) -> Ask.QUIET
        Regex("""\b(what|see|ahead|around|front|describe|near|nearby|there|surroundings|kya hai|dikh)""").containsMatchIn(t) || "क्या" in t || "ఏమి" in t -> Ask.DESCRIBE
        else -> Ask.HELP
    }
}

/** Picks the first recognizer alternative that means something (n-best), else the top one. */
fun bestIntent(alternatives: List<String>): Pair<String, Ask> {
    // Safety in ANY alternative wins: if the person might have asked "can I cross", treat it so.
    alternatives.firstOrNull { intentOf(it) == Ask.SAFETY }?.let { return it to Ask.SAFETY }
    alternatives.firstOrNull { intentOf(it) != Ask.HELP }?.let { return it to intentOf(it) }
    return (alternatives.firstOrNull() ?: "") to Ask.HELP
}

const val HELP_TEXT = "You can ask: what's ahead, find a chair, read the sign, read this note, I'm sitting, let's go, or emergency."

/** On-device speech recognition (no network). One utterance per [listen] call; main thread only. */
/**
 * Push-to-talk speech recognition, on-device (no network). Why the first version missed words:
 * the recognizer needs ~0.3-1 s to open the microphone, users started talking immediately; each
 * press restarted the session; and our own speech/vibration could run while it listened.
 * Now: the "ready" cue comes only when the mic is actually open (onReadyForSpeech), a second press
 * ends the utterance, the app is silent while listening, and all n-best alternatives plus partial
 * results are used. Main thread only.
 */
class VoiceInput(
    ctx: Context,
    private val onReady: () -> Unit,
    private val onText: (List<String>) -> Unit,
    private val onFail: (String) -> Unit,
) : RecognitionListener {
    // Two engines: the strictly on-device one (needs its language pack) and the system default
    // (Google Speech Services, offline when its pack is present). Start with whichever can work now.
    private val onDevice = if (SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx))
        SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx).also { it.setRecognitionListener(this) } else null
    private val system = if (SpeechRecognizer.isRecognitionAvailable(ctx))
        SpeechRecognizer.createSpeechRecognizer(ctx).also { it.setRecognitionListener(this) } else null
    private var rec: SpeechRecognizer? = onDevice ?: system
    private var retried = false
    private var partial: String? = null
    private var windowStartMs = 0L // when the user pressed: we keep listening for Settings.listenWindowMs
    private var spoke = false      // the recognizer heard speech start in this window
    private var restarting = false

    init {
        // Found on the loaner: the on-device English pack was missing ("language pack not installed"),
        // so every question failed. Check it, fall back to the system engine, and fetch the pack.
        if (onDevice != null && Build.VERSION.SDK_INT >= 33) {
            onDevice.checkRecognitionSupport(intent(), ctx.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(r: RecognitionSupport) {
                    val lang = Settings.voiceLanguage
                    Log.i(TAG, "on-device speech: installed=${r.installedOnDeviceLanguages} pending=${r.pendingOnDeviceLanguages}")
                    if (lang !in r.installedOnDeviceLanguages) useSystemAndDownload()
                }
                override fun onError(error: Int) { Log.i(TAG, "on-device speech check failed: $error"); useSystemAndDownload() }
            })
        }
    }

    private fun useSystemAndDownload() {
        if (system != null) rec = system
        if (Build.VERSION.SDK_INT >= 33) runCatching { onDevice?.triggerModelDownload(intent()) }
        Log.i(TAG, "speech engine: ${if (rec === system) "system (Google Speech Services)" else "on-device"}; on-device pack download requested")
    }

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Settings.voiceLanguage)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // Hints: give people time to start and to pause mid-sentence.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
    }

    val available get() = onDevice != null || system != null
    @Volatile var listening = false
        private set

    /** First press: start. Press again while listening: "I'm done talking". */
    fun press() {
        val r = rec ?: return onFail("no recognizer")
        if (listening) { r.stopListening(); return }
        onDevice?.cancel(); system?.cancel() // never stack sessions
        partial = null
        spoke = false
        restarting = false
        windowStartMs = android.os.SystemClock.elapsedRealtime()
        listening = true
        r.startListening(intent())
    }

    /**
     * Measured on the loaner: the on-device recognizer closes the mic after ~2 s of silence and
     * ignores the silence-length hints, so a short pause after the buzz ended every question.
     * Until the user actually starts talking, silently reopen it for the whole listen window.
     */
    private fun keepWaiting(): Boolean {
        val left = Settings.listenWindowMs - (android.os.SystemClock.elapsedRealtime() - windowStartMs)
        if (spoke || left < 800) return false
        restarting = true
        listening = true
        rec?.startListening(intent())
        return true
    }

    override fun onReadyForSpeech(p: Bundle?) { if (!restarting) onReady() } // one cue per press

    override fun onPartialResults(b: Bundle?) {
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { partial = it }
    }

    override fun onResults(b: Bundle) {
        listening = false
        retried = false
        val all = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().filter { it.isNotBlank() }
        val texts = all.ifEmpty { listOfNotNull(partial) }
        if (texts.isEmpty()) onFail("empty result") else onText(texts)
    }

    override fun onError(error: Int) {
        listening = false
        val missingPack = error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
        if (missingPack && rec === onDevice && system != null && !retried) {
            retried = true
            useSystemAndDownload()
            press() // same question, other engine: the ready buzz tells the user to speak again
            return
        }
        retried = false
        val silent = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        if (silent && partial == null && keepWaiting()) return
        val p = partial
        if (p != null) onText(listOf(p)) // heard something before the error: use it
        else onFail(errorName(error))
    }

    override fun onBeginningOfSpeech() { spoke = true }
    override fun onRmsChanged(v: Float) = Unit
    override fun onBufferReceived(b: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(t: Int, b: Bundle?) = Unit

    private fun errorName(e: Int) = when (e) {
        SpeechRecognizer.ERROR_NO_MATCH -> "no match"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech heard"
        SpeechRecognizer.ERROR_AUDIO -> "audio error"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
        SpeechRecognizer.ERROR_CLIENT -> "client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no microphone permission"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "language not supported"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language pack not installed"
        else -> "error $e"
    }
}
