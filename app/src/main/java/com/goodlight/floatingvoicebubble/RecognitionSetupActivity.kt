package com.goodlight.floatingvoicebubble

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
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
    var apiKey by remember { mutableStateOf(store.geminiTranscribeApiKey()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Gemini 3.5 Transcribe",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Googleのリアルタイム音声認識をBYOKで使います。音声は16kHz PCMで直接送信し、途中結果を吹き出しへ表示します。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "認識はVERBATIM（忠実な書き起こし）固定です。『えー』等の削除、句読点、言い直し修復はFloating VoiceBubble側の補正設定で個別に制御します。個人辞書から優先語を最大100件送ります。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; message = "" },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Google AI APIキー") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = { Text("端末のAndroid Keystoreで暗号化して保存します。アプリ共通キーは使用しません。") },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    store.setGeminiTranscribeApiKey(apiKey.trim())
                    message = if (apiKey.isBlank()) "APIキーを削除しました。" else "APIキーを保存しました。"
                },
                enabled = !busy,
            ) {
                Text("保存")
            }
            Button(
                onClick = {
                    val candidate = apiKey.trim()
                    if (candidate.isBlank()) {
                        message = "APIキーを入力してください。"
                        return@Button
                    }
                    busy = true
                    message = "APIへ接続しています…"
                    Thread({
                        runCatching { GeminiTranscribeProbe.run(candidate) }
                            .onSuccess { result ->
                                store.setGeminiTranscribeApiKey(candidate)
                                activity.runOnUiThread {
                                    busy = false
                                    message = "API接続テスト成功（${result.elapsedMs}ms）。音声そのものは送っていません。"
                                }
                            }
                            .onFailure { failure ->
                                activity.runOnUiThread {
                                    busy = false
                                    message = "API接続テスト失敗: ${failure.message ?: failure.javaClass.simpleName}"
                                }
                            }
                    }, "VoiceBubble-GeminiTranscribeProbe").start()
                },
                enabled = !busy,
            ) {
                Text(if (busy) "接続中…" else "API接続テスト")
            }
        }

        HorizontalDivider()

        val active = settings.recognitionMode == RecognitionMode.GEMINI_TRANSCRIBE
        Text(
            if (active) "現在の音声認識: Gemini 3.5 Transcribe" else "現在は別の音声認識を使用しています。",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
        Button(
            onClick = {
                val candidate = apiKey.trim()
                if (candidate.isBlank()) {
                    message = "Geminiを使うにはAPIキーを入力してください。"
                    return@Button
                }
                store.setGeminiTranscribeApiKey(candidate)
                settings = store.update { it.copy(recognitionMode = RecognitionMode.GEMINI_TRANSCRIBE) }
                message = "Gemini 3.5 Transcribeを音声認識に設定しました。"
            },
            enabled = !active && !busy,
        ) {
            Text("Gemini 3.5 Transcribeを使う")
        }

        if (message.isNotBlank()) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = if (message.contains("失敗") || message.contains("入力してください")) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        Text(
            "『通信しない』を有効にした場合は、この設定より端末内ストリーミング音声認識が優先され、Geminiへ音声を送信しません。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
