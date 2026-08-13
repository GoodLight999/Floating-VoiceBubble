package com.goodlight.floatingvoicebubble.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

class FloatingBubbleController(
    private val service: AccessibilityService,
    private val onToggle: () -> Unit,
    private val onCancel: () -> Unit,
    private val onDismiss: () -> Unit,
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val root = FrameLayout(service)
    private val card = LinearLayout(service)
    private val header = LinearLayout(service)
    private val status = TextView(service)
    private val cancel = TextView(service)
    private val transcript = TextView(service)
    private val transcriptScroll = ScrollView(service)
    private val mic = TextView(service)
    private val dismissRoot = FrameLayout(service)
    private val dismissTarget = TextView(service)

    private var params = baseParams(BUBBLE_DP, BUBBLE_DP)
    private val dismissParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        dp(DISMISS_AREA_DP),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }

    private var attached = false
    private var inputAvailable = false
    private var dismissedForInput = false
    private var expanded = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0
    private var moved = false
    private var dismissEngaged = false

    init {
        root.clipChildren = false
        root.clipToPadding = false
        root.visibility = View.GONE

        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(16), dp(10), dp(12), dp(12))
        card.background = roundedDrawable(CARD, 22f)
        card.elevation = dp(12).toFloat()
        card.alpha = 0f
        card.scaleX = 0.96f
        card.scaleY = 0.96f
        card.visibility = View.GONE

        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        status.setTextColor(MUTED)
        status.textSize = 12f
        status.typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
        status.text = "●  待機中"
        header.addView(status, LinearLayout.LayoutParams(0, dp(48), 1f))

        cancel.text = "キャンセル"
        cancel.gravity = Gravity.CENTER
        cancel.textSize = 13f
        cancel.setTextColor(ACCENT_SOFT)
        cancel.minWidth = dp(84)
        cancel.minHeight = dp(48)
        cancel.contentDescription = "音声入力を破棄して閉じる"
        cancel.visibility = View.GONE
        cancel.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onCancel()
        }
        header.addView(cancel, LinearLayout.LayoutParams(dp(92), dp(48)))

        transcript.setTextColor(TEXT)
        transcript.textSize = 16f
        transcript.setLineSpacing(0f, 1.2f)
        transcript.setTextIsSelectable(false)
        transcriptScroll.isVerticalScrollBarEnabled = false
        transcriptScroll.overScrollMode = View.OVER_SCROLL_NEVER
        transcriptScroll.addView(
            transcript,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
        )

        card.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
        card.addView(
            transcriptScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(132)).apply { topMargin = dp(3) },
        )
        root.addView(card, FrameLayout.LayoutParams(dp(CARD_DP), dp(CARD_HEIGHT_DP), Gravity.TOP or Gravity.START))

        mic.gravity = Gravity.CENTER
        mic.text = "●"
        mic.textSize = 23f
        mic.setTextColor(Color.WHITE)
        mic.background = roundedDrawable(ACCENT, 28f)
        mic.elevation = dp(14).toFloat()
        mic.contentDescription = "音声入力を開始または確定"
        mic.setOnTouchListener(::onMicTouch)
        root.addView(mic, FrameLayout.LayoutParams(dp(BUBBLE_DP), dp(BUBBLE_DP), Gravity.BOTTOM or Gravity.END))

        dismissRoot.visibility = View.GONE
        dismissRoot.alpha = 0f
        dismissTarget.gravity = Gravity.CENTER
        dismissTarget.text = "×"
        dismissTarget.textSize = 28f
        dismissTarget.setTextColor(Color.WHITE)
        dismissTarget.background = roundedDrawable(DISMISS_IDLE, 31f)
        dismissTarget.contentDescription = "ここへ離すとVoiceBubbleを閉じます"
        dismissRoot.addView(
            dismissTarget,
            FrameLayout.LayoutParams(dp(62), dp(62), Gravity.CENTER),
        )
    }

    fun attach() {
        if (attached) return
        params.gravity = Gravity.TOP or Gravity.START
        val bounds = windowManager.currentWindowMetrics.bounds
        params.x = (bounds.width() - dp(BUBBLE_DP) - dp(18)).coerceAtLeast(0)
        params.y = (bounds.height() * 0.58).roundToInt()
        windowManager.addView(root, params)
        windowManager.addView(dismissRoot, dismissParams)
        attached = true
        showIdle()
        refreshVisibility()
    }

    fun detach() {
        if (!attached) return
        runCatching { windowManager.removeView(root) }
        runCatching { windowManager.removeView(dismissRoot) }
        attached = false
    }

    fun setInputAvailable(available: Boolean, resetDismissal: Boolean = false) {
        inputAvailable = available
        if (available && resetDismissal) dismissedForInput = false
        if (!available) {
            hideDismissTarget()
            if (expanded) collapse()
        }
        refreshVisibility()
    }

    fun acknowledgeTap() { mic.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }

    fun showIdle() {
        mic.text = "●"
        mic.setTextColor(Color.WHITE)
        mic.background = roundedDrawable(ACCENT, 28f)
        cancel.visibility = View.GONE
        if (expanded) collapse()
        refreshVisibility()
    }

    fun showListening(text: String, state: String = "聴いています", pending: List<String> = emptyList()) {
        refreshVisibility()
        if (!isVisibleForInput()) return
        ensureExpanded()
        status.text = if (pending.isEmpty()) "●  $state" else "●  $state  ·  処理中${pending.size}件"
        status.setTextColor(ACCENT_SOFT)
        transcript.text = renderLiveTranscript(text, pending)
        transcript.setTextColor(if (text.isBlank() && pending.isEmpty()) MUTED else TEXT)
        cancel.visibility = View.VISIBLE
        mic.text = "■"
        scrollToBottom()
    }

    fun showFinalizing(text: String, state: String = "整えています") {
        showFinalizingStack(listOf(text), state)
    }

    fun showFinalizingStack(pending: List<String>, state: String = "整えています") {
        refreshVisibility()
        if (!isVisibleForInput()) return
        if (pending.isEmpty()) {
            showIdle()
            return
        }
        ensureExpanded()
        status.text = "●  $state  ·  ${pending.size}件"
        status.setTextColor(WARM)
        transcript.text = renderPending(pending)
        transcript.setTextColor(TEXT)
        cancel.visibility = View.VISIBLE
        mic.text = "●"
        scrollToBottom()
    }

    fun showError(message: String) {
        refreshVisibility()
        if (!isVisibleForInput()) return
        ensureExpanded()
        status.text = "●  入力できませんでした"
        status.setTextColor(ERROR)
        transcript.text = message
        transcript.setTextColor(TEXT)
        cancel.visibility = View.VISIBLE
        mic.text = "●"
    }

    private fun renderLiveTranscript(current: String, pending: List<String>): String = buildString {
        append("いま話している内容\n")
        append(current.ifBlank { "話し始めると、ここに文字が出ます。" })
        if (pending.isNotEmpty()) {
            append("\n\n")
            append(renderPending(pending))
        }
    }

    private fun renderPending(items: List<String>): String = buildString {
        items.takeLast(MAX_VISIBLE_PENDING).forEachIndexed { index, value ->
            if (index > 0) append("\n\n")
            val ordinal = items.size - minOf(items.size, MAX_VISIBLE_PENDING) + index + 1
            append("処理中 $ordinal\n")
            append(compactPending(value))
        }
        if (items.size > MAX_VISIBLE_PENDING) {
            append("\n\nほか ${items.size - MAX_VISIBLE_PENDING}件を処理中")
        }
    }

    private fun compactPending(value: String): String {
        val normalized = value.replace(Regex("\\s+"), " ").trim()
        return if (normalized.length <= MAX_PENDING_CHARS) normalized else normalized.take(MAX_PENDING_CHARS) + "…"
    }

    private fun isVisibleForInput(): Boolean = attached && inputAvailable && !dismissedForInput

    private fun refreshVisibility() {
        if (!attached) return
        root.visibility = if (isVisibleForInput()) View.VISIBLE else View.GONE
    }

    private fun ensureExpanded() {
        if (expanded) return
        expanded = true
        val previousRight = params.x + params.width
        params.width = dp(CARD_DP)
        params.height = dp(CARD_HEIGHT_DP)
        params.x = (previousRight - params.width).coerceAtLeast(0)
        clampToScreen()
        if (attached) windowManager.updateViewLayout(root, params)
        card.visibility = View.VISIBLE
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(130)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun collapse() {
        expanded = false
        val previousRight = params.x + params.width
        card.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(110)
            .withEndAction { if (!expanded) card.visibility = View.GONE }.start()
        params.width = dp(BUBBLE_DP)
        params.height = dp(BUBBLE_DP)
        params.x = (previousRight - params.width).coerceAtLeast(0)
        clampToScreen()
        if (attached) windowManager.updateViewLayout(root, params)
    }

    private fun clampToScreen() {
        val bounds = windowManager.currentWindowMetrics.bounds
        params.x = params.x.coerceIn(0, (bounds.width() - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (bounds.height() - params.height).coerceAtLeast(0))
    }

    private fun scrollToBottom() { transcriptScroll.post { transcriptScroll.fullScroll(View.FOCUS_DOWN) } }

    private fun onMicTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downX = params.x
                downY = params.y
                moved = false
                dismissEngaged = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!moved && (abs(dx) > dp(8) || abs(dy) > dp(8))) {
                    moved = true
                    showDismissTarget()
                }
                if (moved && attached) {
                    val bounds = windowManager.currentWindowMetrics.bounds
                    params.x = (downX + dx.toInt()).coerceIn(0, (bounds.width() - params.width).coerceAtLeast(0))
                    params.y = (downY + dy.toInt()).coerceIn(0, (bounds.height() - params.height).coerceAtLeast(0))
                    windowManager.updateViewLayout(root, params)
                    setDismissEngaged(event.rawY >= bounds.height() - dp(DISMISS_HIT_ZONE_DP))
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (moved) {
                    val shouldDismiss = dismissEngaged
                    hideDismissTarget()
                    if (shouldDismiss) dismissForCurrentInput()
                } else {
                    view.performClick()
                    acknowledgeTap()
                    onToggle()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                hideDismissTarget()
                return true
            }
        }
        return false
    }

    private fun showDismissTarget() {
        if (!attached) return
        dismissRoot.visibility = View.VISIBLE
        dismissRoot.animate().alpha(1f).setDuration(100).start()
    }

    private fun setDismissEngaged(engaged: Boolean) {
        if (dismissEngaged == engaged) return
        dismissEngaged = engaged
        dismissTarget.background = roundedDrawable(if (engaged) ERROR_DARK else DISMISS_IDLE, 31f)
        dismissTarget.animate().scaleX(if (engaged) 1.14f else 1f).scaleY(if (engaged) 1.14f else 1f)
            .setDuration(90).start()
        root.animate().scaleX(if (engaged) 0.92f else 1f).scaleY(if (engaged) 0.92f else 1f)
            .setDuration(90).start()
        if (engaged) mic.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun hideDismissTarget() {
        dismissEngaged = false
        root.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
        dismissTarget.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
        if (attached) {
            dismissRoot.animate().alpha(0f).setDuration(90).withEndAction {
                dismissRoot.visibility = View.GONE
            }.start()
        }
    }

    private fun dismissForCurrentInput() {
        dismissedForInput = true
        mic.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        root.animate().alpha(0f).scaleX(0.76f).scaleY(0.76f).setDuration(120).withEndAction {
            root.visibility = View.GONE
            root.alpha = 1f
            root.scaleX = 1f
            root.scaleY = 1f
            if (expanded) collapse()
        }.start()
        onDismiss()
    }

    private fun baseParams(widthDp: Int, heightDp: Int) = WindowManager.LayoutParams(
        dp(widthDp),
        dp(heightDp),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    )

    private fun roundedDrawable(color: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).roundToInt()
    private fun dp(value: Float): Int = (value * service.resources.displayMetrics.density).roundToInt()

    companion object {
        private const val BUBBLE_DP = 56
        private const val CARD_DP = 336
        private const val CARD_HEIGHT_DP = 210
        private const val DISMISS_AREA_DP = 104
        private const val DISMISS_HIT_ZONE_DP = 118
        private const val MAX_VISIBLE_PENDING = 4
        private const val MAX_PENDING_CHARS = 140
        private val CARD = Color.rgb(24, 26, 31)
        private val TEXT = Color.rgb(245, 247, 250)
        private val MUTED = Color.rgb(163, 169, 179)
        private val ACCENT = Color.rgb(80, 105, 215)
        private val ACCENT_SOFT = Color.rgb(171, 184, 255)
        private val WARM = Color.rgb(242, 194, 111)
        private val ERROR = Color.rgb(255, 139, 139)
        private val ERROR_DARK = Color.rgb(160, 52, 60)
        private val DISMISS_IDLE = Color.rgb(55, 58, 66)
    }
}