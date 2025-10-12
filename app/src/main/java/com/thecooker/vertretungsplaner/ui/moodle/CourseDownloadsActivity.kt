package com.thecooker.vertretungsplaner.ui.moodle

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.thecooker.vertretungsplaner.L
import com.thecooker.vertretungsplaner.R
import java.io.File

class CourseDownloadsActivity : AppCompatActivity(), CourseDownloadQueue.QueueListener {

    private lateinit var recyclerView: RecyclerView
    private lateinit var searchEditText: EditText
    private lateinit var btnEditMode: ImageButton
    private lateinit var btnMenuCourseDownloads: Button
    private lateinit var editModeControls: LinearLayout
    private lateinit var btnCancel: Button
    private lateinit var btnDelete: Button
    private lateinit var tvCourseCount: TextView
    private lateinit var tvSelectedCount: TextView
    private lateinit var infoBarLayout: LinearLayout

    private var isEditMode = false
    private var downloadedCourses = mutableListOf<DownloadedCourse>()
    private var filteredCourses = mutableListOf<DownloadedCourse>()
    private lateinit var adapter: CourseDownloadAdapter

    private val downloadsDirectory: File
        get() {
            val baseDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                getExternalFilesDir(null)
            } else {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            }
            return File(baseDir, "HKS_Moodle_Downloads")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_course_downloads)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.moodle_course_downloads)

        initViews()
        setupRecyclerView()
        loadDownloadedCourses()
        setupSearchBar()
        setupMenuButton()

        // Register as listener
        CourseDownloadQueue.getInstance().addListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        CourseDownloadQueue.getInstance().removeListener(this)
    }

    override fun onQueueChanged() {
        runOnUiThread {
            // Reload courses when queue changes (new downloads completed)
            loadDownloadedCourses()
        }
    }

    override fun onEntryStatusChanged(entry: CourseDownloadQueue.DownloadQueueEntry) {
        if (entry.status == "completed") {
            runOnUiThread {
                loadDownloadedCourses()
            }
        }
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.courseDownloadsRecyclerView)
        searchEditText = findViewById(R.id.searchBarCourseDownloads)
        btnEditMode = findViewById(R.id.btnEditMode)
        btnMenuCourseDownloads = findViewById(R.id.btnMenuCourseDownloads)
        editModeControls = findViewById(R.id.editModeControls)
        btnCancel = findViewById(R.id.btnCancel)
        btnDelete = findViewById(R.id.btnDelete)
        tvCourseCount = findViewById(R.id.tvCourseCount)
        tvSelectedCount = findViewById(R.id.tvSelectedCount)
        infoBarLayout = findViewById(R.id.infoBarLayout)

        btnEditMode.setOnClickListener { toggleEditMode() }
        btnCancel.setOnClickListener { toggleEditMode() }
        btnDelete.setOnClickListener { deleteSelectedItems() }
    }

    private fun setupRecyclerView() {
        adapter = CourseDownloadAdapter(
            filteredCourses,
            isEditMode = { isEditMode },
            onCourseClick = { course -> toggleCourseExpansion(course) },
            onSelectionChanged = { updateSelectedCount() }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun setupSearchBar() {
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterCourses(s.toString())
            }
        })
    }

    private fun setupMenuButton() {
        btnMenuCourseDownloads.setOnClickListener {
            showMenuPopup()
        }
    }

    private fun showMenuPopup() {
        val popup = PopupMenu(this, btnMenuCourseDownloads)
        popup.menu.add(0, 1, 0, getString(R.string.moodle_open_downloads_folder))
        popup.menu.add(0, 2, 0, getString(R.string.moodle_share_download_link))
        popup.menu.add(0, 3, 0, getString(R.string.moodle_view_download_queue))
        popup.menu.add(0, 4, 0, getString(R.string.moodle_delete_all_downloads))

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    openDownloadsFolder()
                    true
                }
                2 -> {
                    shareDownloadsFolder()
                    true
                }
                3 -> {
                    showDownloadQueueDialog()
                    true
                }
                4 -> {
                    showDeleteAllDialog()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun openDownloadsFolder() {
        if (!downloadsDirectory.exists()) {
            Toast.makeText(this, getString(R.string.moodle_downloads_folder_not_found), Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            showFolderAccessDialog()
        } else {
            try {
                val intent = Intent(Intent.ACTION_VIEW)
                val uri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    downloadsDirectory
                )
                intent.setDataAndType(uri, "resource/folder")
                intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                startActivity(intent)
            } catch (_: Exception) {
                showFolderAccessDialog()
            }
        }
    }

    private fun showFolderAccessDialog() {
        val path = downloadsDirectory.absolutePath
        val displayPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Android/data/${packageName}/files/HKS_Moodle_Downloads"
        } else {
            "Documents/HKS_Moodle_Downloads"
        }

        val message = """
        ${getString(R.string.moodle_downloads_location)}
        
        $displayPath
        
        ${getString(R.string.moodle_open_with_file_manager_instruction)}
    """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.moodle_downloads_folder))
            .setMessage(message)
            .setPositiveButton(getString(R.string.moodle_copy_path)) { _, _ ->
                copyPathToClipboard(path)
            }
            .setNeutralButton(getString(R.string.moodle_open_files_app)) { _, _ ->
                openFilesApp()
            }
            .setNegativeButton(getString(R.string.moodle_close), null)
            .show()
    }

    private fun copyPathToClipboard(path: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(getString(R.string.moodle_folder_path), path)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.moodle_path_copied), Toast.LENGTH_SHORT).show()
    }

    private fun openFilesApp() {
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.google.android.documentsui")
                ?: packageManager.getLaunchIntentForPackage("com.android.documentsui")
                ?: Intent(Intent.ACTION_VIEW).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }

            startActivity(intent)

            Toast.makeText(
                this,
                getString(R.string.moodle_navigate_to_folder_instruction),
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Exception) {
            Toast.makeText(
                this,
                getString(R.string.moodle_no_file_manager_found),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun shareDownloadsFolder() {
        if (!downloadsDirectory.exists() || downloadsDirectory.listFiles()?.isEmpty() == true) {
            Toast.makeText(this, getString(R.string.moodle_no_files_to_share), Toast.LENGTH_SHORT).show()
            return
        }

        val files = mutableListOf<File>()
        downloadsDirectory.listFiles()?.forEach { courseFolder ->
            if (courseFolder.isDirectory) {
                courseFolder.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        files.add(file)
                    }
                }
            }
        }

        if (files.isEmpty()) {
            Toast.makeText(this, getString(R.string.moodle_no_files_to_share), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uris = ArrayList<android.net.Uri>()
            files.forEach { file ->
                val uri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    file
                )
                uris.add(uri)
            }

            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            startActivity(Intent.createChooser(intent, getString(R.string.moodle_share_files)))
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.moodle_error_sharing_files), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDeleteAllDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.moodle_delete_all_downloads))
            .setMessage(getString(R.string.moodle_delete_all_downloads_confirm))
            .setPositiveButton(getString(R.string.moodle_delete)) { _, _ ->
                deleteAllDownloads()
            }
            .setNegativeButton(getString(R.string.moodle_cancel), null)
            .show()
    }

    private fun deleteAllDownloads() {
        if (downloadsDirectory.exists()) {
            downloadsDirectory.deleteRecursively()
        }
        downloadedCourses.clear()
        filteredCourses.clear()
        adapter.notifyDataSetChanged()
        updateCourseCount()
        Toast.makeText(this, getString(R.string.moodle_all_downloads_deleted), Toast.LENGTH_SHORT).show()
    }

    private fun showDownloadQueueDialog() {
        val queueEntries = CourseDownloadQueue.getInstance().getAllEntries()

        if (queueEntries.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.moodle_download_queue))
                .setMessage(getString(R.string.moodle_download_queue_empty))
                .setPositiveButton(getString(R.string.moodle_close), null)
                .show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_download_queue, null)
        val queueRecyclerView = dialogView.findViewById<RecyclerView>(R.id.queueRecyclerView)

        val queueAdapter = DownloadQueueAdapter(queueEntries.toMutableList())
        queueRecyclerView.layoutManager = LinearLayoutManager(this)
        queueRecyclerView.adapter = queueAdapter

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.moodle_download_queue))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.moodle_close), null)
            .setNeutralButton(getString(R.string.moodle_clear_completed)) { _, _ ->
                CourseDownloadQueue.getInstance().clearCompleted()
                Toast.makeText(this, getString(R.string.moodle_completed_cleared), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.moodle_retry_failed)) { _, _ ->
                CourseDownloadQueue.getInstance().retryFailed()
                Toast.makeText(this, getString(R.string.moodle_retrying_failed), Toast.LENGTH_SHORT).show()
            }
            .create()

        dialog.show()
    }

    private fun toggleEditMode() {
        isEditMode = !isEditMode

        editModeControls.visibility = if (isEditMode) View.VISIBLE else View.GONE
        infoBarLayout.visibility = if (isEditMode) View.VISIBLE else View.GONE

        if (!isEditMode) {
            downloadedCourses.forEach { course ->
                course.isSelected = false
                course.entries.forEach { it.isSelected = false }
            }
        }

        adapter.notifyDataSetChanged()
        updateSelectedCount()
    }

    private fun loadDownloadedCourses() {
        L.d("CourseDownloadsActivity", "Loading downloaded courses from: ${downloadsDirectory.absolutePath}")

        if (!downloadsDirectory.exists()) {
            downloadsDirectory.mkdirs()
            L.d("CourseDownloadsActivity", "Created downloads directory")
        }

        downloadedCourses.clear()

        val courseFolders = downloadsDirectory.listFiles { file -> file.isDirectory }
        L.d("CourseDownloadsActivity", "Found ${courseFolders?.size ?: 0} course folders")

        courseFolders?.forEach { courseFolder ->
            val entries = mutableListOf<DownloadedEntry>()

            courseFolder.listFiles { file -> file.isFile }?.forEach { file ->
                val entry = DownloadedEntry(
                    name = file.nameWithoutExtension,
                    fileName = file.name,
                    path = file.absolutePath,
                    size = file.length(),
                    dateModified = file.lastModified(),
                    mimeType = getMimeType(file.extension)
                )
                entries.add(entry)
                L.d("CourseDownloadsActivity", "  - ${file.name} (${formatFileSize(file.length())})")
            }

            if (entries.isNotEmpty()) {
                val course = DownloadedCourse(
                    name = courseFolder.name,
                    entries = entries,
                    path = courseFolder.absolutePath
                )
                downloadedCourses.add(course)
                L.d("CourseDownloadsActivity", "Added course: ${courseFolder.name} with ${entries.size} entries")
            }
        }

        downloadedCourses.sortByDescending { course ->
            course.entries.maxOfOrNull { it.dateModified } ?: 0L
        }

        filteredCourses = downloadedCourses.toMutableList()
        adapter.updateCourses(filteredCourses)
        updateCourseCount()

        L.d("CourseDownloadsActivity", "Loaded ${downloadedCourses.size} courses total")
    }

    private fun filterCourses(query: String) {
        filteredCourses = if (query.isEmpty()) {
            downloadedCourses.toMutableList()
        } else {
            downloadedCourses.filter { course ->
                course.name.contains(query, ignoreCase = true) ||
                        course.entries.any { entry ->
                            entry.name.contains(query, ignoreCase = true) ||
                                    entry.fileName.contains(query, ignoreCase = true)
                        }
            }.toMutableList()
        }
        adapter.updateCourses(filteredCourses)
    }

    private fun toggleCourseExpansion(course: DownloadedCourse) {
        if (!isEditMode) {
            course.isExpanded = !course.isExpanded
            adapter.notifyDataSetChanged()
        }
    }

    private fun deleteSelectedItems() {
        val selectedCourses = downloadedCourses.filter { it.isSelected }
        val selectedEntries = downloadedCourses.flatMap { course ->
            course.entries.filter { it.isSelected && !course.isSelected }
        }

        if (selectedCourses.isEmpty() && selectedEntries.isEmpty()) {
            Toast.makeText(this, getString(R.string.moodle_nothing_selected), Toast.LENGTH_SHORT).show()
            return
        }

        val message = when {
            selectedCourses.isNotEmpty() && selectedEntries.isEmpty() ->
                getString(R.string.moodle_delete_courses_confirm, selectedCourses.size)
            selectedCourses.isEmpty() && selectedEntries.isNotEmpty() ->
                getString(R.string.moodle_delete_entries_confirm, selectedEntries.size)
            else ->
                getString(R.string.moodle_delete_items_confirm, selectedCourses.size, selectedEntries.size)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.moodle_confirm_deletion))
            .setMessage(message)
            .setPositiveButton(getString(R.string.moodle_delete)) { _, _ ->
                performDeletion(selectedCourses, selectedEntries)
            }
            .setNegativeButton(getString(R.string.moodle_cancel), null)
            .show()
    }

    private fun performDeletion(courses: List<DownloadedCourse>, entries: List<DownloadedEntry>) {
        var deletedCount = 0

        // Delete selected courses
        courses.forEach { course ->
            val folder = File(course.path)
            if (folder.exists() && folder.deleteRecursively()) {
                downloadedCourses.remove(course)
                deletedCount++
            }
        }

        // Delete selected entries
        entries.forEach { entry ->
            val file = File(entry.path)
            if (file.exists() && file.delete()) {
                deletedCount++
            }
        }

        // Remove entries from courses
        downloadedCourses.forEach { course ->
            course.entries.toMutableList().removeAll { it.isSelected }
        }

        // Remove empty courses
        downloadedCourses.removeAll { it.entries.isEmpty() }

        filteredCourses = downloadedCourses.toMutableList()
        adapter.updateCourses(filteredCourses)
        updateCourseCount()

        toggleEditMode()

        Toast.makeText(
            this,
            getString(R.string.moodle_items_deleted, deletedCount),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateCourseCount() {
        tvCourseCount.text = downloadedCourses.size.toString()
    }

    private fun updateSelectedCount() {
        val selectedCourses = downloadedCourses.count { it.isSelected }
        val selectedEntries = downloadedCourses.flatMap { course ->
            course.entries.filter { it.isSelected && !course.isSelected }
        }.size

        tvSelectedCount.text = (selectedCourses + selectedEntries).toString()
    }

    private fun getMimeType(extension: String): String {
        return when (extension.lowercase()) {
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            "wav" -> "audio/wav"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    private fun getDisplayPath(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Android/data/${packageName}/files/HKS_Moodle_Downloads"
        } else {
            "Documents/HKS_Moodle_Downloads"
        }
    }
}