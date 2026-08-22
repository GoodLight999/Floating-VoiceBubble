package com.goodlight.floatingvoicebubble

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    private var accessibilityEnabled by mutableStateOf(false)
    private var microphoneGranted by mutableStateOf(false)
    private var settingsRevision by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshRuntimeStatus()
        setContent {
            VoiceBubbleTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing),
                    ) {
                        HomeSettingsScreen(
                            activity = this@MainActivity,
                            microphoneGranted = microphoneGranted,
                            accessibilityEnabled = accessibilityEnabled,
                            refreshRevision = settingsRevision,
                            onRuntimeStatusChanged = ::refreshRuntimeStatus,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshRuntimeStatus()
        settingsRevision += 1
    }

    private fun refreshRuntimeStatus() {
        microphoneGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val expected = ComponentName(
            this,
            com.goodlight.floatingvoicebubble.accessibility.VoiceBubbleAccessibilityService::class.java,
        ).flattenToString()
        accessibilityEnabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty().split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
