package com.goodlight.floatingvoicebubble.testhost

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Test-APK-only activity used as a real external text editor for AccessibilityService validation.
 * It is deliberately not part of the production APK.
 */
class OverlayInputHostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(245, 245, 245))
            setPadding(dp(24), dp(64), dp(24), dp(24))
        }
        val title = TextView(this).apply {
            text = "VoiceBubble 外部入力欄テスト"
            textSize = 18f
            setTextColor(Color.rgb(32, 32, 32))
        }
        val editor = EditText(this).apply {
            hint = "ここへ音声入力"
            contentDescription = "VoiceBubble CI 外部入力欄"
            textSize = 18f
            setTextColor(Color.rgb(20, 20, 20))
            setHintTextColor(Color.rgb(110, 110, 110))
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minHeight = dp(144)
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isSingleLine = false
        }
        root.addView(
            title,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ),
        )
        root.addView(
            editor,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(180),
                Gravity.TOP,
            ).apply { topMargin = dp(56) },
        )
        setContentView(root)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        editor.requestFocus()
        editor.postDelayed({
            getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }, 250L)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
