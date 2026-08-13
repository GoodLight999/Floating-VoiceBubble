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
    var modelFilter by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<ByokModelInfo>>(emptyList()) }
    var busyAction by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<ModelInstallProgress?>(null) }

    fun saveByok(): AppSettings {
        val updated = store.update { it.copy(byokEndpoint = endpoint.trim(), byokModel = model.trim()) }
        store.setApiKey(apiKey.trim())
        settings = updated
        return updated
    }

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
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("補正エンジン", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "BYOKと端末内Gemmaを実際に接続確認します。",
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
                                CorrectionMode.BYOK -> "BYOK"
                                CorrectionMode.GEMMA -> "Gemma"
                                CorrectionMode.NONE -> "補正なし"
                            },
                        )
                    },
                )
            }
        }

        HorizontalDivider()
        Text("BYOK", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "OpenAI互換は /v1 まで、Anthropic / Gemini はAPIルートまででも自動補完します。完全な生成URLでも動作します。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it },
            label = { Text("API URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    busyAction = "models"
                    message = "APIからモデル一覧を取得しています…"
                    Thread({
                        runCatching { ByokModelDiscovery().list(endpoint, apiKey.trim()) }
                            .onSuccess { fetched -> activity.runOnUiThread {
                                models = fetched
                                busyAction = null
                                message = "${fetched.size}件のモデルを取得しました。"
                            } }
                            .onFailure { failure -> activity.runOnUiThread {
                                busyAction = null
                                message = "モデル一覧を取得できませんでした: ${failure.message ?: failure.javaClass.simpleName}"
                            } }
                    }, "VoiceBubble-ModelDiscovery").start()
                },
                enabled = busyAction == null && endpoint.trim().startsWith("https://"),
            ) { Text("モデル一覧を取得") }
            Button(
                onClick = {
                    val saved = saveByok()
                    busyAction = "test"
                    message = "BYOKへ実リクエストを送っています…"
                    Thread({
                        val request = CorrectionRequest(
                            rawTranscript = "えー今日はがんだむを見に行く",
                            alternatives = listOf("えー今日はガンダムを見に行く"),
                            surroundingContext = "",
                            dictionaryTerms = emptyList(),
                            preferences = CorrectionPreferences(
                                addCommas = saved.correctionAddCommas,
                                addPeriods = saved.correctionAddPeriods,
                                removeFillers = saved.correctionRemoveFillers,
                                polite = saved.correctionPolite,
                                businessPolite = saved.correctionBusinessPolite,
                            ),
                        )
                        runCatching {
                            CloudCorrectorFactory.create(
                                saved.byokEndpoint,
                                saved.byokModel,
                                store.apiKey(),
                            ).correct(request)
                        }.onSuccess { output -> activity.runOnUiThread {
                            busyAction = null
                            message = "BYOK接続成功: ${output.take(120)}"
                        } }.onFailure { failure -> activity.runOnUiThread {
                            busyAction = null
                            message = "BYOK接続失敗: ${failure.message ?: failure.javaClass.simpleName}"
                        } }
                    }, "VoiceBubble-ByokTest").start()
                },
                enabled = busyAction == null && endpoint.trim().startsWith("https://") && model.isNotBlank(),
            ) { Text("保存して接続テスト") }
        }
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("モデルID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (models.isNotEmpty()) {
            OutlinedTextField(
                value = modelFilter,
                onValueChange = { modelFilter = it },
                label = { Text("取得したモデルを絞り込み") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val visible = models.asSequence()
                .filter { item ->
                    modelFilter.isBlank() || item.id.contains(modelFilter, ignoreCase = true) ||
                        item.displayName.contains(modelFilter, ignoreCase = true)
                }
                .take(MAX_VISIBLE_MODELS)
                .toList()
            Column {
                visible.forEach { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { model = item.id }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            item.id,
                            color = if (model == item.id) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (item.displayName != item.id) {
                            Text(
                                item.displayName,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
            if (visible.size == MAX_VISIBLE_MODELS) {
                Text(
                    "先頭${MAX_VISIBLE_MODELS}件を表示中です。絞り込みで目的のモデルを検索できます。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        HorizontalDivider()
        Text("端末内Gemma 4", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            if (settings.gemmaModelPath.isBlank()) "モデル未導入" else "導入済み: ${settings.gemmaVariant.name}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "公式LiteRT-LM artifactを直接取得し、サイズとSHA-256が一致した場合だけ端末内へ確定します。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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

        if (message.isNotBlank()) {
            HorizontalDivider()
            Text(
                message,
                color = if ("失敗" in message || "できません" in message) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
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

private const val MAX_VISIBLE_MODELS = 40
