package com.goodlight.floatingvoicebubble

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.goodlight.floatingvoicebubble.correction.ReasoningCapabilities

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickCorrectionControls(
    activity: MainActivity,
    refreshRevision: Int,
    modifier: Modifier = Modifier,
) {
    val store = remember(activity) { SettingsStore(activity) }
    var settings by remember { mutableStateOf(store.load()) }

    LaunchedEffect(refreshRevision) {
        settings = store.load()
    }

    val cloudReasoningVisible = settings.correctionMode == CorrectionMode.BYOK ||
        (settings.correctionMode == CorrectionMode.AUTO && settings.byokModel.isNotBlank())
    val reasoningCapability = remember(settings.byokEndpoint, settings.byokModel) {
        ReasoningCapabilities.capability(settings.byokEndpoint, settings.byokModel)
    }
    val normalizedReasoning = ReasoningCapabilities.normalize(
        settings.byokEndpoint,
        settings.byokModel,
        settings.reasoningEffort,
    )

    Card(
        modifier = modifier.testTag("primary-correction-card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("文章補正", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    modelSummary(settings),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { activity.startActivity(Intent(activity, CorrectionSetupActivity::class.java)) }) {
                    Text("モデル・API")
                }
            }

            ChoiceRowField(
                title = "補正に使うもの",
                value = settings.correctionMode,
                values = CorrectionMode.entries,
                label = ::correctionModeLabel,
                testTag = "correction-mode-control",
            ) { mode ->
                settings = store.update { it.copy(correctionMode = mode) }
            }

            if (cloudReasoningVisible) {
                ChoiceRowField(
                    title = "推論の深さ",
                    value = normalizedReasoning,
                    values = reasoningCapability.choices,
                    label = { ReasoningCapabilities.label(settings.byokEndpoint, settings.byokModel, it) },
                    testTag = "reasoning-effort-control",
                ) { effort ->
                    settings = store.update { it.copy(reasoningEffort = effort) }
                }
                Text(
                    reasoningCapability.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ChoiceRowField(
                title = "聞き取り間違いを直す強さ",
                value = settings.recognitionRepairMode,
                values = RecognitionRepairMode.entries,
                label = ::repairLabel,
                testTag = "repair-strength-control",
            ) { mode ->
                settings = store.update { it.copy(recognitionRepairMode = mode) }
            }

            ChoiceRowField(
                title = "話し方",
                value = registerValue(settings),
                values = RegisterChoice.entries,
                label = ::registerLabel,
                testTag = "register-control",
            ) { choice ->
                settings = store.update {
                    when (choice) {
                        RegisterChoice.PRESERVE -> it.copy(
                            correctionPolite = false,
                            correctionBusinessPolite = false,
                        )
                        RegisterChoice.POLITE -> it.copy(
                            correctionPolite = true,
                            correctionBusinessPolite = false,
                        )
                        RegisterChoice.BUSINESS -> it.copy(
                            correctionPolite = false,
                            correctionBusinessPolite = true,
                        )
                    }
                }
            }

            ChoiceRowField(
                title = "改行",
                value = settings.correctionLineBreakMode,
                values = LineBreakMode.entries,
                label = ::lineBreakLabel,
                testTag = "line-break-control",
            ) { mode ->
                settings = store.update { it.copy(correctionLineBreakMode = mode) }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ToggleChip("読点「、」を追加", settings.correctionAddCommas) { checked ->
                    settings = store.update { it.copy(correctionAddCommas = checked) }
                }
                ToggleChip("句点「。」を追加", settings.correctionAddPeriods) { checked ->
                    settings = store.update { it.copy(correctionAddPeriods = checked) }
                }
                ToggleChip("「えー」「あのー」等を削除", settings.correctionRemoveFillers) { checked ->
                    settings = store.update { it.copy(correctionRemoveFillers = checked) }
                }
            }
        }
    }
}

@Composable
private fun <T> ChoiceRowField(
    title: String,
    value: T,
    values: List<T>,
    label: (T) -> String,
    testTag: String,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier
                    .testTag(testTag)
                    .heightIn(min = 38.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) {
                Text(label(value), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(" ▾")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                values.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(label(option)) },
                        onClick = {
                            expanded = false
                            onSelected(option)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleChip(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    FilterChip(selected = checked, onClick = { onChecked(!checked) }, label = { Text(label) })
}

private enum class RegisterChoice { PRESERVE, POLITE, BUSINESS }

private fun registerValue(settings: AppSettings): RegisterChoice = when {
    settings.correctionBusinessPolite -> RegisterChoice.BUSINESS
    settings.correctionPolite -> RegisterChoice.POLITE
    else -> RegisterChoice.PRESERVE
}

private fun registerLabel(value: RegisterChoice): String = when (value) {
    RegisterChoice.PRESERVE -> "話したまま"
    RegisterChoice.POLITE -> "です・ます調に変換"
    RegisterChoice.BUSINESS -> "ビジネス敬語に変換"
}

private fun correctionModeLabel(mode: CorrectionMode): String = when (mode) {
    CorrectionMode.AUTO -> "自動選択"
    CorrectionMode.BYOK -> "クラウドAPI"
    CorrectionMode.GEMMA -> "端末内Gemma"
    CorrectionMode.NONE -> "補正しない"
}

private fun repairLabel(mode: RecognitionRepairMode): String = when (mode) {
    RecognitionRepairMode.OFF -> "語句は直さない"
    RecognitionRepairMode.NORMAL -> "明らかな間違いだけ"
    RecognitionRepairMode.STRONG -> "文脈・候補から積極修復"
}

private fun lineBreakLabel(mode: LineBreakMode): String = when (mode) {
    LineBreakMode.NONE -> "追加しない"
    LineBreakMode.SMART -> "文・話題の区切りで改行"
    LineBreakMode.SMART_SPACED -> "段落の間を1行空ける"
}

private fun modelSummary(settings: AppSettings): String = when (settings.correctionMode) {
    CorrectionMode.NONE -> "補正なし"
    CorrectionMode.BYOK -> settings.byokModel.ifBlank { "クラウドモデル未選択" }
    CorrectionMode.GEMMA -> when {
        settings.gemmaModelPath.isBlank() -> "Gemma未選択"
        settings.gemmaVariant == GemmaVariant.UNKNOWN -> "端末内Gemma"
        else -> "Gemma ${settings.gemmaVariant.name}"
    }
    CorrectionMode.AUTO -> when {
        settings.byokModel.isNotBlank() -> settings.byokModel
        settings.gemmaModelPath.isNotBlank() && settings.gemmaVariant != GemmaVariant.UNKNOWN ->
            "Gemma ${settings.gemmaVariant.name}"
        settings.gemmaModelPath.isNotBlank() -> "端末内Gemma"
        else -> "モデル未設定"
    }
}
