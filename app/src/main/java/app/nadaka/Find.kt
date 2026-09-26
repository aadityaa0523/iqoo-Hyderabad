package app.nadaka

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.VibratorManager

/** Everyday words -> the detector's COCO labels. Targets not listed here (door, stairs, exit) go to Gemma. */
private val FINDABLE = mapOf(
    "chair" to "chair", "seat" to "chair", "sofa" to "couch", "couch" to "couch", "table" to "dining table",
    "person" to "person", "someone" to "person", "somebody" to "person", "people" to "person", "man" to "person", "woman" to "person",
    "bottle" to "bottle", "water" to "bottle", "cup" to "cup", "glass" to "cup", "mug" to "cup",
    "phone" to "cell phone", "mobile" to "cell phone", "bag" to "backpack", "backpack" to "backpack", "handbag" to "handbag",
    "bench" to "bench", "bicycle" to "bicycle", "cycle" to "bicycle", "car" to "car", "bike" to "motorcycle", "scooter" to "motorcycle",
    "motorcycle" to "motorcycle", "bus" to "bus", "dog" to "dog", "cat" to "cat", "cow" to "cow", "tv" to "tv", "television" to "tv",
    "laptop" to "laptop", "book" to "book", "toilet" to "toilet", "sink" to "sink", "bed" to "bed", "clock" to "clock",
    "umbrella" to "umbrella", "suitcase" to "suitcase", "keyboard" to "keyboard", "remote" to "remote", "plant" to "potted plant",
    "fridge" to "refrigerator", "refrigerator" to "refrigerator", "scissors" to "scissors", "keys" to "", "door" to "", "stairs" to "",
)

/** What the user asked to find: the words after "find / where is / look for", and the detector label if it has one. */
fun findTarget(text: String): Pair<String, String?>? {
    val m = Regex("""\b(find|where is|where's|wheres|look for|search for|locate|take me to)\s+(?:my |the |a |an |some )?([a-z ]+)""")
        .find(normalise(text)) ?: return null
    val words = m.groupValues[2].trim().split(" ").filter { it.isNotEmpty() }.take(3)
    if (words.isEmpty()) return null
    val label = words.firstNotNullOfOrNull { w -> FINDABLE[w] ?: FINDABLE[w.removeSuffix("s")] }?.ifEmpty { null }
    return words.joinToString(" ") to label
}

/**
 * Guides the user to the nearest object with [label]: clock direction and distance every few
 * seconds, "right in front of you" when it is within reach. Pure logic, unit-tested.
 */
class Finder(val label: String, private val startMs: Long) {
    private var lastSaidMs = -1_000_000L
    private var lastSeenMs = startMs
    var done = false
        private set

    fun update(now: Long, tracks: List<Track>): String? {
        if (done) return null
        val name = label.replaceFirstChar { it.uppercase() }
        if (now - startMs > Settings.findTimeoutMs) { done = true; return "Stopped looking for the $label." }
        val t = tracks.filter { it.label == label && it.hits >= 3 && !it.metres.isNaN() }.minByOrNull { it.metres }
        if (t != null) {
            lastSeenMs = now
            if (t.metres < Settings.veryCloseM) { done = true; return "$name, right in front of you, ${clock(t.bearing)}." }
            if (now - lastSaidMs < Settings.findRepeatMs) return null
            lastSaidMs = now
            return "$name, ${clock(t.bearing)}, ${metres(t.metres)}."
        }
        if (now - lastSeenMs > 3000 && now - lastSaidMs > Settings.findRepeatMs * 2) {
            lastSaidMs = now
            return "No $label in view. Turn slowly."
        }
        return null
    }
}

/**
 * Hold both volume keys for 2 s (or say "emergency"): a loud alarm and strong vibration to draw
 * people nearby, plus a spoken call for help. Any volume key stops it. ponytail: no auto-calling
 * (an accidental 112 call during a demo is worse); an emergency-contact SMS can be added later.
 */
class Emergency(ctx: Context) {
    private val vibrator = ctx.getSystemService(VibratorManager::class.java).defaultVibrator
    private var tone: ToneGenerator? = null
    var active = false
        private set

    fun start() {
        if (active) return
        active = true
        tone = ToneGenerator(AudioManager.STREAM_ALARM, 100).also { it.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 20_000) }
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 300), intArrayOf(0, 255, 0), 0)) // repeats
    }

    fun stop() {
        if (!active) return
        active = false
        tone?.release(); tone = null
        vibrator.cancel()
    }
}
