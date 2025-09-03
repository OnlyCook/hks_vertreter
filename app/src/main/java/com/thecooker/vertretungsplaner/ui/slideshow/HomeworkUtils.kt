package com.thecooker.vertretungsplaner.ui.slideshow

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// util class for accessing homework data and settings from other scripts
object HomeworkUtils {

    private const val PREFS_NAME = "AppPrefs"
    private const val PREFS_HOMEWORK_LIST = "homework_list"
    private const val PREFS_AUTO_DELETE = "auto_delete_completed"

    fun getHomeworkList(context: Context): List<SlideshowFragment.HomeworkEntry> {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = sharedPrefs.getString(PREFS_HOMEWORK_LIST, "[]")
        val type = object : TypeToken<List<SlideshowFragment.HomeworkEntry>>() {}.type
        return Gson().fromJson(json, type) ?: emptyList()
    }

    fun getUncompletedHomework(context: Context): List<SlideshowFragment.HomeworkEntry> {
        return getHomeworkList(context).filter { !it.isCompleted }
    }

    fun getUncompletedHomeworkCount(context: Context): Int {
        return getUncompletedHomework(context).size
    }

    fun isAutoDeleteEnabled(context: Context): Boolean {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sharedPrefs.getBoolean(PREFS_AUTO_DELETE, true)
    }

    fun setAutoDeleteEnabled(context: Context, enabled: Boolean) {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPrefs.edit()
            .putBoolean(PREFS_AUTO_DELETE, enabled)
            .apply()
    }

    fun saveHomeworkList(context: Context, homeworkList: List<SlideshowFragment.HomeworkEntry>) {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = Gson().toJson(homeworkList)
        sharedPrefs.edit()
            .putString(PREFS_HOMEWORK_LIST, json)
            .apply()
    }
}