package app.nadaka

import java.util.Locale

/**
 * Language the app speaks alerts in (fixed templates, see [Say]). [qwen] = what Qwen is told; empty for Hindi and
 * Telugu on purpose: measured on the phone, Qwen3-VL-2B answered in broken Hindi and nonsense Telugu, so its free
 * answers stay English. Voice commands already understand some Hindi and Telugu words.
 */
enum class SpeechLang(val label: String, val locale: Locale, val qwen: String) {
    EN("English", Locale.ENGLISH, ""),
    HI("हिंदी  ·  Hindi", Locale.forLanguageTag("hi-IN"), ""),
    TE("తెలుగు  ·  Telugu", Locale.forLanguageTag("te-IN"), ""),
}

/**
 * Free answers (Qwen, cloud) are produced and safety-checked in English, then translated on the phone with ML Kit
 * (offline once the ~30 MB language pack is downloaded, which starts as soon as Hindi or Telugu is chosen).
 * If the pack isn't there yet, the English answer is spoken.
 */
class AnswerTranslator {
    private var lang: SpeechLang? = null
    private var tr: com.google.mlkit.nl.translate.Translator? = null
    @Volatile var ready = false
        private set

    /** Prepare (and download if needed) the pack for the chosen language. Cheap to call repeatedly. */
    fun prepare(l: SpeechLang) {
        if (l == lang) return
        tr?.close(); tr = null; ready = false; lang = l
        if (l == SpeechLang.EN) return
        val target = if (l == SpeechLang.HI) com.google.mlkit.nl.translate.TranslateLanguage.HINDI else com.google.mlkit.nl.translate.TranslateLanguage.TELUGU
        val t = com.google.mlkit.nl.translate.Translation.getClient(
            com.google.mlkit.nl.translate.TranslatorOptions.Builder()
                .setSourceLanguage(com.google.mlkit.nl.translate.TranslateLanguage.ENGLISH).setTargetLanguage(target).build())
        tr = t
        // Offline: the pack already on the phone is enough - check for it directly, don't depend on a download check.
        com.google.mlkit.common.model.RemoteModelManager.getInstance()
            .isModelDownloaded(com.google.mlkit.nl.translate.TranslateRemoteModel.Builder(target).build())
            .addOnSuccessListener { have -> if (have && tr === t) { ready = true; android.util.Log.i(TAG, "translate: ${l.name} pack on the phone (works offline)") } }
        // Online: fetch it if it's missing (once, ~30 MB).
        t.downloadModelIfNeeded().addOnSuccessListener { if (tr === t) { ready = true; android.util.Log.i(TAG, "translate: ${l.name} pack ready") } }
            .addOnFailureListener { android.util.Log.w(TAG, "translate: ${l.name} pack not downloaded yet (${it.javaClass.simpleName})") }
    }

    /** [text] in English -> the chosen language, or the English text if translation isn't possible right now. */
    fun translate(text: String, done: (String) -> Unit) {
        val t = tr
        if (Prefs.speechLang == SpeechLang.EN || t == null || !ready || Say.isIndic(text)) { done(text); return }
        t.translate(text).addOnSuccessListener { done(it.ifBlank { text }) }.addOnFailureListener { done(text) }
    }
}

/** Names people use on Indian streets (the model's COCO names are American). */
fun displayName(label: String) = when (label) {
    "motorcycle" -> "bike"
    "bicycle" -> "cycle"
    "dining table" -> "table"
    "potted plant" -> "plant"
    "cell phone" -> "phone"
    "sports ball" -> "ball"
    "traffic light" -> "traffic signal"
    "tv" -> "TV"
    else -> label
}

/**
 * Translates the app's own alert sentences into Hindi / Telugu. Only sentences the app itself builds are
 * translated, from fixed templates (no machine translation): anything else returns null and is spoken in English.
 * Pure logic, unit-tested.
 */
object Say {
    private const val DIST = """(very close|[\d.]+ metres?)"""
    private const val CLOCK = """(\d{1,2} o'clock)"""

