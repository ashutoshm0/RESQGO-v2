package com.resqgo.app

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

// ---------- message text (plain functions, unit-tested) ----------

/** Locale.US so the link never gets a comma decimal or non-Latin digits. No API key needed. */
fun mapsLink(lat: Double, lng: Double) = "https://maps.google.com/?q=%.5f,%.5f".format(Locale.US, lat, lng)

fun alertText(name: String, manual: Boolean, time: String, link: String?, battery: Int?) = buildString {
    append("RESQGO ALERT: $name ${if (manual) "pressed SOS" else "may have crashed"} at $time.\n")
    append("Location: ${link ?: "not available"}\n")
    if (battery != null) append("Battery $battery%.\n")
    append("If you can't reach $name, call 112 (ambulance 108).")
}

/**
 * Sends SOS messages through the phone's own SIM: no internet, no Twilio, no API key.
 * Every result is reported in RideState.sends, so nothing fails silently.
 */
object SosSender {

    suspend fun sendAlert(ctx: Context, manual: Boolean): Int? = sendToAll(ctx, shareText(ctx, manual), "SMS")

    suspend fun sendUpdate(ctx: Context, sim: Int?) {
        val link = location(ctx)?.let { mapsLink(it.latitude, it.longitude) } ?: return
        sendToAll(ctx, "RESQGO update: ${Prefs(ctx).displayName}'s location at ${now()}:\n$link", "Update", sim)
    }

    suspend fun sendImSafe(ctx: Context) {
        sendToAll(ctx, "RESQGO: ${Prefs(ctx).displayName} is safe. Please ignore the earlier alert.", "I'm safe")
    }

    suspend fun sendTest(ctx: Context) {
        val name = Prefs(ctx).displayName
        sendToAll(ctx, "RESQGO test: $name added you as an emergency contact. If RESQGO detects a crash, you'll get their location here. No action needed.", "Test")
    }

    /** The alert text, also used by the Share on WhatsApp button. */
    fun shareText(ctx: Context, manual: Boolean = true): String = alertText(
        Prefs(ctx).displayName, manual, now(),
        location(ctx)?.let { mapsLink(it.latitude, it.longitude) }, battery(ctx)
    )

    /** Calls the primary contact on the SIM that just worked, so a dual-SIM phone never shows a SIM chooser. */
    fun callPrimary(ctx: Context, sim: Int?) {
        val c = Prefs(ctx).contacts.firstOrNull() ?: return
        val line = try {
            val extras = Bundle()
            phoneAccount(ctx, sim)?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
            ctx.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", c.phone, null), extras)
            SendLine("Call · ${c.name}", "Call placed", true)
        } catch (e: SecurityException) {
            SendLine("Call · ${c.name}", "Not placed. Allow phone calls for RESQGO.", false)
        }
        RideState.sends.value = RideState.sends.value + line
    }

    /** Sends [text] to every contact, trying each SIM in turn. Returns the SIM that worked. */
    private suspend fun sendToAll(ctx: Context, text: String, kind: String, preferred: Int? = null): Int? {
        val contacts = Prefs(ctx).contacts
        val sims = simOrder(ctx, preferred)
        val lines = contacts.map { SendLine("$kind · ${it.name}", "Waiting…", null) }.toMutableList()
        fun show(i: Int, status: String, ok: Boolean?) {
            lines[i] = lines[i].copy(status = status, ok = ok)
            RideState.sends.value = lines.toList()
        }
        RideState.sends.value = lines.toList()

        var worked: Int? = null
        // ponytail: contacts go one after another; worst case is 30 s per SIM per contact.
        // Send in parallel if pilots show that delay matters.
        contacts.forEachIndexed { i, c ->
            for (sim in sims) {
                show(i, "Sending via ${sim.label}…", null)
                if (sendSms(ctx, sim.manager(ctx), c.phone, text)) {
                    show(i, "Sent via ${sim.label}", true)
                    if (worked == null) worked = sim.subId
                    return@forEachIndexed
                }
                show(i, "${sim.label} failed", null)
            }
            show(i, "Not sent. Check signal and SMS permission.", false)
        }
        return worked
    }

    private class Sim(val subId: Int?, val label: String) {
        @Suppress("DEPRECATION") // the replacements need Android 12
        fun manager(ctx: Context): SmsManager = when {
            subId == null && Build.VERSION.SDK_INT >= 31 -> ctx.getSystemService(SmsManager::class.java)
            subId == null -> SmsManager.getDefault()
            Build.VERSION.SDK_INT >= 31 -> ctx.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
            else -> SmsManager.getSmsManagerForSubscriptionId(subId)
        }
    }

    /** Every active SIM, the preferred (or default SMS) one first. */
    private fun simOrder(ctx: Context, preferred: Int?): List<Sim> {
        val subs = try {
            ctx.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
        } catch (e: SecurityException) {
            emptyList()
        }
        if (subs.isEmpty()) return listOf(Sim(null, "default SIM"))
        val first = preferred ?: SubscriptionManager.getDefaultSmsSubscriptionId()
        return subs.sortedBy { if (it.subscriptionId == first) -1 else it.simSlotIndex }
            .map { Sim(it.subscriptionId, "SIM ${it.simSlotIndex + 1}") }
    }

    /** True only when Android reports every part of the SMS as sent. */
    private suspend fun sendSms(ctx: Context, sms: SmsManager, number: String, text: String): Boolean =
        withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine { cont ->
                val parts = sms.divideMessage(text)
                val action = "${ctx.packageName}.SMS_SENT.${System.nanoTime()}"
                var left = parts.size
                var allOk = true
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        if (resultCode != Activity.RESULT_OK) allOk = false
                        if (--left == 0) {
                            runCatching { ctx.unregisterReceiver(this) }
                            if (cont.isActive) cont.resume(allOk)
                        }
                    }
                }
                val filter = IntentFilter(action)
                if (Build.VERSION.SDK_INT >= 33) {
                    ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    ctx.registerReceiver(receiver, filter)
                }
                cont.invokeOnCancellation { runCatching { ctx.unregisterReceiver(receiver) } }
                val sentIntents = ArrayList(parts.indices.map {
                    PendingIntent.getBroadcast(ctx, it, Intent(action).setPackage(ctx.packageName), PendingIntent.FLAG_IMMUTABLE)
                })
                try {
                    sms.sendMultipartTextMessage(number, null, parts, sentIntents, null)
                } catch (e: Exception) { // no permission, empty SIM slot, bad number
                    runCatching { ctx.unregisterReceiver(receiver) }
                    if (cont.isActive) cont.resume(false)
                }
            }
        } ?: false

    private fun phoneAccount(ctx: Context, sim: Int?): PhoneAccountHandle? = try {
        if (sim != null && Build.VERSION.SDK_INT >= 30) {
            ctx.getSystemService(TelephonyManager::class.java).createForSubscriptionId(sim).phoneAccountHandle
        } else {
            ctx.getSystemService(TelecomManager::class.java).callCapablePhoneAccounts.firstOrNull()
        }
    } catch (e: SecurityException) {
        null
    }

    private fun location(ctx: Context): Location? {
        RideState.lastLocation?.let { if (System.currentTimeMillis() - it.time < 2 * 60_000) return it }
        val lm = ctx.getSystemService(LocationManager::class.java)
        val known = try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { lm.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }
        return known ?: RideState.lastLocation
    }

    private fun battery(ctx: Context): Int? = ctx.getSystemService(BatteryManager::class.java)
        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }

    private fun now(): String = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date())
}
