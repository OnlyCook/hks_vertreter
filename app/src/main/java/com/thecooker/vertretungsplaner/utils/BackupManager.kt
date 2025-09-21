package com.thecooker.vertretungsplaner.utils

import android.content.Context
import android.content.SharedPreferences
import com.thecooker.vertretungsplaner.L
import java.text.SimpleDateFormat
import java.util.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.thecooker.vertretungsplaner.data.CalendarDataManager
import java.text.DecimalFormat
import androidx.core.content.edit
import com.thecooker.vertretungsplaner.R
import com.thecooker.vertretungsplaner.data.ExamManager

class BackupManager(private val context: Context) {

    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

    private var isFullBackupRestore = false

    private var progressCallback: BackupProgressCallback? = null

    private val backupSections = listOf(
        BackupSection("TIMETABLE_DATA", "Stundenplan-Daten"),
        BackupSection("CALENDAR_DATA", "Kalender-Daten"),
        BackupSection("HOMEWORK_DATA", "Hausaufgaben"),
        BackupSection("EXAM_DATA", "Klausuren"),
        BackupSection("GRADE_DATA", "Noten"),
        BackupSection("APP_SETTINGS", "App-Einstellungen")
    )

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    companion object {
        private const val TAG = "BackupManager"
        private const val BACKUP_VERSION = "1.0"
        const val HKS_BACKUP_FILE_EXTENSION = ".hks"
    }

    fun createFullBackup(callback: BackupProgressCallback? = null): String {
        L.d(TAG, "Creating full backup...")
        this.progressCallback = callback

        val content = StringBuilder()
        val sections = backupSections.toMutableList()

        // header information
        content.appendLine("# Heinrich-Kleyer-Schule App Backup")
        content.appendLine("# Version: $BACKUP_VERSION")
        content.appendLine("# Created: ${SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMAN).format(Date())}")
        content.appendLine()

        sections.forEach { section ->
            try {
                section.status = SectionStatus.IN_PROGRESS
                notifySectionStarted(section)

                val sectionContent = when (section.name) {
                    "TIMETABLE_DATA" -> {
                        content.appendLine("[TIMETABLE_DATA]")
                        val data = exportTimetableData()
                        content.appendLine(data)
                        content.appendLine("[/TIMETABLE_DATA]")
                        content.appendLine()
                        data
                    }
                    "CALENDAR_DATA" -> {
                        content.appendLine("[CALENDAR_DATA]")
                        val data = exportCalendarData()
                        content.appendLine(data)
                        content.appendLine("[/CALENDAR_DATA]")
                        content.appendLine()
                        data
                    }
                    "HOMEWORK_DATA" -> {
                        content.appendLine("[HOMEWORK_DATA]")
                        val data = exportHomeworkData()
                        content.appendLine(data)
                        content.appendLine("[/HOMEWORK_DATA]")
                        content.appendLine()
                        data
                    }
                    "EXAM_DATA" -> {
                        content.appendLine("[EXAM_DATA]")
                        val data = exportExamData()
                        content.appendLine(data)
                        content.appendLine("[/EXAM_DATA]")
                        content.appendLine()
                        data
                    }
                    "GRADE_DATA" -> {
                        content.appendLine("[GRADE_DATA]")
                        val data = exportGradeData()
                        content.appendLine(data)
                        content.appendLine("[/GRADE_DATA]")
                        content.appendLine()
                        data
                    }
                    "APP_SETTINGS" -> {
                        content.appendLine("[APP_SETTINGS]")
                        val data = exportAppSettings()
                        content.appendLine(data)
                        content.appendLine("[/APP_SETTINGS]")
                        data
                    }
                    else -> ""
                }

                val hasData = sectionContent.trim().isNotEmpty() &&
                        !sectionContent.trim().startsWith("# Error") &&
                        sectionContent.split("\n").size > 3

                section.status = if (hasData) SectionStatus.SUCCESS else SectionStatus.EMPTY
                notifySectionCompleted(section)

            } catch (e: Exception) {
                L.e(TAG, "Error creating backup for section ${section.name}", e)
                section.status = SectionStatus.FAILED
                section.errorMessage = e.message
                notifySectionCompleted(section)
            }
        }

        val success = sections.none { it.status == SectionStatus.FAILED }
        notifyBackupCompleted(success, sections)

        L.d(TAG, "Full backup created successfully")
        return content.toString()
    }

    fun restoreFromBackup(backupContent: String, callback: BackupProgressCallback? = null): RestoreResult {
        L.d(TAG, "Starting backup restore...")
        this.progressCallback = callback

        isFullBackupRestore = true
        val sections = backupSections.toMutableList()

        try {
            val parsedSections = parseBackupSections(backupContent)
            var successCount = 0
            val errors = mutableListOf<String>()

            sections.forEach { section ->
                try {
                    section.status = SectionStatus.IN_PROGRESS
                    notifySectionStarted(section)

                    val sectionContent = parsedSections[section.name]
                    if (sectionContent == null) {
                        section.status = SectionStatus.EMPTY
                        section.errorMessage = context.getString(R.string.backup_no_data_in_backup_found)
                        notifySectionCompleted(section)
                        return@forEach
                    }

                    when (section.name) {
                        "TIMETABLE_DATA" -> {
                            importTimetableData(sectionContent)
                            successCount++
                        }
                        "CALENDAR_DATA" -> {
                            importCalendarData(sectionContent)
                            successCount++
                        }
                        "HOMEWORK_DATA" -> {
                            importHomeworkData(sectionContent)
                            successCount++
                        }
                        "EXAM_DATA" -> {
                            importExamDataFromFullBackup(sectionContent)
                            successCount++
                        }
                        "GRADE_DATA" -> {
                            importGradeData(sectionContent)
                            successCount++
                        }
                        "APP_SETTINGS" -> {
                            importAppSettings(sectionContent)
                            successCount++
                        }
                    }

                    section.status = SectionStatus.SUCCESS
                    notifySectionCompleted(section)

                } catch (e: Exception) {
                    L.e(TAG, "Error restoring section ${section.name}", e)
                    section.status = SectionStatus.FAILED
                    section.errorMessage = e.message
                    errors.add("${section.displayName}: ${e.message}")
                    notifySectionCompleted(section)
                }
            }

            val success = sections.none { it.status == SectionStatus.FAILED }

            val result = RestoreResult(
                success = success,
                restoredSections = successCount,
                totalSections = 6,
                errors = errors
            )

            notifyBackupCompleted(success, sections)
            return result

        } catch (e: Exception) {
            L.e(TAG, "Error parsing backup", e)
            sections.forEach { it.status = SectionStatus.FAILED; it.errorMessage = "Backup parsing failed: ${e.message}" }
            notifyBackupCompleted(false, sections)
            return RestoreResult(
                success = false,
                restoredSections = 0,
                totalSections = 6,
                errors = listOf(context.getString(R.string.backup_parsing_failed, e.message ?: ""))
            )
        } finally {
            isFullBackupRestore = false
        }
    }

    private fun parseBackupSections(content: String): Map<String, String> {
        val sections = mutableMapOf<String, String>()
        val lines = content.lines()

        var currentSection: String? = null
        var currentContent = StringBuilder()

        for (line in lines) {
            when {
                line.startsWith("[") && line.endsWith("]") && !line.startsWith("[/") -> {
                    currentSection?.let { sections[it] = currentContent.toString().trim() }
                    currentSection = line.substring(1, line.length - 1)
                    currentContent = StringBuilder()
                }
                line.startsWith("[/") && line.endsWith("]") -> {
                    currentSection?.let { sections[it] = currentContent.toString().trim() }
                    currentSection = null
                    currentContent = StringBuilder()
                }
                currentSection != null && !line.startsWith("#") -> {
                    currentContent.appendLine(line)
                }
            }
        }

        return sections
    }