    private val HI_WORDS = mapOf(
        "person" to "कोई व्यक्ति", "car" to "कार", "bike" to "बाइक", "cycle" to "साइकिल", "bus" to "बस", "truck" to "ट्रक",
        "dog" to "कुत्ता", "cow" to "गाय", "horse" to "घोड़ा", "sheep" to "भेड़", "cat" to "बिल्ली", "table" to "मेज़",
        "chair" to "कुर्सी", "bench" to "बेंच", "plant" to "गमला", "traffic signal" to "ट्रैफ़िक सिग्नल", "door" to "दरवाज़ा",
        "exit" to "निकास", "stairs" to "सीढ़ियाँ", "lift" to "लिफ्ट", "bottle" to "बोतल", "backpack" to "बैग", "handbag" to "बैग",
        "suitcase" to "सूटकेस", "bed" to "बिस्तर", "couch" to "सोफ़ा", "TV" to "टीवी", "laptop" to "लैपटॉप", "phone" to "फ़ोन",
        "cup" to "कप", "umbrella" to "छाता", "stop sign" to "स्टॉप साइन",
    )
    /** Feminine in Hindi: "आ रही है" instead of "आ रहा है". */
    private val HI_FEM = setOf("कार", "बाइक", "साइकिल", "बस", "गाय", "भेड़", "बिल्ली", "मेज़", "कुर्सी", "बोतल", "लिफ्ट")
    private val TE_WORDS = mapOf(
        "person" to "ఒక వ్యక్తి", "car" to "కారు", "bike" to "బైక్", "cycle" to "సైకిల్", "bus" to "బస్సు", "truck" to "లారీ",
        "dog" to "కుక్క", "cow" to "ఆవు", "horse" to "గుర్రం", "sheep" to "గొర్రె", "cat" to "పిల్లి", "table" to "టేబుల్",
        "chair" to "కుర్చీ", "bench" to "బెంచి", "plant" to "మొక్క", "traffic signal" to "ట్రాఫిక్ సిగ్నల్", "door" to "తలుపు",
        "exit" to "బయటకు దారి", "stairs" to "మెట్లు", "lift" to "లిఫ్ట్", "bottle" to "సీసా", "backpack" to "బ్యాగ్", "handbag" to "బ్యాగ్",
        "suitcase" to "సూట్‌కేస్", "bed" to "మంచం", "couch" to "సోఫా", "TV" to "టీవీ", "laptop" to "ల్యాప్‌టాప్", "phone" to "ఫోన్",
        "cup" to "కప్పు", "umbrella" to "గొడుగు", "stop sign" to "స్టాప్ సైన్",
    )

    private fun word(w: String, lang: SpeechLang) = (if (lang == SpeechLang.HI) HI_WORDS else TE_WORDS)[w.lowercase()]
        ?: (if (lang == SpeechLang.HI) HI_WORDS else TE_WORDS)[w] ?: w

    private fun dist(d: String?, lang: SpeechLang): String {
        if (d.isNullOrEmpty()) return ""
        if (d == "very close") return if (lang == SpeechLang.HI) ", बहुत पास" else ", చాలా దగ్గరగా"
        val n = d.substringBefore(" ")
        return if (lang == SpeechLang.HI) ", $n मीटर पर" else ", $n మీటర్ల దూరంలో"
    }

    private fun clock(c: String?, lang: SpeechLang): String {
        if (c.isNullOrEmpty()) return ""
        val h = c.substringBefore(" ")
        return if (lang == SpeechLang.HI) ", $h बजे की दिशा में" else ", $h గంటల దిశలో"
    }

    private class Rule(val re: Regex, val hi: (MatchResult) -> String, val te: (MatchResult) -> String)
    private fun g(m: MatchResult, i: Int) = m.groupValues.getOrNull(i)?.ifEmpty { null }

