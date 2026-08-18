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

    Card(
        modifier = modifier.testTag("primary-correction-card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("AI補正", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    modelSummary(settings),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { activity.startActivity(Intent(activity, CorrectionSetupActivity::class.java)) }) {
                    Text("モデル")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                CompactChoiceField(
                    title = "補正方式",
                    value = settings.correctionMode,
                    values = CorrectionMode.entries,
                    label = ::correctionModeLabel,
                    testTag = "correction-mode-control",
                    modifier = Modifier.weight(1f),
                ) { mode ->
                    settings = store.update { it.copy(correctionMode = mode) }
                }
                CompactChoiceField(
                    title = "シンキング",
                    value = settings.reasoningEffort,
                    values = ReasoningEffort.entries,
                    label = ::reasoningLabel,
                    testTag = "reasoning-effort-control",
                    modifier = Modifier.weight(1f),
                ) { effort ->
                    settings = store.update { it.copy(reasoningEffort = effort) }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                CompactChoiceField(
                    title = "聞き取りミス修復",
                    value = settings.recognitionRepairMode,
                    values = RecognitionRepairMode.entries,
                    label = ::repairLabel,
                    testTag = "repair-strength-control",
                    modifier = Modifier.weight(1f),
                ) { mode ->
                    settings = store.update { it.copy(recognitionRepairMode = mode) }
                }
                CompactChoiceField(
                    title = "口調",
                    value = registerValue(settings),
                    values = RegisterChoice.entries,
                    label = ::registerLabel,
                    testTag = "register-control",
                    modifier = Modifier.weight(1f),
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
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                CompactChoiceField(
                    title = "改行",
                    value = settings.correctionLineBreakMode,
                    values = LineBreakMode.entries,
                    label = ::lineBreakLabel,
                    testTag = "line-break-control",
                    modifier = Modifier.weight(1f),
                ) { mode ->
                    settings = store.update { it.copy(correctionLineBreakMode = mode) }
                }
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    ToggleChip("、", settings.correctionAddCommas) { checked ->
                        settings = store.update { it.copy(correctionAddCommas = checked) }
                    }
                    ToggleChip("。", settings.correctionAddPeriods) { checked ->
                        settings = store.update { it.copy(correctionAddPeriods = checked) }
                    }
                    ToggleChip("フィラー", settings.correctionRemoveFillers) { checked ->
                        settings = store.update { it.copy(correctionRemoveFillers = checked) }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> CompactChoiceField(
    title: String,
    value: T,
    values: List<T>,
    label: (T) -> String,
    testTag: String,
    modifier: Modifier = Modifier,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(testTag)
                    .heightIn(min = 34.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
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

private enum class RegisterChoice {
    PRESERVE,
    POLITE,
    BUSINESS,
}

private fun registerValue(settings: AppSettings): RegisterChoice = when {
    settings.correctionBusinessPolite -> RegisterChoice.BUSINESS
    settings.correctionPolite -> RegisterChoice.POLITE
    else -> RegisterChoice.PRESERVE
}

private fun registerLabel(value: RegisterChoice): String = when (value) {
    RegisterChoice.PRESERVE -> "そのまま"
    RegisterChoice.POLITE -> "です・ます"
    RegisterChoice.BUSINESS -> "ビジネス敬語"
}

private fun correctionModeLabel(mode: CorrectionMode): String = when (mode) {
    CorrectionMode.AUTO -> "自動"
    CorrectionMode.BYOK -> "クラウド"
    CorrectionMode.GEMMA -> "端末内Gemma"
    CorrectionMode.NONE -> "補正なし"
}

private fun repairLabel(mode: RecognitionRepairMode): String = when (mode) {
    RecognitionRepairMode.OFF -> "なし"
    RecognitionRepairMode.NORMAL -> "標準"
    RecognitionRepairMode.STRONG -> "強め"
}

private fun reasoningLabel(value: ReasoningEffort): String = when (value) {
    ReasoningEffort.DEFAULT -> "既定"
    ReasoningEffort.NONE -> "なし"
    ReasoningEffort.MINIMAL -> "最小"
    ReasoningEffort.LOW -> "低"
    ReasoningEffort.MEDIUM -> "中"
    ReasoningEffort.HIGH -> "高"
    ReasoningEffort.XHIGH -> "xhigh"
    ReasoningEffort.MAX -> "max"
}

private fun lineBreakLabel(mode: LineBreakMode): String = when (mode) {
    LineBreakMode.NONE -> "なし"
    LineBreakMode.SMART -> "適宜改行"
    LineBreakMode.SMART_SPACED -> "改行＋空行"
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
