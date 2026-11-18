package com.resqgo.app

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow

enum class Phase { IDLE, MONITORING, ALARM, SOS }

/** One row on the SOS screen. ok = null while still trying. */
data class SendLine(val what: String, val status: String, val ok: Boolean?)

/** Ride state shared by RideService and the screens, inside one app process. */
object RideState {
    val phase = MutableStateFlow(Phase.IDLE)
    val sends = MutableStateFlow<List<SendLine>>(emptyList())

    /** SystemClock.elapsedRealtime() when the alarm turns into an SOS. */
    @Volatile var alarmEndsAt = 0L

    /** Live sensor readings for the demo panel. Pushed about ten times a second, not at sensor rate. */
    val accelG = MutableStateFlow(1f)
    val gyroDps = MutableStateFlow(Float.NaN)

    /** Degrees the phone is tipped away from lying flat. Budget phones have no gyroscope, but
     *  every phone has an accelerometer, and a crashed bike's phone ends up on its side. */
    val tiltDeg = MutableStateFlow(0f)
    val speedKmh = MutableStateFlow(-1f)

    /** The last few seconds of acceleration, oldest first, for the live graph. */
    val trace = MutableStateFlow<List<Float>>(emptyList())

    /** Set when the rules see a hit, cleared when the verdict lands. Drives the "impact" flash. */
    val impact = MutableStateFlow(false)

    /** A flow so the map screen follows the rider; read `.value` where you just need the latest fix. */
    val location = MutableStateFlow<Location?>(null)
    var lastLocation: Location?
        get() = location.value
        set(v) { location.value = v }
}
