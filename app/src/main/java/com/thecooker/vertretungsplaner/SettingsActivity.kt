package com.thecooker.vertretungsplaner

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.parser.PdfTextExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.ClipData
import android.content.ClipboardManager
import java.text.SimpleDateFormat
import java.util.*
import android.widget.LinearLayout
import android.view.View
import com.thecooker.vertretungsplaner.utils.WorkScheduler
import com.thecooker.vertretungsplaner.utils.TimePickerDialogHelper
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.os.Build
import android.provider.Settings
import android.view.ViewGroup
import android.widget.ImageView
import com.thecooker.vertretungsplaner.ui.slideshow.HomeworkUtils
import android.widget.AdapterView
import com.thecooker.vertretungsplaner.utils.BackupManager
import org.json.JSONObject
import kotlin.system.exitProcess
import androidx.core.content.edit
import androidx.core.net.toUri
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import com.thecooker.vertretungsplaner.utils.BackupProgressDialog
import com.thecooker.vertretungsplaner.utils.DialogDismissCallback
import com.thecooker.vertretungsplaner.utils.SectionSelectionCallback
import com.thecooker.vertretungsplaner.utils.SectionSelectionDialog
import android.text.TextWatcher
import android.text.Editable
import android.util.TypedValue
import android.widget.ScrollView
import androidx.core.graphics.toColorInt
import android.widget.CheckBox
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat

class SettingsActivity : BaseActivity() {

    private var isInitializing = true
    private lateinit var btnResetData: Button
    private lateinit var btnScanTimetable: Button
    private lateinit var tvCurrentSelection: TextView
    private lateinit var tvScannedDocument: TextView
    private lateinit var toolbar: Toolbar
    private lateinit var switchFilterSubjects: Switch
    private lateinit var switchAutoDeleteHomework: Switch
    private lateinit var tvAppVersion: TextView

    private var extractedSubjects = mutableListOf<String>()
    private var extractedTeachers = mutableListOf<String>()
    private var extractedRooms = mutableListOf<String>()
    private val knownSubjectRooms = mutableMapOf<Pair<String, String>, String>()

    // app information expand
    private lateinit var layoutAppInfoContent: LinearLayout
    private lateinit var btnAppInfoToggle: Button
    private var isAppInfoExpanded = false

    // search
    data class SearchableView(
        val view: View,
        val text: String,
        val priority: Int = 1 // 1 = normal text, 2 = header/title, 3 = section header
    )
    private lateinit var searchBarSettings: EditText
    private lateinit var settingsContainer: LinearLayout
    private var allSearchableViews = mutableListOf<SearchableView>()
    private var originalBackgroundColors = mutableMapOf<View, Int>()
    private var noResultsTextView: TextView? = null
    private lateinit var btnClearSearch: ImageView

    companion object {
        private const val PDF_PICKER_REQUEST_CODE = 100
        private const val SUBJECT_SELECTION_REQUEST_CODE = 101
        private const val IMPORT_FILE_REQUEST_CODE = 102
        private const val EXPORT_FILE_REQUEST_CODE = 103
        private const val FULL_BACKUP_EXPORT_REQUEST_CODE = 200
        private const val FULL_BACKUP_IMPORT_REQUEST_CODE = 201
        private const val VALID_SCHOOL_IDENTIFIER = "H.-KLEYER-SCHULE"
        private const val VALID_SCHOOL_IDENTIFIER_ALT = "HEINRICH-KLEYER-SCHULE"
        private const val TAG = "PDF_DEBUG"
        private const val HKS_TIMETABLE_FILE_EXTENSION = ".hkst"
    }

    // import/export timetable
    private lateinit var btnTimetableOptions: Button
    private lateinit var btnEditTimetable: Button
    private lateinit var btnExportTimetable: Button
    private lateinit var btnImportTimetable: Button
    private lateinit var layoutTimetableOptions: LinearLayout
    private var isOptionsExpanded = false

    // start up page
    private lateinit var btnStartupPage: Button

    // auto update settings
    private lateinit var switchAutoUpdate: Switch
    private lateinit var btnAutoUpdateTime: Button
    private lateinit var switchUpdateWifiOnly: Switch
    private lateinit var switchShowUpdateNotifications: Switch

    // change notification settings
    private lateinit var switchChangeNotification: Switch
    private lateinit var btnChangeNotificationInterval: Button
    private lateinit var spinnerChangeNotificationType: Spinner

    // Homework reminder
    private lateinit var switchDueDateReminder: Switch
    private lateinit var btnDueDateReminderHours: Button
    private lateinit var switchDailyHomeworkReminder: Switch
    private lateinit var btnDailyReminderTime: Button

    // update cooldown
    private lateinit var switchRemoveUpdateCooldown: Switch

    // exam reminder
    private lateinit var switchExamDueDateReminder: Switch
    private lateinit var btnExamDueDateReminderDays: Button

    // calendar weekend
    private lateinit var switchCalendarWeekend: Switch

    private lateinit var btnExportFullBackup: Button
    private lateinit var btnImportFullBackup: Button
    private lateinit var backupManager: BackupManager

    // filter lift
    private lateinit var switchLeftFilterLift: Switch

    // backup
    private var tempImportContent: String? = null
    private var tempImportSections: List<BackupManager.BackupSection>? = null

    // class advancement
    private lateinit var btnAdvanceClass: Button

    private lateinit var switchCalendarColorLegend: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        val tempPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val landscapeEnabled = tempPrefs.getBoolean("landscape_mode_enabled", true)
        if (!landscapeEnabled) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        isInitializing = true

        initializeViews()
        initializeSearchViews()
        initializeHomeworkReminderViews()
        initializeExamReminderViews()
        setupToolbar()
        setupListeners()
        setupBackPressedCallback()
        loadCurrentSelection()
        updateTimetableButton()
        loadExtractedData()
        setupFilterSwitch()
        setupStartupPageSetting()
        setupAutoUpdateSettings()
        setupChangeNotificationSettings()
        setupNotificationInfoButton()
        setupHomeworkAutoDelete()
        setupAppVersion()
        loadHomeworkReminderSettings()
        loadExamReminderSettings()
        setupEmailButton()
        setupUpdateCooldownSetting()
        setupColorBlindSpinner()
        setupOrientationSetting()
        setupDarkModeSetting()
        setupCalendarSettings()
        setupCalendarWeekendSetting()
        setupCalendarColorLegendSetting()
        setupBackUpManager()
        setupFilterLift()
        setupLanguageSettings()
        setupAppInfoSection()

