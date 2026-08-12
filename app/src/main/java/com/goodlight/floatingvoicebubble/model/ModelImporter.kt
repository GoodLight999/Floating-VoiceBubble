package com.goodlight.floatingvoicebubble.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

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
        val modelDir = File(context.noBackupFilesDir, "models/correction").apply { mkdirs() }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val destination = File(modelDir, safeName)
        val temporary = File(modelDir, ".$safeName.part")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "モデルを開けませんでした。" }
                FileOutputStream(temporary).use { fileOutput ->
                    fileOutput.buffered().use { output -> input.copyTo(output) }
                }
            }
            check(temporary.length() >= MIN_MODEL_BYTES) { "モデルファイルが小さすぎるため拒否しました。" }
            FileOutputStream(temporary, true).use { it.fd.sync() }
            if (destination.exists()) check(destination.delete()) { "既存モデルを置き換えられませんでした。" }
            check(temporary.renameTo(destination)) { "モデルを保存できませんでした。" }
            return destination
        } catch (failure: Throwable) {
            temporary.delete()
            throw failure
        }
    }

    companion object {
        private const val MIN_MODEL_BYTES = 1L * 1024 * 1024
    }
}
