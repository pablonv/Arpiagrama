package com.example.arpiagrama.operational.ui;

import com.example.arpiagrama.operational.preferences.ThemePreferences;
import com.example.arpiagrama.operational.preferences.TalkbackPreferences;
import com.example.arpiagrama.operational.preferences.OpenAiApiKeyPreferences;

import com.example.arpiagrama.R;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

public class LandingActivity extends BaseActivity {

    private static final float FONT_SCALE_STEP = 0.1f;
    private static final float SPEECH_RATE_STEP = 0.1f;
    private static final String APP_NAV_STATE_PREFS = "app_navigation_state";
    private static final String EXTERNAL_HELP_ACTIVE_KEY = "external_help_active";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (isExternalHelpInProgress()) {
            navigateToAndFinish(MainActivity.class);
            return;
        }
        setContentView(R.layout.activity_landing);

        MaterialButton buttonUse = findViewById(R.id.button_use_arpiagrama);
        MaterialButton buttonVolunteer = findViewById(R.id.button_be_volunteer);
        MaterialButton buttonFontDecrease = findViewById(R.id.button_font_decrease);
        MaterialButton buttonFontIncrease = findViewById(R.id.button_font_increase);
        MaterialSwitch toggleTalkback = findViewById(R.id.button_toggle_talkback);
        MaterialSwitch toggleDarkTheme = findViewById(R.id.button_toggle_dark_theme);
        MaterialButton buttonSpeechDecrease = findViewById(R.id.button_speech_speed_decrease);
        MaterialButton buttonSpeechIncrease = findViewById(R.id.button_speech_speed_increase);
        MaterialButton buttonConfigureOpenAi = findViewById(R.id.button_configure_openai);

        setButtonAction(buttonUse, () -> navigateToTerms(TermsActivity.MODE_USER));
        setButtonAction(buttonVolunteer, () -> navigateToTerms(TermsActivity.MODE_VOLUNTEER));

        toggleTalkback.setChecked(isProjectTalkbackEnabled());
        toggleTalkback.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isProjectTalkbackEnabled() == isChecked) {
                return;
            }
            updateProjectTalkbackEnabled(isChecked);
            speakForAccessibility(getString(isChecked
                    ? R.string.talkback_enabled_message
                    : R.string.talkback_disabled_message));
        });

        toggleDarkTheme.setChecked(ThemePreferences.isDarkThemeEnabled(this));
        toggleDarkTheme.setOnCheckedChangeListener((buttonView, isChecked) ->
                ThemePreferences.setDarkThemeEnabled(this, isChecked)
        );

        setButtonAction(buttonFontDecrease, () -> adjustFontScale(-FONT_SCALE_STEP));
        setButtonAction(buttonFontIncrease, () -> adjustFontScale(FONT_SCALE_STEP));
        setButtonAction(buttonSpeechDecrease, () -> adjustSpeechRate(-SPEECH_RATE_STEP));
        setButtonAction(buttonSpeechIncrease, () -> adjustSpeechRate(SPEECH_RATE_STEP));
        setButtonAction(buttonConfigureOpenAi, this::showOpenAiKeyDialog);
        updateFontButtons(buttonFontDecrease, buttonFontIncrease);
        updateSpeechButtons(buttonSpeechDecrease, buttonSpeechIncrease);
    }

    private void showOpenAiKeyDialog() {
        EditText apiKeyInput = new EditText(this);
        apiKeyInput.setHint(R.string.openai_api_key_hint);
        apiKeyInput.setContentDescription(getString(R.string.openai_api_key_hint));
        apiKeyInput.setSingleLine(true);
        apiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        boolean hasStoredKey = OpenAiApiKeyPreferences.hasKey(this);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.openai_api_key_title)
                .setMessage(hasStoredKey
                        ? R.string.openai_api_key_configured_message
                        : R.string.openai_api_key_explanation)
                .setView(apiKeyInput)
                .setPositiveButton(R.string.openai_api_key_save, null)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.openai_api_key_remove, null)
                .create();

        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String apiKey = apiKeyInput.getText().toString().trim();
                if (apiKey.isEmpty()) {
                    apiKeyInput.setError(getString(R.string.openai_api_key_required));
                    return;
                }
                try {
                    OpenAiApiKeyPreferences.save(this, apiKey);
                    Toast.makeText(this, R.string.openai_api_key_saved, Toast.LENGTH_LONG).show();
                    dialog.dismiss();
                } catch (IllegalStateException e) {
                    apiKeyInput.setError(getString(R.string.openai_api_key_save_error));
                }
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(hasStoredKey);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                OpenAiApiKeyPreferences.clear(this);
                Toast.makeText(this, R.string.openai_api_key_removed, Toast.LENGTH_LONG).show();
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private void navigateToTerms(int mode) {
        // O modo selecionado define os textos e o fluxo de aceite na tela de termos.
        Intent intent = new Intent(this, TermsActivity.class);
        intent.putExtra(TermsActivity.EXTRA_MODE, mode);
        startActivity(intent);
    }

    private void adjustFontScale(float delta) {
        float currentScale = ThemePreferences.getFontScale(this);
        float updatedScale = ThemePreferences.clampFontScale(currentScale + delta);
        if (updatedScale != currentScale) {
            ThemePreferences.setFontScale(this, updatedScale);
            recreate();
        }
    }

    private void updateFontButtons(MaterialButton decreaseButton, MaterialButton increaseButton) {
        float currentScale = ThemePreferences.getFontScale(this);
        decreaseButton.setEnabled(currentScale > ThemePreferences.getMinFontScale());
        increaseButton.setEnabled(currentScale < ThemePreferences.getMaxFontScale());
    }

    private void adjustSpeechRate(float delta) {
        float currentRate = TalkbackPreferences.getSpeechRate(this);
        float updatedRate = TalkbackPreferences.clampSpeechRate(currentRate + delta);
        if (updatedRate != currentRate) {
            updateAccessibilitySpeechRate(updatedRate);
            recreate();
        }
    }

    private void updateSpeechButtons(MaterialButton decreaseButton, MaterialButton increaseButton) {
        float currentRate = TalkbackPreferences.getSpeechRate(this);
        decreaseButton.setEnabled(currentRate > TalkbackPreferences.getMinSpeechRate());
        increaseButton.setEnabled(currentRate < TalkbackPreferences.getMaxSpeechRate());
    }

    private boolean isExternalHelpInProgress() {
        return getSharedPreferences(APP_NAV_STATE_PREFS, MODE_PRIVATE)
                .getBoolean(EXTERNAL_HELP_ACTIVE_KEY, false);
    }
}
