package com.thecooker.vertretungsplaner.ui.moodle

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.AttrRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.thecooker.vertretungsplaner.L
import com.thecooker.vertretungsplaner.R
import java.io.File
import androidx.core.net.toUri
import android.widget.ProgressBar

open class CourseDownloadsActivity : AppCompatActivity(), CourseDownloadQueue.QueueListener {

    private lateinit var tvEmptyState: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var searchEditText: EditText
    private lateinit var btnClearSearch: ImageButton
    private lateinit var btnEditMode: ImageButton
    private lateinit var btnMenuCourseDownloads: Button
    private lateinit var editModeControls: LinearLayout
    private lateinit var btnCancel: Button
    private lateinit var btnDelete: Button
    private lateinit var tvCourseCount: TextView
    private lateinit var infoBarLayout: LinearLayout

    private lateinit var tvEntryCount: TextView
    private lateinit var tvSelectedCourseCount: TextView
    private lateinit var tvTotalCourseCount: TextView
    private lateinit var tvSelectedEntryCount: TextView
    private lateinit var tvTotalEntryCount: TextView

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
        val tempPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val landscapeEnabled = tempPrefs.getBoolean("landscape_mode_enabled", true)
        if (!landscapeEnabled) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_course_downloads)

        setStatusBarStyle()

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        title = getString(R.string.moodle_course_downloads)

        initViews()
        setupRecyclerView()
        loadDownloadedCourses()
        setupSearchBar()
        setupMenuButton()

        CourseDownloadQueue.getInstance().addListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        CourseDownloadQueue.getInstance().removeListener(this)
    }

    override fun onQueueChanged() {
        runOnUiThread {
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
        btnClearSearch = findViewById(R.id.btnClearSearch)
        btnEditMode = findViewById(R.id.btnEditMode)
        btnMenuCourseDownloads = findViewById(R.id.btnMenuCourseDownloads)
        editModeControls = findViewById(R.id.editModeControls)
        btnCancel = findViewById(R.id.btnCancel)
        btnDelete = findViewById(R.id.btnDelete)
        tvCourseCount = findViewById(R.id.tvCourseCount)
        tvEntryCount = findViewById(R.id.tvEntryCount)
        tvSelectedCourseCount = findViewById(R.id.tvSelectedCourseCount)
        tvTotalCourseCount = findViewById(R.id.tvTotalCourseCount)
        tvSelectedEntryCount = findViewById(R.id.tvSelectedEntryCount)
        tvTotalEntryCount = findViewById(R.id.tvTotalEntryCount)
        infoBarLayout = findViewById(R.id.infoBarLayout)
        tvEmptyState = findViewById(R.id.tvEmptyState)

        btnEditMode.setOnClickListener { toggleEditMode() }
        btnCancel.setOnClickListener { toggleEditMode() }
        btnDelete.setOnClickListener { deleteSelectedItems() }
        btnClearSearch.setOnClickListener {
            searchEditText.text.clear()
        }
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
                val query = s.toString()
                btnClearSearch.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                filterCourses(query)
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

        popup.menu.add(0, 1, 0, getString(R.string.moodle_open_downloads_folder)).apply {
            setIcon(R.drawable.ic_folder)
        }
        popup.menu.add(0, 2, 0, getString(R.string.moodle_share_download_link)).apply {
            setIcon(R.drawable.ic_share)
        }
        popup.menu.add(0, 3, 0, getString(R.string.moodle_view_download_queue)).apply {
            setIcon(R.drawable.ic_queue)
        }
        popup.menu.add(0, 4, 0, getString(R.string.moodle_delete_all_downloads)).apply {
            setIcon(R.drawable.ic_trash_can)
        }

        try {
            val fieldMPopup = PopupMenu::class.java.getDeclaredField("mPopup")
            fieldMPopup.isAccessible = true
            val mPopup = fieldMPopup.get(popup)
            mPopup.javaClass
                .getDeclaredMethod("setForceShowIcon", Boolean::class.java)
                .invoke(mPopup, true)
        } catch (_: Exception) {
            // icons wont show but button will work anyways
        }

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

        val dialog = AlertDialog.Builder(this)
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

        val buttonColor = this.getThemeColor(R.attr.dialogSectionButtonColor)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)
    }


    private fun copyPathToClipboard(path: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(getString(R.string.moodle_folder_path), path)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.moodle_path_copied), Toast.LENGTH_SHORT).show()
    }

    private fun openFilesApp() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType("content://com.android.externalstorage.documents/root/primary".toUri(), "vnd.android.document/directory")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)

            Toast.makeText(
                this,
                getString(R.string.moodle_navigate_to_folder_instruction, packageName),
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Exception) {
            try {
                val intent = packageManager.getLaunchIntentForPackage("com.google.android.documentsui")
                    ?: packageManager.getLaunchIntentForPackage("com.android.documentsui")
                if (intent != null) {
                    startActivity(intent)
                    Toast.makeText(
                        this,
                        getString(R.string.moodle_navigate_to_folder_instruction, packageName),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.moodle_no_file_manager_found),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    getString(R.string.moodle_no_file_manager_found),
                    Toast.LENGTH_SHORT
                ).show()
            }
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
                    if (file.isFile && file.name != ".metadata.json") {
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
        val dialog = AlertDialog.Builder(this)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setTitle(getString(R.string.moodle_delete_all_downloads))
            .setMessage(getString(R.string.moodle_delete_all_downloads_confirm))
            .setPositiveButton(getString(R.string.moodle_delete)) { _, _ ->
                deleteAllDownloads()
            }
            .setNegativeButton(getString(R.string.moodle_cancel), null)
            .show()

        val buttonColor = getThemeColor(R.attr.dialogSectionButtonColor)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
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
            val dialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.moodle_download_queue))
                .setMessage(getString(R.string.moodle_download_queue_empty))
                .setPositiveButton(getString(R.string.moodle_close), null)
                .show()

            val buttonColor = getThemeColor(R.attr.dialogSectionButtonColor)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_download_queue, null)
        val queueRecyclerView = dialogView.findViewById<RecyclerView>(R.id.queueRecyclerView)
        val tvQueueSummary = dialogView.findViewById<TextView>(R.id.tvQueueSummary)
        val tvTotalSize = dialogView.findViewById<TextView>(R.id.tvTotalSize)
        val tvDownloadSpeed = dialogView.findViewById<TextView>(R.id.tvDownloadSpeed)
        val tvTimeRemaining = dialogView.findViewById<TextView>(R.id.tvTimeRemaining)
        val progressBarTotal = dialogView.findViewById<ProgressBar>(R.id.progressBarTotal)

        val displayMetrics = resources.displayMetrics
        val maxHeight = (displayMetrics.heightPixels * 0.5).toInt()
        queueRecyclerView.layoutParams.height = maxHeight

        val queueAdapter = DownloadQueueAdapter(
            queueEntries.toMutableList(),
            onCancelClick = { entry ->
                CourseDownloadQueue.getInstance().cancelEntry(entry)
            }
        )
        queueRecyclerView.layoutManager = LinearLayoutManager(this)
        queueRecyclerView.adapter = queueAdapter

        val handler = Handler(Looper.getMainLooper())
        val updateRunnable = object : Runnable {
            override fun run() {
                val stats = CourseDownloadQueue.getInstance().getQueueStats()
                val entries = CourseDownloadQueue.getInstance().getAllEntries()

                queueAdapter.updateEntries(entries)

                tvQueueSummary.text = getString(
                    R.string.moodle_queue_summary_detailed,
                    stats.totalEntries,
                    stats.completedEntries,
                    stats.downloadingEntries,
                    stats.pendingEntries,
                    stats.failedEntries
                )

                val downloadedMB = stats.downloadedBytes / (1024.0 * 1024.0)
                val totalMB = stats.totalBytes / (1024.0 * 1024.0)
                if (stats.totalBytes > 0) {
                    "%.2f MB / %.2f MB".format(downloadedMB, totalMB).also { tvTotalSize.text = it }
                    tvTotalSize.visibility = View.VISIBLE

                    val overallProgress = ((stats.downloadedBytes.toDouble() / stats.totalBytes.toDouble()) * 100).toInt()
                    progressBarTotal.progress = overallProgress
                    progressBarTotal.visibility = View.VISIBLE
                } else {
                    tvTotalSize.visibility = View.GONE
                    progressBarTotal.visibility = View.GONE
                }

                if (stats.totalSpeed > 0) {
                    tvDownloadSpeed.text = formatSpeed(stats.totalSpeed)
                    tvDownloadSpeed.visibility = View.VISIBLE
                } else {
                    tvDownloadSpeed.visibility = View.GONE
                }

                if (stats.estimatedTimeRemaining > 0) {
                    tvTimeRemaining.text = formatTime(stats.estimatedTimeRemaining)
                    tvTimeRemaining.visibility = View.VISIBLE
                } else {
                    tvTimeRemaining.visibility = View.GONE
                }

                handler.postDelayed(this, 500) // update every 500ms
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.moodle_download_queue))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.moodle_close), null)
            .setNeutralButton(getString(R.string.moodle_clear_completed)) { _, _ ->
                CourseDownloadQueue.getInstance().clearCompleted()
            }
            .setNegativeButton(getString(R.string.moodle_cancel_all)) { _, _ ->
                CourseDownloadQueue.getInstance().cancelAll()
                Toast.makeText(this, getString(R.string.moodle_downloads_cancelled), Toast.LENGTH_SHORT).show()
            }
            .setOnDismissListener {
                handler.removeCallbacks(updateRunnable)
            }
            .show()

        handler.post(updateRunnable)

        val buttonColor = getThemeColor(R.attr.dialogSectionButtonColor)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        return when {
            bytesPerSecond >= 1024 * 1024 -> "%.2f MB/s".format(bytesPerSecond / (1024.0 * 1024.0))
            bytesPerSecond >= 1024 -> "%.2f KB/s".format(bytesPerSecond / 1024.0)
            else -> "$bytesPerSecond B/s"
        }
    }

    private fun formatTime(seconds: Long): String {
        return when {
            seconds >= 3600 -> {
                val hours = seconds / 3600
                val minutes = (seconds % 3600) / 60
                "${hours}h ${minutes}m"
            }
            seconds >= 60 -> {
                val minutes = seconds / 60
                val secs = seconds % 60
                "${minutes}m ${secs}s"
            }
            else -> "${seconds}s"
        }
    }

    private fun toggleEditMode() {
        isEditMode = !isEditMode

        editModeControls.visibility = if (isEditMode) View.VISIBLE else View.GONE
        infoBarLayout.visibility = if (isEditMode) View.VISIBLE else View.GONE
        findViewById<LinearLayout>(R.id.courseCountLayout).visibility = if (isEditMode) View.GONE else View.VISIBLE

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

            courseFolder.listFiles()?.forEach { item ->
                if (item.isFile) {
                    // Skip .metadata.json files
                    if (item.name == ".metadata.json") {
                        return@forEach
                    }

                    if (item.extension.lowercase() == "url") {
                        val linkUrl = parseUrlFile(item)
                        if (linkUrl != null) {
                            val entry = DownloadedEntry(
                                name = item.nameWithoutExtension,
                                fileName = item.name,
                                path = item.absolutePath,
                                size = 0L,
                                dateModified = item.lastModified(),
                                mimeType = "text/url",
                                isUrlLink = true,
                                linkUrl = linkUrl
                            )
                            entries.add(entry)
                        }
                    } else {
                        val entry = DownloadedEntry(
                            name = item.nameWithoutExtension,
                            fileName = item.name,
                            path = item.absolutePath,
                            size = item.length(),
                            dateModified = item.lastModified(),
                            mimeType = getMimeType(item.extension),
                            isUrlLink = false,
                            linkUrl = null
                        )
                        entries.add(entry)
                    }
                } else if (item.isDirectory) {
                    val folderFiles = mutableListOf<DownloadedEntry>()
                    var totalSize = 0L
                    var latestModified = 0L

                    item.listFiles { file -> file.isFile }?.forEach { file ->
                        // filter ".metadata.json" files
                        if (file.name == ".metadata.json") {
                            return@forEach
                        }

                        val fileEntry = DownloadedEntry(
                            name = file.nameWithoutExtension,
                            fileName = file.name,
                            path = file.absolutePath,
                            size = file.length(),
                            dateModified = file.lastModified(),
                            mimeType = getMimeType(file.extension),
                            isUrlLink = false,
                            linkUrl = null
                        )
                        folderFiles.add(fileEntry)
                        totalSize += file.length()
                        if (file.lastModified() > latestModified) {
                            latestModified = file.lastModified()
                        }
                    }

                    if (folderFiles.isNotEmpty()) {
                        val folderEntry = DownloadedEntry(
                            name = item.name,
                            fileName = item.name,
                            path = item.absolutePath,
                            size = totalSize,
                            dateModified = latestModified,
                            mimeType = "folder",
                            isFolder = true,
                            folderFiles = folderFiles
                        )
                        entries.add(folderEntry)
                        L.d("CourseDownloadsActivity", "  - ${item.name} (Folder with ${folderFiles.size} files)")
                    }
                }
            }

            if (entries.isNotEmpty()) {
                val course = DownloadedCourse(
                    name = courseFolder.name,
                    entries = entries,
                    path = courseFolder.absolutePath
                )
                downloadedCourses.add(course)
            }
        }

        downloadedCourses.sortByDescending { course ->
            course.entries.maxOfOrNull { it.dateModified } ?: 0L
        }

        filteredCourses = downloadedCourses.toMutableList()
        adapter.updateCourses(filteredCourses)
        updateCourseCount()
    }

    private fun parseUrlFile(file: File): String? {
        return try {
            val content = file.readText(Charsets.UTF_8)
            val match = "URL=(.+)".toRegex().find(content)
            match?.groupValues?.get(1)?.trim()
        } catch (e: Exception) {
            L.e("CourseDownloadsActivity", "Error parsing URL file", e)
            null
        }
    }

    private fun filterCourses(query: String) {
        filteredCourses = if (query.isEmpty()) {
            downloadedCourses.map { course ->
                course.copy(
                    entries = course.entries.toMutableList(),
                    isExpanded = false
                )
            }.toMutableList()
        } else {
            downloadedCourses.mapNotNull { course ->
                val courseNameMatches = course.name.contains(query, ignoreCase = true)

                val matchingEntries = course.entries.mapNotNull { entry ->
                    val entryMatches = entry.name.contains(query, ignoreCase = true) ||
                            entry.fileName.contains(query, ignoreCase = true)

                    if (entry.isFolder) {
                        val matchingFolderFiles = entry.folderFiles.filter { file ->
                            file.name.contains(query, ignoreCase = true) ||
                                    file.fileName.contains(query, ignoreCase = true)
                        }

                        when {
                            entryMatches -> entry.copy(
                                folderFiles = entry.folderFiles.toMutableList(),
                                isExpanded = true
                            )
                            matchingFolderFiles.isNotEmpty() -> entry.copy(
                                folderFiles = matchingFolderFiles.toMutableList(),
                                isExpanded = true
                            )
                            else -> null
                        }
                    } else {
                        if (entryMatches) entry else null
                    }
                }

                when {
                    courseNameMatches -> course.copy(
                        entries = course.entries.toMutableList(),
                        isExpanded = true
                    )
                    matchingEntries.isNotEmpty() -> course.copy(
                        entries = matchingEntries.toMutableList(),
                        isExpanded = true
                    )
                    else -> null
                }
            }.toMutableList()
        }
        adapter.updateCourses(filteredCourses)
        updateCourseCount()
        updateSelectedCount()
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
                resources.getQuantityString(R.plurals.moodle_delete_courses_confirm, selectedCourses.size, selectedCourses.size)
            selectedCourses.isEmpty() ->
                resources.getQuantityString(R.plurals.moodle_delete_entries_confirm, selectedEntries.size, selectedEntries.size)
            else ->
                resources.getQuantityString(R.plurals.moodle_delete_items_confirm, selectedCourses.size + selectedEntries.size, selectedCourses.size, selectedEntries.size)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.moodle_confirm_deletion))
            .setMessage(message)
            .setPositiveButton(getString(R.string.moodle_delete)) { _, _ ->
                performDeletion(selectedCourses, selectedEntries)
            }
            .setNegativeButton(getString(R.string.moodle_cancel), null)
            .show()

        val buttonColor = getThemeColor(R.attr.dialogSectionButtonColor)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
    }

    private fun performDeletion(courses: List<DownloadedCourse>, entries: List<DownloadedEntry>) {
        var deletedCount = 0

        courses.forEach { course ->
            val folder = File(course.path)
            if (folder.exists() && folder.deleteRecursively()) {
                downloadedCourses.remove(course)
                deletedCount++
            }
        }

        entries.forEach { entry ->
            val file = File(entry.path)
            if (file.exists() && file.delete()) {
                deletedCount++
            }
        }

        downloadedCourses.forEach { course ->
            course.entries.toMutableList().removeAll { it.isSelected }
        }

        downloadedCourses.removeAll { it.entries.isEmpty() }

        filteredCourses = downloadedCourses.toMutableList()
        adapter.updateCourses(filteredCourses)
        updateCourseCount()

        toggleEditMode()

        val message = resources.getQuantityString(R.plurals.moodle_items_deleted, deletedCount, deletedCount)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun updateCourseCount() {
        val totalEntries = filteredCourses.flatMap { it.entries }.size
        tvCourseCount.text = filteredCourses.size.toString()
        tvEntryCount.text = totalEntries.toString()

        val isEmpty = filteredCourses.isEmpty()
        tvEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE

        findViewById<LinearLayout>(R.id.courseCountLayout).visibility =
            if (isEmpty || isEditMode) View.GONE else View.VISIBLE

        btnEditMode.isEnabled = !isEmpty
    }

    private fun updateSelectedCount() {
        val selectedCourses = filteredCourses.count { it.isSelected }

        val selectedEntries = filteredCourses.flatMap { course ->
            course.entries.filter { it.isSelected }
        }.size

        val totalEntries = filteredCourses.flatMap { it.entries }.size

        tvSelectedCourseCount.text = selectedCourses.toString()
        tvTotalCourseCount.text = filteredCourses.size.toString()
        tvSelectedEntryCount.text = selectedEntries.toString()
        tvTotalEntryCount.text = totalEntries.toString()
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

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
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

    fun openPdfInMoodleFragment(filePath: String, fileName: String, mimeType: String) {
        L.d("CourseDownloadsActivity", "Opening PDF in Moodle fragment: $fileName")

        val sharedPrefs = getSharedPreferences("moodle_temp", MODE_PRIVATE)
        sharedPrefs.edit {
            putString("open_pdf_path", filePath)
            putString("open_pdf_name", fileName)
            putString("open_pdf_mime", mimeType)
        }

        finish()
    }

    protected fun setStatusBarStyle() {
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)

        val isNightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES

        if (isNightMode) {
            window.statusBarColor = ContextCompat.getColor(this, android.R.color.black)
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        } else {
            window.statusBarColor = ContextCompat.getColor(this, R.color.white)
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        var flags = window.decorView.systemUiVisibility

        if (isNightMode) {
            flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
        } else {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }

        window.decorView.systemUiVisibility = flags
    }
}