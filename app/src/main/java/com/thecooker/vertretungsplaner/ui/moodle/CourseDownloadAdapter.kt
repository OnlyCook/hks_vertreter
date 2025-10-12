package com.thecooker.vertretungsplaner.ui.moodle

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import com.thecooker.vertretungsplaner.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CourseDownloadAdapter(
    private var courses: MutableList<DownloadedCourse>,
    private val isEditMode: () -> Boolean,
    private val onCourseClick: (DownloadedCourse) -> Unit,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val displayItems = mutableListOf<Any>()

    init {
        rebuildDisplayItems()
    }

    private fun rebuildDisplayItems() {
        displayItems.clear()
        courses.forEach { course ->
            displayItems.add(course)
            if (course.isExpanded) {
                displayItems.addAll(course.entries)
            }
        }
    }

    inner class CourseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val courseName: TextView = itemView.findViewById(R.id.tvCourseName)
        val entryCount: TextView = itemView.findViewById(R.id.tvEntryCount)
        val checkbox: CheckBox = itemView.findViewById(R.id.cbCourse)
        val expandIcon: ImageView = itemView.findViewById(R.id.ivExpandIcon)

        fun bind(course: DownloadedCourse) {
            courseName.text = course.name
            "${course.entries.size} ${itemView.context.getString(R.string.moodle_entries)}".also { entryCount.text = it }

            checkbox.visibility = if (isEditMode()) View.VISIBLE else View.GONE
            checkbox.setOnCheckedChangeListener(null)
            checkbox.isChecked = course.isSelected
            checkbox.setOnCheckedChangeListener { _, isChecked ->
                course.isSelected = isChecked
                onSelectionChanged()
            }

            expandIcon.rotation = if (course.isExpanded) 90f else 0f

            itemView.setOnClickListener {
                if (isEditMode()) {
                    checkbox.isChecked = !checkbox.isChecked
                } else {
                    course.isExpanded = !course.isExpanded
                    rebuildDisplayItems()
                    notifyDataSetChanged()
                }
            }
        }
    }

    inner class EntryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val entryName: TextView = itemView.findViewById(R.id.tvEntryName)
        val entrySize: TextView = itemView.findViewById(R.id.tvEntrySize)
        val entryIcon: ImageView = itemView.findViewById(R.id.ivEntryIcon)
        val btnOpen: ImageButton = itemView.findViewById(R.id.btnOpenEntry)
        val checkbox: CheckBox = itemView.findViewById(R.id.cbEntry)

        fun bind(entry: DownloadedEntry) {
            entryName.text = entry.name
            entrySize.text = formatFileSize(entry.size)

            val iconRes = getIconForMimeType(entry.mimeType)
            entryIcon.setImageResource(iconRes)

            checkbox.visibility = if (isEditMode()) View.VISIBLE else View.GONE
            checkbox.setOnCheckedChangeListener(null)
            checkbox.isChecked = entry.isSelected
            checkbox.setOnCheckedChangeListener { _, isChecked ->
                entry.isSelected = isChecked
                onSelectionChanged()
            }

            btnOpen.setOnClickListener {
                showOpenDialog(itemView.context, entry)
            }

            itemView.setOnClickListener {
                if (isEditMode()) {
                    checkbox.isChecked = !checkbox.isChecked
                } else {
                    showEntryInfo(itemView.context, entry)
                }
            }
        }

        private fun showOpenDialog(context: Context, entry: DownloadedEntry) {
            val options = mutableListOf(
                Pair(context.getString(R.string.moodle_open_with_default), 1),
                Pair(context.getString(R.string.moodle_open_in_moodle_browser), 2),
                Pair(context.getString(R.string.moodle_share), 3)
            )

            if (entry.mimeType.contains("pdf")) {
                options.add(0, Pair(context.getString(R.string.moodle_open_with_pdf_viewer), 0))
            }

            val dialog = AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.moodle_open_with))
                .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                    when (options[which].second) {
                        0 -> openWithPdfViewer(context, entry)
                        1 -> openWithDefault(context, entry)
                        2 -> openInMoodleBrowser(context, entry)
                        3 -> shareFile(context, entry)
                    }
                }
                .setNegativeButton(context.getString(R.string.moodle_cancel), null)
                .show()
        }

        private fun openWithPdfViewer(context: Context, entry: DownloadedEntry) {
            if (!entry.mimeType.contains("pdf")) {
                Toast.makeText(context, context.getString(R.string.moodle_not_a_pdf), Toast.LENGTH_SHORT).show()
                return
            }

            val file = File(entry.path)
            if (!file.exists()) {
                Toast.makeText(context, context.getString(R.string.moodle_file_not_found), Toast.LENGTH_SHORT).show()
                return
            }

            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )

                val intent = Intent(context, MoodleFragment::class.java).apply {
                    action = "open_pdf"
                    putExtra("pdf_uri", uri.toString())
                    putExtra("pdf_path", file.absolutePath)
                }

                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.moodle_error_opening_file), Toast.LENGTH_SHORT).show()
            }
        }

        private fun openWithDefault(context: Context, entry: DownloadedEntry) {
            val file = File(entry.path)
            if (!file.exists()) {
                Toast.makeText(context, context.getString(R.string.moodle_file_not_found), Toast.LENGTH_SHORT).show()
                return
            }

            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, entry.mimeType)
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }

                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.moodle_no_app_found), Toast.LENGTH_SHORT).show()
            }
        }

        private fun openInMoodleBrowser(context: Context, entry: DownloadedEntry) {
            val file = File(entry.path)
            if (!file.exists()) {
                Toast.makeText(context, context.getString(R.string.moodle_file_not_found), Toast.LENGTH_SHORT).show()
                return
            }

            try {
                val intent = Intent(context, context.javaClass.`package`?.name?.let { packageName ->
                    Class.forName("$packageName.MainActivity")
                }).apply {
                    action = "OPEN_FILE_IN_MOODLE"
                    putExtra("file_path", file.absolutePath)
                    putExtra("file_name", entry.fileName)
                    putExtra("mime_type", entry.mimeType)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }

                context.startActivity(intent)
            } catch (_: Exception) {
                openWithDefault(context, entry)
            }
        }

        private fun shareFile(context: Context, entry: DownloadedEntry) {
            val file = File(entry.path)
            if (!file.exists()) {
                Toast.makeText(context, context.getString(R.string.moodle_file_not_found), Toast.LENGTH_SHORT).show()
                return
            }

            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = entry.mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }

                context.startActivity(Intent.createChooser(intent, context.getString(R.string.moodle_share_file)))
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.moodle_error_sharing_file), Toast.LENGTH_SHORT).show()
            }
        }

        private fun showEntryInfo(context: Context, entry: DownloadedEntry) {
            val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            val dateStr = dateFormat.format(Date(entry.dateModified))

            val message = """
                ${context.getString(R.string.moodle_name)}: ${entry.name}
                ${context.getString(R.string.moodle_file_name)}: ${entry.fileName}
                ${context.getString(R.string.moodle_size)}: ${formatFileSize(entry.size)}
                ${context.getString(R.string.moodle_type)}: ${entry.mimeType}
                ${context.getString(R.string.moodle_last_modified)}: $dateStr
            """.trimIndent()

            AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.moodle_entry_info))
                .setMessage(message)
                .setPositiveButton(context.getString(R.string.moodle_close), null)
                .show()
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (displayItems[position]) {
            is DownloadedCourse -> VIEW_TYPE_COURSE
            is DownloadedEntry -> VIEW_TYPE_ENTRY
            else -> throw IllegalArgumentException("Unknown item type")
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_COURSE -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_course_download, parent, false)
                CourseViewHolder(view)
            }
            VIEW_TYPE_ENTRY -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_course_entry_download, parent, false)
                EntryViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown view type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is CourseViewHolder -> holder.bind(displayItems[position] as DownloadedCourse)
            is EntryViewHolder -> holder.bind(displayItems[position] as DownloadedEntry)
        }
    }

    override fun getItemCount() = displayItems.size

    fun updateCourses(newCourses: MutableList<DownloadedCourse>) {
        courses = newCourses
        rebuildDisplayItems()
        notifyDataSetChanged()
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun getIconForMimeType(mimeType: String): Int {
        return when {
            mimeType.contains("pdf") -> R.drawable.ic_entry_pdf
            mimeType.contains("image") -> R.drawable.ic_entry_img
            mimeType.contains("audio") -> R.drawable.ic_entry_audio
            mimeType.contains("word") || mimeType.contains("document") -> R.drawable.ic_entry_doc
            else -> R.drawable.ic_entry_unknown
        }
    }

    companion object {
        private const val VIEW_TYPE_COURSE = 0
        private const val VIEW_TYPE_ENTRY = 1
    }
}