    fun createSelectiveBackup(enabledSections: Set<String>, callback: BackupProgressCallback? = null): String {
        L.d(TAG, "Creating selective backup for sections: $enabledSections")
        this.progressCallback = callback

        val content = StringBuilder()
        val allSections = backupSections.toMutableList()
        val sectionsToProcess = allSections.filter { enabledSections.contains(it.name) }

        // header information
        content.appendLine("# Heinrich-Kleyer-Schule App Backup")
        content.appendLine("# Version: $BACKUP_VERSION")
        content.appendLine("# Created: ${SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMAN).format(Date())}")
        content.appendLine()

        sectionsToProcess.forEach { section ->
            try {
                section.status = SectionStatus.IN_PROGRESS
                notifySectionStarted(section)

                val sectionContent = when (section.name) {
                    "TIMETABLE_DATA" -> {
                        content.appendLine("[TIMETABLE_DATA]")
                        val data = exportTimetableData()
                        content.appendLine(data)
                        content.appendLine("[/TIMETABLE_DATA]")
                        content.appendLine()
                        data
                    }
                    "CALENDAR_DATA" -> {
                        content.appendLine("[CALENDAR_DATA]")
                        val data = exportCalendarData()
                        content.appendLine(data)
                        content.appendLine("[/CALENDAR_DATA]")
                        content.appendLine()
                        data
                    }
                    "HOMEWORK_DATA" -> {
                        content.appendLine("[HOMEWORK_DATA]")
                        val data = exportHomeworkData()
                        content.appendLine(data)
                        content.appendLine("[/HOMEWORK_DATA]")
                        content.appendLine()
                        data
                    }
                    "EXAM_DATA" -> {
                        content.appendLine("[EXAM_DATA]")
                        val data = exportExamData()
                        content.appendLine(data)
                        content.appendLine("[/EXAM_DATA]")
                        content.appendLine()
                        data
                    }
                    "GRADE_DATA" -> {
                        content.appendLine("[GRADE_DATA]")
                        val data = exportGradeData()
                        content.appendLine(data)
                        content.appendLine("[/GRADE_DATA]")
                        content.appendLine()
                        data
                    }
                    "APP_SETTINGS" -> {
                        content.appendLine("[APP_SETTINGS]")
                        val data = exportAppSettings()
                        content.appendLine(data)
                        content.appendLine("[/APP_SETTINGS]")
                        data
                    }
                    else -> ""
                }

                val hasData = sectionContent.trim().isNotEmpty() &&
                        !sectionContent.trim().startsWith("# Error") &&
                        sectionContent.split("\n").size > 3

                section.status = if (hasData) SectionStatus.SUCCESS else SectionStatus.EMPTY
                notifySectionCompleted(section)

            } catch (e: Exception) {
                L.e(TAG, "Error creating backup for section ${section.name}", e)
                section.status = SectionStatus.FAILED
                section.errorMessage = e.message
                notifySectionCompleted(section)
            }
        }

        val success = sectionsToProcess.none { it.status == SectionStatus.FAILED }
        notifyBackupCompleted(success, sectionsToProcess)

        L.d(TAG, "Selective backup created successfully")
        return content.toString()
    }

    fun restoreSelectiveBackup(backupContent: String, enabledSections: Set<String>, callback: BackupProgressCallback? = null): RestoreResult {
        L.d(TAG, "Starting selective backup restore for sections: $enabledSections")
        this.progressCallback = callback

        isFullBackupRestore = true
        val allSections = backupSections.toMutableList()
        val sectionsToProcess = allSections.filter { enabledSections.contains(it.name) }

        try {
            val parsedSections = parseBackupSections(backupContent)
            var successCount = 0
            val errors = mutableListOf<String>()

            sectionsToProcess.forEach { section ->
                try {
                    section.status = SectionStatus.IN_PROGRESS
                    notifySectionStarted(section)

                    val sectionContent = parsedSections[section.name]
                    if (sectionContent == null) {
                        section.status = SectionStatus.EMPTY
                        section.errorMessage = context.getString(R.string.backup_no_data_found)
                        notifySectionCompleted(section)
                        return@forEach
                    }

                    when (section.name) {
                        "TIMETABLE_DATA" -> {
                            importTimetableData(sectionContent)
                            successCount++
                        }
                        "CALENDAR_DATA" -> {
                            importCalendarData(sectionContent)
                            successCount++
                        }
                        "HOMEWORK_DATA" -> {
                            importHomeworkData(sectionContent)
                            successCount++
                        }
                        "EXAM_DATA" -> {
                            importExamDataFromFullBackup(sectionContent)
                            successCount++
                        }
                        "GRADE_DATA" -> {
                            importGradeData(sectionContent)
                            successCount++
                        }
                        "APP_SETTINGS" -> {
                            importAppSettings(sectionContent)
                            successCount++
                        }
                    }

                    section.status = SectionStatus.SUCCESS
                    notifySectionCompleted(section)

                } catch (e: Exception) {
                    L.e(TAG, "Error restoring section ${section.name}", e)
                    section.status = SectionStatus.FAILED
                    section.errorMessage = e.message
                    errors.add("${section.displayName}: ${e.message}")
                    notifySectionCompleted(section)
                }
            }

            val success = sectionsToProcess.none { it.status == SectionStatus.FAILED }

            val result = RestoreResult(
                success = success,
                restoredSections = successCount,
                totalSections = sectionsToProcess.size,
                errors = errors
            )

            notifyBackupCompleted(success, sectionsToProcess)
            return result

        } catch (e: Exception) {
            L.e(TAG, "Error parsing backup", e)
            sectionsToProcess.forEach { it.status = SectionStatus.FAILED; it.errorMessage = "Backup parsing failed: ${e.message}" }
            notifyBackupCompleted(false, sectionsToProcess)
            return RestoreResult(
                success = false,
                restoredSections = 0,
                totalSections = sectionsToProcess.size,
                errors = listOf(context.getString(R.string.backup_parsing_failed, e.message ?: ""))
            )
        } finally {
            isFullBackupRestore = false
        }
    }

    fun analyzeBackupSections(backupContent: String): List<BackupSection> {
        val parsedSections = parseBackupSections(backupContent)
        val allSections = listOf(
            BackupSection("TIMETABLE_DATA", "Stundenplan-Daten"),
            BackupSection("CALENDAR_DATA", "Kalender-Daten"),
            BackupSection("HOMEWORK_DATA", "Hausaufgaben"),
            BackupSection("EXAM_DATA", "Klausuren"),
            BackupSection("GRADE_DATA", "Noten"),
            BackupSection("APP_SETTINGS", "App-Einstellungen")
        )

        return allSections.map { section ->
            val hasData = parsedSections.containsKey(section.name) &&
                    parsedSections[section.name]?.trim()?.isNotEmpty() == true
            BackupSection(
                section.name,
                section.displayName,
                if (hasData) SectionStatus.SUCCESS else SectionStatus.EMPTY
            )
        }
    }

    private fun exportTimetableData(): String {
        val subjects = sharedPreferences.getString("student_subjects", "") ?: ""
        val teachers = sharedPreferences.getString("student_teachers", "") ?: ""
        val rooms = sharedPreferences.getString("student_rooms", "") ?: ""
        val allSubjects = sharedPreferences.getString("all_extracted_subjects", "") ?: ""
        val allTeachers = sharedPreferences.getString("all_extracted_teachers", "") ?: ""
        val allRooms = sharedPreferences.getString("all_extracted_rooms", "") ?: ""
        val alternativeRooms = sharedPreferences.getString("alternative_rooms", "{}") ?: "{}"
        val klasse = sharedPreferences.getString("selected_klasse", "") ?: ""
        val bildungsgang = sharedPreferences.getString("selected_bildungsgang", "") ?: ""
        val hasScanned = sharedPreferences.getBoolean("has_scanned_document", false)
        val docInfo = sharedPreferences.getString("scanned_document_info", "") ?: ""
        val filterSubjects = sharedPreferences.getBoolean("filter_only_my_subjects", false)

        return buildString {
            appendLine("KLASSE=$klasse")
            appendLine("BILDUNGSGANG=$bildungsgang")
            appendLine("HAS_SCANNED=$hasScanned")
            appendLine("DOCUMENT_INFO=$docInfo")
            appendLine("STUDENT_SUBJECTS=$subjects")
            appendLine("STUDENT_TEACHERS=$teachers")
            appendLine("STUDENT_ROOMS=$rooms")
            appendLine("ALL_SUBJECTS=$allSubjects")
            appendLine("ALL_TEACHERS=$allTeachers")
            appendLine("ALL_ROOMS=$allRooms")
            appendLine("ALTERNATIVE_ROOMS=$alternativeRooms")
            appendLine("FILTER_SUBJECTS=$filterSubjects")
        }
    }

    private fun importTimetableData(content: String) {
        val lines = content.lines()
        sharedPreferences.edit {

            lines.forEach { line ->
                when {
                    line.startsWith("KLASSE=") -> putString(
                        "selected_klasse",
                        line.substringAfter("=")
                    )

                    line.startsWith("BILDUNGSGANG=") -> putString(
                        "selected_bildungsgang",
                        line.substringAfter("=")
                    )

                    line.startsWith("HAS_SCANNED=") -> putBoolean(
                        "has_scanned_document",
                        line.substringAfter("=").toBoolean()
                    )

                    line.startsWith("DOCUMENT_INFO=") -> putString(
                        "scanned_document_info",
                        line.substringAfter("=")
                    )

                    line.startsWith("STUDENT_SUBJECTS=") -> putString(
                        "student_subjects",
                        line.substringAfter("=")
                    )

                    line.startsWith("STUDENT_TEACHERS=") -> putString(
                        "student_teachers",
                        line.substringAfter("=")
                    )

                    line.startsWith("STUDENT_ROOMS=") -> putString(
                        "student_rooms",
                        line.substringAfter("=")
                    )

                    line.startsWith("ALL_SUBJECTS=") -> putString(
                        "all_extracted_subjects",
                        line.substringAfter("=")
                    )

                    line.startsWith("ALL_TEACHERS=") -> putString(
                        "all_extracted_teachers",
                        line.substringAfter("=")
                    )

                    line.startsWith("ALL_ROOMS=") -> putString(
                        "all_extracted_rooms",
                        line.substringAfter("=")
                    )

                    line.startsWith("ALTERNATIVE_ROOMS=") -> putString(
                        "alternative_rooms",
                        line.substringAfter("=")
                    )

                    line.startsWith("FILTER_SUBJECTS=") -> putBoolean(
                        "filter_only_my_subjects",
                        line.substringAfter("=").toBoolean()
                    )
                }
            }

        }
    }

