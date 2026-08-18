package com.goodlight.floatingvoicebubble

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    private var accessibilityEnabled by mutableStateOf(false)
    private var microphoneGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshRuntimeStatus()
        setContent {
            VoiceBubbleTheme {
                var showDetailed by remember { mutableStateOf(false) }
                var showDictionary by remember { mutableStateOf(false) }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    if (showDictionary) {
                        DictionaryManagerScreen(
                            activity = this@MainActivity,
                            onBack = { showDictionary = false },
                        )
                    } else {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .windowInsetsPadding(WindowInsets.safeDrawing),
                        ) {
                            QuickCorrectionControls(this@MainActivity)
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = { showDictionary = true }) { Text("個人辞書") }
                            }
                            Box(Modifier.weight(1f)) {
                                if (showDetailed) {
                                    Column(Modifier.fillMaxSize()) {
                                        TextButton(onClick = { showDetailed = false }) { Text("← かんたん設定へ戻る") }
                                        Box(Modifier.weight(1f)) {
                                            VoiceBubbleSettingsScreen(
                                                activity = this@MainActivity,
                                                microphoneGranted = microphoneGranted,
                                                accessibilityEnabled = accessibilityEnabled,
                                                onRuntimeStatusChanged = ::refreshRuntimeStatus,
                                            )
                                        }
                                    }
                                } else {
                                    HomeSettingsScreen(
                                        activity = this@MainActivity,
                                        microphoneGranted = microphoneGranted,
                                        accessibilityEnabled = accessibilityEnabled,
                                        onRuntimeStatusChanged = ::refreshRuntimeStatus,
                                        onOpenDetailedSettings = { showDetailed = true },
                                    )
                                }
                            }
                        }
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
