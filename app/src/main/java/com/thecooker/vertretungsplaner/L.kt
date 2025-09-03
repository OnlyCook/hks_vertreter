package com.thecooker.vertretungsplaner

import android.util.Log

object L {

    // --- DEBUG ---
    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.d(tag, msg)
    }
    fun d(tag: String, msg: String, tr: Throwable) {
        if (BuildConfig.DEBUG) Log.d(tag, msg, tr)
    }

    // --- INFO ---
    fun i(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.i(tag, msg)
    }
    fun i(tag: String, msg: String, tr: Throwable) {
        if (BuildConfig.DEBUG) Log.i(tag, msg, tr)
    }

    // --- WARN ---
    fun w(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.w(tag, msg)
    }
    fun w(tag: String, msg: String, tr: Throwable) {
        if (BuildConfig.DEBUG) Log.w(tag, msg, tr)
    }

    // --- ERROR ---
    fun e(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.e(tag, msg)
    }
    fun e(tag: String, msg: String, tr: Throwable) {
        if (BuildConfig.DEBUG) Log.e(tag, msg, tr)
    }

    // --- VERBOSE ---
    fun v(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.v(tag, msg)
    }
    fun v(tag: String, msg: String, tr: Throwable) {
        if (BuildConfig.DEBUG) Log.v(tag, msg, tr)
    }
}