    private fun exportAppSettings(): String {
        val startupPage = sharedPreferences.getInt("startup_page_index", 0)
        val autoUpdate = sharedPreferences.getBoolean("auto_update_enabled", false)
        val updateTime = sharedPreferences.getString("auto_update_time", "06:00") ?: ""
        val wifiOnly = sharedPreferences.getBoolean("update_wifi_only", false)
        val showNotifications = sharedPreferences.getBoolean("show_update_notifications", true)
        val changeNotification = sharedPreferences.getBoolean("change_notification_enabled", false)
        val changeInterval = sharedPreferences.getInt("change_notification_interval", 15)
        val changeType = sharedPreferences.getString("change_notification_type", "all_class_subjects") ?: ""
        val darkMode = sharedPreferences.getBoolean("dark_mode_enabled", false)
        val landscape = sharedPreferences.getBoolean("landscape_mode_enabled", true)
        val colorblindMode = sharedPreferences.getString("colorblind_mode", "none") ?: ""
        val removeCooldown = sharedPreferences.getBoolean("remove_update_cooldown", true)
        val leftFilterLift = sharedPreferences.getBoolean("left_filter_lift", false)
        val filterOnlyMySubjects = sharedPreferences.getBoolean("filter_only_my_subjects", false)
        val autoDeleteHomework = sharedPreferences.getBoolean("auto_delete_homework", false)
        val dueDateReminderEnabled = sharedPreferences.getBoolean("due_date_reminder_enabled", false)
        val dueDateReminderHours = sharedPreferences.getInt("due_date_reminder_hours", 16)
        val dailyHomeworkReminderEnabled = sharedPreferences.getBoolean("daily_homework_reminder_enabled", false)
        val dailyHomeworkReminderTime = sharedPreferences.getString("daily_homework_reminder_time", "19:00") ?: ""
        val examDueDateReminderEnabled = sharedPreferences.getBoolean("exam_due_date_reminder_enabled", false)
        val examDueDateReminderDays = sharedPreferences.getInt("exam_due_date_reminder_days", 7)
        val followSystemTheme = sharedPreferences.getBoolean("follow_system_theme", true)
        val calendarRealTimeEnabled = sharedPreferences.getBoolean("calendar_real_time_enabled", true)
        val calendarIncludeWeekends = sharedPreferences.getBoolean("calendar_include_weekends_dayview", false)
        val autoDetectLang = sharedPreferences.getBoolean("language_auto_detect", true)
        val savedLanguage = sharedPreferences.getString("selected_language", "de") ?: "de"

        return buildString {
            appendLine("STARTUP_PAGE=$startupPage")
            appendLine("AUTO_UPDATE=$autoUpdate")
            appendLine("UPDATE_TIME=$updateTime")
            appendLine("WIFI_ONLY=$wifiOnly")
            appendLine("SHOW_NOTIFICATIONS=$showNotifications")
            appendLine("CHANGE_NOTIFICATION=$changeNotification")
            appendLine("CHANGE_INTERVAL=$changeInterval")
            appendLine("CHANGE_TYPE=$changeType")
            appendLine("DARK_MODE=$darkMode")
            appendLine("LANDSCAPE_MODE=$landscape")
            appendLine("COLORBLIND_MODE=$colorblindMode")
            appendLine("REMOVE_COOLDOWN=$removeCooldown")
            appendLine("LEFT_FILTER_LIFT=$leftFilterLift")
            appendLine("FILTER_ONLY_MY_SUBJECTS=$filterOnlyMySubjects")
            appendLine("AUTO_DELETE_HOMEWORK=$autoDeleteHomework")
            appendLine("DUE_DATE_REMINDER_ENABLED=$dueDateReminderEnabled")
            appendLine("DUE_DATE_REMINDER_HOURS=$dueDateReminderHours")
            appendLine("DAILY_HOMEWORK_REMINDER_ENABLED=$dailyHomeworkReminderEnabled")
            appendLine("DAILY_HOMEWORK_REMINDER_TIME=$dailyHomeworkReminderTime")
            appendLine("EXAM_DUE_DATE_REMINDER_ENABLED=$examDueDateReminderEnabled")
            appendLine("EXAM_DUE_DATE_REMINDER_DAYS=$examDueDateReminderDays")
            appendLine("FOLLOW_SYSTEM_THEME=$followSystemTheme")
            appendLine("CALENDAR_REAL_TIME_ENABLED=$calendarRealTimeEnabled")
            appendLine("CALENDAR_INCLUDE_WEEKENDS=$calendarIncludeWeekends")
            appendLine("LANGUAGE_AUTO_DETECT=$autoDetectLang")
            appendLine("SELECTED_LANGUAGE=$savedLanguage")
        }
    }

    private fun importAppSettings(content: String) {
        val lines = content.lines()
        sharedPreferences.edit {
            lines.forEach { line ->
                when {
                    line.startsWith("STARTUP_PAGE=") -> putInt(
                        "startup_page_index",
                        line.substringAfter("=").toIntOrNull() ?: 0
                    )

                    line.startsWith("AUTO_UPDATE=") -> putBoolean(
                        "auto_update_enabled",
                        line.substringAfter("=").toBoolean()
                    )

                    line.startsWith("UPDATE_TIME=") -> putString(
                        "auto_update_time",
                        line.substringAfter("=")
                    )

                    line.startsWith("WIFI_ONLY=") -> putBoolean(
                        "update_wifi_only",
                        line.substringAfter("=").toBoolean()
                    )

                    line.startsWith("SHOW_NOTIFICATIONS=") -> putBoolean(
                        "show_update_notifications",
                        line.substringAfter("=").toBoolean()
                    )

                    line.startsWith("CHANGE_NOTIFICATION=") -> putBoolean(
                        "change_notification_enabled",
                        line.substringAfter("=").toBoolean()
                    )

                    line.startsWith("CHANGE_INTERVAL=") -> putInt(
                        "change_notification_interval",
                        line.substringAfter("=").toIntOrNull() ?: 15
                    )

                    line.startsWith("CHANGE_TYPE=") -> putString(
                        "change_notification_type",
                        line.substringAfter("=")
                    )

                    line.startsWith("DARK_MODE=") -> putBoolean(
                        "dark_mode_enabled",
                        line.substringAfter("=").toBoolean()
                    )

                    line.startsWith("LANDSCAPE_MODE=") -> putBoolean(
                        "landscape_mode_enabled",
                        line.substringAfter("=").toBoolean()
                    )

                    line.startsWith("COLORBLIND_MODE=") -> putString(
                        "colorblind_mode",
                        line.substringAfter("=")
                    )

                    line.startsWith("REMOVE_COOLDOWN=") -> putBoolean(
                        "remove_update_cooldown",
                        line.substringAfter("=").toBoolean()
                    )

                    line.startsWith("LEFT_FILTER_LIFT=") -> putBoolean(
                        "left_filter_lift",
                        line.substringAfter("=").toBoolean()
                    )

                    line.startsWith("FILTER_ONLY_MY_SUBJECTS=") -> putBoolean(
                        "filter_only_my_subjects",
                        line.substringAfter("=").toBoolean()
                    )

                    line.startsWith("AUTO_DELETE_HOMEWORK=") -> putBoolean(
                        "auto_delete_homework",
                        line.substringAfter("=").toBoolean()
                    )

                    line.startsWith("DUE_DATE_REMINDER_ENABLED=") -> putBoolean(
                        "due_date_reminder_enabled",
                        line.substringAfter("=").toBoolean()
                    )

                    line.startsWith("DUE_DATE_REMINDER_HOURS=") -> putInt(
                        "due_date_reminder_hours",
                        line.substringAfter("=").toIntOrNull() ?: 16
                    )

                    line.startsWith("DAILY_HOMEWORK_REMINDER_ENABLED=") -> putBoolean(
                        "daily_homework_reminder_enabled",
                        line.substringAfter("=").toBoolean()
                    )

                    line.startsWith("DAILY_HOMEWORK_REMINDER_TIME=") -> putString(
                        "daily_homework_reminder_time",
                        line.substringAfter("=")
                    )

                    line.startsWith("EXAM_DUE_DATE_REMINDER_ENABLED=") -> putBoolean(
                        "exam_due_date_reminder_enabled",
                        line.substringAfter("=").toBoolean()
                    )

                    line.startsWith("EXAM_DUE_DATE_REMINDER_DAYS=") -> putInt(
                        "exam_due_date_reminder_days",
                        line.substringAfter("=").toIntOrNull() ?: 7
                    )

                    line.startsWith("FOLLOW_SYSTEM_THEME=") -> putBoolean(
                        "follow_system_theme",
                        line.substringAfter("=").toBoolean()
                    )

                    line.startsWith("CALENDAR_REAL_TIME_ENABLED=") -> putBoolean(
                        "calendar_real_time_enabled",
                        line.substringAfter("=").toBoolean()
                    )

                    line.startsWith("CALENDAR_INCLUDE_WEEKENDS=") -> putBoolean(
                        "calendar_include_weekends_dayview",
                        line.substringAfter("=").toBoolean()
                    )

                    line.startsWith("LANGUAGE_AUTO_DETECT=") -> putBoolean(
                        "language_auto_detect",
                        line.substringAfter("=").toBoolean()
                    )

                    line.startsWith("SELECTED_LANGUAGE=") -> putString(
                        "selected_language",
                        line.substringAfter("=")
                    )
                }
            }
        }
    }

