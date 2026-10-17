package com.resqgo.app

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil

// ---------- theme: the RESQGO palettes, with the light-theme contrast fix ----------

val SosRed = Color(0xFFC62828)

private val LightColors = lightColorScheme(
    primary = Color(0xFFA0522D), onPrimary = Color.White,
    background = Color(0xFFFAF5ED), onBackground = Color(0xFF2E2E2E),
    surface = Color(0xFFFAF5ED), onSurface = Color(0xFF2E2E2E),
    surfaceVariant = Color(0xFFF1D9B7), onSurfaceVariant = Color(0xFF5C4A36),
    secondaryContainer = Color(0xFFF1D9B7), onSecondaryContainer = Color(0xFF3B2A17),
    surfaceContainerLow = Color(0xFFF8F2E9), surfaceContainer = Color(0xFFF6EFE4),
    surfaceContainerHigh = Color(0xFFF4EBDE), surfaceContainerHighest = Color(0xFFF1E6D6),
    outline = Color(0xFF8A7A66), outlineVariant = Color(0xFFD9CBB8),
    error = SosRed,
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFACC15), onPrimary = Color(0xFF080B0B),
    background = Color(0xFF080B0B), onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF080B0B), onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF1F2630), onSurfaceVariant = Color(0xFFB8BEC6),
    secondaryContainer = Color(0xFF3A3416), onSecondaryContainer = Color(0xFFFDE68A),
    surfaceContainerLow = Color(0xFF101418), surfaceContainer = Color(0xFF151A20),
    surfaceContainerHigh = Color(0xFF1A2028), surfaceContainerHighest = Color(0xFF1F2630),
    outline = Color(0xFF8B929A), outlineVariant = Color(0xFF39414B),
    error = Color(0xFFFF8A80),
)

@Composable
fun ResqTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)

@Composable
private fun okColor() = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)

/** Bumped in MainActivity.onResume. */
object Resumes {
    var count by mutableIntStateOf(0)
}

fun Context.ride(action: String) {
    val i = Intent(this, RideService::class.java).setAction(action)
    if (RideState.phase.value == Phase.IDLE) startForegroundService(i) else startService(i)
}

private fun ridePermissions() = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.SEND_SMS)
    add(Manifest.permission.CALL_PHONE)
    add(Manifest.permission.READ_PHONE_STATE)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

// ---------- app ----------

@Composable
fun App() {
    val phase by RideState.phase.collectAsState()
    var screen by rememberSaveable { mutableStateOf("home") }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            phase == Phase.ALARM || phase == Phase.SOS -> AlarmScreen(phase)
            screen == "setup" -> SetupScreen(onDone = { screen = "home" })
            else -> HomeScreen(onSetup = { screen = "setup" })
        }
    }
}

/** Things the rider still has to do. fix = null means "open Safety setup". */
private class Todo(val text: String, val fix: Intent?)

private fun todos(ctx: Context): List<Todo> {
    val p = Prefs(ctx)
    val list = mutableListOf<Todo>()
    if (p.riderName.isBlank() || p.contacts.isEmpty()) {
        list += Todo("Add your name and at least one emergency contact.", null)
    }
    if (Build.VERSION.SDK_INT >= 34 && !ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
        list += Todo(
            "Allow full-screen alerts, so the crash alarm can show over other apps.",
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")),
        )
    }
    if (!ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)) {
        list += Todo(
            "Set Battery to Unrestricted for RESQGO, or the phone may stop it mid-ride. On Xiaomi, Vivo, Oppo and Realme, also turn on Autostart.",
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
        )
    }
    return list
}

// ---------- home ----------

