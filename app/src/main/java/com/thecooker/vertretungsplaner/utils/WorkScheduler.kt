package com.thecooker.vertretungsplaner.utils

import android.content.Context
import android.content.SharedPreferences
import com.thecooker.vertretungsplaner.L
import androidx.work.*
import com.thecooker.vertretungsplaner.workers.AutoUpdateWorker
import java.util.*
import java.util.concurrent.TimeUnit
import com.thecooker.vertretungsplaner.workers.HomeworkReminderWorker
import com.thecooker.vertretungsplaner.workers.ExamReminderWorker

object WorkScheduler {
    private const val TAG = "WorkScheduler"

    fun scheduleAutoUpdate(context: Context, sharedPreferences: SharedPreferences) {
        val autoUpdateEnabled = sharedPreferences.getBoolean("auto_update_enabled", false)

        if (!autoUpdateEnabled) {
            L.d(TAG, "Auto update disabled, canceling scheduled work")
            WorkManager.getInstance(context).cancelUniqueWork(AutoUpdateWorker.WORK_NAME)
            return
        }

        val updateTimeString = sharedPreferences.getString("auto_update_time", "06:00") ?: "06:00"
        L.d(TAG, "Scheduling auto update for time: $updateTimeString")

        val updateTime = parseTime(updateTimeString)
        val delayMinutes = calculateDelayToNextExecution(updateTime)

        L.d(TAG, "Next update in $delayMinutes minutes")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (sharedPreferences.getBoolean("update_wifi_only", false)) {
                    NetworkType.UNMETERED // WiFi only
                } else {
                    NetworkType.CONNECTED // Any connection
                }
            )
            .build()

        val workData = workDataOf("work_type" to "update")

        // Use OneTimeWorkRequest with initial delay, then schedule periodic work
        val initialWorkRequest = OneTimeWorkRequest.Builder(AutoUpdateWorker::class.java)
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInputData(workData)
            .addTag("auto_update_initial")
            .build()

