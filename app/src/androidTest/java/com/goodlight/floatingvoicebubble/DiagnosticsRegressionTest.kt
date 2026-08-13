package com.goodlight.floatingvoicebubble

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.goodlight.floatingvoicebubble.diagnostics.DiagnosticStatus
import com.goodlight.floatingvoicebubble.diagnostics.SelfDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticsRegressionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun byokEndpointAndAppProfileSelfChecksStayGreenAndPrivate() {
        val report = SelfDiagnostics(context).run(includeExternalProbes = false)
        val endpoint = report.items.firstOrNull { it.id == "byok-endpoint-resolution" }
        val profiles = report.items.firstOrNull { it.id == "app-profile-store" }
        assertNotNull(endpoint)
        assertNotNull(profiles)
        assertEquals(DiagnosticStatus.PASS, endpoint!!.status)
        assertEquals(DiagnosticStatus.PASS, profiles!!.status)

        val redacted = report.toRedactedJson()
        assertFalse(redacted.contains("com.google.android.gm"))
        assertFalse(redacted.contains("FVB_SIGNING"))
        assertFalse(redacted.contains("apiKey", ignoreCase = true))
    }
}
