package com.goodlight.floatingvoicebubble.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
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
    private val onCommitRaw: () -> Unit,
    private val onCancel: () -> Unit,
    private val onDismiss: () -> Unit,
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val root = FrameLayout(service)
    private val card = LinearLayout(service)
    private val status = TextView(service)
    private val caption = TextView(service)
    private val raw = action("補正せず入力", TEXT, ACTION_SURFACE) { onCommitRaw() }
    private val cancel = action("破棄", ACCENT_SOFT, Color.TRANSPARENT) { onCancel() }
    private val transcript = TextView(service)
    private val scroll = ScrollView(service)
    private val mic = object : TextView(service) {
        override fun performClick(): Boolean {
            super.performClick()
            acknowledgeTap()
            onToggle()
            return true
        }
    }
    private val dismissRoot = FrameLayout(service)
    private val dismissTarget = TextView(service)
    private var params = params(BUBBLE_DP, BUBBLE_DP)
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
        root.apply {
            clipChildren = false
            clipToPadding = false
            visibility = View.GONE
        }
        card.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(9), dp(12), dp(12))
            background = bg(CARD, 22f)
            elevation = dp(12).toFloat()
            alpha = 0f
            scaleX = .96f
            scaleY = .96f
            visibility = View.GONE
        }
        status.apply {
            setTextColor(MUTED)
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            text = "●  待機中"
            contentDescription = "VoiceBubbleの状態"
        }
        card.addView(status, LinearLayout.LayoutParams(-1, dp(34)))

        val actions = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(raw, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
            addView(cancel, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        card.addView(actions, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(7) })

        caption.apply {
            text = "認識結果"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(MUTED)
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "下の大きな文字欄の種類"
        }
        card.addView(caption, LinearLayout.LayoutParams(-1, dp(24)))

        transcript.apply {
            setTextColor(TEXT)
            textSize = 17f
            setLineSpacing(0f, 1.22f)
            setTextIsSelectable(false)
            setPadding(dp(12), dp(10), dp(12), dp(12))
            contentDescription = "音声認識された本文"
        }
        scroll.apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            background = bg(TRANSCRIPT_SURFACE, 14f, TRANSCRIPT_STROKE)
            addView(transcript, FrameLayout.LayoutParams(-1, -2))
        }
        card.addView(scroll, LinearLayout.LayoutParams(-1, dp(142)))
        root.addView(
            card,
            FrameLayout.LayoutParams(dp(CARD_DP), dp(CARD_HEIGHT_DP), Gravity.TOP or Gravity.START),
        )

        mic.apply {
            gravity = Gravity.CENTER
            text = "●"
            textSize = 23f
            setTextColor(Color.WHITE)
            background = bg(ACCENT, 28f)
            elevation = dp(14).toFloat()
            contentDescription = "音声入力を開始または確定"
            isClickable = true
            setOnTouchListener(::onMicTouch)
        }
        root.addView(mic, FrameLayout.LayoutParams(dp(BUBBLE_DP), dp(BUBBLE_DP), Gravity.BOTTOM or Gravity.END))

        dismissRoot.apply {
            visibility = View.GONE
            alpha = 0f
        }
        dismissTarget.apply {
            gravity = Gravity.CENTER
            text = "×"
            textSize = 28f
            setTextColor(Color.WHITE)
            background = bg(DISMISS_IDLE, 31f)
            contentDescription = "ここへ離すとVoiceBubbleを閉じます"
        }
        dismissRoot.addView(dismissTarget, FrameLayout.LayoutParams(dp(62), dp(62), Gravity.CENTER))

        raw.contentDescription = "AI補正を使わず認識結果をそのまま入力"
        cancel.contentDescription = "音声入力を破棄"
    }

    fun attach() {
        if (attached) return
        params.gravity = Gravity.TOP or Gravity.START
        val bounds = wm.currentWindowMetrics.bounds
        params.x = (bounds.width() - dp(BUBBLE_DP) - dp(18)).coerceAtLeast(0)
        params.y = (bounds.height() * .58).roundToInt()
        wm.addView(root, params)
        wm.addView(dismissRoot, dismissParams)
        attached = true
        showIdle()
        refresh()
    }

    fun detach() {
        if (attached) {
            runCatching { wm.removeView(root) }
            runCatching { wm.removeView(dismissRoot) }
            attached = false
        }
    }

    fun setInputAvailable(available: Boolean, resetDismissal: Boolean = false) {
        inputAvailable = available
        if (available && resetDismissal) dismissedForInput = false
        if (!available) {
            hideDismiss()
            if (expanded) collapse()
        }
        refresh(resetDismissal)
    }

    fun acknowledgeTap() {
        mic.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun showIdle() {
        mic.text = "●"
        mic.setTextColor(Color.WHITE)
        mic.background = bg(ACCENT, 28f)
        buttons(false)
        if (expanded) collapse()
        refresh()
    }

    fun showListening(text: String, state: String = "聴いています", pending: List<String> = emptyList()) {
        refresh()
        if (!visible()) return
        expand()
        status.text = if (pending.isEmpty()) {
            "●  $state"
        } else {
            "●  $state  ·  前の発話を処理中 ${pending.size}件"
        }
        status.setTextColor(ACCENT_SOFT)
        caption.text = "認識中の発話"
        if (text.isBlank()) {
            setPlaceholder("話し始めると、ここに認識された文字が出ます。")
        } else {
            setUserTranscript(text)
        }
        buttons(true)
        mic.text = "■"
        bottom()
    }

    fun showFinalizing(text: String, state: String = "整えています") = showFinalizingStack(listOf(text), state)

    fun showFinalizingStack(pending: List<String>, state: String = "整えています") {
        refresh()
        if (!visible()) return
        if (pending.isEmpty()) {
            showIdle()
            return
        }
        expand()
        status.text = "●  $state  ·  ${pending.size}件"
        status.setTextColor(WARM)
        caption.text = "確定待ちの認識結果"
        setUserTranscript(pendingText(pending))
        buttons(true)
        mic.text = "●"
        bottom()
    }

    fun showError(message: String) {
        refresh()
        if (!visible()) return
        expand()
        status.text = "●  入力できませんでした"
        status.setTextColor(ERROR)
        caption.text = "システムメッセージ"
        setSystemMessage(message)
        raw.visibility = View.GONE
        cancel.visibility = View.VISIBLE
        mic.text = "●"
    }

    private fun action(label: String, color: Int, fill: Int, block: () -> Unit) = TextView(service).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(color)
        minHeight = dp(48)
        background = bg(fill, 14f, ACTION_STROKE)
        visibility = View.GONE
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            block()
        }
    }

    private fun buttons(show: Boolean) {
        val visibility = if (show) View.VISIBLE else View.GONE
        raw.visibility = visibility
        cancel.visibility = visibility
    }

    /** Large text in this surface is always user speech, never a system sentence. */
    private fun setUserTranscript(text: String) {
        scroll.background = bg(TRANSCRIPT_SURFACE, 14f, TRANSCRIPT_STROKE)
        transcript.setTextColor(TEXT)
        transcript.textSize = 17f
        transcript.typeface = Typeface.DEFAULT
        transcript.text = text
        transcript.contentDescription = "音声認識された本文"
    }

    private fun setPlaceholder(text: String) {
        scroll.background = bg(TRANSCRIPT_SURFACE, 14f, TRANSCRIPT_STROKE)
        transcript.setTextColor(MUTED)
        transcript.textSize = 13.5f
        transcript.typeface = Typeface.DEFAULT
        transcript.text = text
        transcript.contentDescription = "認識本文の表示待ち"
    }

    private fun setSystemMessage(text: String) {
        scroll.background = bg(SYSTEM_SURFACE, 14f, ERROR_DARK)
        transcript.setTextColor(ERROR)
        transcript.textSize = 13.5f
        transcript.typeface = Typeface.DEFAULT_BOLD
        transcript.text = text
        transcript.contentDescription = "VoiceBubbleのシステムメッセージ"
    }

    private fun pendingText(items: List<String>): String {
        val visibleItems = items.takeLast(MAX_PENDING).map { value ->
            val clean = value.trim()
            if (clean.length <= 180) clean else clean.take(180) + "…"
        }
        return buildString {
            visibleItems.forEachIndexed { index, value ->
                if (index > 0) append("\n\n────────\n\n")
                append(value)
            }
            if (items.size > MAX_PENDING) {
                append("\n\n…")
            }
        }
    }

    private fun visible() = attached && inputAvailable && !dismissedForInput

    private fun refresh(resetDismissal: Boolean = false) {
        if (resetDismissal && inputAvailable) dismissedForInput = false
        if (attached) root.visibility = if (visible()) View.VISIBLE else View.GONE
    }

    private fun expand() {
        if (expanded) return
        expanded = true
        val right = params.x + params.width
        params.width = dp(CARD_DP)
        params.height = dp(CARD_HEIGHT_DP)
        params.x = (right - params.width).coerceAtLeast(0)
        clamp()
        if (attached) wm.updateViewLayout(root, params)
        card.visibility = View.VISIBLE
        card.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(130)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun collapse() {
        expanded = false
        val right = params.x + params.width
        card.animate()
            .alpha(0f)
            .scaleX(.96f)
            .scaleY(.96f)
            .setDuration(110)
            .withEndAction { if (!expanded) card.visibility = View.GONE }
            .start()
        params.width = dp(BUBBLE_DP)
        params.height = dp(BUBBLE_DP)
        params.x = (right - params.width).coerceAtLeast(0)
        clamp()
        if (attached) wm.updateViewLayout(root, params)
    }

    private fun clamp() {
        val bounds = wm.currentWindowMetrics.bounds
        params.x = params.x.coerceIn(0, (bounds.width() - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (bounds.height() - params.height).coerceAtLeast(0))
    }

    private fun bottom() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun onMicTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downX = params.x
                downY = params.y
                moved = false
                dismissEngaged = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!moved && (abs(dx) > dp(8) || abs(dy) > dp(8))) {
                    moved = true
                    showDismiss()
                }
                if (moved && attached) {
                    val bounds = wm.currentWindowMetrics.bounds
                    params.x = (downX + dx.toInt()).coerceIn(0, (bounds.width() - params.width).coerceAtLeast(0))
                    params.y = (downY + dy.toInt()).coerceIn(0, (bounds.height() - params.height).coerceAtLeast(0))
                    wm.updateViewLayout(root, params)
                    engage(event.rawY >= bounds.height() - dp(DISMISS_HIT_ZONE_DP))
                }
            }
            MotionEvent.ACTION_UP -> if (moved) {
                val dismiss = dismissEngaged
                hideDismiss()
                if (dismiss) dismissForInput()
            } else {
                view.performClick()
            }
            MotionEvent.ACTION_CANCEL -> hideDismiss()
            else -> return false
        }
        return true
    }

    private fun showDismiss() {
        if (attached) {
            dismissRoot.visibility = View.VISIBLE
            dismissRoot.animate().alpha(1f).setDuration(100).start()
        }
    }

    private fun engage(on: Boolean) {
        if (dismissEngaged == on) return
        dismissEngaged = on
        dismissTarget.background = bg(if (on) ERROR_DARK else DISMISS_IDLE, 31f)
        dismissTarget.animate().scaleX(if (on) 1.14f else 1f).scaleY(if (on) 1.14f else 1f).setDuration(90).start()
        root.animate().scaleX(if (on) .92f else 1f).scaleY(if (on) .92f else 1f).setDuration(90).start()
        if (on) mic.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun hideDismiss() {
        dismissEngaged = false
        root.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
        dismissTarget.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
        if (attached) {
            dismissRoot.animate().alpha(0f).setDuration(90).withEndAction {
                dismissRoot.visibility = View.GONE
            }.start()
        }
    }

    private fun dismissForInput() {
        dismissedForInput = true
        mic.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        root.animate().alpha(0f).scaleX(.76f).scaleY(.76f).setDuration(120).withEndAction {
            root.visibility = View.GONE
            root.alpha = 1f
            root.scaleX = 1f
            root.scaleY = 1f
            if (expanded) collapse()
        }.start()
        onDismiss()
    }

    private fun params(w: Int, h: Int) = WindowManager.LayoutParams(
        dp(w),
        dp(h),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    )

    private fun bg(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).roundToInt()
    private fun dp(value: Float) = (value * service.resources.displayMetrics.density).roundToInt()

    companion object {
        private const val BUBBLE_DP = 56
        private const val CARD_DP = 336
        private const val CARD_HEIGHT_DP = 278
        private const val DISMISS_AREA_DP = 104
        private const val DISMISS_HIT_ZONE_DP = 118
        private const val MAX_PENDING = 4

        private val CARD = Color.rgb(24, 26, 31)
        private val TEXT = Color.rgb(245, 247, 250)
        private val MUTED = Color.rgb(156, 163, 176)
        private val ACCENT = Color.rgb(80, 105, 215)
        private val ACCENT_SOFT = Color.rgb(171, 184, 255)
        private val WARM = Color.rgb(242, 194, 111)
        private val ERROR = Color.rgb(255, 139, 139)
        private val ERROR_DARK = Color.rgb(160, 52, 60)
        private val DISMISS_IDLE = Color.rgb(55, 58, 66)
        private val ACTION_SURFACE = Color.rgb(39, 43, 54)
        private val ACTION_STROKE = Color.rgb(80, 86, 101)
        private val TRANSCRIPT_SURFACE = Color.rgb(17, 19, 24)
        private val TRANSCRIPT_STROKE = Color.rgb(50, 56, 69)
        private val SYSTEM_SURFACE = Color.rgb(48, 28, 31)
    }
}