@Composable
fun HomeScreen(onSetup: () -> Unit) {
    val ctx = LocalContext.current
    val phase by RideState.phase.collectAsState()
    val riding = phase != Phase.IDLE
    val todo = remember(Resumes.count, phase) { todos(ctx) }
    var note by remember { mutableStateOf<String?>(null) }

    fun afterPermissions(granted: Map<String, Boolean>, action: String) {
        val location = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true
        note = when {
            !location -> "RESQGO needs precise location to detect crashes and send where you are."
            granted[Manifest.permission.SEND_SMS] != true -> "Without SMS permission, SOS can't reach your contacts."
            else -> null
        }
        if (location) ctx.ride(action)
    }
    val startAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        afterPermissions(it, RideService.ACTION_START)
    }
    val sosAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        afterPermissions(it, RideService.ACTION_SOS_NOW)
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                buildAnnotatedString {
                    append("RES")
                    withStyle(SpanStyle(color = SosRed)) { append("Q") }
                    append("GO")
                },
                fontSize = 40.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text("Ride safe. Help faster.", color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(10.dp).clip(CircleShape)
                        .background(if (riding) okColor() else MaterialTheme.colorScheme.onSurfaceVariant)
                )
                Text(
                    if (riding) "Monitoring on. The alarm waits ${Prefs(ctx).timerSec} s before SOS." else "Not monitoring",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Button(
                onClick = {
                    when {
                        riding -> ctx.ride(RideService.ACTION_STOP)
                        todo.any { it.fix == null } -> onSetup()
                        else -> startAsk.launch(ridePermissions())
                    }
                },
                shape = CircleShape,
                modifier = Modifier.padding(vertical = 8.dp).size(200.dp),
                colors = if (riding) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(if (riding) "Stop ride" else "Start ride", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            if (Prefs(ctx).testMode) {
                Text(
                    "TEST MODE is on: the speed check is off and every alert is marked TEST. Turn it off in Safety setup after staged tests.",
                    color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold,
                )
            }
            if (!riding) {
                Text(
                    "No destination needed. Use any app while you ride; RESQGO keeps watching.",
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            note?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }

            todo.forEach { t ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 4.dp)) {
                        Text(t.text)
                        TextButton(
                            onClick = { if (t.fix == null) onSetup() else runCatching { ctx.startActivity(t.fix) } },
                            contentPadding = PaddingValues(0.dp),
                        ) { Text(if (t.fix == null) "Open safety setup" else "Open settings") }
                    }
                }
            }

            TextButton(onClick = onSetup) { Text("Safety setup") }
            Spacer(Modifier.height(110.dp)) // keeps content clear of the SOS button
        }

        SosHoldButton(
            onSos = { if (Prefs(ctx).contacts.isEmpty()) onSetup() else sosAsk.launch(ridePermissions()) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
    }
}

/** Needs a 2-second hold, so it can't fire in a pocket. Sits where the right thumb rests. */
@Composable
fun SosHoldButton(onSos: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onSos)
    var progress by remember { mutableFloatStateOf(0f) }
    Box(
        modifier.size(96.dp).clip(CircleShape).background(SosRed)
            .semantics { contentDescription = "SOS. Press and hold for 2 seconds." }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val hold = scope.launch {
                        val start = SystemClock.uptimeMillis()
                        while (progress < 1f) {
                            progress = ((SystemClock.uptimeMillis() - start) / 2000f).coerceAtMost(1f)
                            delay(16)
                        }
                        latest()
                    }
                    tryAwaitRelease()
                    hold.cancel()
                    progress = 0f
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        if (progress > 0f) {
            CircularProgressIndicator(
                progress = { progress }, modifier = Modifier.size(86.dp),
                color = Color.White, trackColor = Color.Transparent,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SOS", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text(if (progress > 0f) "keep holding" else "hold 2 s", color = Color.White, fontSize = 11.sp)
        }
    }
}

// ---------- safety setup ----------

