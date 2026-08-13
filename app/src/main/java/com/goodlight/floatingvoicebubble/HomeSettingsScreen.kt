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
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Floating VoiceBubble", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "キーボードを開くとバブルが出ます。タップして話し、もう一度タップするか無音になると完成文を入力します。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("使う準備", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                StatusRow("マイク", microphoneGranted)
                StatusRow("他のアプリへ入力する権限", accessibilityEnabled)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

        Text("音声認識", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "迷ったら「自動」のままで構いません。完全オフラインをONにした場合だけ、ダウンロード済みの端末内ASRを使います。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RecognitionMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.recognitionMode == mode,
                    onClick = { settings = store.update { it.copy(recognitionMode = mode) } },
                    label = { Text(recognitionLabel(mode)) },
                )
            }
        }

        SimpleSwitchRow(
            title = "完全オフライン",
            detail = "ONにすると音声も補正もクラウドへ送りません。端末内モデルが未導入なら開始前に止めて理由を表示します。",
            checked = settings.offlineMode,
        ) { checked -> settings = store.update { it.copy(offlineMode = checked) } }
        HorizontalDivider()
        SimpleSwitchRow(
            title = "話し終わったら自動で確定",
            detail = "ONなら無音を検出して自動終了します。OFFでもバブルをもう一度タップすれば確定できます。",
            checked = settings.autoStop,
        ) { checked -> settings = store.update { it.copy(autoStop = checked) } }

        HorizontalDivider()
        Text("困ったとき", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "全自動診断は、権限・音声認識・保存領域・補正設定・設定済みモデル・BYOK接続をまとめて確認します。APIキーや辞書本文は結果へ出しません。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
        }
        if (diagnosticMessage.isNotBlank()) {
            Text(
                diagnosticMessage,
                color = if (diagnosticMessage.contains("FAIL 0")) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        HorizontalDivider()
        Text("詳しい設定", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "モデル導入・最終ASR・辞書・ベンチマークなど、普段触らない設定はここにまとめています。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { activity.startActivity(Intent(activity, AdvancedToolsActivity::class.java)) }) {
                Text("管理・検証")
            }
            OutlinedButton(onClick = onOpenDetailedSettings) { Text("詳細設定") }
        }
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
private fun SimpleSwitchRow(title: String, detail: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

private fun recognitionLabel(mode: RecognitionMode): String = when (mode) {
    RecognitionMode.AUTO -> "自動"
    RecognitionMode.SYSTEM -> "Android音声認識"
    RecognitionMode.ON_DEVICE -> "Android端末内"
    RecognitionMode.SHERPA_STREAMING -> "ダウンロード済みASR"
}

private fun copyDiagnostic(activity: MainActivity, report: DiagnosticReport) {
    activity.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("Floating VoiceBubble diagnostics", report.toRedactedJson()))
}