package app.nadaka

/**
 * One-button, voice-guided calibration. Fixes the numbers distances and drop-offs rest on:
 *  1. HEIGHT - camera height above the floor (the floor ruler scales with it). Said by voice, or skipped.
 *  2. MOUNT  - the phone tilted so the floor ahead is in view; the voice coaches until it holds steady.
 *  3. WALK   - two walks, each bracketed by volume-down presses: press, 3 steps, press; press, 10 steps, press.
 *              Counting and the timer start at the first press of each walk, not when the voice finishes.
 *              The depth ruler learns from the floor while walking; the known step counts correct the
 *              step sensor (it misses or adds steps) so walking speed comes out right.
 * Saved (Prefs) so later launches start calibrated. Pure logic, fed on the analysis thread. Unit-tested.
 */
class Calibration(private val startMs: Long) {
    enum class Step { HEIGHT, MOUNT, WALK, DONE, FAILED }

    var step = Step.HEIGHT
        private set
    var heightM = Float.NaN   // body height, NaN = skipped
        private set
    /** What the screen shows under CALIBRATING. */
    var instruction = HEIGHT_PROMPT
        private set
    val finished get() = step == Step.DONE || step == Step.FAILED
    /** Only between the two presses of a walk: then the floor ruler may learn. */
    val walking get() = step == Step.WALK && counting
    /** A walk just began: the caller should start the floor ruler fresh (first walk only). */
    var rulerReset = false
        private set
    /** Actual steps / sensor steps over both walks; NaN until measured. */
    var stepFactor = Float.NaN
        private set
    /** Seconds per actual step while walking (cadence), NaN until measured. */
    var secondsPerStep = Float.NaN
        private set

    private var stepSinceMs = startMs
    private var steadySinceMs = -1L
    private var lastCoachMs = -1_000_000L

    // Walk segments: WALKS[i] actual steps, bracketed by presses.
    private var walk = 0
    private var counting = false
    private var segStartMs = 0L
    private var segStartCount = 0
    private var segEndMs = -1L       // second press; evaluated a moment later (the step sensor reports late)
    private val sensed = IntArray(WALKS.size)
    private val durMs = LongArray(WALKS.size)
    private var rulerLocked = false

    /** The spoken height answer. Returns what to say next. */
    fun heard(text: String, now: Long): String {
        if (step != Step.HEIGHT) return ""
        heightM = parseHeightM(text) ?: Float.NaN
        val ack = if (heightM.isNaN()) "OK, keeping the usual height." else "Got it, ${"%.0f".format(heightM * 100)} centimetres."
        return "$ack " + next(Step.MOUNT, now, MOUNT_PROMPT)
    }

    /** No answer came (mic timed out): carry on with the default height. */
    fun noAnswer(now: Long): String = if (step == Step.HEIGHT) "I didn't hear a height. " + next(Step.MOUNT, now, MOUNT_PROMPT) else ""

    /** Volume down. [stepCount] = the step sensor's running total. Returns what to say. */
    fun press(now: Long, stepCount: Int): String = when (step) {
        Step.HEIGHT -> "Skipping height. " + next(Step.MOUNT, now, MOUNT_PROMPT)
        Step.MOUNT -> next(Step.WALK, now, walkPrompt(0))
        Step.WALK -> when {
            segEndMs >= 0 -> "" // still counting the last steps of this walk
            !counting -> {
                counting = true; segStartMs = now; segStartCount = stepCount
                if (walk == 0) rulerReset = true
                instruction = "Walking ${WALKS[walk]} steps. Press volume down when you stop."
                "Go."
            }
            else -> { segEndMs = now; "Stop." }
        }
        else -> ""
    }

    fun consumeRulerReset(): Boolean = rulerReset.also { rulerReset = false }

    /**
     * One analysed frame. [pitchDeg] = how far the camera looks down, [stepCount] = step sensor total,
     * [rulerLocked] = the depth ruler has locked since the walk began. Returns words to say, or null.
     */
    fun frame(now: Long, pitchDeg: Float, stepCount: Int, rulerLocked: Boolean): String? {
        if (rulerLocked) this.rulerLocked = true
        return when (step) {
            Step.MOUNT -> mount(now, pitchDeg)
            Step.WALK -> walkFrame(now, stepCount)
            else -> null
        }
    }

