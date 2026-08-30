package com.goodlight.floatingvoicebubble

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Shell `uiautomator dump` only serializes the active root on the API 33/36 runners and therefore
 * omits TYPE_ACCESSIBILITY_OVERLAY. This test deliberately uses UiAutomation's interactive-window
 * API so the real external editor and Floating VoiceBubble overlay are validated independently.
 */
@RunWith(AndroidJUnit4::class)
class OverlayAccessibilityWindowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun externalEditorExposesClickableMicInAccessibilityOverlayWindow() {
        val uiAutomation = instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
        )
        uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        val host = Intent().apply {
            component = ComponentName(TEST_APP_ID, TEST_HOST_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        instrumentation.context.startActivity(host)
        try {
            SystemClock.sleep(500)
            awaitNode(uiAutomation, "VoiceBubble CI 外部入力欄")

            val overlay = awaitWindow(uiAutomation, AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)
            val root = requireNotNull(overlay.root) { "overlay window has no accessibility root" }
            val mic = awaitNodeInRoot(root, "音声入力を開始または確定")
            assertTrue("floating mic must be enabled", mic.isEnabled)
            assertTrue("floating mic must be clickable", mic.isClickable)
            val bounds = Rect().also { mic.getBoundsInScreen(it) }
            assertFalse("floating mic must have non-empty screen bounds", bounds.isEmpty)

            val rawBypass = findByDescription(root, "AI補正を使わず認識結果をそのまま入力")
            assertTrue("raw-bypass action must stay hidden while bubble is idle", rawBypass == null)
        } finally {
            val home = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            instrumentation.context.startActivity(home)
        }
    }

    private fun awaitWindow(uiAutomation: UiAutomation, type: Int): AccessibilityWindowInfo {
        val deadline = SystemClock.uptimeMillis() + WINDOW_TIMEOUT_MS
        var lastTypes = emptyList<Int>()
        while (SystemClock.uptimeMillis() < deadline) {
            val windows = uiAutomation.windows
            lastTypes = windows.map { it.type }
            windows.firstOrNull { it.type == type }?.let { return it }
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("window type=$type not found; observed=$lastTypes")
    }

    private fun awaitNode(uiAutomation: UiAutomation, description: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + WINDOW_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            uiAutomation.windows.forEach { window ->
                val root = window.root ?: return@forEach
                findByDescription(root, description)?.let { return it }
            }
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("contentDescription not found across interactive windows: $description")
    }

    private fun awaitNodeInRoot(root: AccessibilityNodeInfo, description: String): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + WINDOW_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            findByDescription(root, description)?.let { return it }
            root.refresh()
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("contentDescription not found in overlay root: $description")
    }

    private fun findByDescription(node: AccessibilityNodeInfo, wanted: String): AccessibilityNodeInfo? {
        if (node.contentDescription?.toString() == wanted) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findByDescription(child, wanted)?.let { return it }
        }
        return null
    }

    companion object {
        private const val TEST_APP_ID = "com.goodlight.floatingvoicebubble.test"
        private const val TEST_HOST_ACTIVITY =
            "com.goodlight.floatingvoicebubble.testhost.OverlayInputHostActivity"
        private const val WINDOW_TIMEOUT_MS = 8_000L
        private const val POLL_MS = 100L
    }
}
