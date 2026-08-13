package com.goodlight.floatingvoicebubble

import android.content.pm.PackageManager
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
import androidx.compose.ui.unit.dp

class AppProfilesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VoiceBubbleTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    AppProfilesScreen(this)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppProfilesScreen(activity: AppProfilesActivity) {
    val store = remember(activity) { AppProfileStore(activity) }
    var profiles by remember { mutableStateOf(store.profiles().associateBy { it.packageName }) }
    var recents by remember { mutableStateOf(store.recentPackages()) }
    var manualPackage by remember { mutableStateOf("") }
    var editingPackage by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }

    fun refresh() {
        profiles = store.profiles().associateBy { it.packageName }
        recents = store.recentPackages()
    }

    val packages = (profiles.keys + recents)
        .asSequence()
        .filter { it != activity.packageName }
        .distinct()
        .toList()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("アプリ別設定", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "変更した項目だけグローバル設定へ上書きします。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = activity::finish) { Text("戻る") }
        }

        Text(
            "入力先アプリはVoiceBubbleを使うと自動で候補へ記録されます。先に設定したい場合はpackage nameを直接追加できます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = manualPackage,
                onValueChange = { manualPackage = it.trim() },
                label = { Text("package name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val packageName = manualPackage.trim()
                    if (!AppCorrectionProfileCodec.isValidPackageName(packageName)) {
                        message = "package nameが不正です。例: com.google.android.gm"
                    } else {
                        val profile = profiles[packageName] ?: AppCorrectionProfile(packageName)
                        store.save(profile)
                        manualPackage = ""
                        editingPackage = packageName
                        message = "個別設定を作成しました。"
                        refresh()
                    }
                },
                enabled = manualPackage.isNotBlank(),
            ) { Text("追加") }
        }

        if (message.isNotBlank()) {
            Text(
                message,
                color = if ("不正" in message || "失敗" in message) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        HorizontalDivider()

        if (packages.isEmpty()) {
            Text(
                "まだ入力先アプリの履歴がありません。Gmail等でキーボードを開きVoiceBubbleを一度使うと、ここへ自動で現れます。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        packages.forEach { packageName ->
            val profile = profiles[packageName]
            val label = remember(packageName) { appLabel(activity, packageName) }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editingPackage = if (editingPackage == packageName) null else packageName },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            profile?.let(::profileSummary) ?: "グローバル設定を使用",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (profile?.enabled == true) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { editingPackage = if (editingPackage == packageName) null else packageName }) {
                        Text(if (editingPackage == packageName) "閉じる" else "設定")
                    }
                }

                if (editingPackage == packageName) {
                    val editable = profile ?: AppCorrectionProfile(packageName)
                    AppProfileEditor(
                        profile = editable,
                        exists = profile != null,
                        onSave = { updated ->
                            store.save(updated)
                            message = "$label の設定を保存しました。"
                            refresh()
                        },
                        onDelete = {
                            store.delete(packageName)
                            message = "$label の個別設定を削除しました。"
                            editingPackage = null
                            refresh()
                        },
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppProfileEditor(
    profile: AppCorrectionProfile,
    exists: Boolean,
    onSave: (AppCorrectionProfile) -> Unit,
    onDelete: () -> Unit,
) {
    var draft by remember(profile) { mutableStateOf(profile) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("補正エンジン", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProfileCorrectionMode.entries.forEach { mode ->
                FilterChip(
                    selected = draft.correctionMode == mode,
                    onClick = { draft = draft.copy(correctionMode = mode) },
                    label = { Text(correctionModeLabel(mode)) },
                )
            }
        }

        ProfileToggleSelector("読点「、」", draft.addCommas) { draft = draft.copy(addCommas = it) }
        ProfileToggleSelector("句点「。」", draft.addPeriods) { draft = draft.copy(addPeriods = it) }
        ProfileToggleSelector("フィラー除去", draft.removeFillers) { draft = draft.copy(removeFillers = it) }

        Text("語調", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProfileRegister.entries.forEach { register ->
                FilterChip(
                    selected = draft.register == register,
                    onClick = { draft = draft.copy(register = register) },
                    label = { Text(registerLabel(register)) },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { onSave(draft.copy(enabled = true)) }) {
                Text(if (exists) "保存" else "個別設定を作成")
            }
            if (exists) {
                OutlinedButton(onClick = onDelete) { Text("個別設定を削除") }
            }
        }
        Text(
            "「既定」はグローバル設定をそのまま継承します。「変更なし」はこのアプリだけ丁寧語/ビジネス敬語を明示的に無効化します。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileToggleSelector(label: String, value: ProfileToggle, onChange: (ProfileToggle) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProfileToggle.entries.forEach { option ->
                FilterChip(
                    selected = value == option,
                    onClick = { onChange(option) },
                    label = {
                        Text(
                            when (option) {
                                ProfileToggle.INHERIT -> "既定"
                                ProfileToggle.ON -> "ON"
                                ProfileToggle.OFF -> "OFF"
                            },
                        )
                    },
                )
            }
        }
    }
}

private fun appLabel(activity: AppProfilesActivity, packageName: String): String = runCatching {
    val info = activity.packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
    activity.packageManager.getApplicationLabel(info).toString().ifBlank { packageName }
}.getOrDefault(packageName)

private fun profileSummary(profile: AppCorrectionProfile): String {
    if (!profile.enabled) return "個別設定OFF"
    val parts = buildList {
        if (profile.correctionMode != ProfileCorrectionMode.INHERIT) add("補正=${correctionModeLabel(profile.correctionMode)}")
        if (profile.addCommas != ProfileToggle.INHERIT) add("、=${profile.addCommas.name}")
        if (profile.addPeriods != ProfileToggle.INHERIT) add("。=${profile.addPeriods.name}")
        if (profile.removeFillers != ProfileToggle.INHERIT) add("フィラー=${profile.removeFillers.name}")
        if (profile.register != ProfileRegister.INHERIT) add("語調=${registerLabel(profile.register)}")
    }
    return if (parts.isEmpty()) "個別設定あり（全項目を既定から継承）" else parts.joinToString(" / ")
}

private fun correctionModeLabel(mode: ProfileCorrectionMode): String = when (mode) {
    ProfileCorrectionMode.INHERIT -> "既定"
    ProfileCorrectionMode.AUTO -> "自動"
    ProfileCorrectionMode.BYOK -> "BYOK"
    ProfileCorrectionMode.GEMMA -> "Gemma"
    ProfileCorrectionMode.NONE -> "補正なし"
}

private fun registerLabel(register: ProfileRegister): String = when (register) {
    ProfileRegister.INHERIT -> "既定"
    ProfileRegister.PLAIN -> "変更なし"
    ProfileRegister.POLITE -> "丁寧語"
    ProfileRegister.BUSINESS -> "ビジネス敬語"
}
