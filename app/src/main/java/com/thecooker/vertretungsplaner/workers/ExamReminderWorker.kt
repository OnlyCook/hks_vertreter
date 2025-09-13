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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.thecooker.vertretungsplaner.MainActivity
import com.thecooker.vertretungsplaner.R
import com.thecooker.vertretungsplaner.ui.exams.ExamFragment
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import androidx.core.content.edit

class ExamReminderWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    companion object {
        const val WORK_NAME_EXAM_REMINDER = "exam_reminder"
        private const val TAG = "ExamReminderWorker"
        private const val CHANNEL_ID_EXAM = "exam_reminder_channel"
        private const val NOTIFICATION_ID_EXAM = 4001
    }

    override fun doWork(): Result {
        return try {
            L.d(TAG, "Starting exam reminder work")
            handleExamReminder()
        } catch (e: Exception) {
            L.e(TAG, "Error in exam reminder worker", e)
            Result.failure()
        }
    }

    private fun handleExamReminder(): Result {
        val sharedPreferences = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val isExamReminderEnabled = sharedPreferences.getBoolean("exam_due_date_reminder_enabled", false)

        if (!isExamReminderEnabled) {
            L.d(TAG, "Exam reminders disabled")
            return Result.success()
        }

        val reminderDays = sharedPreferences.getInt("exam_due_date_reminder_days", 7)
        val examList = getExamList(context)
        val upcomingExams = getUpcomingExams(examList, reminderDays)

        if (upcomingExams.isNotEmpty()) {
            val newExamsToNotify = filterAlreadyNotifiedExams(upcomingExams)
            if (newExamsToNotify.isNotEmpty()) {
                showExamReminderNotification(newExamsToNotify)
                markExamsAsNotified(newExamsToNotify)
            } else {
                L.d(TAG, "All upcoming exams already notified")
            }
        } else {
            L.d(TAG, "No upcoming exams found for reminder")
            // Clear old notifications when no exams are upcoming
            clearOldExamNotifications()
        }

        return Result.success()
    }

    private fun getExamList(context: Context): List<ExamFragment.ExamEntry> {
        val sharedPreferences = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val json = sharedPreferences.getString("exam_list", "[]")
        val type = object : TypeToken<List<ExamFragment.ExamEntry>>() {}.type
        return Gson().fromJson(json, type) ?: emptyList()
    }

    private fun getUpcomingExams(
        examList: List<ExamFragment.ExamEntry>,
        reminderDays: Int
    ): List<ExamFragment.ExamEntry> {
        val now = Calendar.getInstance()
        now.timeInMillis

        return examList.filter { exam ->
            if (exam.isCompleted) return@filter false

            // Calculate days until exam
            val examCalendar = Calendar.getInstance().apply {
                time = exam.date
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            val todayCalendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            val timeDiffMillis = examCalendar.timeInMillis - todayCalendar.timeInMillis
            val daysDiff = TimeUnit.MILLISECONDS.toDays(timeDiffMillis)

            // Remind if exam is within the specified days and not yet passed
            val shouldRemind = daysDiff in 0..reminderDays.toLong()

            L.d(TAG, "Exam: ${exam.subject} on ${SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(exam.date)} - Days until: $daysDiff - Should remind: $shouldRemind")
            shouldRemind
        }
    }

    private fun showExamReminderNotification(
        exams: List<ExamFragment.ExamEntry>
    ) {
        createNotificationChannel()

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("navigate_to_exams", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (exams.size == 1) {
            context.getString(R.string.exam_rem_single_title)
        } else {
            context.getString(R.string.exam_rem_multiple_title, exams.size)
        }

        val content = if (exams.size == 1) {
            val exam = exams.first()
            val daysUntil = exam.getDaysUntilExam()
            val timeText = when (daysUntil) {
                0L -> context.getString(R.string.exam_rem_today)
                1L -> context.getString(R.string.exam_rem_tomorrow)
                else -> context.resources.getQuantityString(R.plurals.exam_rem_days, daysUntil.toInt(), daysUntil.toInt())
            }
            "${exam.subject}: $timeText"
        } else {
            val subjects = exams.take(3).joinToString(", ") { it.subject }
            if (exams.size > 3) context.getString(R.string.exam_rem_multiple_content, subjects, exams.size - 3) else subjects
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_EXAM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_EXAM, notification)

        L.d(TAG, "Exam reminder notification shown for ${exams.size} exam(s)")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_EXAM,
                context.getString(R.string.exam_rem_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.exam_rem_channel_description)
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun filterAlreadyNotifiedExams(exams: List<ExamFragment.ExamEntry>): List<ExamFragment.ExamEntry> {
        val sharedPreferences = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val notifiedExamIds = sharedPreferences.getStringSet("notified_exam_ids", emptySet()) ?: emptySet()

        return exams.filter { exam ->
            val examId = generateExamId(exam)
            !notifiedExamIds.contains(examId)
        }
    }

    private fun markExamsAsNotified(exams: List<ExamFragment.ExamEntry>) {
        val sharedPreferences = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val notifiedExamIds = sharedPreferences.getStringSet("notified_exam_ids", emptySet())?.toMutableSet() ?: mutableSetOf()

        exams.forEach { exam ->
            val examId = generateExamId(exam)
            notifiedExamIds.add(examId)
        }

        sharedPreferences.edit { putStringSet("notified_exam_ids", notifiedExamIds) }
        L.d(TAG, "Marked ${exams.size} exams as notified")
    }

    private fun generateExamId(exam: ExamFragment.ExamEntry): String {
        // Create unique ID based on exam properties that don't change
        // Using only subject and date since those are the main identifiers
        return "${exam.subject}_${exam.date.time}"
    }

    private fun clearOldExamNotifications() {
        val sharedPreferences = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val examList = getExamList(context)
        val currentExamIds = examList.map { generateExamId(it) }.toSet()

        val notifiedExamIds = sharedPreferences.getStringSet("notified_exam_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
        val idsToRemove = notifiedExamIds.filter { id -> !currentExamIds.contains(id) }

        if (idsToRemove.isNotEmpty()) {
            notifiedExamIds.removeAll(idsToRemove.toSet())
            sharedPreferences.edit { putStringSet("notified_exam_ids", notifiedExamIds) }
            L.d(TAG, "Cleared ${idsToRemove.size} old exam notification records")
        }
    }
}