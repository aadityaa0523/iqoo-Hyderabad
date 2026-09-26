package app.nadaka

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** The haptic vocabulary (docs/haptics.md). Meaning is carried by rhythm; intensity only adds urgency. */
enum class Tacton { DROP, HEAD, APPROACH, CANT_SEE, TICK }

/** Parking-sensor mapping: pulse interval for an obstacle at [m] metres in my path, or null = no pulse. */
fun pulseIntervalMs(m: Float): Long? = when {
    m.isNaN() || m > Settings.pulseMaxM -> null
    m < Settings.veryCloseM -> 150L // about to touch: near-continuous
    else -> {
        // 1000 ms at pulseMaxM down to 300 ms at 1 m (linear in distance, clamped).
        val f = ((m - 1f) / (Settings.pulseMaxM - 1f)).coerceIn(0f, 1f)
        (300 + f * 700).toLong()
    }
}

/** Three levels only (van Erp 2002), all strong enough to feel through clothes on the chest. */
fun pulseAmplitude(m: Float): Float = when {
    m < Settings.veryCloseM -> 1f
    m < Settings.closeM -> 0.85f
    else -> 0.65f
}

/**
 * Decides whether to tick for the nearest in-path obstacle. Ticks only while the gap is shrinking:
 * standing at a desk with a wall 2 m away must not buzz forever (habituation). Touching range always
 * ticks. Pure logic, unit-tested.
 */
class ProximityPulse {
    private var lastTickMs = -1_000_000L
    private var bestM = Float.MAX_VALUE // closest distance seen in this approach
    private var closerMs = -1_000_000L  // last time the gap shrank meaningfully

    /** Returns the tick amplitude to play now, or null. */
    fun update(now: Long, m: Float): Float? {
        if (m.isNaN() || m > Settings.pulseMaxM) { bestM = Float.MAX_VALUE; return null } // nothing: reset
        if (m < bestM - Settings.pulseProgressM) { bestM = m; closerMs = now }
        if (m > bestM + Settings.pulseProgressM * 2) { bestM = m; closerMs = now } // a new object / moved away
        val stale = now - closerMs > Settings.pulseStaleMs
        if (stale && m >= Settings.veryCloseM) return null
        val interval = pulseIntervalMs(m) ?: return null
        if (now - lastTickMs < interval) return null
        lastTickMs = now
        return pulseAmplitude(m)
    }
}

class Haptics(ctx: Context) {
    private val v: Vibrator = ctx.getSystemService(VibratorManager::class.java).defaultVibrator
    private val pulse = ProximityPulse()
    private var quietUntilMs = 0L

    fun play(t: Tacton) {
        quietUntilMs = SystemClock.elapsedRealtime() + 1200 // don't blur a pattern with proximity ticks
        v.vibrate(effect(t))
    }

    /** Call every frame with the nearest in-path distance (NaN = nothing). */
    fun proximity(m: Float) {
        val now = SystemClock.elapsedRealtime()
        val a = pulse.update(now, m) ?: return
        if (now < quietUntilMs) return
        v.vibrate(VibrationEffect.createOneShot(Settings.tickMs, amp(a)))
    }

    private fun amp(a: Float) = (a * Settings.hapticGain * 255).toInt().coerceIn(1, 255)

    /**
     * Long enough to feel on the chest (the motor's 20 ms primitives are too faint there), and built
     * from clearly different rhythms: heavy-slow, rising, accelerating, soft.
     */
    private fun effect(t: Tacton): VibrationEffect = when (t) {
        // STOP: three long, heavy, evenly spaced pulses. The one pattern that must never be missed.
        Tacton.DROP -> wave(0 to 0, 350 to 255, 200 to 0, 350 to 255, 200 to 0, 350 to 255)
        // HEAD: two swells that ramp up ("rising" = up high).
        Tacton.HEAD -> wave(0 to 0, 80 to 70, 80 to 150, 120 to 255, 250 to 0, 80 to 70, 80 to 150, 120 to 255)
        // APPROACH: four short taps getting faster ("coming at you").
        Tacton.APPROACH -> wave(0 to 0, 60 to 255, 220 to 0, 60 to 255, 130 to 0, 60 to 255, 60 to 0, 60 to 255)
        // CAN'T SEE: two gentle medium pulses, calm.
        Tacton.CANT_SEE -> wave(0 to 0, 180 to 120, 300 to 0, 180 to 120)
        Tacton.TICK -> VibrationEffect.createOneShot(Settings.tickMs, amp(0.85f))
    }

    private fun wave(vararg steps: Pair<Int, Int>) = VibrationEffect.createWaveform(
        LongArray(steps.size) { steps[it].first.toLong() },
        IntArray(steps.size) { i -> if (steps[i].second == 0) 0 else (steps[i].second * Settings.hapticGain).toInt().coerceIn(1, 255) },
        -1,
    )
}

/** "Teach me the vibrations": each pattern with its meaning, as (spoken, tacton) steps. */
val LESSON = listOf(
    "Ticks mean something is in your path. Faster ticks, closer." to Tacton.TICK,
    "Three heavy pulses: stop, drop-off." to Tacton.DROP,
    "Two rising swells: something at head height." to Tacton.HEAD,
    "Four quick taps, getting faster: something is coming at you." to Tacton.APPROACH,
    "Two soft pulses: I can't see, use your cane." to Tacton.CANT_SEE,
)
