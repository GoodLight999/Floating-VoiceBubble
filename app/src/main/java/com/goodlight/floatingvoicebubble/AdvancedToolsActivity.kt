package com.goodlight.floatingvoicebubble

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.goodlight.floatingvoicebubble.benchmark.AsrCandidateScore
import com.goodlight.floatingvoicebubble.benchmark.AsrTournamentRunner
import com.goodlight.floatingvoicebubble.benchmark.BenchmarkReferenceStore
import com.goodlight.floatingvoicebubble.benchmark.ExternalAsrResultStore
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
                AdvancedToolsScreen(this)
            }
        }
    }
}

@Composable
private fun AdvancedToolsScreen(activity: AdvancedToolsActivity) {
    val settingsStore = remember { SettingsStore(activity) }
    val referenceStore = remember { BenchmarkReferenceStore(activity) }
    val externalStore = remember { ExternalAsrResultStore(activity) }
    val streamingStore = remember { AsrModelStore(activity) }
    val finalStore = remember { FinalAsrModelStore(activity) }

    var message by remember { mutableStateOf("") }
    var busyId by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<ModelInstallProgress?>(null) }
    var installedStreaming by remember { mutableStateOf(streamingStore.listInstalled().map { it.id }.toSet()) }
    var finalInstalled by remember { mutableStateOf(finalStore.resolve(FinalAsrModelStore.MODEL_ID) != null) }

    var tournamentRows by remember { mutableStateOf<List<AsrCandidateScore>>(emptyList()) }
    var tournamentSummary by remember { mutableStateOf("") }

    val referenceImport = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            activity.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("正解ラベルTSVを開けませんでした。")
        }.onSuccess { text ->
            val result = referenceStore.importText(text)
            message = "正解ラベル: ${result.imported}件取込 / ${result.skipped}件スキップ"
        }.onFailure { message = "正解ラベル取込失敗: ${it.message}" }
    }
    val referenceExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/tab-separated-values"),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            activity.contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write(referenceStore.exportTemplate(limit = 50))
            } ?: error("正解ラベルTSVの出力先を開けませんでした。")
        }.onSuccess { message = "正解ラベル雛形を出力しました。reference列だけ人間が修正します。" }
            .onFailure { message = "正解ラベル出力失敗: ${it.message}" }
    }
    val externalImport = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            activity.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("競合ASR TSVを開けませんでした。")
        }.onSuccess { text ->
            val result = externalStore.importText(text)
            message = "競合ASR: ${result.imported}件取込 / ${result.skipped}件スキップ"
        }.onFailure { message = "競合ASR取込失敗: ${it.message}" }
    }
    val externalExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/tab-separated-values"),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            activity.contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write(externalStore.exportTemplate(limit = 50))
            } ?: error("競合ASR TSVの出力先を開けませんでした。")
        }.onSuccess { message = "Gboard / Wispr Flow / Aqua Voice比較雛形を出力しました。" }
            .onFailure { message = "競合ASR雛形出力失敗: ${it.message}" }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("高度設定・検証", style = MaterialTheme.typography.headlineSmall)
                Text("通常入力を汚さず、モデル・ベンチなどの検証機能をここへ集約します。", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { activity.finish() }) { Text("戻る") }
        }

        if (message.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }

        SectionCard("公式モデル導入") {
            Text("公式配布元をコードで固定。Gemmaはサイズ+SHA-256完全一致、ASRは必要ファイルだけ展開して検証後にatomic置換します。")
            OfficialModelCatalog.all.forEach { entry ->
                val installed = when (entry.kind) {
                    CatalogModelKind.STREAMING_ASR -> entry.id in installedStreaming
                    CatalogModelKind.FINAL_ASR -> finalInstalled
                    CatalogModelKind.GEMMA -> {
                        val settings = settingsStore.load()
                        settings.gemmaVariant == entry.gemmaVariant && settings.gemmaModelPath.isNotBlank()
                    }
                }
                ModelRow(
                    entry = entry,
                    installed = installed,
                    busy = busyId != null,
                    progress = progress.takeIf { busyId == entry.id },
                    onInstall = {
                        busyId = entry.id
                        progress = null
                        message = "${entry.title} を取得しています…"
                        Thread({
                            runCatching {
                                OfficialModelInstaller(activity).install(entry) { p ->
                                    activity.runOnUiThread { progress = p }
                                }
                            }.onSuccess { installedModel ->
                                activity.runOnUiThread {
                                    when (installedModel) {
                                        is InstalledOfficialModel.Streaming -> {
                                            settingsStore.update { it.copy(streamingAsrModelId = installedModel.model.id) }
                                            installedStreaming = streamingStore.listInstalled().map { it.id }.toSet()
                                        }
                                        is InstalledOfficialModel.Final -> {
                                            settingsStore.update {
                                                it.copy(
                                                    finalAsrModelId = installedModel.model.id,
                                                    finalAsrMode = FinalAsrMode.REAZON_SPEECH,
                                                )
                                            }
                                            finalInstalled = true
                                        }
                                        is InstalledOfficialModel.Gemma -> {
                                            val variant = installedModel.model.fingerprint.detectedVariant
                                            settingsStore.update {
                                                it.copy(
                                                    gemmaModelPath = installedModel.model.file.absolutePath,
                                                    gemmaVariant = variant,
                                                )
                                            }
                                        }
                                    }
                                    busyId = null
                                    progress = null
                                    message = "${entry.title} を検証して導入しました。"
                                }
                            }.onFailure { failure ->
                                activity.runOnUiThread {
                                    busyId = null
                                    progress = null
                                    message = "モデル導入失敗: ${failure.message ?: failure.javaClass.simpleName}"
                                }
                            }
                        }, "VoiceBubble-ModelInstall").start()
                    },
                )
                HorizontalDivider()
            }
        }

        SectionCard("個人辞書") {
            Text("旧インライン編集フォームは廃止しました。検索・登録・名前変更・別名編集・並び替え・大量辞書のページングは、メイン画面上部の「個人辞書」で行います。")
            Text(
                "高度設定の途中で編集状態を見失ったり、見出し語変更が別項目として残る経路はもう使用しません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("同一WAV ASRトーナメント") {
            Text("正解文は人間が付けたreferenceだけを使用。Nemotron各chunk・live・Reazon・外部ASRを同じ基準で比較します。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { referenceExport.launch("voicebubble-ground-truth.tsv") }) { Text("正解雛形") }
                OutlinedButton(onClick = { referenceImport.launch("text/*") }) { Text("正解取込") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { externalExport.launch("voicebubble-competitors.tsv") }) { Text("競合雛形") }
                OutlinedButton(onClick = { externalImport.launch("text/*") }) { Text("競合取込") }
            }
            Button(
                enabled = busyId == null,
                onClick = {
                    busyId = "benchmark"
                    message = "同一WAVトーナメントを実行しています…"
                    Thread({
                        runCatching { AsrTournamentRunner(activity).run(limit = 30) }
                            .onSuccess { result ->
                                activity.runOnUiThread {
                                    tournamentRows = result.candidates
                                    tournamentSummary = result.oneLine()
                                    message = "ベンチ完了: ${result.reportFile.name}"
                                    busyId = null
                                }
                            }.onFailure { failure ->
                                activity.runOnUiThread {
                                    message = "ベンチ失敗: ${failure.message ?: failure.javaClass.simpleName}"
                                    busyId = null
                                }
                            }
                    }, "VoiceBubble-ASRTournament").start()
                },
            ) { Text(if (busyId == "benchmark") "比較中…" else "全候補を比較") }
            if (tournamentSummary.isNotBlank()) Text(tournamentSummary, style = MaterialTheme.typography.titleSmall)
            tournamentRows.sortedWith(compareByDescending<AsrCandidateScore> { it.labeled }.thenBy { it.averageContentCer ?: Double.MAX_VALUE })
                .forEach { score -> CandidateScoreRow(score) }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ModelRow(
    entry: OfficialModelEntry,
    installed: Boolean,
    busy: Boolean,
    progress: ModelInstallProgress?,
    onInstall: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(entry.title, style = MaterialTheme.typography.titleSmall)
        Text(entry.detail, style = MaterialTheme.typography.bodySmall)
        Text("導入後 約 ${formatBytes(entry.estimatedInstalledBytes)}", style = MaterialTheme.typography.bodySmall)
        progress?.let {
            val pct = if (it.totalBytes != null && it.totalBytes > 0L) {
                " ${(it.completedBytes * 100L / it.totalBytes).coerceIn(0L, 100L)}%"
            } else ""
            Text("${it.phase}$pct", style = MaterialTheme.typography.bodySmall)
        }
        Button(enabled = !busy, onClick = onInstall) {
            Text(if (installed) "再取得して検証" else "公式版を導入して選択")
        }
    }
}

@Composable
private fun CandidateScoreRow(score: AsrCandidateScore) {
    val cer = score.averageContentCer?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "—"
    val strict = score.averageStrictCer?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "—"
    val rtf = score.averageRtf?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "—"
    Text(
        "${score.label}: CER=$cer / strict=$strict / RTF=$rtf / 正解付き=${score.labeled} / 成功=${score.succeeded}/${score.attempted}",
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun formatBytes(bytes: Long): String {
    val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gib >= 1.0) String.format(Locale.ROOT, "%.2f GiB", gib)
    else String.format(Locale.ROOT, "%.0f MiB", bytes.toDouble() / (1024.0 * 1024.0))
}
