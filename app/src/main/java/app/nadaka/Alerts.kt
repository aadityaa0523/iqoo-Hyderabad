package app.nadaka

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/** How a message should feel in the hand. */
enum class Buzz { SIDE, AHEAD, APPROACH, WARN }

/**
 * [text] is the full sentence (speech mode, caption, "what's ahead"). In haptics mode (default) the
 * [tacton] carries the meaning and only [short] is spoken; null = vibration only (docs/haptics.md).
 */
data class Alert(val text: String, val buzz: Buzz, val tacton: Tacton? = null, val short: String? = null)

/** Is the camera image usable at all? Checked before trusting any detection. */
enum class Health(val message: String) {
    OK(""),
    BLOCKED("Camera blocked. Use your cane."),
    DARK("Too dark, I can't see. Use your cane."),
    BLURRY("Camera is blurry. Wipe the lens."),
    TILTED("Straighten the phone."),
}

/** Mean luma (0..255) and Laplacian variance (sharpness) of a small ARGB image. */
fun frameStats(px: IntArray, w: Int, h: Int): Pair<Float, Float> {
    val g = FloatArray(px.size) { val p = px[it]; 0.299f * (p shr 16 and 255) + 0.587f * (p shr 8 and 255) + 0.114f * (p and 255) }
    val mean = g.average().toFloat()
    var sum = 0.0
    var sq = 0.0
    var n = 0
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
        val i = y * w + x
        val lap = 4 * g[i] - g[i - 1] - g[i + 1] - g[i - w] - g[i + w]
        sum += lap; sq += lap * lap; n++
    }
    val m = sum / n
    return mean to (sq / n - m * m).toFloat()
}

/** Camera pitch (+ = looking down) and roll in degrees from the gravity vector (device axes). */
fun tiltDegrees(gx: Float, gy: Float, gz: Float): Pair<Float, Float> =
    Math.toDegrees(atan2(gz, gy).toDouble()).toFloat() to Math.toDegrees(atan2(gx, gy).toDouble()).toFloat()

fun assess(luma: Float, sharpness: Float, pitch: Float, roll: Float): Health = when {
    luma < Settings.blockedLuma && sharpness < Settings.blurVar -> Health.BLOCKED // hand or pocket over the lens
    luma < Settings.darkLuma -> Health.DARK
    sharpness < Settings.blurVar -> Health.BLURRY
    pitch !in Settings.minPitchDeg..Settings.maxPitchDeg || abs(roll) > Settings.maxRollDeg -> Health.TILTED
    else -> Health.OK
}

/** Orientation-and-mobility style direction: 12 = straight ahead. */
fun clock(bearingRad: Float): String {
    val hour = (Math.toDegrees(bearingRad.toDouble()) / 30).roundToInt()
    return "${if (hour == 0) 12 else (12 + hour - 1) % 12 + 1} o'clock"
}

/**
 * Safety priority of a class, used only to break conflicts: which of two labels on the same object to trust,
 * and which of several objects to announce/show first. Higher = more dangerous to walk into.
 * Vehicles > people > animals > street furniture > everything else.
 */
fun priorityOf(label: String): Float = when (categoryOf(label)) {
    Category.VEHICLE -> 1.8f
    Category.PERSON -> 1.5f
    Category.ANIMAL -> 1.4f
    Category.SEAT, Category.FURNITURE -> 1.2f
    Category.OTHER -> if (label in setOf("fire hydrant", "stop sign", "parking meter", "potted plant", "suitcase")) 1.2f else 1f
}

/** Lower = pick first: the nearer thing is told first, whatever it is. Class priority only breaks a tie (same half metre). */
fun urgency(t: Track): Float = (if (t.metres.isNaN()) 99f else kotlin.math.round(t.metres * 2) / 2) - (priorityOf(t.label) - 1f) * 0.1f