        // Schedule the initial work
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "${AutoUpdateWorker.WORK_NAME}_initial",
                ExistingWorkPolicy.REPLACE,
                initialWorkRequest
            )

        // Schedule periodic work (WorkManager has minimum 15 minutes for periodic work)
        val periodicWorkRequest = PeriodicWorkRequest.Builder(
            AutoUpdateWorker::class.java,
            24, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setInputData(workData)
            .addTag("auto_update")
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                AutoUpdateWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                periodicWorkRequest
            )

        L.d(TAG, "Auto update work scheduled successfully")
    }

    fun scheduleChangeNotification(context: Context, sharedPreferences: SharedPreferences) {
        val changeNotificationEnabled = sharedPreferences.getBoolean("change_notification_enabled", false)

        if (!changeNotificationEnabled) {
            L.d(TAG, "Change notification disabled, canceling scheduled work")
            WorkManager.getInstance(context).cancelUniqueWork(AutoUpdateWorker.CHANGE_NOTIFICATION_WORK)
            return
        }

        var intervalMinutes = sharedPreferences.getInt("change_notification_interval", 15)

        // WorkManager requires minimum 15 minutes for periodic work
        if (intervalMinutes < 15) {
            L.w(TAG, "Interval $intervalMinutes minutes is too short, using 15 minutes minimum")
            intervalMinutes = 15
        }

        L.d(TAG, "Scheduling change notification check every $intervalMinutes minutes")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (sharedPreferences.getBoolean("update_wifi_only", false)) {
                    NetworkType.UNMETERED // WiFi only
                } else {
                    NetworkType.CONNECTED // Any connection
                }
            )
            .build()

        val workData = workDataOf("work_type" to "check_changes")

        val workRequest = PeriodicWorkRequest.Builder(
            AutoUpdateWorker::class.java,
            intervalMinutes.toLong(),
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setInputData(workData)
            .addTag("change_notification")
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                AutoUpdateWorker.CHANGE_NOTIFICATION_WORK,
                ExistingPeriodicWorkPolicy.REPLACE,
                workRequest
            )

        L.d(TAG, "Change notification work scheduled successfully")
    }

    fun cancelAutoUpdate(context: Context) {
        L.d(TAG, "Canceling auto update work")
        WorkManager.getInstance(context).cancelUniqueWork(AutoUpdateWorker.WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork("${AutoUpdateWorker.WORK_NAME}_initial")
    }

    fun cancelChangeNotification(context: Context) {
        L.d(TAG, "Canceling change notification work")
        WorkManager.getInstance(context).cancelUniqueWork(AutoUpdateWorker.CHANGE_NOTIFICATION_WORK)
    }

    private fun parseTime(timeString: String): Pair<Int, Int> {
        return try {
            val parts = timeString.split(":")
            Pair(parts[0].toInt(), parts[1].toInt())
        } catch (_: Exception) {
            L.w(TAG, "Failed to parse time: $timeString, using default 06:00")
            Pair(6, 0) // Default to 6:00 AM
        }
    }

    private fun calculateDelayToNextExecution(targetTime: Pair<Int, Int>): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, targetTime.first)
            set(Calendar.MINUTE, targetTime.second)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // If target time has passed today, schedule for tomorrow
        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }

        val delayMillis = target.timeInMillis - now.timeInMillis
        return delayMillis / (1000 * 60) // Convert to minutes
    }

    // homework reminder
    fun scheduleDueDateReminder(context: Context, sharedPreferences: SharedPreferences) {
        val dueDateReminderEnabled = sharedPreferences.getBoolean("due_date_reminder_enabled", false)

        if (!dueDateReminderEnabled) {
            L.d(TAG, "Due date reminder disabled, canceling scheduled work")
            WorkManager.getInstance(context).cancelUniqueWork(HomeworkReminderWorker.WORK_NAME_DUE_DATE)
            return
        }

        L.d(TAG, "Scheduling due date reminder checks")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val workData = workDataOf("work_type" to "due_date_reminder")

        // Check every hour for due date reminders
        val workRequest = PeriodicWorkRequest.Builder(
            HomeworkReminderWorker::class.java,
            1, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setInputData(workData)
            .addTag("due_date_reminder")
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                HomeworkReminderWorker.WORK_NAME_DUE_DATE,
                ExistingPeriodicWorkPolicy.REPLACE,
                workRequest
            )

        L.d(TAG, "Due date reminder work scheduled successfully")
    }

    fun scheduleDailyHomeworkReminder(context: Context, sharedPreferences: SharedPreferences) {
        val dailyReminderEnabled = sharedPreferences.getBoolean("daily_homework_reminder_enabled", false)

        if (!dailyReminderEnabled) {
            L.d(TAG, "Daily homework reminder disabled, canceling scheduled work")
            WorkManager.getInstance(context).cancelUniqueWork(HomeworkReminderWorker.WORK_NAME_DAILY)
            return
        }

        val reminderTimeString = sharedPreferences.getString("daily_homework_reminder_time", "19:00") ?: "19:00"
        L.d(TAG, "Scheduling daily homework reminder for time: $reminderTimeString")

        val reminderTime = parseTime(reminderTimeString)
        val delayMinutes = calculateDelayToNextExecution(reminderTime)

        L.d(TAG, "Next daily homework reminder in $delayMinutes minutes")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val workData = workDataOf("work_type" to "daily_reminder")

        // Initial work request
        val initialWorkRequest = OneTimeWorkRequest.Builder(HomeworkReminderWorker::class.java)
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInputData(workData)
            .addTag("daily_homework_reminder_initial")
            .build()

        // Schedule the initial work
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "${HomeworkReminderWorker.WORK_NAME_DAILY}_initial",
                ExistingWorkPolicy.REPLACE,
                initialWorkRequest
            )

        // Schedule periodic daily work
        val periodicWorkRequest = PeriodicWorkRequest.Builder(
            HomeworkReminderWorker::class.java,
            24, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setInputData(workData)
            .addTag("daily_homework_reminder")
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                HomeworkReminderWorker.WORK_NAME_DAILY,
                ExistingPeriodicWorkPolicy.REPLACE,
                periodicWorkRequest
            )

        L.d(TAG, "Daily homework reminder work scheduled successfully")
    }

    fun cancelDueDateReminder(context: Context) {
        L.d(TAG, "Canceling due date reminder work")
        WorkManager.getInstance(context).cancelUniqueWork(HomeworkReminderWorker.WORK_NAME_DUE_DATE)
    }

    fun cancelDailyHomeworkReminder(context: Context) {
        L.d(TAG, "Canceling daily homework reminder work")
        WorkManager.getInstance(context).cancelUniqueWork(HomeworkReminderWorker.WORK_NAME_DAILY)
        WorkManager.getInstance(context).cancelUniqueWork("${HomeworkReminderWorker.WORK_NAME_DAILY}_initial")
    }

    fun scheduleExamReminder(context: Context, sharedPreferences: SharedPreferences) {
        val examReminderEnabled = sharedPreferences.getBoolean("exam_due_date_reminder_enabled", false)

        if (!examReminderEnabled) {
            L.d(TAG, "Exam reminder disabled, canceling scheduled work")
            WorkManager.getInstance(context).cancelUniqueWork(ExamReminderWorker.WORK_NAME_EXAM_REMINDER)
            return
        }

        L.d(TAG, "Scheduling exam reminder checks")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val workData = workDataOf("work_type" to "exam_reminder")

        // Check every 6 hours for exam reminders (since exams are typically days in advance)
        val workRequest = PeriodicWorkRequest.Builder(
            ExamReminderWorker::class.java,
            6, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setInputData(workData)
            .addTag("exam_reminder")
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                ExamReminderWorker.WORK_NAME_EXAM_REMINDER,
                ExistingPeriodicWorkPolicy.REPLACE,
                workRequest
            )

        L.d(TAG, "Exam reminder work scheduled successfully")
    }

    fun cancelExamReminder(context: Context) {
        L.d(TAG, "Canceling exam reminder work")
        WorkManager.getInstance(context).cancelUniqueWork(ExamReminderWorker.WORK_NAME_EXAM_REMINDER)
    }
}