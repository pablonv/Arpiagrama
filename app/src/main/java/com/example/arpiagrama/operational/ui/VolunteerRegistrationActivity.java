package com.example.arpiagrama.operational.ui;

import com.example.arpiagrama.operational.persistence.Volunteer;
import com.example.arpiagrama.operational.persistence.VolunteerDatabase;

import com.example.arpiagrama.R;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;


import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VolunteerRegistrationActivity extends BaseActivity {

    private EditText nameInput;
    private EditText addressInput;
    private EditText phoneInput;
    private ExecutorService executorService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_volunteer_registration);

        nameInput = findViewById(R.id.input_volunteer_name);
        addressInput = findViewById(R.id.input_volunteer_address);
        phoneInput = findViewById(R.id.input_volunteer_phone);
        enforceFirstLetterUppercase(nameInput);
        enforceFirstLetterUppercase(addressInput);

        Button saveButton = findViewById(R.id.button_save_volunteer);

        executorService = Executors.newSingleThreadExecutor();

        setButtonAction(saveButton, this::saveVolunteer);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null) {
            executorService.shutdown();
        }
    }

    private void saveVolunteer() {
        String name = nameInput.getText().toString().trim();
        String address = addressInput.getText().toString().trim();
        String phone = phoneInput.getText().toString().trim();

        if (!isRegistrationValid(name, address, phone)) {
            Toast.makeText(this, R.string.error_empty_fields, Toast.LENGTH_SHORT).show();
            return;
        }

        Volunteer volunteer = new Volunteer(name, address, phone);
        executorService.execute(() -> {
            // Persistência fora da thread principal evita travamentos em dispositivos mais lentos.
            VolunteerDatabase.getInstance(this).volunteerDao().insert(volunteer);
            runOnUiThread(() -> {
                Toast.makeText(this, R.string.volunteer_saved, Toast.LENGTH_SHORT).show();
                finish();
            });
        });
    }

    private boolean isRegistrationValid(String name, String address, String phone) {
        return !TextUtils.isEmpty(name)
                && !TextUtils.isEmpty(address)
                && !TextUtils.isEmpty(phone);
    }
}
