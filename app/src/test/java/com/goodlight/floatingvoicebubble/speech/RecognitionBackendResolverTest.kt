package com.goodlight.floatingvoicebubble.speech

import com.goodlight.floatingvoicebubble.RecognitionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionBackendResolverTest {
    @Test
    fun offlineAlwaysUsesSherpaEvenWhenAndroidOnDeviceExists() {
        val backend = RecognitionBackendResolver.resolve(
            mode = RecognitionMode.SYSTEM,
            offlineRequired = true,
            androidOnDeviceAvailable = true,
            sherpaModelAvailable = true,
        )
        assertEquals(RecognitionBackend.SHERPA_STREAMING, backend)
    }

    @Test
    fun offlineRejectsMissingSherpaModelInsteadOfFallingBack() {
        val result = runCatching {
            RecognitionBackendResolver.resolve(
                mode = RecognitionMode.AUTO,
                offlineRequired = true,
                androidOnDeviceAvailable = true,
                sherpaModelAvailable = false,
            )
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun explicitSherpaRejectsMissingModel() {
        val result = runCatching {
            RecognitionBackendResolver.resolve(
                mode = RecognitionMode.SHERPA_STREAMING,
                offlineRequired = false,
                androidOnDeviceAvailable = true,
                sherpaModelAvailable = false,
            )
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun onlineAutoPrefersAndroidOnDeviceWithoutForcingLargeModel() {
        assertEquals(
            RecognitionBackend.ANDROID_ON_DEVICE,
            RecognitionBackendResolver.resolve(
                mode = RecognitionMode.AUTO,
                offlineRequired = false,
                androidOnDeviceAvailable = true,
                sherpaModelAvailable = true,
            ),
        )
    }

    @Test
    fun onlineAutoFallsBackToSystemWhenOnDeviceUnavailable() {
        assertEquals(
            RecognitionBackend.ANDROID_SYSTEM,
            RecognitionBackendResolver.resolve(
                mode = RecognitionMode.AUTO,
                offlineRequired = false,
                androidOnDeviceAvailable = false,
                sherpaModelAvailable = false,
            ),
        )
    }
}
