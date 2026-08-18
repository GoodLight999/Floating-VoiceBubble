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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goodlight.floatingvoicebubble.diagnostics.DiagnosticReport
import com.goodlight.floatingvoicebubble.diagnostics.SelfDiagnostics

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeSettingsScreen(
    activity: MainActivity,
    microphoneGranted: Boolean,
    accessibilityEnabled: Boolean,
    onRuntimeStatusChanged: () -> Unit,
    onOpenDetailedSettings: () -> Unit,
) {
    val store = remember(activity) { SettingsStore(activity) }
    var settings by remember { mutableStateOf(store.load()) }
    var diagnosticBusy by remember { mutableStateOf(false) }
    var diagnosticReport by remember { mutableStateOf<DiagnosticReport?>(null) }
    var diagnosticMessage by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onRuntimeStatusChanged()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Floating VoiceBubble", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row {
                TextButton(onClick = { activity.startActivity(Intent(activity, DictionaryActivity::class.java)) }) {
                    Text("辞書")
                }
                TextButton(onClick = onOpenDetailedSettings) { Text("詳細") }
            }
        }

        if (microphoneGranted && accessibilityEnabled) {
            Text(
                "● 準備OK  キーボードを開けばバブルを使えます",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("初回設定", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (!microphoneGranted) StatusRow("マイク", false)
                    if (!accessibilityEnabled) StatusRow("他のアプリへ入力", false)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!microphoneGranted) {
                            Button(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("マイクを許可") }
                        }
                        if (!accessibilityEnabled) {
                            OutlinedButton(onClick = { activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                                Text("入力権限を設定")
                            }
                        }
                    }
                }
            }
        }

        Text("音声入力", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            RecognitionMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.recognitionMode == mode,
                    onClick = { settings = store.update { it.copy(recognitionMode = mode) } },
                    label = { Text(recognitionLabel(mode)) },
                )
            }
        }
        CompactSwitchRow(
            title = "完全オフライン",
            detail = "音声認識も補正もクラウドへ送らない",
            checked = settings.offlineMode,
        ) { checked -> settings = store.update { it.copy(offlineMode = checked) } }
        CompactSwitchRow(
            title = "無音で自動確定",
            detail = "OFFでもバブルを再タップすれば確定",
            checked = settings.autoStop,
        ) { checked -> settings = store.update { it.copy(autoStop = checked) } }

        HorizontalDivider()
        QuickCorrectionControls(activity = activity, modifier = Modifier.fillMaxWidth())

        Text("診断・管理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(
                onClick = {
                    diagnosticBusy = true
                    diagnosticMessage = "診断しています…"
                    Thread({
                        runCatching { SelfDiagnostics(activity).run(includeExternalProbes = true) }
                            .onSuccess { report -> activity.runOnUiThread {
                                diagnosticReport = report
                                diagnosticBusy = false
                                diagnosticMessage = report.summary()
                            } }
                            .onFailure { failure -> activity.runOnUiThread {
                                diagnosticBusy = false
                                diagnosticMessage = "診断失敗: ${failure.message ?: failure.javaClass.simpleName}"
                            } }
                    }, "VoiceBubble-OneClickDiagnostics").start()
                },
                enabled = !diagnosticBusy,
            ) { Text(if (diagnosticBusy) "診断中…" else "全自動診断") }
            diagnosticReport?.let { report ->
                OutlinedButton(onClick = { copyDiagnostic(activity, report) }) { Text("結果をコピー") }
            }
            OutlinedButton(onClick = { activity.startActivity(Intent(activity, AdvancedToolsActivity::class.java)) }) {
                Text("管理・検証")
            }
        }
        if (diagnosticMessage.isNotBlank()) {
            Text(
                diagnosticMessage,
                color = if (diagnosticMessage.contains("FAIL 0")) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "普段使う設定はこの画面で完結します。モデル導入・最終ASR・ベンチマーク等だけ「詳細」「管理・検証」に分離しています。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun StatusRow(label: String, ready: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        Text(
            if (ready) "OK" else "要設定",
            color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun CompactSwitchRow(title: String, detail: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

private fun recognitionLabel(mode: RecognitionMode): String = when (mode) {
    RecognitionMode.AUTO -> "自動"
    RecognitionMode.SYSTEM -> "Android"
    RecognitionMode.ON_DEVICE -> "Android端末内"
    RecognitionMode.SHERPA_STREAMING -> "ローカルASR"
}

private fun copyDiagnostic(activity: MainActivity, report: DiagnosticReport) {
    activity.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("Floating VoiceBubble diagnostics", report.toRedactedJson()))
}
