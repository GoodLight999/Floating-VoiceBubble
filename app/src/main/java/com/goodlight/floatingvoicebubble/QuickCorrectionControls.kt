package com.goodlight.floatingvoicebubble

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickCorrectionControls(activity: MainActivity, modifier: Modifier = Modifier) {
    val store = remember(activity) { SettingsStore(activity) }
    var settings by remember { mutableStateOf(store.load()) }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("文章をどう整える？", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "ONにした変更だけを、音声認識のあとに適用します。話した内容そのものは増減しません。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row {
                TextButton(onClick = { activity.startActivity(Intent(activity, AppProfilesActivity::class.java)) }) {
                    Text("アプリ別")
                }
                TextButton(onClick = { activity.startActivity(Intent(activity, CorrectionSetupActivity::class.java)) }) {
                    Text("補正モデル")
                }
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            CorrectionCheck("読点 、", settings.correctionAddCommas) { checked ->
                settings = store.update { it.copy(correctionAddCommas = checked) }
            }
            CorrectionCheck("句点 。", settings.correctionAddPeriods) { checked ->
                settings = store.update { it.copy(correctionAddPeriods = checked) }
            }
            CorrectionCheck("えー・あの等を削除", settings.correctionRemoveFillers) { checked ->
                settings = store.update { it.copy(correctionRemoveFillers = checked) }
            }
            CorrectionCheck("です・ます調", settings.correctionPolite) { checked ->
                settings = store.update {
                    it.copy(correctionPolite = checked, correctionBusinessPolite = if (checked) false else it.correctionBusinessPolite)
                }
            }
            CorrectionCheck("ビジネス敬語", settings.correctionBusinessPolite) { checked ->
                settings = store.update {
                    it.copy(correctionBusinessPolite = checked, correctionPolite = if (checked) false else it.correctionPolite)
                }
            }
        }

        Text(
            "改行",
            modifier = Modifier.padding(start = 20.dp, top = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "長めに話したとき、話題の切れ目だけで文章を分けます。",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LineBreakMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.correctionLineBreakMode == mode,
                    onClick = { settings = store.update { it.copy(correctionLineBreakMode = mode) } },
                    label = {
                        Text(
                            when (mode) {
                                LineBreakMode.NONE -> "改行しない"
                                LineBreakMode.SMART -> "適宜改行"
                                LineBreakMode.SMART_SPACED -> "適宜改行＋空行"
                            },
                        )
                    },
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun CorrectionCheck(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Checkbox) { onChecked(!checked) }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}