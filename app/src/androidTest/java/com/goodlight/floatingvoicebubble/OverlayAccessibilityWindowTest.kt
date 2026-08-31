package com.goodlight.floatingvoicebubble

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.goodlight.floatingvoicebubble.accessibility.VoiceBubbleAccessibilityService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The emulator shell gate already proves the production TYPE_ACCESSIBILITY_OVERLAY and IME
 * windows exist while a real external editor is focused. UiAutomation itself is also an
 * AccessibilityService, though, and API 33/36 do not expose another service's overlay root in
 * its interactive-window list reliably. For interaction semantics this test therefore reaches
 * the already system-bound debug service instance in-process and invokes performClick() on the
 * actual production FloatingBubbleController views. Release builds never publish that instance.
 */
@RunWith(AndroidJUnit4::class)
class OverlayAccessibilityWindowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val uiAutomation = instrumentation.getUiAutomation(
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
    )

    @Test
    fun externalEditorCanStartAndCancelThroughActualOverlayViews() {
        val store = SettingsStore(instrumentation.targetContext)
        val previous = store.load()
        try {
            store.update {
                it.copy(
                    recognitionMode = RecognitionMode.SYSTEM,
                    finalAsrMode = FinalAsrMode.LIVE_RESULT,
                    correctionMode = CorrectionMode.NONE,
                    offlineMode = false,
                    autoStop = false,
                )
            }
            enableProductionAccessibilityService()

            val host = Intent().apply {
                component = ComponentName(TEST_APP_ID, TEST_HOST_ACTIVITY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            instrumentation.context.startActivity(host)

            val (service, idle) = awaitIdleOverlay()
            assertTrue("production overlay root must be attached", idle.rootAttached)
            assertTrue("production overlay must be visible over the external editor", idle.rootVisible)
            assertFalse("idle overlay must not be expanded", idle.expanded)
            assertTrue("floating mic must be visible", idle.micVisible)
            assertTrue("floating mic must be enabled", idle.micEnabled)
            assertTrue("floating mic must be clickable", idle.micClickable)
            assertFalse("raw-bypass action must stay hidden while idle", idle.rawVisible)
            assertFalse("cancel action must stay hidden while idle", idle.cancelVisible)
            assertTrue("idle overlay must have non-zero geometry", idle.area > 0L)

            var micClickAccepted = false
            lateinit var expanded: OverlaySnapshot
            instrumentation.runOnMainSync {
                val controller = controllerUnsafe(service)
                micClickAccepted = viewFieldUnsafe(controller, "mic").performClick()
                expanded = snapshotUnsafe(service)
            }
            assertTrue("actual production mic performClick() must be accepted", micClickAccepted)
            assertTrue("mic click must expand the production overlay synchronously", expanded.expanded)
            assertTrue("raw-bypass action must appear after recording starts", expanded.rawVisible)
            assertTrue("raw-bypass action must be enabled", expanded.rawEnabled)
            assertTrue("raw-bypass action must be clickable", expanded.rawClickable)
            assertTrue("cancel action must appear after recording starts", expanded.cancelVisible)
            assertTrue("cancel action must be enabled", expanded.cancelEnabled)
            assertTrue("cancel action must be clickable", expanded.cancelClickable)
            assertTrue(
                "expanded overlay must expose the transcript surface",
                expanded.transcriptDescription == "認識本文の表示待ち" ||
                    expanded.transcriptDescription == "音声認識された本文",
            )
            assertTrue(
                "overlay must materially expand after the actual mic click: idle=${idle.width}x${idle.height}, " +
                    "expanded=${expanded.width}x${expanded.height}",
                expanded.area >= idle.area * 3,
            )

            var cancelClickAccepted = false
            lateinit var cancelled: OverlaySnapshot
            instrumentation.runOnMainSync {
                val controller = controllerUnsafe(service)
                cancelClickAccepted = viewFieldUnsafe(controller, "cancel").performClick()
                cancelled = snapshotUnsafe(service)
            }
            assertTrue("actual production cancel performClick() must be accepted", cancelClickAccepted)
            assertFalse("cancel must collapse the production overlay", cancelled.expanded)
            assertTrue("mic must remain visible after cancel", cancelled.micVisible)
            assertFalse("raw-bypass action must hide again after cancel", cancelled.rawVisible)
            assertFalse("cancel action must hide again after cancel", cancelled.cancelVisible)
            assertTrue(
                "cancel must restore idle-sized geometry: initial=${idle.width}x${idle.height}, " +
                    "cancelled=${cancelled.width}x${cancelled.height}",
                cancelled.width == idle.width && cancelled.height == idle.height,
            )
        } finally {
            cancelAnyLiveSession()
            store.update { previous }
            val home = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            instrumentation.context.startActivity(home)
        }
    }

    private fun enableProductionAccessibilityService() {
        shell("settings put secure enabled_accessibility_services $ACCESSIBILITY_COMPONENT")
        shell("settings put secure accessibility_enabled 1")
    }

    private fun awaitIdleOverlay(): Pair<VoiceBubbleAccessibilityService, OverlaySnapshot> {
        val deadline = SystemClock.uptimeMillis() + WINDOW_TIMEOUT_MS
        var last: OverlaySnapshot? = null
        while (SystemClock.uptimeMillis() < deadline) {
            val service = VoiceBubbleAccessibilityService.debugInstanceForInstrumentation()
            if (service != null) {
                val state = runCatching { snapshot(service) }.getOrNull()
                if (state != null) {
                    last = state
                    if (
                        state.rootAttached &&
                        state.rootVisible &&
                        !state.expanded &&
                        state.micVisible &&
                        state.micClickable
                    ) {
                        return service to state
                    }
                }
            }
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("system-bound VoiceBubble overlay did not reach idle state; last=$last")
    }

    private fun snapshot(service: VoiceBubbleAccessibilityService): OverlaySnapshot {
        lateinit var result: OverlaySnapshot
        instrumentation.runOnMainSync { result = snapshotUnsafe(service) }
        return result
    }

    private fun snapshotUnsafe(service: VoiceBubbleAccessibilityService): OverlaySnapshot {
        val controller = controllerUnsafe(service)
        val root = viewFieldUnsafe(controller, "root")
        val mic = viewFieldUnsafe(controller, "mic")
        val raw = viewFieldUnsafe(controller, "raw")
        val cancel = viewFieldUnsafe(controller, "cancel")
        val transcript = viewFieldUnsafe(controller, "transcript")
        val params = fieldUnsafe(controller, "params") as WindowManager.LayoutParams
        val expanded = fieldUnsafe(controller, "expanded") as Boolean
        return OverlaySnapshot(
            rootAttached = root.isAttachedToWindow,
            rootVisible = root.visibility == View.VISIBLE,
            expanded = expanded,
            width = params.width,
            height = params.height,
            micVisible = mic.visibility == View.VISIBLE,
            micEnabled = mic.isEnabled,
            micClickable = mic.isClickable,
            rawVisible = raw.visibility == View.VISIBLE,
            rawEnabled = raw.isEnabled,
            rawClickable = raw.isClickable,
            cancelVisible = cancel.visibility == View.VISIBLE,
            cancelEnabled = cancel.isEnabled,
            cancelClickable = cancel.isClickable,
            transcriptDescription = transcript.contentDescription?.toString(),
        )
    }

    private fun controllerUnsafe(service: VoiceBubbleAccessibilityService): Any {
        return fieldUnsafe(service, "overlay")
            ?: throw AssertionError("system-bound VoiceBubble service has no overlay controller")
    }

    private fun viewFieldUnsafe(target: Any, name: String): View {
        return fieldUnsafe(target, name) as? View
            ?: throw AssertionError("overlay field '$name' is not a View")
    }

    private fun fieldUnsafe(target: Any, name: String): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val currentType = type
            val field = runCatching { currentType.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return field.get(target)
            }
            type = currentType.superclass
        }
        throw AssertionError("field '$name' not found on ${target.javaClass.name}")
    }

    private fun cancelAnyLiveSession() {
        val service = VoiceBubbleAccessibilityService.debugInstanceForInstrumentation() ?: return
        runCatching {
            instrumentation.runOnMainSync {
                val controller = controllerUnsafe(service)
                val cancel = viewFieldUnsafe(controller, "cancel")
                if (cancel.visibility == View.VISIBLE) cancel.performClick()
            }
        }
    }

    private fun shell(command: String): String {
        val descriptor = uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader()
            .use { it.readText() }
    }

    private data class OverlaySnapshot(
        val rootAttached: Boolean,
        val rootVisible: Boolean,
        val expanded: Boolean,
        val width: Int,
        val height: Int,
        val micVisible: Boolean,
        val micEnabled: Boolean,
        val micClickable: Boolean,
        val rawVisible: Boolean,
        val rawEnabled: Boolean,
        val rawClickable: Boolean,
        val cancelVisible: Boolean,
        val cancelEnabled: Boolean,
        val cancelClickable: Boolean,
        val transcriptDescription: String?,
    ) {
        val area: Long get() = width.toLong() * height.toLong()
    }

    companion object {
        private const val APP_ID = "com.goodlight.floatingvoicebubble"
        private const val ACCESSIBILITY_COMPONENT =
            "$APP_ID/.accessibility.VoiceBubbleAccessibilityService"
        private const val TEST_APP_ID = "$APP_ID.test"
        private const val TEST_HOST_ACTIVITY =
            "com.goodlight.floatingvoicebubble.testhost.OverlayInputHostActivity"
        private const val WINDOW_TIMEOUT_MS = 8_000L
        private const val POLL_MS = 100L
    }
}
