package com.thecooker.vertretungsplaner.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.thecooker.vertretungsplaner.R
import com.thecooker.vertretungsplaner.ui.exams.ExamFragment
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ExamShareHelper(private val context: Context) {

    data class SharedExam(
        val type: String = "exam",
        val subject: String,
        val date: String, // iso
        val note: String = "",
        val examNumber: Int? = null,
        val mark: Int? = null,
        val sharedBy: String? = null,
        val sharedDate: String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
    )

    fun shareExam(exam: ExamFragment.ExamEntry, sharedBy: String? = null): Intent? {
        return try {
            val sharedExam = SharedExam(
                subject = exam.subject,
                date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(exam.date),
                note = exam.note,
                examNumber = exam.examNumber,
                mark = exam.mark,
                sharedBy = sharedBy
            )

            val json = Gson().toJson(sharedExam)

            val fileName = "${context.getString(R.string.share_filename_prefix)}_${context.getString(R.string.share_filename_exam)}_${exam.subject.replace(" ", "_")}_${System.currentTimeMillis()}.vpex.txt"
            val file = File(context.cacheDir, fileName)
            file.writeText(json)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"  // Changed to text/plain
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.share_exam_subject_text, exam.subject))
                putExtra(Intent.EXTRA_TEXT, context.getString(R.string.share_exam_message_text, exam.subject))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            Intent.createChooser(shareIntent, context.getString(R.string.share_exam_chooser_title))
        } catch (_: Exception) {
            null
        }
    }

    fun parseSharedExam(uri: Uri): SharedExam? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val content = inputStream?.bufferedReader()?.use { it.readText() }
            inputStream?.close()

            if (content != null) {
                Gson().fromJson(content, SharedExam::class.java)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun convertToExamEntry(sharedExam: SharedExam): ExamFragment.ExamEntry? {
        return try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val date = dateFormat.parse(sharedExam.date) ?: return null

            ExamFragment.ExamEntry(
                subject = sharedExam.subject,
                date = date,
                note = sharedExam.note,
                examNumber = sharedExam.examNumber,
                mark = sharedExam.mark,
                isFromSchedule = false
            )
        } catch (_: Exception) {
            null
        }
    }
}