    private val rules = listOf(
        Rule(Regex("""^Stop\. Stairs down ahead(?:, $DIST)?\.(?: At least (\d+) steps\.)?$"""),
            { "रुकिए। आगे नीचे सीढ़ियाँ हैं${dist(g(it, 1), SpeechLang.HI)}।" + (g(it, 2)?.let { n -> " कम से कम $n सीढ़ियाँ।" } ?: "") },
            { "ఆగండి. ముందు కిందకి మెట్లు ఉన్నాయి${dist(g(it, 1), SpeechLang.TE)}." + (g(it, 2)?.let { n -> " కనీసం $n మెట్లు." } ?: "") }),
        Rule(Regex("""^Stop\. Drop ahead(?:, $DIST)?\.$"""),
            { "रुकिए। आगे नीचे उतार है${dist(g(it, 1), SpeechLang.HI)}।" }, { "ఆగండి. ముందు కిందకి దిగుడు ఉంది${dist(g(it, 1), SpeechLang.TE)}." }),
        Rule(Regex("""^Stop\. Drop\.$"""), { "रुकिए। उतार।" }, { "ఆగండి. దిగుడు." }),
        Rule(Regex("""^Step down(?: ahead)?(?:, $DIST)?\.$"""),
            { "आगे एक सीढ़ी नीचे${dist(g(it, 1), SpeechLang.HI)}।" }, { "ముందు ఒక మెట్టు కిందకి${dist(g(it, 1), SpeechLang.TE)}." }),
        Rule(Regex("""^Stairs going up ahead(?:, $DIST)?\.(?: About (\d+) steps\.)?$"""),
            { "आगे ऊपर सीढ़ियाँ हैं${dist(g(it, 1), SpeechLang.HI)}।" + (g(it, 2)?.let { n -> " लगभग $n सीढ़ियाँ।" } ?: "") },
            { "ముందు పైకి మెట్లు ఉన్నాయి${dist(g(it, 1), SpeechLang.TE)}." + (g(it, 2)?.let { n -> " సుమారు $n మెట్లు." } ?: "") }),
        Rule(Regex("""^Stairs up\.$"""), { "ऊपर सीढ़ियाँ।" }, { "పైకి మెట్లు." }),
        Rule(Regex("""^Head height obstacle(?:, $DIST)?(?:, $CLOCK)?\.$"""),
            { "सिर की ऊँचाई पर रुकावट${dist(g(it, 1), SpeechLang.HI)}${clock(g(it, 2), SpeechLang.HI)}।" },
            { "తల ఎత్తులో అడ్డంకి${dist(g(it, 1), SpeechLang.TE)}${clock(g(it, 2), SpeechLang.TE)}." }),
        Rule(Regex("""^Head\.$"""), { "सिर बचाइए।" }, { "తల జాగ్రత్త." }),
        Rule(Regex("""^Obstacle at waist height(?:, $DIST)?\.$"""),
            { "कमर की ऊँचाई पर रुकावट${dist(g(it, 1), SpeechLang.HI)}।" }, { "నడుము ఎత్తులో అడ్డంకి${dist(g(it, 1), SpeechLang.TE)}." }),
        Rule(Regex("""^Waist height\.$"""), { "कमर की ऊँचाई।" }, { "నడుము ఎత్తు." }),
        Rule(Regex("""^Obstacle ahead(?:, $DIST)?\.$"""),
            { "आगे रुकावट${dist(g(it, 1), SpeechLang.HI)}।" }, { "ముందు అడ్డంకి${dist(g(it, 1), SpeechLang.TE)}." }),
        Rule(Regex("""^Crowd ahead\.$"""), { "आगे भीड़ है।" }, { "ముందు జనం ఉన్నారు." }),
        Rule(Regex("""^([A-Za-z ]+?) approaching(?:, $DIST)?(?:, $CLOCK)?\.$"""),
            { val n = word(it.groupValues[1], SpeechLang.HI); "$n पास आ ${if (n in HI_FEM) "रही" else "रहा"} है${dist(g(it, 2), SpeechLang.HI)}${clock(g(it, 3), SpeechLang.HI)}।" },
            { val n = word(it.groupValues[1], SpeechLang.TE); "$n దగ్గరికి ${if (it.groupValues[1] == "person") "వస్తున్నారు" else "వస్తోంది"}${dist(g(it, 2), SpeechLang.TE)}${clock(g(it, 3), SpeechLang.TE)}." }),
        Rule(Regex("""^([A-Za-z ]+?) moving(?:, $DIST)?(?:, $CLOCK)?\.$"""),
            { val n = word(it.groupValues[1], SpeechLang.HI); "$n चल ${if (n in HI_FEM) "रही" else "रहा"} है${dist(g(it, 2), SpeechLang.HI)}${clock(g(it, 3), SpeechLang.HI)}।" },
            { "${word(it.groupValues[1], SpeechLang.TE)} కదులుతోంది${dist(g(it, 2), SpeechLang.TE)}${clock(g(it, 3), SpeechLang.TE)}." }),
        Rule(Regex("""^([A-Za-z ]+?) close(?:, $DIST)?(?:, $CLOCK)?\.$"""),
            { "${word(it.groupValues[1], SpeechLang.HI)} पास में${dist(g(it, 2), SpeechLang.HI)}${clock(g(it, 3), SpeechLang.HI)}।" },
            { "${word(it.groupValues[1], SpeechLang.TE)} దగ్గరలో${dist(g(it, 2), SpeechLang.TE)}${clock(g(it, 3), SpeechLang.TE)}." }),
        Rule(Regex("""^Bus (\S+)\.$"""), { "बस नंबर ${it.groupValues[1]}।" }, { "బస్ నంబర్ ${it.groupValues[1]}." }),
        Rule(Regex("""^Drifting left\. Turn right a little\.$"""), { "बाईं ओर जा रहे हैं। थोड़ा दाएँ मुड़िए।" }, { "ఎడమ వైపు వెళ్తున్నారు. కొంచెం కుడి వైపు తిరగండి." }),
        Rule(Regex("""^Drifting right\. Turn left a little\.$"""), { "दाईं ओर जा रहे हैं। थोड़ा बाएँ मुड़िए।" }, { "కుడి వైపు వెళ్తున్నారు. కొంచెం ఎడమ వైపు తిరగండి." }),
        Rule(Regex("""^Straight\.$"""), { "सीधे।" }, { "నేరుగా." }),
        Rule(Regex("""^Keeping you straight\. Walk\.$"""), { "मैं आपको सीधा रखूँगा। चलिए।" }, { "మిమ్మల్ని నేరుగా నడిపిస్తాను. నడవండి." }),
        Rule(Regex("""^New direction\. Keeping you straight\.$"""), { "नई दिशा। सीधा रखूँगा।" }, { "కొత్త దిశ. నేరుగా నడిపిస్తాను." }),
        Rule(Regex("""^Walk straight off\.$"""), { "सीधा चलना बंद।" }, { "నేరుగా నడక ఆపాను." }),
        Rule(Regex("""^Fall detected\..*$"""),
            { "आप गिर गए हैं। अगर आप ठीक हैं तो वॉल्यूम बटन दबाइए। नहीं तो 7 सेकंड में अलार्म बजेगा और आपके आपातकालीन संपर्कों को संदेश जाएगा।" },
            { "మీరు పడిపోయారు. మీరు బాగుంటే వాల్యూమ్ బటన్ నొక్కండి. లేకపోతే 7 సెకన్లలో అలారం మోగుతుంది, మీ అత్యవసర పరిచయాలకు సందేశం వెళ్తుంది." }),
        Rule(Regex("""^Alarm in 3 seconds\.$"""), { "3 सेकंड में अलार्म।" }, { "3 సెకన్లలో అలారం." }),
        Rule(Regex("""^OK\. Glad you're fine\.$"""), { "ठीक है। अच्छा है कि आप ठीक हैं।" }, { "సరే. మీరు బాగున్నందుకు సంతోషం." }),
        Rule(Regex("""^I have fallen and need help\.$"""), { "मुझे मदद चाहिए। कृपया मदद कीजिए।" }, { "నేను పడిపోయాను, సహాయం కావాలి." }),
        Rule(Regex("""^Sending your location to (.+)\.$"""),
            { "आपकी लोकेशन ${it.groupValues[1].replace(" and ", " और ")} को भेजी जा रही है।" },
            { "మీ లొకేషన్ ${it.groupValues[1].replace(" and ", " మరియు ")} కి పంపుతున్నాను." }),
        Rule(Regex("""^Camera blocked\. Use your cane\.$"""), { "कैमरा ढका हुआ है। अपनी छड़ी इस्तेमाल कीजिए।" }, { "కెమెరా మూసుకుపోయింది. మీ కర్ర వాడండి." }),
        Rule(Regex("""^Too dark.*Use your cane\.$"""), { "बहुत अँधेरा है। अपनी छड़ी इस्तेमाल कीजिए।" }, { "చాలా చీకటిగా ఉంది. మీ కర్ర వాడండి." }),
        Rule(Regex("""^Stopped\.$"""), { "बंद किया।" }, { "ఆపేశాను." }),
        Rule(Regex("""^([A-Za-z ]+?) (on your left|on your right|ahead)(, open|, closed)?\.$"""),
            { val side = when (it.groupValues[2]) { "on your left" -> "आपकी बाईं ओर"; "on your right" -> "आपकी दाईं ओर"; else -> "आगे" }
              val st = when (it.groupValues[3]) { ", open" -> ", खुला"; ", closed" -> ", बंद"; else -> "" }
              "${word(it.groupValues[1], SpeechLang.HI)} $side$st।" },
            { val side = when (it.groupValues[2]) { "on your left" -> "మీ ఎడమ వైపు"; "on your right" -> "మీ కుడి వైపు"; else -> "ముందు" }
              val st = when (it.groupValues[3]) { ", open" -> ", తెరిచి ఉంది"; ", closed" -> ", మూసి ఉంది"; else -> "" }
              "${word(it.groupValues[1], SpeechLang.TE)} $side$st." }),
        // A static object: "Chair, 2 metres, 12 o'clock." (keep last: it matches any "Word, ..." sentence)
        Rule(Regex("""^([A-Za-z ]+?)(?:, $DIST)(?:, $CLOCK)?\.$"""),
            { "${word(it.groupValues[1], SpeechLang.HI)}${dist(g(it, 2), SpeechLang.HI)}${clock(g(it, 3), SpeechLang.HI)}।" },
            { "${word(it.groupValues[1], SpeechLang.TE)}${dist(g(it, 2), SpeechLang.TE)}${clock(g(it, 3), SpeechLang.TE)}." }),
    )

