package com.thecooker.vertretungsplaner

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.os.Build
import android.provider.Settings
import android.view.ViewGroup
import android.widget.ImageView
import com.thecooker.vertretungsplaner.ui.slideshow.HomeworkUtils
import android.widget.AdapterView
import androidx.appcompat.app.AppCompatDelegate
import com.thecooker.vertretungsplaner.utils.BackupManager
import org.json.JSONObject
import kotlin.system.exitProcess
import androidx.core.content.edit
import androidx.core.net.toUri

class SettingsActivity : AppCompatActivity() {

    private var isInitializing = true

    private lateinit var sharedPreferences: SharedPreferences
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        isInitializing = true

        initializeViews()
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
        setupBackUpManager()
        setupFilterLift()

        isInitializing = false
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
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = "Einstellungen"
        }
    }

    private fun setupListeners() {
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
                    if (isChecked) "Nur eigene Fächer werden angezeigt"
                    else "Alle Fächer werden angezeigt",
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
                    if (isChecked) "Update-Cooldown deaktiviert"
                    else "Update-Cooldown aktiviert",
                    Toast.LENGTH_SHORT).show()
            } else {
                sharedPreferences.edit {
                    putBoolean("remove_update_cooldown", isChecked)
                }
            }
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
        val bildungsgang = sharedPreferences.getString("selected_bildungsgang", "Nicht ausgewählt")
        val klasse = sharedPreferences.getString("selected_klasse", "Nicht ausgewählt")

        val selectionText = "Bildungsgang: $bildungsgang\nKlasse: $klasse"
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
        editor.putString("scanned_document_info", "Klasse: $klasse")

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
        Toast.makeText(this,
            "Stundenplan erfolgreich gespeichert\n${selectedSubjects.size} Fächer ausgewählt",
            Toast.LENGTH_SHORT).show()

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
        Toast.makeText(this, "Stundenplan gelöscht", Toast.LENGTH_SHORT).show()
    }

    private fun updateTimetableButton() {
        if (isDocumentScanned()) {
            "Stundenplan löschen".also { btnScanTimetable.text = it }
            val documentInfo = sharedPreferences.getString("scanned_document_info", "")
            val selectedSubjects = sharedPreferences.getString("student_subjects", "")?.split(",") ?: emptyList()
            val alternativeRooms = sharedPreferences.getString("alternative_rooms", "")
            val subjectInfo = if (selectedSubjects.isNotEmpty() && selectedSubjects.first().isNotBlank()) {
                val subjectCount = if (!alternativeRooms.isNullOrBlank()) JSONObject(
                    alternativeRooms
                ).length() else 0
                "\nAusgewählte Fächer: ${selectedSubjects.size} ($subjectCount mit alt. Räumen)"
            } else {
                "\nKeine Fächer ausgewählt"
            }
            "Gescanntes Dokument:\n$documentInfo$subjectInfo".also { tvScannedDocument.text = it }
            tvScannedDocument.visibility = TextView.VISIBLE
        } else {
            "Stundenplan einscannen".also { btnScanTimetable.text = it }
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
                Intent.createChooser(intent, "PDF-Datei auswählen"),
                PDF_PICKER_REQUEST_CODE
            )
        } catch (_: Exception) {
            Toast.makeText(this, "Keine PDF-Reader App gefunden", Toast.LENGTH_SHORT).show()
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
                            "Ungültige PDF-Datei: ${validationResult.errorMessage}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                L.e(TAG, "Error processing pdf", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@SettingsActivity,
                        "Fehler beim Lesen der PDF: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun extractTextFromPdf(uri: Uri): String { // dont ask what this does🗣️
        val inputStream = contentResolver.openInputStream(uri) ?: throw Exception("Konnte PDF nicht öffnen")

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

        // Extract school year
        val schuljahrRegex = Regex("Schuljahr\\s+(\\d{4}/\\d{4})")
        val schuljahrMatch = schuljahrRegex.find(text)
        val schuljahr = schuljahrMatch?.groupValues?.get(1)

        L.d(TAG, "Found Schuljahr: $schuljahr")

        if (schuljahr == null) {
            return ValidationResult(false, "Schuljahr nicht gefunden", "", "", emptyList())
        }

        // Check school identifier
        L.d(TAG, "Looking for school identifier: $VALID_SCHOOL_IDENTIFIER")
        if (!text.contains(VALID_SCHOOL_IDENTIFIER, ignoreCase = true) && !text.contains(VALID_SCHOOL_IDENTIFIER_ALT, ignoreCase = true)) {
            L.d(TAG, "School identifier not found")
            return ValidationResult(false, "Ungültige Schule", "", "", emptyList())
        }
        L.d(TAG, "School identifier found")

        // Extract and validate class
        val userKlasse = sharedPreferences.getString("selected_klasse", "") ?: ""
        L.d(TAG, "User selected class: $userKlasse")

        if (userKlasse.isEmpty()) {
            return ValidationResult(false, "Keine Klasse ausgewählt", "", "", emptyList())
        }

        val klassePrefix = userKlasse.replace(Regex("\\d+$"), "") // Remove trailing numbers
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
            return ValidationResult(false, "Klasse $userKlasse nicht in PDF gefunden", "", "", emptyList())
        }

        // Extract subjects
        val subjects = extractSubjectsFromText(text, foundKlasse)

        L.d(TAG, "Final validation - Found klasse: $foundKlasse")
        L.d(TAG, "Final validation - Extracted ${subjects.size} subjects: $subjects")

        if (subjects.isEmpty()) {
            return ValidationResult(false, "Keine Fächer gefunden", "", "", emptyList())
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
            Toast.makeText(this, "Keine Fächer zum Auswählen gefunden", Toast.LENGTH_SHORT).show()
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
        AlertDialog.Builder(this)
            .setTitle("Stundenplan löschen")
            .setMessage("Willst du den gescannten Stundenplan wirklich löschen?")
            .setPositiveButton("Ja, löschen") { _, _ ->
                updateDeleteTimetableData()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showResetConfirmationDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Daten zurücksetzen")
        builder.setMessage("Bist du sicher, dass du alle gespeicherten Daten zurücksetzen möchten?\n\nDas wird dich zur Ersteinrichtung zurückführen.")
        builder.setIcon(android.R.drawable.ic_dialog_alert)

        builder.setPositiveButton("Ja, zurücksetzen") { _, _ ->
            resetAppData()
        }

        builder.setNegativeButton("Abbrechen") { dialog, _ ->
            dialog.dismiss()
        }

        builder.show()
    }

    private fun resetAppData() {
        sharedPreferences.edit {
            clear()
        }

        extractedSubjects.clear()

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
            Toast.makeText(this, "Kein Stundenplan zum Exportieren verfügbar", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            showExportOptions()
        } catch (e: Exception) {
            L.e(TAG, "Error exporting timetable", e)
            Toast.makeText(this, "Fehler beim Exportieren: ${e.message}", Toast.LENGTH_LONG).show()
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
        content.appendLine("# Heinrich-Kleyer-Schule Stundenplan Export")
        content.appendLine("# Exportiert am: ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMAN).format(Date())}")
        content.appendLine()
        content.appendLine("SCHULJAHR=$schuljahr")
        content.appendLine("KLASSE=$klasse")
        content.appendLine("BILDUNGSGANG=$bildungsgang")
        content.appendLine()
        content.appendLine("# Fächer (Format: Fach|Lehrer|Raum)")

        for (i in subjects.indices) {
            val subject = subjects[i]
            val teacher = if (i < teachers.size) teachers[i] else "UNKNOWN"
            val room = if (i < rooms.size) rooms[i] else "UNKNOWN"
            content.appendLine("$subject|$teacher|$room")
        }

        content.appendLine()
        content.appendLine("# Alternative Räume (JSON)")
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
            Pair("Als Datei speichern", R.drawable.ic_export_file),
            Pair("In Zwischenablage kopieren", R.drawable.ic_export_clipboard)
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
        AlertDialog.Builder(this)
            .setTitle("Stundenplan exportieren")
            .setAdapter(adapter) { _, which ->
                when (which) {
                    0 -> saveToFile(content)
                    1 -> copyToClipboard(content)
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
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
            Toast.makeText(this, "Fehler beim Öffnen des Datei-Dialogs", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToClipboard(content: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Stundenplan", content)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(this, "Stundenplan in die Zwischenablage kopiert", Toast.LENGTH_SHORT).show()
    }

    private fun showImportOptions() {
        val options = listOf(
            Pair("Aus Datei importieren", R.drawable.ic_import_file),
            Pair("Aus Zwischenablage einfügen", R.drawable.ic_import_clipboard)
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
        AlertDialog.Builder(this)
            .setTitle("Stundenplan importieren")
            .setAdapter(adapter) { _, which ->
                when (which) {
                    0 -> importFromFile()
                    1 -> importFromClipboard()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun importFromFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "text/plain"
            addCategory(Intent.CATEGORY_OPENABLE)
        }

        try {
            startActivityForResult(
                Intent.createChooser(intent, "HKS-Datei auswählen"),
                IMPORT_FILE_REQUEST_CODE
            )
        } catch (_: Exception) {
            Toast.makeText(this, "Keine Datei-Manager App gefunden", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "Zwischenablage ist leer", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Zwischenablage ist leer", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processImportContent(content: String) {
        try {
            val importResult = parseImportContent(content)
            if (importResult.isValid) {
                showImportConfirmationDialog(importResult)
            } else {
                Toast.makeText(this, "Ungültige Datei: ${importResult.errorMessage}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            L.e(TAG, "Error processing import content", e)
            Toast.makeText(this, "Fehler beim Importieren: ${e.message}", Toast.LENGTH_LONG).show()
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

        // validation
        if (schuljahr.isEmpty()) {
            return ImportResult(false, "Schuljahr nicht gefunden", "", "", "", emptyList(), emptyList(), emptyList())
        }

        if (klasse.isEmpty()) {
            return ImportResult(false, "Klasse nicht gefunden", "", "", "", emptyList(), emptyList(), emptyList())
        }

        if (subjects.isEmpty()) {
            return ImportResult(false, "Keine Fächer gefunden", "", "", "", emptyList(), emptyList(), emptyList())
        }

        // validate class matches users selection
        val userKlasse = sharedPreferences.getString("selected_klasse", "") ?: ""
        if (userKlasse.isNotEmpty() && klasse != userKlasse) {
            return ImportResult(false, "Klasse stimmt nicht überein (Datei: $klasse, Ausgewählt: $userKlasse)", "", "", "", emptyList(), emptyList(), emptyList())
        }

        return ImportResult(true, "", schuljahr, klasse, bildungsgang, subjects, teachers, rooms, alternativeRoomsJson)
    }

    private fun showImportConfirmationDialog(importResult: ImportResult) {
        val message = """
        Stundenplan importieren?
        
        Schuljahr: ${importResult.schuljahr}
        Klasse: ${importResult.klasse}
        Fächer: ${importResult.subjects.size}
        
        Dies wird den aktuellen Stundenplan ersetzen.
    """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Import bestätigen")
            .setMessage(message)
            .setPositiveButton("Importieren") { _, _ ->
                executeImport(importResult)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
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
            putString(
                "scanned_document_info",
                "Schuljahr ${importResult.schuljahr}\n${importResult.klasse} (Importiert)"
            )

            putString("alternative_rooms", importResult.alternativeRooms)

        }

        updateTimetableButton()
        setupFilterSwitch()

        Toast.makeText(this,
            "Stundenplan erfolgreich importiert\n${importResult.subjects.size} Fächer geladen",
            Toast.LENGTH_LONG).show()

        isOptionsExpanded = false
        layoutTimetableOptions.visibility = View.GONE

        L.d(TAG, "Import completed: ${importResult.subjects.size} subjects imported with alternative rooms")
    }

    private fun editTimetable() {
        if (!isDocumentScanned()) {
            Toast.makeText(this, "Kein Stundenplan zum Bearbeiten verfügbar", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "Keine Fächer zum Bearbeiten verfügbar", Toast.LENGTH_SHORT).show()
                return
            }

            L.d(TAG, "Launching SubjectSelectionActivity for editing with ${allExtractedSubjects.size} subjects")

            val intent = Intent(this, SubjectSelectionActivity::class.java).apply {
                putExtra(SubjectSelectionActivity.EXTRA_SUBJECTS, allExtractedSubjects.toTypedArray())
                putExtra(SubjectSelectionActivity.EXTRA_TEACHERS, allExtractedTeachers.toTypedArray())
                putExtra(SubjectSelectionActivity.EXTRA_ROOMS, allExtractedRooms.toTypedArray())
                putExtra(SubjectSelectionActivity.EXTRA_SCHULJAHR, schuljahr)
                putExtra(SubjectSelectionActivity.EXTRA_KLASSE, klasse)
            }

            startActivityForResult(intent, SUBJECT_SELECTION_REQUEST_CODE)

            isOptionsExpanded = false
            layoutTimetableOptions.visibility = View.GONE

        } catch (e: Exception) {
            L.e(TAG, "Error launching edit timetable", e)
            Toast.makeText(this, "Fehler beim Öffnen der Bearbeitung: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkButtonAvailability() {
        btnExportTimetable.isEnabled = isDocumentScanned()
        btnExportTimetable.alpha = if (isDocumentScanned()) 1.0f else 0.5f
        btnEditTimetable.isEnabled = isDocumentScanned()
        btnEditTimetable.alpha = if (isDocumentScanned()) 1.0f else 0.5f
    }

    private fun setupStartupPageSetting() {
        val startupPages = arrayOf("Kalender", "Vertretungsplan (empfohlen)", "Hausaufgaben", "Klausuren", "Noten")
        val currentSelection = sharedPreferences.getInt("startup_page_index", 0)

        "Startseite: ${startupPages[currentSelection]}".also { btnStartupPage.text = it }

        btnStartupPage.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Startseite auswählen")
                .setSingleChoiceItems(startupPages, currentSelection) { dialog, which ->
                    sharedPreferences.edit {
                        putInt("startup_page_index", which)
                    }

                    "Startseite: ${startupPages[which]}".also { btnStartupPage.text = it }
                    dialog.dismiss()

                    Toast.makeText(this,
                        "Startseite geändert zu: ${startupPages[which]}",
                        Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }
    }

    private fun setupAutoUpdateSettings() {
        val autoUpdateEnabled = sharedPreferences.getBoolean("auto_update_enabled", false)
        val autoUpdateTime = sharedPreferences.getString("auto_update_time", "06:00") ?: "06:00"
        val updateWifiOnly = sharedPreferences.getBoolean("update_wifi_only", false)
        val showUpdateNotifications = sharedPreferences.getBoolean("show_update_notifications", true)

        switchAutoUpdate.isChecked = autoUpdateEnabled
        "Update-Zeit: $autoUpdateTime".also { btnAutoUpdateTime.text = it }
        switchUpdateWifiOnly.isChecked = updateWifiOnly
        switchShowUpdateNotifications.isChecked = showUpdateNotifications

        updateAutoUpdateUI(autoUpdateEnabled)

        switchAutoUpdate.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit { putBoolean("auto_update_enabled", isChecked)}

            updateAutoUpdateUI(isChecked)

            if (isChecked) {
                WorkScheduler.scheduleAutoUpdate(this, sharedPreferences)
                Toast.makeText(this, "Automatische Updates aktiviert", Toast.LENGTH_SHORT).show()
            } else {
                WorkScheduler.cancelAutoUpdate(this)
                Toast.makeText(this, "Automatische Updates deaktiviert", Toast.LENGTH_SHORT).show()
            }
        }

        btnAutoUpdateTime.setOnClickListener {
            TimePickerDialogHelper.showTimePicker(this, autoUpdateTime) { selectedTime ->
                sharedPreferences.edit { putString("auto_update_time", selectedTime) }

                "Update-Zeit: $selectedTime".also { btnAutoUpdateTime.text = it }

                if (switchAutoUpdate.isChecked) {
                    WorkScheduler.scheduleAutoUpdate(this, sharedPreferences)
                    Toast.makeText(this, "Update-Zeit geändert: $selectedTime", Toast.LENGTH_SHORT).show()
                }
            }
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
                if (isChecked) "Updates nur über WLAN aktiviert"
                else "Updates über alle Verbindungen aktiviert",
                Toast.LENGTH_SHORT).show()
        }

        switchShowUpdateNotifications.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit { putBoolean("show_update_notifications", isChecked)}

            Toast.makeText(this,
                if (isChecked) "Update-Benachrichtigungen aktiviert"
                else "Update-Benachrichtigungen deaktiviert",
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
                Toast.makeText(this, "Änderungs-Benachrichtigungen aktiviert", Toast.LENGTH_SHORT).show()
            } else {
                WorkScheduler.cancelChangeNotification(this)
                Toast.makeText(this, "Änderungs-Benachrichtigungen deaktiviert", Toast.LENGTH_SHORT).show()
            }
        }

        btnChangeNotificationInterval.setOnClickListener {
            TimePickerDialogHelper.showIntervalPicker(this, changeNotificationInterval) { selectedInterval ->
                sharedPreferences.edit {putInt("change_notification_interval", selectedInterval) }

                btnChangeNotificationInterval.text = formatInterval(selectedInterval)

                if (switchChangeNotification.isChecked) {
                    WorkScheduler.scheduleChangeNotification(this, sharedPreferences)
                    Toast.makeText(this, "Prüfintervall geändert: ${formatInterval(selectedInterval)}", Toast.LENGTH_SHORT).show()
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
                "all_class_subjects" -> "Alle Fächer der Klasse"
                "my_subjects_only" -> "Nur meine Fächer"
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
            minutes < 60 -> "Alle $minutes Minuten"
            minutes == 60 -> "Alle 1 Stunde"
            else -> "Alle ${minutes / 60} Stunden"
        }
    }

    private fun setupNotificationInfoButton() {
        val ivChangeNotificationInfo = findViewById<ImageView>(R.id.ivChangeNotificationInfo)

        ivChangeNotificationInfo.setOnClickListener {
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
                Toast.makeText(this, "Einstellungen konnten nicht geöffnet werden", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "Einstellungen konnten nicht geöffnet werden", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupHomeworkAutoDelete() {
        switchAutoDeleteHomework = findViewById(R.id.switchAutoDeleteHomework)

        switchAutoDeleteHomework.isChecked = HomeworkUtils.isAutoDeleteEnabled(this)

        switchAutoDeleteHomework.setOnCheckedChangeListener { _, isChecked ->
            HomeworkUtils.setAutoDeleteEnabled(this, isChecked)

            val message = if (isChecked) {
                "Automatisches Löschen aktiviert"
            } else {
                "Automatisches Löschen deaktiviert"
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
        "Erinnerung: $dueDateReminderHours Stunden vorher".also { btnDueDateReminderHours.text = it }

        switchDailyHomeworkReminder.isChecked = dailyHomeworkReminderEnabled
        "Erinnerungszeit: $dailyReminderTime".also { btnDailyReminderTime.text = it }
    }

    private fun showDueDateReminderHoursDialog() {
        val currentHours = sharedPreferences.getInt("due_date_reminder_hours", 16)
        val hoursOptions = arrayOf("1 Stunde", "2 Stunden", "4 Stunden", "8 Stunden", "12 Stunden", "16 Stunden", "18 Stunden", "20 Stunden", "22 Stunden", "24 Stunden", "48 Stunden")
        val hoursValues = arrayOf(1, 2, 4, 8, 12, 16, 18,  20,22, 24, 48)

        val currentIndex = hoursValues.indexOf(currentHours).let { if (it == -1) 5 else it } // default 16

        AlertDialog.Builder(this)
            .setTitle("Erinnerung vor Fälligkeit")
            .setSingleChoiceItems(hoursOptions, currentIndex) { dialog, which ->
                val selectedHours = hoursValues[which]
                sharedPreferences.edit { putInt("due_date_reminder_hours", selectedHours) }

                "Erinnerung: $selectedHours Stunden vorher".also { btnDueDateReminderHours.text = it }

                if (switchDueDateReminder.isChecked) {
                    WorkScheduler.scheduleDueDateReminder(this, sharedPreferences)
                }

                dialog.dismiss()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showDailyReminderTimeDialog() {
        val currentTime = sharedPreferences.getString("daily_homework_reminder_time", "19:00")
        val timeParts = currentTime?.split(":") ?: listOf("19", "00")
        val currentHour = timeParts[0].toIntOrNull() ?: 19
        val currentMinute = timeParts[1].toIntOrNull() ?: 0

        TimePickerDialog(
            this,
            { _, hour, minute ->
                val timeString = String.format("%02d:%02d", hour, minute)
                sharedPreferences.edit {
                    putString("daily_homework_reminder_time", timeString)
                }

                "Erinnerungszeit: $timeString".also { btnDailyReminderTime.text = it }

                if (switchDailyHomeworkReminder.isChecked) {
                    WorkScheduler.scheduleDailyHomeworkReminder(this, sharedPreferences)
                }
            },
            currentHour,
            currentMinute,
            true
        ).show()
    }

    private fun setupEmailButton() {
        val btnContactEmail = findViewById<Button>(R.id.btnContactEmail)
        btnContactEmail.setOnClickListener {
            openEmailClientFromSettings()
        }
    }

    private fun openEmailClientFromSettings() {
        val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = "mailto:theactualcooker@gmail.com".toUri()
            putExtra(Intent.EXTRA_SUBJECT, "Heinrich-Kleyer-Schule App - Kontakt")
            putExtra(Intent.EXTRA_TEXT, "Hallo,\n\nich habe eine Frage/ein Problem mit der App:\n\n")
        }

        try {
            startActivity(Intent.createChooser(emailIntent, "E-Mail-App auswählen"))
        } catch (_: Exception) {
            Toast.makeText(this, "Keine E-Mail-App gefunden. Bitte kopiere die E-Mail-Adresse manuell: theactualcooker@gmail.com", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupUpdateCooldownSetting() {
        val removeCooldown = sharedPreferences.getBoolean("remove_update_cooldown", false)
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
            1 -> "1 Tag vorher"
            in 2..6 -> "$examDueDateReminderDays Tage vorher"
            7 -> "1 Woche vorher"
            14 -> "2 Wochen vorher"
            21 -> "3 Wochen vorher"
            else -> "$examDueDateReminderDays Tage vorher"
        }
        "Erinnerung: $daysText".also { btnExamDueDateReminderDays.text = it }
        //btnExamDueDateReminderDays.isEnabled = examDueDateReminderEnabled
    }

    private fun showExamDueDateReminderDaysDialog() {
        val currentDays = sharedPreferences.getInt("exam_due_date_reminder_days", 7)
        val daysOptions = arrayOf(
            "1 Tag vorher", "2 Tage vorher", "3 Tage vorher", "4 Tage vorher", "5 Tage vorher",
            "6 Tage vorher", "1 Woche vorher", "2 Wochen vorher", "3 Wochen vorher"
        )
        val daysValues = arrayOf(1, 2, 3, 4, 5, 6, 7, 14, 21)

        val currentIndex = daysValues.indexOf(currentDays).let { if (it == -1) 6 else it } // default 1 week

        AlertDialog.Builder(this)
            .setTitle("Erinnerung vor Klausur")
            .setSingleChoiceItems(daysOptions, currentIndex) { dialog, which ->
                val selectedDays = daysValues[which]
                sharedPreferences.edit { putInt("exam_due_date_reminder_days", selectedDays) }

                val daysText = daysOptions[which]
                "Erinnerung: $daysText".also { btnExamDueDateReminderDays.text = it }

                if (switchExamDueDateReminder.isChecked) {
                    WorkScheduler.scheduleExamReminder(this, sharedPreferences)
                }

                dialog.dismiss()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
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
                    if (isChecked) "Querformat aktiviert"
                    else "Querformat deaktiviert",
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
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun setupDarkModeSetting() {
        val switchDarkMode = findViewById<Switch>(R.id.switchDarkMode)

        val darkModeEnabled = sharedPreferences.getBoolean("dark_mode_enabled", false)
        switchDarkMode.isChecked = darkModeEnabled

        switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            if (!isInitializing) {
                sharedPreferences.edit { putBoolean("dark_mode_enabled", isChecked) }

                applyDarkModeSetting(isChecked)

                Toast.makeText(this,
                    if (isChecked) "Dunkler Modus aktiviert"
                    else "Heller Modus aktiviert",
                    Toast.LENGTH_SHORT).show()

                recreate()
            } else {
                sharedPreferences.edit { putBoolean("dark_mode_enabled", isChecked) }
            }
        }
    }

    private fun applyDarkModeSetting(darkModeEnabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AppCompatDelegate.setDefaultNightMode(
                if (darkModeEnabled) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
    }

    private fun setupCalendarSettings() {
        val switchCalendarRealTime = findViewById<Switch>(R.id.switchCalendarRealTime)

        val realTimeEnabled = sharedPreferences.getBoolean("calendar_real_time_enabled", false)
        switchCalendarRealTime.isChecked = realTimeEnabled

        switchCalendarRealTime.setOnCheckedChangeListener { _, isChecked ->
            if (!isInitializing) {
                sharedPreferences.edit {putBoolean("calendar_real_time_enabled", isChecked) }

                Toast.makeText(this,
                    if (isChecked) "Echtzeit-Kalender aktiviert"
                    else "Echtzeit-Kalender deaktiviert",
                    Toast.LENGTH_SHORT).show()
            } else {
                sharedPreferences.edit {putBoolean("calendar_real_time_enabled", isChecked) }
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
                    if (isChecked) "Wochenenden im Kalender aktiviert"
                    else "Wochenenden im Kalender deaktiviert",
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
            val backupContent = backupManager.createFullBackup()
            showFullBackupExportOptions(backupContent)
        } catch (e: Exception) {
            L.e(TAG, "Error creating full backup", e)
            Toast.makeText(this, "Fehler beim Erstellen der Sicherung: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showFullBackupExportOptions(content: String) {
        val options = arrayOf("Als Datei speichern", "In Zwischenablage kopieren")

        AlertDialog.Builder(this)
            .setTitle("Vollständige Sicherung exportieren")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> saveFullBackupToFile(content)
                    1 -> copyFullBackupToClipboard(content)
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
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
            Toast.makeText(this, "Fehler beim Öffnen des Datei-Dialogs", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyFullBackupToClipboard(content: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("HKS Vollständige Sicherung", content)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(this, "Vollständige Sicherung in die Zwischenablage kopiert", Toast.LENGTH_SHORT).show()
    }

    private fun importFullBackup() {
        val options = arrayOf("Aus Datei importieren", "Aus Zwischenablage einfügen")

        AlertDialog.Builder(this)
            .setTitle("Vollständige Sicherung importieren")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> importFullBackupFromFile()
                    1 -> importFullBackupFromClipboard()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun importFullBackupFromFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "text/plain"
            addCategory(Intent.CATEGORY_OPENABLE)
        }

        try {
            startActivityForResult(
                Intent.createChooser(intent, "HKS-Sicherungsdatei auswählen"),
                FULL_BACKUP_IMPORT_REQUEST_CODE
            )
        } catch (_: Exception) {
            Toast.makeText(this, "Keine Datei-Manager App gefunden", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "Zwischenablage ist leer", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Zwischenablage ist leer", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processFullBackupImport(content: String) {
        try {
            showFullBackupImportConfirmationDialog(content)
        } catch (e: Exception) {
            L.e(TAG, "Error processing full backup import", e)
            Toast.makeText(this, "Fehler beim Importieren der Sicherung: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showFullBackupImportConfirmationDialog(content: String) {
        val message = """
        Vollständige Sicherung wiederherstellen?
        
        Dies wird ALLE aktuellen App-Daten ersetzen:
        • Stundenplan
        • Kalender-Daten
        • Hausaufgaben
        • Klausuren
        • Noten
        • App-Einstellungen
        
        Diese Aktion kann nicht rückgängig gemacht werden!
    """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Sicherung wiederherstellen")
            .setMessage(message)
            .setPositiveButton("Wiederherstellen") { _, _ ->
                executeFullBackupRestore(content)
            }
            .setNegativeButton("Abbrechen", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    private fun executeFullBackupRestore(content: String) {
        try {
            val result = backupManager.restoreFromBackup(content)

            if (result.success) {
                loadCurrentSelection()
                updateTimetableButton()
                setupFilterSwitch()

                Toast.makeText(this,
                    "Sicherung erfolgreich wiederhergestellt!\n${result.restoredSections}/${result.totalSections} Bereiche wiederhergestellt",
                    Toast.LENGTH_LONG).show()

                showRestartRecommendationDialog()
            } else {
                val errorMessage = if (result.errors.isEmpty()) {
                    "Unbekannter Fehler beim Wiederherstellen"
                } else {
                    "Teilweise wiederhergestellt: ${result.restoredSections}/${result.totalSections} Bereiche\nFehler: ${result.errors.joinToString(", ")}"
                }

                Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
            }

        } catch (e: Exception) {
            L.e(TAG, "Error executing full backup restore", e)
            Toast.makeText(this, "Fehler beim Wiederherstellen: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showRestartRecommendationDialog() {
        AlertDialog.Builder(this)
            .setTitle("App-Neustart empfohlen")
            .setMessage("Um alle Änderungen korrekt zu laden, wird ein App-Neustart empfohlen. Soll die App jetzt neu gestartet werden?")
            .setPositiveButton("Jetzt neustarten") { _, _ ->
                restartApp()
            }
            .setNegativeButton("Später", null)
            .show()
    }

    private fun restartApp() {
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
            Toast.makeText(this, "Kein Inhalt zum Exportieren", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(content.toByteArray())
            }

            sharedPreferences.edit {remove("temp_full_backup_content") }

            Toast.makeText(this, "Vollständige Sicherung erfolgreich exportiert", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            L.e(TAG, "Error writing full backup to file", e)
            Toast.makeText(this, "Fehler beim Speichern der Sicherungsdatei: ${e.message}", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, "Fehler beim Lesen der Sicherungsdatei: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    data class ValidationResult(
        val isValid: Boolean,
        val errorMessage: String,
        val schuljahr: String,
        val klasse: String,
        val subjects: List<String>
    )
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