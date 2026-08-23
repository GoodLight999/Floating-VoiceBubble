package com.goodlight.floatingvoicebubble

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.goodlight.floatingvoicebubble.correction.ByokEndpointResolver
import com.goodlight.floatingvoicebubble.correction.ByokModelDiscovery
import com.goodlight.floatingvoicebubble.correction.ByokModelInfo
import com.goodlight.floatingvoicebubble.correction.CorrectionPostProcessor
import com.goodlight.floatingvoicebubble.correction.FinalizationEngine
import com.goodlight.floatingvoicebubble.correction.ReasoningCapabilities
import com.goodlight.floatingvoicebubble.dictionary.PersonalDictionary
import com.goodlight.floatingvoicebubble.model.FinalAsrModelStore
import com.goodlight.floatingvoicebubble.model.GemmaModelSource
import com.goodlight.floatingvoicebubble.model.InstalledOfficialModel
import com.goodlight.floatingvoicebubble.model.ModelInstallProgress
import com.goodlight.floatingvoicebubble.model.OfficialModelCatalog
import com.goodlight.floatingvoicebubble.model.OfficialModelEntry
import com.goodlight.floatingvoicebubble.model.OfficialModelInstaller
import com.goodlight.floatingvoicebubble.speech.RecognitionOutcome
import com.goodlight.floatingvoicebubble.trace.SessionTraceStore
import java.util.Locale
import java.util.concurrent.Executors

class CorrectionSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VoiceBubbleTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    CorrectionSetupScreen(this)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CorrectionSetupScreen(activity: CorrectionSetupActivity) {
    val store = remember(activity) { SettingsStore(activity) }
    var settings by remember { mutableStateOf(store.load()) }
    var endpoint by rememberSaveable { mutableStateOf(settings.byokEndpoint) }
    // Unsaved secrets intentionally stay process-local; persisted keys are Android-Keystore protected.
    var apiKey by remember { mutableStateOf(store.apiKey()) }
    var model by rememberSaveable { mutableStateOf(settings.byokModel) }
    var reasoningEffort by rememberSaveable { mutableStateOf(settings.reasoningEffort) }
    var modelFilter by rememberSaveable { mutableStateOf("") }
    var models by remember { mutableStateOf<List<ByokModelInfo>>(emptyList()) }
    var busyAction by remember { mutableStateOf<String?>(null) }
    var message by rememberSaveable { mutableStateOf("") }
    var progress by remember { mutableStateOf<ModelInstallProgress?>(null) }

