package com.thecooker.vertretungsplaner.ui.slideshow

import android.app.Activity
import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.thecooker.vertretungsplaner.L
import android.view.*
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.thecooker.vertretungsplaner.R
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import com.thecooker.vertretungsplaner.utils.BackupManager
import org.json.JSONArray
import org.json.JSONObject

class SlideshowFragment : Fragment() {

    private var isLoading = true
    private lateinit var loadingView: View

    private lateinit var spinnerSort: Spinner
    private lateinit var btnAddHomework: Button
    private lateinit var btnMenu: Button
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvHomeworkCount: TextView
    private lateinit var adapter: HomeworkAdapter
    private lateinit var sharedPreferences: SharedPreferences

    private lateinit var backupManager: BackupManager

    private var homeworkList = mutableListOf<HomeworkEntry>()
    private var currentSortOrder = SortOrder.DUE_DATE_ASC

    // auto delete
    private val cleanupHandler = Handler(Looper.getMainLooper())
    private val cleanupRunnable = object : Runnable {
        override fun run() {
            if (isAutoDeleteEnabled()) {
                cleanupCompletedAndOverdueHomework()
            }
            cleanupHandler.postDelayed(this, TimeUnit.HOURS.toMillis(1)) // check every hour
        }
    }

    // file pickers for import and export
    private lateinit var filePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var exportLauncher: ActivityResultLauncher<Intent>

    private val lessonTimes = mapOf(
        1 to "07:30", 2 to "08:15", 3 to "09:30", 4 to "10:15", 5 to "11:15",
        6 to "12:00", 7 to "13:15", 8 to "14:00", 9 to "15:00", 10 to "15:45",
        11 to "17:00", 12 to "17:45", 13 to "18:45", 14 to "19:30", 15 to "20:15"
    )

    enum class SortOrder(val displayName: String) {
        DUE_DATE_ASC("Fälligkeit (aufsteigend)"),
        DUE_DATE_DESC("Fälligkeit (absteigend)"),
        SUBJECT_ASC("Fach (A-Z)"),
        SUBJECT_DESC("Fach (Z-A)")
    }

    data class HomeworkEntry(
        val id: String = UUID.randomUUID().toString(),
        var subject: String,
        var dueDate: Date,
        var dueTime: Date? = null,
        var lessonNumber: Int? = null,
        var content: String,
        var isCompleted: Boolean = false,
        var completedDate: Date? = null,
        var checklistItems: MutableList<ChecklistItem> = mutableListOf(),
        var hasTextContent: Boolean = false
    ) {
        fun getDueDateString(): String {
            val now = Calendar.getInstance()
            val dueCalendar = Calendar.getInstance().apply { time = dueDate }

            val daysDiff = TimeUnit.MILLISECONDS.toDays(
                Calendar.getInstance().apply {
                    time = dueDate
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis -
                        Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, 0)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
            )

            val dateStr = when {
                daysDiff == 0L -> {
                    dueTime?.let { time ->
                        val currentTime = Date()
                        if (time.after(currentTime)) {
                            val timeDiff = time.time - currentTime.time
                            val hoursDiff = TimeUnit.MILLISECONDS.toHours(timeDiff)
                            val minutesDiff = TimeUnit.MILLISECONDS.toMinutes(timeDiff)
                            when {
                                hoursDiff > 0 -> "fällig in ${hoursDiff}h"
                                minutesDiff > 0 -> "fällig in ${minutesDiff}min"
                                else -> "jetzt fällig"
                            }
                        } else {
                            // time passed -> overdue
                            if (lessonNumber != null) {
                                "überfällig"
                            } else {
                                "heute überfällig"
                            }
                        }
                    } ?: "heute fällig"
                }
                daysDiff == 1L -> "morgen fällig"
                daysDiff == -1L -> "gestern fällig"
                daysDiff > 1 -> {
                    val weeks = daysDiff / 7
                    if (weeks > 0) "fällig in ${weeks} Woche${if (weeks > 1) "n" else ""}"
                    else "fällig in ${daysDiff} Tag${if (daysDiff > 1) "en" else ""}"
                }
                else -> {
                    val daysPast = -daysDiff
                    if (daysPast == 1L) "gestern fällig"
                    else "vor ${daysPast} Tag${if (daysPast > 1) "en" else ""} fällig"
                }
            }

            return if (lessonNumber != null) {
                "$dateStr (${lessonNumber}. Stunde)"
            } else {
                dateStr
            }
        }

        fun getDetailedDueDateString(): String {
            val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)
            val timeFormat = SimpleDateFormat("HH:mm", Locale.GERMANY)

            var result = "Fällig am: ${dateFormat.format(dueDate)}"

            if (dueTime != null) {
                result += " um ${timeFormat.format(dueTime!!)}"
            }

            if (lessonNumber != null) {
                result += " (${lessonNumber}. Stunde)"
            }

            return result
        }

        fun getBackgroundColor(): Int = when {
            isCompleted -> android.R.color.holo_green_light
            isOverdue() -> android.R.color.darker_gray
            isDueToday() -> android.R.color.holo_red_light
            isDueTomorrow() -> android.R.color.holo_orange_light
            else -> android.R.color.transparent
        }

        fun isOverdue(): Boolean {
            val now = Calendar.getInstance()
            val due = Calendar.getInstance().apply {
                time = dueDate
                // if specific time available -> use it, otherwise default to end of day
                dueTime?.let { time ->
                    val timeCalendar = Calendar.getInstance().apply { this.time = time }
                    set(Calendar.HOUR_OF_DAY, timeCalendar.get(Calendar.HOUR_OF_DAY))
                    set(Calendar.MINUTE, timeCalendar.get(Calendar.MINUTE))
                } ?: run {
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                }
            }
            return !isCompleted && now.after(due)
        }

