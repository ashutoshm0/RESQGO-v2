package com.resqgo.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import kotlinx.coroutines.launch

private val DELHI = LatLng(28.6139, 77.2090) // only until the first GPS fix arrives

/**
 * The map on the home screen. If Google never draws it, say so plainly rather than leaving a grey
 * rectangle: that happens when the key has no Maps SDK for Android, which MAPS.md explains.
 */
@Composable
fun HomeMap(onSearch: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val location by RideState.location.collectAsState()
    val here = location?.let { LatLng(it.latitude, it.longitude) }
    var drew by remember { mutableStateOf(false) }
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(8_000); slow = true }

    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(here ?: DELHI, 15f) }
    var followed by remember { mutableStateOf(false) }
    LaunchedEffect(here) {
        if (!followed) here?.let { camera.position = CameraPosition.fromLatLngZoom(it, 16f); followed = true }
    }

    Box(modifier) {
        if (MapsApi.configured()) {
            val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = camera,
                properties = MapProperties(isMyLocationEnabled = fine),
                uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = false),
                onMapLoaded = { drew = true },
            )
        }
        // Google draws nothing without Maps SDK for Android on the key. Rather than leave a dead
        // grey panel, offer what does work: search a place, get the route, hand over to Google Maps.
        if (!drew && (slow || !MapsApi.configured())) {
            Surface(
                onClick = onSearch,
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Plan your ride", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Search any place to see the distance and the two-wheeler route, then hand it to Google Maps.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("Tap to search", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun MapScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val location by RideState.location.collectAsState()
    val here = location?.let { LatLng(it.latitude, it.longitude) }

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var destination by remember { mutableStateOf<Place?>(null) }
    var route by remember { mutableStateOf<Route?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var mapDrew by remember { mutableStateOf(false) }
    var mapSlow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(8_000); mapSlow = true }

    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(here ?: DELHI, 15f) }
    var followed by remember { mutableStateOf(false) }
    LaunchedEffect(here) { // snap to the rider once, then leave the camera to them
        if (!followed) here?.let { camera.position = CameraPosition.fromLatLngZoom(it, 16f); followed = true }
    }
    LaunchedEffect(route) {
        val pts = route?.points.orEmpty()
        if (pts.size > 1) {
            val b = LatLngBounds.builder().apply { pts.forEach { include(it) } }.build()
            runCatching { camera.animate(CameraUpdateFactory.newLatLngBounds(b, 120)) }
        }
    }

    fun find() {
        if (query.isBlank()) return
        busy = true
        error = null
        scope.launch {
            MapsApi.search(query, here)
                .onSuccess { results = it; if (it.isEmpty()) error = "Nothing found for \"$query\"." }
                .onFailure { error = it.message }
            busy = false
        }
    }

    fun pick(p: Place) {
        destination = p
        results = emptyList()
        route = null
        val from = here ?: run { error = "Waiting for GPS. Go outside, then try again."; return }
        busy = true
        scope.launch {
            MapsApi.route(from, p.at).onSuccess { route = it }.onFailure { error = it.message }
            busy = false
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, contentPadding = PaddingValues(4.dp)) { Text("‹ Back") }
                Text("Map", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
            }

            if (!MapsApi.configured()) {
                Text(
                    "No Google Maps key in this build. Add MAPS_API_KEY to local.properties and rebuild.",
                    Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it; error = null },
                label = { Text("Search a place") },
                singleLine = true,
                trailingIcon = { TextButton(onClick = ::find, enabled = !busy) { Text(if (busy) "…" else "Find") } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )

            error?.let {
                Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error)
            }

            results.forEach { p ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(p.name, fontWeight = FontWeight.SemiBold)
                        Text(p.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { pick(p) }, contentPadding = PaddingValues(0.dp)) { Text("Show route") }
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                val fineGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = camera,
                    properties = MapProperties(isMyLocationEnabled = fineGranted),
                    uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = fineGranted),
                    onMapLoaded = { mapDrew = true },
                ) {
                    here?.let { Marker(state = rememberMarkerState(position = it), title = "You") }
                    destination?.let { Marker(state = rememberMarkerState(position = it.at), title = it.name) }
                    route?.let { Polyline(points = it.points, color = MaterialTheme.colorScheme.primary, width = 14f) }
                }

                if (!mapDrew && mapSlow) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(
                            Modifier.fillMaxSize().padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                if (destination == null) {
                                    "Search a place above to see its distance and route."
                                } else {
                                    "Route ready. The distance and time are below."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                location?.let { loc ->
                    // Bottom-right: Google's logo sits bottom-left and their terms say not to cover it.
                    Card(Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${(loc.speed * 3.6f).toInt()}", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                            Text("km/h", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            route?.let { r ->
                val d = destination
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "%.1f km · about %d min by two-wheeler".format(r.km, r.minutes),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { destination = null; route = null }, modifier = Modifier.weight(1f)) {
                            Text("Clear")
                        }
                        Button(onClick = { d?.let { navigateInGoogleMaps(ctx, it.at) } }, modifier = Modifier.weight(1f)) {
                            Text("Navigate")
                        }
                    }
                    Text(
                        "Navigate opens Google Maps for turn-by-turn. RESQGO keeps watching for crashes behind it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Turn-by-turn stays in the Google Maps app: it is free, and it is what riders already know. */
private fun navigateInGoogleMaps(ctx: Context, to: LatLng) {
    val uri = Uri.parse("google.navigation:q=${to.latitude},${to.longitude}&mode=l") // mode l = two-wheeler
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")) }
        .onFailure { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}
