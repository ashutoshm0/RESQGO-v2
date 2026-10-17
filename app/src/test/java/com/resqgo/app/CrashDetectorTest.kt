package com.resqgo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.sin

/** Simulated rides: accelerometer at 50 Hz, GPS speed once a second. */
class CrashDetectorTest {

    private class Ride {
        val detector = CrashDetector()
        val events = mutableListOf<Event>()
        var t = 0L

        /** [kmh] = null means no GPS fix. */
        fun ride(ms: Long, kmh: Double?, g: (Long) -> Double) {
            val end = t + ms
            while (t < end) {
                if (kmh != null && t % 1000 == 0L) detector.onSpeed(t, kmh)
                val e = detector.onAccel(t, g(t))
                if (e != Event.NONE) events += e
                t += 20
            }
        }
    }

    private val road = { t: Long -> 1.0 + 0.3 * sin(t / 37.0) }   // normal vibration
    private val still = { t: Long -> 1.0 + 0.04 * sin(t / 50.0) } // phone lying on the road
    private val impact = { _: Long -> 6.4 }

    @Test fun normalRidingWithPotholesNeverAlarms() {
        val r = Ride()
        r.ride(120_000, 30.0) { t -> if (t % 7_000 < 40) 3.2 else road(t) } // a pothole every 7 s
        assertEquals(emptyList<Event>(), r.events)
    }

    @Test fun phoneDroppedAtARedLightIsIgnored() {
        val r = Ride()
        r.ride(10_000, 0.0, still)
        r.ride(100, 0.0, impact)
        r.ride(15_000, 0.0, still)
        assertEquals(emptyList<Event>(), r.events)
    }

    @Test fun crashWhileRidingRaisesTheAlarm() {
        val r = Ride()
        r.ride(10_000, 30.0, road)
        r.ride(100, 30.0, impact)  // GPS still says 30: it lags
        r.ride(12_000, 0.0, still)
        assertEquals(listOf(Event.IMPACT, Event.CRASH), r.events)
    }

    @Test fun riderWhoRidesOnIsNotAlarmed() {
        val r = Ride()
        r.ride(10_000, 30.0, road)
        r.ride(100, 30.0, impact)
        r.ride(12_000, 25.0, road)
        assertEquals(listOf(Event.IMPACT, Event.CANCELLED), r.events)
    }

    @Test fun withoutGpsOnlyAHarderImpactCounts() {
        val r = Ride()
        r.ride(5_000, null, road)
        r.ride(100, null) { 5.0 }  // would count with GPS, not without
        r.ride(12_000, null, still)
        assertEquals(emptyList<Event>(), r.events)
        r.ride(100, null) { 7.0 }
        r.ride(12_000, null, still)
        assertEquals(listOf(Event.IMPACT, Event.CRASH), r.events)
    }

    @Test fun sosLinkWorksInEveryLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG")) // Arabic digits by default
            val text = alertText("Ravi", false, "9:42 PM", mapsLink(28.5931, 77.2197), 64)
            assertTrue(text, "https://maps.google.com/?q=28.59310,77.21970" in text)
            assertTrue(text, "call 112" in text)
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun phoneNumbersAreCleaned() {
        assertEquals("+919812345678", cleanPhone("+91 98123-45678"))
        assertEquals("9812345678", cleanPhone("98123 45678"))
        assertEquals(null, cleanPhone("12345"))
    }
}
