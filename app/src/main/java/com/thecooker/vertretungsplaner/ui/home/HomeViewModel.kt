package com.thecooker.vertretungsplaner.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class HomeViewModel : ViewModel() {

    private val _text = MutableLiveData<String>().apply {
        value = "Das ist der Vertretungsplan"
    }
    val text: LiveData<String> = _text
}