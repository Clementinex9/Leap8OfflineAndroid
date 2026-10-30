package com.leap8.offline

import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.leap8.offline.ui.Leap8App
import com.leap8.offline.ui.theme.Leap8Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestSmoothRefreshRate()
        setContent {
            Leap8Theme { Leap8App() }
        }
    }

    @Suppress("DEPRECATION")
    private fun requestSmoothRefreshRate() {
        val screen = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
        val current = screen?.mode ?: return
        val desired = screen.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight &&
                it.refreshRate <= 120.1f
        }.maxByOrNull { it.refreshRate }?.refreshRate ?: current.refreshRate
        // A window hint, not a device-wide setting; the system retains the final choice.
        window.attributes = window.attributes.apply { preferredRefreshRate = desired }
        if (Build.VERSION.SDK_INT >= 35) {
            window.setFrameRateBoostOnTouchEnabled(true)
            window.decorView.setRequestedFrameRate(desired)
        }
    }
}
