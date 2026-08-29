package com.goodlight.floatingvoicebubble.testhost;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Test-APK-only activity used as a real external text editor for AccessibilityService validation.
 * It intentionally depends only on the Android framework so the instrumentation APK can be launched
 * as a standalone external app without requiring Kotlin runtime classes from the target APK.
 */
public final class OverlayInputHostActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(245, 245, 245));
        root.setPadding(dp(24), dp(64), dp(24), dp(24));

        TextView title = new TextView(this);
        title.setText("VoiceBubble 外部入力欄テスト");
        title.setTextSize(18f);
        title.setTextColor(Color.rgb(32, 32, 32));

        EditText editor = new EditText(this);
        editor.setHint("ここへ音声入力");
        editor.setContentDescription("VoiceBubble CI 外部入力欄");
        editor.setTextSize(18f);
        editor.setTextColor(Color.rgb(20, 20, 20));
        editor.setHintTextColor(Color.rgb(110, 110, 110));
        editor.setInputType(
            InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        );
        editor.setMinHeight(dp(144));
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setPadding(dp(16), dp(16), dp(16), dp(16));
        editor.setSingleLine(false);

        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        );
        root.addView(title, titleParams);

        FrameLayout.LayoutParams editorParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(180),
            Gravity.TOP
        );
        editorParams.topMargin = dp(56);
        root.addView(editor, editorParams);

        setContentView(root);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        editor.requestFocus();
        editor.postDelayed(() -> {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) {
                imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 250L);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
