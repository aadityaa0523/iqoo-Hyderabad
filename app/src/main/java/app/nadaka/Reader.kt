package app.nadaka

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/** Pure text rules for PAY and MEDS. Deterministic: no model guesses here. */
object Parse {
    private val MONEY_CUES = listOf("RESERVE BANK", "RUPEES", "CENTRAL GOVERNMENT", "GOVERNOR", "₹")
    private val MED_CUES = Regex("""\b(MG|MCG|ML|EXP|MFG|MFD|B\. ?NO|BATCH|TABLETS?|CAPSULES?|SYRUP)\b""")
    private val NOTE_WORDS = mapOf(
        "TWO THOUSAND" to 2000, "FIVE HUNDRED" to 500, "TWO HUNDRED" to 200, "ONE HUNDRED" to 100,
        "FIFTY" to 50, "TWENTY" to 20, "TEN RUPEES" to 10,
    )
    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
    private val UNITS = mapOf("MG" to "milligrams", "MCG" to "micrograms", "G" to "grams", "ML" to "millilitres", "IU" to "units")

    fun isMoney(text: String) = MONEY_CUES.any { it in text.uppercase() }
    fun isMedicine(text: String) = MED_CUES.containsMatchIn(text.uppercase())

    /** The denomination printed most often on the note, or null. */
    fun denomination(text: String): Int? {
        val up = text.uppercase()
        val votes = HashMap<Int, Int>()
        Regex("""(?<!\d)(2000|500|200|100|50|20|10)(?!\d)""").findAll(up).forEach { votes.merge(it.value.toInt(), 1, Int::plus) }
        NOTE_WORDS.forEach { (word, value) -> if (word in up) votes.merge(value, 2, Int::plus) }
        return votes.maxByOrNull { it.value }?.key
    }

    /** "650 milligrams", or null. */
    fun strength(text: String): String? {
        val m = Regex("""(\d+(?:\.\d+)?)\s?(MCG|MG|ML|IU|G)\b""").find(text.uppercase()) ?: return null
        return "${m.groupValues[1]} ${UNITS.getValue(m.groupValues[2])}"
    }

    /** Expiry as (year, month), from forms like "EXP 03/2027", "Exp.Date: MAR.2027", "EXP 03/27". */
    fun expiry(text: String): Pair<Int, Int>? {
        val up = text.uppercase()
        val prefix = """EXP[A-Z.]*\s*(?:DATE)?\s*[:.\-]?\s*"""
        Regex(prefix + """([A-Z]{3})[A-Z.]*\s*[.\-/ ]?\s*(\d{2,4})""").findAll(up).forEach {
            val month = MONTHS.indexOf(it.groupValues[1]) + 1
            if (month > 0) return year(it.groupValues[2]) to month
        }
        Regex(prefix + """(\d{1,2})\s*[/.\-]\s*(\d{2,4})""").findAll(up).forEach {
            val month = it.groupValues[1].toInt()
            if (month in 1..12) return year(it.groupValues[2]) to month
        }
        return null
    }

    /** Medicine is good through the end of its expiry month. */
    fun isExpired(expiry: Pair<Int, Int>, today: LocalDate) =
        !today.isBefore(LocalDate.of(expiry.first, expiry.second, 1).plusMonths(1))

    fun monthName(expiry: Pair<Int, Int>) =
        "${Month.of(expiry.second).getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${expiry.first}"

    private fun year(s: String) = if (s.length == 2) 2000 + s.toInt() else s.toInt()
}

/**
 * READ mode: coaches the user until the same answer is seen on two frames, then says it once.
 * Must be used from a single thread (the camera analysis thread).
 */
class Reader {
    private val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var startedMs = 0L
    private var lastCoachMs = 0L
    private var lastKey: String? = null
    private var total = 0
    private var lastNoteMs = 0L

    fun start() {
        startedMs = SystemClock.elapsedRealtime()
        lastCoachMs = startedMs
        lastKey = null
    }

    /** What to say this frame (null = nothing), and whether reading is finished. */
    fun step(frame: Bitmap): Pair<String?, Boolean> {
        val now = SystemClock.elapsedRealtime()
        if (now - startedMs > Settings.readTimeoutMs) return "Can't read clearly. I won't guess." to true

        val text = Tasks.await(ocr.process(InputImage.fromBitmap(frame, 0)))
        val lines = text.textBlocks.flatMap { it.lines }
        val all = text.text
        val area = text.textBlocks.mapNotNull { it.boundingBox }.fold(Rect()) { u, r -> u.apply { union(r) } }
            .let { it.width() * it.height() / (frame.width * frame.height).toFloat() }

        val (key, speech) = when {
            lines.isEmpty() -> return coach(now, "No text. Turn it over.")
            area < Settings.minTextArea -> return coach(now, "Move closer.")
            Parse.isMedicine(all) -> medicine(all, lines) // before money: strips print "M.R.P. ₹25"
            Parse.isMoney(all) -> Parse.denomination(all)?.let { "note $it" to it.toString() }
            else -> null
        } ?: return coach(now, "Hold still.")

        if (key != lastKey) { lastKey = key; return null to false } // need the same answer twice

        if (!key.startsWith("note")) return speech to true
        val note = speech.toInt()
        if (now - lastNoteMs > Settings.moneyTotalResetMs) total = 0
        total += note
        lastNoteMs = now
        return (if (total == note) "$note rupees." else "$note rupees. Total $total rupees.") to true
    }

    private fun medicine(all: String, lines: List<Text.Line>): Pair<String, String> {
        val name = lines
            .filter { l -> l.text.count { it.isLetter() } >= 4 && !Parse.isMedicine(l.text) }
            .maxByOrNull { it.boundingBox?.height() ?: 0 }
            ?.text?.lowercase()?.replaceFirstChar { it.uppercase() }
        val strength = Parse.strength(all)
        val exp = Parse.expiry(all)
        val expiry = when {
            exp == null -> "Expiry not found."
            Parse.isExpired(exp, LocalDate.now()) -> "Warning: expired ${Parse.monthName(exp)}. Do not take."
            else -> "Expires ${Parse.monthName(exp)}."
        }
        val speech = listOfNotNull(name, strength).joinToString(", ").ifEmpty { "Medicine" } + ". " + expiry
        return "med $strength $exp" to speech
    }

    private fun coach(now: Long, hint: String): Pair<String?, Boolean> {
        if (now - lastCoachMs < Settings.coachCooldownMs) return null to false
        lastCoachMs = now
        return hint to false
    }
}
