package com.resqgo.app

import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The two Google APIs this project's key has: Places (searchText) and Routes (computeRoutes).
 * Both are new-style APIs taking the key in a header. Legacy Geocoding, Directions, Static Maps
 * and Map Tiles are not enabled on this key — see the notes in README-maps.md.
 */
data class Place(val name: String, val address: String, val at: LatLng)

data class Route(val points: List<LatLng>, val metres: Int, val seconds: Int) {
    val km get() = metres / 1000.0
    val minutes get() = (seconds + 30) / 60
}

object MapsApi {
    fun configured() = BuildConfig.MAPS_API_KEY.isNotBlank()

    /** Up to 5 matches for what the rider typed, nearest to [near] first where Google can tell. */
    suspend fun search(query: String, near: LatLng?): Result<List<Place>> = call(
        "https://places.googleapis.com/v1/places:searchText",
        "places.displayName,places.formattedAddress,places.location",
        JSONObject().apply {
            put("textQuery", query)
            put("regionCode", "IN")
            put("maxResultCount", 5)
            near?.let {
                put("locationBias", JSONObject().put("circle", JSONObject()
                    .put("center", latLng(it))
                    .put("radius", 50_000)))
            }
        },
    ).map { body ->
        val arr = body.optJSONArray("places") ?: return@map emptyList()
        (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            val loc = p.getJSONObject("location")
            Place(
                p.optJSONObject("displayName")?.optString("text").orEmpty(),
                p.optString("formattedAddress"),
                LatLng(loc.getDouble("latitude"), loc.getDouble("longitude")),
            )
        }
    }

    /** A road route for a two-wheeler, which is what RESQGO's riders are on. */
    suspend fun route(from: LatLng, to: LatLng): Result<Route> = call(
        "https://routes.googleapis.com/directions/v2:computeRoutes",
        "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline",
        JSONObject().apply {
            put("origin", JSONObject().put("location", JSONObject().put("latLng", latLng(from))))
            put("destination", JSONObject().put("location", JSONObject().put("latLng", latLng(to))))
            put("travelMode", "TWO_WHEELER")
        },
    ).mapCatching { body ->
        val r = body.getJSONArray("routes").getJSONObject(0)
        Route(
            decodePolyline(r.getJSONObject("polyline").getString("encodedPolyline")),
            r.getInt("distanceMeters"),
            r.getString("duration").removeSuffix("s").toDouble().toInt(),
        )
    }

    private fun latLng(p: LatLng) = JSONObject().put("latitude", p.latitude).put("longitude", p.longitude)

    private suspend fun call(url: String, fields: String, body: JSONObject): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            if (!configured()) return@withContext Result.failure(IllegalStateException("No Google Maps key in this build."))
            runCatching {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("X-Goog-Api-Key", BuildConfig.MAPS_API_KEY)
                    setRequestProperty("X-Goog-FieldMask", fields)
                }
                try {
                    c.outputStream.use { it.write(body.toString().toByteArray()) }
                    val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)
                        .bufferedReader().readText()
                    if (c.responseCode !in 200..299) {
                        // Google's own words are the most useful thing to show: they name the API to enable.
                        val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }
                            .getOrDefault("HTTP ${c.responseCode}")
                        throw IllegalStateException(msg)
                    }
                    JSONObject(text)
                } finally {
                    c.disconnect()
                }
            }
        }
}

/** Google's encoded polyline format: the route line comes back as this compact string. */
fun decodePolyline(encoded: String): List<LatLng> {
    val out = mutableListOf<LatLng>()
    var i = 0
    var lat = 0
    var lng = 0
    while (i < encoded.length) {
        for (first in listOf(true, false)) {
            var shift = 0
            var result = 0
            var b: Int
            do {
                b = encoded[i++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val d = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            if (first) lat += d else lng += d
        }
        out.add(LatLng(lat / 1e5, lng / 1e5))
    }
    return out
}
