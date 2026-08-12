package com.goodlight.floatingvoicebubble.model

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

internal object ModelArchiveExtractor {
    fun extractTarBz2(
        source: InputStream,
        destination: File,
        requiredNames: Set<String>,
        onChunk: (() -> Unit)? = null,
    ) {
        require(destination.isDirectory) { "モデル展開先がありません。" }
        require(requiredNames.isNotEmpty()) { "モデルの必要ファイルが指定されていません。" }
        requiredNames.forEach { name ->
            require(name.isNotBlank() && '/' !in name && '\\' !in name) { "不正なモデルファイル名です: $name" }
        }

        BZip2CompressorInputStream(source, true).use { bzip ->
            TarArchiveInputStream(bzip).use { tar ->
                val found = mutableSetOf<String>()
                while (true) {
                    val entry = tar.nextEntry ?: break
                    if (!entry.isFile) continue
                    // Never materialize an archive path. Only a reviewed required basename can be emitted.
                    val baseName = entry.name.substringAfterLast('/').substringAfterLast('\\')
                    if (baseName !in requiredNames) continue
                    check(found.add(baseName)) { "モデルアーカイブ内で $baseName が重複しています。" }
                    val outputFile = File(destination, baseName)
                    check(outputFile.canonicalFile.parentFile == destination.canonicalFile) {
                        "モデルアーカイブの展開先が不正です。"
                    }
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            val read = tar.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            onChunk?.invoke()
                        }
                        output.fd.sync()
                    }
                }
                val missing = requiredNames - found
                require(missing.isEmpty()) {
                    "モデルアーカイブに必要ファイルがありません: ${missing.sorted().joinToString()}"
                }
            }
        }
    }

    private const val COPY_BUFFER_BYTES = 1024 * 1024
}
