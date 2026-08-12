package com.goodlight.floatingvoicebubble

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private var accessibilityEnabled by mutableStateOf(false)
    private var microphoneGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshRuntimeStatus()
        setContent {
            VoiceBubbleTheme {
                VoiceBubbleSettingsScreen(
                    activity = this,
                    microphoneGranted = microphoneGranted,
                    accessibilityEnabled = accessibilityEnabled,
                    onRuntimeStatusChanged = ::refreshRuntimeStatus,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshRuntimeStatus()
    }

    private fun refreshRuntimeStatus() {
        microphoneGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val expected = ComponentName(this, com.goodlight.floatingvoicebubble.accessibility.VoiceBubbleAccessibilityService::class.java)
            .flattenToString()
        accessibilityEnabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty().split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
