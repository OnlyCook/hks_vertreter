package com.thecooker.vertretungsplaner.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.thecooker.vertretungsplaner.ui.exams.ExamFragment
import java.text.SimpleDateFormat
import java.util.*
import androidx.core.content.edit

// singleton class to manage calendar data
class CalendarDataManager private constructor(context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: CalendarDataManager? = null
        private const val TAG = "CalendarDataManager"

        fun getInstance(context: Context): CalendarDataManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CalendarDataManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val sharedPreferences = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
    private var calendarData = mutableMapOf<String, CalendarDayInfo>()

    data class CalendarDayInfo(
        val date: Date,
        val dayOfWeek: String,
        val month: Int, // 1-12
        val year: Int,
        val content: String,
        val exams: List<ExamFragment.ExamEntry>,
        val isSpecialDay: Boolean,
        val specialNote: String = ""
    ) {
        fun getDateKey(): String {
            val format = SimpleDateFormat("yyyyMMdd", Locale.GERMANY)
            return format.format(date)
        }

        fun getDisplayDate(): String {
            val format = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)
            return format.format(date)
        }
    }

    init {
        loadCalendarData()
    }

    fun clearCalendarData() {
        calendarData.clear()
        saveCalendarData()
        Log.d(TAG, "Calendar data cleared")
    }

    fun addCalendarDay(dayInfo: CalendarDayInfo) {
        calendarData[dayInfo.getDateKey()] = dayInfo
        Log.d(TAG, "Added calendar day: ${dayInfo.getDisplayDate()}")
    }

    fun getCalendarInfoForDate(date: Date): CalendarDayInfo? {
        val format = SimpleDateFormat("yyyyMMdd", Locale.GERMANY)
        val dateKey = format.format(date)
        return calendarData[dateKey]
    }

    fun getAllCalendarDays(): List<CalendarDayInfo> {
        return calendarData.values.sortedBy { it.date }
    }

    fun saveCalendarData() {
        try {
            val json = Gson().toJson(calendarData)
            sharedPreferences.edit {
                putString("calendar_data", json)
            }
            Log.d(TAG, "Calendar data saved successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving calendar data", e)
        }
    }

    fun loadCalendarData() {
        try {
            val json = sharedPreferences.getString("calendar_data", "{}")
            val type = object : TypeToken<MutableMap<String, CalendarDayInfo>>() {}.type
            val loadedData: MutableMap<String, CalendarDayInfo> = Gson().fromJson(json, type) ?: mutableMapOf()
            calendarData.clear()
            calendarData.putAll(loadedData)
            Log.d(TAG, "Calendar data loaded: ${calendarData.size} entries")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading calendar data", e)
            calendarData.clear()
        }
    }

    fun importCalendarData(content: String) {
        try {
            val lines = content.split("\n").filter { line ->
                !line.startsWith("#") && line.trim().isNotEmpty() && line.contains("|")
            }

            val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)
            var importCount = 0

            for (line in lines) {
                val parts = line.split("|")
                if (parts.size >= 7) {
                    try {
                        val date = dateFormat.parse(parts[0].trim()) ?: continue
                        val dayOfWeek = parts[1].trim()
                        val month = parts[2].trim().toInt()
                        val year = parts[3].trim().toInt()
                        val contentStr = parts[4].trim().replace("\\n", "\n").replace("\\|", "|")
                        val isSpecialDay = parts[5].trim() == "1"
                        val specialNote = parts[6].trim().replace("\\n", "\n").replace("\\|", "|")

                        val dayInfo = CalendarDayInfo(
                            date = date,
                            dayOfWeek = dayOfWeek,
                            month = month,
                            year = year,
                            content = contentStr,
                            exams = emptyList(), // exams added separately
                            isSpecialDay = isSpecialDay,
                            specialNote = specialNote
                        )

                        addCalendarDay(dayInfo)
                        importCount++
                    } catch (e: Exception) {
                        Log.w(TAG, "Error parsing calendar line: $line", e)
                    }
                }
            }

            saveCalendarData()
            Log.d(TAG, "Imported $importCount calendar entries")
        } catch (e: Exception) {
            Log.e(TAG, "Error importing calendar data", e)
        }
    }

    fun updateCalendarDay(dayInfo: CalendarDayInfo) {
        calendarData[dayInfo.getDateKey()] = dayInfo
        Log.d(TAG, "Updated calendar day: ${dayInfo.getDisplayDate()}")
    }

    fun removeCalendarDay(date: Date) {
        val dateKey = SimpleDateFormat("yyyyMMdd", Locale.GERMANY).format(date)
        calendarData.remove(dateKey)
        Log.d(TAG, "Removed calendar day: ${SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(date)}")
    }
}