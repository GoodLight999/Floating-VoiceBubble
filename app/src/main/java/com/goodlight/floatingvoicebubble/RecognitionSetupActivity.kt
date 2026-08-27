package com.goodlight.floatingvoicebubble

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.goodlight.floatingvoicebubble.speech.GeminiTranscribeProbe

class RecognitionSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VoiceBubbleTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    RecognitionSetupScreen(this)
                }
            }
        }
    }
}

@Composable
private fun RecognitionSetupScreen(activity: RecognitionSetupActivity) {
    val store = remember(activity) { SettingsStore(activity) }
    var settings by remember { mutableStateOf(store.load()) }
    var endpoint by remember { mutableStateOf(settings.recognitionApiEndpoint) }
    var model by remember { mutableStateOf(settings.recognitionApiModel) }
    var apiKey by remember { mutableStateOf(store.recognitionApiKey()) }
    var presetOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    fun persistConfiguration(): Boolean {
        val cleanEndpoint = endpoint.trim()
        val cleanModel = model.trim()
        if (!cleanEndpoint.startsWith("wss://") && !cleanEndpoint.startsWith("https://")) {
            message = "WebSocket URLは wss:// または https:// で入力してください。"
            return false
        }
        if (cleanModel.isBlank()) {
            message = "モデルIDを入力してください。"
            return false
        }
        settings = store.update {
            it.copy(
                recognitionApiEndpoint = cleanEndpoint,
                recognitionApiModel = cleanModel,
            )
        }
        store.setRecognitionApiKey(apiKey.trim())
        return true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "クラウド音声認識",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "URL・モデルID・APIキーを直接入力できます。候補を選んでも入力欄が埋まるだけで、あとから自由に変更できます。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "現在の通信形式はGemini Live互換です。認識はVERBATIM（忠実な書き起こし）固定で、フィラー削除・句読点・言い直し修復はFloating VoiceBubble側の補正設定で制御します。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Box {
            OutlinedButton(onClick = { presetOpen = true }, enabled = !busy) {
                Text("接続先の候補から入力")
            }
            DropdownMenu(expanded = presetOpen, onDismissRequest = { presetOpen = false }) {
                ApiProviderPresets.recognition.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.label) },
                        onClick = {
                            endpoint = preset.endpoint
                            model = preset.model
                            presetOpen = false
                            message = "${preset.label} のURLとモデルIDを入力しました。必要ならそのまま編集できます。"
                        },
                    )
                }
            }
        }

        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it; message = "" },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("WebSocket URL") },
            singleLine = true,
            supportingText = { Text("wss:// または https://。候補にない接続先も手入力できます。") },
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it; message = "" },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("モデルID") },
            singleLine = true,
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; message = "" },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("APIキー") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = { Text("Android Keystoreで暗号化して保存します。URLへキーを埋め込む必要はありません。") },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    if (persistConfiguration()) {
                        message = if (apiKey.isBlank()) "設定を保存し、APIキーを削除しました。" else "設定を保存しました。"
                    }
                },
                enabled = !busy,
            ) {
                Text("保存")
            }
            Button(
                onClick = {
                    if (apiKey.isBlank()) {
                        message = "APIキーを入力してください。"
                        return@Button
                    }
                    if (!persistConfiguration()) return@Button
                    val cleanKey = apiKey.trim()
                    val cleanEndpoint = endpoint.trim()
                    val cleanModel = model.trim()
                    busy = true
                    message = "APIへ接続しています…"
                    Thread({
                        runCatching {
                            GeminiTranscribeProbe.run(
                                apiKey = cleanKey,
                                endpoint = cleanEndpoint,
                                model = cleanModel,
                            )
                        }.onSuccess { result ->
                            activity.runOnUiThread {
                                busy = false
                                message = "API接続テスト成功（${result.elapsedMs}ms）。setupだけを確認し、マイク音声は送っていません。"
                            }
                        }.onFailure { failure ->
                            activity.runOnUiThread {
                                busy = false
                                message = "API接続テスト失敗: ${failure.message ?: failure.javaClass.simpleName}"
                            }
                        }
                    }, "VoiceBubble-CloudRecognitionProbe").start()
                },
                enabled = !busy,
            ) {
                Text(if (busy) "接続中…" else "API接続テスト")
            }
        }

        HorizontalDivider()

        val active = settings.recognitionMode == RecognitionMode.GEMINI_TRANSCRIBE
        Text(
            if (active) "現在の音声認識: クラウドAPI / ${settings.recognitionApiModel}" else "現在は別の音声認識を使用しています。",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
        Button(
            onClick = {
                if (apiKey.isBlank()) {
                    message = "クラウド音声認識を使うにはAPIキーを入力してください。"
                    return@Button
                }
                if (!persistConfiguration()) return@Button
                settings = store.update { it.copy(recognitionMode = RecognitionMode.GEMINI_TRANSCRIBE) }
                message = "このクラウド音声認識設定を使用します。"
            },
            enabled = !busy,
        ) {
            Text(if (active) "設定を保存して使用" else "この設定を音声認識に使う")
        }

        if (message.isNotBlank()) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = if (
                    message.contains("失敗") ||
                    message.contains("入力してください") ||
                    message.contains("で入力してください")
                ) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        Text(
            "『通信しない』が有効な間はこの設定より端末内ストリーミング音声認識を優先し、クラウドへ音声を送信しません。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