        isInitializing = false
    }

    private fun initializeSearchViews() {
        searchBarSettings = findViewById(R.id.searchBarSettings)
        btnClearSearch = findViewById(R.id.btnClearSearch)
        settingsContainer = findViewById(R.id.settingsContainer)
        noResultsTextView = findViewById(R.id.tvNoSearchResults)

        setupSearchFunctionality()
    }

    private fun setupSearchFunctionality() {
        settingsContainer.post {
            buildSearchableViewsList()
        }

        btnClearSearch.visibility = View.GONE

        btnClearSearch.setOnClickListener {
            searchBarSettings.text.clear()
            searchBarSettings.clearFocus()
        }

        searchBarSettings.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""

                btnClearSearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE

                performSearch(query)

                if (query.isNotEmpty()) {
                    val matches = findMatches(query)
                    if (matches.isNotEmpty()) {
                        currentMatches = matches
                        currentMatchIndex = 0
                        scrollToMatch(currentMatchIndex)
                    }
                }
            }
        })

        searchBarSettings.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val query = searchBarSettings.text.toString().trim()
                if (query.isNotEmpty() && currentMatches.isNotEmpty()) {
                    currentMatchIndex = (currentMatchIndex + 1) % currentMatches.size
                    scrollToMatch(currentMatchIndex)
                }
                searchBarSettings.clearFocus()
                searchBarSettings.requestFocus()
                true
            } else {
                false
            }
        }
    }

    private fun scrollToMatch(matchIndex: Int) {
        if (currentMatches.isEmpty() || matchIndex >= currentMatches.size) return

        val targetView = currentMatches[matchIndex].first.view
        val scrollView = findViewById<ScrollView>(R.id.scrollViewSettings)

        val location = IntArray(2)
        targetView.getLocationInWindow(location)
        val scrollViewLocation = IntArray(2)
        scrollView.getLocationInWindow(scrollViewLocation)

        val relativeY = location[1] - scrollViewLocation[1]
        val scrollY = scrollView.scrollY + relativeY - (scrollView.height / 2)

        scrollView.smoothScrollTo(0, maxOf(0, scrollY))

        targetView.animate()
            .scaleX(1.05f)
            .scaleY(1.05f)
            .setDuration(200)
            .withEndAction {
                targetView.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(200)
                    .start()
            }
            .start()
    }

    private fun buildSearchableViewsList() {
        allSearchableViews.clear()
        originalBackgroundColors.clear()

        searchInViewGroup(settingsContainer, allSearchableViews)

        L.d(TAG, "Built searchable views list with ${allSearchableViews.size} items")
    }

    private fun searchInViewGroup(viewGroup: ViewGroup, searchableViews: MutableList<SearchableView>) {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)

            when (child) {
                is TextView -> {
                    if (child.id == R.id.btnAppInfoToggle) {
                        continue
                    }

                    val text = child.text?.toString()?.trim()
                    if (!text.isNullOrEmpty() && !isHintText(child)) {
                        val priority = when {
                            child.textSize >= 18f -> 3 // section headers
                            child.textSize >= 16f -> 2 // subsection headers
                            else -> 1 // normal text
                        }
                        searchableViews.add(SearchableView(child, text, priority))

                        try {
                            val background = child.background
                            if (background is android.graphics.drawable.ColorDrawable) {
                                originalBackgroundColors[child] = background.color
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
                is Spinner -> {
                    try {
                        val selectedItem = child.selectedItem?.toString()?.trim()
                        if (!selectedItem.isNullOrEmpty()) {
                            searchableViews.add(SearchableView(child, selectedItem, 1))
                            storeOriginalBackground(child)
                        }
                    } catch (_: Exception) {
                    }
                }
                is ViewGroup -> {
                    searchInViewGroup(child, searchableViews)
                }
            }
        }
    }

    private fun isHintText(textView: TextView): Boolean {
        val text = textView.text?.toString() ?: ""

        val hintTexts = listOf(
            getString(R.string.act_set_change_hint),
            getString(R.string.act_set_filter_available_hint),
            getString(R.string.act_set_filter_position_hint)
        )

        return hintTexts.any { it.equals(text, ignoreCase = true) }
    }

    private fun setupBackPressedCallback() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }

    private fun initializeViews() {
        toolbar = findViewById(R.id.toolbar)
        btnResetData = findViewById(R.id.btnResetData)
        btnScanTimetable = findViewById(R.id.btnScanTimetable)
        tvCurrentSelection = findViewById(R.id.tvCurrentSelection)
        tvScannedDocument = findViewById(R.id.tvScannedDocument)
        switchFilterSubjects = findViewById(R.id.switchFilterSubjects)
        btnTimetableOptions = findViewById(R.id.btnTimetableOptions)

        btnEditTimetable = findViewById(R.id.btnEditTimetable)
        btnExportTimetable = findViewById(R.id.btnExportTimetable)
        btnImportTimetable = findViewById(R.id.btnImportTimetable)

        btnEditTimetable.apply {
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_pencil, 0, 0, 0)
            compoundDrawablePadding = 32
        }

        btnExportTimetable.apply {
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_export, 0, 0, 0)
            compoundDrawablePadding = 32
        }

        btnImportTimetable.apply {
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_import, 0, 0, 0)
            compoundDrawablePadding = 32
        }

        layoutTimetableOptions = findViewById(R.id.layoutTimetableOptions)
        btnStartupPage = findViewById(R.id.btnStartupPage)
        switchAutoUpdate = findViewById(R.id.switchAutoUpdate)
        btnAutoUpdateTime = findViewById(R.id.btnAutoUpdateTime)
        switchUpdateWifiOnly = findViewById(R.id.switchUpdateWifiOnly)
        switchShowUpdateNotifications = findViewById(R.id.switchShowUpdateNotifications)
        switchChangeNotification = findViewById(R.id.switchChangeNotification)
        btnChangeNotificationInterval = findViewById(R.id.btnChangeNotificationInterval)
        spinnerChangeNotificationType = findViewById(R.id.spinnerChangeNotificationType)
        switchRemoveUpdateCooldown = findViewById(R.id.switchRemoveUpdateCooldown)
        switchCalendarWeekend = findViewById(R.id.switchCalendarWeekend)
        btnExportFullBackup = findViewById(R.id.btnExportFullBackup)
        btnImportFullBackup = findViewById(R.id.btnImportFullBackup)
        switchLeftFilterLift = findViewById(R.id.switchLeftFilterLift)
        btnAdvanceClass = findViewById(R.id.btnAdvanceClass)
        layoutAppInfoContent = findViewById(R.id.layoutAppInfoContent)
        btnAppInfoToggle = findViewById(R.id.btnAppInfoToggle)
        switchCalendarColorLegend = findViewById(R.id.switchCalendarColorLegend)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = getString(R.string.set_act_title)
        }
    }

    private fun setupListeners() {
        btnAppInfoToggle.setOnClickListener {
            toggleAppInfoSection()
        }

        btnResetData.setOnClickListener {
            showResetConfirmationDialog()
        }

        btnScanTimetable.setOnClickListener {
            if (isDocumentScanned()) {
                showDeleteTimetableDialog()
            } else {
                openPdfPicker()
            }
        }

        btnTimetableOptions.setOnClickListener {
            toggleTimetableOptions()
        }

        btnEditTimetable.setOnClickListener {
            editTimetable()
        }

        btnExportTimetable.setOnClickListener {
            exportTimetable()
        }

        btnImportTimetable.setOnClickListener {
            showImportOptions()
        }

        switchFilterSubjects.setOnCheckedChangeListener { _, isChecked ->
            if (!isInitializing) {
                sharedPreferences.edit {
                    putBoolean("filter_only_my_subjects", isChecked)
                }

                switchLeftFilterLift.isEnabled = isChecked

                L.d(TAG, "Subject filtering ${if (isChecked) "enabled" else "disabled"}")
                Toast.makeText(this,
                    if (isChecked) getString(R.string.set_act_subject_filter_enabled)
                    else getString(R.string.set_act_subject_filter_disabled),
                    Toast.LENGTH_SHORT).show()
            } else {
                sharedPreferences.edit {
                    putBoolean("filter_only_my_subjects", isChecked)
                    }

                switchLeftFilterLift.isEnabled = isChecked
            }
        }

        switchRemoveUpdateCooldown.setOnCheckedChangeListener { _, isChecked ->
            if (!isInitializing) {
                sharedPreferences.edit {
                    putBoolean("remove_update_cooldown", isChecked)
                }

                Toast.makeText(this,
                    if (!isChecked) getString(R.string.set_act_update_cooldown_disabled)
                    else getString(R.string.set_act_update_cooldown_enabled),
                    Toast.LENGTH_SHORT).show()
            } else {
                sharedPreferences.edit {
                    putBoolean("remove_update_cooldown", isChecked)
                }
            }
        }

        btnAdvanceClass.setOnClickListener {
            showClassAdvancementDialog()
        }

        setupHomeworkReminderListeners()
    }

    private fun setupFilterLift() {
        switchLeftFilterLift.isChecked = sharedPreferences.getBoolean("left_filter_lift", false)

        switchLeftFilterLift.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit {
                putBoolean("left_filter_lift", isChecked)
            }
        }
    }

    private fun setupFilterSwitch() {
        val hasScannedDocument = isDocumentScanned()

        switchFilterSubjects.isEnabled = hasScannedDocument

        switchFilterSubjects.isChecked = if (hasScannedDocument) {
            sharedPreferences.getBoolean("filter_only_my_subjects", false)
        } else {
            false
        }

        switchLeftFilterLift.isEnabled = switchFilterSubjects.isChecked

        updateFilterSwitchAppearance(hasScannedDocument)
    }

    private fun updateFilterSwitchAppearance(hasScannedDocument: Boolean) {
        if (hasScannedDocument) {
            switchFilterSubjects.alpha = 1.0f
        } else {
            switchFilterSubjects.alpha = 0.5f
            switchFilterSubjects.isChecked = false
            sharedPreferences.edit {
                putBoolean("filter_only_my_subjects", false)
            }
        }
    }

    private fun loadCurrentSelection() {
        val bildungsgang = sharedPreferences.getString("selected_bildungsgang", getString(R.string.set_act_not_selected))
        val klasse = sharedPreferences.getString("selected_klasse", getString(R.string.set_act_not_selected))

        val selectionText = getString(R.string.set_act_current_selection_format, bildungsgang, klasse)
        tvCurrentSelection.text = selectionText
    }

    private fun loadExtractedData() {
        // subjects
        val savedSubjects = sharedPreferences.getString("all_extracted_subjects", "")
        if (!savedSubjects.isNullOrEmpty()) {
            extractedSubjects.clear()
            extractedSubjects.addAll(savedSubjects.split(",").filter { it.isNotBlank() })
        }

        // teachers
        val savedTeachers = sharedPreferences.getString("all_extracted_teachers", "")
        if (!savedTeachers.isNullOrEmpty()) {
            extractedTeachers.clear()
            extractedTeachers.addAll(savedTeachers.split(",").filter { it.isNotBlank() })
        }

        // rooms
        val savedRooms = sharedPreferences.getString("all_extracted_rooms", "")
        if (!savedRooms.isNullOrEmpty()) {
            extractedRooms.clear()
            extractedRooms.addAll(savedRooms.split(",").filter { it.isNotBlank() })
        }

        // fix known subject room combos
        knownSubjectRooms.clear()
        if (extractedSubjects.size == extractedTeachers.size && extractedSubjects.size == extractedRooms.size) {
            for (i in extractedSubjects.indices) {
                val key = Pair(extractedSubjects[i], extractedTeachers[i])
                knownSubjectRooms[key] = extractedRooms[i]
            }
            L.d(TAG, "Rebuilt ${knownSubjectRooms.size} known subject-teacher-room combinations")
        }

        L.d(TAG, "Loaded ${extractedSubjects.size} subjects, ${extractedTeachers.size} teachers, ${extractedRooms.size} rooms from preferences")
    }

    private fun saveSelectedSubjectsWithData(selectedSubjects: List<String>) {
        L.d(TAG, "=== DEBUG: saveSelectedSubjectsWithData called ===")
        L.d(TAG, "Selected subjects: $selectedSubjects (${selectedSubjects.size})")
        L.d(TAG, "Available extracted subjects: $extractedSubjects (${extractedSubjects.size})")
        L.d(TAG, "Available extracted teachers: $extractedTeachers (${extractedTeachers.size})")
        L.d(TAG, "Available extracted rooms: $extractedRooms (${extractedRooms.size})")

        val selectedIndices = selectedSubjects.mapNotNull { subject ->
            val index = extractedSubjects.indexOf(subject)
            L.d(TAG, "Subject '$subject' found at index: $index")
            if (index >= 0) index else null
        }

        L.d(TAG, "Selected indices: $selectedIndices")

        val selectedTeachers = selectedIndices.map {
            val teacher = if (it < extractedTeachers.size) extractedTeachers[it] else "UNKNOWN"
            L.d(TAG, "Index $it -> Teacher: $teacher")
            teacher
        }
        val selectedRooms = selectedIndices.map {
            val room = if (it < extractedRooms.size) extractedRooms[it] else "UNKNOWN"
            L.d(TAG, "Index $it -> Room: $room")
            room
        }

        L.d(TAG, "Final selected teachers: $selectedTeachers")
        L.d(TAG, "Final selected rooms: $selectedRooms")

        val editor = sharedPreferences.edit()
        editor.putBoolean("has_scanned_document", true)
        editor.putString("student_subjects", selectedSubjects.joinToString(","))
        editor.putString("student_teachers", selectedTeachers.joinToString(","))
        editor.putString("student_rooms", selectedRooms.joinToString(","))

        L.d(TAG, "Saving all extracted data:")
        L.d(TAG, "  all_extracted_subjects: ${extractedSubjects.joinToString(",")}")
        L.d(TAG, "  all_extracted_teachers: ${extractedTeachers.joinToString(",")}")
        L.d(TAG, "  all_extracted_rooms: ${extractedRooms.joinToString(",")}")

        editor.putString("all_extracted_subjects", extractedSubjects.joinToString(","))
        editor.putString("all_extracted_teachers", extractedTeachers.joinToString(","))
        editor.putString("all_extracted_rooms", extractedRooms.joinToString(","))

        val klasse = sharedPreferences.getString("selected_klasse", "")
        editor.putString("scanned_document_info", klasse)

        val success = editor.commit() // commit instead apply (i died here)
        L.d(TAG, "SharedPreferences save success: $success")

        val verifySubjects = sharedPreferences.getString("all_extracted_subjects", "")
        val verifyTeachers = sharedPreferences.getString("all_extracted_teachers", "")
        val verifyRooms = sharedPreferences.getString("all_extracted_rooms", "")

        L.d(TAG, "=== VERIFICATION ===")
        L.d(TAG, "Saved all_extracted_subjects: '$verifySubjects'")
        L.d(TAG, "Saved all_extracted_teachers: '$verifyTeachers'")
        L.d(TAG, "Saved all_extracted_rooms: '$verifyRooms'")

        updateTimetableButton()
        val message = getString(R.string.set_act_timetable_save_success_format, selectedSubjects.size)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

        L.d(TAG, "Selected data saved successfully:")
        for (i in selectedSubjects.indices) {
            val teacher = if (i < selectedTeachers.size) selectedTeachers[i] else "UNKNOWN"
            val room = if (i < selectedRooms.size) selectedRooms[i] else "UNKNOWN"
            L.d(TAG, "  ${selectedSubjects[i]} -> $teacher -> $room")
        }
    }

    private fun updateDeleteTimetableData() {
        sharedPreferences.edit {
            remove("has_scanned_document")
            remove("scanned_document_info")
            remove("student_subjects")
            remove("student_teachers")
            remove("student_rooms")
            remove("all_extracted_subjects")
            remove("all_extracted_teachers")
            remove("all_extracted_rooms")
            remove("filter_only_my_subjects")
        }

        extractedSubjects.clear()
        extractedTeachers.clear()
        extractedRooms.clear()
        knownSubjectRooms.clear()
        updateTimetableButton()
        Toast.makeText(this, getString(R.string.set_act_timetable_deleted), Toast.LENGTH_SHORT).show()
    }

    private fun updateTimetableButton() {
        if (isDocumentScanned()) {
            btnScanTimetable.text = getString(R.string.set_act_delete_timetable_btn)
            val documentInfo = sharedPreferences.getString("scanned_document_info", "")
            val selectedSubjects = sharedPreferences.getString("student_subjects", "")?.split(",") ?: emptyList()
            val alternativeRooms = sharedPreferences.getString("alternative_rooms", "")
            val subjectInfo = if (selectedSubjects.isNotEmpty() && selectedSubjects.first().isNotBlank()) {
                val subjectCount = if (!alternativeRooms.isNullOrBlank()) JSONObject(
                    alternativeRooms
                ).length() else 0
                if (subjectCount == 0) {
                    getString(R.string.set_act_selected_subjects_format, selectedSubjects.size)
                }
                else {
                    getString(R.string.set_act_selected_subjects_format_with_alt, selectedSubjects.size, subjectCount)
                }
            } else {
                getString(R.string.set_act_no_subjects_selected)
            }
            val fullText = getString(R.string.set_act_scanned_document_format, "${getString(R.string.set_act_this_class)} $documentInfo", subjectInfo)
            tvScannedDocument.text = fullText
            tvScannedDocument.visibility = TextView.VISIBLE
        } else {
            btnScanTimetable.text = getString(R.string.set_act_scan_timetable)
            tvScannedDocument.visibility = TextView.GONE
        }

        if (::spinnerChangeNotificationType.isInitialized) {
            val currentType = sharedPreferences.getString("change_notification_type", "all_class_subjects") ?: "all_class_subjects"
            setupChangeTypeSpinner(currentType)
            updateChangeNotificationUI(switchChangeNotification.isChecked)
        }

        setupFilterSwitch()
        checkButtonAvailability()
    }

    private fun isDocumentScanned(): Boolean {
        return sharedPreferences.getBoolean("has_scanned_document", false)
    }

    private fun openPdfPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "application/pdf"
            addCategory(Intent.CATEGORY_OPENABLE)
        }

        try {
            startActivityForResult(
                Intent.createChooser(intent, getString(R.string.set_act_choose_pdf_file)),
                PDF_PICKER_REQUEST_CODE
            )
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.set_act_no_pdf_app), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            PDF_PICKER_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) {
                    data?.data?.let { uri ->
                        processPdfFile(uri)
                    }
                }
            }
            SUBJECT_SELECTION_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) {
                    val selectedSubjects = data?.getStringArrayExtra(SubjectSelectionActivity.RESULT_SELECTED_SUBJECTS)?.toList()

                    val updatedSubjects = data?.getStringArrayExtra(SubjectSelectionActivity.EXTRA_SUBJECTS)?.toList()
                    val updatedTeachers = data?.getStringArrayExtra(SubjectSelectionActivity.EXTRA_TEACHERS)?.toList()
                    val updatedRooms = data?.getStringArrayExtra(SubjectSelectionActivity.EXTRA_ROOMS)?.toList()

                    if (!selectedSubjects.isNullOrEmpty()) {
                        L.d(TAG, "Received ${selectedSubjects.size} selected subjects from SubjectSelectionActivity")

                        if (!updatedSubjects.isNullOrEmpty() &&
                            !updatedTeachers.isNullOrEmpty() &&
                            !updatedRooms.isNullOrEmpty()) {

                            L.d(TAG, "Updating local arrays with modified data from SubjectSelectionActivity")
                            extractedSubjects.clear()
                            extractedSubjects.addAll(updatedSubjects)
                            extractedTeachers.clear()
                            extractedTeachers.addAll(updatedTeachers)
                            extractedRooms.clear()
                            extractedRooms.addAll(updatedRooms)

                            knownSubjectRooms.clear()
                            for (i in extractedSubjects.indices) {
                                if (i < extractedTeachers.size && i < extractedRooms.size) {
                                    val key = Pair(extractedSubjects[i], extractedTeachers[i])
                                    knownSubjectRooms[key] = extractedRooms[i]
                                }
                            }
                        }

                        saveSelectedSubjectsWithData(selectedSubjects)
                    }
                } else {
                    L.d(TAG, "Subject selection was cancelled")
                }
            }
            FULL_BACKUP_EXPORT_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) {
                    data?.data?.let { uri ->
                        writeFullBackupToFileUri(uri)
                    }
                }
            }
            FULL_BACKUP_IMPORT_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) {
                    data?.data?.let { uri ->
                        readFullBackupFromFileUri(uri)
                    }
                }
            }
        }
    }

    private fun processPdfFile(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pdfText = extractTextFromPdf(uri)
                val validationResult = validatePdfContent(pdfText)

                withContext(Dispatchers.Main) {
                    if (validationResult.isValid) {
                        L.d(TAG, "pdf validation successful, showing subject selection")
                        showSubjectSelectionDialog(validationResult)
                    } else {
                        L.e(TAG, "pdf validation failed: ${validationResult.errorMessage}")
                        Toast.makeText(
                            this@SettingsActivity,
                            getString(R.string.set_act_invalid_pdf, validationResult.errorMessage),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                L.e(TAG, "Error processing pdf", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.set_act_pdf_error, e.message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun extractTextFromPdf(uri: Uri): String { // dont ask what this does🗣️
        val inputStream = contentResolver.openInputStream(uri) ?: throw Exception(getString(R.string.set_act_couldnt_open_pdf))

        return inputStream.use { stream ->
            val reader = PdfReader(stream)
            val stringBuilder = StringBuilder()

            try {
                for (i in 1..reader.numberOfPages) {
                    val pageText = PdfTextExtractor.getTextFromPage(reader, i)
                    stringBuilder.append(pageText)
                    stringBuilder.append(" ")
                }

                val rawText = stringBuilder.toString()
                L.d(TAG, rawText)
                L.d(TAG, "Raw pdf text length: ${rawText.length}")

                val normalizedText = rawText
                    .replace('\u00A0', ' ') // Non-breaking space
                    .replace('\t', ' ')      // Tab
                    .replace('\n', ' ')      // Newline
                    .replace('\r', ' ')      // Carriage return
                    .replace(Regex("\\s+"), " ") // Multiple spaces to single space
                    .trim()

                L.d(TAG, "Normalized text length: ${normalizedText.length}")

                // Remove "1H" or "2H" when they're surrounded by non-alphanumeric characters
                // This regex matches:
                // (?<![A-Za-zÄÖÜäöüß0-9]) - negative lookbehind: not preceded by letter or digit
                // [12]H - matches "1H" or "2H"
                // (?![A-Za-zÄÖÜäöüß0-9]) - negative lookahead: not followed by letter or digit
                val cleanedText = normalizedText.replace(
                    Regex("(?<![A-Za-zÄÖÜäöüß0-9])[12]H(?![A-Za-zÄÖÜäöüß0-9])"),
                    ""
                )

                L.d(TAG, "Cleaned text length: ${cleanedText.length}")
                cleanedText
            } finally {
                reader.close()
            }
        }
    }

    private fun validatePdfContent(text: String): ValidationResult {
        L.d(TAG, "=== STARTING PDF VALIDATION ===")
        L.d(TAG, "Text length: ${text.length}")

        // Extract school year - keep German keywords for parsing
        val schuljahrRegex = Regex("Schuljahr\\s+(\\d{4}/\\d{4})")
        val schuljahrMatch = schuljahrRegex.find(text)
        val schuljahr = schuljahrMatch?.groupValues?.get(1)

        L.d(TAG, "Found Schuljahr: $schuljahr")

        if (schuljahr == null) {
            return ValidationResult(false, getString(R.string.set_act_schoolyear_not_found), "", "", emptyList())
        }

        // Check school identifier - keep German school names
        L.d(TAG, "Looking for school identifier: $VALID_SCHOOL_IDENTIFIER")
        if (!text.contains(VALID_SCHOOL_IDENTIFIER, ignoreCase = true) && !text.contains(VALID_SCHOOL_IDENTIFIER_ALT, ignoreCase = true)) {
            L.d(TAG, "School identifier not found")
            return ValidationResult(false, getString(R.string.set_act_invalid_school), "", "", emptyList())
        }
        L.d(TAG, "School identifier found")

        // Extract and validate class
        val userKlasse = sharedPreferences.getString("selected_klasse", "") ?: ""
        L.d(TAG, "User selected class: $userKlasse")

        if (userKlasse.isEmpty()) {
            return ValidationResult(false, getString(R.string.set_act_no_class_selected), "", "", emptyList())
        }

        val klassePrefix = userKlasse.replace(Regex("\\d+$"), "")
        L.d(TAG, "Looking for class prefix: $klassePrefix")

        // Look for the class in the text more flexibly
        var foundKlasse = ""

        // Try different patterns to find the class
        val patterns = listOf(
            "$klassePrefix\\d*",
            userKlasse,
            klassePrefix
        )

        for (pattern in patterns) {
            val regex = Regex("\\b$pattern\\b", RegexOption.IGNORE_CASE)
            val match = regex.find(text)
            if (match != null) {
                foundKlasse = match.value
                L.d(TAG, "Found class with pattern '$pattern': $foundKlasse")
                break
            }
        }

        if (foundKlasse.isEmpty()) {
            L.d(TAG, "Class not found in PDF")
            return ValidationResult(false, getString(R.string.set_act_class_not_found_format, userKlasse), "", "", emptyList())
        }

        // Extract subjects
        val subjects = extractSubjectsFromText(text, foundKlasse)

        L.d(TAG, "Final validation - Found klasse: $foundKlasse")
        L.d(TAG, "Final validation - Extracted ${subjects.size} subjects: $subjects")

        if (subjects.isEmpty()) {
            return ValidationResult(false, getString(R.string.set_act_no_subjects_found), "", "", emptyList())
        }

        return ValidationResult(
            true,
            "",
            schuljahr,
            foundKlasse,
            subjects
        )
    }

    private fun backfillUnknownRooms() {
        L.d(TAG, "=== BACKFILLING UNKNOWN ROOMS ===")
        L.d(TAG, "Before backfill - subjects: ${extractedSubjects.size}, teachers: ${extractedTeachers.size}, rooms: ${extractedRooms.size}")

        var changesCount = 0

        // look for "UNKNOWN" rooms
        for (i in extractedRooms.indices) {
            if (extractedRooms[i] == "UNKNOWN" && i < extractedSubjects.size && i < extractedTeachers.size) {
                val subject = extractedSubjects[i]
                val teacher = extractedTeachers[i]
                val key = Pair(subject, teacher)

                val knownRoom = knownSubjectRooms[key]
                if (knownRoom != null && knownRoom != "UNKNOWN") {
                    L.d(TAG, "Backfilling room for $subject->$teacher: UNKNOWN -> $knownRoom")
                    extractedRooms[i] = knownRoom
                    changesCount++
                }
            }
        }

        L.d(TAG, "Backfill completed: $changesCount rooms updated")
    }

    private fun extractSubjectsFromText(text: String, klasse: String): List<String> {
        val subjects = mutableListOf<String>()
        extractedTeachers.clear()
        extractedRooms.clear()

        L.d(TAG, "=== SUBJECT EXTRACTION DEBUG ===")
        L.d(TAG, "Looking for subjects after class: '$klasse'")

        // find where user class appears in the text
        val klasseIndex = text.indexOf(klasse, ignoreCase = true)
        if (klasseIndex == -1) {
            L.e(TAG, "ERROR: Class '$klasse' not found in text")
            return subjects
        }

        L.d(TAG, "Found class '$klasse' at position: $klasseIndex")

        val afterKlasse = text.substring(klasseIndex + klasse.length)

        val datePattern = Regex("\\d{1,2}\\.\\d{1,2}\\.\\d{4}")
        val endMatch = datePattern.find(afterKlasse)
        val relevantText = if (endMatch != null) {
            afterKlasse.substring(0, endMatch.range.first)
        } else {
            afterKlasse.take(5000)
        }

        L.d(TAG, "Relevant text length: ${relevantText.length}")

        val tokens = relevantText.split(Regex("\\s+")).filter { it.isNotBlank() }
        L.d(TAG, "Found ${tokens.size} tokens")

        var i = 0
        while (i < tokens.size) {
            val currentSubjects = mutableListOf<String>()
            val currentTeachers = mutableListOf<String>()
            val currentRooms = mutableListOf<String>()

            // look for weekdays (Mo, Di, Mi, Do, Fr)
            if (tokens[i].matches(Regex("^(Mo|Di|Mi|Do|Fr)$"))) {
                L.d(TAG, "Found weekday: ${tokens[i]}, skipping weekday line...")
                while (i < tokens.size && tokens[i].matches(Regex("^(Mo|Di|Mi|Do|Fr)$"))) {
                    i++
                }
                continue
            }

            // get subjects
            i
            L.d(TAG, "Looking for subjects starting at index $i")

            while (i < tokens.size) {
                val token = tokens[i].replace(Regex("[.,;:!?()\\[\\]]+"), "")

                if (isSubject(token)) {
                    currentSubjects.add(token)
                    L.d(TAG, "Found subject: '$token'")
                    i++
                } else if (isTeacher(token) && currentSubjects.isNotEmpty()) {
                    // found teacher -> stop looking for subjects
                    L.d(TAG, "Found teacher '$token', stopping subject search")
                    break
                } else if (isHourMarker(token)) {
                    // skip hour markers (1, 2, 3 ..)
                    L.d(TAG, "Skipping hour marker: '$token'")
                    i++
                } else {
                    L.d(TAG, "Skipping unknown token: '$token'")
                    i++ // fallback -> skip
                }
            }

            if (currentSubjects.isEmpty()) {
                i++
                continue
            }

            L.d(TAG, "Found ${currentSubjects.size} subjects in this block")

            // extract teachers (should be exactly the same count as subjects)
            val expectedTeacherCount = currentSubjects.size
            var teacherCount = 0

            while (i < tokens.size && teacherCount < expectedTeacherCount) {
                val token = tokens[i].replace(Regex("[.,;:!?()\\[\\]]+"), "")

                if (isHourMarker(token)) {
                    L.d(TAG, "Skipping hour marker in teacher section: '$token'")
                    i++
                    continue
                }

                if (isTeacher(token)) {
                    currentTeachers.add(token)
                    L.d(TAG, "Found teacher: '$token'")
                    teacherCount++
                    i++
                } else if (isRoom(token) && teacherCount > 0) {
                    // started hitting rooms -> stop looking for teachers
                    L.d(TAG, "Found room '$token', stopping teacher search")
                    break
                } else if (isSubject(token)) {
                    // hit the next subject block
                    L.d(TAG, "Found next subject '$token', stopping teacher search")
                    break
                } else {
                    L.d(TAG, "Skipping unknown token in teacher section: '$token'")
                    i++
                }
            }

            // extract rooms (should be exactly the same count as subjects/teachers, but best possibility to be false)
            val expectedRoomCount = currentSubjects.size
            var roomCount = 0

            while (i < tokens.size && roomCount < expectedRoomCount) {
                val token = tokens[i].replace(Regex("[.,;:!?()\\[\\]]+"), "")

                if (isRoom(token)) {
                    currentRooms.add(token)
                    L.d(TAG, "Found room: '$token'")
                    roomCount++
                    i++
                } else if (isSubject(token)) {
                    // hit the next subject block
                    L.d(TAG, "Found next subject '$token', stopping room search")
                    break
                } else {
                    L.d(TAG, "Skipping token in room section: '$token'")
                    i++
                }
            }

            // handle missing rooms (and only rooms) using known data and validate
            if (currentSubjects.size == currentTeachers.size) {
                if (currentRooms.size == currentSubjects.size) {
                    // exact match -> store the combinations for future reference
                    for (j in currentSubjects.indices) {
                        val key = Pair(currentSubjects[j], currentTeachers[j])
                        val room = currentRooms[j]

                        // only update "knownSubjectRooms" if the room is not "UNKNOWN" (ensures we dont overwrite good rooms)
                        if (room != "UNKNOWN") {
                            knownSubjectRooms[key] = room
                            L.d(TAG, "Storing known room: $key -> $room")
                        }
                    }

                    subjects.addAll(currentSubjects)
                    extractedTeachers.addAll(currentTeachers)
                    extractedRooms.addAll(currentRooms)

                    L.d(TAG, "Successfully matched ${currentSubjects.size} subjects with teachers and rooms")
                    for (j in currentSubjects.indices) {
                        L.d(TAG, "   ${currentSubjects[j]} -> ${currentTeachers[j]} -> ${currentRooms[j]}")
                    }
                } else if (currentRooms.size < currentSubjects.size) {
                    L.d(TAG, "Missing rooms detected: ${currentSubjects.size} subjects/teachers but only ${currentRooms.size} rooms")
                    L.d(TAG, "Attempting advanced room filling...")

                    val completedRooms = fillMissingRooms(currentSubjects, currentTeachers, currentRooms)

                    if (completedRooms.size == currentSubjects.size) {
                        L.d(TAG, "Successfully filled missing rooms using positional analysis")

                        for (j in currentSubjects.indices) {
                            val key = Pair(currentSubjects[j], currentTeachers[j])
                            val room = completedRooms[j]
                            if (room != "UNKNOWN") {
                                knownSubjectRooms[key] = room
                                L.d(TAG, "Storing filled room: $key -> $room")
                            }
                        }

                        subjects.addAll(currentSubjects)
                        extractedTeachers.addAll(currentTeachers)
                        extractedRooms.addAll(completedRooms)

                        L.d(TAG, "Final verified mappings:")
                        for (j in currentSubjects.indices) {
                            L.d(TAG, "   ${currentSubjects[j]} -> ${currentTeachers[j]} -> ${completedRooms[j]}")
                        }
                    } else {
                        // safety fallback fill with "UNKNOWN"
                        L.w(TAG, "Could not fill all missing rooms, padding remaining with UNKNOWN")

                        subjects.addAll(currentSubjects)
                        extractedTeachers.addAll(currentTeachers)

                        val paddedRooms = completedRooms.toMutableList()
                        while (paddedRooms.size < currentSubjects.size) {
                            paddedRooms.add("UNKNOWN")
                        }
                        extractedRooms.addAll(paddedRooms)

                        for (j in currentSubjects.indices) {
                            if (j < completedRooms.size && completedRooms[j] != "UNKNOWN") {
                                val key = Pair(currentSubjects[j], currentTeachers[j])
                                knownSubjectRooms[key] = completedRooms[j]
                                L.d(TAG, "Storing known room: $key -> ${completedRooms[j]}")
                            }
                        }
                    }
                } else {
                    L.w(TAG, "More rooms than subjects: ${currentRooms.size} rooms vs ${currentSubjects.size} subjects")

                    subjects.addAll(currentSubjects)
                    extractedTeachers.addAll(currentTeachers)
                    extractedRooms.addAll(currentRooms.take(currentSubjects.size))

                    for (j in currentSubjects.indices) {
                        val key = Pair(currentSubjects[j], currentTeachers[j])
                        val room = currentRooms[j]
                        if (room != "UNKNOWN") {
                            knownSubjectRooms[key] = room
                            L.d(TAG, "Storing room: $key -> $room")
                        }
                    }
                }
            } else {
                // missing teachers (if this is hit take them prayers out🙏)
                L.w(TAG, "Missing teachers detected: ${currentSubjects.size} subjects, ${currentTeachers.size} teachers")
                L.w(TAG, "NOT attempting to fill missing teachers - marking as UNKNOWN")

                subjects.addAll(currentSubjects)

                val paddedTeachers = currentTeachers.toMutableList()
                while (paddedTeachers.size < currentSubjects.size) {
                    paddedTeachers.add("UNKNOWN")
                }
                extractedTeachers.addAll(paddedTeachers)

                val paddedRooms = currentRooms.toMutableList()
                while (paddedRooms.size < currentSubjects.size) {
                    paddedRooms.add("UNKNOWN")
                }
                extractedRooms.addAll(paddedRooms)

                L.w(TAG, "Added ${currentSubjects.size} subjects with UNKNOWN teachers/rooms due to teacher mismatch")
            }
        }

        val uniqueData = removeDuplicatesKeepingOrder(subjects, extractedTeachers, extractedRooms)

        extractedSubjects.clear()
        extractedSubjects.addAll(uniqueData.first)
        extractedTeachers.clear()
        extractedTeachers.addAll(uniqueData.second)
        extractedRooms.clear()
        extractedRooms.addAll(uniqueData.third)

        backfillUnknownRooms()

        L.d(TAG, "=== FINAL EXTRACTION RESULTS ===")
        L.d(TAG, "Total unique subjects found: ${extractedSubjects.size}")
        L.d(TAG, "Subjects with teachers and rooms:")
        for (i in extractedSubjects.indices) {
            L.d(TAG, "  ${extractedSubjects[i]} -> ${extractedTeachers[i]} -> ${extractedRooms[i]}")
        }

        return extractedSubjects
    }

    private fun fillMissingRooms(
        subjects: List<String>,
        teachers: List<String>,
        existingRooms: List<String>
    ): List<String> {
        L.d(TAG, "=== ADVANCED MISSING ROOM FILLING ===")
        L.d(TAG, "Need ${subjects.size} rooms, have ${existingRooms.size} rooms")
        L.d(TAG, "Subjects: $subjects")
        L.d(TAG, "Teachers: $teachers")
        L.d(TAG, "Existing rooms: $existingRooms")
        L.d(TAG, "Known subject-teacher-room combinations: ${knownSubjectRooms.size}")

        if (subjects.size == existingRooms.size) {
            return existingRooms
        }

        val completedRooms = existingRooms.toMutableList()
        var currentRoomIndex = 0

        // check if subject/teacher pair got room at expected position
        for (i in subjects.indices) {
            val subject = subjects[i]
            val teacher = teachers[i]
            val key = Pair(subject, teacher)

            L.d(TAG, "Processing [$i]: $subject -> $teacher")

            val expectedRoom = knownSubjectRooms[key]

            if (expectedRoom == null) {
                L.d(TAG, "  No known room for $subject->$teacher")
                if (currentRoomIndex < completedRooms.size) {
                    L.d(TAG, "  Using existing room at position $currentRoomIndex: ${completedRooms[currentRoomIndex]}")
                    currentRoomIndex++
                } else {
                    L.d(TAG, "  No room available at this position")
                }
                continue
            }

            L.d(TAG, "  Expected room: $expectedRoom")

            if (currentRoomIndex < completedRooms.size) {
                val currentRoom = completedRooms[currentRoomIndex]
                L.d(TAG, "  Current room at position $currentRoomIndex: $currentRoom")

                if (currentRoom == expectedRoom) {
                    L.d(TAG, "  Room matches expected position")
                    currentRoomIndex++
                } else {
                    L.d(TAG, "  ✗ Room doesn't match, inserting expected room")
                    completedRooms.add(currentRoomIndex, expectedRoom)
                    currentRoomIndex++
                }
            } else {
                L.d(TAG, "  Adding missing room at end: $expectedRoom")
                completedRooms.add(expectedRoom)
            }
        }

        // here were dead
        if (completedRooms.size < subjects.size) {
            L.d(TAG, "Still missing ${subjects.size - completedRooms.size} rooms, trying alternative matching")

            val remainingIndices = (completedRooms.size until subjects.size)
            for (i in remainingIndices) {
                if (i >= subjects.size) break

                val subject = subjects[i]

                val possibleRoom = knownSubjectRooms.entries
                    .firstOrNull { it.key.first == subject }
                    ?.value

                if (possibleRoom != null) {
                    completedRooms.add(possibleRoom)
                    L.d(TAG, "Added room for $subject (any teacher): $possibleRoom")
                }
            }

            if (completedRooms.size < subjects.size) {
                val stillRemainingIndices = (completedRooms.size until subjects.size)
                for (i in stillRemainingIndices) {
                    if (i >= subjects.size || i >= teachers.size) break

                    val teacher = teachers[i]

                    val possibleRoom = knownSubjectRooms.entries
                        .firstOrNull { it.key.second == teacher }
                        ?.value

                    if (possibleRoom != null) {
                        completedRooms.add(possibleRoom)
                        L.d(TAG, "Added room for teacher $teacher (any subject): $possibleRoom")
                    }
                }
            }
        }

        while (completedRooms.size > subjects.size) { // safety measure (although probably never used)
            completedRooms.removeAt(completedRooms.size - 1)
            L.d(TAG, "Removed excess room")
        }

        L.d(TAG, "=== FINAL ROOM FILLING RESULT ===")
        L.d(TAG, "Original rooms: $existingRooms")
        L.d(TAG, "Completed rooms: $completedRooms")
        L.d(TAG, "Final count: ${completedRooms.size}/${subjects.size}")

        for (i in subjects.indices) {
            val room = if (i < completedRooms.size) completedRooms[i] else "MISSING"
            L.d(TAG, "Final mapping [$i]: ${subjects[i]} -> ${teachers[i]} -> $room")
        }

        return completedRooms
    }

    private fun isSubject(token: String): Boolean {
        if (token.length > 15) return false

        return when {
            // base patterns like Ma-1, De-2 ..
            token.matches(Regex("^[A-Za-z]{1,6}-\\d+$")) -> true
            // pattern like Ch-2-LK, PI-3-LK ..
            token.matches(Regex("^[A-Za-z]{1,6}-\\d+-[A-Za-z]{1,4}$")) -> true
            // patterns like Me-2-L ..
            token.matches(Regex("^[A-Za-z]{1,6}-\\d+-[A-Za-z]$")) -> true
            // religion like Re-Ka, Re-Ev
            token.matches(Regex("^Re-[A-Za-z]{2,4}$")) -> true
            // ethics like Eth-2, Eth
            token.matches(Regex("^Eth(-\\d+)?$")) -> true
            // sport patterns like Sp-1, Sp-2
            token.matches(Regex("^Sp-\\d+$")) -> true
            // special subjects
            token.matches(Regex("^(Eth|Tu-1)$")) -> true
            // broad pattern for other
            token.matches(Regex("^[A-Za-z]{1,8}-[A-Za-z0-9]{1,4}(-[A-Za-z]{1,4})?$")) && token.length <= 12 -> true
            else -> false
        }
    }

    private fun isTeacher(token: String): Boolean {
        // teachers are normally 3-4 uppercase letters
        return token.matches(Regex("^[A-ZÄÖÜ]{3,8}$")) && token.length <= 8 && !isHourMarker(token) && !isSubject(token)
    }

    private fun isRoom(token: String): Boolean {
        if (token.length > 10) return false

        return when {
            // base room pat like A407, B114 ..
            token.matches(Regex("^[A-Z]\\d{2,4}$")) -> true
            // like PHS133
            token.matches(Regex("^[A-Z]{2,4}\\d{2,4}$")) -> true
            // simple pat like SP
            token.matches(Regex("^[A-Z]{1,3}$")) && token.length <= 3 -> true
            // mixed special patterns (letters and numbers)
            token.matches(Regex("^[A-Z0-9]{2,6}$")) -> true
            else -> false
        }
    }

    private fun isHourMarker(token: String): Boolean {
        // hour markers are digits from 1 - 15
        return token.matches(Regex("^([1-9]|1[0-5])$"))
    }

    private fun removeDuplicatesKeepingOrder(
        subjects: List<String>,
        teachers: List<String>,
        rooms: List<String>
    ): Triple<List<String>, List<String>, List<String>> {

        val uniqueSubjects = mutableListOf<String>()
        val uniqueTeachers = mutableListOf<String>()
        val uniqueRooms = mutableListOf<String>()
        val seenSubjects = mutableSetOf<String>()

        for (i in subjects.indices) {
            val subject = subjects[i]
            if (subject !in seenSubjects) {
                seenSubjects.add(subject)
                uniqueSubjects.add(subject)
                uniqueTeachers.add(if (i < teachers.size) teachers[i] else "UNKNOWN")
                uniqueRooms.add(if (i < rooms.size) rooms[i] else "UNKNOWN")
            }
        }

        return Triple(uniqueSubjects, uniqueTeachers, uniqueRooms)
    }

    private fun showSubjectSelectionDialog(validationResult: ValidationResult) {
        L.d(TAG, "Launching SubjectSelectionActivity with ${extractedSubjects.size} subjects")

        if (extractedSubjects.isEmpty()) {
            Toast.makeText(this, getString(R.string.set_act_no_subjects_to_select_found), Toast.LENGTH_SHORT).show()
            return
        }

        sharedPreferences.edit {
            putString("all_extracted_subjects", extractedSubjects.joinToString(","))
                .putString("all_extracted_teachers", extractedTeachers.joinToString(","))
                .putString("all_extracted_rooms", extractedRooms.joinToString(","))
        }

        L.d(TAG, "Passing to SubjectSelectionActivity:")
        L.d(TAG, "  Subjects (${extractedSubjects.size}): $extractedSubjects")
        L.d(TAG, "  Teachers (${extractedTeachers.size}): $extractedTeachers")
        L.d(TAG, "  Rooms (${extractedRooms.size}): $extractedRooms")

        val intent = Intent(this, SubjectSelectionActivity::class.java).apply {
            putExtra(SubjectSelectionActivity.EXTRA_SUBJECTS, extractedSubjects.toTypedArray())
            putExtra(SubjectSelectionActivity.EXTRA_TEACHERS, extractedTeachers.toTypedArray())
            putExtra(SubjectSelectionActivity.EXTRA_ROOMS, extractedRooms.toTypedArray())
            putExtra(SubjectSelectionActivity.EXTRA_SCHULJAHR, validationResult.schuljahr)
            putExtra(SubjectSelectionActivity.EXTRA_KLASSE, validationResult.klasse)
        }

        startActivityForResult(intent, SUBJECT_SELECTION_REQUEST_CODE)
    }

    private fun showDeleteTimetableDialog() {
        val alertDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.set_act_delete_timetable))
            .setMessage(getString(R.string.set_act_delete_confirm))
            .setPositiveButton(getString(R.string.set_act_yes_delete)) { _, _ ->
                updateDeleteTimetableData()
            }
            .setNegativeButton(getString(R.string.set_act_cancel), null)
            .show()

        val buttonColor = getThemeColor(R.attr.dialogSectionButtonColor)
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)
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

    private fun showResetConfirmationDialog() {
        val alertDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.set_act_reset))
            .setMessage(getString(R.string.set_act_reset_confirm))
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(getString(R.string.set_act_yes_reset)) { _, _ ->
                resetAppData()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()

        val buttonColor = getThemeColor(R.attr.dialogSectionButtonColor)
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)
    }

    private fun resetAppData() {
        sharedPreferences.edit {
            clear()
        }

        extractedSubjects.clear()
        extractedTeachers.clear()
        extractedRooms.clear()
        knownSubjectRooms.clear()

        tempImportContent = null
        tempImportSections = null

        WorkScheduler.cancelAutoUpdate(this)
        WorkScheduler.cancelChangeNotification(this)
        WorkScheduler.cancelDueDateReminder(this)
        WorkScheduler.cancelDailyHomeworkReminder(this)
        WorkScheduler.cancelExamReminder(this)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        Toast.makeText(this, getString(R.string.set_act_reset_complete), Toast.LENGTH_SHORT).show()

        val intent = Intent(this, SetupActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun toggleTimetableOptions() {
        isOptionsExpanded = !isOptionsExpanded
        layoutTimetableOptions.visibility = if (isOptionsExpanded) {
            View.VISIBLE
        } else {
            View.GONE
        }

        btnTimetableOptions.text = if (isOptionsExpanded) "⋮" else "⋮"

        checkButtonAvailability()
    }

    private fun exportTimetable() {
        if (!isDocumentScanned()) {
            Toast.makeText(this, getString(R.string.set_act_no_timetable_found_to_export), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            showExportOptions()
        } catch (e: Exception) {
            L.e(TAG, "Error exporting timetable", e)
            Toast.makeText(this, "${getString(R.string.set_act_error_while_exporting)}: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun generateExportContent(): String {
        val subjects = sharedPreferences.getString("student_subjects", "")?.split(",") ?: emptyList()
        val teachers = sharedPreferences.getString("student_teachers", "")?.split(",") ?: emptyList()
        val rooms = sharedPreferences.getString("student_rooms", "")?.split(",") ?: emptyList()
        val klasse = sharedPreferences.getString("selected_klasse", "")
        val bildungsgang = sharedPreferences.getString("selected_bildungsgang", "")

        val documentInfo = sharedPreferences.getString("scanned_document_info", "")
        val schuljahrPattern = Regex("Schuljahr\\s+(\\d{4}/\\d{4})")
        val schuljahr = schuljahrPattern.find(documentInfo ?: "")?.groupValues?.get(1)
            ?: getCurrentSchuljahr()

        val alternativeRoomsJson = sharedPreferences.getString("alternative_rooms", "{}")

        val content = StringBuilder()
        content.appendLine(getString(R.string.set_act_export_header))
        content.appendLine(getString(R.string.set_act_export_timestamp, SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMAN).format(Date())))
        content.appendLine()
        content.appendLine("SCHULJAHR=$schuljahr")
        content.appendLine("KLASSE=$klasse")
        content.appendLine("BILDUNGSGANG=$bildungsgang")
        content.appendLine()
        content.appendLine(getString(R.string.set_act_export_subjects_header))

        for (i in subjects.indices) {
            val subject = subjects[i]
            val teacher = if (i < teachers.size) teachers[i] else "UNKNOWN"
            val room = if (i < rooms.size) rooms[i] else "UNKNOWN"
            content.appendLine("$subject|$teacher|$room")
        }

        content.appendLine()
        content.appendLine(getString(R.string.set_act_export_alt_rooms_header))
        content.appendLine("ALTERNATIVE_ROOMS=$alternativeRoomsJson")

        return content.toString()
    }

    private fun getCurrentSchuljahr(): String {
        val calendar = Calendar.getInstance()
        val currentYear = calendar.get(Calendar.YEAR)
        val currentMonth = calendar.get(Calendar.MONTH) + 1 // Calendar.MONTH is 0 based

        return if (currentMonth >= 8) {
            "$currentYear/${currentYear + 1}"
        } else {
            "${currentYear - 1}/$currentYear"
        }
    }

    private fun showExportOptions() {
        val content = generateExportContent()
        val options = listOf(
            Pair(getString(R.string.set_act_export_file), R.drawable.ic_export_file),
            Pair(getString(R.string.set_act_export_clipboard), R.drawable.ic_export_clipboard)
        )
        val adapter = object : ArrayAdapter<Pair<String, Int>>(
            this,
            android.R.layout.simple_list_item_1,
            options
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                val (text, iconRes) = getItem(position)!!
                view.text = text
                view.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
                view.compoundDrawablePadding = 16
                return view
            }
        }
        val alertDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.act_set_export_timetable))
            .setAdapter(adapter) { _, which ->
                when (which) {
                    0 -> saveToFile(content)
                    1 -> copyToClipboard(content)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()

        val buttonColor = getThemeColor(R.attr.dialogSectionButtonColor)
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)
    }

    private fun saveToFile(content: String) {
        val klasse = sharedPreferences.getString("selected_klasse", "unknown")
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.GERMAN).format(Date())
        val filename = "stundenplan_${klasse}_${timestamp}$HKS_TIMETABLE_FILE_EXTENSION"

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, filename)
        }

        sharedPreferences.edit {putString("temp_export_content", content) }

        try {
            startActivityForResult(intent, EXPORT_FILE_REQUEST_CODE)
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.set_act_error_while_opening_file_dialog), Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToClipboard(content: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(getString(R.string.set_act_timetable), content)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(this, getString(R.string.set_act_timetable_saved_to_clipboard), Toast.LENGTH_SHORT).show()
    }

    private fun showImportOptions() {
        val options = listOf(
            Pair(getString(R.string.set_act_import_file), R.drawable.ic_import_file),
            Pair(getString(R.string.set_act_import_clipboard), R.drawable.ic_import_clipboard)
        )
        val adapter = object : ArrayAdapter<Pair<String, Int>>(
            this,
            android.R.layout.simple_list_item_1,
            options
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                val (text, iconRes) = getItem(position)!!
                view.text = text
                view.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
                view.compoundDrawablePadding = 16
                return view
            }
        }
        val alertDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.act_set_import_timetable))
            .setAdapter(adapter) { _, which ->
                when (which) {
                    0 -> importFromFile()
                    1 -> importFromClipboard()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()

        val buttonColor = getThemeColor(R.attr.dialogSectionButtonColor)
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)
    }

    private fun importFromFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "text/plain"
            addCategory(Intent.CATEGORY_OPENABLE)
        }

        try {
            startActivityForResult(
                Intent.createChooser(intent, getString(R.string.set_act_select_hks_file)),
                IMPORT_FILE_REQUEST_CODE
            )
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.set_act_no_file_manager_app_found), Toast.LENGTH_SHORT).show()
        }
    }

    private fun importFromClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip

        if (clipData != null && clipData.itemCount > 0) {
            val clipText = clipData.getItemAt(0).text?.toString()
            if (!clipText.isNullOrEmpty()) {
                processImportContent(clipText)
            } else {
                Toast.makeText(this, getString(R.string.set_act_clipboard_is_empty), Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, getString(R.string.set_act_clipboard_is_empty), Toast.LENGTH_SHORT).show()
        }
    }

    private fun processImportContent(content: String) {
        try {
            val importResult = parseImportContent(content)
            if (importResult.isValid) {
                showImportConfirmationDialog(importResult)
            } else {
                Toast.makeText(this, "${getString(R.string.set_act_invalid_fild)}: ${importResult.errorMessage}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            L.e(TAG, "Error processing import content", e)
            Toast.makeText(this, "${getString(R.string.set_act_error_while_importing)}: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun parseImportContent(content: String): ImportResult {
        val lines = content.lines().filter { it.isNotBlank() && !it.startsWith("#") }

        var schuljahr = ""
        var klasse = ""
        var bildungsgang = ""
        var alternativeRoomsJson = "{}"
        val subjects = mutableListOf<String>()
        val teachers = mutableListOf<String>()
        val rooms = mutableListOf<String>()

        for (line in lines) {
            when {
                line.startsWith("SCHULJAHR=") -> schuljahr = line.substringAfter("=")
                line.startsWith("KLASSE=") -> klasse = line.substringAfter("=")
                line.startsWith("BILDUNGSGANG=") -> bildungsgang = line.substringAfter("=")
                line.startsWith("ALTERNATIVE_ROOMS=") -> alternativeRoomsJson = line.substringAfter("=")
                line.contains("|") -> {
                    val parts = line.split("|")
                    if (parts.size >= 3) {
                        subjects.add(parts[0].trim())
                        teachers.add(parts[1].trim())
                        rooms.add(parts[2].trim())
                    }
                }
            }
        }

        if (schuljahr.isEmpty()) {
            return ImportResult(false, getString(R.string.set_act_school_year_not_found), "", "", "", emptyList(), emptyList(), emptyList())
        }

        if (klasse.isEmpty()) {
            return ImportResult(false, getString(R.string.set_act_class_not_found), "", "", "", emptyList(), emptyList(), emptyList())
        }

        if (subjects.isEmpty()) {
            return ImportResult(false, getString(R.string.set_act_no_subjects_found), "", "", "", emptyList(), emptyList(), emptyList())
        }

        // validate class matches users selection
        val userKlasse = sharedPreferences.getString("selected_klasse", "") ?: ""
        if (userKlasse.isNotEmpty() && klasse != userKlasse) {
            return ImportResult(false, getString(R.string.set_act_class_mismatch_format, klasse, userKlasse), "", "", "", emptyList(), emptyList(), emptyList())
        }

        return ImportResult(true, "", schuljahr, klasse, bildungsgang, subjects, teachers, rooms, alternativeRoomsJson)
    }

    private fun showImportConfirmationDialog(importResult: ImportResult) {
        val message = getString(R.string.set_act_import_confirmation_format,
            importResult.schuljahr,
            importResult.klasse,
            importResult.subjects.size
        )

        val alertDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.set_act_confirm_import))
            .setMessage(message)
            .setPositiveButton(getString(R.string.act_set_import)) { _, _ ->
                executeImport(importResult)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()

        val buttonColor = getThemeColor(R.attr.dialogSectionButtonColor)
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)
    }

    private fun executeImport(importResult: ImportResult) {
        extractedSubjects.clear()
        extractedTeachers.clear()
        extractedRooms.clear()
        knownSubjectRooms.clear()

        extractedSubjects.addAll(importResult.subjects)
        extractedTeachers.addAll(importResult.teachers)
        extractedRooms.addAll(importResult.rooms)

        for (i in extractedSubjects.indices) {
            if (i < extractedTeachers.size && i < extractedRooms.size) {
                val key = Pair(extractedSubjects[i], extractedTeachers[i])
                knownSubjectRooms[key] = extractedRooms[i]
            }
        }

        sharedPreferences.edit {
            putBoolean("has_scanned_document", true)
            putString("student_subjects", importResult.subjects.joinToString(","))
            putString("student_teachers", importResult.teachers.joinToString(","))
            putString("student_rooms", importResult.rooms.joinToString(","))
            putString("all_extracted_subjects", importResult.subjects.joinToString(","))
            putString("all_extracted_teachers", importResult.teachers.joinToString(","))
            putString("all_extracted_rooms", importResult.rooms.joinToString(","))
            putString("scanned_document_info", getString(R.string.set_act_document_info_format, importResult.schuljahr, importResult.klasse))
            putString("alternative_rooms", importResult.alternativeRooms)
        }

        updateTimetableButton()
        setupFilterSwitch()

        val message = getString(R.string.set_act_import_success_format, importResult.subjects.size)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

        isOptionsExpanded = false
        layoutTimetableOptions.visibility = View.GONE

        L.d(TAG, "Import completed: ${importResult.subjects.size} subjects imported with alternative rooms")
    }

    private fun editTimetable() {
        if (!isDocumentScanned()) {
            Toast.makeText(this, getString(R.string.set_act_no_timetable_available_to_edit), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val allExtractedSubjects = sharedPreferences.getString("all_extracted_subjects", "")
                ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
            val allExtractedTeachers = sharedPreferences.getString("all_extracted_teachers", "")
                ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
            val allExtractedRooms = sharedPreferences.getString("all_extracted_rooms", "")
                ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

            val documentInfo = sharedPreferences.getString("scanned_document_info", "")

            val schuljahrPattern = Regex("Schuljahr\\s+(\\d{4}/\\d{4})")
            val schuljahr = schuljahrPattern.find(documentInfo ?: "")?.groupValues?.get(1)
                ?: getCurrentSchuljahr()
            val klasse = sharedPreferences.getString("selected_klasse", "")

            if (allExtractedSubjects.isEmpty()) {
                Toast.makeText(this, getString(R.string.set_act_no_subjects_found_to_edit), Toast.LENGTH_SHORT).show()
                return
            }

            L.d(TAG, "Launching SubjectSelectionActivity for editing with ${allExtractedSubjects.size} subjects")

            val intent = Intent(this, SubjectSelectionActivity::class.java).apply {
                putExtra(SubjectSelectionActivity.EXTRA_SUBJECTS, allExtractedSubjects.toTypedArray())
                putExtra(SubjectSelectionActivity.EXTRA_TEACHERS, allExtractedTeachers.toTypedArray())
                putExtra(SubjectSelectionActivity.EXTRA_ROOMS, allExtractedRooms.toTypedArray())
                putExtra(SubjectSelectionActivity.EXTRA_SCHULJAHR, schuljahr)
                putExtra(SubjectSelectionActivity.EXTRA_KLASSE, klasse)
                putExtra(SubjectSelectionActivity.EXTRA_IS_EDITING_MODE, true)
            }

            startActivityForResult(intent, SUBJECT_SELECTION_REQUEST_CODE)

            isOptionsExpanded = false
            layoutTimetableOptions.visibility = View.GONE

        } catch (e: Exception) {
            L.e(TAG, "Error launching edit timetable", e)
            Toast.makeText(this, getString(R.string.set_act_error_trying_to_open_edit, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun checkButtonAvailability() {
        btnExportTimetable.isEnabled = isDocumentScanned()
        btnExportTimetable.alpha = if (isDocumentScanned()) 1.0f else 0.5f
        btnEditTimetable.isEnabled = isDocumentScanned()
        btnEditTimetable.alpha = if (isDocumentScanned()) 1.0f else 0.5f
    }

    private fun setupStartupPageSetting() {
        val startupPages = arrayOf(
            getString(R.string.act_set_calendar),
            getString(R.string.set_act_substitute_plan_recommended),
            getString(R.string.act_set_homework),
            getString(R.string.act_set_exams),
            getString(R.string.dlg_edit_sub_grades)
        )
        val currentSelection = sharedPreferences.getInt("startup_page_index", 1)

        btnStartupPage.text = getString(R.string.set_act_startup_page_format, startupPages[currentSelection])

        btnStartupPage.setOnClickListener {
            val alertDialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.set_act_choose_start_up_page))
                .setSingleChoiceItems(startupPages, currentSelection) { dialog, which ->
                    sharedPreferences.edit {
                        putInt("startup_page_index", which)
                    }

                    btnStartupPage.text = getString(R.string.set_act_startup_page_format, startupPages[which])
                    dialog.dismiss()

                    val message = getString(R.string.set_act_startup_changed_format, startupPages[which])
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()

            val buttonColor = getThemeColor(R.attr.dialogSectionButtonColor)
            alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
            alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
            alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)
        }
    }

    private fun setupAutoUpdateSettings() {
        val autoUpdateEnabled = sharedPreferences.getBoolean("auto_update_enabled", false)
        val autoUpdateTime = sharedPreferences.getString("auto_update_time", "06:00") ?: "06:00"
        val updateWifiOnly = sharedPreferences.getBoolean("update_wifi_only", false)
        val showUpdateNotifications = sharedPreferences.getBoolean("show_update_notifications", true)

        switchAutoUpdate.isChecked = autoUpdateEnabled
        btnAutoUpdateTime.text = getString(R.string.set_act_update_time_format, autoUpdateTime)
        switchUpdateWifiOnly.isChecked = updateWifiOnly
        switchShowUpdateNotifications.isChecked = showUpdateNotifications

        updateAutoUpdateUI(autoUpdateEnabled)

        switchAutoUpdate.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit { putBoolean("auto_update_enabled", isChecked)}

            updateAutoUpdateUI(isChecked)

            if (isChecked) {
                WorkScheduler.scheduleAutoUpdate(this, sharedPreferences)
                Toast.makeText(this, getString(R.string.set_act_automatic_updates_activated), Toast.LENGTH_SHORT).show()
            } else {
                WorkScheduler.cancelAutoUpdate(this)
                Toast.makeText(this, getString(R.string.set_act_automatic_updates_deactivated), Toast.LENGTH_SHORT).show()
            }
        }

        btnAutoUpdateTime.setOnClickListener {
            val timeParts = autoUpdateTime.split(":")
            val hour = timeParts[0].toIntOrNull() ?: 6
            val minute = timeParts[1].toIntOrNull() ?: 0

            val timePickerDialog = TimePickerDialog(
                this,
                { _, selectedHour, selectedMinute ->
                    val selectedTime = String.format("%02d:%02d", selectedHour, selectedMinute)
                    sharedPreferences.edit { putString("auto_update_time", selectedTime) }
                    btnAutoUpdateTime.text = getString(R.string.set_act_update_time_format, selectedTime)
                    if (switchAutoUpdate.isChecked) {
                        WorkScheduler.scheduleAutoUpdate(this, sharedPreferences)
                        Toast.makeText(this,
                            getString(R.string.set_act_update_time_changed_format, selectedTime),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                hour,
                minute,
                true
            )

            timePickerDialog.setTitle("")

            timePickerDialog.setOnShowListener {
                val buttonColor = getThemeColor(R.attr.dialogSectionButtonColor)
                timePickerDialog.getButton(TimePickerDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
                timePickerDialog.getButton(TimePickerDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
            }

            timePickerDialog.show()
        }

        switchUpdateWifiOnly.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit { putBoolean("update_wifi_only", isChecked)}

            if (switchAutoUpdate.isChecked) {
                WorkScheduler.scheduleAutoUpdate(this, sharedPreferences)
            }
            if (switchChangeNotification.isChecked) {
                WorkScheduler.scheduleChangeNotification(this, sharedPreferences)
            }

            Toast.makeText(this,
                if (isChecked) getString(R.string.set_act_updates_only_through_wifi_activated)
                else getString(R.string.set_act_updates_through_all_connections_enabled),
                Toast.LENGTH_SHORT).show()
        }

        switchShowUpdateNotifications.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit { putBoolean("show_update_notifications", isChecked)}

            Toast.makeText(this,
                if (isChecked) getString(R.string.set_act_update_notis_activated)
                else getString(R.string.set_act_update_notis_deactivated),
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupChangeNotificationSettings() {
        val changeNotificationEnabled = sharedPreferences.getBoolean("change_notification_enabled", false)
        val changeNotificationInterval = sharedPreferences.getInt("change_notification_interval", 15)
        val changeNotificationType = sharedPreferences.getString("change_notification_type", "all_class_subjects") ?: "all_class_subjects"

        switchChangeNotification.isChecked = changeNotificationEnabled
        btnChangeNotificationInterval.text = formatInterval(changeNotificationInterval)

        setupChangeTypeSpinner(changeNotificationType)

        updateChangeNotificationUI(changeNotificationEnabled)

        switchChangeNotification.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit { putBoolean("change_notification_enabled", isChecked) }

            updateChangeNotificationUI(isChecked)

            if (isChecked) {
                WorkScheduler.scheduleChangeNotification(this, sharedPreferences)
                Toast.makeText(this, getString(R.string.set_act_change_notifications_enabled), Toast.LENGTH_SHORT).show()
            } else {
                WorkScheduler.cancelChangeNotification(this)
                Toast.makeText(this, getString(R.string.set_act_change_notifications_disabled), Toast.LENGTH_SHORT).show()
            }
        }

        btnChangeNotificationInterval.setOnClickListener {
            TimePickerDialogHelper.showIntervalPicker(this, changeNotificationInterval) { selectedInterval ->
                sharedPreferences.edit {putInt("change_notification_interval", selectedInterval) }

                btnChangeNotificationInterval.text = formatInterval(selectedInterval)

                if (switchChangeNotification.isChecked) {
                    WorkScheduler.scheduleChangeNotification(this, sharedPreferences)
                    Toast.makeText(this, "${getString(R.string.set_act_check_interval_changed)}: ${formatInterval(selectedInterval)}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupChangeTypeSpinner(currentType: String) {
        val hasScannedDocument = isDocumentScanned()

        val types = if (hasScannedDocument) {
            arrayOf("all_class_subjects", "my_subjects_only")
        } else {
            arrayOf("all_class_subjects")
        }

        val typeTexts = types.map { type ->
            when (type) {
                "all_class_subjects" -> getString(R.string.set_act_all_subjects_of_my_class)
                "my_subjects_only" -> getString(R.string.set_act_only_my_subjects)
                else -> type
            }
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, typeTexts)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerChangeNotificationType.adapter = adapter

        val currentIndex = types.indexOf(currentType).let { if (it >= 0) it else 0 }
        spinnerChangeNotificationType.setSelection(currentIndex)

        spinnerChangeNotificationType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            @SuppressLint("UseKtx")
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedType = types[position]
                sharedPreferences.edit {
                    putString("change_notification_type", selectedType)
                }

                L.d(TAG, "Change notification type changed to: $selectedType")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerChangeNotificationType.isEnabled = hasScannedDocument && switchChangeNotification.isChecked
        spinnerChangeNotificationType.alpha = if (hasScannedDocument && switchChangeNotification.isChecked) 1.0f else 0.5f
    }

    private fun updateAutoUpdateUI(enabled: Boolean) {
        btnAutoUpdateTime.isEnabled = enabled
        btnAutoUpdateTime.alpha = if (enabled) 1.0f else 0.5f

        switchUpdateWifiOnly.isEnabled = enabled || switchChangeNotification.isChecked
        switchUpdateWifiOnly.alpha = if (enabled || switchChangeNotification.isChecked) 1.0f else 0.5f

        switchShowUpdateNotifications.isEnabled = enabled
        switchShowUpdateNotifications.alpha = if (enabled) 1.0f else 0.5f
    }

    private fun updateChangeNotificationUI(enabled: Boolean) {
        btnChangeNotificationInterval.isEnabled = enabled
        btnChangeNotificationInterval.alpha = if (enabled) 1.0f else 0.5f

        val hasScannedDocument = isDocumentScanned()
        spinnerChangeNotificationType.isEnabled = enabled && hasScannedDocument
        spinnerChangeNotificationType.alpha = if (enabled && hasScannedDocument) 1.0f else 0.5f

        switchUpdateWifiOnly.isEnabled = enabled || switchAutoUpdate.isChecked
        switchUpdateWifiOnly.alpha = if (enabled || switchAutoUpdate.isChecked) 1.0f else 0.5f
    }

    private fun formatInterval(minutes: Int): String {
        return when {
            minutes < 60 -> "${getString(R.string.set_act_all)} $minutes ${getString(R.string.set_act_minutes)}"
            minutes == 60 -> getString(R.string.set_act_one_hour)
            else -> getString(R.string.set_act_all_hours, minutes / 60)
        }
    }

    private fun setupNotificationInfoButton() {
        val ivChangeNotificationInfo = findViewById<ImageView>(R.id.ivChangeNotificationInfo)

        ivChangeNotificationInfo.setOnClickListener {
            showNotificationInfoDialog()
        }

        val ivChangeNotificationInfo2 = findViewById<ImageView>(R.id.ivChangeNotificationInfo2)

        ivChangeNotificationInfo2.setOnClickListener {
            showNotificationInfoDialog()
        }
    }

    private fun showNotificationInfoDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_notification_info, null)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        val btnNotificationSettings = dialogView.findViewById<Button>(R.id.btnNotificationSettings)
        val btnBatterySettings = dialogView.findViewById<Button>(R.id.btnBatterySettings)
        val btnClose = dialogView.findViewById<Button>(R.id.btnClose)

        btnNotificationSettings.setOnClickListener {
            openNotificationSettings()
            dialog.dismiss()
        }

        btnBatterySettings.setOnClickListener {
            openBatteryOptimizationSettings()
            dialog.dismiss()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun openNotificationSettings() {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // android 8+
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            }
            startActivity(intent)
        } catch (_: Exception) {
            // fallback general settings
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.set_act_settings_error), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openBatteryOptimizationSettings() {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_SETTINGS)
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.set_act_settings_error), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupHomeworkAutoDelete() {
        switchAutoDeleteHomework = findViewById(R.id.switchAutoDeleteHomework)

        switchAutoDeleteHomework.isChecked = HomeworkUtils.isAutoDeleteEnabled(this)

        switchAutoDeleteHomework.setOnCheckedChangeListener { _, isChecked ->
            HomeworkUtils.setAutoDeleteEnabled(this, isChecked)

            val message = if (isChecked) {
                getString(R.string.set_act_auto_delete_enabled)
            } else {
                getString(R.string.set_act_auto_delete_disabled)
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupAppVersion() {
        tvAppVersion = findViewById(R.id.tvAppVersion)
        tvAppVersion.text = BuildConfig.VERSION_NAME
    }

    private fun initializeHomeworkReminderViews() {
        switchDueDateReminder = findViewById(R.id.switchDueDateReminder)
        btnDueDateReminderHours = findViewById(R.id.btnDueDateReminderHours)
        switchDailyHomeworkReminder = findViewById(R.id.switchDailyHomeworkReminder)
        btnDailyReminderTime = findViewById(R.id.btnDailyReminderTime)
    }

    private fun setupHomeworkReminderListeners() {
        switchDueDateReminder.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit {
                    putBoolean("due_date_reminder_enabled", isChecked)
                }

            if (isChecked) {
                WorkScheduler.scheduleDueDateReminder(this, sharedPreferences)
            } else {
                WorkScheduler.cancelDueDateReminder(this)
            }
        }

        btnDueDateReminderHours.setOnClickListener {
            showDueDateReminderHoursDialog()
        }

        switchDailyHomeworkReminder.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit {
                    putBoolean("daily_homework_reminder_enabled", isChecked)
                }

            if (isChecked) {
                WorkScheduler.scheduleDailyHomeworkReminder(this, sharedPreferences)
            } else {
                WorkScheduler.cancelDailyHomeworkReminder(this)
            }
        }

        btnDailyReminderTime.setOnClickListener {
            showDailyReminderTimeDialog()
        }
    }

    private fun loadHomeworkReminderSettings() {
        val dueDateReminderEnabled = sharedPreferences.getBoolean("due_date_reminder_enabled", false)
        val dueDateReminderHours = sharedPreferences.getInt("due_date_reminder_hours", 16)
        val dailyHomeworkReminderEnabled = sharedPreferences.getBoolean("daily_homework_reminder_enabled", false)
        val dailyReminderTime = sharedPreferences.getString("daily_homework_reminder_time", "19:00")

        switchDueDateReminder.isChecked = dueDateReminderEnabled
        btnDueDateReminderHours.text = getString(R.string.set_act_remind_hours_before_format, dueDateReminderHours)

        switchDailyHomeworkReminder.isChecked = dailyHomeworkReminderEnabled
        btnDailyReminderTime.text = getString(R.string.set_act_reminder_time_format, dailyReminderTime)
    }

    private fun showDueDateReminderHoursDialog() {
        val currentHours = sharedPreferences.getInt("due_date_reminder_hours", 16)
        val hoursOptions = arrayOf(getString(R.string.set_act_1_hour), getString(R.string.set_act_2_hours),
            getString(R.string.set_act_4_hours), getString(R.string.set_act_8_hours), getString(R.string.set_act_12_hours),
            getString(R.string.set_act_16_hours), getString(R.string.set_act_18_hours), getString(R.string.set_act_20_hours),
            getString(R.string.set_act_22_hours), getString(R.string.set_act_24_hours), getString(R.string.set_act_48_hours))
        val hoursValues = arrayOf(1, 2, 4, 8, 12, 16, 18,  20,22, 24, 48)

        val currentIndex = hoursValues.indexOf(currentHours).let { if (it == -1) 5 else it } // default 16

        val alertDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.set_act_due_date_reminder))
            .setSingleChoiceItems(hoursOptions, currentIndex) { dialog, which ->
                val selectedHours = hoursValues[which]
                sharedPreferences.edit { putInt("due_date_reminder_hours", selectedHours) }

                btnDueDateReminderHours.text = getString(R.string.set_act_remind_hours_before, selectedHours)

                if (switchDueDateReminder.isChecked) {
                    WorkScheduler.scheduleDueDateReminder(this, sharedPreferences)
                }

                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()

        val buttonColor = getThemeColor(R.attr.dialogSectionButtonColor)
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)
    }

    private fun showDailyReminderTimeDialog() {
        val currentTime = sharedPreferences.getString("daily_homework_reminder_time", "19:00")
        val timeParts = currentTime?.split(":") ?: listOf("19", "00")
        val currentHour = timeParts[0].toIntOrNull() ?: 19
        val currentMinute = timeParts[1].toIntOrNull() ?: 0

        val timePickerDialog = TimePickerDialog(
            this,
            { _, hour, minute ->
                val timeString = String.format("%02d:%02d", hour, minute)
                sharedPreferences.edit {
                    putString("daily_homework_reminder_time", timeString)
                }

                btnDailyReminderTime.text = getString(R.string.set_act_reminder_time, timeString)

                if (switchDailyHomeworkReminder.isChecked) {
                    WorkScheduler.scheduleDailyHomeworkReminder(this, sharedPreferences)
                }
            },
            currentHour,
            currentMinute,
            true
        )

        timePickerDialog.setOnShowListener {
            val buttonColor = getThemeColor(R.attr.dialogSectionButtonColor)
            timePickerDialog.getButton(TimePickerDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
            timePickerDialog.getButton(TimePickerDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        }

        timePickerDialog.show()
    }

    private fun setupEmailButton() {
        val btnContactEmail = findViewById<Button>(R.id.btnContactEmail)
        btnContactEmail.setOnClickListener {
            openEmailClientFromSettings()
        }
    }

    private fun openEmailClientFromSettings() {
        val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = getString(R.string.set_act_hks_contact_email).toUri()
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.set_act_hks_contact))
            putExtra(Intent.EXTRA_TEXT, getString(R.string.set_act_hks_contact_extra))
        }

        try {
            startActivity(Intent.createChooser(emailIntent, getString(R.string.set_act_choose_email_app)))
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.set_act_no_email_app), Toast.LENGTH_LONG).show()
        }
    }

    private fun setupUpdateCooldownSetting() {
        val removeCooldown = sharedPreferences.getBoolean("remove_update_cooldown", true)
        switchRemoveUpdateCooldown.isChecked = removeCooldown
    }

    private fun setupColorBlindSpinner() {
        val spinner = findViewById<Spinner>(R.id.colorblind_spinner)
        val sharedPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)

        val adapter = ArrayAdapter.createFromResource(
            this,
            R.array.colorblind_modes,
            android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val currentMode = sharedPrefs.getString("colorblind_mode", "none")
        val values = resources.getStringArray(R.array.colorblind_values)
        val currentIndex = values.indexOf(currentMode)
        if (currentIndex >= 0) {
            spinner.setSelection(currentIndex)
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedValue = values[position]
                sharedPrefs.edit { putString("colorblind_mode", selectedValue) }
                L.d("Settings", "Colorblind mode saved to AppPrefs: $selectedValue")
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun initializeExamReminderViews() {
        switchExamDueDateReminder = findViewById(R.id.switchExamDueDateReminder)
        btnExamDueDateReminderDays = findViewById(R.id.btnExamDueDateReminderDays)

        setupExamReminderListeners()
    }

    private fun setupExamReminderListeners() {
        switchExamDueDateReminder.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit { putBoolean("exam_due_date_reminder_enabled", isChecked) }

            if (isChecked) {
                WorkScheduler.scheduleExamReminder(this, sharedPreferences)
            } else {
                WorkScheduler.cancelExamReminder(this)
            }

            //btnExamDueDateReminderDays.isEnabled = isChecked
        }

        btnExamDueDateReminderDays.setOnClickListener {
            showExamDueDateReminderDaysDialog()
        }
    }

    private fun loadExamReminderSettings() {
        val examDueDateReminderEnabled = sharedPreferences.getBoolean("exam_due_date_reminder_enabled", false)
        val examDueDateReminderDays = sharedPreferences.getInt("exam_due_date_reminder_days", 7)

        switchExamDueDateReminder.isChecked = examDueDateReminderEnabled

        val daysText = when (examDueDateReminderDays) {
            1 -> getString(R.string.set_act_one_day_before)
            in 2..6 -> getString(R.string.set_act_days_before_format, examDueDateReminderDays)
            7 -> getString(R.string.set_act_one_week_before)
            14 -> getString(R.string.set_act_two_weeks_before)
            21 -> getString(R.string.set_act_three_weeks_before)
            else -> getString(R.string.set_act_days_before_format, examDueDateReminderDays)
        }
        btnExamDueDateReminderDays.text = getString(R.string.set_act_exam_due_date_reminder_format, daysText)
    }

    private fun showExamDueDateReminderDaysDialog() {
        val currentDays = sharedPreferences.getInt("exam_due_date_reminder_days", 7)
        val daysOptions = arrayOf(
            getString(R.string.set_act_one_day_before), getString(R.string.set_act_two_days_before), getString(R.string.set_act_three_days_before),
            getString(R.string.set_act_four_days_before), getString(R.string.set_act_five_days_before), getString(R.string.set_act_six_days_before),
            getString(R.string.set_act_one_week_before), getString(R.string.set_act_two_weeks_before), getString(R.string.set_act_three_weeks_before)
        )
        val daysValues = arrayOf(1, 2, 3, 4, 5, 6, 7, 14, 21)

        val currentIndex = daysValues.indexOf(currentDays).let { if (it == -1) 6 else it } // default 1 week

        val alertDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.set_act_reminder_before_exam))
            .setSingleChoiceItems(daysOptions, currentIndex) { dialog, which ->
                val selectedDays = daysValues[which]
                sharedPreferences.edit { putInt("exam_due_date_reminder_days", selectedDays) }

                val daysText = daysOptions[which]
                btnExamDueDateReminderDays.text = getString(R.string.set_act_exam_due_date_reminder_format, daysText)

                if (switchExamDueDateReminder.isChecked) {
                    WorkScheduler.scheduleExamReminder(this, sharedPreferences)
                }

                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()

        val buttonColor = getThemeColor(R.attr.dialogSectionButtonColor)
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)
    }

    private fun setupOrientationSetting() {
        val switchLandscapeMode = findViewById<Switch>(R.id.switchLandscapeMode)

        val landscapeEnabled = sharedPreferences.getBoolean("landscape_mode_enabled", true)
        switchLandscapeMode.isChecked = landscapeEnabled

        applyOrientationSetting(landscapeEnabled)

        switchLandscapeMode.setOnCheckedChangeListener { _, isChecked ->
            if (!isInitializing) {
                sharedPreferences.edit {
                    putBoolean("landscape_mode_enabled", isChecked)
                }

                applyOrientationSetting(isChecked)

                Toast.makeText(this,
                    if (isChecked) getString(R.string.set_act_landscape_enabled)
                    else getString(R.string.set_act_landscape_disabled),
                    Toast.LENGTH_SHORT).show()
            } else {
                sharedPreferences.edit {
                    putBoolean("landscape_mode_enabled", isChecked)
                }
            }
        }
    }

    private fun applyOrientationSetting(landscapeEnabled: Boolean) {
        requestedOrientation = if (landscapeEnabled) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        if (!landscapeEnabled && resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        val intent = Intent().apply {
            action = "com.thecooker.vertretungsplaner.ORIENTATION_CHANGED"
            putExtra("landscape_enabled", landscapeEnabled)
        }
        sendBroadcast(intent)
    }

    private fun setupDarkModeSetting() {
        val switchDarkMode = findViewById<Switch>(R.id.switchDarkMode)
        val switchFollowSystem = findViewById<Switch>(R.id.switchFollowSystemTheme)

        val followSystemTheme = sharedPreferences.getBoolean("follow_system_theme", true)
        val darkModeEnabled = if (followSystemTheme) false else sharedPreferences.getBoolean("dark_mode_enabled", false)

        switchDarkMode.isChecked = darkModeEnabled
        switchFollowSystem.isChecked = followSystemTheme
        switchDarkMode.isEnabled = !followSystemTheme

        switchFollowSystem.setOnCheckedChangeListener { _, isChecked ->
            if (!isInitializing) {
                val previousFollowSystem = sharedPreferences.getBoolean("follow_system_theme", true)

                if (previousFollowSystem != isChecked) {
                    val willThemeChange = willThemeActuallyChange(isChecked, switchDarkMode.isChecked)

                    sharedPreferences.edit().apply {
                        putBoolean("follow_system_theme", isChecked)
                        if (isChecked) {
                            remove("dark_mode_enabled")
                        }
                        apply()
                    }

                    switchDarkMode.isEnabled = !isChecked
                    if (isChecked) {
                        switchDarkMode.isChecked = false
                    }

                    android.util.Log.d("Settings", "Saved follow_system_theme: $isChecked")

                    if (willThemeChange) {
                        restartAppSafely()
                    }
                }
            }
        }

        switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            if (!isInitializing) {
                val currentFollowSystem = sharedPreferences.getBoolean("follow_system_theme", true)

                if (!currentFollowSystem) {
                    val previousDarkMode = sharedPreferences.getBoolean("dark_mode_enabled", false)

                    if (previousDarkMode != isChecked) {
                        sharedPreferences.edit().apply {
                            putBoolean("dark_mode_enabled", isChecked)
                            apply()
                        }

                        android.util.Log.d("Settings", "Saved dark_mode_enabled: $isChecked")
                        restartAppSafely()
                    }
                }
            }
        }
    }

    private fun willThemeActuallyChange(newFollowSystem: Boolean, currentDarkModeEnabled: Boolean): Boolean {
        val currentFollowSystem = sharedPreferences.getBoolean("follow_system_theme", true)

        if (currentFollowSystem && !newFollowSystem) {
            val isSystemDark = (resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            return isSystemDark != currentDarkModeEnabled
        } else if (!currentFollowSystem && newFollowSystem) {
            val currentManualDark = sharedPreferences.getBoolean("dark_mode_enabled", false)
            val isSystemDark = (resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            return currentManualDark != isSystemDark
        }

        return false
    }

    private fun restartAppSafely() {
        Toast.makeText(this, getString(R.string.set_act_settings_saved), Toast.LENGTH_LONG).show()

        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)

            Handler(Looper.getMainLooper()).postDelayed({
                finishAffinity()
            }, 200)
        }, 500)
    }

    private fun setupCalendarSettings() {
        val switchCalendarRealTime = findViewById<Switch>(R.id.switchCalendarRealTime)

        val realTimeEnabled = sharedPreferences.getBoolean("calendar_real_time_enabled", true)
        switchCalendarRealTime.isChecked = realTimeEnabled

        switchCalendarRealTime.setOnCheckedChangeListener { _, isChecked ->
            if (!isInitializing) {
                sharedPreferences.edit {putBoolean("calendar_real_time_enabled", isChecked) }

                Toast.makeText(this,
                    if (isChecked) getString(R.string.set_act_real_time_calendar_enabled)
                    else getString(R.string.set_act_real_time_calendar_disabled),
                    Toast.LENGTH_SHORT).show()
            } else {
                sharedPreferences.edit {putBoolean("calendar_real_time_enabled", isChecked) }
            }
        }
    }

    private fun setupCalendarColorLegendSetting() {
        val colorLegendEnabled = sharedPreferences.getBoolean("calendar_color_legend_enabled", true)
        switchCalendarColorLegend.isChecked = colorLegendEnabled

        switchCalendarColorLegend.setOnCheckedChangeListener { _, isChecked ->
            if (!isInitializing) {
                sharedPreferences.edit {
                    putBoolean("calendar_color_legend_enabled", isChecked)
                }

                Toast.makeText(this,
                    if (isChecked) getString(R.string.act_set_color_legend_enabled)
                    else getString(R.string.act_set_color_legend_disabled),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                sharedPreferences.edit {
                    putBoolean("calendar_color_legend_enabled", isChecked)
                }
            }
        }
    }

    private fun setupCalendarWeekendSetting() {
        val includeWeekendsEnabled = sharedPreferences.getBoolean("calendar_include_weekends_dayview", false)
        switchCalendarWeekend.isChecked = includeWeekendsEnabled

        switchCalendarWeekend.setOnCheckedChangeListener { _, isChecked ->
            if (!isInitializing) {
                sharedPreferences.edit {
                    putBoolean(
                        "calendar_include_weekends_dayview",
                        isChecked
                    )
                }

                Toast.makeText(this,
                    if (isChecked) getString(R.string.set_act_weekends_enabled)
                    else getString(R.string.set_act_weekends_disabled),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                sharedPreferences.edit {putBoolean("calendar_include_weekends_dayview", isChecked)}
            }
        }
    }

    private fun setupBackUpManager() {
        backupManager = BackupManager(this)

        btnExportFullBackup.setOnClickListener {
            exportFullBackup()
        }

        btnImportFullBackup.setOnClickListener {
            importFullBackup()
        }
    }

    private fun exportFullBackup() {
        try {
            showSectionSelectionDialog(isExport = true) { selectedSections ->
                executeExport(selectedSections)
            }
        } catch (e: Exception) {
            L.e(TAG, "Error starting full backup", e)
            Toast.makeText(this, getString(R.string.set_act_backup_error, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun executeExport(enabledSections: Set<String>) {
        showProgressDialog(isExport = true, enabledSections = enabledSections) { progressDialog ->
            Thread {
                try {
                    val content = if (enabledSections.size == 6) {
                        backupManager.createFullBackup(progressDialog.progressCallback)
                    } else {
                        backupManager.createSelectiveBackup(enabledSections, progressDialog.progressCallback)
                    }

                    runOnUiThread {
                        sharedPreferences.edit { putString("temp_export_content", content) }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        progressDialog.dismiss()
                        L.e(TAG, "Error creating backup", e)
                        Toast.makeText(this@SettingsActivity, getString(R.string.set_act_error_while_backing_up, e.message), Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    private fun showSectionSelectionDialog(
        isExport: Boolean,
        availableSections: List<BackupManager.BackupSection>? = null,
        currentSelection: Set<String>? = null,
        onSectionsSelected: (Set<String>) -> Unit
    ) {
        val sections = availableSections ?: listOf(
            BackupManager.BackupSection("TIMETABLE_DATA", getString(R.string.set_act_timetable_data)),
            BackupManager.BackupSection("CALENDAR_DATA", getString(R.string.set_act_calendar_data)),
            BackupManager.BackupSection("HOMEWORK_DATA", getString(R.string.act_set_homework)),
            BackupManager.BackupSection("EXAM_DATA", getString(R.string.act_set_exams)),
            BackupManager.BackupSection("GRADE_DATA", getString(R.string.dlg_edit_sub_grades)),
            BackupManager.BackupSection("APP_SETTINGS", getString(R.string.set_act_app_settings))
        )

        val dialog = SectionSelectionDialog(this, isExport, sections, currentSelection)
        dialog.setCallback(object : SectionSelectionCallback {
            override fun onSectionsSelected(selectedSections: Set<String>) {
                onSectionsSelected(selectedSections)
            }

            override fun onSelectionCancelled() {
                // dialog cancelled
            }
        })
        dialog.show()
    }

    private fun showProgressDialog(
        isExport: Boolean,
        enabledSections: Set<String>? = null,
        onReady: (BackupProgressDialog) -> Unit
    ) {
        val progressDialog = BackupProgressDialog(this, isExport, backupManager, enabledSections)

        progressDialog.setDismissCallback(object : DialogDismissCallback {
            override fun onDialogDismissed(isExport: Boolean, wasSuccessful: Boolean) {
                if (isExport && wasSuccessful) {
                    val content = sharedPreferences.getString("temp_export_content", "")
                    if (!content.isNullOrEmpty()) {
                        showFullBackupExportOptions(content)
                        sharedPreferences.edit { remove("temp_export_content") }
                    }
                } else if (!isExport && wasSuccessful) {
                    showRestartRecommendationDialog()
                }
            }

            override fun onEditRequested(isExport: Boolean, currentSections: List<BackupManager.BackupSection>) {
                if (isExport) {
                    val currentSelection = currentSections.map { it.name }.toSet()
                    showSectionSelectionDialog(
                        isExport = true,
                        currentSelection = currentSelection
                    ) { selectedSections ->
                        executeExport(selectedSections)
                    }
                } else {
                    tempImportSections?.let { sections ->
                        val availableSections = sections.filter {
                            it.status != BackupManager.SectionStatus.FAILED
                        }
                        val currentSelection = currentSections
                            .filter { it.status == BackupManager.SectionStatus.SUCCESS }
                            .map { it.name }.toSet()

                        showSectionSelectionDialog(
                            isExport = false,
                            availableSections = availableSections,
                            currentSelection = currentSelection
                        ) { selectedSections ->
                            tempImportContent?.let { content ->
                                executeImport(content, selectedSections)
                            }
                        }
                    }
                }
            }
        })

        progressDialog.show()
        onReady(progressDialog)
    }

    private fun showFullBackupExportOptions(content: String) {
        //val content = backupManager.createFullBackup()
        val options = listOf(
            Pair(getString(R.string.set_act_backup_save_file), R.drawable.ic_export_file),
            Pair(getString(R.string.set_act_backup_copy_clipboard), R.drawable.ic_export_clipboard)
        )
        val adapter = object : ArrayAdapter<Pair<String, Int>>(
            this,
            android.R.layout.simple_list_item_1,
            options
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                val (text, iconRes) = getItem(position)!!
                view.text = text
                view.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
                view.compoundDrawablePadding = 16
                return view
            }
        }
        val alertDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.set_act_export_full_backup))
            .setAdapter(adapter) { _, which ->
                when (which) {
                    0 -> saveFullBackupToFile(content)
                    1 -> copyFullBackupToClipboard(content)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()

        val buttonColor = getThemeColor(R.attr.dialogSectionButtonColor)
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)
    }

    private fun saveFullBackupToFile(content: String) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.GERMAN).format(Date())
        val filename = "hks_vollbackup_${timestamp}${BackupManager.HKS_BACKUP_FILE_EXTENSION}"

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, filename)
        }

        sharedPreferences.edit { putString("temp_full_backup_content", content) }

        try {
            startActivityForResult(intent, FULL_BACKUP_EXPORT_REQUEST_CODE)
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.set_act_error_while_opening_file_dialog), Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyFullBackupToClipboard(content: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(getString(R.string.set_act_hks_full_backup), content)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(this, getString(R.string.set_act_full_backup_clipboard_success), Toast.LENGTH_SHORT).show()
    }

    private fun importFullBackup() {
        val options = listOf(
            Pair(getString(R.string.set_act_backup_import_file), R.drawable.ic_import_file),
            Pair(getString(R.string.set_act_import_clipboard), R.drawable.ic_import_clipboard)
        )
        val adapter = object : ArrayAdapter<Pair<String, Int>>(
            this,
            android.R.layout.simple_list_item_1,
            options
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                val (text, iconRes) = getItem(position)!!
                view.text = text
                view.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
                view.compoundDrawablePadding = 16
                return view
            }
        }
        val alertDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.set_act_import_full_backup))
            .setAdapter(adapter) { _, which ->
                when (which) {
                    0 -> importFullBackupFromFile()
                    1 -> importFullBackupFromClipboard()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()

        val buttonColor = getThemeColor(R.attr.dialogSectionButtonColor)
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)
    }

    private fun importFullBackupFromFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "text/plain"
            addCategory(Intent.CATEGORY_OPENABLE)
        }

        try {
            startActivityForResult(
                Intent.createChooser(intent, getString(R.string.set_act_select_hks_backup_file)),
                FULL_BACKUP_IMPORT_REQUEST_CODE
            )
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.set_act_no_file_manager_app_found), Toast.LENGTH_SHORT).show()
        }
    }

    private fun importFullBackupFromClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip

        if (clipData != null && clipData.itemCount > 0) {
            val clipText = clipData.getItemAt(0).text?.toString()
            if (!clipText.isNullOrEmpty()) {
                processFullBackupImport(clipText)
            } else {
                Toast.makeText(this, getString(R.string.set_act_clipboard_is_empty), Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, getString(R.string.set_act_clipboard_is_empty), Toast.LENGTH_SHORT).show()
        }
    }

    private fun processFullBackupImport(content: String) {
        try {
            val availableSections = backupManager.analyzeBackupSections(content)
            tempImportContent = content
            tempImportSections = availableSections

            val sectionsWithData = availableSections.filter {
                it.status != BackupManager.SectionStatus.EMPTY
            }

            if (sectionsWithData.isEmpty()) {
                Toast.makeText(this, getString(R.string.set_act_backup_no_data), Toast.LENGTH_LONG).show()
                return
            }

            showSectionSelectionDialog(
                isExport = false,
                availableSections = availableSections
            ) { selectedSections ->
                showFullBackupImportConfirmationDialog(content, selectedSections)
            }

        } catch (e: Exception) {
            L.e(TAG, "Error processing full backup import", e)
            Toast.makeText(this, getString(R.string.set_act_backup_analyze_error, e.message), Toast.LENGTH_LONG).show()
        }
    }


    private fun showFullBackupImportConfirmationDialog(content: String, selectedSections: Set<String>) {
        val sectionNames = listOf(
            "TIMETABLE_DATA" to getString(R.string.set_act_timetable),
            "CALENDAR_DATA" to getString(R.string.set_act_calendar_data),
            "HOMEWORK_DATA" to getString(R.string.act_set_homework),
            "EXAM_DATA" to getString(R.string.act_set_exams),
            "GRADE_DATA" to getString(R.string.dlg_edit_sub_grades),
            "APP_SETTINGS" to getString(R.string.set_act_app_settings)
        ).toMap()

        val selectedSectionsList = selectedSections.mapNotNull { sectionNames[it] }
        val sectionText = selectedSectionsList.joinToString("\n• ", "• ")

        val message = buildString {
            append(getString(R.string.set_act_backup_sections_restore))
            append("\n\n")
            append(sectionText)
            append("\n\n")
            append(getString(R.string.set_act_backup_data_replaced))
            append("\n\n")
            append(getString(R.string.set_act_backup_no_undo))
        }

        val alertDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.set_act_backup_dialog_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.set_act_backup_restore)) { _, _ ->
                executeImport(content, selectedSections)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()

        val buttonColor = getThemeColor(R.attr.dialogSectionButtonColor)
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)
    }

    private fun executeImport(content: String, enabledSections: Set<String>) {
        showProgressDialog(isExport = false, enabledSections = enabledSections) { progressDialog ->
            Thread {
                try {
                    val result = if (enabledSections.size == 6) {
                        backupManager.restoreFromBackup(content, progressDialog.progressCallback)
                    } else {
                        backupManager.restoreSelectiveBackup(content, enabledSections, progressDialog.progressCallback)
                    }

                    runOnUiThread {
                        if (result.success) {
                            loadCurrentSelection()
                            updateTimetableButton()
                            setupFilterSwitch()

                            val hasEmptySections = result.restoredSections < result.totalSections
                            val message = if (hasEmptySections) {
                                getString(R.string.set_act_backup_restore_partial, result.restoredSections, result.totalSections)
                            } else {
                                getString(R.string.set_act_backup_restore_success, result.restoredSections, result.totalSections)
                            }

                            Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
                        } else {
                            val errorMessage = if (result.errors.isEmpty()) {
                                getString(R.string.set_act_unknown_error_while_recovering)
                            } else {
                                getString(R.string.set_act_backup_restore_partial_errors, result.restoredSections, result.totalSections, result.errors.joinToString(", "))
                            }

                            Toast.makeText(this@SettingsActivity, errorMessage, Toast.LENGTH_LONG).show()
                        }
                    }

                } catch (e: Exception) {
                    runOnUiThread {
                        L.e(TAG, "Error executing backup restore", e)
                        Toast.makeText(this@SettingsActivity, getString(R.string.set_act_backup_restore_error, e.message), Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    private fun showRestartRecommendationDialog() {
        val alertDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.set_act_restart_recommended))
            .setMessage(getString(R.string.set_act_restart_message))
            .setPositiveButton(getString(R.string.restart_now)) { _, _ ->
                restartApp()
            }
            .setNegativeButton(getString(R.string.set_act_restart_later), null)
            .show()

        val buttonColor = getThemeColor(R.attr.dialogSectionButtonColor)
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)
    }

    private fun restartApp() {
        val savedLanguage = LanguageUtil.getSavedLanguage(this)
        LanguageUtil.setLanguage(this, savedLanguage)

        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
            finish()
            exitProcess(0)
        }
    }

    private fun writeFullBackupToFileUri(uri: Uri) {
        val content = sharedPreferences.getString("temp_full_backup_content", "")
        if (content.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.set_act_backup_no_export_content), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(content.toByteArray())
            }

            sharedPreferences.edit {remove("temp_full_backup_content") }

            Toast.makeText(this, getString(R.string.set_act_backup_success), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            L.e(TAG, "Error writing full backup to file", e)
            Toast.makeText(this, getString(R.string.set_act_backup_save_error, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun readFullBackupFromFileUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val content = inputStream.bufferedReader().readText()
                processFullBackupImport(content)
            }
        } catch (e: Exception) {
            L.e(TAG, "Error reading full backup from file", e)
            Toast.makeText(this, getString(R.string.set_act_backup_import_error, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun setupLanguageSettings() {
        val switchAutoDetectLanguage = findViewById<Switch>(R.id.switchAutoDetectLanguage)
        val layoutManualLanguage = findViewById<LinearLayout>(R.id.layoutManualLanguage)
        val btnSelectLanguage = findViewById<Button>(R.id.btnSelectLanguage)
        val tvCurrentLanguage = findViewById<TextView>(R.id.tvCurrentLanguage)

        val autoDetect = sharedPreferences.getBoolean("language_auto_detect", true)
        val savedLanguage = sharedPreferences.getString("selected_language", "de") ?: "de"

        switchAutoDetectLanguage.isChecked = autoDetect
        layoutManualLanguage.visibility = if (autoDetect) View.GONE else View.VISIBLE

        updateCurrentLanguageDisplay(tvCurrentLanguage)

        val languageName = if (savedLanguage == "de") getString(R.string.german) else getString(R.string.english)
        btnSelectLanguage.text = languageName

        switchAutoDetectLanguage.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit { putBoolean("language_auto_detect", isChecked) }
            layoutManualLanguage.visibility = if (isChecked) View.GONE else View.VISIBLE
            updateCurrentLanguageDisplay(tvCurrentLanguage)
            showRestartDialog()
        }

        btnSelectLanguage.setOnClickListener {
            showLanguageSelectionDialog(btnSelectLanguage)
        }
    }

    private fun updateCurrentLanguageDisplay(tvCurrentLanguage: TextView) {
        val autoDetect = sharedPreferences.getBoolean("language_auto_detect", true)

        val displayText = if (autoDetect) {
            val currentLocale = resources.configuration.locales[0]
            val languageCode = currentLocale.language
            when (languageCode) {
                "de" -> getString(R.string.current_language_german)
                "en" -> getString(R.string.current_language_english)
                else -> getString(R.string.current_language_german) // fallback
            }
        } else {
            val selectedLanguage = sharedPreferences.getString("selected_language", "de") ?: "de"
            when (selectedLanguage) {
                "de" -> getString(R.string.current_language_german)
                "en" -> getString(R.string.current_language_english)
                else -> getString(R.string.current_language_german) // fallback
            }
        }

        tvCurrentLanguage.text = displayText
    }

    private fun showLanguageSelectionDialog(btnSelectLanguage: Button) {
        val currentLanguage = sharedPreferences.getString("selected_language", "de") ?: "de"

        val languages = arrayOf("de", "en")
        val languageNames = arrayOf(
            "🇩🇪 ${getString(R.string.german)}",
            "🇺🇸 ${getString(R.string.english)}"
        )

        val currentIndex = languages.indexOf(currentLanguage).let { if (it == -1) 0 else it }

        val alertDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_language))
            .setSingleChoiceItems(languageNames, currentIndex) { dialog, which ->
                val selectedLanguageCode = languages[which]
                val selectedLanguageName = languageNames[which]

                sharedPreferences.edit { putString("selected_language", selectedLanguageCode) }
                btnSelectLanguage.text = selectedLanguageName
                showRestartDialog()

                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()

        val buttonColor = getThemeColor(R.attr.dialogSectionButtonColor)
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)
    }

    private fun showRestartDialog() {
        val alertDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.restart_required))
            .setMessage(getString(R.string.restart_required_message))
            .setPositiveButton(getString(R.string.restart_now)) { _, _ ->
                restartApp()
            }
            .setNegativeButton(getString(R.string.restart_later), null)
            .show()

        val buttonColor = getThemeColor(R.attr.dialogSectionButtonColor)
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)
    }

    private fun storeOriginalBackground(view: View) {
        try {
            val background = view.background
            if (background is android.graphics.drawable.ColorDrawable) {
                originalBackgroundColors[view] = background.color
            }
        } catch (_: Exception) {
        }
    }

    private fun performSearch(query: String) {
        if (query.isEmpty()) {
            clearHighlights()
            hideNoResultsMessage()
            currentMatches = emptyList()
            currentMatchIndex = 0
            return
        }

        if (isGreetingQuery(query)) {
            clearHighlights()
            showGreetingMessage()
            currentMatches = emptyList()
            currentMatchIndex = 0
            return
        }

        val matches = findMatches(query)

        if (matches.any { isViewInAppInfoSection(it.first.view) } && !isAppInfoExpanded) {
            expandAppInfoSection()
        }

        highlightMatches(matches, query)

        currentMatches = matches
        currentMatchIndex = 0

        L.d(TAG, "Search for '$query' found ${matches.size} matches")
    }

    private fun expandAppInfoSection() {
        isAppInfoExpanded = true
        updateAppInfoSectionUI()
    }

    private fun isViewInAppInfoSection(view: View): Boolean {
        var parent = view.parent
        while (parent != null) {
            if (parent == layoutAppInfoContent) {
                return true
            }
            parent = parent.parent
        }
        return false
    }

    private fun isGreetingQuery(query: String): Boolean {
        val queryLower = query.lowercase().trim()
        val greetingKeywords = getString(R.string.set_act_greeting_keywords)
            .split(",")
            .map { it.trim().lowercase() }

        return greetingKeywords.any { keyword ->
            queryLower == keyword || queryLower.startsWith("$keyword ")
        }
    }

    private fun showGreetingMessage() {
        val noResultsContainer = findViewById<LinearLayout>(R.id.noResultsContainer)
        noResultsTextView?.text = getString(R.string.set_act_greeting_response)
        noResultsContainer?.visibility = View.VISIBLE
    }

    private fun findMatches(query: String): List<Pair<SearchableView, Int>> {
        val queryWords = query.lowercase().split("\\s+".toRegex()).filter { it.isNotEmpty() }
        val matches = mutableListOf<Pair<SearchableView, Int>>()

        for (searchableView in allSearchableViews) {
            val text = searchableView.text.lowercase()
            var score = 0

            if (text.contains(query.lowercase())) {
                score += 100 * searchableView.priority
            }

            for (word in queryWords) {
                if (text.contains(word)) {
                    score += 10 * searchableView.priority
                }
            }

            for (word in queryWords) {
                if (isCloseMatch(text, word)) {
                    score += 5 * searchableView.priority
                }
            }

            if (score > 0) {
                matches.add(Pair(searchableView, score))
            }
        }

        return matches.sortedWith { a, b ->
            when {
                a.second != b.second -> b.second.compareTo(a.second)
                a.first.priority != b.first.priority -> b.first.priority.compareTo(a.first.priority)
                else -> 0
            }
        }
    }

    private fun isCloseMatch(text: String, word: String): Boolean {
        if (word.length < 3) return false

        val textWords = text.split("\\s+".toRegex())
        for (textWord in textWords) {
            if (textWord.length == word.length) {
                var differences = 0
                for (i in word.indices) {
                    if (i < textWord.length && textWord[i] != word[i]) {
                        differences++
                        if (differences > 1) break
                    }
                }
                if (differences <= 1) return true
            }
        }
        return false
    }

    private fun highlightMatches(matches: List<Pair<SearchableView, Int>>, query: String) {
        clearHighlights()

        if (matches.isEmpty()) {
            showNoResultsMessage()
            return
        } else {
            hideNoResultsMessage()
        }

        val highlightColor = "#FFFF8C".toColorInt()

        for ((searchableView, _) in matches) {
            val view = searchableView.view

            when (view) {
                is TextView -> {
                    highlightTextInTextView(view, query, highlightColor)
                }
            }
        }
    }

    private fun highlightTextInTextView(textView: TextView, query: String, highlightColor: Int) {
        val text = textView.text.toString()
        val spannableText = android.text.SpannableStringBuilder(text)
        val queryLower = query.lowercase()
        val textLower = text.lowercase()

        var startIndex = 0
        while (true) {
            val index = textLower.indexOf(queryLower, startIndex)
            if (index == -1) break

            val endIndex = index + query.length
            val highlightSpan = android.text.style.BackgroundColorSpan(highlightColor)
            spannableText.setSpan(highlightSpan, index, endIndex, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

            startIndex = endIndex
        }

        textView.text = spannableText
    }

    private fun clearHighlights() {
        hideNoResultsMessage()

        for (searchableView in allSearchableViews) {
            val view = searchableView.view

            when (view) {
                is TextView -> {
                    view.text = searchableView.text
                }
            }
        }

        currentMatches = emptyList()
        currentMatchIndex = 0
    }

    private fun showNoResultsMessage() {
        val noResultsContainer = findViewById<LinearLayout>(R.id.noResultsContainer)
        noResultsTextView?.text = getString(R.string.settings_no_results)
        noResultsContainer?.visibility = View.VISIBLE
    }

    private fun hideNoResultsMessage() {
        val noResultsContainer = findViewById<LinearLayout>(R.id.noResultsContainer)
        noResultsContainer?.visibility = View.GONE
    }

    private var currentMatchIndex = 0
    private var currentMatches = listOf<Pair<SearchableView, Int>>()

    data class ValidationResult(
        val isValid: Boolean,
        val errorMessage: String,
        val schuljahr: String,
        val klasse: String,
        val subjects: List<String>
    )

    private fun showClassAdvancementDialog() {
        val currentBildungsgang = sharedPreferences.getString("selected_bildungsgang", "") ?: ""
        val currentKlasse = sharedPreferences.getString("selected_klasse", "") ?: ""

        if (currentBildungsgang.isEmpty() || currentKlasse.isEmpty()) {
            Toast.makeText(this, getString(R.string.act_set_no_current_class), Toast.LENGTH_SHORT).show()
            return
        }

        val availableClasses = getClassesForBildungsgang(currentBildungsgang).filter { it != currentKlasse }

        if (availableClasses.isEmpty()) {
            Toast.makeText(this, getString(R.string.act_set_no_other_classes), Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_class_advancement, null)
        val spinnerNewClass = dialogView.findViewById<Spinner>(R.id.spinnerNewClass)
        val checkboxPreserveGrades = dialogView.findViewById<CheckBox>(R.id.checkboxPreserveGrades)
        val tvAdvancementType = dialogView.findViewById<TextView>(R.id.tvAdvancementType)
        val btnContinue = dialogView.findViewById<Button>(R.id.btnContinue)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        val classOptions = mutableListOf(getString(R.string.act_set_select_new_class))
        classOptions.addAll(availableClasses)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, classOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerNewClass.adapter = adapter

        btnContinue.isEnabled = false

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        val buttonColor = getThemeColor(R.attr.dialogSectionButtonColor)
        val grayColor = ContextCompat.getColor(this, android.R.color.darker_gray)

        btnContinue.setTextColor(grayColor)
        btnCancel.setTextColor(buttonColor)

        spinnerNewClass.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 0) {
                    btnContinue.isEnabled = false
                    btnContinue.setTextColor(grayColor)
                    return
                }

                val selectedClass = availableClasses[position - 1]
                val advancementType = determineAdvancementType(currentKlasse, selectedClass)

                updateAdvancementDialog(advancementType, tvAdvancementType, checkboxPreserveGrades)
                btnContinue.isEnabled = true
                btnContinue.setTextColor(buttonColor)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                btnContinue.isEnabled = false
                btnContinue.setTextColor(grayColor)
            }
        }

        checkboxPreserveGrades.setOnCheckedChangeListener { _, isChecked ->
            val selectedPosition = spinnerNewClass.selectedItemPosition
            if (selectedPosition > 0) {
                val selectedClass = availableClasses[selectedPosition - 1]
                val advancementType = determineAdvancementType(currentKlasse, selectedClass)

                if (!isChecked && advancementType != AdvancementType.ASCENDING) {
                    showGradePreservationConfirmationDialog { confirmed ->
                        if (!confirmed) {
                            checkboxPreserveGrades.isChecked = true
                        }
                    }
                }
            }
        }

        btnContinue.setOnClickListener {
            val selectedPosition = spinnerNewClass.selectedItemPosition
            if (selectedPosition > 0) {
                val selectedClass = availableClasses[selectedPosition - 1]
                val preserveGrades = checkboxPreserveGrades.isChecked
                executeClassAdvancement(currentKlasse, selectedClass, preserveGrades)
                dialog.dismiss()
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun getClassesForBildungsgang(bildungsgang: String): List<String> {
        val bildungsgangData = mapOf(
            "AM" to listOf("10AM", "11AM", "12AM"),
            "AO" to listOf("10AO1", "10AO2", "10AO3", "10AO4", "11AO1", "11AO2", "11AO3", "11AO4", "12AO1", "12AO2", "12AO3", "12AO4"),
            "AOB" to listOf("11AOB", "12AOB"),
            "AOW" to listOf("12AOW"),
            "BFS" to listOf("10BFS1", "10BFS2", "11BFS1", "11BFS2"),
            "BG" to listOf("11BG1", "11BG2", "11BG3", "12BG1", "12BG2", "13BG1", "13BG2"),
            "BzB" to listOf("10BzB1", "10BzB2", "10BzB3"),
            "EL" to listOf("10EL1", "10EL2", "10EL3", "11EL1", "11EL2", "11EL3", "12EL1", "12EL2", "12ELW"),
            "EZ" to listOf("10EZ1", "10EZ2", "11EZ1", "11EZ2", "12EZ1", "12EZ2"),
            "FOS" to listOf("11FOS1", "11FOS2", "12FOS1", "12FOS2"),
            "FS" to listOf("02FS", "03FS", "04FS"),
            "Förd" to listOf("Förd"),
            "IM" to listOf("10IM1", "10IM2", "11IM1", "11IM2", "11IM3", "12IM1", "12IM2", "13IM"),
            "KB" to listOf("10KB1", "10KB3", "11KB2", "11KB3", "12KB1", "12KB2", "13KB"),
            "KM" to listOf("10KM1", "10KM2", "10KM3", "10KM4", "10KM5", "11KM1", "11KM2", "11KM3", "11KM4", "11KM5", "12KM1", "12KM2", "12KM3", "12KM4", "12KM5", "13KM1", "13KM2", "13KM3"),
            "KOM" to listOf("10KOM", "11KOM", "12KOM", "13KOM"),
            "ME" to listOf("10ME1", "10ME2", "10ME3", "10ME4", "11ME1", "11ME2", "11ME3", "11ME4", "12ME1", "12ME2", "12ME3", "12ME4", "13ME1", "13ME2"),
            "ZM" to listOf("11ZM", "12ZM"),
            "ZU" to listOf("12ZU1"),
            "ZW" to listOf("10ZW1", "10ZW2", "10ZW4", "11ZW1", "11ZW2", "11ZW3", "11ZW4", "12ZW1", "12ZW2", "12ZW4", "13ZW1", "13ZW2")
        )

        return bildungsgangData[bildungsgang] ?: emptyList()
    }

    private enum class AdvancementType {
        ASCENDING, DESCENDING, SAME_YEAR_DIFFERENT_COURSE
    }

    private fun determineAdvancementType(currentClass: String, newClass: String): AdvancementType {
        val currentYear = extractYearFromClass(currentClass)
        val newYear = extractYearFromClass(newClass)

        return when {
            newYear > currentYear -> AdvancementType.ASCENDING
            newYear < currentYear -> AdvancementType.DESCENDING
            newYear == currentYear -> AdvancementType.SAME_YEAR_DIFFERENT_COURSE
            else -> AdvancementType.ASCENDING
        }
    }

    private fun extractYearFromClass(className: String): Int {
        val yearMatch = Regex("^(\\d+)").find(className)
        return yearMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun updateAdvancementDialog(
        type: AdvancementType,
        tvAdvancementType: TextView,
        checkboxPreserveGrades: CheckBox
    ) {
        when (type) {
            AdvancementType.ASCENDING -> {
                tvAdvancementType.text = getString(R.string.act_set_advancing_class)
                tvAdvancementType.setTextColor(getColor(R.color.green))
                checkboxPreserveGrades.text = getString(R.string.act_set_preserve_grades_data)
                checkboxPreserveGrades.isChecked = true
            }
            AdvancementType.DESCENDING -> {
                tvAdvancementType.text = getString(R.string.act_set_descending_class)
                tvAdvancementType.setTextColor(getColor(R.color.orange))
                checkboxPreserveGrades.text = getString(R.string.act_set_remove_last_year_data)
                checkboxPreserveGrades.isChecked = true
            }
            AdvancementType.SAME_YEAR_DIFFERENT_COURSE -> {
                tvAdvancementType.text = getString(R.string.act_set_repeating_year)
                tvAdvancementType.setTextColor(getColor(R.color.orange))
                checkboxPreserveGrades.text = getString(R.string.act_set_remove_last_year_data)
                checkboxPreserveGrades.isChecked = true
            }
        }
    }

    private fun showGradePreservationConfirmationDialog(callback: (Boolean) -> Unit) {
        val alertDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.act_set_confirm_grade_deletion))
            .setMessage(getString(R.string.act_set_confirm_grade_deletion_message))
            .setPositiveButton(getString(R.string.act_set_delete_grades)) { _, _ ->
                callback(true)
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                callback(false)
            }
            .show()

        val buttonColor = getThemeColor(R.attr.dialogSectionButtonColor)
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)
    }

    private fun executeClassAdvancement(currentClass: String, newClass: String, preserveGrades: Boolean) {
        val advancementType = determineAdvancementType(currentClass, newClass)

        var gradeDataToPreserve: String? = null
        if (preserveGrades) {
            try {
                gradeDataToPreserve = backupManager.exportGradeData()
            } catch (e: Exception) {
                L.e(TAG, "Error backing up grades before class advancement", e)
            }
        }

        val settingsToPreserve = mutableMapOf<String, Any?>()

        if (preserveGrades) {
            settingsToPreserve["grades_subjects"] = sharedPreferences.getString("grades_subjects", "")
            settingsToPreserve["grades_teachers"] = sharedPreferences.getString("grades_teachers", "")
            settingsToPreserve["grades_rooms"] = sharedPreferences.getString("grades_rooms", "")
        }

        settingsToPreserve["language_auto_detect"] = sharedPreferences.getBoolean("language_auto_detect", true)
        settingsToPreserve["selected_language"] = sharedPreferences.getString("selected_language", "de")
        settingsToPreserve["dark_mode_enabled"] = sharedPreferences.getBoolean("dark_mode_enabled", false)
        settingsToPreserve["follow_system_theme"] = sharedPreferences.getBoolean("follow_system_theme", true)
        settingsToPreserve["landscape_mode_enabled"] = sharedPreferences.getBoolean("landscape_mode_enabled", true)
        settingsToPreserve["colorblind_mode"] = sharedPreferences.getString("colorblind_mode", "none")
        settingsToPreserve["startup_page_index"] = sharedPreferences.getInt("startup_page_index", 1)
        settingsToPreserve["auto_update_enabled"] = sharedPreferences.getBoolean("auto_update_enabled", false)
        settingsToPreserve["auto_update_time"] = sharedPreferences.getString("auto_update_time", "06:00")
        settingsToPreserve["update_wifi_only"] = sharedPreferences.getBoolean("update_wifi_only", false)
        settingsToPreserve["show_update_notifications"] = sharedPreferences.getBoolean("show_update_notifications", true)
        settingsToPreserve["change_notification_enabled"] = sharedPreferences.getBoolean("change_notification_enabled", false)
        settingsToPreserve["change_notification_interval"] = sharedPreferences.getInt("change_notification_interval", 15)
        settingsToPreserve["change_notification_type"] = sharedPreferences.getString("change_notification_type", "all_class_subjects")
        settingsToPreserve["due_date_reminder_enabled"] = sharedPreferences.getBoolean("due_date_reminder_enabled", false)
        settingsToPreserve["due_date_reminder_hours"] = sharedPreferences.getInt("due_date_reminder_hours", 16)
        settingsToPreserve["daily_homework_reminder_enabled"] = sharedPreferences.getBoolean("daily_homework_reminder_enabled", false)
        settingsToPreserve["daily_homework_reminder_time"] = sharedPreferences.getString("daily_homework_reminder_time", "19:00")
        settingsToPreserve["exam_due_date_reminder_enabled"] = sharedPreferences.getBoolean("exam_due_date_reminder_enabled", false)
        settingsToPreserve["exam_due_date_reminder_days"] = sharedPreferences.getInt("exam_due_date_reminder_days", 7)
        settingsToPreserve["remove_update_cooldown"] = sharedPreferences.getBoolean("remove_update_cooldown", true)
        settingsToPreserve["calendar_real_time_enabled"] = sharedPreferences.getBoolean("calendar_real_time_enabled", true)
        settingsToPreserve["calendar_include_weekends_dayview"] = sharedPreferences.getBoolean("calendar_include_weekends_dayview", false)
        settingsToPreserve["left_filter_lift"] = sharedPreferences.getBoolean("left_filter_lift", false)

        sharedPreferences.edit { clear() }

        WorkScheduler.cancelAutoUpdate(this)
        WorkScheduler.cancelChangeNotification(this)
        WorkScheduler.cancelDueDateReminder(this)
        WorkScheduler.cancelDailyHomeworkReminder(this)
        WorkScheduler.cancelExamReminder(this)

        sharedPreferences.edit {
            for ((key, value) in settingsToPreserve) {
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                }
            }

            val newBildungsgang = extractBildungsgangFromClass(newClass)
            putBoolean("setup_completed", true)
            putString("selected_bildungsgang", newBildungsgang)
            putString("selected_klasse", newClass)
        }

        if (preserveGrades && !gradeDataToPreserve.isNullOrEmpty()) {
            try {
                when (advancementType) {
                    AdvancementType.ASCENDING -> {
                        backupManager.importGradeData(gradeDataToPreserve)
                    }
                    AdvancementType.DESCENDING, AdvancementType.SAME_YEAR_DIFFERENT_COURSE -> {
                        val currentYear = extractYearFromClass(currentClass)
                        val newYear = extractYearFromClass(newClass)
                        val yearsToRemove = maxOf(1, currentYear - newYear)

                        backupManager.importGradeData(gradeDataToPreserve)
                        removeLastYearsGradeData(yearsToRemove)
                    }
                }
            } catch (e: Exception) {
                L.e(TAG, "Error restoring grades after class advancement", e)
            }
        }

        loadCurrentSelection()
        updateTimetableButton()
        setupFilterSwitch()

        val message = when (advancementType) {
            AdvancementType.ASCENDING -> getString(R.string.act_set_class_advanced_successfully, newClass)
            AdvancementType.DESCENDING -> getString(R.string.act_set_class_descended_successfully, newClass)
            AdvancementType.SAME_YEAR_DIFFERENT_COURSE -> getString(R.string.act_set_class_changed_successfully, newClass)
        }

        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun extractBildungsgangFromClass(className: String): String {
        return className.replace(Regex("\\d"), "")
    }

    private fun removeLastYearsGradeData(yearsToRemove: Int) {
        try {
            val currentHalfyear = sharedPreferences.getInt("current_halfyear", 1)

            val halfyearsToRemove = mutableSetOf<Int>()
            for (year in 1..yearsToRemove) {
                for (halfyear in 1..4) {
                    val targetHalfyear = currentHalfyear - ((year - 1) * 4) - (4 - halfyear + 1)
                    if (targetHalfyear > 0) {
                        halfyearsToRemove.add(targetHalfyear)
                    }
                }
            }

            val oralGradesHistoryJson = sharedPreferences.getString("oral_grades_history", "{}")
            val oralGradesHistoryType = object : com.google.gson.reflect.TypeToken<Map<String, MutableMap<Int, Double>>>() {}.type
            val oralGradesHistory: MutableMap<String, MutableMap<Int, Double>> = try {
                com.google.gson.Gson().fromJson(oralGradesHistoryJson, oralGradesHistoryType) ?: mutableMapOf()
            } catch (e: Exception) {
                mutableMapOf()
            }

            for (subjectGrades in oralGradesHistory.values) {
                halfyearsToRemove.forEach { halfyear ->
                    subjectGrades.remove(halfyear)
                }
            }

            sharedPreferences.edit {
                putString("oral_grades_history", com.google.gson.Gson().toJson(oralGradesHistory))
            }

            L.d(TAG, "Removed grade data for halfyears: $halfyearsToRemove")

        } catch (e: Exception) {
            L.e(TAG, "Error removing last year's grade data", e)
        }
    }

    private fun toggleAppInfoSection() {
        isAppInfoExpanded = !isAppInfoExpanded
        updateAppInfoSectionUI()
    }

    private fun updateAppInfoSectionUI() {
        layoutAppInfoContent.visibility = if (isAppInfoExpanded) View.VISIBLE else View.GONE

        if (isAppInfoExpanded) {
            btnAppInfoToggle.text = getString(R.string.act_set_hide_app_info)
            btnAppInfoToggle.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_expand_less, 0)
        } else {
            btnAppInfoToggle.text = getString(R.string.act_set_show_app_info)
            btnAppInfoToggle.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_expand_more, 0)
        }
    }

    private fun setupAppInfoSection() {
        isAppInfoExpanded = false
        updateAppInfoSectionUI()
    }
}

data class ImportResult(
    val isValid: Boolean,
    val errorMessage: String,
    val schuljahr: String,
    val klasse: String,
    val bildungsgang: String,
    val subjects: List<String>,
    val teachers: List<String>,
    val rooms: List<String>,
    val alternativeRooms: String = "{}"
)
