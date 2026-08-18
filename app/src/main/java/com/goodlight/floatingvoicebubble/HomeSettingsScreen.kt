package com.goodlight.floatingvoicebubble

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goodlight.floatingvoicebubble.diagnostics.DiagnosticReport
import com.goodlight.floatingvoicebubble.diagnostics.DiagnosticStatus
import com.goodlight.floatingvoicebubble.diagnostics.SelfDiagnostics

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeSettingsScreen(
    activity: MainActivity,
    microphoneGranted: Boolean,
    accessibilityEnabled: Boolean,
    refreshRevision: Int,
    onRuntimeStatusChanged: () -> Unit,
    onOpenDetailedSettings: () -> Unit,
) {
    val store = remember(activity) { SettingsStore(activity) }
    var settings by remember { mutableStateOf(store.load()) }
    var diagnosticBusy by remember { mutableStateOf(false) }
    var diagnosticReport by remember { mutableStateOf<DiagnosticReport?>(null) }
    var diagnosticMessage by remember { mutableStateOf("") }

    LaunchedEffect(refreshRevision) {
        settings = store.load()
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onRuntimeStatusChanged()
    }
    val ready = microphoneGranted && accessibilityEnabled

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("home-settings-scroll")
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("compact-home-header"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Floating VoiceBubble",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (ready) "● 準備OK" else "● 要設定",
                color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onOpenDetailedSettings) { Text("詳細") }
        }

        if (!ready) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (!microphoneGranted) {
                    OutlinedButton(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                        Text("マイク許可")
                    }
                }
                if (!accessibilityEnabled) {
                    OutlinedButton(onClick = { activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                        Text("入力権限")
                    }
                }
            }
        }

        QuickCorrectionControls(
            activity = activity,
            refreshRevision = refreshRevision,
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("音声認識", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                finalAsrLabel(settings.finalAsrMode),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RecognitionMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.recognitionMode == mode,
                    onClick = { settings = store.update { it.copy(recognitionMode = mode) } },
                    label = { Text(recognitionLabel(mode)) },
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            HomeToggleChip("完全オフライン", settings.offlineMode) { checked ->
                settings = store.update { it.copy(offlineMode = checked) }
            }
            HomeToggleChip("無音で自動確定", settings.autoStop) { checked ->
                settings = store.update { it.copy(autoStop = checked) }
            }
            FinalAsrMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.finalAsrMode == mode,
                    onClick = { settings = store.update { it.copy(finalAsrMode = mode) } },
                    label = { Text(finalAsrLabel(mode)) },
                )
            }
        }

        HorizontalDivider()

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            OutlinedButton(onClick = { activity.startActivity(Intent(activity, DictionaryActivity::class.java)) }) {
                Text("個人辞書")
            }
            OutlinedButton(onClick = { activity.startActivity(Intent(activity, AppProfilesActivity::class.java)) }) {
                Text("アプリ別")
            }
            Button(
                onClick = {
                    diagnosticBusy = true
                    diagnosticMessage = "診断しています…"
                    Thread({
                        runCatching { SelfDiagnostics(activity).run(includeExternalProbes = true) }
                            .onSuccess { report -> activity.runOnUiThread {
                                diagnosticReport = report
                                diagnosticBusy = false
                                val failure = report.items.firstOrNull { it.status == DiagnosticStatus.FAIL }
                                diagnosticMessage = if (failure == null) {
                                    report.summary()
                                } else {
                                    "${report.summary()}\n${failure.id}: ${failure.detail}"
                                }
                            } }
                            .onFailure { failure -> activity.runOnUiThread {
                                diagnosticBusy = false
                                diagnosticMessage = "診断失敗: ${failure.message ?: failure.javaClass.simpleName}"
                            } }
                    }, "VoiceBubble-OneClickDiagnostics").start()
                },
                enabled = !diagnosticBusy,
            ) { Text(if (diagnosticBusy) "診断中…" else "全自動診断") }
            OutlinedButton(onClick = { activity.startActivity(Intent(activity, AdvancedToolsActivity::class.java)) }) {
                Text("管理・検証")
            }
            diagnosticReport?.let { report ->
                TextButton(onClick = { copyDiagnostic(activity, report) }) { Text("診断結果をコピー") }
            }
        }

        if (diagnosticMessage.isNotBlank()) {
            Text(
                diagnosticMessage,
                color = if (diagnosticMessage.contains("FAIL 0")) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun HomeToggleChip(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    FilterChip(
        selected = checked,
        onClick = { onChecked(!checked) },
        label = { Text(label) },
    )
}

private fun recognitionLabel(mode: RecognitionMode): String = when (mode) {
    RecognitionMode.AUTO -> "自動"
    RecognitionMode.SYSTEM -> "Android"
    RecognitionMode.ON_DEVICE -> "Android端末内"
    RecognitionMode.SHERPA_STREAMING -> "ローカルASR"
}

private fun finalAsrLabel(mode: FinalAsrMode): String = when (mode) {
    FinalAsrMode.LIVE_RESULT -> "最終ASR: ライブ"
    FinalAsrMode.REAZON_SPEECH -> "最終ASR: Reazon"
}

private fun copyDiagnostic(activity: MainActivity, report: DiagnosticReport) {
    activity.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("Floating VoiceBubble diagnostics", report.toRedactedJson()))
}
