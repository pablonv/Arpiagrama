package com.example.arpiagrama.operational.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores a user-provided API key encrypted with a non-exportable Android Keystore key. */
public final class OpenAiApiKeyPreferences {
    private static final String TAG = "OpenAiApiKeyPrefs";
    private static final String PREFERENCES_NAME = "openai_api_credentials";
    private static final String KEY_CIPHERTEXT = "ciphertext";
    private static final String KEY_IV = "iv";
    private static final String KEYSTORE_ALIAS = "arpiagrama_openai_api_key";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private OpenAiApiKeyPreferences() {
    }

    public static synchronized void save(@NonNull Context context, @NonNull String apiKey) {
        String normalizedKey = apiKey.trim();
        if (normalizedKey.isEmpty()) {
            clear(context);
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey());
            byte[] encrypted = cipher.doFinal(normalizedKey.getBytes(StandardCharsets.UTF_8));
            preferences(context).edit()
                    .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString(KEY_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .apply();
        } catch (Exception e) {
            throw new IllegalStateException("Não foi possível proteger a chave da API.", e);
        }
    }

    @NonNull
    public static synchronized String get(@NonNull Context context) {
        SharedPreferences preferences = preferences(context);
        String ciphertext = preferences.getString(KEY_CIPHERTEXT, "");
        String iv = preferences.getString(KEY_IV, "");
        if (ciphertext == null || ciphertext.isEmpty() || iv == null || iv.isEmpty()) {
            return "";
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateSecretKey(),
                    new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            );
            byte[] decrypted = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP));
            return new String(decrypted, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            Log.w(TAG, "Stored API key could not be decrypted; removing it", e);
            clear(context);
            return "";
        }
    }

    public static synchronized boolean hasKey(@NonNull Context context) {
        return !get(context).isEmpty();
    }

    public static synchronized void clear(@NonNull Context context) {
        preferences(context).edit().clear().apply();
    }

    @NonNull
    private static SharedPreferences preferences(@NonNull Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    @NonNull
    private static SecretKey getOrCreateSecretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        KeyStore.Entry existingEntry = keyStore.getEntry(KEYSTORE_ALIAS, null);
        if (existingEntry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) existingEntry).getSecretKey();
        }

        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
        );
        keyGenerator.init(new KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return keyGenerator.generateKey();
    }
}
