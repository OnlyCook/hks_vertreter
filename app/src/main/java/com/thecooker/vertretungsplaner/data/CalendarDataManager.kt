package com.thecooker.vertretungsplaner.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.thecooker.vertretungsplaner.ui.exams.ExamFragment
import java.text.SimpleDateFormat
import java.util.*

// singleton class to manage calendar data
class CalendarDataManager private constructor(private val context: Context) {

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

        fun getDisplayDateWithWeekday(): String {
            val format = SimpleDateFormat("EEEE, dd.MM.yyyy", Locale.GERMANY)
            return format.format(date)
        }

        fun getShortWeekday(): String {
            val format = SimpleDateFormat("EEE", Locale.GERMANY)
            return format.format(date)
        }

        fun hasExams(): Boolean = exams.isNotEmpty()

        fun hasSpecialEvent(): Boolean = isSpecialDay

        fun hasAnyEvent(): Boolean = hasExams() || hasSpecialEvent()
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

    fun updateExamsForDay(date: Date, exams: List<ExamFragment.ExamEntry>) {
        val dateKey = SimpleDateFormat("yyyyMMdd", Locale.GERMANY).format(date)
        val existingDay = calendarData[dateKey]

        if (existingDay != null) {
            val updatedDay = existingDay.copy(exams = exams)
            calendarData[dateKey] = updatedDay
            saveCalendarData()
            Log.d(TAG, "Updated exams for ${existingDay.getDisplayDate()}: ${exams.size} exams")
        }
    }

    fun getCalendarInfoForDate(date: Date): CalendarDayInfo? {
        val format = SimpleDateFormat("yyyyMMdd", Locale.GERMANY)
        val dateKey = format.format(date)
        return calendarData[dateKey]
    }

    fun getCalendarInfoForDateString(dateString: String): CalendarDayInfo? {
        try {
            val inputFormat = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)
            val date = inputFormat.parse(dateString) ?: return null
            return getCalendarInfoForDate(date)
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing date string: $dateString", e)
            return null
        }
    }

    fun getAllCalendarDays(): List<CalendarDayInfo> {
        return calendarData.values.sortedBy { it.date }
    }

    fun getCalendarDataForWeek(targetDate: Date): List<CalendarDayInfo> {
        val calendar = Calendar.getInstance(Locale.GERMANY)
        calendar.time = targetDate

        // get monday of this week
        calendar.firstDayOfWeek = Calendar.MONDAY
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        val weekStart = calendar.time

        // get sunday of this week
        calendar.add(Calendar.DAY_OF_WEEK, 6)
        val weekEnd = calendar.time

        return getCalendarDataForRange(weekStart, weekEnd)
    }

    fun getCurrentWeekData(): List<CalendarDayInfo> {
        return getCalendarDataForWeek(Date())
    }

    fun getCalendarDataForRange(startDate: Date, endDate: Date): List<CalendarDayInfo> {
        return calendarData.values.filter { dayInfo ->
            val dayStart = Calendar.getInstance().apply {
                time = dayInfo.date
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.time

            val rangeStart = Calendar.getInstance().apply {
                time = startDate
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.time

            val rangeEnd = Calendar.getInstance().apply {
                time = endDate
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }.time

            dayStart >= rangeStart && dayStart <= rangeEnd
        }.sortedBy { it.date }
    }

    fun getExamsForDateRange(startDate: Date, endDate: Date): List<CalendarDayInfo> {
        return getCalendarDataForRange(startDate, endDate).filter { it.hasExams() }
    }

    fun getSpecialDaysForRange(startDate: Date, endDate: Date): List<CalendarDayInfo> {
        return getCalendarDataForRange(startDate, endDate).filter { it.hasSpecialEvent() }
    }

    fun getUpcomingExams(daysAhead: Int = 30): List<CalendarDayInfo> {
        val today = Date()
        val calendar = Calendar.getInstance()
        calendar.time = today
        calendar.add(Calendar.DAY_OF_YEAR, daysAhead)
        val futureDate = calendar.time

        return getExamsForDateRange(today, futureDate)
    }

    fun getTodaysExams(): CalendarDayInfo? {
        val today = Date()
        val dayInfo = getCalendarInfoForDate(today)
        return if (dayInfo?.hasExams() == true) dayInfo else null
    }

    fun hasEventsOnDate(date: Date): Boolean {
        return getCalendarInfoForDate(date)?.hasAnyEvent() ?: false
    }

    fun saveCalendarData() {
        try {
            val json = Gson().toJson(calendarData)
            sharedPreferences.edit()
                .putString("calendar_data", json)
                .apply()
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

    fun exportCalendarData(): String {
        val sb = StringBuilder()
        sb.appendLine("# Calendar Data Export")
        sb.appendLine("# Format: Date|DayOfWeek|Month|Year|Content|IsSpecialDay|SpecialNote")

        for (dayInfo in getAllCalendarDays()) {
            val dateStr = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(dayInfo.date)
            val contentStr = dayInfo.content.replace("\n", "\\n").replace("|", "\\|")
            val specialNoteStr = dayInfo.specialNote.replace("\n", "\\n").replace("|", "\\|")
            val isSpecialStr = if (dayInfo.isSpecialDay) "1" else "0"

            sb.appendLine("$dateStr|${dayInfo.dayOfWeek}|${dayInfo.month}|${dayInfo.year}|$contentStr|$isSpecialStr|$specialNoteStr")
        }

        return sb.toString()
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

    fun getCalendarStats(): Map<String, Int> {
        val stats = mutableMapOf<String, Int>()
        val allDays = getAllCalendarDays()

        stats["total_days"] = allDays.size
        stats["exam_days"] = allDays.count { it.hasExams() }
        stats["special_days"] = allDays.count { it.hasSpecialEvent() }
        stats["total_exams"] = allDays.sumOf { it.exams.size }

        return stats
    }
}