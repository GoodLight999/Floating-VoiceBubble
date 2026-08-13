package com.goodlight.floatingvoicebubble

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.goodlight.floatingvoicebubble.correction.ByokEndpointResolver
import com.goodlight.floatingvoicebubble.correction.ByokModelDiscovery
import com.goodlight.floatingvoicebubble.correction.ByokModelInfo
import com.goodlight.floatingvoicebubble.correction.CloudCorrectorFactory
import com.goodlight.floatingvoicebubble.correction.CorrectionPreferences
import com.goodlight.floatingvoicebubble.correction.CorrectionRequest
import com.goodlight.floatingvoicebubble.model.InstalledOfficialModel
import com.goodlight.floatingvoicebubble.model.ModelInstallProgress
import com.goodlight.floatingvoicebubble.model.OfficialModelCatalog
import com.goodlight.floatingvoicebubble.model.OfficialModelEntry
import com.goodlight.floatingvoicebubble.model.OfficialModelInstaller
import java.util.Locale

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
    var endpoint by remember { mutableStateOf(settings.byokEndpoint) }
    var apiKey by remember { mutableStateOf(store.apiKey()) }
    var model by remember { mutableStateOf(settings.byokModel) }
    var reasoningEffort by remember { mutableStateOf(settings.reasoningEffort) }
    var modelFilter by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<ByokModelInfo>>(emptyList()) }
    var busyAction by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<ModelInstallProgress?>(null) }

    fun saveByok(): AppSettings {
        val updated = store.update {
            it.copy(
                byokEndpoint = endpoint.trim(),
                byokModel = model.trim(),
                reasoningEffort = reasoningEffort,
            )
        }
        store.setApiKey(apiKey.trim())
        settings = updated
        return updated
    }

    fun connectionRequest(): CorrectionRequest = CorrectionRequest(
        rawTranscript = "えー今日はがんだむを見に行く",
        alternatives = listOf("えー今日はガンダムを見に行く"),
        surroundingContext = "",
        dictionaryTerms = emptyList(),
        preferences = CorrectionPreferences(
            addCommas = settings.correctionAddCommas,
            addPeriods = settings.correctionAddPeriods,
            removeFillers = settings.correctionRemoveFillers,
            polite = settings.correctionPolite,
            businessPolite = settings.correctionBusinessPolite,
            lineBreakMode = settings.correctionLineBreakMode,
        ),
    )

    val resolvedPreview = runCatching { ByokEndpointResolver.resolve(endpoint) }.getOrNull()
    val selectedModelInfo = models.firstOrNull { it.id == model }
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
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("補正モデル", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "APIを登録し、使えるモデルを一覧から選びます。モデルIDの手入力は通常不要です。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = activity::finish) { Text("戻る") }
        }

        Text("使用する補正", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CorrectionMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.correctionMode == mode,
                    onClick = { settings = store.update { it.copy(correctionMode = mode) } },
                    label = {
                        Text(
                            when (mode) {
                                CorrectionMode.AUTO -> "自動"
                                CorrectionMode.BYOK -> "クラウドAPI"
                                CorrectionMode.GEMMA -> "端末内Gemma"
                                CorrectionMode.NONE -> "補正なし"
                            },
                        )
                    },
                )
            }
        }

        HorizontalDivider()
        Text("クラウドAPI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "OpenAI / OpenRouter / OpenAI互換 / Anthropic / Geminiに対応します。URLとAPIキーを入れたら、次の「モデルを取得」を押してください。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it; models = emptyList(); message = "" },
            label = { Text("API URL") },
            supportingText = { Text("例: https://openrouter.ai  または providerのAPI URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; models = emptyList(); message = "" },
            label = { Text("APIキー") },
            supportingText = { Text("端末のAndroid Keystoreで暗号化して保存します") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        resolvedPreview?.let { resolved ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("接続先の確認", style = MaterialTheme.typography.labelLarge)
                    Text("生成: ${resolved.generationUrl}", style = MaterialTheme.typography.bodySmall)
                    Text("モデル取得: ${resolved.modelsUrl}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

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
                                "接続はできましたが、選択可能なテキスト生成モデルが0件でした。"
                            } else {
                                "${fetched.size}件取得しました。下の検索欄から選んでください。"
                            }
                        } }
                        .onFailure { failure -> activity.runOnUiThread {
                            busyAction = null
                            message = "モデル取得失敗: ${failure.message ?: failure.javaClass.simpleName}"
                        } }
                }, "VoiceBubble-ModelDiscovery").start()
            },
            enabled = busyAction == null && endpoint.trim().startsWith("https://"),
        ) { Text(if (busyAction == "models") "取得中…" else "APIからモデルを取得") }

        if (models.isNotEmpty()) {
            Text("モデルを選ぶ", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = modelFilter,
                onValueChange = { modelFilter = it },
                label = { Text("モデルを検索") },
                supportingText = { Text("名前・ID・説明文から絞り込みます") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${models.size}件中 ${filteredModels.size}件を表示" +
                    if (models.size > MAX_VISIBLE_MODELS && modelFilter.isBlank()) "（検索すると絞れます）" else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                filteredModels.forEach { item ->
                    ModelRow(item = item, selected = model == item.id) { model = item.id }
                }
            }
        }

        if (models.isEmpty()) {
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("モデルID（一覧取得できないAPIだけ）") },
                supportingText = { Text("通常は上のモデル一覧から選択してください") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (model.isNotBlank()) {
            Text("選択中: $model", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }

        Text("推論の深さ", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "音声の整形は通常それほど深い推論を必要としません。遅い場合は「低」、難しい固有名詞や文脈判定を重視するなら上げます。「モデル既定」は余計なパラメータを送りません。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ReasoningEffort.entries.forEach { effort ->
                FilterChip(
                    selected = reasoningEffort == effort,
                    onClick = { reasoningEffort = effort },
                    label = { Text(reasoningLabel(effort)) },
                )
            }
        }
        selectedModelInfo?.let { info ->
            if (ByokEndpointResolver.isOpenRouter(endpoint) && info.supportedParameters.isNotEmpty()) {
                Text(
                    if (info.supportsReasoning) "このモデルはOpenRouter上でreasoning対応です。"
                    else "このモデルのOpenRouter metadataにはreasoning対応がありません。推論深度は「モデル既定」を推奨します。",
                    color = if (info.supportsReasoning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    busyAction = "test"
                    message = "選択したモデルへテスト送信しています…"
                    Thread({
                        runCatching {
                            CloudCorrectorFactory.create(
                                endpoint.trim(),
                                model.trim(),
                                apiKey.trim(),
                                reasoningEffort,
                            ).correct(connectionRequest())
                        }.onSuccess { output -> activity.runOnUiThread {
                            busyAction = null
                            message = "接続成功: ${output.take(160)}"
                        } }.onFailure { failure -> activity.runOnUiThread {
                            busyAction = null
                            message = "接続失敗: ${failure.message ?: failure.javaClass.simpleName}"
                        } }
                    }, "VoiceBubble-ByokTest").start()
                },
                enabled = busyAction == null && endpoint.trim().startsWith("https://") && model.isNotBlank(),
            ) { Text(if (busyAction == "test") "テスト中…" else "このモデルへ接続テスト") }
            Button(
                onClick = {
                    saveByok()
                    message = "API・モデル・推論深度を保存しました。"
                },
                enabled = busyAction == null && endpoint.trim().startsWith("https://") && model.isNotBlank(),
            ) { Text("設定を保存") }
        }

        if (message.isNotBlank()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if ("失敗" in message || "0件" in message) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Text(
                    message,
                    modifier = Modifier.padding(12.dp),
                    color = if ("失敗" in message || "0件" in message) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        HorizontalDivider()
        Text("端末内Gemma 4", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            if (settings.gemmaModelPath.isBlank()) "まだモデルを入れていません。" else "導入済み: ${settings.gemmaVariant.name}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "ネットを使わず補正したい場合のモデルです。E2Bは軽量、E4Bは精度寄りです。ダウンロード後にサイズとSHA-256を検証してから使います。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GemmaInstallButton(
                title = "E2Bを自動導入",
                entry = OfficialModelCatalog.gemmaE2B,
                enabled = busyAction == null,
                onStart = { busyAction = it.id; progress = null; message = "${it.title} を取得しています…" },
                onProgress = { progress = it },
                onSuccess = { installed ->
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
                title = "E4Bを自動導入",
                entry = OfficialModelCatalog.gemmaE4B,
                enabled = busyAction == null,
                onStart = { busyAction = it.id; progress = null; message = "${it.title} を取得しています…" },
                onProgress = { progress = it },
                onSuccess = { installed ->
                    val variant = installed.fingerprint.detectedVariant
                    settings = store.update { it.copy(gemmaModelPath = installed.file.absolutePath, gemmaVariant = variant) }
                    busyAction = null
                    progress = null
                    message = "Gemma ${variant.name} を検証して導入しました。"
                },
                onFailure = { error -> busyAction = null; progress = null; message = "Gemma導入失敗: $error" },
                activity = activity,
            )
        }
        progress?.let { p ->
            Text(formatProgress(p), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
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
            if (item.supportsReasoning) add("reasoning")
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

private fun reasoningLabel(value: ReasoningEffort): String = when (value) {
    ReasoningEffort.DEFAULT -> "モデル既定"
    ReasoningEffort.NONE -> "なし"
    ReasoningEffort.MINIMAL -> "最小"
    ReasoningEffort.LOW -> "低"
    ReasoningEffort.MEDIUM -> "中"
    ReasoningEffort.HIGH -> "高"
    ReasoningEffort.XHIGH -> "xhigh"
    ReasoningEffort.MAX -> "max"
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