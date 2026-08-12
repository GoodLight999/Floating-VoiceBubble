package com.goodlight.floatingvoicebubble.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

class ModelImporter(private val context: Context) {
    fun importGemma(uri: Uri): File {
        val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            ?.takeIf { it.isNotBlank() }
            ?: "gemma-model.litertlm"
        require(displayName.endsWith(".litertlm", ignoreCase = true)) {
            "LiteRT-LM の .litertlm モデルを選択してください。"
        }
        val modelDir = File(context.filesDir, "models").apply { mkdirs() }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val destination = File(modelDir, safeName)
        val temporary = File(modelDir, ".$safeName.part")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "モデルを開けませんでした。" }
            temporary.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        check(temporary.length() > 0L) { "モデルファイルが空です。" }
        if (destination.exists()) destination.delete()
        check(temporary.renameTo(destination)) { "モデルを保存できませんでした。" }
        return destination
    }
}
