package com.thecooker.vertretungsplaner.data

import java.text.SimpleDateFormat
import java.util.*
import com.thecooker.vertretungsplaner.ui.exams.ExamFragment

object ExamManager {
    private val examList = mutableListOf<ExamFragment.ExamEntry>()

    fun setExams(exams: List<ExamFragment.ExamEntry>) {
        examList.clear()
        examList.addAll(exams)
    }

    private fun parseDateFromString(dateStr: String): Date? {
        return try {
            val sdf = SimpleDateFormat("yyyyMMdd", Locale.GERMANY)
            sdf.parse(dateStr)
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeDate(date: Date): Date {
        val cal = Calendar.getInstance()
        cal.time = date
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.time
    }

    fun getExamsForDate(dateStr: String): List<ExamFragment.ExamEntry> {
        val targetDate = parseDateFromString(dateStr)?.let { normalizeDate(it) } ?: return emptyList()
        return examList.filter { normalizeDate(it.date) == targetDate }
    }
}
