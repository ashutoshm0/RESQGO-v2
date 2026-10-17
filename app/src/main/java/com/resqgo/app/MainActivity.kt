package com.resqgo.app

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ResqTheme { App() } }
    }

    override fun onResume() {
        super.onResume()
        Resumes.count++ // re-check the setup list after the rider comes back from Settings
        Uploader.flushSoon(this) // send any pilot data waiting from earlier rides
    }
}

/** Shown over the lock screen when a crash alarm fires while the phone is locked or in another app. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        enableEdgeToEdge()
        setContent {
            ResqTheme {
                val phase by RideState.phase.collectAsState()
                if (phase == Phase.ALARM || phase == Phase.SOS) AlarmScreen(phase)
                else LaunchedEffect(Unit) { finish() }
            }
        }
    }
}
