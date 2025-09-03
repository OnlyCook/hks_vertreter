package com.thecooker.vertretungsplaner.ui.grades

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class GradesViewModel : ViewModel() {

    private val _text = MutableLiveData<String>().apply {
        value = "Das sind die Noten"
    }
    val text: LiveData<String> = _text
}