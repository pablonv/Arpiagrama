package com.example.arpiagrama.operational.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatDelegate;

public final class ThemePreferences {
    private static final String PREFS_NAME = "theme_prefs";
    private static final String KEY_DARK_THEME = "dark_theme_enabled";
    private static final String KEY_FONT_SCALE = "font_scale";
    private static final float FONT_SCALE_MIN = 0.9f;
    private static final float FONT_SCALE_MAX = 1.2f;
    private static final float FONT_SCALE_DEFAULT = 1.0f;

    private ThemePreferences() {
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static void applySavedTheme(Context context) {
        boolean isDarkThemeEnabled = isDarkThemeEnabled(context);
        AppCompatDelegate.setDefaultNightMode(
                isDarkThemeEnabled
                        ? AppCompatDelegate.MODE_NIGHT_YES
                        : AppCompatDelegate.MODE_NIGHT_NO
        );
    }

    public static boolean isDarkThemeEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_DARK_THEME, false);
    }

    public static void setDarkThemeEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_DARK_THEME, enabled).apply();
        AppCompatDelegate.setDefaultNightMode(
                enabled
                        ? AppCompatDelegate.MODE_NIGHT_YES
                        : AppCompatDelegate.MODE_NIGHT_NO
        );
    }

    public static Context applyFontScale(Context context) {
        // Ajusta apenas o contexto da Activity, preservando a configuração global do sistema.
        float scale = getFontScale(context);
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.fontScale = scale;
        return context.createConfigurationContext(configuration);
    }

    public static float getFontScale(Context context) {
        return getPrefs(context).getFloat(KEY_FONT_SCALE, FONT_SCALE_DEFAULT);
    }

    public static void setFontScale(Context context, float scale) {
        float clampedScale = clampFontScale(scale);
        getPrefs(context).edit().putFloat(KEY_FONT_SCALE, clampedScale).apply();
    }

    public static float clampFontScale(float scale) {
        return Math.max(FONT_SCALE_MIN, Math.min(scale, FONT_SCALE_MAX));
    }

    public static float getMinFontScale() {
        return FONT_SCALE_MIN;
    }

    public static float getMaxFontScale() {
        return FONT_SCALE_MAX;
    }
}
