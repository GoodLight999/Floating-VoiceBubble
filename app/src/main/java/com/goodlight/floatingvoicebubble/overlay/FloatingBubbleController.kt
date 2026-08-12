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
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val root = FrameLayout(service)
    private val card = LinearLayout(service)
    private val status = TextView(service)
    private val transcript = TextView(service)
    private val transcriptScroll = ScrollView(service)
    private val mic = TextView(service)

    private var params = baseParams(BUBBLE_DP, BUBBLE_DP)
    private var attached = false
    private var expanded = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0
    private var moved = false

    init {
        root.clipChildren = false
        root.clipToPadding = false

        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(16), dp(14), dp(16), dp(14))
        card.background = roundedDrawable(CARD, 22f)
        card.elevation = dp(12).toFloat()
        card.alpha = 0f
        card.scaleX = 0.96f
        card.scaleY = 0.96f
        card.visibility = View.GONE

        status.setTextColor(MUTED)
        status.textSize = 12f
        status.typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
        status.text = "●  待機中"

        transcript.setTextColor(TEXT)
        transcript.textSize = 17f
        transcript.setLineSpacing(0f, 1.18f)
        transcript.setTextIsSelectable(false)
        transcriptScroll.isVerticalScrollBarEnabled = false
        transcriptScroll.overScrollMode = View.OVER_SCROLL_NEVER
        transcriptScroll.addView(
            transcript,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
        )

        card.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(22)))
        card.addView(
            transcriptScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(104)).apply { topMargin = dp(7) },
        )
        root.addView(card, FrameLayout.LayoutParams(dp(CARD_DP), dp(CARD_HEIGHT_DP), Gravity.TOP or Gravity.START))

        mic.gravity = Gravity.CENTER
        mic.text = "●"
        mic.textSize = 23f
        mic.setTextColor(Color.WHITE)
        mic.background = roundedDrawable(ACCENT, 28f)
        mic.elevation = dp(14).toFloat()
        mic.contentDescription = "音声入力を開始または終了"
        mic.setOnTouchListener(::onMicTouch)
        root.addView(mic, FrameLayout.LayoutParams(dp(BUBBLE_DP), dp(BUBBLE_DP), Gravity.BOTTOM or Gravity.END))
    }

    fun attach() {
        if (attached) return
        params.gravity = Gravity.TOP or Gravity.START
        val bounds = windowManager.currentWindowMetrics.bounds
        params.x = (bounds.width() - dp(BUBBLE_DP) - dp(18)).coerceAtLeast(0)
        params.y = (bounds.height() * 0.58).roundToInt()
        windowManager.addView(root, params)
        attached = true
        showIdle()
    }

    fun detach() {
        if (!attached) return
        windowManager.removeView(root)
        attached = false
    }

    fun acknowledgeTap() { mic.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }

    fun showIdle() {
        mic.text = "●"
        mic.setTextColor(Color.WHITE)
        mic.background = roundedDrawable(ACCENT, 28f)
        if (expanded) collapse()
    }

    fun showListening(text: String, state: String = "聴いています") {
        ensureExpanded()
        status.text = "●  $state"
        status.setTextColor(ACCENT_SOFT)
        transcript.text = text.ifBlank { "話し始めると、ここに認識文字が現れます。" }
        transcript.setTextColor(if (text.isBlank()) MUTED else TEXT)
        mic.text = "■"
        scrollToBottom()
    }

    fun showFinalizing(text: String, state: String = "整えています") {
        ensureExpanded()
        status.text = "●  $state"
        status.setTextColor(WARM)
        transcript.text = text.ifBlank { "認識結果を確定しています。" }
        transcript.setTextColor(TEXT)
        mic.text = "■"
        scrollToBottom()
    }

    fun showError(message: String) {
        ensureExpanded()
        status.text = "●  入力できませんでした"
        status.setTextColor(ERROR)
        transcript.text = message
        transcript.setTextColor(TEXT)
        mic.text = "●"
    }

    private fun ensureExpanded() {
        if (expanded) return
        expanded = true
        val previousRight = params.x + params.width
        params.width = dp(CARD_DP)
        params.height = dp(CARD_HEIGHT_DP)
        params.x = (previousRight - params.width).coerceAtLeast(0)
        if (attached) windowManager.updateViewLayout(root, params)
        card.visibility = View.VISIBLE
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(130).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun collapse() {
        expanded = false
        val previousRight = params.x + params.width
        card.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(110)
            .withEndAction { if (!expanded) card.visibility = View.GONE }.start()
        params.width = dp(BUBBLE_DP)
        params.height = dp(BUBBLE_DP)
        params.x = (previousRight - params.width).coerceAtLeast(0)
        if (attached) windowManager.updateViewLayout(root, params)
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
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!moved && (abs(dx) > dp(8) || abs(dy) > dp(8))) moved = true
                if (moved && attached) {
                    val bounds = windowManager.currentWindowMetrics.bounds
                    params.x = (downX + dx.toInt()).coerceIn(0, (bounds.width() - params.width).coerceAtLeast(0))
                    params.y = (downY + dy.toInt()).coerceIn(0, (bounds.height() - params.height).coerceAtLeast(0))
                    windowManager.updateViewLayout(root, params)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    view.performClick()
                    acknowledgeTap()
                    onToggle()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return false
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
        private const val CARD_HEIGHT_DP = 170
        private val CARD = Color.rgb(24, 26, 31)
        private val TEXT = Color.rgb(245, 247, 250)
        private val MUTED = Color.rgb(163, 169, 179)
        private val ACCENT = Color.rgb(80, 105, 215)
        private val ACCENT_SOFT = Color.rgb(157, 173, 255)
        private val WARM = Color.rgb(242, 194, 111)
        private val ERROR = Color.rgb(255, 139, 139)
    }
}
