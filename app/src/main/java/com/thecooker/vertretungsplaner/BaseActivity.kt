package com.thecooker.vertretungsplaner

import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

open class BaseActivity : AppCompatActivity() {

    protected lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        applyOrientationSetting()
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
}