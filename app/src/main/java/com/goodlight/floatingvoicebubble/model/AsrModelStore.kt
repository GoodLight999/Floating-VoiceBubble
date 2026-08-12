package com.goodlight.floatingvoicebubble.model

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

data class StreamingAsrModel(
    val id: String,
    val family: String,
    val chunkMs: Int,
    val root: File,
    val encoder: File,
    val decoder: File,
    val joiner: File,
    val tokens: File,
) {
    val totalBytes: Long
        get() = encoder.length() + decoder.length() + joiner.length() + tokens.length()

    val cacheKey: String
        get() = "$id:${encoder.length()}:${encoder.lastModified()}:${decoder.length()}:${joiner.length()}"
}

class AsrModelStore(context: Context) {
    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver
    private val rootDir = File(appContext.noBackupFilesDir, "models/asr").apply { mkdirs() }

    fun listInstalled(): List<StreamingAsrModel> = rootDir.listFiles()
        ?.asSequence()
        ?.filter { it.isDirectory }
        ?.mapNotNull { runCatching { loadModel(it) }.getOrNull() }
        ?.sortedBy { it.chunkMs }
        ?.toList()
        .orEmpty()

    fun resolve(id: String): StreamingAsrModel? {
        if (id.isBlank()) return null
        val directory = File(rootDir, id)
        if (!directory.isDirectory) return null
        return runCatching { loadModel(directory) }.getOrNull()
    }

    fun importNemotronTree(treeUri: Uri, declaredChunkMs: Int? = null): StreamingAsrModel {
        val tree = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: error("選択したフォルダを開けませんでした。")
        val source = locateModelDirectory(tree)
            ?: error("encoder.int8.onnx / decoder.int8.onnx / joiner.int8.onnx / tokens.txt を含むモデルフォルダを選択してください。")

        val inferredChunk = inferChunkMs(source.name.orEmpty()) ?: inferChunkMs(tree.name.orEmpty())
        if (inferredChunk != null && declaredChunkMs != null) {
            require(inferredChunk == declaredChunkMs) {
                "選択したモデルは ${inferredChunk}ms ですが、設定は ${declaredChunkMs}ms です。"
            }
        }
        val chunkMs = inferredChunk ?: declaredChunkMs
            ?: error("モデルのchunk幅を判定できません。80 / 160 / 560 / 1120ms のいずれかを指定してください。")
        require(chunkMs in SUPPORTED_CHUNKS) { "未対応のchunk幅です: ${chunkMs}ms" }

        val sources = REQUIRED_FILES.associateWith { name ->
            source.findFile(name)?.takeIf { it.isFile }
                ?: error("モデルに $name がありません。")
        }
        val reportedBytes = sources.values.sumOf { it.length().coerceAtLeast(0L) }
        if (reportedBytes > 0L) ensureDiskSpace(reportedBytes)

        val id = "nemotron35-${chunkMs}ms-int8"
        val temporary = File(rootDir, ".$id.part-${UUID.randomUUID()}")
        val destination = File(rootDir, id)
        temporary.mkdirs()

        try {
            sources.forEach { (name, document) -> copyDocument(document, File(temporary, name)) }
            val candidate = modelFromDirectory(id, chunkMs, temporary)
            validateModelFiles(candidate, strictSizes = true)
            writeManifest(candidate)

            AtomicDirectoryInstaller.replace(temporary, destination, "ASRモデル")
            return loadModel(destination)
        } catch (failure: Throwable) {
            temporary.deleteRecursively()
            throw failure
        }
    }

    fun remove(id: String): Boolean {
        if (id.isBlank() || id.contains('/') || id.contains('\\')) return false
        val target = File(rootDir, id)
        return !target.exists() || target.deleteRecursively()
    }

    internal fun validateModelFiles(model: StreamingAsrModel, strictSizes: Boolean = true) {
        REQUIRED_FILES.forEach { name ->
            val file = File(model.root, name)
            require(file.isFile && file.length() > 0L) { "$name が壊れているか空です。" }
        }
        if (strictSizes) {
            require(model.encoder.length() >= MIN_ENCODER_BYTES) { "encoder が小さすぎます。別のモデルまたは不完全なコピーの可能性があります。" }
            require(model.decoder.length() >= MIN_DECODER_BYTES) { "decoder が小さすぎます。" }
            require(model.joiner.length() >= MIN_JOINER_BYTES) { "joiner が小さすぎます。" }
            require(model.tokens.length() >= MIN_TOKENS_BYTES) { "tokens.txt が小さすぎます。" }
        }
    }

