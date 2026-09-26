package app.nadaka

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition
import android.os.Vibrator
import android.os.VibratorManager

/** The haptic vocabulary (docs/haptics.md). Meaning is carried by rhythm; intensity only adds urgency. */
enum class Tacton { DROP, HEAD, APPROACH, CANT_SEE, TICK }

/** Parking-sensor mapping: pulse interval for an obstacle at [m] metres in my path, or null = no pulse. */
fun pulseIntervalMs(m: Float): Long? = when {
    m.isNaN() || m > Settings.pulseMaxM -> null
    m < Settings.veryCloseM -> 120L // effectively continuous: about to touch
    else -> {
        // 1000 ms at pulseMaxM down to 250 ms at 1 m (linear in distance, clamped).
        val f = ((m - 1f) / (Settings.pulseMaxM - 1f)).coerceIn(0f, 1f)
        (250 + f * 750).toLong()
    }
}

/** Stronger when closer: 3 perceptible levels are all people reliably distinguish (van Erp 2002). */
fun pulseAmplitude(m: Float): Float = when {
    m < Settings.veryCloseM -> 1f
    m < Settings.closeM -> 0.7f
    else -> 0.4f
}

class Haptics(ctx: Context) {
    private val v: Vibrator = ctx.getSystemService(VibratorManager::class.java).defaultVibrator
    private val rich = v.areAllPrimitivesSupported(
        Composition.PRIMITIVE_CLICK, Composition.PRIMITIVE_THUD, Composition.PRIMITIVE_TICK, Composition.PRIMITIVE_QUICK_RISE,
    )
    private var lastPulseMs = 0L
    private var quietUntilMs = 0L

    fun play(t: Tacton) {
        quietUntilMs = SystemClock.elapsedRealtime() + 900 // don't blur a pattern with proximity ticks
        v.vibrate(if (rich) composed(t) else waveform(t))
    }

    /** Call every frame with the nearest in-path distance (NaN = nothing). Ticks faster as it gets closer. */
    fun proximity(m: Float) {
        val now = SystemClock.elapsedRealtime()
        val interval = pulseIntervalMs(m) ?: return
        if (now < quietUntilMs || now - lastPulseMs < interval) return
        lastPulseMs = now
        val a = pulseAmplitude(m)
        v.vibrate(
            if (rich) VibrationEffect.startComposition().addPrimitive(Composition.PRIMITIVE_TICK, a).compose()
            else VibrationEffect.createOneShot(30, (a * 255).toInt())
        )
    }

    private fun composed(t: Tacton): VibrationEffect = VibrationEffect.startComposition().apply {
        when (t) {
            Tacton.DROP -> repeat(3) { addPrimitive(Composition.PRIMITIVE_THUD, 1f, if (it == 0) 0 else 350) }
            Tacton.HEAD -> repeat(2) { addPrimitive(Composition.PRIMITIVE_QUICK_RISE, 1f, if (it == 0) 0 else 200) }
            Tacton.APPROACH -> { addPrimitive(Composition.PRIMITIVE_CLICK, 1f); addPrimitive(Composition.PRIMITIVE_CLICK, 1f, 120); addPrimitive(Composition.PRIMITIVE_CLICK, 1f, 80); addPrimitive(Composition.PRIMITIVE_CLICK, 1f, 40) }
            Tacton.CANT_SEE -> repeat(2) { addPrimitive(Composition.PRIMITIVE_THUD, 0.4f, if (it == 0) 0 else 300) }
            Tacton.TICK -> addPrimitive(Composition.PRIMITIVE_TICK, 0.7f)
        }
    }.compose()

    /** Fallback for motors without primitives: same rhythms as timing + amplitude waveforms. */
    private fun waveform(t: Tacton): VibrationEffect = when (t) {
        Tacton.DROP -> VibrationEffect.createWaveform(longArrayOf(0, 250, 350, 250, 350, 250), intArrayOf(0, 255, 0, 255, 0, 255), -1)
        Tacton.HEAD -> VibrationEffect.createWaveform(longArrayOf(0, 60, 60, 120, 200, 60, 60, 120), intArrayOf(0, 80, 160, 255, 0, 80, 160, 255), -1)
        Tacton.APPROACH -> VibrationEffect.createWaveform(longArrayOf(0, 30, 120, 30, 80, 30, 40, 30), intArrayOf(0, 255, 0, 255, 0, 255, 0, 255), -1)
        Tacton.CANT_SEE -> VibrationEffect.createWaveform(longArrayOf(0, 150, 300, 150), intArrayOf(0, 90, 0, 90), -1)
        Tacton.TICK -> VibrationEffect.createOneShot(30, 180)
    }
}

/** "Teach me the vibrations": each pattern with its meaning, as (spoken, tacton) steps. */
val LESSON = listOf(
    "Ticks mean something is in your path. Faster ticks, closer." to Tacton.TICK,
    "Three heavy pulses: stop, drop-off." to Tacton.DROP,
    "Two rising swells: something at head height." to Tacton.HEAD,
    "Quick triple tap: something is coming at you." to Tacton.APPROACH,
    "Two soft thuds: I can't see, use your cane." to Tacton.CANT_SEE,
)
