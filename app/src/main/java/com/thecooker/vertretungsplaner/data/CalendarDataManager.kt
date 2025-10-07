package com.thecooker.vertretungsplaner.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.thecooker.vertretungsplaner.ui.exams.ExamFragment
import java.text.SimpleDateFormat
import java.util.*
import androidx.core.content.edit
import java.text.ParseException

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

    fun importMoodleCalendarData(calendarContent: String) {
        try {
            if (calendarContent.isBlank()) {
                Log.w(TAG, "Empty calendar content, nothing to import")
                return
            }

            val events = parseMoodleICalendarContent(calendarContent)

            if (events.isEmpty()) {
                Log.w(TAG, "No valid events found in calendar content")
                return
            }

            val uids = events.mapNotNull { event ->
                event.uid.takeIf { it.isNotEmpty() }
            }
            Log.d(TAG, "Parsed UIDs from calendar: ${uids.joinToString(", ")}")
            Log.d(TAG, "Total events with UIDs: ${uids.size} / ${events.size}")

            val existingData = getAllCalendarDays().filter { !it.specialNote.contains("Moodle:") }
            calendarData.clear()

            for (entry in existingData) {
                calendarData[entry.getDateKey()] = entry
            }

            var importedCount = 0
            for (event in events) {
                try {
                    val dayOfWeek = SimpleDateFormat("EEEE", Locale.GERMANY).format(event.date)
                    val month = SimpleDateFormat("MM", Locale.GERMANY).format(event.date).toInt()
                    val year = SimpleDateFormat("yyyy", Locale.GERMANY).format(event.date).toInt()

                    val dayInfo = CalendarDayInfo(
                        date = event.date,
                        dayOfWeek = dayOfWeek,
                        month = month,
                        year = year,
                        content = "${event.category}\n\n${event.description}",
                        exams = emptyList(),
                        isSpecialDay = true,
                        specialNote = "Moodle: ${event.summary}${if (event.uid.isNotEmpty()) " (ID: ${event.uid})" else ""}"
                    )

                    val dateKey = dayInfo.getDateKey()
                    val existing = calendarData[dateKey]

                    if (existing != null) {
                        val mergedContent = if (existing.content.isNotEmpty()) {
                            "${existing.content}\n\n---\n\n${dayInfo.content}"
                        } else {
                            dayInfo.content
                        }

                        calendarData[dateKey] = existing.copy(
                            content = mergedContent,
                            isSpecialDay = true,
                            specialNote = if (existing.specialNote.isNotEmpty()) {
                                "${existing.specialNote} | ${dayInfo.specialNote}"
                            } else {
                                dayInfo.specialNote
                            }
                        )
                    } else {
                        calendarData[dateKey] = dayInfo
                    }
                    importedCount++
                } catch (e: Exception) {
                    Log.w(TAG, "Error creating calendar entry for event: ${event.summary}", e)
                }
            }

            saveCalendarData()
            Log.d(TAG, "Successfully imported $importedCount / ${events.size} Moodle calendar events")

        } catch (e: Exception) {
            Log.e(TAG, "Error importing Moodle calendar data", e)
        }
    }


    private data class MoodleCalendarEvent(
        val date: Date,
        val summary: String,
        val description: String,
        val category: String,
        val uid: String = ""
    )

    private fun parseMoodleICalendarContent(content: String): List<MoodleCalendarEvent> {
        val events = mutableListOf<MoodleCalendarEvent>()

        if (content.isBlank()) {
            Log.w(TAG, "Empty calendar content received")
            return events
        }

        val lines = content.split("\n")
        var currentEvent: MutableMap<String, String>? = null
        var currentKey = ""

        try {
            for (line in lines) {
                val trimmedLine = line.trim()

                when {
                    trimmedLine == "BEGIN:VEVENT" -> {
                        currentEvent = mutableMapOf()
                    }
                    trimmedLine == "END:VEVENT" -> {
                        currentEvent?.let { event ->
                            try {
                                if (!event.containsKey("DTEND") && !event.containsKey("DTSTART")) {
                                    Log.w(TAG, "Event missing both DTEND and DTSTART, skipping")
                                    return@let
                                }

                                if (!event.containsKey("SUMMARY") || event["SUMMARY"]?.isBlank() == true) {
                                    Log.w(TAG, "Event missing or empty SUMMARY, skipping")
                                    return@let
                                }

                                val dtend = event["DTEND"] ?: event["DTSTART"] ?: return@let
                                val summary = event["SUMMARY"]?.takeIf { it.isNotBlank() } ?: return@let
                                val description = event["DESCRIPTION"] ?: ""
                                val category = event["CATEGORIES"] ?: ""
                                val uid = event["UID"] ?: ""

                                val date = parseMoodleICalendarDate(dtend)
                                if (date != null) {
                                    val cleanedUid = uid.removePrefix("UID:").substringBefore("@")

                                    events.add(MoodleCalendarEvent(
                                        date = date,
                                        summary = cleanMoodleICalendarText(summary),
                                        description = cleanMoodleICalendarText(description),
                                        category = cleanMoodleICalendarText(category),
                                        uid = cleanedUid
                                    ))
                                } else {
                                    Log.w(TAG, "Failed to parse date for event: $summary")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Error parsing Moodle calendar event: ${e.message}", e)
                            }
                        }
                        currentEvent = null
                    }
                    currentEvent != null && trimmedLine.isNotEmpty() -> {
                        try {
                            if (trimmedLine.startsWith(" ") || trimmedLine.startsWith("\t")) {
                                if (currentKey.isNotEmpty() && currentEvent.containsKey(currentKey)) {
                                    currentEvent[currentKey] = currentEvent[currentKey] + trimmedLine.trim()
                                }
                            } else {
                                val colonIndex = trimmedLine.indexOf(':')
                                if (colonIndex > 0) {
                                    val key = trimmedLine.substring(0, colonIndex).split(';')[0]
                                    val value = trimmedLine.substring(colonIndex + 1)
                                    currentEvent[key] = value
                                    currentKey = key
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error parsing calendar line: $trimmedLine", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Critical error parsing Moodle calendar content", e)
        }

        Log.d(TAG, "Successfully parsed ${events.size} valid events")
        return events
    }

    private fun parseMoodleICalendarDate(dateString: String): Date? {
        return try {
            // format: 20250923T053000Z
            val cleanDateString = dateString.replace("Z", "").replace("T", "")
            val format = SimpleDateFormat("yyyyMMddHHmmss", Locale.GERMANY)
            format.parse(cleanDateString)
        } catch (_: ParseException) {
            try {
                // try alt format: 20250923
                val format = SimpleDateFormat("yyyyMMdd", Locale.GERMANY)
                format.parse(dateString.substring(0, 8))
            } catch (e2: ParseException) {
                Log.e(TAG, "Error parsing Moodle date: $dateString", e2)
                null
            }
        }
    }

    private fun cleanMoodleICalendarText(text: String): String {
        return text.replace("\\n", "\n")
            .replace("\\,", ",")
            .replace("\\;", ";")
            .replace("\\\\", "\\")
            .replace("\\t", "\t")
            .trim()
    }

    fun getMoodleCalendarEntries(): List<CalendarDayInfo> {
        return calendarData.values.filter { it.specialNote.contains("Moodle:") }
            .sortedBy { it.date }
    }

    fun clearMoodleCalendarData() {
        val nonMoodleEntries = calendarData.values.filter { !it.specialNote.contains("Moodle:") }
        calendarData.clear()

        for (entry in nonMoodleEntries) {
            calendarData[entry.getDateKey()] = entry
        }

        saveCalendarData()
        Log.d(TAG, "Moodle calendar data cleared, preserved ${nonMoodleEntries.size} manual entries")
    }

    fun getMoodleEventUIDs(): List<String> {
        return calendarData.values
            .filter { it.specialNote.contains("Moodle:") }
            .mapNotNull { entry ->
                val uidPattern = """\(UID: ([^)]+)\)""".toRegex()
                uidPattern.find(entry.specialNote)?.groupValues?.get(1)
            }
            .distinct()
            .sorted()
    }

    fun getMoodleEventUIDDetails(): Map<String, List<String>> {
        val uidToSummaries = mutableMapOf<String, MutableList<String>>()

        calendarData.values
            .filter { it.specialNote.contains("Moodle:") }
            .forEach { entry ->
                val uidPattern = """\(UID: ([^)]+)\)""".toRegex()
                val summaryPattern = """Moodle: ([^(]+)""".toRegex()

                val uid = uidPattern.find(entry.specialNote)?.groupValues?.get(1)
                val summary = summaryPattern.find(entry.specialNote)?.groupValues?.get(1)?.trim()

                if (uid != null && summary != null) {
                    uidToSummaries.getOrPut(uid) { mutableListOf() }.add(summary)
                }
            }

        return uidToSummaries.mapValues { it.value.distinct() }
    }
}