/** Depth-model hazards for this frame (Depth.kt). Null = not present. */
data class Hazards(
    val dropAtM: Float? = null,
    val dropIsStep: Boolean = false, // a single step / kerb rather than a big drop
    val overheadAtM: Float? = null,
    val overheadBearing: Float = 0f,
    val floorObstacleAtM: Float? = null,
    val waistAtM: Float? = null, // table top, counter, railing: 0.45-1.2 m high, often with open space below
    val unsure: Boolean = false, // possible drop or path the depth can't judge: never "path clear" while this holds
)

private fun name(t: Track) = displayName(t.label)

fun inPath(t: Track, halfDeg: Float = Settings.pathHalfDeg) = Math.toDegrees(kotlin.math.abs(t.bearing).toDouble()) < halfDeg

/** "2.5 metres", "1 metre", "very close"; "" when unknown. */
fun metres(m: Float): String {
    if (m.isNaN()) return ""
    if (m < Settings.veryCloseM) return "very close"
    val r = kotlin.math.round(m * 2) / 2
    return when {
        r == 1f -> "1 metre"
        r % 1f == 0f -> "${r.toInt()} metres"
        else -> "$r metres"
    }
}

private fun phrase(vararg parts: String) = parts.filter { it.isNotEmpty() }.joinToString(", ") + "."

/**
 * Decides what to say each frame. Two classes of message:
 *  - emergency (camera unusable, drop-off, head height, something blocking the way under 0.75 m): always, and
 *    [Feedback] lets it cut off whatever is being said;
 *  - routine (crowd, approaching, close, moving, static): only when [canTalk], i.e. the previous sentence has
 *    finished, so sentences never overlap or cut each other off. Nearest thing first.
 * Three or more people = "Crowd ahead." once, never person by person. After something was announced, "Path clear."
 * is said once, only when nothing at all has been in the way for [Settings.pathClearMs].
 * Handles flicker (min hits) and nagging (habituation per track). Pure logic, unit-tested.
 */
class AlertPolicy {
    private var health = Health.OK
    private var healthSince = 0L
    private var healthSaid = Health.OK
    private var healthSaidMs = 0L
    private var lastInfoMs = -1_000_000L
    private var lastApproachMs = -1_000_000L
    private var lastCloseMs = -1_000_000L
    private var lastBlockedMs = -1_000_000L
    private var lastDropMs = -1_000_000L
    private var lastOverheadMs = -1_000_000L
    private var lastCrowdMs = -1_000_000L
    private var obstructedMs = -1_000_000L
    private var clearOwed = false // something was announced: say "Path clear" once it has gone
    private val spokenHeight = HashMap<Int, Float>()
    private val movingSaidMs = HashMap<Int, Long>()
    private val staticSaidMs = HashMap<String, Long>() // "chair@12" -> time: survives track ID changes

    /** True while the camera has been unusable long enough that detections are not trusted. */
    val blind get() = health != Health.OK

    /** Nothing has been in the way for [Settings.pathClearMs] (the screen shows PATH CLEAR only then). */
    var clear = false
        private set