    val gemmaModelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        busyAction = "gemma-external"
        progress = null
        message = "既存のGemmaモデルをコピーせず検証しています…"
        Thread({
            runCatching {
                val selected = GemmaModelSource.verifyExternal(activity, uri) { readBytes, totalBytes ->
                    activity.runOnUiThread {
                        progress = ModelInstallProgress("既存モデルを検証", readBytes, totalBytes)
                    }
                }
                GemmaModelSource.persistReadPermission(activity, uri)
                selected
            }.onSuccess { selected ->
                activity.runOnUiThread {
                    val previousReference = settings.gemmaModelPath
                    if (previousReference != selected.reference && GemmaModelSource.isExternal(previousReference)) {
                        GemmaModelSource.releaseReadPermission(activity, previousReference)
                    }
                    val variant = selected.fingerprint.detectedVariant
                    settings = store.update {
                        it.copy(
                            gemmaModelPath = selected.reference,
                            gemmaVariant = variant,
                        )
                    }
                    busyAction = null
                    progress = null
                    message = if (selected.fingerprint.knownOfficialArtifact) {
                        "${selected.displayName} を検証し、コピーせず使用するモデルに設定しました。"
                    } else {
                        "${selected.displayName} をコピーせず使用するモデルに設定しました。"
                    }
                }
            }.onFailure { failure ->
                activity.runOnUiThread {
                    busyAction = null
                    progress = null
                    message = "Gemmaモデルの選択失敗: ${failure.message ?: failure.javaClass.simpleName}"
                }
            }
        }, "VoiceBubble-GemmaExternalVerify").start()
    }

    fun saveByok(
        modelValue: String = model,
        reasoningValue: ReasoningEffort = reasoningEffort,
    ): AppSettings {
        val updated = store.update {
            it.copy(
                byokEndpoint = endpoint.trim(),
                byokModel = modelValue.trim(),
                reasoningEffort = reasoningValue,
            )
        }
        store.setApiKey(apiKey.trim())
        settings = updated
        return updated
    }

    val selectedModelInfo = models.firstOrNull { it.id == model }
    val baseCapability = ReasoningCapabilities.capability(endpoint, model)
    val openRouterMetadataSaysNoReasoning = ByokEndpointResolver.isOpenRouter(endpoint) &&
        selectedModelInfo?.supportedParameters?.isNotEmpty() == true &&
        selectedModelInfo.supportsReasoning.not()
    val reasoningChoices = if (openRouterMetadataSaysNoReasoning) {
        listOf(ReasoningEffort.DEFAULT)
    } else {
        baseCapability.choices
    }
    val normalizedReasoning = ReasoningCapabilities.normalize(endpoint, model, reasoningEffort)

    val filteredModels = models.asSequence()
        .filter { item ->
            val query = modelFilter.trim()
            query.isBlank() || item.id.contains(query, ignoreCase = true) ||
                item.displayName.contains(query, ignoreCase = true) ||
                item.description.contains(query, ignoreCase = true)
        }
        .take(MAX_VISIBLE_MODELS)
        .toList()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("モデル・API", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            TextButton(onClick = activity::finish) { Text("戻る") }
        }

        Text("補正に使うもの", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CorrectionMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.correctionMode == mode,
                    onClick = { settings = store.update { it.copy(correctionMode = mode) } },
                    label = {
                        Text(
                            when (mode) {
                                CorrectionMode.AUTO -> "自動選択"
                                CorrectionMode.BYOK -> "クラウドAPI"
                                CorrectionMode.GEMMA -> "端末内Gemma"
                                CorrectionMode.NONE -> "補正しない"
                            },
                        )
                    },
                )
            }
        }

        HorizontalDivider()
        Text("クラウドAPI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it; models = emptyList(); message = "" },
            label = { Text("API URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; models = emptyList(); message = "" },
            label = { Text("APIキー") },
            supportingText = { Text("保存時にAndroid Keystoreで暗号化します") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedButton(
            onClick = {
                busyAction = "models"
                message = "モデル一覧を取得しています…"
                Thread({
                    runCatching { ByokModelDiscovery().list(endpoint, apiKey.trim()) }
                        .onSuccess { fetched -> activity.runOnUiThread {
                            models = fetched
                            busyAction = null
                            message = if (fetched.isEmpty()) {
                                "接続できましたが、選べるモデルが見つかりませんでした。"
                            } else {
                                "${fetched.size}件のモデルを取得しました。"
                            }
                        } }
                        .onFailure { failure -> activity.runOnUiThread {
                            busyAction = null
                            message = "モデル取得失敗: ${failure.message ?: failure.javaClass.simpleName}"
                        } }
                }, "VoiceBubble-ModelDiscovery").start()
            },
            enabled = busyAction == null && endpoint.trim().startsWith("https://"),
        ) { Text(if (busyAction == "models") "取得中…" else "モデル一覧を取得") }

        if (models.isNotEmpty()) {
            OutlinedTextField(
                value = modelFilter,
                onValueChange = { modelFilter = it },
                label = { Text("モデルを検索") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${models.size}件中 ${filteredModels.size}件を表示",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                filteredModels.forEach { item ->
                    ModelRow(item = item, selected = model == item.id) {
                        model = item.id
                        val normalized = ReasoningCapabilities.normalize(endpoint, item.id, reasoningEffort)
                        reasoningEffort = normalized
                        saveByok(modelValue = item.id, reasoningValue = normalized)
                        message = "${item.displayName} を使用するモデルとして保存しました。"
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("モデルID") },
                supportingText = { Text("一覧を取得できないAPIの場合だけ手入力します") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (model.isNotBlank()) {
            Text("推論の深さ", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                reasoningChoices.forEach { effort ->
                    FilterChip(
                        selected = normalizedReasoning == effort,
                        onClick = {
                            reasoningEffort = effort
                            saveByok(reasoningValue = effort)
                            message = "推論の深さを「${ReasoningCapabilities.label(endpoint, model, effort)}」に保存しました。"
                        },
                        label = { Text(ReasoningCapabilities.label(endpoint, model, effort)) },
                    )
                }
            }
            Text(
                if (openRouterMetadataSaysNoReasoning) {
                    "このモデルはOpenRouterのモデル情報上、推論深度の指定に対応していません。"
                } else {
                    baseCapability.note
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = {
                    busyAction = "test"
                    saveByok()
                    val persisted = SettingsStore(activity).load()
                    message = "本番と同じ補正経路で確認しています…"
                    Thread({
                        runCatching { runProductionCorrectionProbe(activity, store, persisted) }
                            .onSuccess { report -> activity.runOnUiThread {
                                busyAction = null
                                message = report
                            } }
                            .onFailure { failure -> activity.runOnUiThread {
                                busyAction = null
                                message = "実補正テスト失敗: ${failure.message ?: failure.javaClass.simpleName}"
                            } }
                    }, "VoiceBubble-ProductionCorrectionTest").start()
                },
                enabled = busyAction == null && endpoint.trim().startsWith("https://") && model.isNotBlank(),
            ) { Text(if (busyAction == "test") "実補正中…" else "本番と同じ経路で実補正テスト") }
            Button(
                onClick = {
                    saveByok()
                    message = "API設定を保存しました。"
                },
                enabled = busyAction == null && endpoint.trim().startsWith("https://") && model.isNotBlank(),
            ) { Text("API設定を保存") }
        }

        if (message.isNotBlank()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if ("失敗" in message || "見つかりません" in message) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Text(
                    message,
                    modifier = Modifier.padding(12.dp),
                    color = if ("失敗" in message || "見つかりません" in message) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        HorizontalDivider()
        Text("端末内Gemma", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            gemmaStatus(activity, settings),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "公式E2B/E4Bはアプリから取得できます。通信が切れても途中から再開します。既にある .litertlm はコピーせず、そのファイルを直接使えます。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GemmaInstallButton(
                title = "E2Bを取得",
                entry = OfficialModelCatalog.gemmaE2B,
                enabled = busyAction == null,
                onStart = {
                    busyAction = it.id
                    progress = null
                    message = "${it.title} を取得しています。通信が切れても途中から再開します…"
                },
                onProgress = { progress = it },
                onSuccess = { installed ->
                    val previousReference = settings.gemmaModelPath
                    if (GemmaModelSource.isExternal(previousReference)) {
                        GemmaModelSource.releaseReadPermission(activity, previousReference)
                    }
                    val variant = installed.fingerprint.detectedVariant
                    settings = store.update { it.copy(gemmaModelPath = installed.file.absolutePath, gemmaVariant = variant) }
                    busyAction = null
                    progress = null
                    message = "Gemma ${variant.name} を検証して導入しました。"
                },
                onFailure = { error -> busyAction = null; progress = null; message = "Gemma導入失敗: $error" },
                activity = activity,
            )
            GemmaInstallButton(
                title = "E4Bを取得",
                entry = OfficialModelCatalog.gemmaE4B,
                enabled = busyAction == null,
                onStart = {
                    busyAction = it.id
                    progress = null
                    message = "${it.title} を取得しています。通信が切れても途中から再開します…"
                },
                onProgress = { progress = it },
                onSuccess = { installed ->
                    val previousReference = settings.gemmaModelPath
                    if (GemmaModelSource.isExternal(previousReference)) {
                        GemmaModelSource.releaseReadPermission(activity, previousReference)
                    }
                    val variant = installed.fingerprint.detectedVariant
                    settings = store.update { it.copy(gemmaModelPath = installed.file.absolutePath, gemmaVariant = variant) }
                    busyAction = null
                    progress = null
                    message = "Gemma ${variant.name} を検証して導入しました。"
                },
                onFailure = { error -> busyAction = null; progress = null; message = "Gemma導入失敗: $error" },
                activity = activity,
            )
            OutlinedButton(
                onClick = { gemmaModelLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                enabled = busyAction == null,
            ) {
                Text(if (busyAction == "gemma-external") "検証中…" else "端末にある .litertlm をそのまま使う")
            }
        }
        if (settings.gemmaModelPath.isNotBlank()) {
            TextButton(
                enabled = busyAction == null,
                onClick = {
                    val previousReference = settings.gemmaModelPath
                    if (GemmaModelSource.isExternal(previousReference)) {
                        GemmaModelSource.releaseReadPermission(activity, previousReference)
                    }
                    settings = store.update {
                        it.copy(gemmaModelPath = "", gemmaVariant = GemmaVariant.UNKNOWN)
                    }
                    message = if (GemmaModelSource.isExternal(previousReference)) {
                        "外部Gemmaの選択を解除しました。元のモデルファイルは削除していません。"
                    } else {
                        "Gemmaの使用を解除しました。取得済みファイルは削除していません。"
                    }
                },
            ) { Text("このGemmaを使用しない") }
        }
        progress?.let { p ->
            Text(formatProgress(p), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun gemmaStatus(activity: CorrectionSetupActivity, settings: AppSettings): String {
    val reference = settings.gemmaModelPath
    if (reference.isBlank()) return "モデル未設定"
    val available = GemmaModelSource.isAvailable(activity, reference)
    val name = GemmaModelSource.displayName(activity, reference)
    val source = if (GemmaModelSource.isExternal(reference)) "外部ファイル・コピーなし" else "アプリ内"
    val variant = settings.gemmaVariant.takeIf { it != GemmaVariant.UNKNOWN }?.name?.let { "Gemma $it / " }.orEmpty()
    return if (available) "$variant$name / $source" else "$variant$name / ファイルを開けません"
}

private fun runProductionCorrectionProbe(
    activity: CorrectionSetupActivity,
    store: SettingsStore,
    persisted: AppSettings,
): String {
    val probe = CorrectionPostProcessor.correctionProbeRequest()
    val now = System.currentTimeMillis()
    val outcome = RecognitionOutcome(
        sessionId = "settings-production-probe-$now",
        rawTranscript = probe.rawTranscript,
        alternatives = probe.alternatives,
        audioFile = null,
        startedAtMs = now,
        recognitionFinishedAtMs = now,
        recognizerKind = "settings-production-probe",
    )
    val worker = Executors.newCachedThreadPool()
    return try {
        PersonalDictionary(activity).use { dictionary ->
            val engine = FinalizationEngine(
                context = activity,
                settingsStore = store,
                dictionary = dictionary,
                traceStore = SessionTraceStore(activity),
                finalAsrModelStore = FinalAsrModelStore(activity),
                inferenceWorker = worker,
            )
            val testSettings = persisted.copy(
                correctionMode = CorrectionMode.BYOK,
                finalAsrMode = FinalAsrMode.LIVE_RESULT,
                recognitionRepairMode = RecognitionRepairMode.STRONG,
                correctionAddCommas = true,
                correctionAddPeriods = true,
                correctionRemoveFillers = true,
                correctionLineBreakMode = LineBreakMode.NONE,
                keepSessionTraces = false,
            )
            val result = engine.finalize(
                outcome = outcome,
                surrounding = probe.surroundingContext,
                settings = testSettings,
                bypassCorrection = false,
            )
            result.correctionError?.let { error(it) }
            check(result.correctionModelResponded) { "補正モデルから本文が返りませんでした。" }
            CorrectionPostProcessor.probeFailure(result.finalText)?.let { error(it) }
            val latency = result.correctionLatencyMs?.let { "${it}ms" } ?: "計測不能"
            "実補正テスト成功（$latency）: ${result.finalText.take(160)}"
        }
    } finally {
        worker.shutdownNow()
    }
}

@Composable
private fun ModelRow(item: ByokModelInfo, selected: Boolean, onSelect: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            item.displayName,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (item.displayName != item.id) {
            Text(item.id, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        val metadata = buildList {
            item.contextLength?.let { add("context ${formatTokenCount(it)}") }
            if (item.supportsReasoning) add("推論設定対応")
            if (item.promptPricePerMillion != null || item.completionPricePerMillion != null) {
                val input = item.promptPricePerMillion?.let(::formatPrice) ?: "?"
                val output = item.completionPricePerMillion?.let(::formatPrice) ?: "?"
                add("$${input}/M in · $${output}/M out")
            }
        }
        if (metadata.isNotEmpty()) {
            Text(metadata.joinToString("  •  "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
    HorizontalDivider()
}

@Composable
private fun GemmaInstallButton(
    title: String,
    entry: OfficialModelEntry,
    enabled: Boolean,
    onStart: (OfficialModelEntry) -> Unit,
    onProgress: (ModelInstallProgress) -> Unit,
    onSuccess: (com.goodlight.floatingvoicebubble.model.ImportedGemmaModel) -> Unit,
    onFailure: (String) -> Unit,
    activity: CorrectionSetupActivity,
) {
    OutlinedButton(
        onClick = {
            onStart(entry)
            Thread({
                runCatching {
                    OfficialModelInstaller(activity).install(entry) { p -> activity.runOnUiThread { onProgress(p) } }
                }.onSuccess { installed -> activity.runOnUiThread {
                    val gemma = installed as? InstalledOfficialModel.Gemma
                    if (gemma == null) onFailure("Gemma以外のモデルが返されました。") else onSuccess(gemma.model)
                } }.onFailure { failure -> activity.runOnUiThread {
                    onFailure(failure.message ?: failure.javaClass.simpleName)
                } }
            }, "VoiceBubble-GemmaInstall").start()
        },
        enabled = enabled,
    ) { Text(title) }
}

private fun formatTokenCount(value: Long): String = when {
    value >= 1_000_000 -> "%.1fM".format(Locale.US, value / 1_000_000.0)
    value >= 1_000 -> "%.0fk".format(Locale.US, value / 1_000.0)
    else -> value.toString()
}

private fun formatPrice(value: Double): String = when {
    value == 0.0 -> "0"
    value < 0.01 -> "%.4f".format(Locale.US, value)
    value < 1.0 -> "%.3f".format(Locale.US, value)
    else -> "%.2f".format(Locale.US, value)
}

private fun formatProgress(progress: ModelInstallProgress): String {
    val doneMiB = progress.completedBytes / (1024.0 * 1024.0)
    val total = progress.totalBytes
    return if (total != null && total > 0L) {
        val totalMiB = total / (1024.0 * 1024.0)
        val percent = (progress.completedBytes * 100.0 / total).coerceIn(0.0, 100.0)
        "%s  %.1f / %.1f MiB  %.0f%%".format(progress.phase, doneMiB, totalMiB, percent)
    } else {
        "%s  %.1f MiB".format(progress.phase, doneMiB)
    }
}

private const val MAX_VISIBLE_MODELS = 80
