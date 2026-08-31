package com.goodlight.floatingvoicebubble.correction

import com.goodlight.floatingvoicebubble.ReasoningEffort
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ByokModelDiscoveryReasoningTest {
    @Test
    fun parsesOpenRouterReasoningMetadataWithoutInventingLevels() {
        val json = JSONObject(
            """
            {
              "data": [
                {
                  "id": "deepseek/deepseek-v4-flash-0731",
                  "name": "DeepSeek V4 Flash 0731",
                  "context_length": 1048576,
                  "supported_parameters": ["reasoning", "reasoning_effort", "max_tokens"],
                  "reasoning": {
                    "mandatory": false,
                    "default_enabled": true,
                    "supported_efforts": ["max", "high", "low"],
                    "default_effort": "high"
                  }
                },
                {
                  "id": "meta/non-reasoning",
                  "supported_parameters": ["temperature"]
                }
              ]
            }
            """.trimIndent(),
        )

        val models = ByokModelDiscovery.parseOpenAiCompatibleModels(json)
        val reasoning = models.first { it.id == "deepseek/deepseek-v4-flash-0731" }
        assertEquals(
            listOf(ReasoningEffort.MAX, ReasoningEffort.HIGH, ReasoningEffort.LOW),
            reasoning.reasoningEfforts,
        )
        assertEquals(false, reasoning.reasoningMandatory)
        assertEquals(ReasoningEffort.HIGH, reasoning.reasoningDefaultEffort)
        assertTrue(reasoning.supportsReasoning)

        val plain = models.first { it.id == "meta/non-reasoning" }
        assertTrue(plain.reasoningEfforts.isEmpty())
        assertNull(plain.reasoningMandatory)
        assertNull(plain.reasoningDefaultEffort)
        assertFalse(plain.supportsReasoning)
    }

    @Test
    fun ignoresUnknownFutureEffortInsteadOfMappingItToWrongSemantics() {
        val json = JSONObject(
            """
            {
              "data": [{
                "id": "provider/future-model",
                "supported_parameters": ["reasoning"],
                "reasoning": {
                  "mandatory": true,
                  "supported_efforts": ["ultra", "medium"],
                  "default_effort": "ultra"
                }
              }]
            }
            """.trimIndent(),
        )

        val model = ByokModelDiscovery.parseOpenAiCompatibleModels(json).single()
        assertEquals(listOf(ReasoningEffort.MEDIUM), model.reasoningEfforts)
        assertTrue(model.reasoningMandatory == true)
        assertNull(model.reasoningDefaultEffort)
    }
}
