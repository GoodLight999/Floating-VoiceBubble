package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.LineBreakMode
import com.goodlight.floatingvoicebubble.ReasoningEffort
import com.goodlight.floatingvoicebubble.RecognitionRepairMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ByokEndpointResolverTest {
    @Test
    fun normalizesOpenAiCompatibleBaseAndCompletionUrls() {
        val base = ByokEndpointResolver.resolve("https://openrouter.ai/api/v1")
        assertEquals("https://openrouter.ai/api/v1/chat/completions", base.generationUrl)
        assertEquals("https://openrouter.ai/api/v1/models", base.modelsUrl)
        val full = ByokEndpointResolver.resolve("https://api.openai.com/v1/chat/completions")
        assertEquals("https://api.openai.com/v1/chat/completions", full.generationUrl)
        assertEquals("https://api.openai.com/v1/models", full.modelsUrl)
    }

    @Test
    fun canonicalizesEveryCommonOpenRouterInputToApiV1() {
        listOf(
            "https://openrouter.ai", "https://openrouter.ai/", "https://openrouter.ai/api",
            "https://openrouter.ai/v1", "https://openrouter.ai/v1/models", "https://openrouter.ai/api/v1",
            "https://openrouter.ai/api/v1/models", "https://openrouter.ai/api/v1/chat/completion",
        ).forEach { input ->
            val resolved = ByokEndpointResolver.resolve(input)
            assertEquals("https://openrouter.ai/api/v1/chat/completions", resolved.generationUrl)
            assertEquals("https://openrouter.ai/api/v1/models", resolved.modelsUrl)
            assertTrue(ByokEndpointResolver.isOpenRouter(input))
        }
    }

    @Test
    fun addsV1ForVersionlessGenericOpenAiCompatibleRoots() {
        val root = ByokEndpointResolver.resolve("https://llm.example.com")
        assertEquals("https://llm.example.com/v1/chat/completions", root.generationUrl)
        assertEquals("https://llm.example.com/v1/models", root.modelsUrl)
        val apiRoot = ByokEndpointResolver.resolve("https://llm.example.com/api")
        assertEquals("https://llm.example.com/api/v1/chat/completions", apiRoot.generationUrl)
        assertEquals("https://llm.example.com/api/v1/models", apiRoot.modelsUrl)
    }

    @Test
    fun repairsCommonOpenAiEndpointMistakes() {
        val singular = ByokEndpointResolver.resolve("https://llm.example.com/v1/chat/completion")
        assertEquals("https://llm.example.com/v1/chat/completions", singular.generationUrl)
        val legacy = ByokEndpointResolver.resolve("https://llm.example.com/v1/completions")
        assertEquals("https://llm.example.com/v1/chat/completions", legacy.generationUrl)
        val models = ByokEndpointResolver.resolve("https://llm.example.com/v1/models?foo=bar#ignored")
        assertEquals("https://llm.example.com/v1/chat/completions", models.generationUrl)
        assertEquals("https://llm.example.com/v1/models", models.modelsUrl)
    }

    @Test
    fun normalizesAnthropicBaseMessagesAndModelsUrls() {
        val root = ByokEndpointResolver.resolve("https://api.anthropic.com")
        assertEquals("https://api.anthropic.com/v1/messages", root.generationUrl)
        assertEquals("https://api.anthropic.com/v1/models", root.modelsUrl)
        val messages = ByokEndpointResolver.resolve("https://api.anthropic.com/v1/messages")
        assertEquals("https://api.anthropic.com/v1/messages", messages.generationUrl)
        val models = ByokEndpointResolver.resolve("https://api.anthropic.com/v1/models")
        assertEquals("https://api.anthropic.com/v1/messages", models.generationUrl)
    }

    @Test
    fun normalizesGeminiOfficialBase() {
        val gemini = ByokEndpointResolver.resolve("https://generativelanguage.googleapis.com/v1beta")
        assertEquals("https://generativelanguage.googleapis.com/v1beta", gemini.generationUrl)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models", gemini.modelsUrl)
    }

    @Test
    fun googleOpenAiCompatibilityStaysOpenAiProtocol() {
        val resolved = ByokEndpointResolver.resolve("https://generativelanguage.googleapis.com/v1beta/openai")
        assertEquals(CloudCorrectorFactory.Protocol.OPENAI_COMPATIBLE, resolved.protocol)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", resolved.generationUrl)
    }

    @Test
    fun zaiVersionedRootsStayOnTheirOwnApiPrefix() {
        val normal = ByokEndpointResolver.resolve("https://api.z.ai/api/paas/v4")
        assertEquals("https://api.z.ai/api/paas/v4/chat/completions", normal.generationUrl)
        assertEquals("https://api.z.ai/api/paas/v4/models", normal.modelsUrl)
        val coding = ByokEndpointResolver.resolve("https://api.z.ai/api/coding/paas/v4")
        assertEquals("https://api.z.ai/api/coding/paas/v4/chat/completions", coding.generationUrl)
        assertEquals("https://api.z.ai/api/coding/paas/v4/models", coding.modelsUrl)
    }

    @Test
    fun zaiUsesZaiThinkingInsteadOfOpenAiReasoningEffort() {
        val high = OpenAiProviderCompatibility.resolve(
            "https://api.z.ai/api/paas/v4/chat/completions", "GLM-4.7", ReasoningEffort.HIGH,
        )
        assertEquals("glm-4.7", high.requestModel)
        assertEquals(true, high.zaiThinkingEnabled)
        assertNull(high.openAiReasoningEffort)
        assertNull(high.openRouterReasoningEffort)
        assertTrue(high.disableSampling)
        assertFalse(high.zaiCodingPlanEndpoint)
        val none = OpenAiProviderCompatibility.resolve(
            "https://api.z.ai/api/coding/paas/v4/chat/completions", "GLM-4.5-Air", ReasoningEffort.NONE,
        )
        assertEquals(false, none.zaiThinkingEnabled)
        assertTrue(none.zaiCodingPlanEndpoint)
    }

    @Test
    fun zaiDefaultOmitsThinkingOverrideAndUsesProviderDefault() {
        val options = OpenAiProviderCompatibility.resolve(
            "https://api.z.ai/api/coding/paas/v4/chat/completions", "GLM-4.7", ReasoningEffort.DEFAULT,
        )
        assertNull(options.zaiThinkingEnabled)
        assertTrue(options.disableSampling)
        assertTrue(options.zaiCodingPlanEndpoint)
    }

    @Test
    fun genericCompatibleHostDoesNotReceiveProviderSpecificReasoningField() {
        val options = OpenAiProviderCompatibility.resolve(
            "https://llm.example.com/v1/chat/completions", "model", ReasoningEffort.HIGH,
        )
        assertNull(options.openAiReasoningEffort)
        assertNull(options.openRouterReasoningEffort)
        assertNull(options.zaiThinkingEnabled)
    }

    @Test
    fun selectedPeriodCannotBecomeSilentNoOp() {
        val prefs = CorrectionPreferences(addPeriods = true, removeFillers = false)
        assertEquals("今日は晴れ。", CorrectionPostProcessor.apply("今日は晴れ", "今日は晴れ", prefs))
    }

    @Test
    fun selectedFillerRemovalCannotBecomeSilentNoOp() {
        val prefs = CorrectionPreferences(addPeriods = false, removeFillers = true)
        assertEquals("今日は晴れ", CorrectionPostProcessor.apply("えー今日は晴れ", "えー今日は晴れ", prefs))
    }

    @Test
    fun strongRepairDoesNotTriggerHiddenSecondModelCall() {
        val request = CorrectionRequest(
            rawTranscript = "取り合いが聞き取りミスをした",
            alternatives = listOf("取り合いが聞き取りミスをした"),
            surroundingContext = "音声認識AIの話",
            dictionaryTerms = emptyList(),
            preferences = CorrectionPreferences(recognitionRepairMode = RecognitionRepairMode.STRONG),
        )
        assertFalse(CorrectionPostProcessor.shouldRetryNoOp(request, request.rawTranscript))
    }

    @Test
    fun requestedLineBreakGetsDeterministicFallbackWhenModelReturnsFlatText() {
        val raw = "今日は音声入力の補正について話します。句読点も直したいです。聞き取りミスも積極的に直したいです。さらに長い文章では改行も入れて読みやすくしたいです。"
        val prefs = CorrectionPreferences(
            addPeriods = true,
            removeFillers = false,
            lineBreakMode = LineBreakMode.SMART,
        )
        val output = CorrectionPostProcessor.apply(raw, raw, prefs)
        assertTrue(output.contains('\n'))
        assertEquals(raw.replace("\n", ""), output.replace("\n", ""))
    }

    @Test
    fun punctuationFreeLongJapaneseStillGetsRequestedLineBreakWithoutChangingCharacters() {
        val raw = "今日は音声入力アプリの補正についてかなり長めに話していて聞き取りミスをもっと積極的に直してほしいし読みやすい場所にはちゃんと改行も入れてほしいという話をしています"
        val prefs = CorrectionPreferences(
            addPeriods = false,
            removeFillers = false,
            lineBreakMode = LineBreakMode.SMART_SPACED,
        )
        val output = CorrectionPostProcessor.apply(raw, raw, prefs)
        assertTrue(output.contains("\n\n"))
        assertEquals(raw, output.replace("\n", ""))
    }

    @Test
    fun rejectsInsecureOrCredentialBearingEndpoints() {
        assertThrows(IllegalArgumentException::class.java) { ByokEndpointResolver.resolve("http://llm.example.com/v1") }
        assertThrows(IllegalArgumentException::class.java) { ByokEndpointResolver.resolve("https://user:secret@llm.example.com/v1") }
    }
}
