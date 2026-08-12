package com.goodlight.floatingvoicebubble.model

import com.goodlight.floatingvoicebubble.GemmaVariant

enum class CatalogModelKind { STREAMING_ASR, FINAL_ASR, GEMMA }

data class OfficialModelEntry(
    val id: String,
    val title: String,
    val detail: String,
    val kind: CatalogModelKind,
    val url: String,
    val estimatedInstalledBytes: Long,
    val chunkMs: Int? = null,
    val gemmaVariant: GemmaVariant? = null,
    val displayName: String? = null,
    val expectedBytes: Long? = null,
    val expectedSha256: String? = null,
)

/**
 * Pinned, reviewable acquisition sources. Updating a URL/fingerprint is a code change,
 * never a server-controlled remote configuration change.
 */
object OfficialModelCatalog {
    private const val SHERPA_RELEASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

    val streamingAsr = listOf(80, 160, 560, 1120).map { chunkMs ->
        OfficialModelEntry(
            id = "nemotron35-${chunkMs}ms-int8",
            title = "Nemotron 3.5 Streaming ${chunkMs}ms int8",
            detail = if (chunkMs == 560) {
                "日本語の精度・遅延バランスを比較する基準候補。最終選択は同一音声CERで決めます。"
            } else {
                "真ストリーミング日本語候補。${chunkMs}ms chunkを同一音声で比較します。"
            },
            kind = CatalogModelKind.STREAMING_ASR,
            url = "$SHERPA_RELEASE/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-${chunkMs}ms-int8-2026-06-11.tar.bz2",
            estimatedInstalledBytes = 720L * 1024 * 1024,
            chunkMs = chunkMs,
        )
    }

    val finalAsr = OfficialModelEntry(
        id = FinalAsrModelStore.MODEL_ID,
        title = "ReazonSpeech Zipformer int8",
        detail = "日本語final-ASR候補。保存した同一WAVを再認識してCER/RTFを測ります。",
        kind = CatalogModelKind.FINAL_ASR,
        url = "$SHERPA_RELEASE/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01.tar.bz2",
        estimatedInstalledBytes = 220L * 1024 * 1024,
    )

    val gemmaE2B = OfficialModelEntry(
        id = "gemma4-e2b-current",
        title = "Gemma 4 E2B LiteRT-LM",
        detail = "軽量側のオンデバイス補正モデル。公式artifactをサイズ＋SHA-256で完全検証します。",
        kind = CatalogModelKind.GEMMA,
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true",
        estimatedInstalledBytes = 2_588_147_712L,
        gemmaVariant = GemmaVariant.E2B,
        displayName = "gemma-4-E2B-it.litertlm",
        expectedBytes = 2_588_147_712L,
        expectedSha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
    )

    val gemmaE4B = OfficialModelEntry(
        id = "gemma4-e4b-current",
        title = "Gemma 4 E4B LiteRT-LM",
        detail = "高品質側のオンデバイス補正モデル。公式artifactをサイズ＋SHA-256で完全検証します。",
        kind = CatalogModelKind.GEMMA,
        url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm?download=true",
        estimatedInstalledBytes = 3_659_530_240L,
        gemmaVariant = GemmaVariant.E4B,
        displayName = "gemma-4-E4B-it.litertlm",
        expectedBytes = 3_659_530_240L,
        expectedSha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
    )

    val all: List<OfficialModelEntry> = streamingAsr + finalAsr + listOf(gemmaE2B, gemmaE4B)

    fun find(id: String): OfficialModelEntry? = all.firstOrNull { it.id == id }
}
