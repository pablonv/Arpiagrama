package com.example.arpiagrama.operational.ui;

import com.example.arpiagrama.R;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.TextView;

public class StartActivity extends BaseActivity {

    public static final String EXTRA_USER_NAME = "extra_user_name";
    public static final String EXTRA_USER_EMAIL = "extra_user_email";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start);

        TextView welcomeText = findViewById(R.id.text_welcome_user);
        Button startButton = findViewById(R.id.button_start_detection);
        Button tutorialButton = findViewById(R.id.button_tutorial);

        String displayName = getIntent().getStringExtra(EXTRA_USER_NAME);
        String email = getIntent().getStringExtra(EXTRA_USER_EMAIL);

        if (!TextUtils.isEmpty(displayName)) {
            welcomeText.setText(getString(R.string.start_welcome_user, displayName));
        } else if (!TextUtils.isEmpty(email)) {
            welcomeText.setText(getString(R.string.start_welcome_user, email));
        }

        // A tela inicial funciona como hub de navegação para os dois fluxos principais.
        setButtonAction(startButton, () -> navigateTo(MainActivity.class));
        setButtonAction(tutorialButton, () -> navigateTo(TutorialActivity.class));
    }
}
