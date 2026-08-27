package com.example.arpiagrama.operational.preferences;

import android.content.Context;
import android.content.SharedPreferences;

public final class TalkbackPreferences {

    private static final String PREFS_NAME = "talkback_prefs";
    private static final String KEY_ENABLED = "talkback_enabled";
    private static final String KEY_SPEECH_RATE = "talkback_speech_rate";
    private static final float SPEECH_RATE_MIN = 0.8f;
    private static final float SPEECH_RATE_MAX = 2f;
    private static final float SPEECH_RATE_DEFAULT = 1.0f;

    private TalkbackPreferences() {
    }

    public static boolean isEnabled(Context context) {
        return getPreferences(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        getPreferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static float getSpeechRate(Context context) {
        return getPreferences(context).getFloat(KEY_SPEECH_RATE, SPEECH_RATE_DEFAULT);
    }

    public static void setSpeechRate(Context context, float speechRate) {
        float clampedRate = clampSpeechRate(speechRate);
        getPreferences(context).edit().putFloat(KEY_SPEECH_RATE, clampedRate).apply();
    }

    public static float clampSpeechRate(float speechRate) {
        return Math.max(SPEECH_RATE_MIN, Math.min(speechRate, SPEECH_RATE_MAX));
    }

    public static float getMinSpeechRate() {
        return SPEECH_RATE_MIN;
    }

    public static float getMaxSpeechRate() {
        return SPEECH_RATE_MAX;
    }

    private static SharedPreferences getPreferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
