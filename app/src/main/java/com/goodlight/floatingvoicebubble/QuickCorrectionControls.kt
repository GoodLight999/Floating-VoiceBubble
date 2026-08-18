package com.goodlight.floatingvoicebubble

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickCorrectionControls(activity: MainActivity, modifier: Modifier = Modifier) {
    val store = remember(activity) { SettingsStore(activity) }
    var settings by remember { mutableStateOf(store.load()) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("AI補正", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row {
                TextButton(onClick = { activity.startActivity(Intent(activity, AppProfilesActivity::class.java)) }) {
                    Text("アプリ別")
                }
                TextButton(onClick = { activity.startActivity(Intent(activity, CorrectionSetupActivity::class.java)) }) {
                    Text("モデル")
                }
            }
        }

        QuickLabel("使用する補正")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CorrectionMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.correctionMode == mode,
                    onClick = { settings = store.update { it.copy(correctionMode = mode) } },
                    label = { Text(correctionModeLabel(mode)) },
                )
            }
        }

        QuickLabel("聞き取りミス修復")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RecognitionRepairMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.recognitionRepairMode == mode,
                    onClick = { settings = store.update { it.copy(recognitionRepairMode = mode) } },
                    label = { Text(repairLabel(mode)) },
                )
            }
        }

        QuickLabel("シンキング深度")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            ReasoningEffort.entries.forEach { effort ->
                FilterChip(
                    selected = settings.reasoningEffort == effort,
                    onClick = { settings = store.update { it.copy(reasoningEffort = effort) } },
                    label = { Text(reasoningLabel(effort)) },
                )
            }
        }
        Text(
            "クラウド補正で使用。対応APIでは推論量を直接指定します。Z.AIは既定/なし/最小=thinking OFF、低以上=ONです。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )

        QuickLabel("整形")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            ToggleChip("読点 、", settings.correctionAddCommas) { checked ->
                settings = store.update { it.copy(correctionAddCommas = checked) }
            }
            ToggleChip("句点 。", settings.correctionAddPeriods) { checked ->
                settings = store.update { it.copy(correctionAddPeriods = checked) }
            }
            ToggleChip("フィラー削除", settings.correctionRemoveFillers) { checked ->
                settings = store.update { it.copy(correctionRemoveFillers = checked) }
            }
        }

        QuickLabel("口調")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            FilterChip(
                selected = !settings.correctionPolite && !settings.correctionBusinessPolite,
                onClick = {
                    settings = store.update { it.copy(correctionPolite = false, correctionBusinessPolite = false) }
                },
                label = { Text("そのまま") },
            )
            FilterChip(
                selected = settings.correctionPolite,
                onClick = {
                    settings = store.update { it.copy(correctionPolite = true, correctionBusinessPolite = false) }
                },
                label = { Text("です・ます") },
            )
            FilterChip(
                selected = settings.correctionBusinessPolite,
                onClick = {
                    settings = store.update { it.copy(correctionBusinessPolite = true, correctionPolite = false) }
                },
                label = { Text("ビジネス敬語") },
            )
        }

        QuickLabel("改行")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            LineBreakMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.correctionLineBreakMode == mode,
                    onClick = { settings = store.update { it.copy(correctionLineBreakMode = mode) } },
                    label = { Text(lineBreakLabel(mode)) },
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun QuickLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ToggleChip(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    FilterChip(selected = checked, onClick = { onChecked(!checked) }, label = { Text(label) })
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
