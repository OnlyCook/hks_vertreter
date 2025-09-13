package com.thecooker.vertretungsplaner.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.thecooker.vertretungsplaner.R
import com.thecooker.vertretungsplaner.ui.slideshow.SlideshowFragment
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class HomeworkShareHelper(private val context: Context) {

    data class SharedHomework(
        val type: String = "homework",
        val subject: String,
        val dueDate: String, // iso
        val dueTime: String? = null, // iso
        val lessonNumber: Int? = null,
        val content: String,
        val checklistItems: List<ChecklistItem> = emptyList(),
        val hasTextContent: Boolean = false,
        val sharedBy: String? = null,
        val sharedDate: String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
    )

    data class ChecklistItem(
        val text: String,
        val isCompleted: Boolean = false
    )

    fun shareHomework(homework: SlideshowFragment.HomeworkEntry, sharedBy: String? = null): Intent? {
        try {
            val sharedHomework = SharedHomework(
                subject = homework.subject,
                dueDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(homework.dueDate),
                dueTime = homework.dueTime?.let {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(it)
                },
                lessonNumber = homework.lessonNumber,
                content = homework.content,
                checklistItems = homework.checklistItems.map {
                    ChecklistItem(it.text, it.isCompleted)
                },
                hasTextContent = homework.hasTextContent,
                sharedBy = sharedBy
            )

            val json = Gson().toJson(sharedHomework)

            val fileName = "${context.getString(R.string.share_filename_prefix)}_${context.getString(R.string.share_filename_homework)}_${homework.subject.replace(" ", "_")}_${System.currentTimeMillis()}.vphw.txt"
            val file = File(context.cacheDir, fileName)
            file.writeText(json)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.vertretungsplaner.homework"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.share_subject_text, homework.subject))
                putExtra(Intent.EXTRA_TEXT, context.getString(R.string.share_message_text, homework.subject))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            return Intent.createChooser(shareIntent, context.getString(R.string.share_chooser_title))
        } catch (_: Exception) {
            return null
        }
    }

    fun parseSharedHomework(uri: Uri): SharedHomework? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val content = inputStream?.bufferedReader()?.use { it.readText() }
            inputStream?.close()

            if (content != null) {
                Gson().fromJson(content, SharedHomework::class.java)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun convertToHomeworkEntry(sharedHomework: SharedHomework): SlideshowFragment.HomeworkEntry? {
        return try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val dueDate = dateFormat.parse(sharedHomework.dueDate) ?: return null
            val dueTime = sharedHomework.dueTime?.let { dateFormat.parse(it) }

            val checklistItems = sharedHomework.checklistItems.map {
                SlideshowFragment.ChecklistItem(it.text, it.isCompleted)
            }.toMutableList()

            SlideshowFragment.HomeworkEntry(
                subject = sharedHomework.subject,
                dueDate = dueDate,
                dueTime = dueTime,
                lessonNumber = sharedHomework.lessonNumber,
                content = sharedHomework.content,
                checklistItems = checklistItems,
                hasTextContent = sharedHomework.hasTextContent
            )
        } catch (_: Exception) {
            null
        }
    }
}