@Composable
fun SetupScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(prefs.riderName) }
    val contacts = remember { prefs.contacts.toMutableStateList() }
    var timer by remember { mutableIntStateOf(prefs.timerSec) }
    var logging by remember { mutableStateOf(prefs.logConsent) }
    var newName by remember { mutableStateOf("") }
    var newPhone by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val sends by RideState.sends.collectAsState()
    var riderId by remember { mutableStateOf(prefs.riderId) }
    var placement by remember { mutableStateOf(prefs.placement) }
    var testMode by remember { mutableStateOf(prefs.testMode) }
    val waiting by Uploader.waiting.collectAsState()
    val uploadStatus by Uploader.status.collectAsState()
    val testAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.SEND_SMS] == true) scope.launch { SosSender.sendTest(ctx) }
        else error = "Allow SMS so RESQGO can send the test."
    }
    val alarmAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) ctx.ride(RideService.ACTION_TEST_ALARM)
        else error = "Allow precise location so RESQGO can run the test."
    }
    LaunchedEffect(Unit) { Uploader.flushSoon(ctx) } // refreshes the "waiting" count too
    BackHandler(onBack = onDone)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val small = MaterialTheme.typography.bodySmall

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onDone, contentPadding = PaddingValues(0.dp)) { Text("‹ Back") }
        Text("Safety setup", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        OutlinedTextField(
            value = name,
            onValueChange = { name = it.filterNot { c -> c == '\t' || c == '\n' }; prefs.riderName = name },
            label = { Text("Your name, used in the SOS message") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

        Section("Emergency contacts")
        Text("The first contact also gets a phone call.", style = small, color = muted)
        contacts.forEachIndexed { i, c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name + if (i == 0) " · primary" else "", fontWeight = FontWeight.SemiBold)
                    Text(c.phone, style = small, color = muted)
                }
                TextButton(onClick = { contacts.removeAt(i); prefs.contacts = contacts.toList() }) { Text("Remove") }
            }
        }
        if (contacts.size < 5) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it.filterNot { c -> c == '\t' || c == '\n' }; error = null },
                label = { Text("Contact name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = newPhone, onValueChange = { newPhone = it; error = null },
                label = { Text("Phone number") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = {
                val phone = cleanPhone(newPhone)
                when {
                    newName.isBlank() -> error = "Enter the contact's name."
                    phone == null -> error = "Enter a 10-digit mobile number. +91 is optional."
                    else -> {
                        contacts += Contact(newName.trim(), phone)
                        prefs.contacts = contacts.toList()
                        newName = ""
                        newPhone = ""
                    }
                }
            }) { Text("Add contact") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = small) }

        Section("Wait before SOS")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Prefs.TIMER_CHOICES.forEach { s ->
                FilterChip(selected = timer == s, onClick = { timer = s; prefs.timerSec = s }, label = { Text("$s s") })
            }
        }
        Text("Nothing longer than 60 s. Help shouldn't wait.", style = small, color = muted)

        Section("Improve detection")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Save sensor data around alarms")
                Text("Stays on this phone. Used to tune crash detection.", style = small, color = muted)
            }
            Switch(checked = logging, onCheckedChange = { logging = it; prefs.logConsent = it })
        }

        Section("Pilot")
        OutlinedTextField(
            value = riderId,
            onValueChange = { riderId = cleanRiderId(it); prefs.riderId = riderId },
            label = { Text("Rider ID, like R01") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Text("Where is the phone during rides?")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Prefs.PLACEMENTS.forEach { pl ->
                FilterChip(
                    selected = placement == pl, onClick = { placement = pl; prefs.placement = pl },
                    label = { Text(pl.replaceFirstChar(Char::uppercase)) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Test mode")
                Text(
                    "Staged crash tests only. Skips the speed check and marks every alert TEST. Applies from the next ride.",
                    style = small, color = muted,
                )
            }
            Switch(checked = testMode, onCheckedChange = { testMode = it; prefs.testMode = it })
        }
        Text(
            if (Uploader.enabled()) "Waiting to upload: $waiting files. $uploadStatus" else uploadStatus,
            style = small, color = muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { Uploader.flushSoon(ctx) }, enabled = Uploader.enabled()) { Text("Upload now") }
            OutlinedButton(onClick = { sharePilotData(ctx) }) { Text("Share pilot data") }
        }

        Section("Check that SOS works")
        Button(
            onClick = { testAsk.launch(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE)) },
            enabled = contacts.isNotEmpty(), modifier = Modifier.fillMaxWidth(),
        ) { Text("Send test SOS") }
        OutlinedButton(
            onClick = { alarmAsk.launch(ridePermissions()) },
            enabled = contacts.isNotEmpty(), modifier = Modifier.fillMaxWidth(),
        ) { Text("Test the full alarm") }
        Text(
            "Starts a ride and rings the alarm. If nobody taps I'm OK, your contacts get an SMS marked TEST.",
            style = small, color = muted,
        )
        sends.forEach { SendRow(it) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String) = Text(
    title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
    modifier = Modifier.padding(top = 8.dp),
)

@Composable
private fun SendRow(line: SendLine) {
    val color = when (line.ok) {
        true -> okColor()
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(line.what, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        Text(line.status, Modifier.weight(1.3f), color = color, textAlign = TextAlign.End)
    }
}

// ---------- alarm and SOS ----------

@Composable
fun AlarmScreen(phase: Phase) {
    val ctx = LocalContext.current
    if (phase == Phase.ALARM) {
        var left by remember { mutableIntStateOf(Prefs(ctx).timerSec) }
        LaunchedEffect(Unit) {
            while (true) {
                left = ceil((RideState.alarmEndsAt - SystemClock.elapsedRealtime()) / 1000.0).toInt().coerceAtLeast(0)
                delay(200)
            }
        }
        Surface(Modifier.fillMaxSize(), color = SosRed, contentColor = Color.White) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("POSSIBLE CRASH DETECTED", fontSize = 14.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(16.dp))
                Text("Are you OK?", fontSize = 44.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(24.dp))
                Text("$left", fontSize = 110.sp, fontWeight = FontWeight.Black)
                Text("seconds until SOS goes to your contacts", textAlign = TextAlign.Center)
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { ctx.ride(RideService.ACTION_IM_OK) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF8E1B1B)),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                ) { Text("I'm OK", fontSize = 36.sp, fontWeight = FontWeight.Black) }
                TextButton(onClick = { ctx.ride(RideService.ACTION_SOS_NOW) }) { Text("Send SOS now", color = Color.White) }
            }
        }
    } else {
        val sends by RideState.sends.collectAsState()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("SOS sent", color = MaterialTheme.colorScheme.error, fontSize = 36.sp, fontWeight = FontWeight.Black)
                Text(
                    "Your contacts get your location again every 2 minutes. The SMS tells them to call 112 if they can't reach you.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                sends.forEach { SendRow(it) }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { shareOnWhatsApp(ctx) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Share on WhatsApp")
                }
                Button(
                    onClick = { ctx.ride(RideService.ACTION_IM_SAFE) },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                ) { Text("I'm safe, cancel SOS", fontSize = 18.sp) }
            }
        }
    }
}

/** Backup for uploads: zip of every file on this phone, sent by hand over WhatsApp, Drive or Bluetooth. */
private fun sharePilotData(ctx: Context) {
    val zip = Uploader.exportZip(ctx) // ponytail: zips on the main thread; a few MB takes under a second
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", zip)
    val send = Intent(Intent.ACTION_SEND).setType("application/zip")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Share pilot data"))
}

/** WhatsApp can't be sent automatically, so the rider shares it themselves if they can. */
private fun shareOnWhatsApp(ctx: Context) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, SosSender.shareText(ctx))
    try {
        ctx.startActivity(Intent(send).setPackage("com.whatsapp"))
    } catch (e: ActivityNotFoundException) {
        ctx.startActivity(Intent.createChooser(send, "Share your location"))
    }
}
