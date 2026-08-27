package com.example.arpiagrama.operational.ui;

import com.example.arpiagrama.R;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;

public class LoginActivity extends BaseActivity {

    private static final String PREFS_NAME = "auth_prefs";
    private static final String KEY_NAME = "user_name";
    private static final String KEY_EMAIL = "user_email";
    private static final String KEY_PASSWORD = "user_password";

    private EditText nameInput;
    private EditText emailInput;
    private EditText passwordInput;
    private TextView statusText;

    private SharedPreferences authPreferences;
    private GoogleSignInClient googleSignInClient;
    private ActivityResultLauncher<Intent> googleSignInLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        authPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        nameInput = findViewById(R.id.input_name);
        emailInput = findViewById(R.id.input_email);
        passwordInput = findViewById(R.id.input_password);
        statusText = findViewById(R.id.text_status);

        enforceFirstLetterUppercase(nameInput);


        Button registerButton = findViewById(R.id.button_register);
        Button loginButton = findViewById(R.id.button_login);
        Button googleButton = findViewById(R.id.button_google);

        setupGoogleSignIn();
        restoreSavedUser();

        setButtonAction(registerButton, this::registerUser);
        setButtonAction(loginButton, this::loginWithEmail);
        setButtonAction(googleButton, () -> googleSignInLauncher.launch(googleSignInClient.getSignInIntent()));
    }

    @Override
    protected void onStart() {
        super.onStart();
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
        if (account != null) {
            navigateToStart(account.getDisplayName(), account.getEmail());
        }
    }

    private void setupGoogleSignIn() {
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .build();
        googleSignInClient = GoogleSignIn.getClient(this, gso);

        googleSignInLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getData() == null) {
                        Toast.makeText(this, R.string.google_sign_in_error, Toast.LENGTH_SHORT).show();
                        navigateToLanding();
                        return;
                    }

                    Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(result.getData());
                    handleGoogleSignIn(task);
                }
        );
    }

    private void handleGoogleSignIn(Task<GoogleSignInAccount> completedTask) {
        try {
            GoogleSignInAccount account = completedTask.getResult(ApiException.class);
            if (account != null) {
                navigateToStart(account.getDisplayName(), account.getEmail());
            }
        } catch (ApiException e) {
            Toast.makeText(this, R.string.google_sign_in_error, Toast.LENGTH_SHORT).show();
            navigateToLanding();
        }
    }

    private void registerUser() {
        String name = nameInput.getText().toString().trim();
        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();

        if (!validateRequiredFields(name, email, password)) {
            return;
        }

        authPreferences.edit()
                .putString(KEY_NAME, name)
                .putString(KEY_EMAIL, email)
                .putString(KEY_PASSWORD, password)
                .apply();

        statusText.setText(getString(R.string.register_success, name));
    }

    private void loginWithEmail() {
        String savedEmail = authPreferences.getString(KEY_EMAIL, null);
        String savedPassword = authPreferences.getString(KEY_PASSWORD, null);

        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();

        if (!validateRequiredFields(email, password)) {
            return;
        }

        if (savedEmail != null && savedPassword != null
                && savedEmail.equalsIgnoreCase(email)
                && savedPassword.equals(password)) {
            String name = authPreferences.getString(KEY_NAME, "");
            navigateToStart(name, email);
        } else {
            Toast.makeText(this, R.string.login_failed, Toast.LENGTH_SHORT).show();
            navigateToLanding();
        }
    }

    private void restoreSavedUser() {
        String savedName = authPreferences.getString(KEY_NAME, "");
        String savedEmail = authPreferences.getString(KEY_EMAIL, "");

        if (!TextUtils.isEmpty(savedName)) {
            nameInput.setText(savedName);
        }
        if (!TextUtils.isEmpty(savedEmail)) {
            emailInput.setText(savedEmail);
        }
    }

    private void navigateToStart(@Nullable String displayName, @Nullable String email) {
        Intent intent = new Intent(this, StartActivity.class);
        intent.putExtra(StartActivity.EXTRA_USER_NAME, displayName);
        intent.putExtra(StartActivity.EXTRA_USER_EMAIL, email);
        startActivity(intent);
        finish();
    }

    private void navigateToLanding() {
        Intent intent = new Intent(this, LandingActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    /**
     * Centraliza validação de campos para manter o mesmo feedback em cadastro e login.
     */
    private boolean validateRequiredFields(String... fields) {
        for (String field : fields) {
            if (TextUtils.isEmpty(field)) {
                Toast.makeText(this, R.string.error_empty_fields, Toast.LENGTH_SHORT).show();
                return false;
            }
        }
        return true;
    }
}
