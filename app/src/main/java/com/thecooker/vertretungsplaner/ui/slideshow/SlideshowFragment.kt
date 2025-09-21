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
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import com.thecooker.vertretungsplaner.utils.BackupManager
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.content.edit
import com.thecooker.vertretungsplaner.utils.HomeworkShareHelper
import androidx.core.net.toUri
import android.provider.Settings
import android.graphics.drawable.ColorDrawable
import android.graphics.Color
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import com.thecooker.vertretungsplaner.ui.gallery.GalleryFragment.InternalConstants

class SlideshowFragment : Fragment() {

    private var isLoading = true
    private lateinit var loadingView: View
    private var isInitialized = false

    private lateinit var btnAddHomework: Button
    private lateinit var btnMenu: Button
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvHomeworkCount: TextView
    private lateinit var adapter: HomeworkAdapter
    private lateinit var sharedPreferences: SharedPreferences

    private lateinit var backupManager: BackupManager

    private var homeworkList = mutableListOf<HomeworkEntry>()

    private lateinit var searchBarHomework: EditText

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

    private val lessonTimes = mapOf(
        1 to "07:30", 2 to "08:15", 3 to "09:30", 4 to "10:15", 5 to "11:15",
        6 to "12:00", 7 to "13:15", 8 to "14:00", 9 to "15:00", 10 to "15:45",
        11 to "17:00", 12 to "17:45", 13 to "18:45", 14 to "19:30", 15 to "20:15"
    )

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
        fun getDueDateString(context: Context): String {
            Calendar.getInstance()
            Calendar.getInstance().apply { time = dueDate }

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
                                hoursDiff > 0 -> context.getString(R.string.slide_time_in_hours, hoursDiff)
                                minutesDiff > 0 -> context.getString(R.string.slide_time_in_minutes, minutesDiff)
                                else -> context.getString(R.string.slide_due_now)
                            }
                        } else {
                            // time passed -> overdue
                            if (lessonNumber != null) {
                                context.getString(R.string.slide_overdue)
                            } else {
                                context.getString(R.string.slide_overdue_today)
                            }
                        }
                    } ?: context.getString(R.string.slide_due_today)
                }
                daysDiff == 1L -> context.getString(R.string.slide_due_tomorrow)
                daysDiff == -1L -> context.getString(R.string.slide_due_yesterday)
                daysDiff > 1 -> {
                    val weeks = daysDiff / 7
                    if (weeks > 0) {
                        context.resources.getQuantityString(R.plurals.slide_due_in_weeks, weeks.toInt(), weeks.toInt())
                    } else {
                        context.resources.getQuantityString(R.plurals.slide_due_in_days, daysDiff.toInt(), daysDiff.toInt())
                    }
                }
                else -> {
                    val daysPast = (-daysDiff).toInt()
                    context.resources.getQuantityString(R.plurals.slide_due_days_ago, daysPast, daysPast)
                }
            }

            return if (lessonNumber != null) {
                context.getString(R.string.slide_due_lesson, dateStr, lessonNumber)
            } else {
                dateStr
            }
        }

        fun getBackgroundColor(context: Context): Int = when {
            isCompleted -> context.getThemeColor(R.attr.homeworkCompletedBackgroundColor)
            isOverdue() -> context.getThemeColor(R.attr.homeworkOverdueBackgroundColor)
            isDueToday() -> context.getThemeColor(R.attr.homeworkDueTodayBackgroundColor)
            isDueTomorrow() -> context.getThemeColor(R.attr.homeworkDueTomorrowBackgroundColor)
            else -> context.getThemeColor(R.attr.homeworkTransparentBackgroundColor)
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

        fun isOverdue(): Boolean {
            val now = Calendar.getInstance()
            val due = Calendar.getInstance().apply {
                time = dueDate
                // if specific time available -> use it, otherwise default to end of day
                dueTime?.let { time ->
                    val timeCalendar = Calendar.getInstance().apply { this.time = time }
                    set(Calendar.HOUR_OF_DAY, timeCalendar.get(Calendar.HOUR_OF_DAY))
                    set(Calendar.MINUTE, timeCalendar.get(Calendar.MINUTE))
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                } ?: run {
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                    set(Calendar.MILLISECOND, 999)
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
            if (isAdded && view.parent != null) {
                performHeavyInitialization()
            }
        }

        return view
    }

    private fun performHeavyInitialization() {
        try {
            setupRecyclerView()
            loadHomework()
            setupSearchBar()
            setupListeners()
            updateHomeworkCount()

            isLoading = false
            isInitialized = true
            hideLoadingState()

            cleanupHandler.post(cleanupRunnable)

            L.d(TAG, "Homework fragment initialization completed successfully")
        } catch (e: Exception) {
            L.e(TAG, "Error during initialization", e)
            showErrorState()
        }
    }

    private fun showErrorState() {
        if (!isAdded || view == null) return

        try {
            hideLoadingState()
            Toast.makeText(requireContext(), getString(R.string.slide_loading_error), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            L.e(TAG, "Error showing error state", e)
        }
    }

    private fun showLoadingState() {
        loadingView = LayoutInflater.from(requireContext()).inflate(android.R.layout.simple_list_item_1, null)

        val loadingText = loadingView.findViewById<TextView>(android.R.id.text1)
        loadingText.apply {
            text = getString(R.string.slide_homework_loading)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(32, 64, 32, 64)

            val typedValue = TypedValue()
            val theme = requireContext().theme
            theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
            val textColor = if (typedValue.resourceId != 0) {
                ContextCompat.getColor(requireContext(), typedValue.resourceId)
            } else {
                typedValue.data
            }
            setTextColor(textColor)

            setTypeface(null, Typeface.BOLD)
        }

        recyclerView.visibility = View.GONE
        searchBarHomework.visibility = View.GONE
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
        searchBarHomework.visibility = View.VISIBLE
        btnAddHomework.visibility = View.VISIBLE
        btnMenu.visibility = View.VISIBLE
        tvHomeworkCount.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cleanupHandler.removeCallbacks(cleanupRunnable)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == EXPORT_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                val content = sharedPreferences.getString("temp_export_content", "") ?: ""
                saveToSelectedFile(uri, content)
                sharedPreferences.edit { remove("temp_export_content") }
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
        searchBarHomework = view.findViewById(R.id.searchBarHomework)
        btnAddHomework = view.findViewById(R.id.btnAddHomework)
        btnMenu = view.findViewById(R.id.btnMenu)
        recyclerView = view.findViewById(R.id.recyclerViewHomework)
        tvHomeworkCount = view.findViewById(R.id.tvHomeworkCount)
    }

    private fun setupSearchBar() {
        searchBarHomework.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                adapter.filter(s.toString())
                updateHomeworkCount()
            }
        })
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
        popup.menu.add(0, 1, 0, getString(R.string.act_set_export)).apply {
            setIcon(R.drawable.ic_export)
        }
        popup.menu.add(0, 2, 0, getString(R.string.act_set_import)).apply {
            setIcon(R.drawable.ic_import)
        }

        try {
            val fieldMPopup = PopupMenu::class.java.getDeclaredField("mPopup")
            fieldMPopup.isAccessible = true
            val mPopup = fieldMPopup.get(popup)
            mPopup.javaClass
                .getDeclaredMethod("setForceShowIcon", Boolean::class.java)
                .invoke(mPopup, true)
        } catch (e: Exception) {
            L.w("GalleryFragment", "Could not force show icons", e)
        }

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

        sharedPreferences.getBoolean("has_scanned_document", false)
        val availableSubjects = getAvailableSubjects()
        val hasTimetableData = availableSubjects.isNotEmpty()

        val isAutoEnabled = if (editHomework != null) {
            false // force uncheck when editing
        } else {
            sharedPreferences.getBoolean(PREFS_AUTO_HOMEWORK, false)
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
                sharedPreferences.edit { putBoolean(PREFS_AUTO_HOMEWORK, isChecked) }
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
            .setTitle(if (editHomework != null) getString(R.string.slide_edit_homework) else getString(R.string.slide_add_homework))
            .setView(dialogView)
            .setPositiveButton(if (editHomework != null) getString(R.string.slide_save) else getString(R.string.slide_add)) { _, _ ->
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
                    Toast.makeText(requireContext(), getString(R.string.slide_enter_subject), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.show()

        val buttonColor = requireContext().getThemeColor(R.attr.dialogSectionButtonColor)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)
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
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, allSubjects)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        selectedSubject?.let { subject ->
            if (!isInternalConstant(subject)) {
                val position = allSubjects.indexOf(subject)
                if (position >= 0) {
                    spinner.setSelection(position)
                }
            }
        }
    }

    private fun showCustomDatePicker(
        currentDate: Date,
        selectedSubject: String?,
        onDateSelected: (Date, Int?) -> Unit
    ) {
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
                    val alertDialog = AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.slide_suggest_lesson))
                        .setMessage(getString(R.string.slide_suggest_lesson_message, selectedSubject, suggestedLesson))
                        .setPositiveButton(getString(R.string.slide_yes)) { _, _ ->
                            onDateSelected(newDate, suggestedLesson)
                        }
                        .setNegativeButton(getString(R.string.slide_no)) { _, _ ->
                            onDateSelected(newDate, null)
                        }
                        .show()

                    val buttonColor = requireContext().getThemeColor(R.attr.dialogSectionButtonColor)
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
                    alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
                    alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)
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
            val availableDays = subjectSchedule.keys.joinToString(", ") { dayIndex ->
                val dayName = getWeekdayName(dayIndex)
                if (dayIndex == todayIndex) "[$dayName]" else dayName
            }
            datePickerDialog.setTitle("$selectedSubject: $availableDays")
        }

        datePickerDialog.setOnShowListener {
            val buttonColor = requireContext().getThemeColor(R.attr.dialogSectionButtonColor)
            datePickerDialog.getButton(DatePickerDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
            datePickerDialog.getButton(DatePickerDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
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

        val filteredSubjects = allSubjects.filter { subject ->
            !isInternalConstant(subject) &&
                    !subject.equals(getString(R.string.slide_free_lesson), ignoreCase = true) &&
                    subject.trim().isNotEmpty()
        }

        return filteredSubjects.toList().sorted()
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
                    if (!subject.isNullOrEmpty() && subject.trim().isNotEmpty() && !isInternalConstant(subject)) {
                        subjects.add(subject.trim())
                    }
                }
            }
        } catch (e: Exception) {
            L.e(TAG, "Error loading subjects from timetable", e)
        }

        return subjects.toList().sorted()
    }

    private fun isInternalConstant(subject: String): Boolean {
        return when (subject) {
            InternalConstants.FREE_LESSON,
            InternalConstants.NO_SCHOOL,
            InternalConstants.HOLIDAY_TYPE_AUTUMN,
            InternalConstants.HOLIDAY_TYPE_WINTER,
            InternalConstants.HOLIDAY_TYPE_EASTER,
            InternalConstants.HOLIDAY_TYPE_SUMMER,
            InternalConstants.HOLIDAY_TYPE_WHITSUN,
            InternalConstants.HOLIDAY_TYPE_SPRING,
            InternalConstants.HOLIDAY_TYPE_GENERIC,
            InternalConstants.HOLIDAY_GENERAL -> true
            else -> false
        }
    }

    private fun setupLessonSpinner(spinner: Spinner, selectedLessonNumber: Int?) {
        val lessonNumbers = getLessonNumbers()
        val lessonOptions = mutableListOf(getString(R.string.slide_time_optional))

        lessonOptions.addAll(lessonNumbers.map { lessonNum ->
            getString(R.string.slide_lesson_format, lessonNum, lessonTimes[lessonNum])
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
        val bildungsgang = sharedPreferences.getString("selected_bildungsgang", getString(R.string.set_act_not_selected))

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

        Toast.makeText(requireContext(), getString(R.string.slide_homework_added), Toast.LENGTH_SHORT).show()
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

        Toast.makeText(requireContext(), getString(R.string.slide_homework_updated), Toast.LENGTH_SHORT).show()
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
        val alertDialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.slide_delete_homework))
            .setMessage(getString(R.string.slide_delete_confirm))
            .setPositiveButton(getString(R.string.set_act_yes_delete)) { _, _ ->
                homeworkList.remove(homework)
                adapter.notifyDataSetChanged()
                saveHomework()
                updateHomeworkCount()
                Toast.makeText(requireContext(), getString(R.string.slide_homework_deleted), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()

        val buttonColor = requireContext().getThemeColor(R.attr.dialogSectionButtonColor)
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)
    }

    private fun showHomeworkDetailDialog(homework: HomeworkEntry) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_homework_detail, null)
        val textSubject = dialogView.findViewById<TextView>(R.id.textSubject)
        val textDueDate = dialogView.findViewById<TextView>(R.id.textDueDate)
        val layoutContent = dialogView.findViewById<LinearLayout>(R.id.layoutContent)
        val btnShareHomework = dialogView.findViewById<ImageButton>(R.id.btnShareHomework)
        val btnCopyToClipboard = dialogView.findViewById<ImageButton>(R.id.btnCopyToClipboard)

        textSubject.text = homework.subject
        textDueDate.text = getDetailedDueDateStringWithWeekday(homework)
        textDueDate.setTextColor(requireContext().getThemeColor(R.attr.homeworkDetailDueDateTextColor))

        layoutContent.removeAllViews()

        if (homework.checklistItems.isEmpty() && !homework.hasTextContent) {
            val noNotesView = TextView(requireContext())
            noNotesView.text = getString(R.string.dia_ho_de_no_notes)
            noNotesView.setTextColor(resources.getColor(android.R.color.darker_gray))
            noNotesView.setPadding(16, 16, 16, 16)
            layoutContent.addView(noNotesView)
        } else {
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
                    val spacer = View(requireContext())
                    spacer.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        16
                    )
                    layoutContent.addView(spacer)
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
        }

        btnShareHomework.setOnClickListener {
            shareHomework(homework)
        }

        btnCopyToClipboard.setOnClickListener {
            copyHomeworkToClipboard(homework)
        }

        val alertDialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.slide_homework_details))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.slide_close), null)
            .setNeutralButton(getString(R.string.gall_edit)) { _, _ ->
                showAddHomeworkDialog(homework)
            }
            .show()

        val buttonColor = requireContext().getThemeColor(R.attr.dialogSectionButtonColor)
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)
    }

    private fun getDetailedDueDateStringWithWeekday(homework: HomeworkEntry): String {
        val calendar = Calendar.getInstance().apply { time = homework.dueDate }
        val dayOfWeek = when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> getString(R.string.monday_short)
            Calendar.TUESDAY -> getString(R.string.tuesday_short)
            Calendar.WEDNESDAY -> getString(R.string.wednesday_short)
            Calendar.THURSDAY -> getString(R.string.thursday_short)
            Calendar.FRIDAY -> getString(R.string.friday_short)
            Calendar.SATURDAY -> getString(R.string.saturday_short)
            Calendar.SUNDAY -> getString(R.string.sunday_short)
            else -> ""
        }

        val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)
        val timeFormat = SimpleDateFormat("HH:mm", Locale.GERMANY)

        var result = getString(R.string.slide_due_date_format_weekday, dayOfWeek, dateFormat.format(homework.dueDate))

        if (homework.dueTime != null) {
            result += " ${getString(R.string.slide_due_date_time, timeFormat.format(homework.dueTime!!))}"
        }

        if (homework.lessonNumber != null) {
            result += " ${getString(R.string.slide_due_date_lesson, homework.lessonNumber)}"
        }

        return result
    }

    private fun sortHomework() {
        homeworkList.sortWith { a, b ->
            when {
                // both completed -> sort by due date then due time
                a.isCompleted && b.isCompleted -> {
                    val dateComparison = a.dueDate.compareTo(b.dueDate)
                    if (dateComparison != 0) {
                        dateComparison
                    } else {
                        compareByTime(a, b)
                    }
                }

                // one completed, one not -> uncompleted first unless overdue
                a.isCompleted && !b.isCompleted -> {
                    if (b.isOverdue()) -1 else 1
                }
                !a.isCompleted && b.isCompleted -> {
                    if (a.isOverdue()) 1 else -1
                }

                // both uncompleted
                !a.isCompleted && !b.isCompleted -> {
                    when {
                        // both overdue -> sort by date then time
                        a.isOverdue() && b.isOverdue() -> {
                            val dateComparison = a.dueDate.compareTo(b.dueDate)
                            if (dateComparison != 0) {
                                dateComparison
                            } else {
                                compareByTime(a, b)
                            }
                        }

                        // one overdue, one not -> overdue goes after non-overdue
                        a.isOverdue() && !b.isOverdue() -> 1
                        !a.isOverdue() && b.isOverdue() -> -1

                        // both not overdue -> sort by date then time
                        else -> {
                            val dateComparison = a.dueDate.compareTo(b.dueDate)
                            if (dateComparison != 0) {
                                dateComparison
                            } else {
                                compareByTime(a, b)
                            }
                        }
                    }
                }

                else -> 0
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun compareByTime(a: HomeworkEntry, b: HomeworkEntry): Int {
        return when {
            // both have due times -> sort by time
            a.dueTime != null && b.dueTime != null -> {
                a.dueTime!!.compareTo(b.dueTime!!)
            }
            // 1 has time, 2 doesnt -> 1 comes first (higher priority)
            a.dueTime != null && b.dueTime == null -> -1
            // 2 has time, 1 doesnt -> 2 comes first (higher priority)
            a.dueTime == null && b.dueTime != null -> 1
            // neither has time -> equal priority
            else -> 0
        }
    }

    private fun saveHomework() {
        val json = Gson().toJson(homeworkList)
        sharedPreferences.edit {
            putString(PREFS_HOMEWORK_LIST, json)
        }
    }

    private fun loadHomework() {
        val json = sharedPreferences.getString(PREFS_HOMEWORK_LIST, "[]")
        val type = object : TypeToken<MutableList<HomeworkEntry>>() {}.type
        val loadedList: MutableList<HomeworkEntry> = Gson().fromJson(json, type) ?: mutableListOf()

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
        tvHomeworkCount.text = getString(R.string.slide_homework_count, uncompletedCount, totalCount)
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

        val options = listOf(
            Pair(getString(R.string.set_act_export_file), R.drawable.ic_export_file),
            Pair(getString(R.string.set_act_backup_copy_clipboard), R.drawable.ic_export_clipboard)
        )

        val adapter = object : ArrayAdapter<Pair<String, Int>>(
            requireContext(),
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

        val alertDialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.slide_export_homework))
            .setAdapter(adapter) { _, which ->
                when (which) {
                    0 -> saveToFile(content)
                    1 -> copyToClipboard(content)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()

        val buttonColor = requireContext().getThemeColor(R.attr.dialogSectionButtonColor)
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)
    }

    private fun showImportOptions() {
        val options = listOf(
            Pair(getString(R.string.set_act_import_file), R.drawable.ic_import_file),
            Pair(getString(R.string.set_act_backup_import_clipboard), R.drawable.ic_import_clipboard)
        )

        val adapter = object : ArrayAdapter<Pair<String, Int>>(
            requireContext(),
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

        val alertDialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.slide_import_homework))
            .setAdapter(adapter) { _, which ->
                when (which) {
                    0 -> importFromFilePicker()
                    1 -> importFromClipboard()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()

        val buttonColor = requireContext().getThemeColor(R.attr.dialogSectionButtonColor)
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)
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
        sharedPreferences.edit { putString("temp_export_content", content) }

        try {
            startActivityForResult(intent, EXPORT_FILE_REQUEST_CODE)
        } catch (_: Exception) {
            Toast.makeText(requireContext(), getString(R.string.set_act_export_file_error), Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveToSelectedFile(uri: Uri, content: String) {
        try {
            requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(content.toByteArray())
            }
            Toast.makeText(requireContext(), getString(R.string.slide_export_saved), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            L.e(TAG, "Error saving to file", e)
            Toast.makeText(requireContext(), getString(R.string.slide_save_error), Toast.LENGTH_LONG).show()
        }
    }

    private fun copyToClipboard(content: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Hausaufgaben Export", content)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), getString(R.string.slide_export_copied), Toast.LENGTH_SHORT).show()
    }

    private fun importFromFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        filePickerLauncher.launch(Intent.createChooser(intent, getString(R.string.slide_choose_homework_file)))
    }

    private fun importFromFile(uri: Uri) {
        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            val content = inputStream?.bufferedReader()?.use { it.readText() }

            if (content != null) {
                importHomeworkData(content)
            } else {
                Toast.makeText(requireContext(), getString(R.string.slide_file_read_error), Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            L.e(TAG, "Error importing from file", e)
            Toast.makeText(requireContext(), getString(R.string.slide_import_file_error), Toast.LENGTH_LONG).show()
        }
    }

    private fun importFromClipboard() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip

        if (clip != null && clip.itemCount > 0) {
            val content = clip.getItemAt(0).text.toString()
            importHomeworkData(content)
        } else {
            Toast.makeText(requireContext(), getString(R.string.set_act_import_clipboard_empty), Toast.LENGTH_SHORT).show()
        }
    }

    private fun importHomeworkData(content: String) {
        try {
            backupManager.importHomeworkData(content)

            loadHomework()
            updateHomeworkCount()

            Toast.makeText(requireContext(), getString(R.string.slide_import_success), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            L.e(TAG, "Error importing homework data via BackupManager", e)
            Toast.makeText(requireContext(), getString(R.string.set_act_import_error, e.message), Toast.LENGTH_LONG).show()
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
            0 -> getString(R.string.slide_monday)
            1 -> getString(R.string.slide_tuesday)
            2 -> getString(R.string.slide_wednesday)
            3 -> getString(R.string.slide_thursday)
            4 -> getString(R.string.slide_friday)
            else -> getString(R.string.slide_unknown)
        }
    }

    fun getNextSubjectDate(context: Context, subject: String): Pair<Date, Int?>? {
        val schedule = getSubjectSchedule(context, subject)
        if (schedule.isEmpty()) return null

        val today = Calendar.getInstance()
        when (today.get(Calendar.DAY_OF_WEEK)) {
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
                            date == startDate || date == endDate
                        ) {
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        L.d(TAG, "onViewCreated called")
        L.d(TAG, "Arguments: $arguments")

        if (isInitialized) {
            arguments?.getString("shared_homework_uri")?.let { uriString ->
                handleSharedHomework(uriString.toUri())
                arguments?.remove("shared_homework_uri")
            }

            arguments?.getString("highlight_homework_id")?.let { homeworkId ->
                L.d(TAG, "Processing highlight_homework_id: $homeworkId")
                highlightAndShowHomework(homeworkId)
            }

            Handler(Looper.getMainLooper()).post {
                L.d(TAG, "Clearing arguments to prevent reuse")
                arguments?.clear()
                arguments = Bundle()
            }
        }
    }

    private fun handleSharedHomework(uri: Uri) {
        try {
            val shareHelper = HomeworkShareHelper(requireContext())
            val sharedHomework = shareHelper.parseSharedHomework(uri)

            if (sharedHomework != null && sharedHomework.type == "homework") {
                val homeworkEntry = shareHelper.convertToHomeworkEntry(sharedHomework)

                if (homeworkEntry != null) { // clear already to prevent retry
                    sharedPreferences.edit {
                        remove("pending_shared_homework_uri")
                        putBoolean("shared_content_processed", true)
                    }

                    showSharedHomeworkDialog(homeworkEntry, sharedHomework.sharedBy)
                } else {
                    Toast.makeText(requireContext(), getString(R.string.share_parse_error), Toast.LENGTH_LONG).show()
                    sharedPreferences.edit {
                        remove("pending_shared_homework_uri")
                        putBoolean("shared_content_processed", true)
                    }
                }
            } else {
                Toast.makeText(requireContext(), getString(R.string.share_invalid_file), Toast.LENGTH_LONG).show()
                sharedPreferences.edit {
                    remove("pending_shared_homework_uri")
                    putBoolean("shared_content_processed", true)
                }
            }
        } catch (e: Exception) {
            L.e(TAG, "Error handling shared homework", e)
            Toast.makeText(requireContext(), getString(R.string.share_error_generic), Toast.LENGTH_LONG).show()
            sharedPreferences.edit {
                remove("pending_shared_homework_uri")
                putBoolean("shared_content_processed", true)
            }
        }
    }

    private fun showSharedHomeworkDialog(sharedHomework: HomeworkEntry, sharedBy: String?) {
        val existingHomework = findExistingHomework(sharedHomework)

        val message = if (existingHomework != null) {
            if (sharedBy != null) {
                getString(R.string.share_overwrite_message_with_sender, sharedHomework.subject, sharedBy)
            } else {
                getString(R.string.share_overwrite_message, sharedHomework.subject)
            }
        } else {
            if (sharedBy != null) {
                getString(R.string.share_add_message_with_sender, sharedHomework.subject, sharedBy)
            } else {
                getString(R.string.share_add_message, sharedHomework.subject)
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.share_dialog_title))
            .setMessage(message)
            .setPositiveButton(if (existingHomework != null) getString(R.string.share_overwrite) else getString(R.string.share_add)) { _, _ ->
                if (existingHomework != null) {
                    existingHomework.content = sharedHomework.content
                    existingHomework.checklistItems = sharedHomework.checklistItems
                    existingHomework.hasTextContent = sharedHomework.hasTextContent
                } else {
                    homeworkList.add(sharedHomework)
                }

                sortHomework()
                saveHomework()
                updateHomeworkCount()

                Toast.makeText(requireContext(),
                    if (existingHomework != null) getString(R.string.share_homework_updated)
                    else getString(R.string.slide_homework_added),
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                // do nothing as was cancelled already
            }
            .setOnCancelListener {
                // same here
            }
            .show()
    }

    private fun findExistingHomework(sharedHomework: HomeworkEntry): HomeworkEntry? {
        return homeworkList.find { existing ->
            existing.subject == sharedHomework.subject &&
                    existing.dueDate.time == sharedHomework.dueDate.time &&
                    existing.lessonNumber == sharedHomework.lessonNumber
        }
    }

    private fun shareHomework(homework: HomeworkEntry) {
        val shareHelper = HomeworkShareHelper(requireContext())
        val userName = sharedPreferences.getString("user_name", null)

        val shareIntent = shareHelper.shareHomework(homework, userName)
        if (shareIntent != null) {
            try {
                startActivity(shareIntent)
            } catch (e: Exception) {
                L.e(TAG, "Error starting share activity", e)
                Toast.makeText(requireContext(), getString(R.string.share_error_generic), Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(requireContext(), getString(R.string.share_create_error), Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyHomeworkToClipboard(homework: HomeworkEntry) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        val contentText = if (homework.checklistItems.isNotEmpty() || homework.hasTextContent) {
            homework.content
        } else {
            getString(R.string.dia_ho_de_no_notes)
        }

        val clip = ClipData.newPlainText(getString(R.string.slide_homework_notes), contentText)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), getString(R.string.dia_ho_de_copied), Toast.LENGTH_SHORT).show()
    }

    fun highlightAndShowHomework(homeworkId: String) {
        val homework = homeworkList.find { it.id == homeworkId }
        if (homework == null) {
            Toast.makeText(requireContext(), getString(R.string.slide_homework_not_found), Toast.LENGTH_SHORT).show()
            return
        }

        val position = adapter.getFilteredList().indexOf(homework)
        if (position == -1) {
            searchBarHomework.setText("")
            adapter.filter("")
            val newPosition = adapter.getFilteredList().indexOf(homework)
            if (newPosition != -1) {
                highlightAndScrollToPosition(newPosition, homework)
            }
        } else {
            highlightAndScrollToPosition(position, homework)
        }
    }

    private fun highlightAndScrollToPosition(position: Int, homework: HomeworkEntry) {
        recyclerView.scrollToPosition(position)

        recyclerView.post {
            val viewHolder = recyclerView.findViewHolderForAdapterPosition(position)
            if (viewHolder != null) {
                highlightViewHolder(viewHolder, homework)
            } else {
                recyclerView.postDelayed({
                    val vh = recyclerView.findViewHolderForAdapterPosition(position)
                    if (vh != null) {
                        highlightViewHolder(vh, homework)
                    }
                }, 50)
            }
        }
    }

    private fun highlightViewHolder(viewHolder: RecyclerView.ViewHolder, homework: HomeworkEntry) {
        val itemView = viewHolder.itemView

        val animationsDisabled = Settings.Global.getFloat(
            requireContext().contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f

        if (animationsDisabled) {
            highlightWithoutAnimation(itemView)
        } else {
            highlightWithAnimation(itemView)
        }

        Handler(Looper.getMainLooper()).postDelayed({
            if (isAdded && !isDetached && !isRemoving && activity != null && !requireActivity().isFinishing) {
                try {
                    showHomeworkDetailDialog(homework)
                } catch (e: Exception) {
                    L.e("SlideshowFragment", "Error showing homework details dialog", e)
                }
            } else {
                L.d("SlideshowFragment", "Fragment not in valid state to show dialog - skipping")
            }
        }, 500)
    }

    private fun highlightWithAnimation(itemView: View) {
        val originalBackground = itemView.background
        val originalBackgroundColor = if (itemView.backgroundTintList != null) {
            itemView.backgroundTintList?.defaultColor
        } else if (itemView.background is ColorDrawable) {
            (itemView.background as ColorDrawable).color
        } else null

        val highlightColor = resources.getColor(android.R.color.holo_blue_light)
        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f, 0f, 1f, 0f, 1f, 0f)
        animator.duration = 2000

        animator.addUpdateListener { animation ->
            val animatedValue = animation.animatedValue as Float

            if (originalBackgroundColor != null) {
                val blendedColor = blendColors(originalBackgroundColor, highlightColor, animatedValue)
                itemView.setBackgroundColor(blendedColor)
            } else {
                val alpha = (animatedValue * 100).toInt()
                val color = Color.argb(
                    alpha,
                    Color.red(highlightColor),
                    Color.green(highlightColor),
                    Color.blue(highlightColor)
                )
                itemView.setBackgroundColor(color)
            }
        }

        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                itemView.background = originalBackground
            }
        })

        animator.start()
    }

    private fun highlightWithoutAnimation(itemView: View) {
        val originalBackground = itemView.background
        val highlightColor = resources.getColor(android.R.color.holo_blue_light)

        itemView.setBackgroundColor(highlightColor)

        Handler(Looper.getMainLooper()).postDelayed({ // restore after 2 seconds
            itemView.background = originalBackground
        }, 2000)
    }

    private fun blendColors(color1: Int, color2: Int, ratio: Float): Int {
        val inverseRatio = 1f - ratio
        val r = (Color.red(color1) * inverseRatio + Color.red(color2) * ratio).toInt()
        val g = (Color.green(color1) * inverseRatio + Color.green(color2) * ratio).toInt()
        val b = (Color.blue(color1) * inverseRatio + Color.blue(color2) * ratio).toInt()
        val a = (Color.alpha(color1) * inverseRatio + Color.alpha(color2) * ratio).toInt()
        return Color.argb(a, r, g, b)
    }

    override fun onResume() {
        super.onResume()
        L.d(TAG, "onResume called")

        try {
            if (!isAdded || view == null) {
                L.w(TAG, "Fragment not properly attached")
                return
            }

            arguments?.getString("shared_homework_uri")?.let { uriString ->
                if (isInitialized) {
                    handleSharedHomework(uriString.toUri())
                } else {
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (isInitialized) handleSharedHomework(uriString.toUri())
                    }, 200)
                }
                arguments?.remove("shared_homework_uri")
            }

            arguments?.getString("highlight_homework_id")?.let { homeworkId ->
                if (isInitialized) {
                    highlightAndShowHomework(homeworkId)
                } else {
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (isInitialized) highlightAndShowHomework(homeworkId)
                    }, 200)
                }
            }

            Handler(Looper.getMainLooper()).post {
                arguments?.clear()
                arguments = Bundle()
            }

        } catch (e: Exception) {
            L.e(TAG, "Error in onResume", e)
            showErrorState()
        }
    }
}