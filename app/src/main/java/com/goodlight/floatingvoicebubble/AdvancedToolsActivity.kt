package com.goodlight.floatingvoicebubble

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.goodlight.floatingvoicebubble.model.AsrModelStore
import com.goodlight.floatingvoicebubble.model.CatalogModelKind
import com.goodlight.floatingvoicebubble.model.FinalAsrModelStore
import com.goodlight.floatingvoicebubble.model.InstalledOfficialModel
import com.goodlight.floatingvoicebubble.model.ModelInstallProgress
import com.goodlight.floatingvoicebubble.model.OfficialModelCatalog
import com.goodlight.floatingvoicebubble.model.OfficialModelEntry
import com.goodlight.floatingvoicebubble.model.OfficialModelInstaller
import java.util.Locale

class AdvancedToolsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VoiceBubbleTheme {
                OfflineRecognitionSetupScreen(this)
            }
        }
    }
}

@Composable
private fun OfflineRecognitionSetupScreen(activity: AdvancedToolsActivity) {
    val settingsStore = remember { SettingsStore(activity) }
    val streamingStore = remember { AsrModelStore(activity) }
    val finalStore = remember { FinalAsrModelStore(activity) }

    var settings by remember { mutableStateOf(settingsStore.load()) }
    var message by remember { mutableStateOf("") }
    var busyId by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<ModelInstallProgress?>(null) }
    var installedStreaming by remember { mutableStateOf(streamingStore.listInstalled().map { it.id }.toSet()) }
    var finalInstalled by remember { mutableStateOf(finalStore.resolve(FinalAsrModelStore.MODEL_ID) != null) }

    val recognitionModels = OfficialModelCatalog.all.filter { it.kind != CatalogModelKind.GEMMA }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("オフライン音声認識", style = MaterialTheme.typography.titleLarge)
                Text(
                    "通信せずに音声を文字にするためのモデルを導入します。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { activity.finish() }) { Text("戻る") }
        }

        if (message.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }

        SectionCard("発話中のリアルタイム認識") {
            Text(
                installedStreamingModelLabel(settings, streamingStore),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            recognitionModels.filter { it.kind == CatalogModelKind.STREAMING_ASR }.forEach { entry ->
                val installed = entry.id in installedStreaming
                RecognitionModelRow(
                    entry = entry,
                    installed = installed,
                    selected = settings.streamingAsrModelId == entry.id,
                    busy = busyId != null,
                    progress = progress.takeIf { busyId == entry.id },
                    onInstall = {
                        installModel(
                            activity = activity,
                            entry = entry,
                            onStart = { busyId = entry.id; progress = null; message = "${entry.title} を取得しています…" },
                            onProgress = { progress = it },
                            onSuccess = { installedModel ->
                                val streaming = installedModel as? InstalledOfficialModel.Streaming
                                if (streaming == null) {
                                    message = "音声認識モデルとして読み込めませんでした。"
                                } else {
                                    settings = settingsStore.update { it.copy(streamingAsrModelId = streaming.model.id) }
                                    installedStreaming = streamingStore.listInstalled().map { it.id }.toSet()
                                    message = "${entry.title} を導入し、使用するモデルに設定しました。"
                                }
                                busyId = null
                                progress = null
                            },
                            onFailure = { error ->
                                busyId = null
                                progress = null
                                message = "導入失敗: $error"
                            },
                        )
                    },
                )
                HorizontalDivider()
            }
        }

        SectionCard("確定時にもう一度認識する") {
            Text(
                if (settings.finalAsrMode == FinalAsrMode.REAZON_SPEECH && finalInstalled) {
                    "ReazonSpeechで確定文を再認識します。"
                } else {
                    "現在は発話中の認識結果をそのまま確定に使います。"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            recognitionModels.filter { it.kind == CatalogModelKind.FINAL_ASR }.forEach { entry ->
                RecognitionModelRow(
                    entry = entry,
                    installed = finalInstalled,
                    selected = settings.finalAsrMode == FinalAsrMode.REAZON_SPEECH && finalInstalled,
                    busy = busyId != null,
                    progress = progress.takeIf { busyId == entry.id },
                    onInstall = {
                        installModel(
                            activity = activity,
                            entry = entry,
                            onStart = { busyId = entry.id; progress = null; message = "${entry.title} を取得しています…" },
                            onProgress = { progress = it },
                            onSuccess = { installedModel ->
                                val final = installedModel as? InstalledOfficialModel.Final
                                if (final == null) {
                                    message = "確定用の音声認識モデルとして読み込めませんでした。"
                                } else {
                                    settings = settingsStore.update {
                                        it.copy(
                                            finalAsrModelId = final.model.id,
                                            finalAsrMode = FinalAsrMode.REAZON_SPEECH,
                                        )
                                    }
                                    finalInstalled = true
                                    message = "${entry.title} を導入し、確定時の再認識を有効にしました。"
                                }
                                busyId = null
                                progress = null
                            },
                            onFailure = { error ->
                                busyId = null
                                progress = null
                                message = "導入失敗: $error"
                            },
                        )
                    },
                )
            }
            if (settings.finalAsrMode == FinalAsrMode.REAZON_SPEECH) {
                Button(
                    enabled = busyId == null,
                    onClick = {
                        settings = settingsStore.update { it.copy(finalAsrMode = FinalAsrMode.LIVE_RESULT) }
                        message = "確定時の再認識をOFFにしました。"
                    },
                ) { Text("確定時の再認識をOFF") }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun RecognitionModelRow(
    entry: OfficialModelEntry,
    installed: Boolean,
    selected: Boolean,
    busy: Boolean,
    progress: ModelInstallProgress?,
    onInstall: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(entry.title, style = MaterialTheme.typography.titleSmall)
        Text(
            when (entry.kind) {
                CatalogModelKind.STREAMING_ASR -> "発話中の文字を端末内でリアルタイム表示します。"
                CatalogModelKind.FINAL_ASR -> "発話終了後にもう一度認識し、確定文の精度向上を狙います。"
                CatalogModelKind.GEMMA -> ""
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Text("必要容量 約 ${formatBytes(entry.estimatedInstalledBytes)}", style = MaterialTheme.typography.bodySmall)
        progress?.let {
            val pct = if (it.totalBytes != null && it.totalBytes > 0L) {
                " ${(it.completedBytes * 100L / it.totalBytes).coerceIn(0L, 100L)}%"
            } else ""
            Text("${it.phase}$pct", style = MaterialTheme.typography.bodySmall)
        }
        Button(enabled = !busy, onClick = onInstall) {
            Text(
                when {
                    selected -> "再取得"
                    installed -> "このモデルを使用"
                    else -> "導入して使用"
                },
            )
        }
    }
}

private fun installModel(
    activity: AdvancedToolsActivity,
    entry: OfficialModelEntry,
    onStart: () -> Unit,
    onProgress: (ModelInstallProgress) -> Unit,
    onSuccess: (InstalledOfficialModel) -> Unit,
    onFailure: (String) -> Unit,
) {
    onStart()
    Thread({
        runCatching {
            OfficialModelInstaller(activity).install(entry) { p -> activity.runOnUiThread { onProgress(p) } }
        }.onSuccess { installed -> activity.runOnUiThread { onSuccess(installed) } }
            .onFailure { failure -> activity.runOnUiThread {
                onFailure(failure.message ?: failure.javaClass.simpleName)
            } }
    }, "VoiceBubble-OfflineModelInstall").start()
}

private fun installedStreamingModelLabel(settings: AppSettings, store: AsrModelStore): String {
    val selected = store.resolve(settings.streamingAsrModelId)
    return selected?.let { "使用中: ${it.family} (${it.chunkMs}ms)" } ?: "端末内モデルはまだ選ばれていません。"
}

private fun formatBytes(bytes: Long): String {
    val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gib >= 1.0) String.format(Locale.ROOT, "%.2f GiB", gib)
    else String.format(Locale.ROOT, "%.0f MiB", bytes.toDouble() / (1024.0 * 1024.0))
}