        private fun isDueToday(): Boolean {
            val today = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val due = Calendar.getInstance().apply {
                time = dueDate
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return today.timeInMillis == due.timeInMillis
        }

        private fun isDueTomorrow(): Boolean {
            val tomorrow = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val due = Calendar.getInstance().apply {
                time = dueDate
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return tomorrow.timeInMillis == due.timeInMillis
        }

        fun updateChecklistCompletion(): Boolean {
            val allCompleted = checklistItems.isNotEmpty() && checklistItems.all { it.isCompleted }
            if (allCompleted && !isCompleted) {
                isCompleted = true
                completedDate = Date()
                return true
            } else if (!allCompleted && isCompleted && checklistItems.isNotEmpty()) {
                isCompleted = false
                completedDate = null
                return true
            }
            return false
        }

        fun isOverdueForUI(): Boolean {
            val now = Calendar.getInstance()
            val due = Calendar.getInstance().apply {
                time = dueDate
                dueTime?.let { time ->
                    val timeCalendar = Calendar.getInstance().apply { this.time = time }
                    set(Calendar.HOUR_OF_DAY, timeCalendar.get(Calendar.HOUR_OF_DAY))
                    set(Calendar.MINUTE, timeCalendar.get(Calendar.MINUTE))
                } ?: run {
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                }
            }
            return now.after(due)
        }
    }

    data class ChecklistItem(
        var text: String,
        var isCompleted: Boolean = false
    )

    companion object {
        private const val TAG = "SlideshowFragment"
        private const val PREFS_HOMEWORK_LIST = "homework_list"
        private const val PREFS_AUTO_DELETE = "auto_delete_completed"
        private const val PREFS_AUTO_HOMEWORK = "auto_homework_enabled"
        private const val EXPORT_FILE_REQUEST_CODE = 100

        fun getHomeworkList(context: Context): List<HomeworkEntry> {
            val sharedPrefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val json = sharedPrefs.getString(PREFS_HOMEWORK_LIST, "[]")
            val type = object : TypeToken<List<HomeworkEntry>>() {}.type
            return Gson().fromJson(json, type) ?: emptyList()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_slideshow, container, false)

        sharedPreferences = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        backupManager = BackupManager(requireContext())

        initializeViews(view)
        setupFilePickerLaunchers()

        showLoadingState()

        view.post {
            setupRecyclerView()
            loadHomework()
            setupSortSpinner()
            setupListeners()
            updateHomeworkCount()

            isLoading = false
            hideLoadingState()

            cleanupHandler.post(cleanupRunnable)
        }

        return view
    }

    private fun showLoadingState() {
        loadingView = LayoutInflater.from(requireContext()).inflate(android.R.layout.simple_list_item_1, null)

        val loadingText = loadingView.findViewById<TextView>(android.R.id.text1)
        loadingText.apply {
            text = "Hausaufgaben werden geladen..."
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(32, 64, 32, 64)
            setTextColor(resources.getColor(android.R.color.black))
            setTypeface(null, Typeface.BOLD)
        }

        recyclerView.visibility = View.GONE
        spinnerSort.visibility = View.GONE
        btnAddHomework.visibility = View.GONE
        btnMenu.visibility = View.GONE
        tvHomeworkCount.visibility = View.GONE

        val rootLayout = recyclerView.parent as ViewGroup
        rootLayout.addView(loadingView, 0)
    }

    private fun hideLoadingState() {
        if (::loadingView.isInitialized) {
            val rootLayout = loadingView.parent as? ViewGroup
            rootLayout?.removeView(loadingView)
        }

        recyclerView.visibility = View.VISIBLE
        spinnerSort.visibility = View.VISIBLE
        btnAddHomework.visibility = View.VISIBLE
        btnMenu.visibility = View.VISIBLE
        tvHomeworkCount.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cleanupHandler.removeCallbacks(cleanupRunnable)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == EXPORT_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                val content = sharedPreferences.getString("temp_export_content", "") ?: ""
                saveToSelectedFile(uri, content)
                sharedPreferences.edit().remove("temp_export_content").apply()
            }
        }
    }

    private fun setupFilePickerLaunchers() {
        filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    importFromFile(uri)
                }
            }
        }
    }

    private fun initializeViews(view: View) {
        spinnerSort = view.findViewById(R.id.spinnerSort)
        btnAddHomework = view.findViewById(R.id.btnAddHomework)
        btnMenu = view.findViewById(R.id.btnMenu)
        recyclerView = view.findViewById(R.id.recyclerViewHomework)
        tvHomeworkCount = view.findViewById(R.id.tvHomeworkCount)
    }

    private fun setupSortSpinner() {
        val sortOptions = SortOrder.values().map { it.displayName }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, sortOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSort.adapter = adapter

        spinnerSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentSortOrder = SortOrder.values()[position]
                sortHomework()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupRecyclerView() {
        adapter = HomeworkAdapter(
            homeworkList = homeworkList,
            onHomeworkToggled = { homework, isCompleted ->
                homework.isCompleted = isCompleted
                if (isCompleted) {
                    homework.completedDate = Date()
                } else {
                    homework.completedDate = null
                }
                saveHomework()
                updateHomeworkCount()
                recyclerView.post {
                    val position = homeworkList.indexOf(homework)
                    if (position != -1) {
                        adapter.notifyItemChanged(position)
                    }
                }
            },
            onHomeworkDeleted = { homework ->
                deleteHomework(homework)
            },
            onHomeworkEdited = { homework ->
                showAddHomeworkDialog(homework)
            },
            onHomeworkViewed = { homework ->
                showHomeworkDetailDialog(homework)
            },
            onChecklistItemToggled = { homework, item, isCompleted ->
                item.isCompleted = isCompleted
                val wasAutoCompleted = homework.updateChecklistCompletion()
                if (wasAutoCompleted) {
                    recyclerView.post {
                        val position = homeworkList.indexOf(homework)
                        if (position != -1) {
                            adapter.notifyItemChanged(position)
                        }
                    }
                }
                saveHomework()
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
    }

    private fun setupListeners() {
        btnAddHomework.setOnClickListener {
            showAddHomeworkDialog()
        }

        btnMenu.setOnClickListener {
            showMenuPopup()
        }
    }

    private fun showMenuPopup() {
        val popup = PopupMenu(requireContext(), btnMenu)
        popup.menu.add(0, 1, 0, "Exportieren")
        popup.menu.add(0, 2, 0, "Importieren")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    showExportOptions()
                    true
                }
                2 -> {
                    showImportOptions()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showAddHomeworkDialog(editHomework: HomeworkEntry? = null) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_homework, null)

        val spinnerSubject = dialogView.findViewById<Spinner>(R.id.spinnerSubject)
        val editTextSubject = dialogView.findViewById<EditText>(R.id.editTextSubject)
        val btnDueDate = dialogView.findViewById<Button>(R.id.btnDueDate)
        val spinnerLesson = dialogView.findViewById<Spinner>(R.id.spinnerLesson)
        val editTextContent = dialogView.findViewById<EditText>(R.id.editTextContent)

        val switchAutoHomework = dialogView.findViewById<Switch>(R.id.switchAutoHomework)

        var selectedDate = editHomework?.dueDate ?: Date()
        var selectedLessonNumber: Int? = editHomework?.lessonNumber

        val hasScannedDocument = sharedPreferences.getBoolean("has_scanned_document", false)
        val availableSubjects = getAvailableSubjects()
        val hasTimetableData = availableSubjects.isNotEmpty()

        val isAutoEnabled = if (editHomework != null) {
            false // force uncheck when editing
        } else {
            sharedPreferences.getBoolean(PREFS_AUTO_HOMEWORK, false) // Use saved preference when creating new
        }
        switchAutoHomework.isChecked = isAutoEnabled

        switchAutoHomework.isEnabled = hasTimetableData

        if (hasTimetableData) {
            spinnerSubject.visibility = View.VISIBLE
            editTextSubject.visibility = View.GONE
            setupSubjectSpinner(spinnerSubject, editHomework?.subject)

            spinnerSubject.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selectedSubject = spinnerSubject.selectedItem?.toString()
                    updateAutoSwitchState(switchAutoHomework, selectedSubject, editHomework != null)

                    if (switchAutoHomework.isChecked && switchAutoHomework.isEnabled) {
                        selectedSubject?.let { subject ->
                            getNextSubjectDate(requireContext(), subject)?.let { (date, lesson) ->
                                selectedDate = date
                                selectedLessonNumber = lesson
                                updateDateButton(btnDueDate, selectedDate)

                                lesson?.let { lessonNum ->
                                    val lessonNumbers = getLessonNumbers()
                                    val position = lessonNumbers.indexOf(lessonNum) + 1
                                    if (position > 0) {
                                        spinnerLesson.setSelection(position)
                                    }
                                }
                            }
                        }
                    }

                    updateUIState(switchAutoHomework.isChecked && switchAutoHomework.isEnabled,
                        btnDueDate, spinnerLesson)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        } else {
            spinnerSubject.visibility = View.GONE
            editTextSubject.visibility = View.VISIBLE
            editHomework?.let { editTextSubject.setText(it.subject) }
            switchAutoHomework.isEnabled = false
        }

        switchAutoHomework.setOnCheckedChangeListener { _, isChecked ->
            // save preference only when creating new hw and auto fill disabled
            if (editHomework == null && switchAutoHomework.isEnabled) {
                sharedPreferences.edit().putBoolean(PREFS_AUTO_HOMEWORK, isChecked).apply()
            }

            if (isChecked && switchAutoHomework.isEnabled) {
                val selectedSubject = if (hasTimetableData) {
                    spinnerSubject.selectedItem?.toString()
                } else null

                selectedSubject?.let { subject ->
                    getNextSubjectDate(requireContext(), subject)?.let { (date, lesson) ->
                        selectedDate = date
                        selectedLessonNumber = lesson
                        updateDateButton(btnDueDate, selectedDate)

                        lesson?.let { lessonNum ->
                            val lessonNumbers = getLessonNumbers()
                            val position = lessonNumbers.indexOf(lessonNum) + 1
                            if (position > 0) {
                                spinnerLesson.setSelection(position)
                            }
                        }
                    }
                }
            }

            updateUIState(isChecked && switchAutoHomework.isEnabled, btnDueDate, spinnerLesson)
        }

        updateDateButton(btnDueDate, selectedDate)

        btnDueDate.setOnClickListener {
            if (!switchAutoHomework.isChecked || !switchAutoHomework.isEnabled) {
                val currentSubject = if (hasTimetableData) {
                    spinnerSubject.selectedItem?.toString()
                } else null

                showCustomDatePicker(selectedDate, currentSubject) { date, lesson ->
                    selectedDate = date
                    selectedLessonNumber = lesson
                    updateDateButton(btnDueDate, selectedDate)

                    lesson?.let { lessonNum ->
                        val lessonNumbers = getLessonNumbers()
                        val position = lessonNumbers.indexOf(lessonNum) + 1
                        if (position > 0) {
                            spinnerLesson.setSelection(position)
                        }
                    }
                }
            }
        }

        setupLessonSpinner(spinnerLesson, selectedLessonNumber)

        spinnerLesson.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!switchAutoHomework.isChecked || !switchAutoHomework.isEnabled) {
                    selectedLessonNumber = if (position == 0) null else getLessonNumbers()[position - 1]
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        editHomework?.let { editTextContent.setText(it.content) }

        updateUIState(switchAutoHomework.isChecked && switchAutoHomework.isEnabled, btnDueDate, spinnerLesson)

        if (hasTimetableData) {
            val currentSubject = editHomework?.subject ?: spinnerSubject.selectedItem?.toString()
            updateAutoSwitchState(switchAutoHomework, currentSubject, editHomework != null)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(if (editHomework != null) "Hausaufgabe bearbeiten" else "Hausaufgabe hinzufügen")
            .setView(dialogView)
            .setPositiveButton(if (editHomework != null) "Speichern" else "Hinzufügen") { _, _ ->
                val subject = if (hasTimetableData) {
                    spinnerSubject.selectedItem?.toString() ?: ""
                } else {
                    editTextSubject.text.toString().trim()
                }

                val content = editTextContent.text.toString().trim()

                if (subject.isNotEmpty()) {
                    if (editHomework != null) {
                        updateHomework(editHomework, subject, selectedDate, selectedLessonNumber, content)
                    } else {
                        addHomework(subject, selectedDate, selectedLessonNumber, content)
                    }
                } else {
                    Toast.makeText(requireContext(), "Gebe ein Fach ein", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .create()

        dialog.show()
    }

    private fun updateAutoSwitchState(switchAutoHomework: Switch, subject: String?, isEditMode: Boolean = false) {
        if (subject != null) {
            val schedule = getSubjectSchedule(requireContext(), subject)
            val canAutoFill = schedule.isNotEmpty()

            switchAutoHomework.isEnabled = canAutoFill

            if (!canAutoFill) {
                switchAutoHomework.isChecked = false
            } else if (!isEditMode) {
                val userPreference = sharedPreferences.getBoolean(PREFS_AUTO_HOMEWORK, false)
                switchAutoHomework.isChecked = userPreference
            }
        } else {
            switchAutoHomework.isEnabled = false
            switchAutoHomework.isChecked = false
        }
    }

    private fun updateUIState(isAutoEnabled: Boolean, btnDueDate: Button, spinnerLesson: Spinner) {
        btnDueDate.isEnabled = !isAutoEnabled
        spinnerLesson.isEnabled = !isAutoEnabled

        btnDueDate.alpha = if (isAutoEnabled) 0.5f else 1.0f
        spinnerLesson.alpha = if (isAutoEnabled) 0.5f else 1.0f
    }

    private fun setupSubjectSpinner(spinner: Spinner, selectedSubject: String?) {
        val allSubjects = getAvailableSubjects()
        val subjects = allSubjects.filterNot { it.equals("Freistunde", ignoreCase = true) } // ignore "Freistunde"
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, subjects)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        selectedSubject?.let { subject ->
            val position = subjects.indexOf(subject)
            if (position >= 0) {
                spinner.setSelection(position)
            }
        }
    }

    private fun showCustomDatePicker(currentDate: Date, selectedSubject: String?, onDateSelected: (Date, Int?) -> Unit) {
        val calendar = Calendar.getInstance().apply { time = currentDate }

        val subjectSchedule = if (!selectedSubject.isNullOrEmpty()) {
            getSubjectSchedule(requireContext(), selectedSubject)
        } else {
            emptyMap()
        }

        val today = Calendar.getInstance()
        val todayIndex = when (today.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            else -> -1
        }

        val datePickerDialog = DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                val newCalendar = Calendar.getInstance().apply {
                    set(year, month, day)
                }
                val newDate = newCalendar.time

                val dayOfWeek = newCalendar.get(Calendar.DAY_OF_WEEK)
                val dayIndex = when (dayOfWeek) {
                    Calendar.MONDAY -> 0
                    Calendar.TUESDAY -> 1
                    Calendar.WEDNESDAY -> 2
                    Calendar.THURSDAY -> 3
                    Calendar.FRIDAY -> 4
                    else -> -1
                }

                val suggestedLesson = if (dayIndex >= 0) {
                    subjectSchedule[dayIndex]?.firstOrNull()
                } else null

                if (suggestedLesson != null) {
                    // confirmation for recommended lesson
                    AlertDialog.Builder(requireContext())
                        .setTitle("Stunde vorschlagen")
                        .setMessage("Du hast ${selectedSubject} an diesem Tag in der ${suggestedLesson}. Stunde. Soll diese automatisch ausgewählt werden?")
                        .setPositiveButton("Ja") { _, _ ->
                            onDateSelected(newDate, suggestedLesson)
                        }
                        .setNegativeButton("Nein") { _, _ ->
                            onDateSelected(newDate, null)
                        }
                        .show()
                } else {
                    onDateSelected(newDate, null)
                }
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )

        // if able show days with today marked in []
        if (subjectSchedule.isNotEmpty()) {
            val availableDays = subjectSchedule.keys.map { dayIndex ->
                val dayName = getWeekdayName(dayIndex)
                if (dayIndex == todayIndex) "[$dayName]" else dayName
            }.joinToString(", ")
            datePickerDialog.setTitle("$selectedSubject: $availableDays")
        }

        datePickerDialog.show()
    }

    private fun getAvailableSubjects(): List<String> {
        val allSubjects = mutableSetOf<String>()

        val subjectsString = sharedPreferences.getString("student_subjects", "")
        val studentSubjects = subjectsString?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        allSubjects.addAll(studentSubjects)

        val timetableSubjects = getTimetableSubjects()
        allSubjects.addAll(timetableSubjects)

        return allSubjects.toList().sorted()
    }

    private fun getTimetableSubjects(): List<String> {
        val json = sharedPreferences.getString("timetable_data", "{}")
        val subjects = mutableSetOf<String>()

        try {
            val type = object : TypeToken<MutableMap<String, MutableMap<Int, Any>>>() {}.type
            val timetableData: MutableMap<String, MutableMap<Int, Any>> =
                Gson().fromJson(json, type) ?: mutableMapOf()

            for (dayIndex in 0..4) {
                val dayKey = "weekday_$dayIndex"
                val daySchedule = timetableData[dayKey] as? Map<*, *> ?: continue

                for ((_, entryData) in daySchedule) {
                    val entry = entryData as? Map<*, *> ?: continue
                    val subject = entry["subject"] as? String
                    if (!subject.isNullOrEmpty() && subject.trim().isNotEmpty()) {
                        subjects.add(subject.trim())
                    }
                }
            }
        } catch (e: Exception) {
            L.e(TAG, "Error loading subjects from timetable", e)
        }

        return subjects.toList().sorted()
    }

    private fun setupLessonSpinner(spinner: Spinner, selectedLessonNumber: Int?) {
        val lessonNumbers = getLessonNumbers()
        val lessonOptions = mutableListOf("Zeit (optional)")

        lessonOptions.addAll(lessonNumbers.map { lessonNum ->
            "${lessonNum}. Stunde (${lessonTimes[lessonNum]})"
        })

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, lessonOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        selectedLessonNumber?.let { lessonNum ->
            val position = lessonNumbers.indexOf(lessonNum) + 1 // +1 -> because "Zeit (optional)" at index 0
            if (position > 0) {
                spinner.setSelection(position)
            }
        }
    }

    private fun getLessonNumbers(): List<Int> {
        val bildungsgang = sharedPreferences.getString("selected_bildungsgang", "Nicht ausgewählt")

        return when (bildungsgang) {
            "FST" -> listOf(11, 12, 13, 14, 15)
            "FHR" -> (1..13).toList()
            else -> (1..10).toList()
        }
    }

    private fun updateDateButton(button: Button, date: Date) {
        val format = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)
        button.text = format.format(date)
        button.visibility = View.VISIBLE
    }

    private fun showDatePicker(currentDate: Date, onDateSelected: (Date) -> Unit) {
        val calendar = Calendar.getInstance().apply { time = currentDate }

        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                val newDate = Calendar.getInstance().apply {
                    set(year, month, day)
                }.time
                onDateSelected(newDate)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun addHomework(subject: String, dueDate: Date, lessonNumber: Int?, content: String) {
        val (checklistItems, hasTextContent) = parseContentWithChecklistItems(content)

        val dueTime = lessonNumber?.let { lessonNum ->
            val timeStr = lessonTimes[lessonNum] ?: return@let null
            val timeParts = timeStr.split(":")
            val calendar = Calendar.getInstance().apply {
                time = dueDate
                set(Calendar.HOUR_OF_DAY, timeParts[0].toInt())
                set(Calendar.MINUTE, timeParts[1].toInt())
                set(Calendar.SECOND, 0)
            }
            calendar.time
        }

        val homework = HomeworkEntry(
            subject = subject,
            dueDate = dueDate,
            dueTime = dueTime,
            lessonNumber = lessonNumber,
            content = content,
            checklistItems = checklistItems,
            hasTextContent = hasTextContent
        )

        homeworkList.add(homework)
        sortHomework()
        saveHomework()
        updateHomeworkCount()

        Toast.makeText(requireContext(), "Hausaufgabe hinzugefügt", Toast.LENGTH_SHORT).show()
    }

    private fun updateHomework(homework: HomeworkEntry, subject: String, dueDate: Date, lessonNumber: Int?, content: String) {
        val (checklistItems, hasTextContent) = parseContentWithChecklistItems(content)

        val dueTime = lessonNumber?.let { lessonNum ->
            val timeStr = lessonTimes[lessonNum] ?: return@let null
            val timeParts = timeStr.split(":")
            val calendar = Calendar.getInstance().apply {
                time = dueDate
                set(Calendar.HOUR_OF_DAY, timeParts[0].toInt())
                set(Calendar.MINUTE, timeParts[1].toInt())
                set(Calendar.SECOND, 0)
            }
            calendar.time
        }

        homework.subject = subject
        homework.dueDate = dueDate
        homework.dueTime = dueTime
        homework.lessonNumber = lessonNumber
        homework.content = content
        homework.checklistItems = checklistItems
        homework.hasTextContent = hasTextContent

        sortHomework()
        saveHomework()
        updateHomeworkCount()

        Toast.makeText(requireContext(), "Hausaufgabe aktualisiert", Toast.LENGTH_SHORT).show()
    }

    private fun parseContentWithChecklistItems(content: String): Pair<MutableList<ChecklistItem>, Boolean> {
        val checklistItems = mutableListOf<ChecklistItem>()
        val lines = content.split("\n")
        var hasTextContent = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("-") && trimmed.length > 1) {
                checklistItems.add(ChecklistItem(trimmed.substring(1).trim()))
            } else if (trimmed.isNotEmpty()) {
                hasTextContent = true
            }
        }

        return Pair(checklistItems, hasTextContent)
    }

    private fun deleteHomework(homework: HomeworkEntry) {
        AlertDialog.Builder(requireContext())
            .setTitle("Hausaufgabe löschen")
            .setMessage("Willst du diese Hausaufgabe wirklich löschen?")
            .setPositiveButton("Ja, löschen") { _, _ ->
                homeworkList.remove(homework)
                adapter.notifyDataSetChanged()
                saveHomework()
                updateHomeworkCount()
                Toast.makeText(requireContext(), "Hausaufgabe gelöscht", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showHomeworkDetailDialog(homework: HomeworkEntry) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_homework_detail, null)
        val textSubject = dialogView.findViewById<TextView>(R.id.textSubject)
        val textDueDate = dialogView.findViewById<TextView>(R.id.textDueDate)
        val layoutContent = dialogView.findViewById<LinearLayout>(R.id.layoutContent)

        textSubject.text = homework.subject
        textDueDate.text = homework.getDetailedDueDateString()

        if (homework.checklistItems.isNotEmpty()) {
            for (item in homework.checklistItems) {
                val checkBox = CheckBox(requireContext())
                checkBox.text = item.text
                checkBox.isChecked = item.isCompleted
                checkBox.setOnCheckedChangeListener { _, isChecked ->
                    item.isCompleted = isChecked
                    val wasAutoCompleted = homework.updateChecklistCompletion()
                    if (wasAutoCompleted) {
                        val position = homeworkList.indexOf(homework)
                        if (position != -1) {
                            adapter.notifyItemChanged(position)
                        }
                    }
                    saveHomework()
                }
                layoutContent.addView(checkBox)
            }
        }

        if (homework.hasTextContent) {
            if (homework.checklistItems.isNotEmpty()) {
                val separator = View(requireContext())
                separator.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    2
                )
                separator.setBackgroundColor(resources.getColor(android.R.color.darker_gray))
                val params = separator.layoutParams as LinearLayout.LayoutParams
                params.setMargins(0, 16, 0, 16)
                layoutContent.addView(separator)
            }

            val textContent = homework.content.split("\n")
                .filter { line -> !line.trim().startsWith("-") }
                .joinToString("\n")
                .trim()

            if (textContent.isNotEmpty()) {
                val textView = TextView(requireContext())
                textView.text = textContent
                textView.setPadding(16, 16, 16, 16)
                layoutContent.addView(textView)
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Hausaufgabe Details")
            .setView(dialogView)
            .setPositiveButton("Schließen", null)
            .show()
    }

    private fun sortHomework() {
        homeworkList.sortWith { a, b ->
            // firstly sort by completion status, then by overdue status
            when {
                // ongoing (not complete, not overdue) comes first
                !a.isCompleted && !a.isOverdue() && !b.isCompleted && !b.isOverdue() -> {
                    // both ongoing
                    when (currentSortOrder) {
                        SortOrder.DUE_DATE_ASC -> {
                            val dateComparison = a.dueDate.compareTo(b.dueDate)
                            if (dateComparison != 0) {
                                dateComparison
                            } else {
                                // same date, sort by time
                                when {
                                    a.dueTime != null && b.dueTime != null -> a.dueTime!!.compareTo(b.dueTime!!)
                                    a.dueTime != null && b.dueTime == null -> -1 // timed homework first
                                    a.dueTime == null && b.dueTime != null -> 1
                                    else -> 0
                                }
                            }
                        }
                        SortOrder.DUE_DATE_DESC -> {
                            val dateComparison = b.dueDate.compareTo(a.dueDate)
                            if (dateComparison != 0) {
                                dateComparison
                            } else {
                                // same date, sort by time (reverse)
                                when {
                                    a.dueTime != null && b.dueTime != null -> b.dueTime!!.compareTo(a.dueTime!!)
                                    a.dueTime != null && b.dueTime == null -> -1 // timed homework first
                                    a.dueTime == null && b.dueTime != null -> 1
                                    else -> 0
                                }
                            }
                        }
                        SortOrder.SUBJECT_ASC -> a.subject.compareTo(b.subject, ignoreCase = true)
                        SortOrder.SUBJECT_DESC -> b.subject.compareTo(a.subject, ignoreCase = true)
                    }
                }
                // ongoing and overdue
                !a.isCompleted && !a.isOverdue() && !b.isCompleted && b.isOverdue() -> -1
                !a.isCompleted && a.isOverdue() && !b.isCompleted && !b.isOverdue() -> 1
                // both overdue
                !a.isCompleted && a.isOverdue() && !b.isCompleted && b.isOverdue() -> {
                    when (currentSortOrder) {
                        SortOrder.DUE_DATE_ASC -> {
                            val dateComparison = a.dueDate.compareTo(b.dueDate)
                            if (dateComparison != 0) {
                                dateComparison
                            } else {
                                when {
                                    a.dueTime != null && b.dueTime != null -> a.dueTime!!.compareTo(b.dueTime!!)
                                    a.dueTime != null && b.dueTime == null -> -1
                                    a.dueTime == null && b.dueTime != null -> 1
                                    else -> 0
                                }
                            }
                        }
                        SortOrder.DUE_DATE_DESC -> {
                            val dateComparison = b.dueDate.compareTo(a.dueDate)
                            if (dateComparison != 0) {
                                dateComparison
                            } else {
                                when {
                                    a.dueTime != null && b.dueTime != null -> b.dueTime!!.compareTo(a.dueTime!!)
                                    a.dueTime != null && b.dueTime == null -> -1
                                    a.dueTime == null && b.dueTime != null -> 1
                                    else -> 0
                                }
                            }
                        }
                        SortOrder.SUBJECT_ASC -> a.subject.compareTo(b.subject, ignoreCase = true)
                        SortOrder.SUBJECT_DESC -> b.subject.compareTo(a.subject, ignoreCase = true)
                    }
                }
                // overdue and completed
                !a.isCompleted && a.isOverdue() && b.isCompleted -> -1
                a.isCompleted && !b.isCompleted && b.isOverdue() -> 1
                // ongoing and completed
                !a.isCompleted && !a.isOverdue() && b.isCompleted -> -1
                a.isCompleted && !b.isCompleted && !b.isOverdue() -> 1
                // both completed
                a.isCompleted && b.isCompleted -> {
                    when (currentSortOrder) {
                        SortOrder.DUE_DATE_ASC -> {
                            val dateComparison = a.dueDate.compareTo(b.dueDate)
                            if (dateComparison != 0) {
                                dateComparison
                            } else {
                                when {
                                    a.dueTime != null && b.dueTime != null -> a.dueTime!!.compareTo(b.dueTime!!)
                                    a.dueTime != null && b.dueTime == null -> -1
                                    a.dueTime == null && b.dueTime != null -> 1
                                    else -> 0
                                }
                            }
                        }
                        SortOrder.DUE_DATE_DESC -> {
                            val dateComparison = b.dueDate.compareTo(a.dueDate)
                            if (dateComparison != 0) {
                                dateComparison
                            } else {
                                when {
                                    a.dueTime != null && b.dueTime != null -> b.dueTime!!.compareTo(a.dueTime!!)
                                    a.dueTime != null && b.dueTime == null -> -1
                                    a.dueTime == null && b.dueTime != null -> 1
                                    else -> 0
                                }
                            }
                        }
                        SortOrder.SUBJECT_ASC -> a.subject.compareTo(b.subject, ignoreCase = true)
                        SortOrder.SUBJECT_DESC -> b.subject.compareTo(a.subject, ignoreCase = true)
                    }
                }
                else -> 0
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun saveHomework() {
        val json = Gson().toJson(homeworkList)
        sharedPreferences.edit()
            .putString(PREFS_HOMEWORK_LIST, json)
            .apply()
    }

    private fun loadHomework() {
        val json = sharedPreferences.getString(PREFS_HOMEWORK_LIST, "[]")
        val type = object : TypeToken<MutableList<HomeworkEntry>>() {}.type
        val loadedList: MutableList<HomeworkEntry> = Gson().fromJson(json, type) ?: mutableListOf<HomeworkEntry>()

        homeworkList.clear()
        homeworkList.addAll(loadedList)

        sortHomework()

        if (::adapter.isInitialized) {
            adapter.notifyDataSetChanged()
        }
    }

    private fun updateHomeworkCount() {
        val uncompletedCount = homeworkList.count { !it.isCompleted }
        val totalCount = homeworkList.size
        tvHomeworkCount.text = "$uncompletedCount / $totalCount Hausaufgaben"
    }

    private fun cleanupCompletedAndOverdueHomework() {
        val threeDaysAgo = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -3)
        }.time

        val initialSize = homeworkList.size
        homeworkList.removeAll { homework ->
            (homework.isCompleted && homework.completedDate?.before(threeDaysAgo) == true) ||
                    (homework.isOverdue() && homework.dueDate.before(threeDaysAgo))
        }

        if (homeworkList.size < initialSize) {
            adapter.notifyDataSetChanged()
            saveHomework()
            updateHomeworkCount()
            L.d(TAG, "Cleaned up ${initialSize - homeworkList.size} old completed and overdue homework entries")
        }
    }

    private fun isAutoDeleteEnabled(): Boolean {
        return sharedPreferences.getBoolean(PREFS_AUTO_DELETE, true)
    }

    private fun showExportOptions() {
        val content = exportHomeworkData()
        val options = arrayOf("Als Datei speichern", "In Zwischenablage kopieren")

        AlertDialog.Builder(requireContext())
            .setTitle("Hausaufgaben exportieren")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> saveToFile(content)
                    1 -> copyToClipboard(content)
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showImportOptions() {
        val options = arrayOf("Aus Datei importieren", "Aus Zwischenablage importieren")

        AlertDialog.Builder(requireContext())
            .setTitle("Hausaufgaben importieren")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> importFromFilePicker()
                    1 -> importFromClipboard()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun exportHomeworkData(): String {
        return backupManager.exportHomeworkData()
    }

    private fun saveToFile(content: String) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.GERMAN).format(Date())
        val filename = "hausaufgaben_export_${timestamp}.hksh"

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, filename)
        }
        sharedPreferences.edit().putString("temp_export_content", content).apply()

        try {
            startActivityForResult(intent, EXPORT_FILE_REQUEST_CODE)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Fehler beim Öffnen des Datei-Dialogs", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveToSelectedFile(uri: Uri, content: String) {
        try {
            requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(content.toByteArray())
            }
            Toast.makeText(requireContext(), "Export erfolgreich gespeichert", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            L.e(TAG, "Error saving to file", e)
            Toast.makeText(requireContext(), "Fehler beim Speichern der Datei", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyToClipboard(content: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Hausaufgaben Export", content)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "Export in Zwischenablage kopiert", Toast.LENGTH_SHORT).show()
    }

    private fun importFromFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        filePickerLauncher.launch(Intent.createChooser(intent, "Hausaufgaben-Datei auswählen"))
    }

    private fun importFromFile(uri: Uri) {
        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            val content = inputStream?.bufferedReader()?.use { it.readText() }

            if (content != null) {
                importHomeworkData(content)
            } else {
                Toast.makeText(requireContext(), "Fehler beim Lesen der Datei", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            L.e(TAG, "Error importing from file", e)
            Toast.makeText(requireContext(), "Fehler beim Importieren der Datei", Toast.LENGTH_LONG).show()
        }
    }

    private fun importFromClipboard() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip

        if (clip != null && clip.itemCount > 0) {
            val content = clip.getItemAt(0).text.toString()
            importHomeworkData(content)
        } else {
            Toast.makeText(requireContext(), "Zwischenablage ist leer", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importHomeworkData(content: String) {
        try {
            backupManager.importHomeworkData(content)

            loadHomework()
            updateHomeworkCount()

            Toast.makeText(requireContext(), "Hausaufgaben erfolgreich importiert", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            L.e(TAG, "Error importing homework data via BackupManager", e)
            Toast.makeText(requireContext(), "Fehler beim Importieren: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun getSubjectSchedule(context: Context, subject: String): Map<Int, List<Int>> {
        val sharedPreferences = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val json = sharedPreferences.getString("timetable_data", "{}")

        try {
            val type = object : TypeToken<MutableMap<String, MutableMap<Int, Any>>>() {}.type
            val timetableData: MutableMap<String, MutableMap<Int, Any>> =
                Gson().fromJson(json, type) ?: mutableMapOf()

            val result = mutableMapOf<Int, MutableList<Int>>()

            for (dayIndex in 0..4) {
                val dayKey = "weekday_$dayIndex"
                val daySchedule = timetableData[dayKey] as? Map<*, *> ?: continue

                for ((lesson, entryData) in daySchedule) {
                    val entry = entryData as? Map<*, *> ?: continue
                    val entrySubject = entry["subject"] as? String ?: continue

                    if (entrySubject == subject) {
                        val lessonNum = lesson.toString().toIntOrNull() ?: continue
                        if (!result.containsKey(dayIndex)) {
                            result[dayIndex] = mutableListOf()
                        }
                        result[dayIndex]?.add(lessonNum)
                    }
                }

                result[dayIndex]?.sort()
            }

            return result.mapValues { it.value.toList() }
        } catch (e: Exception) {
            L.e(TAG, "Error loading timetable for subject lookup", e)
            return emptyMap()
        }
    }

    fun getWeekdayName(dayIndex: Int): String {
        return when (dayIndex) {
            0 -> "Montag"
            1 -> "Dienstag"
            2 -> "Mittwoch"
            3 -> "Donnerstag"
            4 -> "Freitag"
            else -> "Unbekannt"
        }
    }

    fun getNextSubjectDate(context: Context, subject: String): Pair<Date, Int?>? {
        val schedule = getSubjectSchedule(context, subject)
        if (schedule.isEmpty()) return null

        val today = Calendar.getInstance()
        val todayIndex = when (today.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            else -> -1
        }

        // start searching from tomorrow
        for (dayOffset in 1..14) { // max 2 weeks ahead, then fallback to simply next
            val targetDay = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, dayOffset)
            }

            // skip weekends
            val dayOfWeek = targetDay.get(Calendar.DAY_OF_WEEK)
            if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) {
                continue
            }

            val checkDayIndex = when (dayOfWeek) {
                Calendar.MONDAY -> 0
                Calendar.TUESDAY -> 1
                Calendar.WEDNESDAY -> 2
                Calendar.THURSDAY -> 3
                Calendar.FRIDAY -> 4
                else -> continue
            }

            schedule[checkDayIndex]?.let { lessons ->
                for (lesson in lessons) {
                    if (hasSchoolOnLesson(checkDayIndex, lesson)) {
                        val dateForCheck = targetDay.time

                        // check for holiday/vacation
                        if (isDateHolidayOrVacation(context, dateForCheck)) {
                            continue
                        }

                        // check if cancelled
                        if (isLessonCancelled(context, dateForCheck, lesson, subject)) {
                            continue
                        }

                        return Pair(dateForCheck, lesson)
                    }
                }
            }
        }

        return null
    }

    private fun isDateHolidayOrVacation(context: Context, date: Date): Boolean {
        val sharedPrefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

        try {
            // holidays
            val holidaysJson = sharedPrefs.getString("holidays_data", "[]")
            val holidaysArray = JSONArray(holidaysJson)

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val dateStr = dateFormat.format(date)

            for (i in 0 until holidaysArray.length()) {
                val holiday = holidaysArray.getJSONObject(i)
                val holidayDate = holiday.optString("date", "")
                if (holidayDate == dateStr) {
                    L.d(TAG, "Date $dateStr is a holiday")
                    return true
                }
            }

            // vacations
            val vacationsJson = sharedPrefs.getString("vacations_data", "[]")
            val vacationsArray = JSONArray(vacationsJson)

            for (i in 0 until vacationsArray.length()) {
                val vacation = vacationsArray.getJSONObject(i)
                val startDateStr = vacation.optString("start_date", "")
                val endDateStr = vacation.optString("end_date", "")

                if (startDateStr.isNotEmpty() && endDateStr.isNotEmpty()) {
                    val startDate = dateFormat.parse(startDateStr)
                    val endDate = dateFormat.parse(endDateStr)

                    if (startDate != null && endDate != null) {
                        if (date.after(startDate) && date.before(endDate) ||
                            date.equals(startDate) || date.equals(endDate)) {
                            L.d(TAG, "Date $dateStr is in vacation period")
                            return true
                        }
                    }
                }
            }

        } catch (e: Exception) {
            L.e(TAG, "Error checking holidays/vacations", e)
        }

        return false
    }

    private fun isLessonCancelled(context: Context, date: Date, lesson: Int, subject: String): Boolean {
        val sharedPrefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val klasse = sharedPrefs.getString("selected_klasse", "") ?: return false

        try {
            val cacheFile = File(context.cacheDir, "substitute_plan_$klasse.json")
            if (!cacheFile.exists()) {
                return false
            }

            val cachedData = cacheFile.readText()
            val jsonData = JSONObject(cachedData)
            val dates = jsonData.optJSONArray("dates") ?: return false

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val targetDateStr = dateFormat.format(date)

            for (i in 0 until dates.length()) {
                val dateObj = dates.getJSONObject(i)
                val dateString = dateObj.getString("date")

                if (dateString == targetDateStr) {
                    val entries = dateObj.getJSONArray("entries")

                    for (j in 0 until entries.length()) {
                        val entry = entries.getJSONObject(j)
                        val entryLesson = entry.getInt("stunde")
                        val entrySubject = entry.optString("fach", "")
                        val entryText = entry.optString("text", "")

                        if (entryLesson == lesson &&
                            entrySubject.equals(subject, ignoreCase = true) &&
                            (entryText.contains("Entfällt", ignoreCase = true) ||
                                    entryText.contains("verlegt", ignoreCase = true))) {
                            L.d(TAG, "Lesson $lesson for $subject on $targetDateStr is cancelled")
                            return true
                        }
                    }
                }
            }

        } catch (e: Exception) {
            L.e(TAG, "Error checking cancelled lessons", e)
        }

        return false
    }

    private fun hasSchoolOnLesson(dayIndex: Int, lesson: Int): Boolean {
        return try {
            val json = sharedPreferences.getString("timetable_data", "{}")
            val type = object : TypeToken<MutableMap<String, MutableMap<Int, Any>>>() {}.type
            val timetableData: MutableMap<String, MutableMap<Int, Any>> =
                Gson().fromJson(json, type) ?: mutableMapOf()

            val dayKey = "weekday_$dayIndex"
            val daySchedule = timetableData[dayKey] as? Map<*, *> ?: return true

            val entryData = daySchedule[lesson] as? Map<*, *> ?: return false

            val subject = entryData["subject"] as? String
            val hasSchool = entryData["hasSchool"] as? Boolean ?: true

            return !subject.isNullOrEmpty() && hasSchool
        } catch (e: Exception) {
            L.e(TAG, "Error checking school status", e)
            true
        }
    }
}