package app.nadaka

/**
 * Things the object detector has no class for (door, exit, stairs, lift, counter...): Qwen looks every few
 * seconds while the user turns. Announced only when two looks in a row agree on the side (a single look can
 * imagine a door). Pure logic, unit-tested.
 */
class SceneFinder(val what: String, private val startMs: Long) {
    private var lastSide: String? = null
    @Volatile var asking = false
    var lastAskMs = -1_000_000L

    fun prompt(): String {
        val extra = when {
            "door" in what -> ", and whether it is open or closed"
            "exit" in what || "sign" in what -> ", and what the sign says"
            else -> ""
        }
        return "Is there a $what in this photo? If yes, say where it is: left, ahead or right$extra, in one short sentence. " +
            "If there is no $what, answer exactly: not visible."
    }

    /** One answer. Returns (words to say or null, finished). */
    fun answer(now: Long, text: String?): Pair<String?, Boolean> {
        if (now - startMs > Settings.sceneFindMaxMs) return "I couldn't find a $what. Try turning slowly, or ask someone nearby." to true
        val t = text?.lowercase()?.trim() ?: return null to false
        if ("not visible" in t || t.startsWith("no")) { lastSide = null; return null to false }
        val side = when {
            "left" in t -> "on your left"
            "right" in t -> "on your right"
            else -> "ahead"
        }
        if (side != lastSide) { lastSide = side; return null to false }
        val state = when { "open" in t -> ", open"; "closed" in t -> ", closed"; else -> "" }
        return "${what.replaceFirstChar { it.uppercase() }} $side$state." to true
    }
}

/** A word the on-device OCR read, with where it sits in the image (pixels). */
data class Word(val text: String, val left: Int, val top: Int, val height: Int)

/**
 * Lift panels and door signs, read deterministically from OCR positions (no model guessing):
 * lift buttons top to bottom, left to right ("5, 4, 3, 2, 1, G"); room / flat numbers ("Room 204").
 */
object Panel {
    private val FLOOR = Regex("""^(-?\d{1,2}|G|LG|UG|B\d?|P\d?|M|L\d?)$""")
    private val ROOM = Regex("""^[A-Z]?-?\d{2,4}[A-Z]?$""")

    fun liftButtons(words: List<Word>): String? {
        val floors = words.map { it.copy(text = it.text.uppercase().trim('.', ',', ':')) }.filter { FLOOR.matches(it.text) }
        if (floors.size < 2) return null
        val rowH = floors.map { it.height }.sorted()[floors.size / 2].coerceAtLeast(1)
        val ordered = floors.sortedWith(compareBy({ it.top / rowH }, { it.left })).map { if (it.text == "G") "ground" else it.text }.distinct()
        return "Lift buttons, top to bottom: ${ordered.joinToString(", ")}."
    }

    fun roomNumber(words: List<Word>): String? {
        val n = words.map { it.text.uppercase().trim('.', ',', ':', '#') }.filter { ROOM.matches(it) }
        // The biggest number is the door sign; small ones are usually floor plans or notices.
        val best = words.filter { ROOM.matches(it.text.uppercase().trim('.', ',', ':', '#')) }.maxByOrNull { it.height } ?: return null
        return "Number ${best.text.uppercase().trim('.', ',', ':', '#')}." .takeIf { n.isNotEmpty() }
    }

    fun isRoomQuestion(q: String) = Regex("""\b(room|door|flat|apartment|office|cabin|house) (number|no)\b|\bwhich room\b""").containsMatchIn(q.lowercase())
}
