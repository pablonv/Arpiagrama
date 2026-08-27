package com.example.arpiagrama.operational.ui;

import com.example.arpiagrama.R;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;


import com.google.android.material.button.MaterialButton;

public class TermsActivity extends BaseActivity {

    public static final String EXTRA_MODE = "extra_terms_mode";
    public static final int MODE_USER = 0;
    public static final int MODE_VOLUNTEER = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terms);

        TextView introText = findViewById(R.id.text_terms_intro);
        TextView itemsText = findViewById(R.id.text_terms_items);
        TextView footerText = findViewById(R.id.text_terms_footer);
        MaterialButton termsServiceButton = findViewById(R.id.button_terms_service);
        MaterialButton privacyPolicyButton = findViewById(R.id.button_privacy_policy);
        MaterialButton acceptButton = findViewById(R.id.button_accept);

        int mode = getIntent().getIntExtra(EXTRA_MODE, MODE_USER);

        if (mode == MODE_VOLUNTEER) {
            introText.setText(R.string.terms_volunteer_intro);
            itemsText.setText(R.string.terms_volunteer_items);
            footerText.setText(R.string.terms_volunteer_footer);
        } else {
            introText.setText(R.string.terms_user_intro);
            itemsText.setText(R.string.terms_user_items);
            footerText.setText(R.string.terms_user_footer);
        }

        setButtonAction(termsServiceButton, this::showPlaceholderMessage);
        setButtonAction(privacyPolicyButton, this::showPlaceholderMessage);

        setButtonAction(acceptButton, () -> {
            // Direciona para o cadastro adequado conforme o perfil escolhido na landing.
            navigateToAndFinish(resolveDestination(mode));
        });
    }

    private void showPlaceholderMessage() {
        Toast.makeText(this, R.string.terms_open_link_placeholder, Toast.LENGTH_SHORT).show();
    }

    private Class<?> resolveDestination(int mode) {
        return mode == MODE_VOLUNTEER
                ? VolunteerRegistrationActivity.class
                : LoginActivity.class;
    }
}
