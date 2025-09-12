package com.thecooker.vertretungsplaner.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.thecooker.vertretungsplaner.L
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.thecooker.vertretungsplaner.MainActivity
import com.thecooker.vertretungsplaner.R
import com.thecooker.vertretungsplaner.ui.slideshow.SlideshowFragment
import java.util.*
import java.util.concurrent.TimeUnit
import androidx.core.content.edit

class HomeworkReminderWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    companion object {
        const val WORK_NAME_DUE_DATE = "homework_due_date_reminder"
        const val WORK_NAME_DAILY = "homework_daily_reminder"
        private const val TAG = "HomeworkReminderWorker"

        private const val CHANNEL_ID_DUE_DATE = "homework_due_date_channel"
        private const val CHANNEL_ID_DAILY = "homework_daily_channel"

        private const val NOTIFICATION_ID_DUE_DATE = 3001
        private const val NOTIFICATION_ID_DAILY = 3002
    }

    override fun doWork(): Result {
        return try {
            val workType = inputData.getString("work_type") ?: "unknown"
            L.d(TAG, "Starting homework reminder work: $workType")

            when (workType) {
                "due_date_reminder" -> handleDueDateReminder()
                "daily_reminder" -> handleDailyReminder()
                else -> {
                    L.w(TAG, "Unknown work type: $workType")
                    Result.failure()
                }
            }
        } catch (e: Exception) {
            L.e(TAG, "Error in homework reminder worker", e)
            Result.failure()
        }
    }

    private fun handleDueDateReminder(): Result {
        val sharedPreferences = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val isDueDateReminderEnabled = sharedPreferences.getBoolean("due_date_reminder_enabled", false)

        if (!isDueDateReminderEnabled) {
            L.d(TAG, "Due date reminders disabled")
            return Result.success()
        }

        val reminderHours = sharedPreferences.getInt("due_date_reminder_hours", 16)
        val homeworkList = SlideshowFragment.getHomeworkList(context)
        val upcomingHomework = getUpcomingHomework(homeworkList, reminderHours)

        if (upcomingHomework.isNotEmpty()) {
            val newHomeworkToNotify = filterAlreadyNotifiedHomework(upcomingHomework, "due_date")
            if (newHomeworkToNotify.isNotEmpty()) {
                showDueDateNotification(newHomeworkToNotify)
                markHomeworkAsNotified(newHomeworkToNotify, "due_date")
            } else {
                L.d(TAG, "All upcoming homework already notified for due date")
            }
        } else {
            L.d(TAG, "No upcoming homework found for due date reminder")
            // Clear old notifications when no homework is due
            clearOldNotifications("due_date")
        }

        return Result.success()
    }

    private fun handleDailyReminder(): Result {
        val sharedPreferences = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val isDailyReminderEnabled = sharedPreferences.getBoolean("daily_homework_reminder_enabled", false)

        if (!isDailyReminderEnabled) {
            L.d(TAG, "Daily homework reminders disabled")
            return Result.success()
        }

        // Only show daily reminder once per day
        val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val lastDailyNotificationDay = sharedPreferences.getInt("last_daily_notification_day", -1)

        if (today == lastDailyNotificationDay) {
            L.d(TAG, "Daily reminder already shown today")
            return Result.success()
        }

        val homeworkList = SlideshowFragment.getHomeworkList(context)
        val uncompletedHomework = homeworkList.filter { !it.isCompleted }

        if (uncompletedHomework.isNotEmpty()) {
            showDailyNotification(uncompletedHomework)
            sharedPreferences.edit { putInt("last_daily_notification_day", today) }
        } else {
            L.d(TAG, "No uncompleted homework found for daily reminder")
        }

        return Result.success()
    }

    private fun getUpcomingHomework(
        homeworkList: List<SlideshowFragment.HomeworkEntry>,
        reminderHours: Int
    ): List<SlideshowFragment.HomeworkEntry> {
        val now = Calendar.getInstance()
        val currentTimeMillis = now.timeInMillis

        return homeworkList.filter { homework ->
            if (homework.isCompleted) return@filter false

            // Check if we should remind about this homework
            val shouldRemind = if (homework.dueTime != null) {
                // For homework with specific due time
                val timeDiffMillis = homework.dueTime!!.time - currentTimeMillis
                val hoursDiff = TimeUnit.MILLISECONDS.toHours(timeDiffMillis)

                // Remind if due within the specified hours and not yet passed
                hoursDiff in 0..reminderHours.toLong()
            } else {
                // For homework without specific due time (due at end of day)
                // Set due time to 23:59 of the due date
                val dueDateEndOfDay = Calendar.getInstance().apply {
                    time = homework.dueDate
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                    set(Calendar.MILLISECOND, 999)
                }.time

                val timeDiffMillis = dueDateEndOfDay.time - currentTimeMillis
                val hoursDiff = TimeUnit.MILLISECONDS.toHours(timeDiffMillis)

                // Remind if due within the specified hours and not yet passed
                hoursDiff in 0..reminderHours.toLong()
            }

            L.d(TAG, "Homework: ${homework.subject} - Should remind: $shouldRemind")
            shouldRemind
        }
    }

    private fun showDueDateNotification(
        homework: List<SlideshowFragment.HomeworkEntry>
    ) {
        createNotificationChannel(CHANNEL_ID_DUE_DATE, context.getString(R.string.hw_notification_channel_due), context.getString(R.string.hw_notification_channel_due_desc))

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("navigate_to_homework", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (homework.size == 1) {
            context.getString(R.string.hw_due_soon_single)
        } else {
            context.getString(R.string.hw_due_soon_multiple, homework.size)
        }

        val content = if (homework.size == 1) {
            val hw = homework.first()
            "${hw.subject}: ${hw.getDueDateString(context)}"
        } else {
            val subjects = homework.take(3).joinToString(", ") { it.subject }
            if (homework.size > 3) context.getString(R.string.hw_subject_and_more, subjects, homework.size - 3) else subjects
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_DUE_DATE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_DUE_DATE, notification)

        L.d(TAG, "Due date notification shown for ${homework.size} homework items")
    }

    private fun showDailyNotification(homework: List<SlideshowFragment.HomeworkEntry>) {
        createNotificationChannel(CHANNEL_ID_DAILY, "Tägliche Hausaufgabenerinnerung", "Tägliche Erinnerung an offene Hausaufgaben")

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("navigate_to_homework", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (homework.size == 1) {
            context.getString(R.string.hw_daily_single)
        } else {
            context.getString(R.string.hw_daily_multiple, homework.size)
        }

        val content = if (homework.size == 1) {
            val hw = homework.first()
            "${hw.subject}: ${hw.getDueDateString(context)}"
        } else {
            val subjects = homework.take(3).joinToString(", ") { it.subject }
            if (homework.size > 3) context.getString(R.string.hw_subject_and_more, subjects, homework.size - 3) else subjects
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_DAILY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_DAILY, notification)

        L.d(TAG, "Daily homework notification shown for ${homework.size} homework items")
    }

    private fun createNotificationChannel(channelId: String, name: String, description: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = if (channelId == CHANNEL_ID_DUE_DATE) {
                NotificationManager.IMPORTANCE_HIGH
            } else {
                NotificationManager.IMPORTANCE_DEFAULT
            }

            val channel = NotificationChannel(channelId, name, importance).apply {
                this.description = description
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun filterAlreadyNotifiedHomework(
        homework: List<SlideshowFragment.HomeworkEntry>,
        notificationType: String
    ): List<SlideshowFragment.HomeworkEntry> {
        val sharedPreferences = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val notifiedHomeworkIds = sharedPreferences.getStringSet("notified_homework_$notificationType", emptySet()) ?: emptySet()

        return homework.filter { hw ->
            val homeworkId = generateHomeworkId(hw)
            !notifiedHomeworkIds.contains(homeworkId)
        }
    }

    private fun markHomeworkAsNotified(
        homework: List<SlideshowFragment.HomeworkEntry>,
        notificationType: String
    ) {
        val sharedPreferences = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val notifiedHomeworkIds = sharedPreferences.getStringSet("notified_homework_$notificationType", emptySet())?.toMutableSet() ?: mutableSetOf()

        homework.forEach { hw ->
            val homeworkId = generateHomeworkId(hw)
            notifiedHomeworkIds.add(homeworkId)
        }

        sharedPreferences.edit {
            putStringSet(
                "notified_homework_$notificationType",
                notifiedHomeworkIds
            )
        }
        L.d(TAG, "Marked ${homework.size} homework items as notified for $notificationType")
    }

    private fun generateHomeworkId(homework: SlideshowFragment.HomeworkEntry): String {
        // Create unique ID based on homework properties that don't change
        // Using only subject and due date since those are the main identifiers
        return "${homework.subject}_${homework.dueDate.time}"
    }

    private fun clearOldNotifications(notificationType: String) {
        val sharedPreferences = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val homeworkList = SlideshowFragment.getHomeworkList(context)
        val currentHomeworkIds = homeworkList.map { generateHomeworkId(it) }.toSet()

        val notifiedHomeworkIds = sharedPreferences.getStringSet("notified_homework_$notificationType", emptySet())?.toMutableSet() ?: mutableSetOf()
        val idsToRemove = notifiedHomeworkIds.filter { id -> !currentHomeworkIds.contains(id) }

        if (idsToRemove.isNotEmpty()) {
            notifiedHomeworkIds.removeAll(idsToRemove.toSet())
            sharedPreferences.edit {
                putStringSet(
                    "notified_homework_$notificationType",
                    notifiedHomeworkIds
                )
            }
            L.d(TAG, "Cleared ${idsToRemove.size} old notification records for $notificationType")
        }
    }
}