    /** Translation of one app sentence (or several joined by spaces), or null if any part has no template. */
    fun tr(text: String, lang: SpeechLang): String? {
        if (lang == SpeechLang.EN) return null
        val t = text.trim()
        one(t, lang)?.let { return it }
        // Several alerts spoken together ("Stop. Drop. Head."): translate each sentence, all or nothing.
        val parts = t.split(Regex("""(?<=[.!?])\s+""")).filter { it.isNotBlank() } // sentence ends, not "1.5"
        if (parts.size < 2) return null
        // Longest run of sentences that one template covers ("Stop. Drop." is one), then the rest.
        val out = ArrayList<String>()
        var i = 0
        while (i < parts.size) {
            val (j, tr) = (parts.size downTo i + 1).firstNotNullOfOrNull { j ->
                one(parts.subList(i, j).joinToString(" "), lang)?.let { j to it }
            } ?: return null
            out += tr; i = j
        }
        return out.joinToString(" ")
    }

    private fun one(t: String, lang: SpeechLang) =
        rules.firstNotNullOfOrNull { r -> r.re.matchEntire(t)?.let { if (lang == SpeechLang.HI) r.hi(it) else r.te(it) } }

    /** Already Hindi or Telugu text (e.g. a Qwen answer asked in that language). */
    fun isIndic(text: String) = text.any { it in 'ऀ'..'ॿ' || it in 'ఀ'..'౿' }
}
