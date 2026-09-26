package app.nadaka.drop

import app.nadaka.drop.DropOffConfig as C

/**
 * Combines independent evidence for one frame into an EvidenceClass + confidence (no decision).
 *  - edge alone -> NONE (a painted line is an edge too)
 *  - reliable depth saying the floor continues -> NONE (shadow, painted line, rug, tile boundary)
 *  - UNRELIABLE depth adds nothing
 *  - object overlap multiplies confidence down, never to zero
 *  - barometer is not used per frame (only to upgrade POSSIBLE -> CONFIRMED in the state machine)
 */
object DropEvidenceFusion {
    fun fuse(t: Long, edge: EdgeCandidate?, depth: DepthResult, ground: GroundResult, objectScore: Float, baro: BarometerAnalyzer): DropEvidence {
        if (edge == null || edge.score < C.EDGE_MIN_SCORE) return DropEvidence.none(t, baro.descentConfidence, baro.descendingConfirmed)
        val strongEdge = edge.score >= C.EDGE_STRONG_SCORE
        val depthSupport = depth.verdict == DepthVerdict.SUPPORTS && depth.confidence >= C.DEPTH_MIN_CONFIDENCE
        val depthContradicts = depth.verdict == DepthVerdict.CONTRADICTS
        val groundBreak = ground.reliable && ground.breakAway && ground.score >= C.GROUND_PLANE_THRESHOLD
        val geometry = (if (depthSupport) 1 else 0) + (if (groundBreak) 1 else 0)

        var confidence = 0.35f * edge.score +
            (if (depthSupport) 0.4f * depth.confidence else 0f) +
            (if (groundBreak) 0.25f * ground.score else 0f)
        confidence *= 1f - C.OBJECT_PENALTY * clamp01(objectScore)

        val cls = when {
            depthContradicts && !groundBreak -> EvidenceClass.NONE
            geometry == 0 -> EvidenceClass.NONE
            strongEdge && depthSupport && (groundBreak || depth.confidence >= C.STRONG_DEPTH_CONFIDENCE) &&
                confidence >= C.STRONG_MIN_CONFIDENCE -> EvidenceClass.STRONG
            confidence >= C.PRESENT_MIN_CONFIDENCE -> EvidenceClass.PRESENT
            else -> EvidenceClass.WEAK_PRESENT
        }
        return DropEvidence(
            t, edge.score, depth.verdict, depth.confidence, ground.score, objectScore, baro.descentConfidence,
            (edge.x0 + edge.x1) / 2, edge.y, true, groundBreak, objectScore >= C.OBJECT_SUPPRESSION_THRESHOLD,
            baro.descendingConfirmed, cls, if (cls == EvidenceClass.NONE) 0f else confidence,
        )
    }
}

/** Last 5 evaluated frames, timestamped (frames don't arrive at a fixed rate; stale ones stop counting). */
class DropEvidenceHistory {
    private val items = ArrayDeque<DropEvidence>()

    fun add(e: DropEvidence) {
        items.addLast(e)
        while (items.size > C.CONFIRMED_WINDOW_FRAMES) items.removeFirst()
    }

    fun clear() = items.clear()

    private fun recent(n: Int, now: Long) = items.toList().takeLast(n).filter { now - it.timestamp <= C.HISTORY_MAX_AGE_MS }

    /** Frames with meaningful evidence (WEAK_PRESENT or better) among the last 3. */
    fun possibleCount(now: Long) = recent(C.POSSIBLE_WINDOW_FRAMES, now).count { it.evidenceClass != EvidenceClass.NONE }

    /** STRONG frames among the last 5. */
    fun strongCount(now: Long) = recent(C.CONFIRMED_WINDOW_FRAMES, now).count { it.evidenceClass == EvidenceClass.STRONG }

    fun latest() = items.lastOrNull()
    fun classes() = items.map { it.evidenceClass }
}

data class DropTransition(val from: DropState, val to: DropState, val atMs: Long)

/**
 * SAFE -> POSSIBLE (2 of last 3 meaningful) -> CONFIRMED (3 of last 5 STRONG, or POSSIBLE + barometric descent);
 * back to SAFE only after SAFE_RECOVERY_FRAMES consecutive clean frames. Sensor blocked and path-not-traversable
 * take priority and are never reported as a drop. A single clean frame never clears a state.
 */
class DropStateMachine {
    var state = DropState.SAFE
        private set
    var recoveryCount = 0
        private set

    fun update(now: Long, h: DropEvidenceHistory, sensorBlocked: Boolean, pathBlocked: Boolean, descending: Boolean): DropTransition? {
        val next = when {
            sensorBlocked -> DropState.SENSOR_BLOCKED
            pathBlocked -> DropState.PATH_NOT_TRAVERSABLE
            else -> nextDropState(now, h, descending)
        }
        if (next == DropState.SENSOR_BLOCKED || next == DropState.PATH_NOT_TRAVERSABLE) h.clear() // evidence from an unusable view
        return if (next != state) DropTransition(state, next, now).also { state = next; recoveryCount = 0 } else null
    }

    private fun nextDropState(now: Long, h: DropEvidenceHistory, descending: Boolean): DropState {
        val possible = h.possibleCount(now) >= C.POSSIBLE_REQUIRED_FRAMES
        val confirmed = h.strongCount(now) >= C.CONFIRMED_REQUIRED_FRAMES
        val clean = h.latest()?.evidenceClass == EvidenceClass.NONE
        recoveryCount = if (clean) recoveryCount + 1 else 0
        return when (state) {
            DropState.SAFE, DropState.SENSOR_BLOCKED, DropState.PATH_NOT_TRAVERSABLE -> when {
                confirmed -> DropState.CONFIRMED_DROP
                possible -> DropState.POSSIBLE_DROP
                else -> DropState.SAFE
            }
            DropState.POSSIBLE_DROP -> when {
                confirmed -> DropState.CONFIRMED_DROP
                possible && descending -> DropState.CONFIRMED_DROP // barometer only upgrades existing visual evidence
                recoveryCount >= C.SAFE_RECOVERY_FRAMES -> DropState.SAFE
                else -> DropState.POSSIBLE_DROP
            }
            DropState.CONFIRMED_DROP ->
                if (recoveryCount >= C.SAFE_RECOVERY_FRAMES && h.possibleCount(now) == 0) DropState.SAFE else DropState.CONFIRMED_DROP
        }
    }
}

enum class DropHaptic { NONE, POSSIBLE_PULSE, CONFIRMED_ESCALATING, CONFIRMED_MAX }

/** Possible: one subdued pulse at most every 1.5 s. Confirmed: once, on the rising edge; maximum if descending. */
class DropHapticController {
    private var lastPossibleMs = -1_000_000L
    private var prev = DropState.SAFE

    fun update(now: Long, state: DropState, descending: Boolean): DropHaptic {
        val rising = state == DropState.CONFIRMED_DROP && prev != DropState.CONFIRMED_DROP
        prev = state
        return when {
            rising -> if (descending) DropHaptic.CONFIRMED_MAX else DropHaptic.CONFIRMED_ESCALATING
            state == DropState.POSSIBLE_DROP && now - lastPossibleMs >= C.POSSIBLE_HAPTIC_INTERVAL_MS -> {
                lastPossibleMs = now; DropHaptic.POSSIBLE_PULSE
            }
            else -> DropHaptic.NONE
        }
    }
}