    private fun mount(now: Long, pitch: Float): String? {
        val ok = pitch in Settings.calPitchMinDeg..Settings.calPitchMaxDeg
        if (ok) {
            if (steadySinceMs < 0) steadySinceMs = now
            if (now - steadySinceMs >= Settings.calSteadyMs) return next(Step.WALK, now, walkPrompt(0))
            return null
        }
        steadySinceMs = -1
        if (now - stepSinceMs > Settings.calStepTimeoutMs) return next(Step.WALK, now, "Carrying on. " + walkPrompt(0))
        if (now - lastCoachMs < Settings.calCoachMs) return null
        lastCoachMs = now
        return if (pitch < Settings.calPitchMinDeg) "Tilt the phone down a little." else "Tilt it up a little."
    }

    private fun walkFrame(now: Long, stepCount: Int): String? {
        if (!counting) { // waiting for the first press: no clock running, only a very long give-up
            return if (now - stepSinceMs > Settings.calIdleTimeoutMs) fail() else null
        }
        if (segEndMs < 0) { // walking: this walk's own clock, from its first press
            return if (now - segStartMs > Settings.calWalkTimeoutMs) fail() else null
        }
        if (now - segEndMs < Settings.calStepLatencyMs) return null
        sensed[walk] = stepCount - segStartCount
        durMs[walk] = segEndMs - segStartMs
        counting = false; segEndMs = -1
        walk++
        if (walk < WALKS.size) { stepSinceMs = now; instruction = walkPrompt(walk); return "Good. " + walkPrompt(walk) }
        return finish()
    }

    private fun finish(): String {
        if (!rulerLocked) return fail()
        val actual = WALKS.sum().toFloat()
        val sensedTotal = sensed.sum()
        if (sensedTotal >= actual / 3) stepFactor = (actual / sensedTotal).coerceIn(0.5f, 2f)
        secondsPerStep = durMs.sum() / 1000f / actual
        step = Step.DONE; instruction = DONE_PROMPT
        return DONE_PROMPT
    }

    private fun fail(): String { step = Step.FAILED; instruction = FAIL_PROMPT; counting = false; return FAIL_PROMPT }

    private fun next(s: Step, now: Long, prompt: String): String {
        step = s; stepSinceMs = now; steadySinceMs = -1; instruction = prompt
        return prompt
    }

    companion object {
        val WALKS = intArrayOf(3, 10)

        const val HEIGHT_PROMPT = "Calibration. First, after the buzz, say your height, like 170 centimetres or 5 foot 8. Or press volume down to skip."
        const val MOUNT_PROMPT = "Now hold the phone where you will carry it, camera facing forward, and stand still."
        const val DONE_PROMPT = "Calibration done. Distances are now tuned to you."
        const val FAIL_PROMPT = "I couldn't measure the floor. Try again on a flat floor in good light."

        fun walkPrompt(i: Int) = if (i == 0)
            "Now two short walks on a flat, clear floor. Press volume down, walk ${WALKS[0]} steps, then press volume down again."
        else "Press volume down, walk ${WALKS[i]} steps, then press volume down again."

        /** "170", "170 cm", "1.7 metres", "5 foot 8", "5'8", "5 feet" -> metres; null if none or implausible. */
        fun parseHeightM(text: String): Float? {
            val t = text.lowercase()
            if ("skip" in t) return null
            Regex("""(\d)\s*(?:foot|feet|ft|')\s*(\d{1,2})?""").find(t)?.let { m ->
                val inches = m.groupValues[1].toInt() * 12 + (m.groupValues[2].toIntOrNull() ?: 0)
                return (inches * 0.0254f).takeIf { it in 1.0f..2.3f }
            }
            val n = Regex("""\d+(?:\.\d+)?""").find(t)?.value?.toFloatOrNull() ?: return null
            return when { n in 100f..230f -> n / 100f; n in 1.0f..2.3f -> n; else -> null }
        }

        /** Chest-carried camera sits at roughly 72% of body height. */
        fun cameraHeightFor(bodyM: Float) = bodyM * 0.72f

        /** Typical stride per step from body height (walking): ~0.415 x height. */
        fun strideFor(bodyM: Float) = bodyM * 0.415f
    }
}