    private fun loadModel(directory: File): StreamingAsrModel {
        val manifestFile = File(directory, MANIFEST_NAME)
        require(manifestFile.isFile) { "ASR model manifest is missing" }
        val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
        require(manifest.optInt("schema") == 1) { "Unsupported ASR model manifest" }
        val family = manifest.getString("family")
        require(family == FAMILY) { "Unsupported ASR family: $family" }
        val chunkMs = manifest.getInt("chunkMs")
        require(chunkMs in SUPPORTED_CHUNKS) { "Unsupported ASR chunk: $chunkMs" }
        val id = manifest.getString("id")
        require(id == directory.name) { "ASR model id/path mismatch" }
        return modelFromDirectory(id, chunkMs, directory).also { validateModelFiles(it, strictSizes = true) }
    }

    private fun modelFromDirectory(id: String, chunkMs: Int, directory: File) = StreamingAsrModel(
        id = id,
        family = FAMILY,
        chunkMs = chunkMs,
        root = directory,
        encoder = File(directory, "encoder.int8.onnx"),
        decoder = File(directory, "decoder.int8.onnx"),
        joiner = File(directory, "joiner.int8.onnx"),
        tokens = File(directory, "tokens.txt"),
    )

    private fun writeManifest(model: StreamingAsrModel) {
        val files = JSONObject()
        listOf(model.encoder, model.decoder, model.joiner, model.tokens).forEach { file ->
            files.put(
                file.name,
                JSONObject()
                    .put("size", file.length())
                    .put("sha256", sha256(file)),
            )
        }
        val json = JSONObject()
            .put("schema", 1)
            .put("id", model.id)
            .put("family", FAMILY)
            .put("chunkMs", model.chunkMs)
            .put("files", files)
            .toString(2)
        FileOutputStream(File(model.root, MANIFEST_NAME)).use { output ->
            output.write(json.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun copyDocument(source: DocumentFile, destination: File) {
        contentResolver.openInputStream(source.uri).use { input ->
            requireNotNull(input) { "${source.name ?: "model file"} を開けませんでした。" }
            FileOutputStream(destination).use { fileOutput ->
                fileOutput.buffered().use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
            }
        }
        FileOutputStream(destination, true).use { it.fd.sync() }
        require(destination.length() > 0L) { "${source.name ?: "model file"} のコピー結果が空です。" }
    }

    private fun locateModelDirectory(root: DocumentFile, depth: Int = 0): DocumentFile? {
        if (!root.isDirectory) return null
        val children = runCatching { root.listFiles() }.getOrDefault(emptyArray())
        val childNames = children.filter { it.isFile }.mapNotNull { it.name }.toSet()
        if (REQUIRED_FILES.all(childNames::contains)) return root
        if (depth >= MAX_SEARCH_DEPTH) return null
        return children.asSequence()
            .filter { it.isDirectory }
            .mapNotNull { locateModelDirectory(it, depth + 1) }
            .firstOrNull()
    }

    private fun ensureDiskSpace(requiredBytes: Long) {
        val available = StatFs(rootDir.absolutePath).availableBytes
        require(available >= requiredBytes + DISK_HEADROOM_BYTES) {
            "ASRモデル用の空き容量が不足しています。必要約 ${requiredBytes / (1024 * 1024)} MiB + 予備128 MiB、空き ${available / (1024 * 1024)} MiB。"
        }
    }

    private fun inferChunkMs(name: String): Int? = CHUNK_REGEX.find(name)?.groupValues?.get(1)?.toIntOrNull()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    companion object {
        const val FAMILY = "nemotron-3.5-asr-streaming-0.6b"
        val SUPPORTED_CHUNKS = setOf(80, 160, 560, 1120)
        private val REQUIRED_FILES = setOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
        private val CHUNK_REGEX = Regex("(?:^|[-_])(80|160|560|1120)ms(?:[-_]|$)", RegexOption.IGNORE_CASE)
        private const val MANIFEST_NAME = "voicebubble-asr-model.json"
        private const val MAX_SEARCH_DEPTH = 2
        private const val COPY_BUFFER_BYTES = 256 * 1024
        private const val DISK_HEADROOM_BYTES = 128L * 1024 * 1024
        private const val MIN_ENCODER_BYTES = 100L * 1024 * 1024
        private const val MIN_DECODER_BYTES = 1L * 1024 * 1024
        private const val MIN_JOINER_BYTES = 1L * 1024 * 1024
        private const val MIN_TOKENS_BYTES = 1024L
    }
}
