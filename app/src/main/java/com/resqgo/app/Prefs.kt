package com.resqgo.app

import android.content.Context

data class Contact(val name: String, val phone: String)

/** Everything the rider sets up. Stored on the phone only. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("resqgo", Context.MODE_PRIVATE)

    var riderName: String
        get() = sp.getString("name", "").orEmpty()
        set(v) = sp.edit().putString("name", v.trim()).apply()

    val displayName: String get() = riderName.ifBlank { "The rider" }

    /** Seconds the "Are you OK?" alarm waits before sending SOS. */
    var timerSec: Int
        get() = sp.getInt("timer", 30)
        set(v) = sp.edit().putInt("timer", v).apply()

    /** Rider agreed to keep sensor data around alarms, for tuning detection. */
    var logConsent: Boolean
        get() = sp.getBoolean("log", false)
        set(v) = sp.edit().putBoolean("log", v).apply()

    /** First contact is the primary one: they also get the phone call. */
    var contacts: List<Contact>
        get() = sp.getString("contacts", "").orEmpty().lines()
            .filter { '\t' in it }
            .map { line -> line.split('\t', limit = 2).let { (n, p) -> Contact(n, p) } }
        set(v) = sp.edit().putString("contacts", v.joinToString("\n") { "${it.name}\t${it.phone}" }).apply()

    companion object {
        val TIMER_CHOICES = listOf(20, 30, 45, 60)
    }
}

/** Keeps digits and a leading +; null if it can't be a phone number. */
fun cleanPhone(raw: String): String? {
    val p = raw.trim().let { (if (it.startsWith("+")) "+" else "") + it.filter(Char::isDigit) }
    return p.takeIf { it.count(Char::isDigit) in 10..13 }
}
