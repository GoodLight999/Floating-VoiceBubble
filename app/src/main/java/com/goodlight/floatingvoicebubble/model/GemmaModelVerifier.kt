package com.goodlight.floatingvoicebubble.model

import com.goodlight.floatingvoicebubble.GemmaVariant
import java.io.File
import java.security.MessageDigest

data class GemmaModelFingerprint(
    val bytes: Long,
    val sha256: String,
    val detectedVariant: GemmaVariant,
    val knownOfficialArtifact: Boolean,
    val artifactId: String?,
)

/** Identifies known official LiteRT-LM Gemma 4 artifacts by exact bytes + SHA-256. */
object GemmaModelVerifier {
    private data class KnownArtifact(
        val id: String,
        val variant: GemmaVariant,
        val bytes: Long,
        val sha256: String,
    )

    private val knownArtifacts = listOf(
        KnownArtifact(
            id = "litert-community/gemma-4-E2B-it-litert-lm:gemma-4-E2B-it.litertlm",
            variant = GemmaVariant.E2B,
            bytes = 2_583_085_056L,
            sha256 = "ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42",
        ),
        KnownArtifact(
            id = "litert-community/gemma-4-E4B-it-litert-lm:gemma-4-E4B-it.litertlm@current",
            variant = GemmaVariant.E4B,
            bytes = 3_659_530_240L,
            sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        ),
        // Keep the immediately preceding official E4B revision recognized so an already-downloaded
        // model does not become "unknown" solely because the upstream package was regenerated.
        KnownArtifact(
            id = "litert-community/gemma-4-E4B-it-litert-lm:gemma-4-E4B-it.litertlm@previous",
            variant = GemmaVariant.E4B,
            bytes = 3_654_467_584L,
            sha256 = "f335f2bfd1b758dc6476db16c0f41854bd6237e2658d604cbe566bcefd00a7bc",
        ),
    )

    fun inspect(file: File): GemmaModelFingerprint {
        require(file.isFile) { "Gemma model does not exist: ${file.absolutePath}" }
        val bytes = file.length()
        val sha256 = sha256(file)
        return identify(bytes, sha256)
    }

    fun identify(bytes: Long, sha256: String): GemmaModelFingerprint {
        val normalizedHash = sha256.lowercase()
        val exact = knownArtifacts.firstOrNull { it.bytes == bytes && it.sha256 == normalizedHash }
        if (exact != null) {
            return GemmaModelFingerprint(
                bytes = bytes,
                sha256 = normalizedHash,
                detectedVariant = exact.variant,
                knownOfficialArtifact = true,
                artifactId = exact.id,
            )
        }

        // A size match alone is not trusted as authenticity, but it is useful for a diagnostic hint.
        val sizeHint = knownArtifacts.map { it.variant to it.bytes }.distinct()
            .firstOrNull { (_, knownBytes) -> knownBytes == bytes }
            ?.first
            ?: GemmaVariant.UNKNOWN
        return GemmaModelFingerprint(
            bytes = bytes,
            sha256 = normalizedHash,
            detectedVariant = sizeHint,
            knownOfficialArtifact = false,
            artifactId = null,
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
