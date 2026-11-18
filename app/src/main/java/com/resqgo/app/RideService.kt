package com.resqgo.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * The ride session. Runs as a foreground service so it keeps watching
 * while the rider uses Rapido, Zomato, Google Maps or anything else.
 */
class RideService : Service(), SensorEventListener, LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var detector = CrashDetector()
    private val recent = ArrayDeque<Sample>() // last 25 s of readings, for the sensor log
    private var alarmSnapshot: List<Sample> = emptyList()
    private var speedKmh = -1f
    private var gx = Float.NaN
    private var gy = Float.NaN
    private var gz = Float.NaN
    private var running = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var player: MediaPlayer? = null
    private var savedAlarmVolume = -1
    private var alarmJob: Job? = null
    private var sosJob: Job? = null
    private var alarmIsTest = false

    // ride diary, fixed when the ride starts
    private var testMode = false
    private var demoMode = false
    private var logConsent = false
    private var hasGyro = false
    private var rider = "unknown"
    private var rideName = ""
    private var rideStartedAt = 0L
    private var batteryStart: Int? = null
    private var alarms = 0
    private var riderOk = 0
    private var sosCount = 0
    private var movedOn = 0
    private var bumpsSeen = 0
    private var bumpsSaved = 0
    private var mlFlagged = 0 // alarms the ML model scored as false alarms (shadow mode)
    private var lastUiPush = 0L
    private var bumpAt = -1L
    private var lastCheckpoint = 0L
    private var checkpoints = 0

    /** Accelerometer in m/s², gyroscope in rad/s (NaN when the phone has none). */
    private class Sample(
        val t: Long, val ax: Float, val ay: Float, val az: Float,
        val gx: Float, val gy: Float, val gz: Float, val kmh: Float,
    )

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RIDE, "Ride monitoring", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, "Crash alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // the service plays the alarm itself, at alarm volume
                enableVibration(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopRide(); return START_NOT_STICKY }
            ACTION_IM_OK -> imOk()
            ACTION_IM_SAFE -> imSafe()
            ACTION_SOS_NOW -> if (ensureRunning()) sendSos(manual = true)
            ACTION_TEST_ALARM -> if (ensureRunning() && RideState.phase.value == Phase.MONITORING) startAlarm(test = true)
            else -> ensureRunning() // ACTION_START, or Android restarting us after a kill
        }
        if (!running) stopSelf()
        return START_STICKY
    }

    private fun ensureRunning(): Boolean {
        if (running) return true
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID_RIDE, rideNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(ID_RIDE, rideNotification())
            }
        } catch (e: Exception) { // location permission missing, or Android refused a background start
            return false
        }
        running = true
        // ponytail: CPU stays awake for the whole ride so sensor readings keep coming with the
        // screen off. The ride diary measures the battery cost; try wake-up sensors if it's too high.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "resqgo:ride")
            .apply { acquire(12 * 60 * 60 * 1000L) }
        val sm = getSystemService(SensorManager::class.java)
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) // about 50 readings a second
        }
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) // many budget phones don't have one
        hasGyro = gyro != null
        gyro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        try {
            getSystemService(LocationManager::class.java)
                .requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        } catch (e: SecurityException) {
            // No GPS permission: detection still runs, with the stricter no-speed threshold.
        }
        startDiary() // after the sensors, so the first checkpoint knows about the gyroscope
        RideState.phase.value = Phase.MONITORING
        return true
    }

    // ---------- sensors ----------

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = event.values
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            gx = x; gy = y; gz = z
            return
        }
        val t = SystemClock.elapsedRealtime()
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        recent.addLast(Sample(t, x, y, z, gx, gy, gz, speedKmh))
        while (recent.first().t < t - 25_000) recent.removeFirst()

        if (t - lastCheckpoint >= 60_000) checkpoint(t)
        pushToUi(t, g)

        val event = if (RideState.phase.value == Phase.MONITORING) detector.onAccel(t, g.toDouble()) else Event.NONE
        watchBumps(t, g)
        when (event) {
            Event.CRASH -> startAlarm(test = false)
            Event.CANCELLED -> { movedOn++; saveWindow("moved_on", recent.toList()) }
            else -> {}
        }
    }

    /**
     * Feeds the live panel on the home screen. Sensors arrive about 50 times a second, which is far
     * more than a screen needs, so this pushes ten times a second and keeps 8 seconds of history.
     */
    private fun pushToUi(t: Long, g: Float) {
        if (t - lastUiPush < 100) return
        lastUiPush = t
        RideState.accelG.value = g
        RideState.gyroDps.value = if (gx.isNaN()) Float.NaN
        else Math.toDegrees(sqrt(gx * gx + gy * gy + gz * gz).toDouble()).toFloat()
        RideState.speedKmh.value = speedKmh
        RideState.impact.value = detector.watching
        RideState.trace.value = (RideState.trace.value + g).takeLast(TRACE_POINTS)
    }

    /** Potholes and speed breakers: 6-second clips that teach the ML model what isn't a crash. */
    private fun watchBumps(t: Long, g: Float) {
        if (detector.watching || RideState.phase.value != Phase.MONITORING) {
            bumpAt = -1L // the alarm or moved_on clip already covers this hit
            return
        }
        if (bumpAt < 0 && g >= BUMP_G) {
            bumpsSeen++
            if (bumpsSaved < MAX_BUMP_FILES) bumpAt = t
        } else if (bumpAt >= 0 && t - bumpAt >= 3_000) {
            saveWindow("bump", recent.filter { it.t >= bumpAt - 3_000 })
            bumpsSaved++
            bumpAt = -1L
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onLocationChanged(location: Location) {
        RideState.lastLocation = location
        if (location.hasSpeed()) {
            speedKmh = location.speed * 3.6f
            detector.onSpeed(SystemClock.elapsedRealtime(), speedKmh.toDouble())
        }
    }

    // Android 9 and 10 call these and crash if the app doesn't implement them.
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Needed on Android 9 and 10")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    // ---------- alarm, SOS, cancel ----------

    private fun startAlarm(test: Boolean) {
        val secs = Prefs(this).timerSec
        alarmIsTest = test
        if (!test) alarms++
        alarmSnapshot = recent.toList() // covers the impact; saved once we know the outcome
        if (!test) mlScore(alarmSnapshot)?.let { if (it < (model?.threshold ?: 0.0)) mlFlagged++ }
        RideState.alarmEndsAt = SystemClock.elapsedRealtime() + secs * 1000L
        RideState.phase.value = Phase.ALARM
        notifications().notify(ID_ALARM, alarmNotification(secs))
        soundOn()
        alarmJob = scope.launch {
            delay(secs * 1000L)
            sendSos(manual = false)
        }
    }

    private fun imOk() {
        if (RideState.phase.value != Phase.ALARM) return
        alarmJob?.cancel()
        soundOff()
        notifications().cancel(ID_ALARM)
        if (!alarmIsTest) { riderOk++; saveWindow("rider_ok", alarmSnapshot) }
        alarmIsTest = false
        detector.reset()
        RideState.phase.value = Phase.MONITORING
    }

    private fun sendSos(manual: Boolean) {
        val was = RideState.phase.value
        if (was == Phase.SOS) return
        alarmJob?.cancel()
        soundOff()
        if (was == Phase.ALARM && !manual && !alarmIsTest) saveWindow("no_response", alarmSnapshot)
        val test = alarmIsTest || testMode
        sosCount++
        RideState.phase.value = Phase.SOS
        notifications().notify(ID_ALARM, sosNotification())
        sosJob = scope.launch {
            val sim = SosSender.sendAlert(this@RideService, manual, test)
            SosSender.callPrimary(this@RideService, sim)
            // ponytail: a fixed 5 updates, 2 minutes apart. Switch to "until someone replies"
            // once contacts have a way to acknowledge.
            repeat(5) {
                delay(2 * 60_000L)
                SosSender.sendUpdate(this@RideService, sim, test)
            }
        }
    }

    private fun imSafe() {
        if (RideState.phase.value != Phase.SOS) return
        sosJob?.cancel()
        notifications().cancel(ID_ALARM)
        alarmIsTest = false
        detector.reset()
        RideState.phase.value = Phase.MONITORING
        scope.launch { SosSender.sendImSafe(this@RideService) }
    }

    private fun stopRide() {
        if (running) {
            val csv = rideCsv("stopped")
            Uploader.queue(this, rideName) { csv }
            File(filesDir, "current-$rideName").delete()
        }
        cleanup()
        Uploader.flushSoon(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        cleanup()
        scope.cancel()
        super.onDestroy()
    }

    private fun cleanup() {
        if (running) {
            getSystemService(SensorManager::class.java).unregisterListener(this)
            getSystemService(LocationManager::class.java).removeUpdates(this)
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            running = false
        }
        alarmJob?.cancel()
        sosJob?.cancel()
        soundOff()
        notifications().cancel(ID_ALARM)
        RideState.trace.value = emptyList()
        RideState.impact.value = false
        RideState.phase.value = Phase.IDLE
    }

    // ---------- ride diary (one row per ride in the pilot sheet) ----------

    private fun startDiary() {
        Uploader.recoverInterrupted(this) // a previous ride that Android killed
        val p = Prefs(this)
        testMode = p.testMode
        demoMode = p.demoMode
        logConsent = p.logConsent
        rider = p.riderId.ifBlank { "unknown" }
        detector = CrashDetector(
            when {
                demoMode -> Thresholds.demo()
                testMode -> Thresholds(minSpeedKmh = 0.0, noGpsImpactG = 4.0)
                else -> Thresholds()
            }
        )
        rideStartedAt = System.currentTimeMillis()
        rideName = "ride-$rider-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(rideStartedAt))}.csv"
        batteryStart = SosSender.battery(this)
        alarms = 0; riderOk = 0; sosCount = 0; movedOn = 0; bumpsSeen = 0; bumpsSaved = 0; mlFlagged = 0
        bumpAt = -1L; checkpoints = 0
        checkpoint(SystemClock.elapsedRealtime())
    }

    /** Saved every minute as "interrupted": if Android kills the ride, this row is what survives. */
    private fun checkpoint(t: Long) {
        lastCheckpoint = t
        runCatching { File(filesDir, "current-$rideName").writeText(rideCsv("interrupted")) }
        if (++checkpoints % 15 == 0) Uploader.flushSoon(this) // upload during long rides too
    }

    private fun rideCsv(ended: String): String {
        val minutes = (System.currentTimeMillis() - rideStartedAt) / 60_000.0
        val row = listOf(
            rider, "${Build.MANUFACTURER} ${Build.MODEL}".replace(',', ' '), Build.VERSION.RELEASE,
            BuildConfig.VERSION_NAME, Prefs(this).placement, if (testMode) 1 else 0, if (hasGyro) 1 else 0,
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(rideStartedAt)),
            "%.1f".format(Locale.US, minutes), batteryStart ?: "", SosSender.battery(this) ?: "",
            alarms, riderOk, sosCount, movedOn, bumpsSeen, bumpsSaved, ended, mlFlagged,
        )
        return "rider,phone,android,app,placement,test_mode,gyro,started,minutes,battery_start,battery_end," +
            "alarms,rider_ok,sos,moved_on,bumps_seen,bumps_saved,ended,ml_flagged\n" + row.joinToString(",") + "\n"
    }

    // ---------- ML model, shadow mode: it scores, it never stops an alarm ----------

    private val model by lazy { CrashModel.get(this) }

    private fun mlScore(s: List<Sample>): Double? {
        val m = model ?: return null
        if (s.size < 50) return null
        return m.score(
            crashFeatures(
                DoubleArray(s.size) { s[it].t.toDouble() },
                DoubleArray(s.size) { s[it].ax.toDouble() },
                DoubleArray(s.size) { s[it].ay.toDouble() },
                DoubleArray(s.size) { s[it].az.toDouble() },
                DoubleArray(s.size) { if (s[it].kmh < 0) Double.NaN else s[it].kmh.toDouble() },
            )
        )
    }

    // ---------- sensor log (training data for the ML step) ----------

    /** Queues one labelled clip for upload. Only with the rider's consent. Staged tests get a staged_ label. */
    private fun saveWindow(label: String, samples: List<Sample>) {
        if (samples.isEmpty() || !logConsent) return
        val tag = if (testMode) "staged_$label" else label
        val name = "$rider-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}-$tag.csv"
        val t0 = samples.first().t
        Uploader.queue(this, name) {
            buildString {
                mlScore(samples)?.let { append("# model=${model?.version} ml_score=${"%.4f".format(Locale.US, it)}\n") }
                append("t_ms,ax,ay,az,gx,gy,gz,speed_kmh\n")
                for (s in samples) {
                    append(s.t - t0).append(',')
                    append(r(s.ax, 100)).append(',').append(r(s.ay, 100)).append(',').append(r(s.az, 100)).append(',')
                    append(r(s.gx, 1000)).append(',').append(r(s.gy, 1000)).append(',').append(r(s.gz, 1000)).append(',')
                    append(if (s.kmh < 0) "" else r(s.kmh, 10)).append('\n')
                }
            }
        }
    }

    /** Rounds to 1/[per]; empty for a missing reading. Faster than String.format for 1,250 rows. */
    private fun r(v: Float, per: Int): String = if (v.isNaN()) "" else ((v * per).roundToLong() / per.toDouble()).toString()

    // ---------- sound ----------

    private fun soundOn() {
        val am = getSystemService(AudioManager::class.java)
        runCatching { // throws in Do Not Disturb on some phones; the alarm still plays at the current volume
            savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@RideService, uri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
        vibrator()?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0))
    }

    private fun soundOff() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        vibrator()?.cancel()
        if (savedAlarmVolume >= 0) {
            runCatching { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0) }
            savedAlarmVolume = -1
        }
    }

    @Suppress("DEPRECATION") // VibratorManager needs Android 12; this works on all versions
    private fun vibrator(): Vibrator? = getSystemService(Vibrator::class.java)

    // ---------- notifications ----------

    private fun notifications() = getSystemService(NotificationManager::class.java)

    private fun serviceIntent(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, RideService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE
    )

    private fun openScreen(cls: Class<*>, code: Int) = PendingIntent.getActivity(
        this, code, Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE
    )

    private fun button(title: String, intent: PendingIntent) =
        Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), title, intent).build()

    private fun rideNotification() = Notification.Builder(this, CH_RIDE)
        .setSmallIcon(R.drawable.ic_stat)
        .setContentTitle(
            Prefs(this).let {
                when {
                    it.demoMode -> "RESQGO demo mode is on"
                    it.testMode -> "RESQGO test mode is on"
                    else -> "RESQGO is watching your ride"
                }
            }
        )
        .setContentText("If you crash and can't respond, your contacts get your location.")
        .setContentIntent(openScreen(MainActivity::class.java, 10))
        .setOngoing(true)
        .addAction(button("Stop ride", serviceIntent(ACTION_STOP, 11)))
        .build()

    private fun alarmNotification(secs: Int) = Notification.Builder(this, CH_ALARM)
        .setSmallIcon(R.drawable.ic_stat)
        .setContentTitle(if (alarmIsTest) "Test alarm" else "Possible crash detected")
        .setContentText("Tap I'm OK, or SOS goes to your contacts in $secs s.")
        .setCategory(Notification.CATEGORY_ALARM)
        .setFullScreenIntent(openScreen(AlarmActivity::class.java, 20), true)
        .setContentIntent(openScreen(AlarmActivity::class.java, 21))
        .setOngoing(true)
        .addAction(button("I'm OK", serviceIntent(ACTION_IM_OK, 22)))
        .build()

    private fun sosNotification() = Notification.Builder(this, CH_ALARM)
        .setSmallIcon(R.drawable.ic_stat)
        .setContentTitle("SOS sent to your contacts")
        .setContentText("Your location goes out again every 2 minutes.")
        .setContentIntent(openScreen(AlarmActivity::class.java, 30))
        .setOngoing(true)
        .addAction(button("I'm safe", serviceIntent(ACTION_IM_SAFE, 31)))
        .build()

    companion object {
        const val ACTION_START = "com.resqgo.app.START"
        const val ACTION_STOP = "com.resqgo.app.STOP"
        const val ACTION_IM_OK = "com.resqgo.app.IM_OK"
        const val ACTION_SOS_NOW = "com.resqgo.app.SOS_NOW"
        const val ACTION_IM_SAFE = "com.resqgo.app.IM_SAFE"
        const val ACTION_TEST_ALARM = "com.resqgo.app.TEST_ALARM"
        private const val CH_RIDE = "ride"
        private const val CH_ALARM = "alarm"
        private const val ID_RIDE = 1
        private const val ID_ALARM = 2
        private const val BUMP_G = 2.5f
        private const val TRACE_POINTS = 80 // 8 seconds at ten a second
        private const val MAX_BUMP_FILES = 20 // per ride, so a bad road doesn't flood the upload
    }
}
