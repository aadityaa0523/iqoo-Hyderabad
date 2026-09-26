package app.nadaka

import kotlin.math.acos
import kotlin.math.sqrt

/** Spoken by Settings > How fall alerts work, and on the welcome screen. */
const val FALL_GUIDE = "About fall alerts. If you fall, I say: fall detected. If you are OK, press either volume key " +
    "within 7 seconds and nothing happens. If you don't press, I sound a loud siren and call your emergency contacts. " +
    "Press a volume key to stop the siren. Dropping the phone can look like a fall, so just press a volume key then."

/**
 * Fall = free fall of at least [Settings.fallMinDropM] (height integrated from the weightless phase), then a hard
 * impact, then the phone lies still in a very different orientation.
 * Fed raw accelerometer samples (m/s^2, ~50 Hz). Each stage has a time window, so walking, sitting down
 * hard or a jump don't qualify. A dropped phone can look the same; the 7 s "press if you're OK" window
 * in MainActivity is the answer to that. Pure logic, unit-tested.
 */
class FallDetector {
    private enum class Stage { IDLE, FREE_FALL, IMPACT }

    private var stage = Stage.IDLE
    private var stageMs = 0L
    private var lowSinceMs = -1L
    private val before = FloatArray(3)       // orientation (unit gravity) before the fall
    private var uprightX = 0f; private var uprightY = 9.8f; private var uprightZ = 0f
    private var stillSum = 0f; private var stillN = 0
    private var lastMs = 0L
    private var speed = 0f   // m/s downward, integrated while weightless
    /** How far the phone fell before the impact (m), for the log. */
    var dropM = 0f
        private set
    private val after = FloatArray(3)

    /** One sample. Returns true exactly once per detected fall. */
    fun update(now: Long, x: Float, y: Float, z: Float): Boolean {
        val g = sqrt(x * x + y * y + z * z) / G
        when (stage) {
            Stage.IDLE -> {
                // Slowly track the resting orientation, so "before" is how the phone was carried.
                if (g in 0.9f..1.1f) { uprightX = 0.95f * uprightX + 0.05f * x; uprightY = 0.95f * uprightY + 0.05f * y; uprightZ = 0.95f * uprightZ + 0.05f * z }
                if (g < Settings.fallFreeG) {
                    if (lowSinceMs < 0) lowSinceMs = now
                    if (now - lowSinceMs >= Settings.fallFreeMs) {
                        stage = Stage.FREE_FALL; stageMs = lowSinceMs; lastMs = lowSinceMs
                        // Count the weightless time already seen: in free fall, distance = g t^2 / 2.
                        val t = (now - lowSinceMs) / 1000f
                        speed = (1f - g) * G * t; dropM = speed * t / 2
                        before[0] = uprightX; before[1] = uprightY; before[2] = uprightZ
                    }
                } else lowSinceMs = -1
            }
            Stage.FREE_FALL -> {
                // Height fallen = integral of the missing gravity: a 50 cm fall is ~0.32 s of free fall.
                val dt = (now - lastMs) / 1000f; lastMs = now
                if (g < 1f) { speed += (1f - g) * G * dt; dropM += speed * dt }
                when {
                    g > Settings.fallImpactG ->
                        if (dropM >= Settings.fallMinDropM) { stage = Stage.IMPACT; stageMs = now; stillSum = 0f; stillN = 0; after.fill(0f) }
                        else reset() // a jolt or a short drop: not a fall
                    now - stageMs > Settings.fallImpactWindowMs -> reset()
                }
            }
            Stage.IMPACT -> {
                val settle = now - stageMs
                if (settle < Settings.fallSettleMs) return false // bouncing / rolling right after the hit
                stillSum += kotlin.math.abs(g - 1f); stillN++
                after[0] += x; after[1] += y; after[2] += z
                if (settle < Settings.fallSettleMs + Settings.fallStillMs) return false
                val still = stillSum / stillN < Settings.fallStillG
                val turned = angleDeg(before, after) >= Settings.fallTurnDeg
                reset()
                return still && turned
            }
        }
        return false
    }

    private fun reset() { stage = Stage.IDLE; lowSinceMs = -1; speed = 0f }

    private fun angleDeg(a: FloatArray, b: FloatArray): Float {
        val na = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]); val nb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        if (na < 1e-3f || nb < 1e-3f) return 0f
        val c = ((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(c).toDouble()).toFloat()
    }

    private companion object { const val G = 9.81f }
}
