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

data class FinalAsrModel(
    val id: String,
    val family: String,
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

class FinalAsrModelStore(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val rootDir = File(appContext.noBackupFilesDir, "models/final-asr").apply { mkdirs() }

    fun resolve(id: String): FinalAsrModel? {
        if (id.isBlank()) return null
        val directory = File(rootDir, id)
        return runCatching { loadModel(directory) }.getOrNull()
    }

    fun listInstalled(): List<FinalAsrModel> = rootDir.listFiles()
        ?.filter(File::isDirectory)
        ?.mapNotNull { runCatching { loadModel(it) }.getOrNull() }
        .orEmpty()

    fun importReazonSpeechTree(treeUri: Uri): FinalAsrModel {
        val tree = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: error("選択したフォルダを開けませんでした。")
        val source = locateModelDirectory(tree)
            ?: error("ReazonSpeech int8モデルのencoder / decoder / joiner / tokens.txt が見つかりません。")
        val sources = REQUIRED_FILES.associateWith { name ->
            source.findFile(name)?.takeIf { it.isFile } ?: error("モデルに $name がありません。")
        }
        val reportedBytes = sources.values.sumOf { it.length().coerceAtLeast(0L) }
        if (reportedBytes > 0L) ensureDiskSpace(reportedBytes)

        val id = MODEL_ID
        val temporary = File(rootDir, ".$id.part-${UUID.randomUUID()}")
        val destination = File(rootDir, id)
        temporary.mkdirs()
        try {
            sources.forEach { (name, document) -> copyDocument(document, File(temporary, name)) }
            val candidate = modelFromDirectory(temporary)
            validate(candidate, strictSizes = true)
            writeManifest(candidate)
            AtomicDirectoryInstaller.replace(temporary, destination, "ReazonSpeechモデル")
            return loadModel(destination)
        } catch (failure: Throwable) {
            temporary.deleteRecursively()
            throw failure
        }
    }

    fun remove(): Boolean {
        val target = File(rootDir, MODEL_ID)
        return !target.exists() || target.deleteRecursively()
    }

    internal fun validate(model: FinalAsrModel, strictSizes: Boolean = true) {
        REQUIRED_FILES.forEach { name ->
            val file = File(model.root, name)
            require(file.isFile && file.length() > 0L) { "$name が壊れているか空です。" }
        }
        if (strictSizes) {
            require(model.encoder.length() >= 100L * 1024 * 1024) { "ReazonSpeech encoder が小さすぎます。" }
            require(model.decoder.length() >= 5L * 1024 * 1024) { "ReazonSpeech decoder が小さすぎます。" }
            require(model.joiner.length() >= 1L * 1024 * 1024) { "ReazonSpeech joiner が小さすぎます。" }
            require(model.tokens.length() >= 10L * 1024) { "ReazonSpeech tokens.txt が小さすぎます。" }
        }
    }

    private fun loadModel(directory: File): FinalAsrModel {
        require(directory.isDirectory) { "final ASR model directory is missing" }
        val manifest = JSONObject(File(directory, MANIFEST_NAME).readText(Charsets.UTF_8))
        require(manifest.optInt("schema") == 1)
        require(manifest.getString("family") == FAMILY)
        require(manifest.getString("id") == MODEL_ID)
        return modelFromDirectory(directory).also { validate(it, strictSizes = true) }
    }

    private fun modelFromDirectory(directory: File) = FinalAsrModel(
        id = MODEL_ID,
        family = FAMILY,
        root = directory,
        encoder = File(directory, ENCODER),
        decoder = File(directory, DECODER),
        joiner = File(directory, JOINER),
        tokens = File(directory, TOKENS),
    )

    private fun locateModelDirectory(root: DocumentFile, depth: Int = 0): DocumentFile? {
        if (!root.isDirectory) return null
        val children = runCatching { root.listFiles() }.getOrDefault(emptyArray())
        val names = children.filter { it.isFile }.mapNotNull { it.name }.toSet()
        if (REQUIRED_FILES.all(names::contains)) return root
        if (depth >= 2) return null
        return children.asSequence().filter { it.isDirectory }
            .mapNotNull { locateModelDirectory(it, depth + 1) }.firstOrNull()
    }

    private fun copyDocument(source: DocumentFile, destination: File) {
        resolver.openInputStream(source.uri).use { input ->
            requireNotNull(input) { "${source.name ?: "model file"} を開けませんでした。" }
            FileOutputStream(destination).use { output -> input.copyTo(output, 256 * 1024) }
        }
        FileOutputStream(destination, true).use { it.fd.sync() }
        require(destination.length() > 0L)
    }

    private fun writeManifest(model: FinalAsrModel) {
        val files = JSONObject()
        listOf(model.encoder, model.decoder, model.joiner, model.tokens).forEach { file ->
            files.put(file.name, JSONObject().put("size", file.length()).put("sha256", sha256(file)))
        }
        val json = JSONObject()
            .put("schema", 1)
            .put("id", MODEL_ID)
            .put("family", FAMILY)
            .put("files", files)
            .toString(2)
        FileOutputStream(File(model.root, MANIFEST_NAME)).use { output ->
            output.write(json.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun ensureDiskSpace(requiredBytes: Long) {
        val available = StatFs(rootDir.absolutePath).availableBytes
        require(available >= requiredBytes + 64L * 1024 * 1024) {
            "最終ASRモデル用の空き容量が不足しています。"
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    companion object {
        const val MODEL_ID = "reazonspeech-v2-int8"
        const val FAMILY = "sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01"
        private const val ENCODER = "encoder-epoch-99-avg-1.int8.onnx"
        private const val DECODER = "decoder-epoch-99-avg-1.onnx"
        private const val JOINER = "joiner-epoch-99-avg-1.int8.onnx"
        private const val TOKENS = "tokens.txt"
        private const val MANIFEST_NAME = "voicebubble-final-asr-model.json"
        private val REQUIRED_FILES = setOf(ENCODER, DECODER, JOINER, TOKENS)
    }
}
