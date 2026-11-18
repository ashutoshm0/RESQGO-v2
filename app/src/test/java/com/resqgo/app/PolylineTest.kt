package com.resqgo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The route line arrives as an encoded string; a wrong decode draws the route across the wrong city. */
class PolylineTest {
    @Test fun decodesGooglesOwnExample() {
        // From Google's polyline documentation: (38.5,-120.2), (40.7,-120.95), (43.252,-126.453)
        val pts = decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, pts.size)
        val want = listOf(38.5 to -120.2, 40.7 to -120.95, 43.252 to -126.453)
        want.forEachIndexed { i, (lat, lng) ->
            assertEquals(lat, pts[i].latitude, 1e-5)
            assertEquals(lng, pts[i].longitude, 1e-5)
        }
    }

    @Test fun anEmptyLineIsNoPoints() {
        assertTrue(decodePolyline("").isEmpty())
    }
}
