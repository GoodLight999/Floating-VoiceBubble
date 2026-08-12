package com.goodlight.floatingvoicebubble

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.goodlight.floatingvoicebubble.benchmark.AsrReplayBenchmarkRunner
import com.goodlight.floatingvoicebubble.benchmark.BenchmarkReferenceStore
import com.goodlight.floatingvoicebubble.diagnostics.DiagnosticReport
import com.goodlight.floatingvoicebubble.diagnostics.SelfDiagnostics
import com.goodlight.floatingvoicebubble.dictionary.PersonalDictionary
import com.goodlight.floatingvoicebubble.model.AsrModelStore
import com.goodlight.floatingvoicebubble.model.FinalAsrModelStore
import com.goodlight.floatingvoicebubble.model.ModelImporter
import java.io.File

@Composable
internal fun VoiceBubbleSettingsScreen(
    activity: MainActivity,
    microphoneGranted: Boolean,
    accessibilityEnabled: Boolean,
    onRuntimeStatusChanged: () -> Unit,
) {
    val settingsStore = remember(activity) { SettingsStore(activity) }
    val referenceStore = remember(activity) { BenchmarkReferenceStore(activity) }

    var settings by remember { mutableStateOf(settingsStore.load()) }
    var apiKey by remember { mutableStateOf(settingsStore.apiKey()) }
    var endpointDraft by remember { mutableStateOf(settings.byokEndpoint) }
    var modelDraft by remember { mutableStateOf(settings.byokModel) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var diagnosticReport by remember { mutableStateOf<DiagnosticReport?>(null) }
    var dictionaryCount by remember { mutableLongStateOf(PersonalDictionary(activity).use { it.count() }) }
    var referenceCount by remember { mutableIntStateOf(referenceStore.count()) }
    var asrModels by remember { mutableStateOf(AsrModelStore(activity).listInstalled()) }
    var finalAsrModels by remember { mutableStateOf(FinalAsrModelStore(activity).listInstalled()) }
    var asrImportChunkMs by remember { mutableIntStateOf(560) }
    var gemmaVariantDraft by remember {
        mutableStateOf(if (settings.gemmaVariant == GemmaVariant.UNKNOWN) GemmaVariant.E2B else settings.gemmaVariant)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onRuntimeStatusChanged()
    }

    val gemmaModelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true
        message = "Gemmaモデルを検証しながら端末内へコピーしています…"
        Thread {
            runCatching { ModelImporter(activity).importGemmaVerified(uri) }
                .onSuccess { imported ->
                    val officialVariant = imported.fingerprint.detectedVariant.takeIf {
                        imported.fingerprint.knownOfficialArtifact && it != GemmaVariant.UNKNOWN
                    }
                    val selectedVariant = officialVariant ?: gemmaVariantDraft
                    val updated = settingsStore.update {
                        it.copy(
                            gemmaModelPath = imported.file.absolutePath,
                            gemmaVariant = selectedVariant,
                        )
                    }
                    activity.runOnUiThread {
                        settings = updated
                        gemmaVariantDraft = selectedVariant
                        busy = false
                        message = if (officialVariant != null) {
                            "公式Gemma ${officialVariant.name} をSHA-256で検証して読み込みました。"
                        } else {
                            "Gemma互換モデルを読み込みました。公式fingerprint未知のため系列=${selectedVariant.name}として扱います。"
                        }
                    }
                }
                .onFailure { error -> activity.runOnUiThread {
                    busy = false
                    message = error.message ?: "Gemmaモデルを読み込めませんでした。"
                } }
        }.start()
    }

    val asrModelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true
        message = "真のストリーミングASRモデルを検証・コピーしています…"
        Thread {
            val store = AsrModelStore(activity)
            runCatching { store.importNemotronTree(uri, asrImportChunkMs) }
                .onSuccess { model ->
                    val updated = settingsStore.update { it.copy(streamingAsrModelId = model.id) }
                    val installed = store.listInstalled()
                    activity.runOnUiThread {
                        settings = updated
                        asrModels = installed
                        busy = false
                        message = "Nemotron ${model.chunkMs}ms streamingモデルを導入しました。"
                    }
                }
                .onFailure { error -> activity.runOnUiThread {
                    busy = false
                    message = error.message ?: "ASRモデルを読み込めませんでした。"
                } }
        }.start()
    }

    val finalAsrLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true
        message = "ReazonSpeech最終ASRモデルを検証・コピーしています…"
        Thread {
            val store = FinalAsrModelStore(activity)
            runCatching { store.importReazonSpeechTree(uri) }
                .onSuccess { model ->
                    val updated = settingsStore.update {
                        it.copy(finalAsrMode = FinalAsrMode.REAZON_SPEECH, finalAsrModelId = model.id)
                    }
                    val installed = store.listInstalled()
                    activity.runOnUiThread {
                        settings = updated
                        finalAsrModels = installed
                        busy = false
                        message = "ReazonSpeech最終ASRモデルを導入しました。"
                    }
                }
                .onFailure { error -> activity.runOnUiThread {
                    busy = false
                    message = error.message ?: "ReazonSpeechモデルを読み込めませんでした。"
                } }
        }.start()
    }

    val dictionaryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true
        message = "個人辞書を読み込んでいます…"
        Thread {
            runCatching {
                val text = activity.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("辞書ファイルを開けませんでした。")
                PersonalDictionary(activity).use { dictionary ->
                    val result = dictionary.importText(text)
                    result to dictionary.count()
                }
            }.onSuccess { (result, count) -> activity.runOnUiThread {
                dictionaryCount = count
                busy = false
                message = "個人辞書: ${result.imported}件を読み込み、${result.skipped}件をスキップしました。"
            } }.onFailure { error -> activity.runOnUiThread {
                busy = false
                message = error.message ?: "個人辞書を読み込めませんでした。"
            } }
        }.start()
    }

    val referenceImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true
        message = "ASRベンチ用の正解ラベルを読み込んでいます…"
        Thread {
            runCatching {
                val text = activity.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("正解ラベルファイルを開けませんでした。")
                referenceStore.importText(text)
            }.onSuccess { result -> activity.runOnUiThread {
                referenceCount = referenceStore.count()
                busy = false
                message = "正解ラベル: ${result.imported}件を読み込み、${result.skipped}件をスキップしました。"
            } }.onFailure { error -> activity.runOnUiThread {
                busy = false
                message = error.message ?: "正解ラベルを読み込めませんでした。"
            } }
        }.start()
    }

    val referenceExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/tab-separated-values"),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true
        Thread {
            runCatching {
                val template = referenceStore.exportTemplate(limit = 30)
                activity.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                    it.write(template)
                } ?: error("出力先を開けませんでした。")
            }.onSuccess { activity.runOnUiThread {
                busy = false
                message = "正解ラベル用TSVを出力しました。reference列だけ編集して再インポートできます。"
            } }.onFailure { error -> activity.runOnUiThread {
                busy = false
                message = error.message ?: "正解ラベル雛形を出力できませんでした。"
            } }
        }.start()
    }

    val selectedAsr = asrModels.firstOrNull { it.id == settings.streamingAsrModelId }
    val selectedFinalAsr = finalAsrModels.firstOrNull { it.id == settings.finalAsrModelId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
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
                if (!microphoneGranted) {
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("マイクを許可") }
                }
                if (!accessibilityEnabled) {
                    OutlinedButton(onClick = { activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                        Text("アクセシビリティを開く")
                    }
                }
            }
        }

        Section("動作モード") {
            SettingSwitch(
                title = "完全オフライン",
                detail = "通信を使わず、Sherpa/Nemotronの真ストリーミングASRで認識します。補正が必要なら端末内Gemma、不要なら補正なしで完結し、クラウドへ黙ってフォールバックしません。",
                checked = settings.offlineMode,
                onChecked = { checked -> settings = settingsStore.update { it.copy(offlineMode = checked) } },
            )
            HorizontalDivider()
            Text("リアルタイム認識", fontWeight = FontWeight.SemiBold)
            ChoiceRow(
                values = RecognitionMode.entries,
                selected = settings.recognitionMode,
                label = {
                    when (it) {
                        RecognitionMode.AUTO -> "自動"
                        RecognitionMode.SYSTEM -> "システム"
                        RecognitionMode.ON_DEVICE -> "Android端末内"
                        RecognitionMode.SHERPA_STREAMING -> "自前Streaming"
                    }
                },
                onSelect = { value -> settings = settingsStore.update { it.copy(recognitionMode = value) } },
            )
            SettingSwitch(
                title = "無音で自動終了",
                detail = "発話後の静寂を端末内で検出して確定します。バブルを再度タップして手動終了もできます。",
                checked = settings.autoStop,
                onChecked = { checked -> settings = settingsStore.update { it.copy(autoStop = checked) } },
            )
        }

        Section("完全オフラインASR") {
            Text(
                selectedAsr?.let { "${it.family}  •  ${it.chunkMs}ms  •  ${it.totalBytes / (1024 * 1024)} MiB" }
                    ?: "真のストリーミングASRモデル未設定",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Nemotron 3.5 Streamingのint8モデルを端末内へコピーします。ReazonSpeechのVAD+offline再認識はpartial用途には使いません。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("取り込むモデルのchunk幅", fontWeight = FontWeight.SemiBold)
            ChoiceRow(
                values = listOf(80, 160, 560, 1120),
                selected = asrImportChunkMs,
                label = { "${it}ms" },
                onSelect = { asrImportChunkMs = it },
            )
            Text(
                "560msは精度寄り、短いchunkは遅延寄りの候補です。最終採用値は同一音声ベンチマークで決めます。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { asrModelLauncher.launch(null) }, enabled = !busy) {
                Text("展開済みNemotronモデルフォルダを読み込む")
            }
            selectedAsr?.let { model ->
                OutlinedButton(
                    onClick = {
                        if (AsrModelStore(activity).remove(model.id)) {
                            asrModels = AsrModelStore(activity).listInstalled()
                            settings = settingsStore.update { it.copy(streamingAsrModelId = "") }
                            message = "選択中のASRモデルを削除しました。"
                        }
                    },
                    enabled = !busy,
                ) { Text("選択モデルを削除") }
            }
        }

        Section("最終ASR / 精度ベンチ") {
            Text(
                "partial表示とは別の認識器を同じ録音WAVへ適用できます。live経路の速度を維持したまま、確定文だけ精度重視へ差し替えます。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChoiceRow(
                values = FinalAsrMode.entries,
                selected = settings.finalAsrMode,
                label = { if (it == FinalAsrMode.LIVE_RESULT) "live結果" else "ReazonSpeech" },
                onSelect = { value -> settings = settingsStore.update { it.copy(finalAsrMode = value) } },
            )
            Text(
                selectedFinalAsr?.let { "${it.family}  •  ${it.totalBytes / (1024 * 1024)} MiB" }
                    ?: "ReazonSpeech最終ASRモデル未設定",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { finalAsrLauncher.launch(null) }, enabled = !busy) {
                Text("展開済みReazonSpeechモデルを読み込む")
            }
            selectedFinalAsr?.let { model ->
                OutlinedButton(
                    onClick = {
                        if (FinalAsrModelStore(activity).remove()) {
                            finalAsrModels = FinalAsrModelStore(activity).listInstalled()
                            settings = settingsStore.update {
                                it.copy(finalAsrMode = FinalAsrMode.LIVE_RESULT, finalAsrModelId = "")
                            }
                            message = "ReazonSpeechモデルを削除しました。"
                        }
                    },
                    enabled = !busy,
                ) { Text("ReazonSpeechモデルを削除") }
                Button(
                    onClick = {
                        busy = true
                        message = "保存済みWAVをReazonSpeechへ再投入しています…"
                        Thread {
                            runCatching { AsrReplayBenchmarkRunner(activity).run(model, limit = 20) }
                                .onSuccess { summary -> activity.runOnUiThread {
                                    busy = false
                                    message = if (summary.labeled > 0) {
                                        "同一音声ベンチ: ${summary.oneLine()}。明示した正解ラベルだけで精度を計算しました。"
                                    } else {
                                        "同一音声リプレイ: ${summary.oneLine()}。正解ラベルがないため差分は精度として扱いません。"
                                    }
                                } }
                                .onFailure { error -> activity.runOnUiThread {
                                    busy = false
                                    message = error.message ?: "リプレイベンチを完了できませんでした。"
                                } }
                        }.start()
                    },
                    enabled = !busy,
                ) { Text("保存済みWAVでリプレイベンチ") }
            }
            HorizontalDivider()
            Text("正解ラベル $referenceCount 件", fontWeight = FontWeight.SemiBold)
            Text(
                "雛形には sessionId / liveTranscript / reference を出力します。ASR出力から正解を推測せず、reference列に人間が明示した文字列だけをCER/WERへ使います。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { referenceExportLauncher.launch("floating-voicebubble-asr-reference.tsv") },
                    enabled = !busy,
                ) { Text("正解TSV雛形を出力") }
                OutlinedButton(
                    onClick = {
                        referenceImportLauncher.launch(arrayOf("text/*", "text/csv", "text/tab-separated-values"))
                    },
                    enabled = !busy,
                ) { Text("正解ラベルを読込") }
            }
        }

        Section("最終補正") {
            ChoiceRow(
                values = CorrectionMode.entries,
                selected = settings.correctionMode,
                label = {
                    when (it) {
                        CorrectionMode.AUTO -> "自動"
                        CorrectionMode.BYOK -> "BYOK"
                        CorrectionMode.GEMMA -> "Gemma"
                        CorrectionMode.NONE -> "補正なし"
                    }
                },
                onSelect = { value -> settings = settingsStore.update { it.copy(correctionMode = value) } },
            )
            Text(
                "補正器には『最小訂正』だけを許し、編集量が不自然に大きい出力は破棄して語調を保護します。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("BYOK") {
            OutlinedTextField(
                value = endpointDraft,
                onValueChange = { endpointDraft = it },
                label = { Text("API URL（OpenAI互換 / Anthropic / Gemini）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = modelDraft,
                onValueChange = { modelDraft = it },
                label = { Text("モデル名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API key（Android Keystoreで暗号化）") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    settings = settingsStore.update {
                        it.copy(byokEndpoint = endpointDraft.trim(), byokModel = modelDraft.trim())
                    }
                    settingsStore.setApiKey(apiKey.trim())
                    message = "BYOK設定を保存しました。"
                },
                enabled = !busy && endpointDraft.startsWith("https://"),
            ) { Text("BYOK設定を保存") }
        }

        Section("端末内Gemma 4") {
            Text(
                if (settings.gemmaModelPath.isBlank()) {
                    "モデル未設定"
                } else {
                    "${settings.gemmaVariant.name}  •  ${File(settings.gemmaModelPath).name}  •  端末内保存"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "公式E2B/E4Bはサイズ+SHA-256で自動判定します。未知の互換パッケージだけ、次の系列指定を採用します。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text("未知モデルの系列", fontWeight = FontWeight.SemiBold)
            ChoiceRow(
                values = listOf(GemmaVariant.E2B, GemmaVariant.E4B),
                selected = gemmaVariantDraft,
                label = { it.name },
                onSelect = { gemmaVariantDraft = it },
            )
            ChoiceRow(
                values = GemmaBackend.entries,
                selected = settings.gemmaBackend,
                label = {
                    when (it) {
                        GemmaBackend.AUTO -> "GPU→CPU"
                        GemmaBackend.GPU -> "GPU"
                        GemmaBackend.CPU -> "CPU"
                    }
                },
                onSelect = { value -> settings = settingsStore.update { it.copy(gemmaBackend = value) } },
            )
            OutlinedButton(
                onClick = { gemmaModelLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                enabled = !busy,
            ) { Text("E2B/E4B .litertlm モデルを読み込む") }
            if (settings.gemmaModelPath.isNotBlank()) {
                OutlinedButton(
                    onClick = {
                        val file = File(settings.gemmaModelPath)
                        if (!file.exists() || file.delete()) {
                            settings = settingsStore.update {
                                it.copy(gemmaModelPath = "", gemmaVariant = GemmaVariant.UNKNOWN)
                            }
                            message = "Gemmaモデルを削除しました。"
                        } else {
                            message = "Gemmaモデルを削除できませんでした。"
                        }
                    },
                    enabled = !busy,
                ) { Text("Gemmaモデルを削除") }
            }
        }

        Section("個人辞書") {
            Text("$dictionaryCount 語", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "保存件数に小さな上限は設けません。CSV/TSV: term, reading, aliases(|区切り), weight。対応するAndroid ASRでは上位語を認識バイアスにも使い、全経路で最終補正コンテキストへ共有します。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { dictionaryLauncher.launch(arrayOf("text/*", "text/csv", "text/tab-separated-values")) },
                enabled = !busy,
            ) { Text("辞書をインポート") }
        }

        Section("診断 / ベンチマーク保存") {
            SettingSwitch(
                title = "セッショントレースを保存",
                detail = "同一音声でASRを比較できるよう、WAV・N-best・live/final ASR・raw/final・レイテンシを最大30セッション端末内のno-backup領域へ残します。",
                checked = settings.keepSessionTraces,
                onChecked = { checked -> settings = settingsStore.update { it.copy(keepSessionTraces = checked) } },
            )
            HorizontalDivider()
            Text("全自動診断", fontWeight = FontWeight.SemiBold)
            Text(
                "1回で権限、Accessibility、Android認識、Sherpa JNI/モデル、最終ASRモデル、AudioRecord、辞書DB、保存先、Gemma、BYOK実経路、オフライン遮断、補正ガードを検査します。設定済みBYOK/Gemmaには固定テスト文だけを使い、ユーザーの音声・辞書・API keyは診断レポートへ出しません。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = {
                    busy = true
                    diagnosticReport = null
                    message = "全自動診断を実行しています…"
                    Thread {
                        runCatching { SelfDiagnostics(activity).run(includeExternalProbes = true) }
                            .onSuccess { report -> activity.runOnUiThread {
                                diagnosticReport = report
                                busy = false
                                message = if (report.failed) "診断でFAILを検出しました。" else "全自動診断が完了しました。"
                            } }
                            .onFailure { error -> activity.runOnUiThread {
                                busy = false
                                message = error.message ?: "全自動診断を完了できませんでした。"
                            } }
                    }.start()
                },
                enabled = !busy,
            ) { Text("全自動診断を実行") }

            diagnosticReport?.let { report ->
                Text(report.summary(), fontWeight = FontWeight.Bold)
                report.items.forEach { item ->
                    Text(
                        "${item.status.name.padEnd(4)}  ${item.id}  —  ${item.detail}",
                        color = if (item.status.name == "FAIL") {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(onClick = {
                    activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                        ClipData.newPlainText("Floating VoiceBubble diagnostic", report.toRedactedJson()),
                    )
                    message = "redacted診断JSONをクリップボードへコピーしました。"
                }) { Text("診断JSONをコピー") }
            }
        }

        message?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text(
                    it,
                    modifier = Modifier.padding(14.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun StatusLine(label: String, enabled: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(
            if (enabled) "準備完了" else "未設定",
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    detail: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        values.forEach { value ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label(value)) },
            )
        }
    }
}

@Composable
internal fun VoiceBubbleTheme(content: @Composable () -> Unit) {
    val light = lightColorScheme(
        primary = Color(0xFF4257B2),
        onPrimary = Color.White,
        background = Color(0xFFF7F7F5),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFEDEEF2),
    )
    val dark = darkColorScheme(
        primary = Color(0xFFAEBBFF),
        background = Color(0xFF111318),
        surface = Color(0xFF191B20),
        surfaceVariant = Color(0xFF24272E),
    )
    MaterialTheme(
        colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) dark else light,
        content = content,
    )
}