    fun decide(
        tracks: List<Track>, raw: Health, now: Long, hz: Hazards = Hazards(), activity: Activity = Activity.WALKING,
        canTalk: Boolean = true,
    ): List<Alert> {
        // Camera health, with persistence so one dark frame doesn't cry wolf.
        if (raw != health) { health = raw; healthSince = now }
        if (health != Health.OK) { obstructedMs = now; clear = false } // can't see = can't say it's clear
        if (health != Health.OK && now - healthSince >= Settings.healthPersistMs) {
            if (healthSaid != health || now - healthSaidMs > Settings.healthRepeatMs) {
                healthSaid = health; healthSaidMs = now
                return listOf(Alert(health.message, Buzz.WARN, Tacton.CANT_SEE, health.message))
            }
            return emptyList() // detections from a bad image are not trusted
        }
        if (health == Health.OK) healthSaid = Health.OK

        // In a vehicle, motion fakes drop-offs and "approaching" objects: stay silent except camera health.
        if (activity == Activity.VEHICLE) return emptyList()
        val walking = activity == Activity.WALKING
        val sitting = activity == Activity.SITTING

        val stable = tracks.filter { it.hits >= Settings.minHits } // never trust one frame
        val crowd = stable.count { it.label == "person" && !it.metres.isNaN() && it.metres <= Settings.movingRangeM } >= Settings.crowdCount
        // With a crowd, people are told as one "Crowd ahead", never one by one.
        val things = if (crowd) stable.filter { it.label != "person" } else stable

        // Anything at all in the way, named or not: resets the path-clear timer.
        val obstructed = hz.dropAtM != null || hz.unsure || hz.overheadAtM != null || hz.waistAtM != null ||
            (hz.floorObstacleAtM ?: Float.MAX_VALUE) < Settings.closeM ||
            stable.any { it.approaching || (!it.metres.isNaN() && it.metres <= Settings.staticRangeM && inPath(it)) }
        if (obstructed) obstructedMs = now
        clear = now - obstructedMs >= Settings.pathClearMs

        val closeRange = if (walking) Settings.closeM else if (sitting) Settings.veryCloseM else Settings.veryCloseM + 0.25f
        val out = ArrayList<Alert>()

        // ---- Emergency: said at once, interrupting. ----
        // Drop-off: DropStateMachine already decided (and passes it only when CONFIRMED), so no walking gate here;
        // its vibration is played by DropHapticController, never tied to speech.
        hz.dropAtM?.let {
            if (now - lastDropMs >= Settings.hazardRepeatMs) { lastDropMs = now; out += if (hz.dropIsStep) Alert(phrase("Step down ahead", metres(it)), Buzz.WARN, null, "Step down.")
                    else Alert(phrase("Stop. Drop ahead", metres(it)), Buzz.WARN, null, "Stop. Drop.") }
        }
        hz.overheadAtM?.takeIf { walking || it < closeRange }?.let {
            if (now - lastOverheadMs >= Settings.hazardRepeatMs) {
                lastOverheadMs = now
                out += Alert(phrase("Head height obstacle", metres(it), clock(hz.overheadBearing)), Buzz.WARN, Tacton.HEAD, "Head.")
            }
        }
        // Blocked: something right in front (a named object, or a wall/pole the depth sees), about to be walked into.
        if (!sitting) {
            val blocking = stable.filter { !it.metres.isNaN() && it.metres < Settings.veryCloseM && inPath(it) }.minByOrNull { urgency(it) }
            val wall = hz.floorObstacleAtM?.takeIf { walking && it < Settings.veryCloseM }
            if ((blocking != null || wall != null) && now - lastBlockedMs >= Settings.closeRepeatMs) {
                lastBlockedMs = now; lastCloseMs = now
                val what = blocking?.takeIf { !(crowd && it.label == "person") }?.let { name(it) } ?: ""
                out += Alert(phrase("Blocked ahead", what), Buzz.WARN, null, "Blocked ahead.")
            }
        }
        if (!canTalk) return out.also { if (it.isNotEmpty()) clearOwed = true } // still speaking: routine waits its turn

        // ---- Routine: only once the last sentence has finished. ----
        if (crowd && !sitting && now - lastCrowdMs > Settings.crowdRepeatMs) {
            lastCrowdMs = now; lastInfoMs = now
            out += Alert("Crowd ahead.", Buzz.AHEAD, null, "Crowd ahead.") // instead of "person ..." x 6
        }

        // Approaching: my own walking already removed (ego-motion). Nearest first.
        // Far away, a few pixels of box jitter look like motion: only trust "approaching" within approachAnnounceM.
        val coming = things.filter { it.approaching && (it.metres.isNaN() || it.metres <= Settings.approachAnnounceM) }
            .minByOrNull { urgency(it) }
        if (coming != null && now - lastApproachMs >= Settings.approachCooldownMs) {
            lastApproachMs = now
            out += Alert(phrase("${name(coming)} approaching", metres(coming.metres), clock(coming.bearing)), Buzz.APPROACH, Tacton.APPROACH)
        }

        // Close by (0.75 m - 1.5 m): repeats while close. Only things in my path, and only when sure:
        // silence beats a wrong alert. Closer than that is "Blocked ahead" above.
        val close = things.filter {
            it !== coming && it.metres < closeRange && it.metres >= Settings.veryCloseM && inPath(it) && it.sure
        }.minByOrNull { urgency(it) }
        if (close != null && now - lastCloseMs >= Settings.closeRepeatMs) {
            lastCloseMs = now
            spokenHeight[close.id] = close.box.height()
            out += Alert(phrase("${name(close)} close", metres(close.metres), clock(close.bearing)), Buzz.AHEAD)
        } else if (close == null && coming == null && walking) {
            hz.floorObstacleAtM?.takeIf { it < Settings.closeM && now - lastCloseMs >= Settings.closeRepeatMs }?.let {
                lastCloseMs = now
                out += Alert(phrase("Obstacle ahead", metres(it)), Buzz.AHEAD) // something YOLO can't name (wall, pole)
            }
        }
        // Waist height (table top, counter): walking or standing, the cane sweeps under it.
        if (close == null && coming == null) hz.waistAtM?.takeIf { it < closeRange && now - lastCloseMs >= Settings.closeRepeatMs }?.let {
            lastCloseMs = now
            out += Alert(phrase("Obstacle at waist height", metres(it)), Buzz.AHEAD, null, "Waist height.")
        }
        if (out.isNotEmpty()) { clearOwed = true; return out.take(Settings.maxAlerts) }

        // Awareness, one message at a time, with a gap: moving things coming closer, then static things in the way.
        if (sitting || now - lastInfoMs < Settings.speechGapMs) return out
        pathClear(now)?.let { return listOf(it) }
        val known = things.filter { !it.metres.isNaN() }

        known.filter {
            // Only things getting CLOSER (own speed toward me, my walking removed), and near enough to matter.
            it.moving && it.objSpeed > Settings.movingMps && it.sure && !it.edge && it.metres <= Settings.movingAnnounceM &&
                now - (movingSaidMs[it.id] ?: -1_000_000L) >= Settings.movingRepeatMs
        }.minByOrNull { urgency(it) }?.let {
            movingSaidMs[it.id] = now; lastInfoMs = now; clearOwed = true
            val words = phrase("${name(it)} moving", metres(it.metres), clock(it.bearing))
            return listOf(Alert(words, Buzz.SIDE, short = words))
        }

        // Static things within range, walking or standing: each once, again only once it looms 50% bigger.
        val range = if (Settings.chatty) Float.MAX_VALUE else Settings.staticRangeM
        val t = known.filter {
            it.sure && !it.moving && !it.edge && it.metres <= range && inPath(it, Settings.staticPathDeg) &&
                spokenHeight[it.id].let { said -> said == null || it.box.height() >= said * Settings.habituationGrowth } &&
                now - (staticSaidMs["${it.label}@${clock(it.bearing)}"] ?: -1_000_000L) >= Settings.staticRepeatMs
        }.minByOrNull { urgency(it) } ?: return out
        spokenHeight[t.id] = t.box.height()
        staticSaidMs["${t.label}@${clock(t.bearing)}"] = now
        lastInfoMs = now; clearOwed = true
        val c = clock(t.bearing)
        val words = phrase(name(t).replaceFirstChar { it.uppercase() }, metres(t.metres), c)
        return listOf(Alert(words, if (c == "12 o'clock") Buzz.AHEAD else Buzz.SIDE, short = words))
    }

    /** "Path clear." once, after something was announced and nothing at all has been in the way since. */
    private fun pathClear(now: Long): Alert? {
        if (!clearOwed || !clear) return null
        clearOwed = false; lastInfoMs = now
        return Alert("Path clear.", Buzz.SIDE, null, "Path clear.")
    }
}
