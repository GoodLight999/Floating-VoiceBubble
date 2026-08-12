package com.goodlight.floatingvoicebubble

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.goodlight.floatingvoicebubble.accessibility.VoiceBubbleAccessibilityService
import com.goodlight.floatingvoicebubble.dictionary.PersonalDictionary
import com.goodlight.floatingvoicebubble.model.ModelImporter
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var settingsStore: SettingsStore
    private var accessibilityEnabled by mutableStateOf(false)
    private var microphoneGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(this)
        refreshRuntimeStatus()
        setContent { VoiceBubbleTheme { SettingsScreen() } }
    }

    override fun onResume() {
        super.onResume()
        refreshRuntimeStatus()
    }

    private fun refreshRuntimeStatus() {
        microphoneGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val expected = ComponentName(this, VoiceBubbleAccessibilityService::class.java).flattenToString()
        accessibilityEnabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { it.equals(expected, ignoreCase = true) }
    }

    @Composable
    private fun SettingsScreen() {
        var settings by remember { mutableStateOf(settingsStore.load()) }
        var apiKey by remember { mutableStateOf(settingsStore.apiKey()) }
        var endpointDraft by remember { mutableStateOf(settings.byokEndpoint) }
        var modelDraft by remember { mutableStateOf(settings.byokModel) }
        var message by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var dictionaryCount by remember { mutableLongStateOf(PersonalDictionary(this).use { it.count() }) }

        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshRuntimeStatus() }
        val modelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            busy = true
            message = "Gemmaモデルを端末内へコピーしています…"
            Thread {
                runCatching { ModelImporter(this).importGemma(uri) }
                    .onSuccess { file ->
                        val updated = settingsStore.update { it.copy(gemmaModelPath = file.absolutePath) }
                        runOnUiThread {
                            settings = updated
                            busy = false
                            message = "Gemmaモデルを読み込みました: ${file.name}"
                        }
                    }
                    .onFailure { error -> runOnUiThread {
                        busy = false
                        message = error.message ?: "Gemmaモデルを読み込めませんでした。"
                    } }
            }.start()
        }
        val dictionaryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            busy = true
            message = "個人辞書を読み込んでいます…"
            Thread {
                runCatching {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("辞書ファイルを開けませんでした。")
                    PersonalDictionary(this).use { dictionary ->
                        val result = dictionary.importText(text)
                        result to dictionary.count()
                    }
                }.onSuccess { (result, count) -> runOnUiThread {
                    dictionaryCount = count
                    busy = false
                    message = "個人辞書: ${result.imported}件を読み込み、${result.skipped}件をスキップしました。"
                } }.onFailure { error -> runOnUiThread {
                    busy = false
                    message = error.message ?: "個人辞書を読み込めませんでした。"
                } }
            }.start()
        }

        Column(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Floating VoiceBubble", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "認識中の文字を即座に見せ、完成文だけを一度で入力します。クラウドと完全オフラインは同じ操作感です。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )

            Section("端末の準備") {
                StatusLine("マイク", microphoneGranted)
                StatusLine("アクセシビリティ入力", accessibilityEnabled)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!microphoneGranted) Button(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("マイクを許可") }
                    if (!accessibilityEnabled) OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("アクセシビリティを開く") }
                }
            }

            Section("動作モード") {
                SettingSwitch(
                    title = "完全オフライン",
                    detail = "ネットワークを使わず、端末内認識 + Gemmaで認識から入力まで完結します。クラウドへ黙ってフォールバックしません。",
                    checked = settings.offlineMode,
                    onChecked = { checked -> settings = settingsStore.update { it.copy(offlineMode = checked) } },
                )
                HorizontalDivider()
                Text("リアルタイム認識", fontWeight = FontWeight.SemiBold)
                ChoiceRow(
                    values = RecognitionMode.entries,
                    selected = settings.recognitionMode,
                    label = { when (it) { RecognitionMode.AUTO -> "自動"; RecognitionMode.SYSTEM -> "システム"; RecognitionMode.ON_DEVICE -> "端末内" } },
                    onSelect = { value -> settings = settingsStore.update { it.copy(recognitionMode = value) } },
                )
                SettingSwitch(
                    title = "無音で自動終了",
                    detail = "発話後の静寂を端末内で検出して確定します。バブルを再度タップして手動終了もできます。",
                    checked = settings.autoStop,
                    onChecked = { checked -> settings = settingsStore.update { it.copy(autoStop = checked) } },
                )
            }

            Section("最終補正") {
                ChoiceRow(
                    values = CorrectionMode.entries,
                    selected = settings.correctionMode,
                    label = { when (it) { CorrectionMode.AUTO -> "自動"; CorrectionMode.BYOK -> "BYOK"; CorrectionMode.GEMMA -> "Gemma"; CorrectionMode.NONE -> "補正なし" } },
                    onSelect = { value -> settings = settingsStore.update { it.copy(correctionMode = value) } },
                )
                Text("補正器には『最小訂正』だけを許し、編集量が不自然に大きい出力は破棄して語調を保護します。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Section("BYOK") {
                OutlinedTextField(value = endpointDraft, onValueChange = { endpointDraft = it }, label = { Text("OpenAI互換 chat/completions URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = modelDraft, onValueChange = { modelDraft = it }, label = { Text("モデル名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API key（Android Keystoreで暗号化）") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        settings = settingsStore.update { it.copy(byokEndpoint = endpointDraft.trim(), byokModel = modelDraft.trim()) }
                        settingsStore.setApiKey(apiKey.trim())
                        message = "BYOK設定を保存しました。"
                    },
                    enabled = !busy && endpointDraft.startsWith("https://"),
                ) { Text("BYOK設定を保存") }
            }

            Section("端末内Gemma") {
                Text(if (settings.gemmaModelPath.isBlank()) "モデル未設定" else "${File(settings.gemmaModelPath).name}  •  端末内保存", color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChoiceRow(
                    values = GemmaBackend.entries,
                    selected = settings.gemmaBackend,
                    label = { when (it) { GemmaBackend.AUTO -> "GPU→CPU"; GemmaBackend.GPU -> "GPU"; GemmaBackend.CPU -> "CPU" } },
                    onSelect = { value -> settings = settingsStore.update { it.copy(gemmaBackend = value) } },
                )
                OutlinedButton(onClick = { modelLauncher.launch(arrayOf("application/octet-stream", "*/*")) }, enabled = !busy) { Text(".litertlm モデルを読み込む") }
            }

            Section("個人辞書") {
                Text("$dictionaryCount 語", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("保存件数に小さな上限は設けません。CSV/TSV: term, reading, aliases(|区切り), weight。上位語はASRバイアスにも使います。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { dictionaryLauncher.launch(arrayOf("text/*", "text/csv", "text/tab-separated-values")) }, enabled = !busy) { Text("辞書をインポート") }
            }

            Section("診断 / ベンチマーク") {
                SettingSwitch(
                    title = "セッショントレースを保存",
                    detail = "同一音声でASRを比較できるよう、WAV・N-best・raw/final・レイテンシを最大30セッション端末内へ残します。",
                    checked = settings.keepSessionTraces,
                    onChecked = { checked -> settings = settingsStore.update { it.copy(keepSessionTraces = checked) } },
                )
            }

            message?.let { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text(it, modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
            } }
            Spacer(Modifier.height(16.dp))
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                content()
            }
        }
    }

    @Composable
    private fun StatusLine(label: String, enabled: Boolean) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(if (enabled) "準備完了" else "未設定", color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = checked, onCheckedChange = onChecked)
        }
    }

    @Composable
    private fun <T> ChoiceRow(values: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            values.forEach { value -> FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label(value)) }) }
        }
    }
}

@Composable
private fun VoiceBubbleTheme(content: @Composable () -> Unit) {
    val light = lightColorScheme(primary = Color(0xFF4257B2), onPrimary = Color.White, background = Color(0xFFF7F7F5), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFEDEEF2))
    val dark = darkColorScheme(primary = Color(0xFFAEBBFF), background = Color(0xFF111318), surface = Color(0xFF191B20), surfaceVariant = Color(0xFF24272E))
    MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) dark else light, content = content)
}