    data class ExamEntry(
        val id: String = UUID.randomUUID().toString(),
        var subject: String,
        var date: Date,
        var note: String = "",
        var isCompleted: Boolean = false,
        var examNumber: Int? = null,
        var isFromSchedule: Boolean = false,
        var mark: Int? = null
    )

    data class ExamScheduleInfo(
        val semester: String,
        val year: String,
        val className: String,
        val isValid: Boolean
    )

    fun exportExamData(): String {
        return try {
            val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY)
            val exportDate = dateFormat.format(Date())

            val sb = StringBuilder()
            sb.appendLine("# Heinrich-Kleyer-Schule Klausuren Export")
            sb.appendLine("# Exportiert am: $exportDate")

            val scheduleInfoJson = sharedPreferences.getString("exam_schedule_info", null)
            if (scheduleInfoJson != null) {
                try {
                    val scheduleInfo = Gson().fromJson(scheduleInfoJson, ExamScheduleInfo::class.java)
                    sb.appendLine("# Semester: ${scheduleInfo.semester}")
                    sb.appendLine("# Schuljahr: ${scheduleInfo.year}")
                    sb.appendLine("# Klasse: ${scheduleInfo.className}")
                } catch (e: Exception) {
                    L.w(TAG, "Error parsing schedule info", e)
                }
            }

            sb.appendLine("# Format: Fach|Datum|Notiz|Erledigt|Klausurnummer|AusStundenplan|Punkte")
            sb.appendLine()

            val examListJson = sharedPreferences.getString("exam_list", "[]")
            val type = object : TypeToken<MutableList<com.thecooker.vertretungsplaner.ui.exams.ExamFragment.ExamEntry>>() {}.type
            val examList: MutableList<com.thecooker.vertretungsplaner.ui.exams.ExamFragment.ExamEntry> = Gson().fromJson(examListJson, type) ?: mutableListOf()

            for (exam in examList) {
                val dateStr = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(exam.date)
                val noteStr = if (exam.note.isBlank()) "" else exam.note.replace("|", "\\|").replace("\n", "\\n").replace("\r", "\\r")
                val completedStr = if (exam.isCompleted) "1" else "0"
                val examNumberStr = exam.examNumber?.toString() ?: ""
                val fromScheduleStr = if (exam.isFromSchedule) "1" else "0"
                val markStr = exam.mark?.toString() ?: ""

                sb.appendLine("${exam.subject}|$dateStr|$noteStr|$completedStr|$examNumberStr|$fromScheduleStr|$markStr")
            }

            sb.appendLine()
            sb.append(getFilteredCalendarExportForBackup())

            sb.toString()
        } catch (e: Exception) {
            L.e(TAG, "Error exporting exam data", e)
            "# Error exporting exam data: ${e.message}"
        }
    }

    fun importExamData(content: String) {
        try {
            if (!content.contains("# Heinrich-Kleyer-Schule Klausuren Export")) {
                throw IllegalArgumentException(context.getString(R.string.backup_invalid_data_format))
            }

            val lines = content.split("\n")
            val examList = mutableListOf<com.thecooker.vertretungsplaner.ui.exams.ExamFragment.ExamEntry>()

            val calendarManager = CalendarDataManager.getInstance(context)
            calendarManager.clearCalendarData()

            var importedExamCount = 0
            val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)

            var inCalendarSection = false
            val calendarLines = mutableListOf<String>()

            for (line in lines) {
                if (line.trim().startsWith("# Calendar Data")) {
                    inCalendarSection = true
                    continue
                }

                if (inCalendarSection) {
                    calendarLines.add(line)
                } else if (!line.startsWith("#") && line.trim().isNotEmpty() && line.contains("|")) {
                    val parts = line.split("|")
                    if (parts.size >= 7) {
                        try {
                            val subject = parts[0].trim()
                            val date = dateFormat.parse(parts[1].trim()) ?: continue
                            val note = parts[2].trim()
                                .replace("\\|", "|")
                                .replace("\\n", "\n")
                                .replace("\\r", "\r")
                            val isCompleted = parts[3].trim() == "1"
                            val examNumber = parts.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()
                            val isFromSchedule = parts.getOrNull(5)?.trim() == "1"
                            val mark = parts.getOrNull(6)?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()

                            val exam = com.thecooker.vertretungsplaner.ui.exams.ExamFragment.ExamEntry(
                                subject = subject,
                                date = date,
                                note = note,
                                isCompleted = isCompleted,
                                examNumber = examNumber,
                                isFromSchedule = isFromSchedule,
                                mark = mark
                            )

                            examList.add(exam)
                            importedExamCount++
                        } catch (e: Exception) {
                            L.w(TAG, "Error parsing exam line: $line", e)
                        }
                    } else if (parts.size >= 6) {
                        try {
                            val subject = parts[0].trim()
                            val date = dateFormat.parse(parts[1].trim()) ?: continue
                            val note = parts[2].trim()
                                .replace("\\|", "|")
                                .replace("\\n", "\n")
                                .replace("\\r", "\r")
                            val isCompleted = parts[3].trim() == "1"
                            val examNumber = parts.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()
                            val isFromSchedule = parts.getOrNull(5)?.trim() == "1"

                            val exam = com.thecooker.vertretungsplaner.ui.exams.ExamFragment.ExamEntry(
                                subject = subject,
                                date = date,
                                note = note,
                                isCompleted = isCompleted,
                                examNumber = examNumber,
                                isFromSchedule = isFromSchedule,
                                mark = null // No mark in old format
                            )

                            examList.add(exam)
                            importedExamCount++
                        } catch (e: Exception) {
                            L.w(TAG, "Error parsing exam line (old format): $line", e)
                        }
                    }
                }
            }

            if (calendarLines.isNotEmpty() && !isFullBackupRestore) {
                val calendarContent = calendarLines.joinToString("\n")
                calendarManager.importCalendarData(calendarContent)
            }

            val json = Gson().toJson(examList)
            sharedPreferences.edit {
                putString("exam_list", json)
            }

            ExamManager.setExams(examList)

            L.d(TAG, "Imported $importedExamCount exams successfully")

        } catch (e: Exception) {
            L.e(TAG, "Error importing exam data", e)
            throw Exception(context.getString(R.string.backup_import_error, e.message ?: ""))
        }
    }

    private fun importExamDataFromFullBackup(content: String) {
        try {
            val lines = content.split("\n")
            val examList = mutableListOf<com.thecooker.vertretungsplaner.ui.exams.ExamFragment.ExamEntry>()

            var importedExamCount = 0
            val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)

            for (line in lines) {
                if (line.startsWith("#") || line.trim().isEmpty()) {
                    continue
                }

                if (line.contains("|")) {
                    val parts = line.split("|")
                    if (parts.size >= 7) {
                        try {
                            val subject = parts[0].trim()
                            val date = dateFormat.parse(parts[1].trim()) ?: continue
                            val note = parts[2].trim()
                                .replace("\\|", "|")
                                .replace("\\n", "\n")
                                .replace("\\r", "\r")
                            val isCompleted = parts[3].trim() == "1"
                            val examNumber = parts.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()
                            val isFromSchedule = parts.getOrNull(5)?.trim() == "1"
                            val mark = parts.getOrNull(6)?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()

                            val exam = com.thecooker.vertretungsplaner.ui.exams.ExamFragment.ExamEntry(
                                subject = subject,
                                date = date,
                                note = note,
                                isCompleted = isCompleted,
                                examNumber = examNumber,
                                isFromSchedule = isFromSchedule,
                                mark = mark
                            )

                            examList.add(exam)
                            importedExamCount++
                        } catch (e: Exception) {
                            L.w(TAG, "Error parsing exam line from full backup: $line", e)
                        }
                    } else if (parts.size >= 6) {
                        try {
                            val subject = parts[0].trim()
                            val date = dateFormat.parse(parts[1].trim()) ?: continue
                            val note = parts[2].trim()
                                .replace("\\|", "|")
                                .replace("\\n", "\n")
                                .replace("\\r", "\r")
                            val isCompleted = parts[3].trim() == "1"
                            val examNumber = parts.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()
                            val isFromSchedule = parts.getOrNull(5)?.trim() == "1"

                            val exam = com.thecooker.vertretungsplaner.ui.exams.ExamFragment.ExamEntry(
                                subject = subject,
                                date = date,
                                note = note,
                                isCompleted = isCompleted,
                                examNumber = examNumber,
                                isFromSchedule = isFromSchedule,
                                mark = null
                            )

                            examList.add(exam)
                            importedExamCount++
                        } catch (e: Exception) {
                            L.w(TAG, "Error parsing exam line from full backup (old format): $line", e)
                        }
                    }
                }
            }

            if (importedExamCount > 0) {
                val json = Gson().toJson(examList)
                sharedPreferences.edit {
                    putString("exam_list", json)
                }

                ExamManager.setExams(examList)

                L.d(TAG, "Imported $importedExamCount exams from full backup successfully")
            } else {
                L.w(TAG, "No exam data found in full backup section")
            }

        } catch (e: Exception) {
            L.e(TAG, "Error importing exam data from full backup", e)
            throw Exception(context.getString(R.string.backup_exam_import_failed, e.message ?: ""))
        }
    }

    private fun getFilteredCalendarExportForBackup(): String {
        val sb = StringBuilder()
        sb.appendLine("# Calendar Data Export")
        sb.appendLine("# Format: Date|DayOfWeek|Month|Year|Content|IsSpecialDay|SpecialNote")

        try {
            val calendarManager = CalendarDataManager.getInstance(context)
            val allDays = calendarManager.getAllCalendarDays()

            val specialDaysOnly = allDays.filter { dayInfo ->
                dayInfo.isSpecialDay && dayInfo.specialNote.isNotEmpty()
            }

            L.d(TAG, "Exporting ${specialDaysOnly.size} special days out of ${allDays.size} total days")

            for (dayInfo in specialDaysOnly.sortedBy { it.date }) {
                val dateStr = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(dayInfo.date)
                val dayOfWeek = dayInfo.dayOfWeek
                val month = dayInfo.month
                val year = dayInfo.year
                val content = dayInfo.content
                val isSpecialDay = "1" // always 1 since filtered for special days
                val specialNote = dayInfo.specialNote

                sb.appendLine("$dateStr|$dayOfWeek|$month|$year|$content|$isSpecialDay|$specialNote")
            }
        } catch (e: Exception) {
            L.e(TAG, "Error exporting calendar data for backup", e)
            sb.appendLine("# Error exporting calendar data: ${e.message}")
        }

        return sb.toString()
    }

    fun exportCalendarData(): String {
        return try {
            val sb = StringBuilder()
            val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY)

            sb.appendLine("# Heinrich-Kleyer-Schule Kalender Stundenplan Export")
            sb.appendLine("# Exportiert am: ${dateFormat.format(Date())}")
            sb.appendLine("SCHULJAHR=2025/2026")
            sb.appendLine("")

            sb.appendLine("# Feriendaten mit Namen:")
            val vacationData = loadVacationDataFromPrefs()
            vacationData.forEach { (weekKey, vacation) ->
                sb.appendLine("$weekKey: ${vacation.name} (${vacation.source})")
            }
            sb.appendLine("")

            sb.appendLine("# Benutzer Notizen und besondere Ereignisse:")
            exportUserDayData(sb)
            sb.appendLine("")

            sb.appendLine("# Alternative Räume Verwendung:")
            val alternativeRoomUsage = extractAlternativeRoomUsage()
            alternativeRoomUsage.forEach { (key, room) ->
                sb.appendLine("$key: $room")
            }
            sb.appendLine("")

            sb.appendLine("# Stundenplan Daten:")
            val timetableData = loadTimetableDataFromPrefs()

            for ((dayKey, daySchedule) in timetableData) {
                val dayName = when (dayKey) {
                    "weekday_0" -> "Montag"
                    "weekday_1" -> "Dienstag"
                    "weekday_2" -> "Mittwoch"
                    "weekday_3" -> "Donnerstag"
                    "weekday_4" -> "Freitag"
                    else -> dayKey
                }
                sb.appendLine("$dayName:")

                val groupedLessons = mutableListOf<Pair<IntRange, TimetableEntry>>()
                var currentRange: IntRange? = null
                var currentSubject: String? = null

                for ((lesson, entry) in daySchedule.toSortedMap()) {
                    if (entry.subject == currentSubject && currentRange != null) {
                        currentRange = currentRange.first..lesson
                    } else {
                        if (currentRange != null && currentSubject != null) {
                            val previousEntry = daySchedule[currentRange.first]
                            if (previousEntry != null) {
                                groupedLessons.add(Pair(currentRange, previousEntry))
                            }
                        }
                        currentRange = lesson..lesson
                        currentSubject = entry.subject
                    }
                }

                if (currentRange != null && currentSubject != null) {
                    val lastEntry = daySchedule[currentRange.first]
                    if (lastEntry != null) {
                        groupedLessons.add(Pair(currentRange, lastEntry))
                    }
                }

                for ((range, entry) in groupedLessons) {
                    val lessonCount = range.count()
                    val lessonText = if (lessonCount == 1) "Stunde" else "Stunden"
                    val rangeText = if (range.first == range.last) {
                        "Stunde ${range.first}"
                    } else {
                        "Stunden ${range.first}-${range.last}"
                    }
                    sb.appendLine("  $rangeText: ${entry.subject} ($lessonCount $lessonText)")
                    if (entry.teacher.isNotBlank()) sb.appendLine("    Lehrer: ${entry.teacher}")
                    if (entry.room.isNotBlank()) {
                        val roomText = if (entry.useAlternativeRoom) "    Raum: ${entry.room} (Alternativ)" else "    Raum: ${entry.room}"
                        sb.appendLine(roomText)
                    }
                }
                sb.appendLine("")
            }

            sb.appendLine("# Raw JSON Data:")
            val json = Gson().toJson(timetableData)
            sb.appendLine("TIMETABLE_DATA=$json")

            sb.appendLine("# Vacation Data:")
            val vacationJson = Gson().toJson(vacationData)
            sb.appendLine("VACATION_DATA=$vacationJson")

            sb.appendLine("# User Day Data:")
            val userDayDataJson = exportUserDayDataAsJson()
            sb.appendLine("USER_DAY_DATA=$userDayDataJson")

            sb.appendLine("# Alternative Rooms Usage Data:")
            val alternativeRoomUsageJson = Gson().toJson(alternativeRoomUsage)
            sb.appendLine("ALTERNATIVE_ROOMS_USAGE_DATA=$alternativeRoomUsageJson")

            sb.toString()
        } catch (e: Exception) {
            L.e(TAG, "Error exporting calendar data", e)
            "# Error exporting calendar data: ${e.message}"
        }
    }

    private fun extractAlternativeRoomUsage(): Map<String, String> {
        val usage = mutableMapOf<String, String>()
        val timetableData = loadTimetableDataFromPrefs()

        for ((dayKey, daySchedule) in timetableData) {
            for ((lesson, entry) in daySchedule) {
                if (entry.useAlternativeRoom && entry.room.isNotBlank()) {
                    val key = "${dayKey}_${lesson}_${entry.subject}"
                    usage[key] = entry.room
                }
            }
        }

        return usage
    }

    private fun exportUserDayData(sb: StringBuilder) {
        val allKeys = sharedPreferences.all.keys
        val noteKeys = allKeys.filter { it.startsWith("user_notes_") }
        allKeys.filter { it.startsWith("user_special_occasions_") }

        val processedDates = mutableSetOf<String>()

        noteKeys.forEach { key ->
            val dateStr = key.substringAfter("user_notes_")
            if (!processedDates.contains(dateStr)) {
                processedDates.add(dateStr)

                val notes = sharedPreferences.getString(key, "") ?: ""
                val occasionsJson = sharedPreferences.getString("user_special_occasions_$dateStr", "[]") ?: "[]"
                val occasions = try {
                    val type = object : TypeToken<List<String>>() {}.type
                    Gson().fromJson<List<String>>(occasionsJson, type) ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }

                if (notes.isNotBlank() || occasions.isNotEmpty()) {
                    sb.appendLine("$dateStr:")
                    if (notes.isNotBlank()) {
                        sb.appendLine("  Notizen: $notes")
                    }
                    if (occasions.isNotEmpty()) {
                        sb.appendLine("  Ereignisse: ${occasions.joinToString(", ")}")
                    }
                }
            }
        }
    }

    private fun exportUserDayDataAsJson(): String {
        val userDayData = mutableMapOf<String, UserDayData>()
        val allKeys = sharedPreferences.all.keys
        val noteKeys = allKeys.filter { it.startsWith("user_notes_") }

        noteKeys.forEach { key ->
            val dateStr = key.substringAfter("user_notes_")
            val notes = sharedPreferences.getString(key, "") ?: ""
            val occasionsJson = sharedPreferences.getString("user_special_occasions_$dateStr", "[]") ?: "[]"
            val occasions = try {
                val type = object : TypeToken<List<String>>() {}.type
                Gson().fromJson<List<String>>(occasionsJson, type) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }

            if (notes.isNotBlank() || occasions.isNotEmpty()) {
                userDayData[dateStr] = UserDayData(notes, occasions)
            }
        }

        return Gson().toJson(userDayData)
    }

    fun importCalendarData(content: String) {
        try {
            val lines = content.split("\n")
            var timetableImported = false
            var vacationImported = false
            var userDayDataImported = false
            var alternativeRoomUsageImported = false

            for (line in lines) {
                when {
                    line.startsWith("TIMETABLE_DATA=") -> {
                        val jsonData = line.substringAfter("TIMETABLE_DATA=")
                        val type = object : TypeToken<MutableMap<String, MutableMap<Int, TimetableEntry>>>() {}.type
                        val importedData: MutableMap<String, MutableMap<Int, TimetableEntry>> = Gson().fromJson(jsonData, type)

                        val processedData = mutableMapOf<String, MutableMap<Int, TimetableEntry>>()
                        for ((dayKey, daySchedule) in importedData) {
                            val processedSchedule = mutableMapOf<Int, TimetableEntry>()
                            for ((lesson, entry) in daySchedule) {
                                val (matchedTeacher, matchedRoom) = getTeacherAndRoomForSubject(entry.subject)
                                val updatedEntry = TimetableEntry(
                                    subject = entry.subject,
                                    duration = entry.duration,
                                    isBreak = entry.isBreak,
                                    breakDuration = entry.breakDuration,
                                    teacher = matchedTeacher.ifBlank { entry.teacher },
                                    room = entry.room.ifBlank { matchedRoom },
                                    useAlternativeRoom = entry.useAlternativeRoom
                                )
                                processedSchedule[lesson] = updatedEntry
                            }
                            processedData[dayKey] = processedSchedule
                        }

                        saveTimetableDataToPrefs(processedData)
                        timetableImported = true
                    }
                    line.startsWith("VACATION_DATA=") -> {
                        val jsonData = line.substringAfter("VACATION_DATA=")

                        try {
                            val newType = object : TypeToken<MutableMap<String, VacationWeek>>() {}.type
                            val importedVacations: MutableMap<String, VacationWeek> = Gson().fromJson(jsonData, newType)
                            saveVacationDataToPrefs(importedVacations)
                            vacationImported = true
                        } catch (_: Exception) {
                            try {
                                val oldType = object : TypeToken<MutableSet<String>>() {}.type
                                val importedVacationsOld: MutableSet<String> = Gson().fromJson(jsonData, oldType)

                                val convertedVacations = mutableMapOf<String, VacationWeek>()
                                importedVacationsOld.forEach { weekKey ->
                                    val vacationName = "Ferien"
                                    convertedVacations[weekKey] = VacationWeek(weekKey, vacationName, VacationSource.MANUAL)
                                }
                                saveVacationDataToPrefs(convertedVacations)
                                vacationImported = true
                            } catch (migrateException: Exception) {
                                L.e(TAG, "Error migrating vacation data", migrateException)
                            }
                        }
                    }
                    line.startsWith("USER_DAY_DATA=") -> {
                        val jsonData = line.substringAfter("USER_DAY_DATA=")
                        try {
                            val type = object : TypeToken<MutableMap<String, UserDayData>>() {}.type
                            val importedUserData: MutableMap<String, UserDayData> = Gson().fromJson(jsonData, type)

                            clearAllUserDayData()

                            importedUserData.forEach { (dateStr, userData) ->
                                if (userData.notes.isNotBlank()) {
                                    sharedPreferences.edit {
                                        putString("user_notes_$dateStr", userData.notes)
                                    }
                                }
                                if (userData.specialOccasions.isNotEmpty()) {
                                    val occasionsJson = Gson().toJson(userData.specialOccasions)
                                    sharedPreferences.edit {
                                        putString("user_special_occasions_$dateStr", occasionsJson)
                                    }
                                }
                            }
                            userDayDataImported = true
                        } catch (e: Exception) {
                            L.e(TAG, "Error importing user day data", e)
                        }
                    }
                    line.startsWith("ALTERNATIVE_ROOMS_USAGE_DATA=") -> {
                        val jsonData = line.substringAfter("ALTERNATIVE_ROOMS_USAGE_DATA=")
                        try {
                            val type = object : TypeToken<MutableMap<String, String>>() {}.type
                            val importedUsage: MutableMap<String, String> = Gson().fromJson(jsonData, type)
                            applyAlternativeRoomUsage(importedUsage)
                            alternativeRoomUsageImported = true
                            L.d(TAG, "Alternative room usage imported: ${importedUsage.size} entries")
                        } catch (e: Exception) {
                            L.e(TAG, "Error importing alternative room usage data", e)
                        }
                    }
                }
            }

            if (!timetableImported && !vacationImported && !userDayDataImported && !alternativeRoomUsageImported) {
                throw Exception(context.getString(R.string.backup_no_calendar_data))
            }

            L.d(TAG, "Import completed - Timetable: $timetableImported, Vacation: $vacationImported, UserData: $userDayDataImported, AltRoomUsage: $alternativeRoomUsageImported")

        } catch (e: Exception) {
            L.e(TAG, "Error importing calendar data", e)
            throw Exception(context.getString(R.string.backup_calendar_import_failed, e.message ?: ""))
        }
    }

    private fun applyAlternativeRoomUsage(usage: Map<String, String>) {
        val timetableData = loadTimetableDataFromPrefs()

        for ((key, alternativeRoom) in usage) {
            val parts = key.split("_")
            if (parts.size >= 3) {
                val dayKey = parts[0] + "_" + parts[1] // weekday_X
                val lesson = parts[2].toIntOrNull()
                val subject = parts.drop(3).joinToString("_")

                if (lesson != null && timetableData[dayKey]?.containsKey(lesson) == true) {
                    val existingEntry = timetableData[dayKey]!![lesson]!!
                    if (existingEntry.subject == subject) {
                        val (_, _) = getTeacherAndRoomForSubject(subject)
                        timetableData[dayKey]!![lesson] = existingEntry.copy(
                            room = alternativeRoom,
                            useAlternativeRoom = true
                        )
                    }
                }
            }
        }

        saveTimetableDataToPrefs(timetableData)
    }

    private fun clearAllUserDayData() {
        val allKeys = sharedPreferences.all.keys.toList()
        sharedPreferences.edit {

            allKeys.forEach { key ->
                if (key.startsWith("user_notes_") || key.startsWith("user_special_occasions_")) {
                    remove(key)
                }
            }

        }
    }

    data class UserDayData(
        val notes: String,
        val specialOccasions: List<String>
    )

    private fun loadTimetableDataFromPrefs(): MutableMap<String, MutableMap<Int, TimetableEntry>> {
        return try {
            val json = sharedPreferences.getString("timetable_data", "{}")
            val type = object : TypeToken<MutableMap<String, MutableMap<Int, TimetableEntry>>>() {}.type
            Gson().fromJson(json, type) ?: mutableMapOf()
        } catch (e: Exception) {
            L.e(TAG, "Error loading timetable data from preferences", e)
            mutableMapOf()
        }
    }

    private fun saveTimetableDataToPrefs(data: MutableMap<String, MutableMap<Int, TimetableEntry>>) {
        try {
            val json = Gson().toJson(data)
            sharedPreferences.edit {
                putString("timetable_data", json)
            }
        } catch (e: Exception) {
            L.e(TAG, "Error saving timetable data to preferences", e)
        }
    }

    private fun loadVacationDataFromPrefs(): MutableMap<String, VacationWeek> {
        return try {
            val json = sharedPreferences.getString("vacation_data", "{}")
            if (json == "[]" || json == "{}") {
                return mutableMapOf()
            }

            try {
                val type = object : TypeToken<MutableMap<String, VacationWeek>>() {}.type
                Gson().fromJson(json, type) ?: mutableMapOf()
            } catch (_: Exception) {
                val oldType = object : TypeToken<MutableSet<String>>() {}.type
                val oldData: MutableSet<String> = Gson().fromJson(json, oldType) ?: mutableSetOf()
                val convertedData = mutableMapOf<String, VacationWeek>()
                oldData.forEach { weekKey ->
                    convertedData[weekKey] = VacationWeek(weekKey, "Ferien", VacationSource.MANUAL)
                }
                convertedData
            }
        } catch (e: Exception) {
            L.e(TAG, "Error loading vacation data from preferences", e)
            mutableMapOf()
        }
    }

    private fun saveVacationDataToPrefs(data: MutableMap<String, VacationWeek>) {
        try {
            val json = Gson().toJson(data)
            sharedPreferences.edit {
                putString("vacation_data", json)
            }
        } catch (e: Exception) {
            L.e(TAG, "Error saving vacation data to preferences", e)
        }
    }

    private fun getTeacherAndRoomForSubject(subject: String): Pair<String, String> {
        val subjects = sharedPreferences.getString("student_subjects", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        val teachers = sharedPreferences.getString("student_teachers", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        val rooms = sharedPreferences.getString("student_rooms", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

        var subjectIndex = subjects.indexOf(subject)

        if (subjectIndex == -1 && subject.contains("-")) {
            val baseSubject = subject.split("-")[0]
            subjectIndex = subjects.indexOfFirst { it.startsWith(baseSubject) }
        }

        if (subjectIndex == -1) {
            subjectIndex = subjects.indexOfFirst { storedSubject ->
                val storedBase = storedSubject.split("-")[0]
                val lookupBase = subject.split("-")[0]
                storedBase.equals(lookupBase, ignoreCase = true)
            }
        }

        val teacher = if (subjectIndex >= 0 && subjectIndex < teachers.size) {
            teachers[subjectIndex].takeIf { it.isNotBlank() } ?: ""
        } else ""

        val room = if (subjectIndex >= 0 && subjectIndex < rooms.size) {
            rooms[subjectIndex].takeIf { it.isNotBlank() } ?: ""
        } else ""

        return Pair(teacher, room)
    }

    data class TimetableEntry(
        val subject: String,
        val duration: Int = 1,
        val isBreak: Boolean = false,
        val breakDuration: Int = 0,
        val teacher: String = "",
        val room: String = "",
        val useAlternativeRoom: Boolean = false
    )

    data class VacationWeek(
        val weekKey: String,
        val name: String,
        val source: VacationSource = VacationSource.MANUAL
    )

    enum class VacationSource {
        MANUAL,
    }

    fun exportHomeworkData(): String {
        return try {
            val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY)
            val exportDate = dateFormat.format(Date())

            val sb = StringBuilder()
            sb.appendLine("# Heinrich-Kleyer-Schule Hausaufgaben Export")
            sb.appendLine("# Exportiert am: $exportDate")
            sb.appendLine("# Format: Fach|Fälligkeitsdatum|Fälligkeitszeit|Stunde|Inhalt|Erledigt")
            sb.appendLine()

            val json = sharedPreferences.getString("homework_list", "[]")
            val type = object : TypeToken<List<HomeworkEntry>>() {}.type
            val homeworkList: List<HomeworkEntry> = Gson().fromJson(json, type) ?: emptyList()

            for (homework in homeworkList) {
                val dueDateStr = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(homework.dueDate)
                val dueTimeStr = homework.dueTime?.let {
                    SimpleDateFormat("HH:mm", Locale.GERMANY).format(it)
                } ?: ""
                val lessonStr = homework.lessonNumber?.toString() ?: ""
                val contentStr = homework.content.replace("\n", "\\n")
                val completedStr = if (homework.isCompleted) "1" else "0"

                sb.appendLine("${homework.subject}|$dueDateStr|$dueTimeStr|$lessonStr|$contentStr|$completedStr")
            }

            sb.toString()
        } catch (e: Exception) {
            L.e(TAG, "Error exporting homework data", e)
            "# Error exporting homework data: ${e.message}"
        }
    }

    fun importHomeworkData(content: String) {
        try {
            val lines = content.split("\n").filter { line ->
                !line.startsWith("#") && line.trim().isNotEmpty()
            }

            val homeworkList = mutableListOf<HomeworkEntry>()
            var importedCount = 0
            val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)
            val timeFormat = SimpleDateFormat("HH:mm", Locale.GERMANY)

            for (line in lines) {
                val parts = line.split("|")
                if (parts.size >= 5) {
                    try {
                        val subject = parts[0].trim()
                        val dueDate = dateFormat.parse(parts[1].trim()) ?: continue
                        val dueTime = if (parts[2].trim().isNotEmpty()) {
                            timeFormat.parse(parts[2].trim())
                        } else null
                        val lessonNumber = if (parts[3].trim().isNotEmpty()) {
                            parts[3].trim().toIntOrNull()
                        } else null
                        val homeworkContent = parts[4].trim().replace("\\n", "\n")
                        val isCompleted = parts.getOrNull(5)?.trim() == "1"

                        val (checklistItems, hasTextContent) = parseContentWithChecklistItems(homeworkContent)
                        val homework = HomeworkEntry(
                            subject = subject,
                            dueDate = dueDate,
                            dueTime = dueTime,
                            lessonNumber = lessonNumber,
                            content = homeworkContent,
                            isCompleted = isCompleted,
                            checklistItems = checklistItems,
                            hasTextContent = hasTextContent,
                            completedDate = if (isCompleted) Date() else null
                        )

                        homeworkList.add(homework)
                        importedCount++
                    } catch (e: Exception) {
                        L.w(TAG, "Error parsing homework line: $line", e)
                    }
                }
            }

            if (importedCount > 0) {
                val json = Gson().toJson(homeworkList)
                sharedPreferences.edit {
                    putString("homework_list", json)
                }

                L.d(TAG, "Imported $importedCount homework entries successfully")
            } else {
                throw Exception(context.getString(R.string.backup_no_homework_data))
            }
        } catch (e: Exception) {
            L.e(TAG, "Error importing homework data", e)
            throw Exception(context.getString(R.string.backup_homework_import_failed, e.message ?: ""))
        }
    }

    private fun parseContentWithChecklistItems(content: String): Pair<MutableList<ChecklistItem>, Boolean> {
        val checklistItems = mutableListOf<ChecklistItem>()
        val lines = content.split("\n")
        var hasTextContent = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("-") && trimmed.length > 1) {
                checklistItems.add(ChecklistItem(trimmed.substring(1).trim()))
            } else if (trimmed.isNotEmpty()) {
                hasTextContent = true
            }
        }

        return Pair(checklistItems, hasTextContent)
    }

    data class HomeworkEntry(
        val id: String = UUID.randomUUID().toString(),
        var subject: String,
        var dueDate: Date,
        var dueTime: Date? = null,
        var lessonNumber: Int? = null,
        var content: String,
        var isCompleted: Boolean = false,
        var completedDate: Date? = null,
        var checklistItems: MutableList<ChecklistItem> = mutableListOf(),
        var hasTextContent: Boolean = false
    )

    data class ChecklistItem(
        var text: String,
        var isCompleted: Boolean = false
    )

    fun exportGradeData(): String {
        return try {
            val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY)
            val exportDate = dateFormat.format(Date())
            val bildungsgang = sharedPreferences.getString("selected_bildungsgang", "")
            val useComplexGrading = bildungsgang == "BG" && sharedPreferences.getBoolean("use_simple_grading", false).not()

            val sb = StringBuilder()
            sb.appendLine("# Heinrich-Kleyer-Schule Noten Export")
            sb.appendLine("# Exportiert am: $exportDate")
            sb.appendLine("# Grading System: ${if (useComplexGrading) "Complex (Abitur)" else "Simple"}")
            sb.appendLine("# Format: Fach|Lehrer|HalbjahrX_Mündlich|HalbjahrX_Verhältnis")
            sb.appendLine()

            val subjects = sharedPreferences.getString("student_subjects", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
            val teachers = sharedPreferences.getString("student_teachers", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

            val oralGradesHistoryJson = sharedPreferences.getString("oral_grades_history", "{}")
            val oralGradesHistoryType = object : TypeToken<Map<String, Map<Int, Double>>>() {}.type
            val oralGradesHistory: Map<String, Map<Int, Double>> = try {
                Gson().fromJson(oralGradesHistoryJson, oralGradesHistoryType) ?: emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }

            val ratiosJson = sharedPreferences.getString("grade_ratios", "{}")
            val ratiosType = object : TypeToken<Map<String, Pair<Int, Int>>>() {}.type
            val ratios: Map<String, Pair<Int, Int>> = try {
                Gson().fromJson(ratiosJson, ratiosType) ?: emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }

            val pruefungsfaecherJson = sharedPreferences.getString("pruefungsfaecher", "{}")
            val pruefungsfaecherType = object : TypeToken<Map<String, Boolean>>() {}.type
            val pruefungsfaecher: Map<String, Boolean> = try {
                Gson().fromJson(pruefungsfaecherJson, pruefungsfaecherType) ?: emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }

            val pruefungsergebnisseJson = sharedPreferences.getString("pruefungsergebnisse", "{}")
            val pruefungsergebnisseType = object : TypeToken<Map<String, Double>>() {}.type
            val pruefungsergebnisse: Map<String, Double> = try {
                Gson().fromJson(pruefungsergebnisseJson, pruefungsergebnisseType) ?: emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }

            val selectedHalfYearsJson = sharedPreferences.getString("selected_half_years", "{}")
            val selectedHalfYearsType = object : TypeToken<Map<String, Int>>() {}.type
            val selectedHalfYears: Map<String, Int> = try {
                Gson().fromJson(selectedHalfYearsJson, selectedHalfYearsType) ?: emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }

            val subjectGradeSystemJson = sharedPreferences.getString("subject_grade_system", "{}")
            val subjectGradeSystemType = object : TypeToken<Map<String, Boolean>>() {}.type
            val subjectGradeSystems: Map<String, Boolean> = try {
                Gson().fromJson(subjectGradeSystemJson, subjectGradeSystemType) ?: emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }

            val subjectRangeModeJson = sharedPreferences.getString("subject_range_mode", "{}")
            val subjectRangeModeType = object : TypeToken<Map<String, Boolean>>() {}.type
            val subjectRangeModes: Map<String, Boolean> = try {
                Gson().fromJson(subjectRangeModeJson, subjectRangeModeType) ?: emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }

            for (halfyear in 1..4) {
                sb.appendLine("## Halbjahr $halfyear")

                for (i in subjects.indices) {
                    val subject = subjects[i].trim()
                    if (subject.isEmpty() || subject.startsWith("tu", ignoreCase = true)) continue

                    val teacher = if (i < teachers.size) teachers[i].trim() else ""
                    val oralGrade = oralGradesHistory[subject]?.get(halfyear)?.let { DecimalFormat("0.0").format(it) } ?: ""
                    val ratio = ratios[subject]?.let { "${it.first}:${it.second}" } ?: "50:50"
                    val isPruefungsfach = if (useComplexGrading && pruefungsfaecher[subject] == true) "P" else ""
                    val pruefungsergebnis = if (useComplexGrading && pruefungsergebnisse[subject] != null)
                        DecimalFormat("0.0").format(pruefungsergebnisse[subject]!!) else ""
                    val selectedHalfYearsCount = if (useComplexGrading) selectedHalfYears[subject]?.toString() ?: "1" else "1"
                    val gradeSystem = if (subjectGradeSystems[subject] == false) "D" else "P" // D=Decimal, P=Points
                    val rangeMode = if (subjectRangeModes[subject] == true) "R" else "E" // R=Range, E=Exact

                    sb.appendLine("$subject|$teacher|$oralGrade|$ratio|$isPruefungsfach|$pruefungsergebnis|$selectedHalfYearsCount|$gradeSystem|$rangeMode")
                }
                sb.appendLine()
            }

            val goalGrade = sharedPreferences.getFloat("goal_grade", 0f)
            if (goalGrade > 0) {
                sb.appendLine("# Ziel-Note")
                sb.appendLine("GOAL|${DecimalFormat("0.0").format(goalGrade)}")
                sb.appendLine()
            }

            // export graph
            val historyJson = sharedPreferences.getString("grade_history", "[]")
            val historyType = object : TypeToken<List<GradeHistoryEntry>>() {}.type
            val history: List<GradeHistoryEntry> = try {
                Gson().fromJson(historyJson, historyType) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }

            if (history.isNotEmpty()) {
                sb.appendLine("# Notenverlauf (Graph)")
                sb.appendLine("# Format: Monat|Jahr|Note")
                for (entry in history) {
                    sb.appendLine("GRAPH|${entry.month}|${entry.year}|${DecimalFormat("0.0").format(entry.grade)}")
                }
                sb.appendLine()
            }

            sb.appendLine("# System Settings")
            sb.appendLine("COMPLEX_GRADING|${if (useComplexGrading) "true" else "false"}")
            sb.appendLine("CURRENT_HALFYEAR|${sharedPreferences.getInt("current_halfyear", 1)}")

            sb.toString()
        } catch (e: Exception) {
            L.e(TAG, "Error exporting grade data", e)
            "# Error exporting grade data: ${e.message}"
        }
    }

    fun importGradeData(content: String) {
        try {
            val lines = content.split("\n")
            val oralGradesAllHalfYears = mutableMapOf<String, MutableMap<Int, Double>>()
            val ratios = mutableMapOf<String, Pair<Int, Int>>()
            val pruefungsfaecher = mutableMapOf<String, Boolean>()
            val pruefungsergebnisse = mutableMapOf<String, Double>()
            val selectedHalfYears = mutableMapOf<String, Int>()
            var goalGrade: Float? = null
            val history = mutableListOf<GradeHistoryEntry>()
            var currentHalfyear = 1
            var importedComplexGrading = false
            var importedCurrentHalfyear = 1
            val subjectGradeSystems = mutableMapOf<String, Boolean>()
            val subjectRangeModes = mutableMapOf<String, Boolean>()

            for (line in lines) {
                if (line.startsWith("#") || line.trim().isEmpty()) {
                    if (line.contains("Halbjahr")) {
                        val halfyearMatch = Regex("Halbjahr (\\d+)").find(line)
                        if (halfyearMatch != null) {
                            currentHalfyear = halfyearMatch.groupValues[1].toInt()
                        }
                    }
                    continue
                }

                val parts = line.split("|")
                when {
                    parts[0] == "GOAL" && parts.size >= 2 -> {
                        goalGrade = parts[1].replace(",", ".").toFloatOrNull()
                    }
                    parts[0] == "GRAPH" && parts.size >= 4 -> {
                        val month = parts[1].toIntOrNull()
                        val year = parts[2].toIntOrNull()
                        val grade = parts[3].replace(",", ".").toDoubleOrNull()
                        if (month != null && year != null && grade != null) {
                            history.add(GradeHistoryEntry(month, year, grade))
                        }
                    }
                    parts[0] == "COMPLEX_GRADING" && parts.size >= 2 -> {
                        importedComplexGrading = parts[1] == "true"
                    }
                    parts[0] == "CURRENT_HALFYEAR" && parts.size >= 2 -> {
                        importedCurrentHalfyear = parts[1].toIntOrNull() ?: 1
                    }
                    parts.size >= 9 -> {
                        val subject = parts[0].trim()
                        val oralGradeStr = parts[2].trim()
                        val ratioStr = parts[3].trim()
                        val isPruefungsfachStr = parts[4].trim()
                        val pruefungsergebnisStr = parts[5].trim()
                        val selectedHalfYearsStr = parts[6].trim()
                        val gradeSystemStr = parts[7].trim()
                        val rangeModeStr = parts[8].trim()

                        if (!oralGradesAllHalfYears.containsKey(subject)) {
                            oralGradesAllHalfYears[subject] = mutableMapOf()
                        }

                        if (oralGradeStr.isNotEmpty()) {
                            oralGradesAllHalfYears[subject]!![currentHalfyear] = oralGradeStr.replace(",", ".").toDouble()
                        }

                        if (ratioStr.contains(":")) {
                            val ratioParts = ratioStr.split(":")
                            if (ratioParts.size == 2) {
                                val oral = ratioParts[0].toIntOrNull() ?: 50
                                val written = ratioParts[1].toIntOrNull() ?: 50
                                ratios[subject] = Pair(oral, written)
                            }
                        }

                        if (isPruefungsfachStr == "P") {
                            pruefungsfaecher[subject] = true
                        }

                        if (pruefungsergebnisStr.isNotEmpty()) {
                            pruefungsergebnisse[subject] = pruefungsergebnisStr.replace(",", ".").toDouble()
                        }

                        if (selectedHalfYearsStr.isNotEmpty()) {
                            selectedHalfYears[subject] = selectedHalfYearsStr.toIntOrNull() ?: 1
                        }

                        subjectGradeSystems[subject] = gradeSystemStr != "D" // P=Points (true), D=Decimal (false)
                        subjectRangeModes[subject] = rangeModeStr == "R" // R=Range (true), E=Exact (false)
                    }
                    parts.size >= 4 -> {
                        val subject = parts[0].trim()
                        val oralGradeStr = parts[2].trim()
                        val ratioStr = parts[3].trim()

                        if (!oralGradesAllHalfYears.containsKey(subject)) {
                            oralGradesAllHalfYears[subject] = mutableMapOf()
                        }

                        if (oralGradeStr.isNotEmpty()) {
                            oralGradesAllHalfYears[subject]!![1] = oralGradeStr.replace(",", ".").toDouble()
                        }

                        if (ratioStr.contains(":")) {
                            val ratioParts = ratioStr.split(":")
                            if (ratioParts.size == 2) {
                                val oral = ratioParts[0].toIntOrNull() ?: 50
                                val written = ratioParts[1].toIntOrNull() ?: 50
                                ratios[subject] = Pair(oral, written)
                            }
                        }
                    }
                }
            }
            sharedPreferences.edit {
                putString("oral_grades_history", Gson().toJson(oralGradesAllHalfYears))
                putString("grade_ratios", Gson().toJson(ratios))
                putString("grade_history", Gson().toJson(history))
                putString("pruefungsfaecher", Gson().toJson(pruefungsfaecher))
                putString("pruefungsergebnisse", Gson().toJson(pruefungsergebnisse))
                putString("selected_half_years", Gson().toJson(selectedHalfYears))
                putBoolean("use_simple_grading", !importedComplexGrading)
                putInt("current_halfyear", importedCurrentHalfyear)
                putString("subject_grade_system", Gson().toJson(subjectGradeSystems))
                putString("subject_range_mode", Gson().toJson(subjectRangeModes))

                if (goalGrade != null) {
                    putFloat("goal_grade", goalGrade)
                }

            }

            L.d(TAG, "Grade data imported successfully")

        } catch (e: Exception) {
            L.e(TAG, "Error importing grade data", e)
            throw Exception(context.getString(R.string.backup_grade_import_failed, e.message ?: ""))
        }
    }

    fun generateErrorReport(sections: List<BackupSection>, operation: String): String {
        val timestamp = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMAN).format(Date())
        val sb = StringBuilder()

        sb.appendLine("# Heinrich-Kleyer-Schule App - Fehlerprotokoll")
        sb.appendLine("# Operation: $operation")
        sb.appendLine("# Zeitstempel: $timestamp")
        sb.appendLine("# App Version: $BACKUP_VERSION")
        sb.appendLine("# Android Version: ${android.os.Build.VERSION.RELEASE}")
        sb.appendLine("# Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        sb.appendLine()

        sections.forEach { section ->
            sb.appendLine("## ${section.displayName} (${section.name})")
            sb.appendLine("Status: ${section.status}")
            if (section.errorMessage != null) {
                sb.appendLine("Fehler: ${section.errorMessage}")
            }
            sb.appendLine()
        }

        val failedSections = sections.filter { it.status == SectionStatus.FAILED }
        val emptySections = sections.filter { it.status == SectionStatus.EMPTY }
        val successfulSections = sections.filter { it.status == SectionStatus.SUCCESS }

        sb.appendLine("# Zusammenfassung")
        sb.appendLine("Erfolgreich: ${successfulSections.size}")
        sb.appendLine("Fehlgeschlagen: ${failedSections.size}")
        sb.appendLine("Leer/Nicht gefunden: ${emptySections.size}")

        return sb.toString()
    }

    data class GradeHistoryEntry(
        val month: Int,
        val year: Int,
        var grade: Double
    )

    data class RestoreResult(
        val success: Boolean,
        val restoredSections: Int,
        val totalSections: Int,
        val errors: List<String>
    )

    data class BackupSection(
        val name: String,
        val displayName: String,
        var status: SectionStatus = SectionStatus.PENDING,
        var errorMessage: String? = null
    )

    enum class SectionStatus {
        PENDING,
        IN_PROGRESS,
        SUCCESS,
        FAILED,
        EMPTY,
        EXCLUDED
    }

    interface BackupProgressCallback {
        fun onSectionStarted(section: BackupSection)
        fun onSectionCompleted(section: BackupSection)
        fun onBackupCompleted(success: Boolean, sections: List<BackupSection>)
    }

    private fun notifySectionStarted(section: BackupSection) {
        handler.post {
            progressCallback?.onSectionStarted(section)
        }
    }

    private fun notifySectionCompleted(section: BackupSection) {
        handler.post {
            progressCallback?.onSectionCompleted(section)
        }
    }

    private fun notifyBackupCompleted(success: Boolean, sections: List<BackupSection>) {
        handler.post {
            progressCallback?.onBackupCompleted(success, sections)
        }
    }
}