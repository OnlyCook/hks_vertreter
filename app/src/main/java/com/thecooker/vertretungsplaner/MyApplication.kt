package com.thecooker.vertretungsplaner

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        applyGlobalTheme()
    }

    private fun applyGlobalTheme() {
        val sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val followSystemTheme = sharedPreferences.getBoolean("follow_system_theme", true)

        if (followSystemTheme) {
            val hasManualSetting = sharedPreferences.contains("dark_mode_enabled")
            if (hasManualSetting) {
                sharedPreferences.edit().remove("dark_mode_enabled").apply()
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