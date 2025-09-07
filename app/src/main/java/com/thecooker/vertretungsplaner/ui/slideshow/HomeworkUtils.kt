package com.thecooker.vertretungsplaner.ui.slideshow

import android.content.Context
import androidx.core.content.edit

// util class for accessing homework data and settings from other scripts
object HomeworkUtils {

    private const val PREFS_NAME = "AppPrefs"
    private const val PREFS_AUTO_DELETE = "auto_delete_completed"

    fun isAutoDeleteEnabled(context: Context): Boolean {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sharedPrefs.getBoolean(PREFS_AUTO_DELETE, true)
    }

    fun setAutoDeleteEnabled(context: Context, enabled: Boolean) {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPrefs.edit {
                putBoolean(PREFS_AUTO_DELETE, enabled)
            }
    }

}