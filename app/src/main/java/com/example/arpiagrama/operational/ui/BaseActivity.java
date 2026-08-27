package com.example.arpiagrama.operational.ui;

import com.example.arpiagrama.operational.preferences.ThemePreferences;
import com.example.arpiagrama.operational.preferences.TalkbackPreferences;

import com.example.arpiagrama.R;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.arpiagrama.operational.audio.SpeechController;
import com.google.android.material.button.MaterialButton;

import java.util.Map;
import java.util.WeakHashMap;

public class BaseActivity extends AppCompatActivity {

    private static final long TALKBACK_DOUBLE_TAP_WINDOW_MS = 600L;
    private static final float MIN_BUTTON_TEXT_SIZE_SP = 18f;
    private static final String BUTTON_FONT_FAMILY = "verdana";
    private static final int BUTTON_STROKE_WIDTH_DP = 3;

    private final Map<View, Long> pendingTalkbackClicks = new WeakHashMap<>();
    private SpeechController accessibilitySpeechController;

    /**
     * Navegação padronizada para reduzir código repetido nas Activities.
     */
    protected void navigateTo(Class<?> destinationActivity) {
        startActivity(new Intent(this, destinationActivity));
    }

    /**
     * Navegação com finalização da Activity atual.
     */
    protected void navigateToAndFinish(Class<?> destinationActivity) {
        navigateTo(destinationActivity);
        finish();
    }

