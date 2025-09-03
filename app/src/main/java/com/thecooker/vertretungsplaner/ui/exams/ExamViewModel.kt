package com.thecooker.vertretungsplaner.ui.exams

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class ExamViewModel : ViewModel() {

    private val _text = MutableLiveData<String>().apply {
        value = "Klausuren"
    }
    val text: LiveData<String> = _text

    private val _examCount = MutableLiveData<Pair<Int, Int>>().apply {
        value = Pair(0, 0) // upcoming, total
    }
    val examCount: LiveData<Pair<Int, Int>> = _examCount

    private val _hasScannedSchedule = MutableLiveData<Boolean>().apply {
        value = false
    }
    val hasScannedSchedule: LiveData<Boolean> = _hasScannedSchedule

    fun updateExamCount(upcoming: Int, total: Int) {
        _examCount.value = Pair(upcoming, total)
    }

    fun setScannedSchedule(hasScanned: Boolean) {
        _hasScannedSchedule.value = hasScanned
    }
}