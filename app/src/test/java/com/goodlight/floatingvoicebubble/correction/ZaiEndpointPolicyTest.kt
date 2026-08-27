package com.goodlight.floatingvoicebubble.correction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZaiEndpointPolicyTest {
    @Test
    fun detectsCodingPlanBaseAndGenerationUrlsButNotGeneralApi() {
        assertTrue(ByokEndpointResolver.isZaiCodingPlan("https://api.z.ai/api/coding/paas/v4"))
        assertTrue(
            ByokEndpointResolver.isZaiCodingPlan(
                "https://api.z.ai/api/coding/paas/v4/chat/completions",
            ),
        )
        assertFalse(ByokEndpointResolver.isZaiCodingPlan("https://api.z.ai/api/paas/v4"))
        assertFalse(ByokEndpointResolver.isZaiCodingPlan("https://openrouter.ai/api/v1"))
    }
}
