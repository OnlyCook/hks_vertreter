package com.thecooker.vertretungsplaner

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

open class BaseActivity : AppCompatActivity() {

    protected lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        val savedLanguage = LanguageUtil.getSavedLanguage(this)
        LanguageUtil.setLanguage(this, savedLanguage)

        sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        applyOrientationSetting()

        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        applyOrientationSetting()
    }

    private fun applyOrientationSetting() {
        val landscapeEnabled = sharedPreferences.getBoolean("landscape_mode_enabled", true)
        val targetOrientation = if (landscapeEnabled) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        if (requestedOrientation != targetOrientation) {
            requestedOrientation = targetOrientation
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val savedLanguage = LanguageUtil.getSavedLanguage(newBase)
        val context = updateBaseContextLocale(newBase, savedLanguage)
        super.attachBaseContext(context)
    }

    private fun updateBaseContextLocale(context: Context, languageCode: String): Context {
        val locale = Locale(languageCode)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)

        return context.createConfigurationContext(config)
    }
}