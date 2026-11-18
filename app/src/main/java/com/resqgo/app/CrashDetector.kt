package com.resqgo.app

import kotlin.math.abs

/**
 * Crash rules, v1: a hard impact while moving, then the phone stays still.
 * Every number is a calibration knob. Tune them with logged ride data, not by guessing.
 */
data class Thresholds(
    val impactG: Double = 4.0,          // spike needed when GPS speed is known
    val noGpsImpactG: Double = 6.0,     // stricter spike when there's no recent GPS speed
    val minSpeedKmh: Double = 15.0,     // rider must have been at least this fast...
    val speedLookbackMs: Long = 5_000,  // ...within this long before the impact
    val settleMs: Long = 1_500,         // ignore the tumble right after impact
    val gpsLagMs: Long = 3_000,         // GPS speed trails reality by a couple of seconds
    val stillWindowMs: Long = 10_000,   // how long after impact we watch for stillness
    val stillToleranceG: Double = 0.35, // |g - 1| above this counts as movement
    val maxMovingFraction: Double = 0.15,
    val rodeOnKmh: Double = 10.0,       // GPS speed above this after impact = rider carried on
) {
    companion object {
        /**
         * For showing the app indoors: a firm shake of the phone sets the alarm off about two
         * seconds later, with no GPS speed needed. Far too twitchy for a real ride.
         */
        fun demo() = Thresholds(
            impactG = 2.5,
            noGpsImpactG = 2.5,
            minSpeedKmh = 0.0,
            settleMs = 300,
            stillWindowMs = 1_500,
            maxMovingFraction = 0.95,
            rodeOnKmh = Double.MAX_VALUE, // a GPS fix must never cancel a demo
        )
    }
}

enum class Event { NONE, IMPACT, CRASH, CANCELLED }

class CrashDetector(private val th: Thresholds = Thresholds()) {
    private val speeds = ArrayDeque<Pair<Long, Double>>()
    private var impactAt = -1L
    private var samples = 0
    private var moving = 0

    fun onSpeed(t: Long, kmh: Double) {
        speeds.addLast(t to kmh)
        while (speeds.first().first < t - 30_000) speeds.removeFirst()
    }

    fun reset() {
        impactAt = -1L
    }

    /** True between an impact and its verdict: that clip belongs to the alarm, not to the bump log. */
    val watching get() = impactAt >= 0

    /** Feed every accelerometer reading: [g] is total acceleration in g (1.0 = resting). */
    fun onAccel(t: Long, g: Double): Event {
        if (impactAt < 0) return checkImpact(t, g)

        val since = t - impactAt
        if (since < th.settleMs) return Event.NONE

        val latest = speeds.lastOrNull()
        if (latest != null && latest.first >= impactAt + th.gpsLagMs && latest.second > th.rodeOnKmh) {
            impactAt = -1L
            return Event.CANCELLED
        }

        samples++
        if (abs(g - 1.0) > th.stillToleranceG) moving++
        if (since < th.stillWindowMs) return Event.NONE

        val still = moving <= samples * th.maxMovingFraction
        impactAt = -1L
        return if (still) Event.CRASH else Event.CANCELLED
    }

    private fun checkImpact(t: Long, g: Double): Event {
        if (g < th.impactG) return Event.NONE
        val recent = speeds.filter { it.first >= t - th.speedLookbackMs }
        if (recent.isEmpty()) {
            if (g < th.noGpsImpactG) return Event.NONE
        } else if (recent.maxOf { it.second } < th.minSpeedKmh) {
            return Event.NONE // phone dropped while standing still
        }
        impactAt = t
        samples = 0
        moving = 0
        return Event.IMPACT
    }
}
