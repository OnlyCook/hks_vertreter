package com.thecooker.vertretungsplaner.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.thecooker.vertretungsplaner.L
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.thecooker.vertretungsplaner.MainActivity
import com.thecooker.vertretungsplaner.R
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray

class AutoUpdateWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    companion object {
        const val TAG = "AutoUpdateWorker"
        const val WORK_NAME = "auto_update_work"
        const val CHANGE_NOTIFICATION_WORK = "change_notification_work"
        const val NOTIFICATION_CHANNEL_UPDATE = "update_channel"
        const val NOTIFICATION_CHANNEL_CHANGES = "changes_channel"
        const val NOTIFICATION_ID_UPDATE = 1001
        const val NOTIFICATION_ID_CHANGES = 1002
    }

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

    override fun doWork(): Result {
        L.d(TAG, "AutoUpdateWorker started")

        val workType = inputData.getString("work_type") ?: "update"

        return try {
            when (workType) {
                "update" -> handleAutoUpdate()
                "check_changes" -> handleChangeNotification()
                else -> Result.failure()
            }
        } catch (e: Exception) {
            L.e(TAG, "Error in background work: ${e.message}", e)
            Result.retry()
        }
    }

    private fun handleAutoUpdate(): Result {
        L.d(TAG, "Handling auto update")

        val autoUpdateEnabled = sharedPreferences.getBoolean("auto_update_enabled", false)
        if (!autoUpdateEnabled) {
            L.d(TAG, "Auto update disabled, skipping")
            return Result.success()
        }

        val wifiOnly = sharedPreferences.getBoolean("update_wifi_only", false)
        if (wifiOnly && !isWifiConnected()) {
            L.d(TAG, "WiFi only mode enabled but not on WiFi, skipping")
            return Result.success()
        }

        if (!isNetworkAvailable()) {
            L.d(TAG, "No network available, retrying later")
            return Result.retry()
        }

        val klasse = sharedPreferences.getString("selected_klasse", "") ?: ""
        if (klasse.isEmpty() || klasse == "Nicht ausgewählt") {
            L.d(TAG, "No class selected, skipping update")
            return Result.success()
        }

        return runBlocking {
            try {
                L.d(TAG, "Fetching substitute plan for class: $klasse")

                val lastUpdate = fetchLastUpdateTime()
                val substitutePlan = fetchSubstitutePlan(klasse)

                // Save to cache
                saveSubstitutePlanToCache(klasse, substitutePlan.toString(), lastUpdate)

                // Show notification if enabled
                val showNotifications =
                    sharedPreferences.getBoolean("show_update_notifications", true)
                if (showNotifications) {
                    showUpdateNotification(lastUpdate)
                }

                L.d(TAG, "Auto update completed successfully")
                Result.success()

            } catch (e: Exception) {
                L.e(TAG, "Error during auto update: ${e.message}", e)
                Result.retry()
            }
        }
    }

    private fun handleChangeNotification(): Result {
        L.d(TAG, "Handling change notification check")

        val changeNotificationEnabled = sharedPreferences.getBoolean("change_notification_enabled", false)
        if (!changeNotificationEnabled) {
            L.d(TAG, "Change notification disabled, skipping")
            return Result.success()
        }

        val wifiOnly = sharedPreferences.getBoolean("update_wifi_only", false)
        if (wifiOnly && !isWifiConnected()) {
            L.d(TAG, "WiFi only mode enabled but not on WiFi, skipping")
            return Result.success()
        }

        if (!isNetworkAvailable()) {
            L.d(TAG, "No network available for change check, retrying later")
            return Result.retry()
        }

        val klasse = sharedPreferences.getString("selected_klasse", "") ?: ""
        if (klasse.isEmpty() || klasse == "Nicht ausgewählt") {
            L.d(TAG, "No class selected, skipping change check")
            return Result.success()
        }

        return runBlocking {
            try {
                L.d(TAG, "Checking for changes in class: $klasse")

                val currentPlan = fetchSubstitutePlan(klasse)
                val hasChanges = detectChanges(klasse, currentPlan)

                if (hasChanges) {
                    L.d(TAG, "Changes detected!")

                    // Update cache with new data
                    val lastUpdate = fetchLastUpdateTime()
                    saveSubstitutePlanToCache(klasse, currentPlan.toString(), lastUpdate)

                    // Show change notification
                    showChangeNotification()
                } else {
                    L.d(TAG, "No changes detected")
                }

                Result.success()

            } catch (e: Exception) {
                L.e(TAG, "Error during change check: ${e.message}", e)
                Result.retry()
            }
        }
    }

    private fun getNewEntries(oldPlan: JSONObject, newPlan: JSONObject): List<SubstituteEntry> {
        val newEntries = mutableListOf<SubstituteEntry>()
        val oldEntries = extractAllEntries(oldPlan)
        val currentEntries = extractAllEntries(newPlan)

        // Only consider entries for today and future dates
        val today = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.time

        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())

        for (entry in currentEntries) {
            try {
                val entryDate = dateFormat.parse(entry.date)
                if (entryDate != null && (entryDate == today || entryDate.after(today))) {
                    if (!oldEntries.contains(entry)) {
                        newEntries.add(entry)
                    }
                }
            } catch (e: Exception) {
                L.w(TAG, "Error parsing date ${entry.date}", e)
            }
        }

        return newEntries
    }

    private fun getNewEntriesForMySubjects(oldPlan: JSONObject, newPlan: JSONObject): List<SubstituteEntry> {
        val studentSubjects = getStudentSubjects()
        if (studentSubjects.isEmpty()) {
            return getNewEntries(oldPlan, newPlan)
        }

        val allNewEntries = getNewEntries(oldPlan, newPlan)
        return allNewEntries.filter { entry ->
            studentSubjects.any { subject ->
                entry.fach.contains(subject, ignoreCase = true)
            }
        }
    }

    private fun extractAllEntries(plan: JSONObject): List<SubstituteEntry> {
        val entries = mutableListOf<SubstituteEntry>()
        val dates = plan.optJSONArray("dates") ?: return entries

        for (i in 0 until dates.length()) {
            val dateObj = dates.getJSONObject(i)
            val date = dateObj.getString("date")
            val entriesArray = dateObj.getJSONArray("entries")

            for (j in 0 until entriesArray.length()) {
                val entry = entriesArray.getJSONObject(j)
                entries.add(
                    SubstituteEntry(
                        date = date,
                        stunde = entry.getInt("stunde"),
                        stundebis = entry.optInt("stundebis", -1),
                        fach = entry.optString("fach", ""),
                        raum = entry.optString("raum", ""),
                        text = entry.optString("text", "")
                    )
                )
            }
        }

        return entries
    }

    private fun saveDetectedChanges(changes: List<SubstituteEntry>) {
        try {
            val changesArray = JSONArray()
            for (change in changes) {
                val changeJson = JSONObject().apply {
                    put("date", change.date)
                    put("stunde", change.stunde)
                    put("stundebis", change.stundebis)
                    put("fach", change.fach)
                    put("raum", change.raum)
                    put("text", change.text)
                }
                changesArray.put(changeJson)
            }

            val changesFile = File(context.cacheDir, "detected_changes.json")
            changesFile.writeText(changesArray.toString())

            L.d(TAG, "Saved ${changes.size} detected changes")
        } catch (e: Exception) {
            L.e(TAG, "Error saving detected changes", e)
        }
    }

    private fun loadDetectedChanges(): List<SubstituteEntry> {
        return try {
            val changesFile = File(context.cacheDir, "detected_changes.json")
            if (!changesFile.exists()) return emptyList()

            val changesData = changesFile.readText()
            val changesArray = JSONArray(changesData)
            val changes = mutableListOf<SubstituteEntry>()

            for (i in 0 until changesArray.length()) {
                val change = changesArray.getJSONObject(i)
                changes.add(
                    SubstituteEntry(
                        date = change.getString("date"),
                        stunde = change.getInt("stunde"),
                        stundebis = change.optInt("stundebis", -1),
                        fach = change.optString("fach", ""),
                        raum = change.optString("raum", ""),
                        text = change.optString("text", "")
                    )
                )
            }

            changes
        } catch (e: Exception) {
            L.e(TAG, "Error loading detected changes", e)
            emptyList()
        }
    }

    private fun createNotificationText(changes: List<SubstituteEntry>): Pair<String, String> {
        if (changes.isEmpty()) {
            return Pair("Vertretungsplan geändert", "Änderungen erkannt!")
        }

        val title = if (changes.size == 1) {
            "1 neue Vertretung"
        } else {
            "${changes.size} neue Vertretungen"
        }

        val message = StringBuilder()
        val maxChangesToShow = 3

        changes.take(maxChangesToShow).forEachIndexed { index, change ->
            if (index > 0) message.append("; ")

            val formattedDate = formatDateForNotification(change.date)
            val stundenText = if (change.stundebis != -1 && change.stundebis != change.stunde) {
                "${change.stunde}.-${change.stundebis}."
            } else {
                "${change.stunde}."
            }

            val changeType = getChangeType(change.text)
            message.append("${change.fach} $changeType am $formattedDate ($stundenText Std.)")
        }

        if (changes.size > maxChangesToShow) {
            message.append(" und ${changes.size - maxChangesToShow} weitere")
        }

        return Pair(title, message.toString())
    }

    private fun formatDateForNotification(dateString: String): String {
        return try {
            val inputFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            val outputFormat = java.text.SimpleDateFormat("EEEE (dd.MM.)", java.util.Locale.GERMAN)
            val date = inputFormat.parse(dateString) ?: return dateString

            val today = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }

            val targetDate = java.util.Calendar.getInstance().apply {
                time = date
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }

            val daysDifference = ((targetDate.timeInMillis - today.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()

            when (daysDifference) {
                0 -> "heute"
                1 -> "morgen"
                2 -> "übermorgen"
                else -> outputFormat.format(date)
            }
        } catch (_: Exception) {
            dateString
        }
    }

    private fun getChangeType(text: String): String {
        return when {
            text.contains("entfällt", ignoreCase = true) -> "entfällt"
            text.contains("vertreten", ignoreCase = true) -> "wird vertreten"
            text.contains("betreut", ignoreCase = true) -> "wird betreut"
            else -> "geändert"
        }
    }

    private fun detectChanges(klasse: String, newPlan: JSONObject): Boolean {
        val changeType = sharedPreferences.getString("change_notification_type", "all_class_subjects") ?: "all_class_subjects"
        val cacheFile = File(context.cacheDir, "substitute_plan_$klasse.json")

        if (!cacheFile.exists()) {
            L.d(TAG, "No cached plan exists, considering this as change")
            return true
        }

        return try {
            val cachedData = cacheFile.readText()
            val cachedPlan = JSONObject(cachedData)

            when (changeType) {
                "all_class_subjects" -> {
                    val newEntries = getNewEntries(cachedPlan, newPlan)
                    if (newEntries.isNotEmpty()) {
                        saveDetectedChanges(newEntries)
                        true
                    } else false
                }
                "my_subjects_only" -> {
                    val newEntries = getNewEntriesForMySubjects(cachedPlan, newPlan)
                    if (newEntries.isNotEmpty()) {
                        saveDetectedChanges(newEntries)
                        true
                    } else false
                }
                else -> false
            }
        } catch (e: Exception) {
            L.e(TAG, "Error comparing plans: ${e.message}", e)
            true // Assume changes if we can't compare
        }
    }

    private fun getStudentSubjects(): List<String> {
        val savedSubjects = sharedPreferences.getString("student_subjects", "") ?: ""
        return if (savedSubjects.isNotEmpty()) {
            savedSubjects.split(",").filter { it.isNotBlank() }
        } else {
            emptyList()
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isWifiConnected(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun fetchLastUpdateTime(): String {
        val url = URL("https://www.heinrich-kleyer-schule.de/aktuelles/vertretungsplan/")
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            val html = connection.inputStream.bufferedReader().use { it.readText() }

            val regex = """<div class="vpstand">Stand: ([^<]+)</div>""".toRegex()
            val matchResult = regex.find(html)
            matchResult?.groups?.get(1)?.value ?: "Unbekannt"
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchSubstitutePlan(klasse: String): JSONObject {
        val url =
            URL("https://www.heinrich-kleyer-schule.de/aktuelles/vertretungsplan/$klasse.json")
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(jsonString)
        } finally {
            connection.disconnect()
        }
    }

    private fun saveSubstitutePlanToCache(klasse: String, jsonData: String, lastUpdate: String) {
        try {
            val cacheFile = File(context.cacheDir, "substitute_plan_$klasse.json")
            val lastUpdateFile = File(context.cacheDir, "last_update_$klasse.txt")

            cacheFile.writeText(jsonData)
            lastUpdateFile.writeText(lastUpdate)

            L.d(TAG, "Saved substitute plan to cache")
        } catch (e: Exception) {
            L.e(TAG, "Error saving to cache", e)
        }
    }

    private fun showUpdateNotification(lastUpdate: String) {
        createNotificationChannels()

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_UPDATE)
            .setSmallIcon(R.drawable.ic_notification) // Make sure you have this icon
            .setContentTitle("Vertretungsplan aktualisiert")
            .setContentText("Stand: $lastUpdate")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_UPDATE, notification)
    }

    private fun showChangeNotification() {
        createNotificationChannels()

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val changes = loadDetectedChanges()
        val (title, message) = createNotificationText(changes)

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_CHANGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_CHANGES, notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Update channel
            val updateChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_UPDATE,
                "Automatische Updates",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Benachrichtigungen über automatische Aktualisierungen"
            }

            // Changes channel
            val changesChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_CHANGES,
                "Änderungen im Vertretungsplan",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Benachrichtigungen über Änderungen im Vertretungsplan"
            }

            notificationManager.createNotificationChannel(updateChannel)
            notificationManager.createNotificationChannel(changesChannel)
        }
    }

    data class SubstituteEntry(
        val date: String,
        val stunde: Int,
        val stundebis: Int,
        val fach: String,
        val raum: String,
        val text: String
    ) {
        // Custom equals to properly compare entries
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SubstituteEntry) return false
            return date == other.date &&
                    stunde == other.stunde &&
                    stundebis == other.stundebis &&
                    fach == other.fach &&
                    raum == other.raum &&
                    text == other.text
        }

        override fun hashCode(): Int {
            return listOf(date, stunde, stundebis, fach, raum, text).hashCode()
        }
    }
}