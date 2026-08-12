package com.goodlight.floatingvoicebubble

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private var accessibilityEnabled by mutableStateOf(false)
    private var microphoneGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshRuntimeStatus()
        setContent {
            VoiceBubbleTheme {
                Box(Modifier.fillMaxSize()) {
                    VoiceBubbleSettingsScreen(
                        activity = this@MainActivity,
                        microphoneGranted = microphoneGranted,
                        accessibilityEnabled = accessibilityEnabled,
                        onRuntimeStatusChanged = ::refreshRuntimeStatus,
                    )
                    FloatingActionButton(
                        onClick = { startActivity(Intent(this@MainActivity, AdvancedToolsActivity::class.java)) },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                    ) {
                        Text("管理")
                    }
                }
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
