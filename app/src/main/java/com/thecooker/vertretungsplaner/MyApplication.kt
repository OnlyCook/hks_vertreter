package com.thecooker.vertretungsplaner

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        applyLanguageSettings()
        applyGlobalTheme()
    }

    private fun applyLanguageSettings() {
        val savedLanguage = LanguageUtil.getSavedLanguage(this)
        LanguageUtil.setLanguage(this, savedLanguage)
    }

    private fun applyGlobalTheme() {
        val sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val followSystemTheme = sharedPreferences.getBoolean("follow_system_theme", true)

        if (followSystemTheme) {
            val hasManualSetting = sharedPreferences.contains("dark_mode_enabled")
            if (hasManualSetting) {
                sharedPreferences.edit { remove("dark_mode_enabled") }
            }

            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        } else {
            val darkModeEnabled = sharedPreferences.getBoolean("dark_mode_enabled", false)
            AppCompatDelegate.setDefaultNightMode(
                if (darkModeEnabled) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
    }
}