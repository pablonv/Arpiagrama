package com.example.arpiagrama;

import com.example.arpiagrama.operational.preferences.ThemePreferences;

import android.app.Application;

public class ArpiagramaApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        ThemePreferences.applySavedTheme(this);
    }
}
