package app.nadaka

import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.SEND_SMS
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.location.Location
import android.location.LocationManager
import android.telephony.SmsManager
import android.util.Log

/** An emergency contact picked from the phone's contacts. */
data class Contact(val name: String, val number: String)

/**
 * Texts the emergency contacts when the siren starts (fall or both-keys emergency): a plain message with a map
 * link to where the phone is. GPS works without internet. Sends at once with the last known position, then
 * again when a fresh fix arrives (the first can be minutes old or missing indoors).
 */
class EmergencySms(private val ctx: Context) {
    val canSend get() = ctx.checkSelfPermission(SEND_SMS) == PERMISSION_GRANTED
    private val canLocate get() = ctx.checkSelfPermission(ACCESS_FINE_LOCATION) == PERMISSION_GRANTED

    /** Returns what to say: who is being texted, or why nobody can be. */
    fun alert(reason: String, test: Boolean = false): String {
        val to = Prefs.contacts
        if (to.isEmpty()) return "No emergency contacts are set. Add them in Settings."
        if (!canSend) return "I can't send messages: the SMS permission is off."
        val head = if (test) "Nadaka test message: this is how an emergency alert will look." else "NADAKA ALERT: $reason Please call or come now."
        send(to, message(head, lastKnown()))
        if (canLocate && !test) freshFix { send(to, message("NADAKA ALERT update: current location.", it)) }
        val names = to.joinToString(" and ") { it.name }
        return if (test) "Test message sent to $names." else "Sending your location to $names."
    }

    private fun message(head: String, at: Location?) = head + " " + (at?.let {
        val age = (System.currentTimeMillis() - it.time) / 60_000
        "Location: https://maps.google.com/?q=%.6f,%.6f (within %.0f m%s).".format(it.latitude, it.longitude, it.accuracy,
            if (age >= 2) ", $age min ago" else "")
    } ?: "Location not available yet.")

    private fun send(to: List<Contact>, text: String) {
        val sms = ctx.getSystemService(SmsManager::class.java)
        for (c in to) runCatching {
            sms.sendMultipartTextMessage(c.number, null, sms.divideMessage(text), null, null)
            Log.i(TAG, "sms: sent to ${c.name}")
        }.onFailure { Log.e(TAG, "sms: failed to ${c.name}", it) }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(): Location? {
        if (!canLocate) return null
        val lm = ctx.getSystemService(LocationManager::class.java)
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    @SuppressLint("MissingPermission")
    private fun freshFix(onFix: (Location) -> Unit) {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val provider = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        runCatching {
            lm.getCurrentLocation(provider, null, ctx.mainExecutor) { it?.let(onFix) }
        }.onFailure { Log.w(TAG, "sms: no location fix", it) }
    }
}
