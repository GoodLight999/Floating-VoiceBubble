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
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickCorrectionControls(activity: MainActivity, modifier: Modifier = Modifier) {
    val store = remember(activity) { SettingsStore(activity) }
    var settings by remember { mutableStateOf(store.load()) }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("音声の整え方", style = MaterialTheme.typography.titleSmall)
                Text(
                    "選んだ処理だけを最終補正へ許可します",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = { activity.startActivity(Intent(activity, CorrectionSetupActivity::class.java)) }) {
                Text("API / Gemma")
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            CorrectionCheck("、", settings.correctionAddCommas) { checked ->
                settings = store.update { it.copy(correctionAddCommas = checked) }
            }
            CorrectionCheck("。", settings.correctionAddPeriods) { checked ->
                settings = store.update { it.copy(correctionAddPeriods = checked) }
            }
            CorrectionCheck("フィラー除去", settings.correctionRemoveFillers) { checked ->
                settings = store.update { it.copy(correctionRemoveFillers = checked) }
            }
            CorrectionCheck("丁寧語", settings.correctionPolite) { checked ->
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
