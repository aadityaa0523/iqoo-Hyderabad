package app.nadaka

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Open Nadaka from anywhere without finding the icon: press volume UP three times quickly.
 * (Android reserves the power button; iQOO uses quick volume-down presses for its camera shortcut, which the
 * system handles before any app sees the key, so volume down can't be used.) An accessibility service is the supported way to see
 * key presses outside the app. It only watches for that triple press: every press still changes the volume
 * as normal, and nothing is read from the screen. The user turns it on in Android Settings > Accessibility.
 */
class QuickLaunchService : AccessibilityService() {
    private val presses = LongArray(3) { -1_000_000L }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP || event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return false
        if (MainActivity.visible) return false // inside Nadaka, volume up keeps its own meaning (ask a question)
        val now = SystemClock.elapsedRealtime()
        android.util.Log.i(TAG, "quick launch: volume up")
        if (TriplePress.add(presses, now)) {
            android.util.Log.i(TAG, "quick launch: opening Nadaka")
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        return false // never swallow the key: the volume still changes
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}

/** Three presses within [Settings.quickLaunchMs]. Pure logic, unit-tested. */
object TriplePress {
    /** Records a press at [now] in [times] (the last 3). True when the last three fall inside the window. */
    fun add(times: LongArray, now: Long): Boolean {
        times[0] = times[1]; times[1] = times[2]; times[2] = now
        if (times[2] - times[0] > Settings.quickLaunchMs) return false
        times.fill(-1_000_000L) // consumed: a fourth press doesn't re-trigger
        return true
    }
}
