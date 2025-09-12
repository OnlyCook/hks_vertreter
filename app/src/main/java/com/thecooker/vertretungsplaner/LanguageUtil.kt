package com.thecooker.vertretungsplaner

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import java.util.*

object LanguageUtil {

    fun setLanguage(context: Context, languageCode: String) {
        val locale = Locale(languageCode)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)

        context.resources.updateConfiguration(config, context.resources.displayMetrics)

        if (context !is Application) {
            val appContext = context.applicationContext
            appContext.resources.updateConfiguration(config, appContext.resources.displayMetrics)
        }
    }

    fun getDeviceLanguage(): String {
        val deviceLanguage = Locale.getDefault().language
        return when (deviceLanguage) {
            "de" -> "de"
            "en" -> "en"
            else -> "de" // fallback to german
        }
    }

    fun getSavedLanguage(context: Context): String {
        val sharedPreferences = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val autoDetect = sharedPreferences.getBoolean("language_auto_detect", true)

        return if (autoDetect) {
            getDeviceLanguage()
        } else {
            sharedPreferences.getString("selected_language", "de") ?: "de"
        }
    }
}