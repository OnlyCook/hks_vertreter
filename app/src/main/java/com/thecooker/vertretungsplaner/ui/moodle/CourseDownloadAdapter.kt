package com.thecooker.vertretungsplaner.ui.moodle

import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.AttrRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import com.thecooker.vertretungsplaner.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.net.toUri

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
                course.entries.forEach { entry ->
                    displayItems.add(entry)
                    if (entry.isFolder && entry.isExpanded) {
                        displayItems.addAll(entry.folderFiles)
                    }
                }
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
                course.entries.forEach { entry ->
                    entry.isSelected = isChecked
                }
                onSelectionChanged()
                notifyDataSetChanged()
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

            if (entry.isFolder) {
                val folderSize = entry.folderFiles.sumOf { it.size }
                "${entry.folderFiles.size} ${itemView.context.getString(R.string.moodle_files)} | ${formatFileSize(folderSize)}".also { entrySize.text = it }
                entryIcon.setImageResource(R.drawable.ic_entry_folder)

                btnOpen.setImageResource(if (entry.isExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
                btnOpen.setOnClickListener {
                    entry.isExpanded = !entry.isExpanded
                    rebuildDisplayItems()
                    notifyDataSetChanged()
                }
            } else if (entry.isUrlLink) {
                entrySize.text = itemView.context.getString(R.string.moodle_url_link)
                entryIcon.setImageResource(R.drawable.ic_entry_url)

                btnOpen.setImageResource(R.drawable.ic_output)
                btnOpen.setOnClickListener {
                    showUrlLinkDialog(itemView.context, entry)
                }
            } else {
                entrySize.text = formatFileSize(entry.size)
                entryIcon.setImageResource(getIconForMimeType(entry.mimeType))

                btnOpen.setImageResource(R.drawable.ic_output)
                btnOpen.setOnClickListener {
                    showOpenDialog(itemView.context, entry)
                }
            }

            val iconTintColor = itemView.context.getThemeColor(R.attr.iconTintColor)
            entryIcon.setColorFilter(iconTintColor, android.graphics.PorterDuff.Mode.SRC_IN)

            checkbox.visibility = if (isEditMode()) View.VISIBLE else View.GONE
            checkbox.setOnCheckedChangeListener(null)
            checkbox.isChecked = entry.isSelected
            checkbox.setOnCheckedChangeListener { _, isChecked ->
                entry.isSelected = isChecked
                onSelectionChanged()
            }

            itemView.setOnClickListener {
                if (isEditMode()) {
                    checkbox.isChecked = !checkbox.isChecked
                } else {
                    if (entry.isFolder) {
                        entry.isExpanded = !entry.isExpanded
                        rebuildDisplayItems()
                        notifyDataSetChanged()
                    } else {
                        showEntryInfo(itemView.context, entry)
                    }
                }
            }
        }

        private fun Context.getThemeColor(@AttrRes attrRes: Int): Int {
            val typedValue = TypedValue()
            val theme = theme
            theme.resolveAttribute(attrRes, typedValue, true)
            return if (typedValue.resourceId != 0) {
                ContextCompat.getColor(this, typedValue.resourceId)
            } else {
                typedValue.data
            }
        }

        private fun showOpenDialog(context: Context, entry: DownloadedEntry) {
            if (entry.isUrlLink && entry.linkUrl != null) {
                openUrlLink(context, entry)
                return
            }

            data class DialogOption(val text: String, val iconRes: Int, val action: Int)

            val options = mutableListOf(
                DialogOption(context.getString(R.string.moodle_open_with_default), R.drawable.ic_home, 1),
                DialogOption(context.getString(R.string.moodle_open_in_moodle_browser), R.drawable.ic_globe, 2),
                DialogOption(context.getString(R.string.moodle_share), R.drawable.ic_share, 3)
            )

            if (entry.mimeType.contains("pdf")) {
                options.add(0, DialogOption(context.getString(R.string.moodle_open_with_pdf_viewer), R.drawable.ic_pdf_viewer, 0))
            }

            val adapter = object : android.widget.ArrayAdapter<DialogOption>(
                context,
                android.R.layout.simple_list_item_1,
                options
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent) as TextView
                    val option = getItem(position)!!
                    view.text = option.text
                    view.setCompoundDrawablesWithIntrinsicBounds(option.iconRes, 0, 0, 0)
                    view.compoundDrawablePadding = 16
                    return view
                }
            }

            val dialog = AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.moodle_open_with))
                .setAdapter(adapter) { _, which ->
                    when (options[which].action) {
                        0 -> openWithPdfViewer(context, entry)
                        1 -> openWithDefault(context, entry)
                        2 -> openInMoodleBrowser(context, entry)
                        3 -> shareFile(context, entry)
                    }
                }
                .setNegativeButton(context.getString(R.string.moodle_cancel), null)
                .show()

            val buttonColor = context.getThemeColor(R.attr.dialogSectionButtonColor)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        }

        private fun openUrlLink(context: Context, entry: DownloadedEntry) {
            if (entry.linkUrl == null) {
                Toast.makeText(context, context.getString(R.string.moodle_invalid_url), Toast.LENGTH_SHORT).show()
                return
            }

            try {
                val intent = Intent(Intent.ACTION_VIEW, entry.linkUrl.toUri()).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(context, context.getString(R.string.moodle_error_opening_url), Toast.LENGTH_SHORT).show()
            }
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
            } catch (_: Exception) {
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
            } catch (_: Exception) {
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
            } catch (_: Exception) {
                Toast.makeText(context, context.getString(R.string.moodle_error_sharing_file), Toast.LENGTH_SHORT).show()
            }
        }

        private fun showEntryInfo(context: Context, entry: DownloadedEntry) {
            val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            val dateStr = dateFormat.format(Date(entry.dateModified))

            val message = if (entry.isUrlLink && entry.linkUrl != null) {
                """
                ${context.getString(R.string.moodle_name)}: ${entry.name}
                ${context.getString(R.string.moodle_type)}: ${context.getString(R.string.moodle_url_link)}
                ${context.getString(R.string.moodle_url)}: ${entry.linkUrl}
                ${context.getString(R.string.moodle_last_modified)}: $dateStr
            """.trimIndent()
            } else {
                """
                ${context.getString(R.string.moodle_name)}: ${entry.name}
                ${context.getString(R.string.moodle_file_name)}: ${entry.fileName}
                ${context.getString(R.string.moodle_size)}: ${formatFileSize(entry.size)}
                ${context.getString(R.string.moodle_type)}: ${entry.mimeType}
                ${context.getString(R.string.moodle_last_modified)}: $dateStr
            """.trimIndent()
            }

            val dialog = AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.moodle_entry_info))
                .setMessage(message)
                .setPositiveButton(context.getString(R.string.moodle_close), null)
                .show()

            val buttonColor = context.getThemeColor(R.attr.dialogSectionButtonColor)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        }
    }

    inner class FolderFileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val checkbox: CheckBox = itemView.findViewById(R.id.cbEntry)
        val icon: ImageView = itemView.findViewById(R.id.ivEntryIcon)
        val name: TextView = itemView.findViewById(R.id.tvEntryName)
        val size: TextView = itemView.findViewById(R.id.tvEntrySize)
        val btnOpen: ImageButton = itemView.findViewById(R.id.btnOpenEntry)

        fun bind(file: DownloadedEntry) {
            name.text = file.name
            size.text = formatFileSize(file.size)

            val iconRes = getIconForMimeType(file.mimeType)
            icon.setImageResource(iconRes)

            val iconTintColor = itemView.context.getThemeColor(R.attr.iconTintColor)
            icon.setColorFilter(iconTintColor, android.graphics.PorterDuff.Mode.SRC_IN)

            checkbox.visibility = if (isEditMode()) View.VISIBLE else View.GONE
            checkbox.setOnCheckedChangeListener(null)
            checkbox.isChecked = file.isSelected
            checkbox.setOnCheckedChangeListener { _, isChecked ->
                file.isSelected = isChecked
                onSelectionChanged()
            }

            btnOpen.setOnClickListener {
                showOpenDialog(itemView.context, file)
            }

            itemView.setOnClickListener {
                if (isEditMode()) {
                    checkbox.isChecked = !checkbox.isChecked
                } else {
                    showEntryInfo(itemView.context, file)
                }
            }
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
            } catch (_: Exception) {
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
            } catch (_: Exception) {
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
            } catch (_: Exception) {
                Toast.makeText(context, context.getString(R.string.moodle_error_sharing_file), Toast.LENGTH_SHORT).show()
            }
        }

        private fun showOpenDialog(context: Context, entry: DownloadedEntry) {
            if (entry.isUrlLink && entry.linkUrl != null) {
                openUrlLink(context, entry)
                return
            }

            data class DialogOption(val text: String, val iconRes: Int, val action: Int)

            val options = mutableListOf(
                DialogOption(context.getString(R.string.moodle_open_with_default), R.drawable.ic_home, 1),
                DialogOption(context.getString(R.string.moodle_open_in_moodle_browser), R.drawable.ic_globe, 2),
                DialogOption(context.getString(R.string.moodle_share), R.drawable.ic_share, 3)
            )

            if (entry.mimeType.contains("pdf")) {
                options.add(0, DialogOption(context.getString(R.string.moodle_open_with_pdf_viewer), R.drawable.ic_pdf_viewer, 0))
            }

            val adapter = object : android.widget.ArrayAdapter<DialogOption>(
                context,
                android.R.layout.simple_list_item_1,
                options
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent) as TextView
                    val option = getItem(position)!!
                    view.text = option.text
                    view.setCompoundDrawablesWithIntrinsicBounds(option.iconRes, 0, 0, 0)
                    view.compoundDrawablePadding = 16
                    return view
                }
            }

            val dialog = AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.moodle_open_with))
                .setAdapter(adapter) { _, which ->
                    when (options[which].action) {
                        0 -> openWithPdfViewer(context, entry)
                        1 -> openWithDefault(context, entry)
                        2 -> openInMoodleBrowser(context, entry)
                        3 -> shareFile(context, entry)
                    }
                }
                .setNegativeButton(context.getString(R.string.moodle_cancel), null)
                .show()

            val buttonColor = context.getThemeColor(R.attr.dialogSectionButtonColor)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        }

        private fun Context.getThemeColor(@AttrRes attrRes: Int): Int {
            val typedValue = TypedValue()
            val theme = theme
            theme.resolveAttribute(attrRes, typedValue, true)
            return if (typedValue.resourceId != 0) {
                ContextCompat.getColor(this, typedValue.resourceId)
            } else {
                typedValue.data
            }
        }

        private fun showEntryInfo(context: Context, entry: DownloadedEntry) {
            val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            val dateStr = dateFormat.format(Date(entry.dateModified))

            val message = if (entry.isUrlLink && entry.linkUrl != null) {
                """
                ${context.getString(R.string.moodle_name)}: ${entry.name}
                ${context.getString(R.string.moodle_type)}: ${context.getString(R.string.moodle_url_link)}
                ${context.getString(R.string.moodle_url)}: ${entry.linkUrl}
                ${context.getString(R.string.moodle_last_modified)}: $dateStr
            """.trimIndent()
                    } else {
                        """
                ${context.getString(R.string.moodle_name)}: ${entry.name}
                ${context.getString(R.string.moodle_file_name)}: ${entry.fileName}
                ${context.getString(R.string.moodle_size)}: ${formatFileSize(entry.size)}
                ${context.getString(R.string.moodle_type)}: ${entry.mimeType}
                ${context.getString(R.string.moodle_last_modified)}: $dateStr
            """.trimIndent()
            }

            val dialog = AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.moodle_entry_info))
                .setMessage(message)
                .setPositiveButton(context.getString(R.string.moodle_close), null)
                .show()

            val buttonColor = context.getThemeColor(R.attr.dialogSectionButtonColor)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        }
    }

    private fun showUrlLinkDialog(context: Context, entry: DownloadedEntry) {
        if (entry.linkUrl == null) {
            Toast.makeText(context, context.getString(R.string.moodle_invalid_url), Toast.LENGTH_SHORT).show()
            return
        }

        val options = arrayOf(
            context.getString(R.string.moodle_open_in_browser),
            context.getString(R.string.moodle_copy_url),
            context.getString(R.string.moodle_share_url)
        )

        AlertDialog.Builder(context)
            .setTitle(entry.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openUrlLink(context, entry)
                    1 -> copyUrlToClipboard(context, entry.linkUrl)
                    2 -> shareUrl(context, entry.linkUrl)
                }
            }
            .setNegativeButton(context.getString(R.string.moodle_cancel), null)
            .show()
    }

    private fun copyUrlToClipboard(context: Context, url: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText(context.getString(R.string.moodle_url), url)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, context.getString(R.string.moodle_url_copied), Toast.LENGTH_SHORT).show()
    }

    private fun shareUrl(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.moodle_share_url)))
        } catch (_: Exception) {
            Toast.makeText(context, context.getString(R.string.moodle_error_sharing_url), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openUrlLink(context: Context, entry: DownloadedEntry) {
        if (entry.linkUrl == null) {
            Toast.makeText(context, context.getString(R.string.moodle_invalid_url), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW, entry.linkUrl.toUri()).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, context.getString(R.string.moodle_error_opening_url), Toast.LENGTH_SHORT).show()
        }
    }

    override fun getItemViewType(position: Int): Int {
        val item = displayItems[position]
        return when {
            item is DownloadedCourse -> VIEW_TYPE_COURSE
            item is DownloadedEntry && item.isFolder -> VIEW_TYPE_ENTRY
            item is DownloadedEntry -> {
                var isChildOfFolder = false
                for (course in courses) {
                    for (entry in course.entries) {
                        if (entry.isFolder && entry.folderFiles.contains(item)) {
                            isChildOfFolder = true
                            break
                        }
                    }
                    if (isChildOfFolder) break
                }
                if (isChildOfFolder) VIEW_TYPE_FOLDER_FILE else VIEW_TYPE_ENTRY
            }
            else -> VIEW_TYPE_ENTRY
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_COURSE -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_course_download, parent, false)
                CourseViewHolder(view)
            }
            VIEW_TYPE_FOLDER_FILE -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_folder_file_download, parent, false)
                FolderFileViewHolder(view)
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
            is FolderFileViewHolder -> holder.bind(displayItems[position] as DownloadedEntry)
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
            mimeType.contains("text/plain") -> R.drawable.ic_entry_page
            mimeType.contains("word") || mimeType.contains("document") -> R.drawable.ic_entry_doc
            else -> R.drawable.ic_entry_unknown
        }
    }

    companion object {
        private const val VIEW_TYPE_COURSE = 0
        private const val VIEW_TYPE_ENTRY = 1
        private const val VIEW_TYPE_FOLDER_FILE = 2
    }
}