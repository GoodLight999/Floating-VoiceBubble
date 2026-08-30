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
 * Shell `uiautomator dump` serializes only the active root on the API 33/36 runners and omits
 * TYPE_ACCESSIBILITY_OVERLAY. This test uses UiAutomation's interactive-window API and invokes
 * ACTION_CLICK on the actual accessibility nodes, so it validates the same semantic actions an
 * accessibility client sees without relying on shell coordinate injection into overlay windows.
 */
@RunWith(AndroidJUnit4::class)
class OverlayAccessibilityWindowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun externalEditorCanStartAndCancelThroughClickableOverlayNodes() {
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

            val idle = awaitOverlayContaining(uiAutomation, "音声入力を開始または確定")
            val idleRoot = requireNotNull(idle.root) { "idle overlay window has no accessibility root" }
            val mic = awaitNodeInRoot(idleRoot, "音声入力を開始または確定")
            assertTrue("floating mic must be enabled", mic.isEnabled)
            assertTrue("floating mic must be clickable", mic.isClickable)
            val micBounds = Rect().also { mic.getBoundsInScreen(it) }
            assertFalse("floating mic must have non-empty screen bounds", micBounds.isEmpty)
            assertTrue(
                "raw-bypass action must stay hidden while bubble is idle",
                findByDescription(idleRoot, "AI補正を使わず認識結果をそのまま入力") == null,
            )

            val idleBounds = Rect().also { idle.getBoundsInScreen(it) }
            assertFalse("idle overlay window must have non-empty bounds", idleBounds.isEmpty)
            assertTrue(
                "accessibility ACTION_CLICK must be accepted by floating mic",
                mic.performAction(AccessibilityNodeInfo.ACTION_CLICK),
            )

            val expanded = awaitOverlayContaining(
                uiAutomation,
                "AI補正を使わず認識結果をそのまま入力",
            )
            val expandedRoot = requireNotNull(expanded.root) {
                "expanded overlay window has no accessibility root"
            }
            val raw = awaitNodeInRoot(expandedRoot, "AI補正を使わず認識結果をそのまま入力")
            val cancel = awaitNodeInRoot(expandedRoot, "音声入力を破棄")
            assertTrue("raw-bypass action must be enabled", raw.isEnabled)
            assertTrue("raw-bypass action must be clickable", raw.isClickable)
            assertTrue("cancel action must be enabled", cancel.isEnabled)
            assertTrue("cancel action must be clickable", cancel.isClickable)
            assertTrue(
                "expanded overlay must expose transcript surface",
                findByDescription(expandedRoot, "認識本文の表示待ち") != null ||
                    findByDescription(expandedRoot, "音声認識された本文") != null,
            )

            val expandedBounds = Rect().also { expanded.getBoundsInScreen(it) }
            assertFalse("expanded overlay window must have non-empty bounds", expandedBounds.isEmpty)
            assertTrue(
                "overlay must materially expand after mic action: idle=$idleBounds expanded=$expandedBounds",
                area(expandedBounds) >= area(idleBounds) * 3,
            )

            assertTrue(
                "accessibility ACTION_CLICK must be accepted by cancel action",
                cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK),
            )
            awaitOverlayContaining(uiAutomation, "音声入力を開始または確定")
        } finally {
            val home = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            instrumentation.context.startActivity(home)
        }
    }

    private fun awaitOverlayContaining(
        uiAutomation: UiAutomation,
        description: String,
    ): AccessibilityWindowInfo {
        val deadline = SystemClock.uptimeMillis() + WINDOW_TIMEOUT_MS
        var lastTypes = emptyList<Int>()
        while (SystemClock.uptimeMillis() < deadline) {
            val windows = uiAutomation.windows
            lastTypes = windows.map { it.type }
            windows.asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
                .firstOrNull { window ->
                    window.root?.let { findByDescription(it, description) } != null
                }
                ?.let { return it }
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError(
            "accessibility overlay containing '$description' not found; observed window types=$lastTypes",
        )
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

    private fun area(rect: Rect): Long = rect.width().toLong() * rect.height().toLong()

    companion object {
        private const val TEST_APP_ID = "com.goodlight.floatingvoicebubble.test"
        private const val TEST_HOST_ACTIVITY =
            "com.goodlight.floatingvoicebubble.testhost.OverlayInputHostActivity"
        private const val WINDOW_TIMEOUT_MS = 8_000L
        private const val POLL_MS = 100L
    }
}
