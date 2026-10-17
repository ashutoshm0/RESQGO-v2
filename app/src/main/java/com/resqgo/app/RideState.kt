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
    @Volatile var lastLocation: Location? = null
}