    protected void enforceFirstLetterUppercase(EditText editText) {
        if (editText == null) {
            return;
        }
        editText.addTextChangedListener(new TextWatcher() {
            private boolean updating;

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // no-op
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // no-op
            }

            @Override
            public void afterTextChanged(Editable editable) {
                if (updating || editable == null || editable.length() == 0) {
                    return;
                }
                char first = editable.charAt(0);
                char upper = Character.toUpperCase(first);
                if (first == upper) {
                    return;
                }
                updating = true;
                editable.replace(0, 1, String.valueOf(upper));
                updating = false;
            }
        });
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(ThemePreferences.applyFontScale(newBase));
    }

    @Override
    protected void onPostCreate(@Nullable Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        configureTalkbackButtons();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (accessibilitySpeechController != null) {
            accessibilitySpeechController.shutdown();
            accessibilitySpeechController = null;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }

        if (event.getKeyCode() == KeyEvent.KEYCODE_TAB && handleTabNavigation(event)) {
            return true;
        }

        if ((event.getKeyCode() == KeyEvent.KEYCODE_F1
                || event.getKeyCode() == KeyEvent.KEYCODE_F2
                || event.getKeyCode() == KeyEvent.KEYCODE_F3)
                && onFunctionKeyPressed(event.getKeyCode())) {
            return true;
        }

        return super.dispatchKeyEvent(event);
    }

    protected boolean onFunctionKeyPressed(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_F1) {
            return clickIfVisible(R.id.button_context_description);
        }
        if (keyCode == KeyEvent.KEYCODE_F2) {
            return clickIfVisible(R.id.button_external_help);
        }
        if (keyCode == KeyEvent.KEYCODE_F3) {
            return clickIfVisible(R.id.button_save_volunteer)
                    || clickIfVisible(R.id.button_download_image);
        }
        return false;
    }

    protected void setButtonAction(Button button, Runnable action) {
        if (button == null || action == null) {
            return;
        }
        // Mantém os botões com comportamento consistente em todos os fluxos.
        prepareTalkbackButton(button);
        button.setOnClickListener(view -> handleTalkbackAwareClick(button, action));
    }

    protected boolean isProjectTalkbackEnabled() {
        return TalkbackPreferences.isEnabled(this);
    }

    protected void updateProjectTalkbackEnabled(boolean enabled) {
        TalkbackPreferences.setEnabled(this, enabled);
        pendingTalkbackClicks.clear();
        if (!enabled && accessibilitySpeechController != null) {
            accessibilitySpeechController.stopAll();
        }
    }

    protected void updateAccessibilitySpeechRate(float speechRate) {
        float clampedRate = TalkbackPreferences.clampSpeechRate(speechRate);
        TalkbackPreferences.setSpeechRate(this, clampedRate);
        if (accessibilitySpeechController != null) {
            accessibilitySpeechController.setSpeechRate(clampedRate);
        }
    }

    protected void speakForAccessibility(String text) {
        if (!isProjectTalkbackEnabled() || TextUtils.isEmpty(text)) {
            return;
        }
        if (accessibilitySpeechController == null) {
            accessibilitySpeechController = new SpeechController(getApplicationContext());
            accessibilitySpeechController.setSpeechRate(TalkbackPreferences.getSpeechRate(this));
        }
        accessibilitySpeechController.speakImmediate(text);
    }

    protected String getButtonAnnouncement(View view) {
        if (view == null) {
            return "";
        }
        CharSequence contentDescription = view.getContentDescription();
        if (!TextUtils.isEmpty(contentDescription)) {
            return contentDescription.toString();
        }
        if (view instanceof Button) {
            CharSequence text = ((Button) view).getText();
            if (!TextUtils.isEmpty(text)) {
                return text.toString();
            }
        }
        return "";
    }

    protected void configureTalkbackButtons() {
        View rootView = getWindow() != null ? getWindow().getDecorView() : null;
        if (rootView != null) {
            configureTalkbackButtonsRecursively(rootView);
        }
    }

    private void configureTalkbackButtonsRecursively(View view) {
        if (view instanceof Button) {
            prepareTalkbackButton((Button) view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                configureTalkbackButtonsRecursively(group.getChildAt(i));
            }
        }
    }

    private void prepareTalkbackButton(Button button) {
        if (button == null) {
            return;
        }
        enforceButtonTypography(button);
        button.setFocusable(true);
        button.setFocusableInTouchMode(false);

        Object configuredTag = button.getTag(R.id.tag_talkback_configured);
        if (Boolean.TRUE.equals(configuredTag)) {
            return;
        }

        button.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                speakForAccessibility(getButtonAnnouncement(view));
            }
        });
        button.setOnHoverListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_HOVER_ENTER) {
                speakForAccessibility(getButtonAnnouncement(view));
            }
            return false;
        });
        button.setTag(R.id.tag_talkback_configured, true);
    }

    private void enforceButtonTypography(Button button) {
        if (button == null) {
            return;
        }
        float scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        float currentSizeSp = button.getTextSize() / scaledDensity;
        if (currentSizeSp < MIN_BUTTON_TEXT_SIZE_SP) {
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, MIN_BUTTON_TEXT_SIZE_SP);
        }
        button.setTypeface(Typeface.create(BUTTON_FONT_FAMILY, Typeface.NORMAL));
        enforceThickButtonBorder(button);
    }

    private void enforceThickButtonBorder(Button button) {
        if (!(button instanceof MaterialButton)) {
            return;
        }
        MaterialButton materialButton = (MaterialButton) button;
        int strokeWidthPx = Math.round(
                BUTTON_STROKE_WIDTH_DP * getResources().getDisplayMetrics().density
        );
        materialButton.setStrokeWidth(strokeWidthPx);
        ColorStateList strokeColors = materialButton.getStrokeColor();
        if (strokeColors == null) {
            materialButton.setStrokeColor(ColorStateList.valueOf(Color.BLACK));
        }
    }

    private void handleTalkbackAwareClick(Button button, Runnable action) {
        if (!isProjectTalkbackEnabled()) {
            action.run();
            return;
        }

        long now = System.currentTimeMillis();
        Long lastClickTimestamp = pendingTalkbackClicks.get(button);
        if (lastClickTimestamp != null && now - lastClickTimestamp <= TALKBACK_DOUBLE_TAP_WINDOW_MS) {
            pendingTalkbackClicks.remove(button);
            action.run();
            return;
        }

        pendingTalkbackClicks.put(button, now);
        speakForAccessibility(getButtonAnnouncement(button));
    }

    private boolean clickIfVisible(int viewId) {
        View view = findViewById(viewId);
        if (view != null && view.isShown() && view.isEnabled()) {
            view.performClick();
            return true;
        }
        return false;
    }

    private boolean handleTabNavigation(KeyEvent event) {
        View currentFocus = getCurrentFocus();
        if (currentFocus == null) {
            return false;
        }

        int direction = event.isShiftPressed() ? View.FOCUS_BACKWARD : View.FOCUS_FORWARD;
        View nextFocus = currentFocus.focusSearch(direction);
        if (nextFocus != null) {
            nextFocus.requestFocus();
            return true;
        }
        return false;
    }
}
