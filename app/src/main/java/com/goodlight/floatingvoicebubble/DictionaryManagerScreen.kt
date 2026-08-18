package com.goodlight.floatingvoicebubble

import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goodlight.floatingvoicebubble.dictionary.DictionarySort
import com.goodlight.floatingvoicebubble.dictionary.DictionaryTerm
import com.goodlight.floatingvoicebubble.dictionary.PersonalDictionary

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DictionaryManagerScreen(
    activity: ComponentActivity,
    onBack: () -> Unit,
) {
    val dictionary = remember(activity) { PersonalDictionary(activity) }
    DisposableEffect(dictionary) { onDispose { dictionary.close() } }

    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(DictionarySort.PRIORITY) }
    var rows by remember(query, sort) { mutableStateOf(dictionary.search(query, limit = PAGE_SIZE, sort = sort)) }
    var offset by remember(query, sort) { mutableIntStateOf(rows.size) }
    var hasMore by remember(query, sort) { mutableStateOf(rows.size == PAGE_SIZE) }
    var total by remember { mutableLongStateOf(dictionary.count()) }
    var message by rememberSaveable { mutableStateOf("") }

    var editing by rememberSaveable { mutableStateOf(false) }
    var originalTerm by rememberSaveable { mutableStateOf<String?>(null) }
    var editTerm by rememberSaveable { mutableStateOf("") }
    var editReading by rememberSaveable { mutableStateOf("") }
    var editAliases by rememberSaveable { mutableStateOf("") }
    var editWeight by rememberSaveable { mutableStateOf("100") }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    fun reload(newQuery: String = query, newSort: DictionarySort = sort) {
        val first = dictionary.search(newQuery, limit = PAGE_SIZE, offset = 0, sort = newSort)
        rows = first
        offset = first.size
        hasMore = first.size == PAGE_SIZE
        total = dictionary.count()
    }

    fun loadMore() {
        if (!hasMore) return
        val next = dictionary.search(query, limit = PAGE_SIZE, offset = offset, sort = sort)
        rows = rows + next
        offset += next.size
        hasMore = next.size == PAGE_SIZE
    }

    fun openNew() {
        originalTerm = null
        editTerm = query.takeIf(String::isNotBlank).orEmpty()
        editReading = ""
        editAliases = ""
        editWeight = "100"
        confirmDelete = false
        editing = true
    }

    fun openEdit(row: DictionaryTerm) {
        originalTerm = row.term
        editTerm = row.term
        editReading = row.reading
        editAliases = row.aliases.joinToString("\n")
        editWeight = row.weight.toString()
        confirmDelete = false
        editing = true
    }

    val dictionaryImport = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            activity.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("辞書ファイルを開けませんでした。")
        }.onSuccess { text ->
            runCatching { dictionary.importText(text) }
                .onSuccess { result ->
                    reload()
                    message = "${result.imported}件を取り込みました${if (result.skipped > 0) " / ${result.skipped}件スキップ" else ""}。"
                }
                .onFailure { message = "辞書取込失敗: ${it.message ?: it.javaClass.simpleName}" }
        }.onFailure { message = "辞書取込失敗: ${it.message ?: it.javaClass.simpleName}" }
    }

    val dictionaryExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/tab-separated-values"),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            activity.contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write(dictionary.exportTsv())
            } ?: error("辞書エクスポート先を開けませんでした。")
        }.onSuccess { message = "個人辞書をTSVへ出力しました。" }
            .onFailure { message = "辞書出力失敗: ${it.message ?: it.javaClass.simpleName}" }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("個人辞書", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "$total 件 · 音声認識と補正の両方で使用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = if (editing) ({ editing = false }) else onBack) {
                Text(if (editing) "一覧へ" else "戻る")
            }
        }

        if (message.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { message = "" }) { Text("閉じる") }
                }
            }
        }

        if (editing) {
            DictionaryEditor(
                originalTerm = originalTerm,
                term = editTerm,
                reading = editReading,
                aliases = editAliases,
                weight = editWeight,
                onTermChange = { editTerm = it },
                onReadingChange = { editReading = it },
                onAliasesChange = { editAliases = it },
                onWeightChange = { editWeight = it.filter(Char::isDigit).take(5) },
                onWeightPreset = { editWeight = it.toString() },
                onSave = {
                    runCatching {
                        dictionary.save(
                            originalTerm = originalTerm,
                            term = DictionaryTerm(
                                term = editTerm,
                                reading = editReading,
                                aliases = parseAliases(editAliases),
                                weight = editWeight.toIntOrNull() ?: 100,
                            ),
                        )
                    }.onSuccess {
                        val saved = editTerm.trim()
                        val old = originalTerm
                        editing = false
                        query = ""
                        reload(newQuery = "")
                        message = if (old != null && old != saved) {
                            "「$old」を「$saved」へ変更しました。"
                        } else {
                            "「$saved」を保存しました。"
                        }
                    }.onFailure {
                        message = when {
                            it.message?.contains("already exists") == true -> "同じ見出し語が既にあります。既存項目を編集してください。"
                            else -> "辞書保存失敗: ${it.message ?: it.javaClass.simpleName}"
                        }
                    }
                },
                onDelete = { confirmDelete = true },
                onCancel = { editing = false },
            )
        } else {
            OutlinedTextField(
                value = query,
                onValueChange = { value ->
                    query = value
                    reload(newQuery = value)
                },
                label = { Text("検索") },
                placeholder = { Text("見出し語・読み・別名を即時検索") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (query.isNotBlank()) {
                        TextButton(onClick = {
                            query = ""
                            reload(newQuery = "")
                        }) { Text("消去") }
                    }
                },
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DictionarySort.entries.forEach { candidate ->
                    FilterChip(
                        selected = sort == candidate,
                        onClick = {
                            sort = candidate
                            reload(newSort = candidate)
                        },
                        label = { Text(sortLabel(candidate)) },
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Button(onClick = ::openNew) { Text("＋ 新規登録") }
                OutlinedButton(onClick = { dictionaryImport.launch("text/*") }) { Text("取込") }
                OutlinedButton(onClick = { dictionaryExport.launch("voicebubble-dictionary.tsv") }) { Text("TSV出力") }
            }

            Text(
                if (query.isBlank()) "${total}件中 ${rows.size}件を表示"
                else "「$query」の検索結果 ${rows.size}件${if (hasMore) "以上" else ""}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(rows, key = { it.term }) { row ->
                    DictionaryRow(row = row, onClick = { openEdit(row) })
                    HorizontalDivider()
                }
                if (hasMore) {
                    item {
                        TextButton(onClick = ::loadMore, modifier = Modifier.fillMaxWidth()) {
                            Text("さらに100件表示")
                        }
                    }
                }
                if (rows.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("該当する単語はありません。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = ::openNew) { Text("この語を登録") }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        val target = originalTerm.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("辞書から削除") },
            text = { Text("「$target」を削除します。元には戻せません。") },
            confirmButton = {
                Button(onClick = {
                    message = if (dictionary.delete(target)) {
                        "「$target」を削除しました。"
                    } else {
                        "削除対象が見つかりませんでした。"
                    }
                    confirmDelete = false
                    editing = false
                    reload()
                }) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("キャンセル") }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.DictionaryEditor(
    originalTerm: String?,
    term: String,
    reading: String,
    aliases: String,
    weight: String,
    onTermChange: (String) -> Unit,
    onReadingChange: (String) -> Unit,
    onAliasesChange: (String) -> Unit,
    onWeightChange: (String) -> Unit,
    onWeightPreset: (Int) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (originalTerm == null) "新しい単語を登録" else "辞書項目を編集",
            modifier = Modifier.testTag("dictionary-editor-title"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "見出し語を変更して保存すると、旧項目を残さず正しく名前変更します。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = term,
            onValueChange = onTermChange,
            label = { Text("見出し語 *") },
            placeholder = { Text("例: Floating VoiceBubble") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("dictionary-term-field"),
        )
        OutlinedTextField(
            value = reading,
            onValueChange = onReadingChange,
            label = { Text("読み") },
            placeholder = { Text("例: ふろーてぃんぐぼいすばぶる") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = aliases,
            onValueChange = onAliasesChange,
            label = { Text("別名・誤認識候補") },
            supportingText = { Text("1行1件。過去に誤認識された表記や略称も入れられます。") },
            minLines = 4,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("優先度", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(100 to "標準", 500 to "強め", 1000 to "最優先").forEach { (value, label) ->
                FilterChip(
                    selected = weight.toIntOrNull() == value,
                    onClick = { onWeightPreset(value) },
                    label = { Text(label) },
                )
            }
        }
        OutlinedTextField(
            value = weight,
            onValueChange = onWeightChange,
            label = { Text("優先度の数値 1–10000") },
            supportingText = { Text("通常は上の3段階だけで十分です。") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(onClick = onSave, enabled = term.isNotBlank()) { Text("保存") }
            OutlinedButton(onClick = onCancel) { Text("キャンセル") }
            if (originalTerm != null) TextButton(onClick = onDelete) { Text("削除") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DictionaryRow(row: DictionaryTerm, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp, horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(row.term, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(priorityLabel(row.weight), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (row.reading.isNotBlank()) {
            Text(row.reading, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (row.aliases.isNotEmpty()) {
            val preview = row.aliases.take(4).joinToString(" · ") + if (row.aliases.size > 4) " · …" else ""
            Text("別名: $preview", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "weight ${row.weight} · 使用 ${row.useCount}回",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun parseAliases(text: String): List<String> = text
    .lineSequence()
    .flatMap { it.split('|', '／', ',', '、').asSequence() }
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinctBy { it.lowercase() }
    .toList()

private fun sortLabel(sort: DictionarySort): String = when (sort) {
    DictionarySort.PRIORITY -> "優先度"
    DictionarySort.MOST_USED -> "よく使う"
    DictionarySort.RECENT -> "最近編集"
    DictionarySort.TERM -> "名前順"
}

private fun priorityLabel(weight: Int): String = when {
    weight >= 1000 -> "最優先"
    weight >= 500 -> "強め"
    else -> "標準"
}

private const val PAGE_SIZE = 100
