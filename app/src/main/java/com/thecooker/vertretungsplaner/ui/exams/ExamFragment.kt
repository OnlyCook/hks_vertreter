package com.thecooker.vertretungsplaner.ui.exams

import android.app.Activity
import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
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
import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.parser.PdfTextExtractor
import com.thecooker.vertretungsplaner.R
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import com.itextpdf.text.pdf.parser.SimpleTextExtractionStrategy
import com.thecooker.vertretungsplaner.data.CalendarDataManager
import com.thecooker.vertretungsplaner.data.ExamManager
import com.thecooker.vertretungsplaner.utils.BackupManager
import androidx.core.content.edit
import com.thecooker.vertretungsplaner.utils.ExamShareHelper
import android.provider.Settings
import android.graphics.Color
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

class ExamFragment : Fragment() {

    private var isLoading = true
    private lateinit var loadingView: View

    private lateinit var searchBar: EditText
    private lateinit var btnAddExam: Button
    private lateinit var btnMenu: Button
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvExamCount: TextView
    private lateinit var adapter: ExamAdapter
    private lateinit var sharedPreferences: SharedPreferences

    private var examList = mutableListOf<ExamEntry>()
    private var filteredExamList = mutableListOf<ExamEntry>()

    // file picker pdf import
    private lateinit var pdfPickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var exportLauncher: ActivityResultLauncher<Intent>

    private val allowOldSchedules = false // set to true for testing only

    private lateinit var backupManager: BackupManager

    private lateinit var examShareHelper: ExamShareHelper
    private var isProcessingSharedExam = false

    // exam notes preservation
    private var preserveExamNotes = false

    data class ExamEntry(
        val id: String = UUID.randomUUID().toString(),
        var subject: String,
        var date: Date,
        var note: String = "",
        var isCompleted: Boolean = false,
        var examNumber: Int? = null,
        var isFromSchedule: Boolean = false,
        var mark: Int? = null // grade points (0 - 15) only settable when overdue
    ) {
        fun isOverdue(): Boolean {
            val today = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val examDate = Calendar.getInstance().apply {
                time = date
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return examDate.before(today)
        }

        fun isTodayOrOverdue(): Boolean {
            val today = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            val examDate = Calendar.getInstance().apply {
                time = date
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            return examDate.timeInMillis <= today.timeInMillis
        }

        fun getDaysUntilExam(): Long {
            val today = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val examDate = Calendar.getInstance().apply {
                time = date
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return TimeUnit.MILLISECONDS.toDays(examDate.timeInMillis - today.timeInMillis)
        }

        fun getBackgroundColorAttribute(): Int {
            if (isOverdue()) {
                isCompleted = true
                return R.attr.examOverdueBackground
            }

            val daysUntil = getDaysUntilExam()
            return when {
                daysUntil <= 1 -> R.attr.examUrgentBackground
                daysUntil <= 3 -> R.attr.examWarningBackground
                daysUntil <= 7 -> R.attr.examSoonBackground
                daysUntil <= 14 -> R.attr.examUpcomingBackground
                else -> 0
            }
        }

        fun getDetailedDateString(): String {
            val weekdayFormat = SimpleDateFormat("E", Locale.getDefault())
            val dayFormat = SimpleDateFormat("d", Locale.getDefault())
            val monthFormat = SimpleDateFormat("MMMM", Locale.getDefault())
            val yearFormat = SimpleDateFormat("yyyy", Locale.getDefault())

            val weekdayStr = weekdayFormat.format(date)
            val dayStr = dayFormat.format(date)
            val monthStr = monthFormat.format(date)
            val yearStr = yearFormat.format(date)

            val dayWithOrdinal = if (Locale.getDefault().language == "en") {
                dayStr + getOrdinalSuffix(dayStr.toInt())
            } else {
                "$dayStr."
            }

            return "$weekdayStr $dayWithOrdinal $monthStr $yearStr"
        }

        private fun getOrdinalSuffix(day: Int): String {
            return when {
                day in 11..13 -> "th" // edge case ah
                day % 10 == 1 -> "st"
                day % 10 == 2 -> "nd"
                day % 10 == 3 -> "rd"
                else -> "th"
            }
        }

        fun getDisplayDateString(context: Context): String {
            val daysUntil = getDaysUntilExam()
            val weekdayFormat = SimpleDateFormat("E", Locale.getDefault())
            val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

            val weekdayStr = weekdayFormat.format(date)
            val dateStr = dateFormat.format(date)
            val formattedDate = "$weekdayStr $dateStr"

            return when {
                isOverdue() -> context.getString(R.string.exam_date_detail_overdue, formattedDate)
                daysUntil == 0L -> context.getString(R.string.exam_date_detail_today, formattedDate)
                daysUntil == 1L -> context.getString(R.string.exam_date_detail_tomorrow, formattedDate)
                daysUntil <= 7 -> {
                    context.resources.getQuantityString(
                        R.plurals.exam_date_detail_in_given_days,
                        daysUntil.toInt(),
                        formattedDate,
                        daysUntil.toInt()
                    )
                }
                daysUntil <= 30 -> {
                    val weeks = daysUntil / 7
                    val remainingDays = daysUntil % 7
                    when {
                        weeks == 1L && remainingDays == 0L -> context.getString(R.string.exam_date_detail_one_week, formattedDate)
                        weeks == 1L -> {
                            context.resources.getQuantityString(
                                R.plurals.exam_date_detail_one_week_plus_days,
                                remainingDays.toInt(),
                                formattedDate,
                                remainingDays.toInt()
                            )
                        }
                        remainingDays == 0L -> {
                            context.resources.getQuantityString(
                                R.plurals.exam_date_detail_weeks,
                                weeks.toInt(),
                                formattedDate,
                                weeks.toInt()
                            )
                        }
                        else -> {
                            context.resources.getQuantityString(
                                R.plurals.exam_date_detail_weeks_plus_days,
                                remainingDays.toInt(),
                                formattedDate,
                                weeks.toInt(),
                                remainingDays.toInt()
                            )
                        }
                    }
                }
                daysUntil <= 365 -> {
                    val months = daysUntil / 30
                    val remainingDays = daysUntil % 30
                    when {
                        months == 1L && remainingDays <= 5 -> context.getString(R.string.exam_date_detail_one_month, formattedDate)
                        months == 1L -> {
                            context.resources.getQuantityString(
                                R.plurals.exam_date_detail_one_month_plus_days,
                                remainingDays.toInt(),
                                formattedDate,
                                remainingDays.toInt()
                            )
                        }
                        remainingDays <= 5 -> {
                            context.resources.getQuantityString(
                                R.plurals.exam_date_detail_months,
                                months.toInt(),
                                formattedDate,
                                months.toInt()
                            )
                        }
                        else -> {
                            context.resources.getQuantityString(
                                R.plurals.exam_date_detail_months_plus_days,
                                remainingDays.toInt(),
                                formattedDate,
                                months.toInt(),
                                remainingDays.toInt()
                            )
                        }
                    }
                }
                else -> {
                    val years = daysUntil / 365
                    val remainingDays = daysUntil % 365
                    when {
                        years == 1L && remainingDays <= 10 -> context.getString(R.string.exam_date_detail_one_year, formattedDate)
                        years == 1L -> {
                            val months = remainingDays / 30
                            if (months > 0) {
                                context.resources.getQuantityString(
                                    R.plurals.exam_date_detail_one_year_plus_months,
                                    months.toInt(),
                                    formattedDate,
                                    months.toInt()
                                )
                            } else {
                                context.getString(R.string.exam_date_detail_one_year, formattedDate)
                            }
                        }
                        remainingDays <= 10 -> {
                            context.resources.getQuantityString(
                                R.plurals.exam_date_detail_years,
                                years.toInt(),
                                formattedDate,
                                years.toInt()
                            )
                        }
                        else -> {
                            val months = remainingDays / 30
                            if (months > 0) {
                                context.resources.getQuantityString(
                                    R.plurals.exam_date_detail_years_plus_months,
                                    months.toInt(),
                                    formattedDate,
                                    years.toInt(),
                                    months.toInt()
                                )
                            } else {
                                context.resources.getQuantityString(
                                    R.plurals.exam_date_detail_years,
                                    years.toInt(),
                                    formattedDate,
                                    years.toInt()
                                )
                            }
                        }
                    }
                }
            }
        }

        fun getGradeFromMark(): String? {
            return when (mark) {
                0 -> "6"
                1 -> "5-"
                2 -> "5"
                3 -> "5+"
                4 -> "4-"
                5 -> "4"
                6 -> "4+"
                7 -> "3-"
                8 -> "3"
                9 -> "3+"
                10 -> "2-"
                11 -> "2"
                12 -> "2+"
                13 -> "1-"
                14 -> "1"
                15 -> "1+"
                else -> null
            }
        }

        fun getNumericalGrade(): Double? { // convert points to numerical grade
            return when (mark) {
                null -> null
                else -> {
                    val grade = (17.0 - mark!!) / 3.0 // (17 - points) / 3
                    maxOf(1.0, minOf(6.0, grade)) // clamp
                }
            }
        }
    }

    data class ExamScheduleInfo(
        val semester: String,
        val year: String,
        val className: String,
        val isValid: Boolean
    )

    data class DayEntry(
        val day: Int,
        val weekday: String,
        val content: String,
        val monthIndex: Int // to which month column this belongs to
    )

    companion object {
        private const val TAG = "ExamFragment"
        private const val PREFS_EXAM_LIST = "exam_list"
        private const val PREFS_EXAM_SCHEDULE_INFO = "exam_schedule_info"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_exam, container, false)

        sharedPreferences = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        backupManager = BackupManager(requireContext())
        examShareHelper = ExamShareHelper(requireContext())

        initializeViews(view)
        setupFilePickerLaunchers()

        showLoadingState() // instant

        view.post { // async
            CalendarDataManager.getInstance(requireContext()).loadCalendarData()

            setupRecyclerView()
            loadExams()
            setupListeners()
            handleSharedExam()
            updateExamCount()

            isLoading = false
            hideLoadingState()
        }

        return view
    }

    private fun showLoadingState() {
        loadingView = LayoutInflater.from(requireContext()).inflate(android.R.layout.simple_list_item_1, null)

        val loadingText = loadingView.findViewById<TextView>(android.R.id.text1)
        loadingText.apply {
            text = getString(R.string.exam_loading)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(32, 64, 32, 64)
            setTextColor(resources.getColor(android.R.color.black))
            setTypeface(null, Typeface.BOLD)
        }

        recyclerView.visibility = View.GONE
        searchBar.visibility = View.GONE
        btnAddExam.visibility = View.GONE
        btnMenu.visibility = View.GONE
        tvExamCount.visibility = View.GONE

        val rootLayout = recyclerView.parent as ViewGroup
        rootLayout.addView(loadingView, 0)
    }

    private fun hideLoadingState() {
        if (::loadingView.isInitialized) {
            val rootLayout = loadingView.parent as? ViewGroup
            rootLayout?.removeView(loadingView)
        }

        recyclerView.visibility = View.VISIBLE
        searchBar.visibility = View.VISIBLE
        btnAddExam.visibility = View.VISIBLE
        btnMenu.visibility = View.VISIBLE
        tvExamCount.visibility = View.VISIBLE
    }

    private fun initializeViews(view: View) {
        searchBar = view.findViewById(R.id.searchBarExam)
        btnAddExam = view.findViewById(R.id.btnAddExam)
        btnMenu = view.findViewById(R.id.btnMenuExam)
        recyclerView = view.findViewById(R.id.recyclerViewExam)
        tvExamCount = view.findViewById(R.id.tvExamCount)
    }

    private fun setupFilePickerLaunchers() {
        pdfPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    processPdfExamSchedule(uri)
                }
            }
        }

        exportLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    val content = sharedPreferences.getString("temp_export_content", "") ?: ""
                    saveToSelectedFile(uri, content)
                    sharedPreferences.edit {remove("temp_export_content")}
                }
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = ExamAdapter(
            examList = filteredExamList,
            onExamDeleted = { exam ->
                deleteExam(exam)
            },
            onExamEdited = { exam ->
                showAddExamDialog(exam)
            },
            onExamDetailsRequested = { exam ->
                showExamDetailsDialog(exam)
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
    }

    private fun setupListeners() {
        btnAddExam.setOnClickListener {
            showAddExamDialog()
        }

        btnMenu.setOnClickListener {
            showMenuPopup()
        }

        searchBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterExams(s.toString())
            }
        })
    }

    private fun showMenuPopup() {
        val popup = PopupMenu(requireContext(), btnMenu)
        popup.menu.add(0, 1, 0, getString(R.string.exam_scan_schedule)).apply {
            setIcon(R.drawable.ic_scan_file)
        }
        popup.menu.add(0, 2, 0, getString(R.string.exam_export)).apply {
            setIcon(R.drawable.ic_export)
        }
        popup.menu.add(0, 3, 0, getString(R.string.exam_import)).apply {
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
                    scanExamSchedule()
                    true
                }
                2 -> {
                    showExportOptions()
                    true
                }
                3 -> {
                    showImportOptions()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun scanExamSchedule() {
        val hasScannedDocument = sharedPreferences.getBoolean("has_scanned_document", false)

        if (!hasScannedDocument) {
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.exam_timetable_required))
                .setMessage(getString(R.string.exam_timetable_required_message))
                .setPositiveButton(getString(R.string.exam_ok), null)
                .show()

            val buttonColor = requireContext().getThemeColor(R.attr.dialogSectionButtonColor)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)
        }

        val hasExamsWithNotesOrGrades = examList.any {
            it.note.isNotBlank() || it.mark != null
        }

        if (hasExamsWithNotesOrGrades) {
            showNotePreservationDialog()
        } else {
            proceedWithExamScheduleScan(preserveNotes = false)
        }
    }

    private fun showNotePreservationDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_exam, null)

        val container = dialogView as ViewGroup
        container.removeAllViews()

        val messageText = TextView(requireContext()).apply {
            text = getString(R.string.exam_preserve_notes_message)
            textSize = 16f
            setPadding(0, 0, 0, 32)
        }

        val checkBox = CheckBox(requireContext()).apply {
            text = getString(R.string.exam_preserve_notes)
            isChecked = true
        }

        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 32)
            addView(messageText)
            addView(checkBox)
        }

        container.addView(layout)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.exam_scan_schedule))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.exam_continue)) { _, _ ->
                proceedWithExamScheduleScan(preserveNotes = checkBox.isChecked)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()

        val buttonColor = requireContext().getThemeColor(R.attr.dialogSectionButtonColor)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
    }

    private fun proceedWithExamScheduleScan(preserveNotes: Boolean) {
        this.preserveExamNotes = preserveNotes
        L.d(TAG, "Setting preserveExamNotes to: $preserveNotes")

        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "application/pdf"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        pdfPickerLauncher.launch(Intent.createChooser(intent, getString(R.string.exam_select_pdf)))
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

    private fun processPdfExamSchedule(uri: Uri) {
        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            inputStream?.use { stream ->
                val pdfText = extractTextFromPdf(stream)
                L.d(TAG, "PDF Text extracted:\n$pdfText")

                val oldExams = if (preserveExamNotes && examList.isNotEmpty()) {
                    val oldExamsCopy = examList.filter { it.note.isNotBlank() || it.mark != null }.map {
                        it.copy(
                            id = it.id,
                            subject = it.subject,
                            date = Date(it.date.time),
                            note = it.note,
                            isCompleted = it.isCompleted,
                            examNumber = it.examNumber,
                            isFromSchedule = it.isFromSchedule,
                            mark = it.mark
                        )
                    }
                    L.d(TAG, "STORED ${oldExamsCopy.size} old exams with notes/grades in processPdfExamSchedule")
                    oldExamsCopy.forEach { exam ->
                        L.d(TAG, "Will preserve: ${exam.subject} on ${SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(exam.date)} - note: '${exam.note}', mark: ${exam.mark}, overdue: ${exam.isOverdue()}")
                    }
                    oldExamsCopy
                } else {
                    L.d(TAG, "No old exams to preserve in processPdfExamSchedule")
                    emptyList()
                }

                examList.clear()

                parseExamSchedule(pdfText, oldExams)
            }
        } catch (e: Exception) {
            L.e(TAG, "Error processing PDF", e)
            Toast.makeText(requireContext(), getString(R.string.exam_pdf_error), Toast.LENGTH_LONG).show()
        }
    }

    private fun extractTextFromPdf(inputStream: InputStream): String {
        val reader = PdfReader(inputStream)
        val stringBuilder = StringBuilder()

        for (i in 1..reader.numberOfPages) {
            try {
                val strategy = SimpleTextExtractionStrategy()
                val pageText = PdfTextExtractor.getTextFromPage(reader, i, strategy)
                stringBuilder.append(pageText)
                stringBuilder.append("\n")
            } catch (_: Exception) {
                stringBuilder.append(PdfTextExtractor.getTextFromPage(reader, i))
                stringBuilder.append("\n")
            }
        }

        reader.close()
        return stringBuilder.toString()
    }

    private fun parseExamSchedule(pdfText: String, storedOldExams: List<ExamEntry> = emptyList()) {
        try {
            L.d(TAG, "parseExamSchedule called with preserveExamNotes: $preserveExamNotes")
            L.d(TAG, "Current exam list size: ${examList.size}")
            L.d(TAG, "Stored old exams size: ${storedOldExams.size}")

            val userClass = sharedPreferences.getString("selected_klasse", getString(R.string.set_act_not_selected))
            if (userClass == getString(R.string.set_act_not_selected)) {
                Toast.makeText(requireContext(), getString(R.string.exam_select_class), Toast.LENGTH_LONG).show()
                return
            }

            L.d(TAG, "User class: $userClass")

            // general prefix extracting "13BG" from "13BG1"
            val classPrefix = userClass?.let {
                if (it.length >= 4) it.substring(0, it.length - 1) else it
            } ?: return

            L.d(TAG, "Class prefix: $classPrefix")

            val userPage = findUserClassPage(pdfText, classPrefix)
            if (userPage == null) {
                L.e(TAG, "User class page not found for $classPrefix")
                Toast.makeText(requireContext(), getString(R.string.exam_class_not_found), Toast.LENGTH_LONG).show()
                return
            }

            L.d(TAG, "Found user page (before preprocessing):\n$userPage")

            val preprocessedPage = preprocessScheduleText(userPage)
            L.d(TAG, "Preprocessed user page:\n$preprocessedPage")

            val scheduleInfo = extractScheduleInfo(preprocessedPage, classPrefix)
            if (!allowOldSchedules && !scheduleInfo.isValid) {
                Toast.makeText(requireContext(), getString(R.string.exam_schedule_invalid), Toast.LENGTH_LONG).show()
                return
            }

            L.d(TAG, "Schedule info: $scheduleInfo")

            val newExams = parseExamsFromSchedule(preprocessedPage)

            if (newExams.isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.exam_no_exams_found), Toast.LENGTH_SHORT).show()
                return
            }

            L.d(TAG, "Found ${newExams.size} exams")

            examList.addAll(newExams)

            L.d(TAG, "After adding new exams: examList.size = ${examList.size}, storedOldExams.size = ${storedOldExams.size}")

            if (preserveExamNotes && storedOldExams.isNotEmpty()) {
                L.d(TAG, "Calling preserveExamNotesFromOldExams with ${storedOldExams.size} stored old exams")
                preserveExamNotesFromOldExams(storedOldExams)
            } else {
                L.d(TAG, "Skipping note preservation - preserveExamNotes: $preserveExamNotes, storedOldExams.size: ${storedOldExams.size}")
            }

            sortExams()
            saveExams()
            filterExams(searchBar.text.toString())
            updateExamCount()

            saveScheduleInfo(scheduleInfo)

            Toast.makeText(requireContext(), getString(R.string.exam_import_success, newExams.size), Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            L.e(TAG, "Error parsing exam schedule", e)
            Toast.makeText(requireContext(), getString(R.string.exam_processing_error), Toast.LENGTH_LONG).show()
        }
    }

    private fun preserveExamNotesFromOldExams(oldExams: List<ExamEntry>) {
        L.d(TAG, "=== Starting note/grade preservation from ${oldExams.size} old exams ===")

        // Include ALL exams that have notes or grades (including overdue ones)
        val oldExamsToPreserve = oldExams.filter {
            val hasNote = it.note.isNotBlank()
            val hasGrade = it.mark != null
            val shouldPreserve = hasNote || hasGrade

            if (shouldPreserve) {
                L.d(TAG, "Will preserve: ${it.subject} - hasNote: $hasNote, hasGrade: $hasGrade, isOverdue: ${it.isOverdue()}")
            }

            shouldPreserve
        }

        L.d(TAG, "Found ${oldExamsToPreserve.size} old exams with notes or grades to preserve")

        var preservedCount = 0

        for (oldExam in oldExamsToPreserve) {
            val dateStr = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(oldExam.date)
            L.d(TAG, "Processing old exam: ${oldExam.subject} on $dateStr with note: '${oldExam.note}', mark: ${oldExam.mark}")

            // First priority: exact match (same subject and date)
            val exactMatch = examList.find { newExam ->
                val subjectMatch = newExam.subject.equals(oldExam.subject, ignoreCase = true)
                val dateMatch = isSameDay(newExam.date, oldExam.date)
                L.d(TAG, "Checking exact match with ${newExam.subject}: subjectMatch=$subjectMatch, dateMatch=$dateMatch")
                subjectMatch && dateMatch
            }

            if (exactMatch != null) {
                var preserved = false

                // Always preserve note if old exam has one
                if (oldExam.note.isNotBlank()) {
                    exactMatch.note = oldExam.note
                    preserved = true
                    L.d(TAG, "Preserved note for exact match")
                }

                // Always preserve mark if it exists
                if (oldExam.mark != null) {
                    exactMatch.mark = oldExam.mark
                    preserved = true
                    L.d(TAG, "Preserved mark for exact match")
                }

                if (preserved) {
                    preservedCount++
                    L.d(TAG, "✓ Exact match found - preserved data for ${exactMatch.subject}")
                }
                continue
            }

            // Second priority: same subject with date difference less than 1 month
            val subjectMatch = examList.find { newExam ->
                val subjectMatches = newExam.subject.equals(oldExam.subject, ignoreCase = true)
                val withinMonth = isWithinOneMonth(oldExam.date, newExam.date)
                L.d(TAG, "Checking subject match with ${newExam.subject}: subjectMatches=$subjectMatches, withinMonth=$withinMonth")
                subjectMatches && withinMonth
            }

            if (subjectMatch != null) {
                val daysDiff = getDaysDifference(oldExam.date, subjectMatch.date)
                var preserved = false

                // Preserve note if old exam has one and new exam doesn't
                if (oldExam.note.isNotBlank() && subjectMatch.note.isBlank()) {
                    subjectMatch.note = oldExam.note
                    preserved = true
                    L.d(TAG, "Preserved note for subject match")
                }

                // Preserve mark if old exam has one and new exam doesn't
                if (oldExam.mark != null && subjectMatch.mark == null) {
                    subjectMatch.mark = oldExam.mark
                    preserved = true
                    L.d(TAG, "Preserved mark for subject match")
                }

                if (preserved) {
                    preservedCount++
                    L.d(TAG, "✓ Subject match found with $daysDiff days difference - preserved data for ${subjectMatch.subject}")
                } else {
                    L.d(TAG, "Subject match found but no data preserved (new exam already has data)")
                }
                continue
            }

            // Third priority: For overdue exams with grades, add them as separate entries if no match found
            if (oldExam.isOverdue() && oldExam.mark != null) {
                val existsInNewList = examList.any {
                    it.subject.equals(oldExam.subject, ignoreCase = true) &&
                            isSameDay(it.date, oldExam.date)
                }

                if (!existsInNewList) {
                    // Add the overdue exam back to preserve the grade
                    examList.add(oldExam.copy())
                    preservedCount++
                    L.d(TAG, "✓ Added overdue exam with grade back to list: ${oldExam.subject}")
                    continue
                }
            }

            L.d(TAG, "✗ No suitable match found for ${oldExam.subject} - data will be lost")
        }

        L.d(TAG, "=== Preservation complete: preserved data for $preservedCount exams ===")
    }

    private fun isWithinOneMonth(date1: Date, date2: Date): Boolean {
        val daysDiff = kotlin.math.abs(getDaysDifference(date1, date2))
        return daysDiff <= 30
    }

    private fun getDaysDifference(date1: Date, date2: Date): Long {
        val cal1 = Calendar.getInstance().apply {
            time = date1
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val cal2 = Calendar.getInstance().apply {
            time = date2
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        return TimeUnit.MILLISECONDS.toDays(cal2.timeInMillis - cal1.timeInMillis)
    }

    private fun findUserClassPage(pdfText: String, classPrefix: String): String? {
        val lines = pdfText.split("\n")
        var pageStart: Int
        var pageEnd = -1

        val userClass = sharedPreferences.getString("selected_klasse", getString(R.string.set_act_not_selected)) ?: getString(R.string.set_act_not_selected)
        L.d(TAG, "Looking for user class: $userClass")
        L.d(TAG, "Class prefix: $classPrefix")

        // search for "Halbjahr" line and then users class
        for (i in lines.indices) {
            val line = lines[i].trim()
            L.d(TAG, "Checking line $i: $line")

            if (line.contains("Halbjahr")) {
                val isMatch = when {
                    // specific classes "13BG1"
                    userClass.matches(Regex(".*\\d$")) -> {
                        // general class "13BG"
                        val userBaseClass = userClass.dropLast(1)
                        val exactMatch = line.contains("\\s$userClass\\s*$".toRegex()) || line.contains("\\s$userClass$".toRegex())
                        val baseMatch = line.contains("\\s$userBaseClass(?!\\d)\\s*$".toRegex())

                        L.d(TAG, "User base class: $userBaseClass, Exact match: $exactMatch, Base match: $baseMatch")
                        exactMatch || baseMatch
                    }
                    else -> {
                        val pattern = "\\s$userClass(?!\\d)\\s*$".toRegex()
                        line.contains(pattern)
                    }
                }

                if (isMatch) {
                    L.d(TAG, "Found matching Halbjahr line at index $i: $line")
                    pageEnd = i

                    if (i + 1 < lines.size) {
                        val monthsLine = lines[i + 1].trim()
                        L.d(TAG, "Months line: $monthsLine")
                        if (isMonthsLine(monthsLine)) {
                            pageEnd = i + 1 // line after months line page end
                            break
                        }
                    }
                }
            }
        }

        if (pageEnd == -1) {
            L.e(TAG, "Could not find end of page (Halbjahr line)")
            return null
        }

        pageStart = 0
        for (i in pageEnd - 2 downTo 0) {
            val line = lines[i].trim()
            if (isMonthsLine(line)) {
                pageStart = i + 1
                L.d(TAG, "Found page start at line $pageStart (after months line: $line)")
                break
            }
        }

        val pageLines = lines.subList(pageStart, pageEnd + 1)
        val page = pageLines.joinToString("\n")

        L.d(TAG, "Extracted page from line $pageStart to $pageEnd")
        L.d(TAG, "Page content:\n$page")

        return page
    }

    private fun isMonthsLine(line: String): Boolean {
        val germanMonthNames = listOf("Januar", "Februar", "März", "April", "Mai", "Juni",
            "Juli", "August", "September", "Oktober", "November", "Dezember")

        val monthCount = germanMonthNames.count { month -> line.contains(month) }
        val hasYear = line.contains(Regex("20\\d{2}"))

        return monthCount >= 3 && hasYear
    }

    private fun extractScheduleInfo(page: String, classPrefix: String): ExamScheduleInfo {
        val lines = page.split("\n")
        var semester = ""
        var year = ""
        val className = classPrefix

        // find line with "Halbjahr"
        for (line in lines) {
            if (line.contains("Halbjahr")) {
                L.d(TAG, "Processing Halbjahr line: $line")

                // extract half years
                val semesterRegex = "(\\d+\\.)\\s*Halbjahr".toRegex()
                val semesterMatch = semesterRegex.find(line)
                if (semesterMatch != null) {
                    semester = semesterMatch.groupValues[1] + " Halbjahr"
                }

                // extract years
                val yearRegex = "(20\\d{2}/20\\d{2})".toRegex()
                val yearMatch = yearRegex.find(line)
                if (yearMatch != null) {
                    year = yearMatch.groupValues[1]
                }
                break
            }
        }

        L.d(TAG, "Extracted semester: '$semester', year: '$year'")

        // check using half years if schedule is still valid
        val isValid = if (allowOldSchedules) true else isScheduleValid(semester, year)

        return ExamScheduleInfo(semester, year, className, isValid)
    }

    private fun isScheduleValid(semester: String, year: String): Boolean {
        if (semester.isEmpty() || year.isEmpty()) return false

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val currentMonth = Calendar.getInstance().get(Calendar.MONTH) + 1

        val yearParts = year.split("/")
        if (yearParts.size != 2) return false

        val startYear = yearParts[0].toIntOrNull() ?: return false
        val endYear = yearParts[1].toIntOrNull() ?: return false

        // 1. halbjahr: aug - jan
        // 2. halbjahr: feb - july
        return when {
            semester.contains("1.") -> {
                (currentYear == startYear && currentMonth >= 8) ||
                        (currentYear == endYear && currentMonth <= 1)
            }
            semester.contains("2.") -> {
                currentYear == endYear && currentMonth in 2..7
            }
            else -> false
        }
    }

    private fun parseExamsFromSchedule(page: String): List<ExamEntry> {
        val exams = mutableListOf<ExamEntry>()
        val studentSubjects = getStudentSubjects()
        val studentTeachers = getStudentTeachers()

        val calendarManager = CalendarDataManager.getInstance(requireContext())
        calendarManager.clearCalendarData()

        L.d(TAG, "Student subjects: $studentSubjects")
        L.d(TAG, "Student teachers: $studentTeachers")

        val months = extractMonthsFromSchedule(page)
        if (months.isEmpty()) {
            L.e(TAG, "No months found in schedule")
            return exams
        }

        L.d(TAG, "Extracted months: $months")

        val dayEntries = parseScheduleDataCorrected(page)
        L.d(TAG, "Parsed ${dayEntries.size} day entries")

        for (dayEntry in dayEntries) {
            if (dayEntry.monthIndex >= months.size) {
                L.w(TAG, "Day entry month index ${dayEntry.monthIndex} >= ${months.size}, skipping")
                continue
            }

            val monthInfo = months[dayEntry.monthIndex]
            val examDate = createExamDate(dayEntry.day, monthInfo)

            if (examDate == null) {
                L.w(TAG, "Could not create date for day ${dayEntry.day} in ${monthInfo.first} ${monthInfo.second}")
                continue
            }

            // check if day isnt oob
            if (!isValidDayForMonth(dayEntry.day, monthInfo)) {
                L.d(TAG, "Day ${dayEntry.day} is not valid for ${monthInfo.first} ${monthInfo.second}, skipping")
                continue
            }

            L.d(TAG, "Processing day entry: ${dayEntry.day} ${dayEntry.weekday} '${dayEntry.content}' for ${monthInfo.first}")

            // create exam entry for date
            val dayExams = mutableListOf<ExamEntry>()
            var isSpecialDay = false
            var specialNote = ""

            // check special days
            if (findSpecialDays(dayEntry.content)) {
                isSpecialDay = true
                specialNote = dayEntry.content
            } else {
                val matchingExams = findMatchingExams(dayEntry.content, studentSubjects, studentTeachers)

                for (examInfo in matchingExams) {
                    L.d(TAG, "Found exam: ${examInfo.first} on $examDate (${examInfo.second})")
                    val exam = ExamEntry(
                        subject = examInfo.first,
                        date = examDate,
                        note = examInfo.second,
                        isFromSchedule = true,
                        examNumber = extractExamNumber(dayEntry.content)
                    )
                    if (exam.isOverdue()) {
                        exam.isCompleted = true
                    }

                    exams.add(exam)
                    dayExams.add(exam)
                }
            }

            val monthNumber = getMonthNumber(monthInfo.first)
            val year = monthInfo.second

            val calendarInfo = CalendarDataManager.CalendarDayInfo(
                date = examDate,
                dayOfWeek = dayEntry.weekday,
                month = monthNumber,
                year = year,
                content = dayEntry.content,
                exams = dayExams,
                isSpecialDay = isSpecialDay,
                specialNote = specialNote
            )
            calendarManager.addCalendarDay(calendarInfo)
        }

        calendarManager.saveCalendarData()

        return exams
    }

    private fun parseScheduleDataCorrected(page: String): List<DayEntry> {
        val dayEntries = mutableListOf<DayEntry>()

        val lines = page.split("\n")
        val dataLines = mutableListOf<String>()
        var inDataSection = false

        for (line in lines) {
            val trimmed = line.trim()

            if (trimmed.isEmpty()) continue

            // stop at version(2. last), halbjahr(2. last), months line(last)
            if (trimmed.contains("Version") ||
                trimmed.contains("Halbjahr") ||
                isMonthsLine(trimmed)) {
                if (inDataSection) break
                continue
            }

            // start collecting when we see day numbers
            if (!inDataSection && trimmed.matches(Regex(".*\\b\\d+[A-Za-z]*\\s.*"))) {
                inDataSection = true
            }

            if (inDataSection) {
                dataLines.add(trimmed)
            }
        }

        L.d(TAG, "Data section has ${dataLines.size} lines")

        // parse preprocessed line into entries
        for (line in dataLines) {
            val dayNumber = extractFirstDayNumber(line)
            if (dayNumber != null) {
                val dayEntriesForLine = parseLineToSixColumns(line, dayNumber)
                dayEntries.addAll(dayEntriesForLine)
            }
        }

        return dayEntries
    }

    private fun preprocessLineForConcatenatedNumbers(line: String): String {
        if (line.isEmpty()) return line

        var result = line

        // Pattern 1: number followed by uppercase letter ("1Do" -> "1 Do")
        result = result.replace(Regex("(\\d)([A-Z])")) { matchResult ->
            "${matchResult.groupValues[1]} ${matchResult.groupValues[2]}"
        }

        // Pattern 2: number followed by lowercase letter that should be uppercase (like "1mo" -> "1 Mo")
        result = result.replace(Regex("(\\d)([a-z]{2,3})\\b")) { matchResult ->
            val dayNumber = matchResult.groupValues[1]
            val weekday = matchResult.groupValues[2].replaceFirstChar { it.uppercase() }
            "$dayNumber $weekday"
        }

        // Clean up multiple spaces
        result = result.replace(Regex("\\s+"), " ").trim()

        if (result != line) {
            L.d(TAG, "Preprocessed concatenated numbers:")
            L.d(TAG, "Original: '$line'")
            L.d(TAG, "Processed: '$result'")
        }

        return result
    }

    private fun preprocessScheduleText(rawText: String): String {
        L.d(TAG, "=== Starting preprocessing ===")
        L.d(TAG, "Raw input text:\n$rawText")

        // fix concatenated day numbers with weekdays (1Mo)
        val step1Result = fixConcatenatedDayNumbers(rawText)
        L.d(TAG, "=== After Step 1: Fixed concatenated day numbers ===")
        L.d(TAG, step1Result)

        // fix multi line entries while preserving correct line structure
        val step2Result = fixMultiLineDayEntries(step1Result)
        L.d(TAG, "=== After Step 2: Fixed multi-line day entries ===")
        L.d(TAG, step2Result)

        // add missing day placeholders for months with fewer days
        val step3Result = addMissingDayPlaceholders(step2Result)
        L.d(TAG, "=== After Step 3: Added missing day placeholders ===")
        L.d(TAG, step3Result)

        L.d(TAG, "=== Preprocessing complete ===")
        return step3Result
    }

    private fun fixConcatenatedDayNumbers(text: String): String {
        var result = text

        // Pattern 1: number immediately followed by uppercase letter (like "1Do" -> "1 Do")
        result = result.replace(Regex("(\\d)([A-Z])")) { matchResult ->
            "${matchResult.groupValues[1]} ${matchResult.groupValues[2]}"
        }

        // Pattern 2: number followed by lowercase letter that should be uppercase (like "1mo" -> "1 Mo")
        result = result.replace(Regex("(\\d)([a-z]{2,3})\\b")) { matchResult ->
            val dayNumber = matchResult.groupValues[1]
            val weekday = matchResult.groupValues[2].replaceFirstChar { it.uppercase() }
            "$dayNumber $weekday"
        }

        // clean up multiple spaces within lines but preserve line breaks
        result = result.split("\n").joinToString("\n") { line ->
            line.replace(Regex("\\s+"), " ").trim()
        }

        return result
    }

    private fun fixMultiLineDayEntries(text: String): String {
        val lines = text.split("\n")
        val result = mutableListOf<String>()
        setOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

        var i = 0
        while (i < lines.size) {
            val currentLine = lines[i].trim()

            if (currentLine.isEmpty()) {
                result.add("")
                i++
                continue
            }

            // skip version, halbjahr, months lines
            if (currentLine.contains("Version") ||
                currentLine.contains("Halbjahr") ||
                isMonthsLine(currentLine)) {
                result.add(currentLine)
                i++
                continue
            }

            // check if this is a day entry line (starts with day number + weekday)
            val dayNumber = extractFirstDayNumber(currentLine)
            if (dayNumber != null && startsWithDayPattern(currentLine)) {
                var reconstructedLine = currentLine
                var j = i + 1

                while (j < lines.size) {
                    val nextLine = lines[j].trim()

                    if (nextLine.isEmpty()) {
                        break
                    }

                    if (nextLine.contains("Version") ||
                        nextLine.contains("Halbjahr") ||
                        isMonthsLine(nextLine)) {
                        break
                    }

                    // stop if we hit a line that starts with another day number (cur 1 but we read 2)
                    val nextDayNumber = extractFirstDayNumber(nextLine)
                    if (nextDayNumber != null && nextDayNumber != dayNumber && startsWithDayPattern(nextLine)) {
                        break
                    }

                    // stop if we hit a line that starts with a higher day number (even without weekday)
                    if (nextDayNumber != null && nextDayNumber > dayNumber) {
                        break
                    }

                    L.d(TAG, "Merging continuation line: '$nextLine' into day $dayNumber")

                    val separator = determineSeparator(reconstructedLine, nextLine)
                    reconstructedLine += separator + nextLine
                    j++
                }

                result.add(reconstructedLine)
                i = j // skip processed continuation lines
            } else {
                // not a day entry line add vanilla
                result.add(currentLine)
                i++
            }
        }

        return result.joinToString("\n")
    }

    private fun addMissingDayPlaceholders(text: String): String {
        val lines = text.split("\n").toMutableList()

        // find the months line to get months and year
        val monthsInfo = extractMonthsInfoFromText(text)
        if (monthsInfo.isEmpty()) {
            L.d(TAG, "No months info found, skipping placeholder addition")
            return text
        }

        L.d(TAG, "Found months info: $monthsInfo")

        // find data section (lines with day entries)
        val dataStartIndex = lines.indexOfFirst { line ->
            extractFirstDayNumber(line.trim()) != null && startsWithDayPattern(line.trim())
        }

        if (dataStartIndex == -1) {
            L.d(TAG, "No data section found, skipping placeholder addition")
            return text
        }

        // process each line that contains day entries
        for (i in dataStartIndex until lines.size) {
            val line = lines[i].trim()

            // skip lines without data
            if (line.isEmpty() ||
                line.contains("Version") ||
                line.contains("Klausurplan") ||
                isMonthsLine(line)) {
                break
            }

            val dayNumber = extractFirstDayNumber(line)
            if (dayNumber != null && dayNumber >= 29) {  // Only process days 29-31
                val updatedLine = addPlaceholdersForDay(line, dayNumber, monthsInfo)
                lines[i] = updatedLine
                L.d(TAG, "Updated day $dayNumber line from: '$line' to: '$updatedLine'")
            }
        }

        return lines.joinToString("\n")
    }

    private fun extractMonthsInfoFromText(text: String): List<Pair<String, Int>> {
        val months = mutableListOf<Pair<String, Int>>()
        val lines = text.split("\n")

        // find line with months (mostly last)
        for (line in lines.reversed()) {
            if (isMonthsLine(line)) {
                L.d(TAG, "Found months line: $line")

                val monthNames = listOf("Januar", "Februar", "März", "April", "Mai", "Juni",
                    "Juli", "August", "September", "Oktober", "November", "Dezember")

                val yearRegex = "(\\w+)\\s+(20\\d{2})".toRegex()
                val matches = yearRegex.findAll(line)

                for (match in matches) {
                    val monthName = match.groupValues[1]
                    val monthYear = match.groupValues[2].toIntOrNull() ?: continue

                    if (monthNames.contains(monthName)) {
                        months.add(Pair(monthName, monthYear))
                        L.d(TAG, "Added month: $monthName $monthYear")
                    }
                }
                break
            }
        }

        return months
    }

    private fun addPlaceholdersForDay(line: String, dayNumber: Int, monthsInfo: List<Pair<String, Int>>): String {
        if (monthsInfo.isEmpty()) return line

        L.d(TAG, "Processing day $dayNumber line: '$line'")

        // parse original line to extract existing day entries
        val originalTokens = line.split(Regex("\\s+"))
        val result = mutableListOf<String>()

        var originalIndex = 0 // index of vanilla tokens

        // go through each month and either add placeholder or copy from original
        for (monthIndex in monthsInfo.indices) {
            val monthInfo = monthsInfo[monthIndex]
            val daysInMonth = getDaysInMonth(monthInfo.first, monthInfo.second)

            if (dayNumber > daysInMonth) {
                result.add(dayNumber.toString())
                result.add("XX")
                L.d(TAG, "Added placeholder '$dayNumber XX' for ${monthInfo.first} (max $daysInMonth days)")
            } else {
                if (originalIndex < originalTokens.size && originalTokens[originalIndex] == dayNumber.toString()) {
                    result.add(originalTokens[originalIndex])
                    originalIndex++

                    if (originalIndex < originalTokens.size && isWeekday(originalTokens[originalIndex])) {
                        result.add(originalTokens[originalIndex])
                        originalIndex++
                    }

                    while (originalIndex < originalTokens.size && originalTokens[originalIndex] != dayNumber.toString()) {
                        result.add(originalTokens[originalIndex])
                        originalIndex++
                    }
                }
            }
        }

        val finalResult = result.joinToString(" ")
        L.d(TAG, "Transformed '$line' → '$finalResult'")
        return finalResult
    }

    private fun getDaysInMonth(monthName: String, year: Int): Int {
        return when (monthName.lowercase()) {
            "januar" -> 31
            "februar" -> if (isLeapYear(year)) 29 else 28
            "märz" -> 31
            "april" -> 30
            "mai" -> 31
            "juni" -> 30
            "juli" -> 31
            "august" -> 31
            "september" -> 30
            "oktober" -> 31
            "november" -> 30
            "dezember" -> 31
            else -> 31
        }
    }

    private fun startsWithDayPattern(line: String): Boolean {
        // check if line starts with: number whitespace weekday
        val pattern = "^\\d+\\s+(?:Mo|Di|Mi|Do|Fr|Sa|So)\\b".toRegex()
        val matches = pattern.containsMatchIn(line)
        L.d(TAG, "Checking day pattern for '$line': $matches")
        return matches
    }

    private fun determineSeparator(currentLine: String, nextLine: String): String {
        val currentTrimmed = currentLine.trimEnd()
        val nextTrimmed = nextLine.trimStart()

        // if current line ends with hyphen probably split word
        if (currentTrimmed.endsWith("-")) {
            return "" // No separator, just join directly
        }

        // if next line starts with lowercase letter probably continuation
        if (nextTrimmed.firstOrNull()?.isLowerCase() == true) {
            return "-"
        }

        // if current line ends with letter and next starts with letter -> add space
        val currentEndsWithLetter = currentTrimmed.lastOrNull()?.isLetter() == true
        val nextStartsWithLetter = nextTrimmed.firstOrNull()?.isLetter() == true

        if (currentEndsWithLetter && nextStartsWithLetter) {
            return " "
        }

        // default: add space if neither line has trailing nor leading space
        return if (!currentLine.endsWith(" ") && !nextLine.startsWith(" ")) " " else ""
    }

    private fun parseLineToSixColumns(line: String, dayNumber: Int): List<DayEntry> {
        val entries = mutableListOf<DayEntry>()

        val processedLine = preprocessLineForConcatenatedNumbers(line)

        L.d(TAG, "Parsing line for day $dayNumber: '$processedLine'")

        val dayPattern = "(?<!\\[)\\b$dayNumber\\b(?!])"
        val dayMatches = dayPattern.toRegex().findAll(processedLine).toList()

        L.d(TAG, "Found ${dayMatches.size} occurrences of day $dayNumber")

        dayMatches.forEachIndexed { monthIndex, match ->
            if (monthIndex >= 6) return@forEachIndexed // only process first 6 months

            val startPos = match.range.first

            // find column end (start of next day number or end of line)
            val nextMatch = if (monthIndex + 1 < dayMatches.size) dayMatches[monthIndex + 1] else null
            val endPos = nextMatch?.range?.first ?: processedLine.length

            val columnText = processedLine.substring(startPos, endPos).trim()

            L.d(TAG, "Month $monthIndex column text: '$columnText'")

            val (weekday, content) = parseColumnContent(columnText, dayNumber)

            if (content.isNotEmpty() || weekday.isNotEmpty()) {
                val entry = DayEntry(dayNumber, weekday, content, monthIndex)
                entries.add(entry)

                val monthName = getMonthName(monthIndex)
                L.d(TAG, "✓ Created entry: Day $dayNumber, Month $monthIndex ($monthName), Weekday '$weekday', Content '$content'")

                // debug for special days
                val specialKeywords = listOf("Feiertag", "Ferien", "Fortbildung", "Projektwoche", "Päd. Tag", "Bew. Ferientag")
                if (specialKeywords.any { content.contains(it, ignoreCase = true) }) {
                    L.d(TAG, "🚨 SPECIAL DAY DETECTED: '$content' assigned to $monthName (month index $monthIndex)")
                }
            }
        }

        return entries
    }

    private fun getMonthName(monthIndex: Int): String { // debug
        val months = listOf("Feb", "Mar", "Apr", "Mai", "Jun", "Jul")
        return if (monthIndex < months.size) months[monthIndex] else "Unknown"
    }

    private fun parseColumnContent(columnText: String, expectedDay: Int): Pair<String, String> {
        if (columnText.isEmpty()) return Pair("", "")

        L.d(TAG, "Parsing column: '$columnText' for expected day: $expectedDay")

        // pattern: "day_number weekday content" or just "day_number weekday"
        val pattern = "^$expectedDay\\s+([A-Za-z]{2,3})\\s*(.*)$".toRegex()
        val match = pattern.find(columnText)

        if (match != null) {
            val weekday = match.groupValues[1]
            val content = match.groupValues[2].trim()

            if (isWeekday(weekday)) {
                L.d(TAG, "Parsed -> weekday: '$weekday', content: '$content'")
                return Pair(weekday, content)
            }
        }

        val fallbackPattern = "^$expectedDay\\s+(.*)$".toRegex()
        val fallbackMatch = fallbackPattern.find(columnText)

        if (fallbackMatch != null) {
            val remainder = fallbackMatch.groupValues[1].trim()
            val tokens = remainder.split(Regex("\\s+"))

            if (tokens.isNotEmpty() && isWeekday(tokens[0])) {
                val weekday = tokens[0]
                val content = tokens.drop(1).joinToString(" ")
                L.d(TAG, "Fallback parsed -> weekday: '$weekday', content: '$content'")
                return Pair(weekday, content)
            } else {
                // no valid weekday found -> treat everything as content
                L.d(TAG, "No weekday found -> weekday: '', content: '$remainder'")
                return Pair("", remainder)
            }
        }

        L.w(TAG, "Could not parse column: '$columnText'")
        return Pair("", columnText.trim())
    }

    private fun extractFirstDayNumber(line: String): Int? {
        val dayRegex = "^\\s*(\\d{1,2})\\b".toRegex()  // match day number at start of line
        val match = dayRegex.find(line.trim())
        val dayNumber = match?.groupValues?.get(1)?.toIntOrNull()
        return if (dayNumber != null && dayNumber in 1..31) dayNumber else null
    }

    private fun getStudentSubjects(): List<String> {
        val subjects = sharedPreferences.getString("student_subjects", "")?.split(",") ?: emptyList()
        return subjects.filter { it.isNotBlank() }
    }

    private fun getStudentTeachers(): List<String> {
        val teachers = sharedPreferences.getString("student_teachers", "")?.split(",") ?: emptyList()
        return teachers.filter { it.isNotBlank() }
    }

    private fun extractMonthsFromSchedule(page: String): List<Pair<String, Int>> {
        val months = mutableListOf<Pair<String, Int>>()
        val lines = page.split("\n")

        // find the line with months (should be last line)
        for (line in lines.reversed()) {
            if (isMonthsLine(line)) {
                L.d(TAG, "Found months line: $line")

                val monthNames = listOf("Januar", "Februar", "März", "April", "Mai", "Juni",
                    "Juli", "August", "September", "Oktober", "November", "Dezember")

                val yearRegex = "(\\w+)\\s+(20\\d{2})".toRegex() // extract month year pairs
                val matches = yearRegex.findAll(line)

                for (match in matches) {
                    val monthName = match.groupValues[1]
                    val monthYear = match.groupValues[2].toIntOrNull() ?: continue

                    if (monthNames.contains(monthName)) {
                        months.add(Pair(monthName, monthYear))
                        L.d(TAG, "Added month: $monthName $monthYear")
                    }
                }
                break
            }
        }

        return months
    }

    private fun isWeekday(token: String): Boolean {
        val weekdays = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
        return weekdays.contains(token)
    }

    private fun isValidDayForMonth(day: Int, monthInfo: Pair<String, Int>): Boolean {
        val daysInMonth = when (monthInfo.first) {
            "Januar" -> 31
            "Februar" -> if (isLeapYear(monthInfo.second)) 29 else 28
            "März" -> 31
            "April" -> 30
            "Mai" -> 31
            "Juni" -> 30
            "Juli" -> 31
            "August" -> 31
            "September" -> 30
            "Oktober" -> 31
            "November" -> 30
            "Dezember" -> 31
            else -> 31
        }
        return day <= daysInMonth
    }

    private fun isLeapYear(year: Int): Boolean {
        return (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
    }

    private fun findMatchingExams(
        content: String,
        studentSubjects: List<String>,
        studentTeachers: List<String>
    ): List<Pair<String, String>> {
        val matchingExams = mutableListOf<Pair<String, String>>()

        if (content.isBlank()) return matchingExams

        L.d(TAG, "Analyzing content: '$content'")

        // skip non-exam entries
        if (findSpecialDays(content)) {
            L.d(TAG, "Skipping non-exam content")
            return matchingExams
        }

        // parse exam subjects from content (this now handles exam number cleaning)
        val examSubjects = parseExamSubjects(content)
        L.d(TAG, "Found exam subjects: $examSubjects")

        for (examSubject in examSubjects) {
            val matchingSubject = findMatchingUserSubject(examSubject, studentSubjects, studentTeachers)
            if (matchingSubject != null) {
                val note = buildExamNote()
                matchingExams.add(Pair(matchingSubject, note))
                L.d(TAG, "Matched exam: $matchingSubject with note: '$note'")
            }
        }

        return matchingExams
    }

    private fun findSpecialDays(content: String): Boolean { // special days keywords
        val knownSpecialDays = listOf(
            "feier", "ferien", "bildung", "woche", "tag",
            "mainstudy", "abi", "prüfung", "std",
            "konferenz", "fahrt", "austausch",
            "praktikum", "veranstaltung", "ausgabe",
        )

        val lowerContent = content.lowercase()

        for (day in knownSpecialDays) {
            if (lowerContent.contains(day)) {
                return true
            }
        }
        return false
    }

    private fun parseExamSubjects(content: String): List<String> {
        val subjects = mutableListOf<String>()
        val cleanContent = content.replace(Regex("\\s+"), " ").trim()

        L.d(TAG, "Parsing exam subjects from: '$cleanContent'")

        // remove [1] and [2]
        val contentWithoutExamNumbers = cleanExamNumber(cleanContent)

        // check for mixed format like "Ch GK/LK, Ph KK" or "Ch GK, Ma LK, Ph KK"
        if (contentWithoutExamNumbers.contains(",") &&
            (contentWithoutExamNumbers.contains("GK") ||
                    contentWithoutExamNumbers.contains("LK") ||
                    contentWithoutExamNumbers.contains("KK"))) {

            L.d(TAG, "Detected mixed course format")
            return parseMixedCourseFormat(contentWithoutExamNumbers)
        }

        // handle simple GK/LK format (like "Ch, Ph GK/LK" or "Ma GK/LK")
        val gkLkRegex = "([A-Za-z,\\s-]+)\\s+GK/LK".toRegex()
        val gkLkMatch = gkLkRegex.find(contentWithoutExamNumbers)

        if (gkLkMatch != null) {
            val subjectsPart = gkLkMatch.groupValues[1].trim()
            val subjectList = subjectsPart.split(",").map { it.trim() }

            for (subject in subjectList) {
                if (subject.isNotEmpty()) {
                    subjects.add("$subject GK/LK")
                }
            }
            L.d(TAG, "Found GK/LK subjects: $subjects")
            return subjects
        }

        // split by commas to handle lists like "IT, KL, MT" or "Sp-1-PESC, Re-ev, Re-ka"
        val commaParts = contentWithoutExamNumbers.split(",").map { it.trim() }

        for (part in commaParts) {
            if (part.isEmpty()) continue

            // clean the part by removing weekdays and day numbers
            val cleanPart = part.split(Regex("\\s+")).filter { token ->
                !isWeekday(token) && !token.matches(Regex("\\d+"))
            }.joinToString(" ").trim()

            if (cleanPart.isNotEmpty() && isValidSubjectToken(cleanPart)) {
                subjects.add(cleanPart)
                L.d(TAG, "Added subject: '$cleanPart'")
            }
        }

        // without comma separation -> treat content as single subject
        if (subjects.isEmpty()) {
            val cleanPart = contentWithoutExamNumbers.split(Regex("\\s+")).filter { token ->
                !isWeekday(token) && !token.matches(Regex("\\d+"))
            }.joinToString(" ").trim()

            if (cleanPart.isNotEmpty() && isValidSubjectToken(cleanPart)) {
                subjects.add(cleanPart)
                L.d(TAG, "Added single subject: '$cleanPart'")
            }
        }

        return subjects
    }

    private fun parseMixedCourseFormat(content: String): List<String> {
        val subjects = mutableListOf<String>()

        L.d(TAG, "Parsing mixed course format: '$content'")

        // split by commas to get individual parts
        val parts = content.split(",").map { it.trim() }

        for (part in parts) {
            if (part.isEmpty()) continue

            // clean the part by removing weekdays and day numbers
            val cleanPart = part.split(Regex("\\s+")).filter { token ->
                !isWeekday(token) && !token.matches(Regex("\\d+"))
            }.joinToString(" ").trim()

            if (cleanPart.isEmpty()) continue

            L.d(TAG, "Processing part: '$cleanPart'")

            when {
                // pattern: "Ch GK/LK"
                cleanPart.matches(Regex("^[A-Za-z-]+\\s+GK/LK$")) -> {
                    val subject = cleanPart.replace("GK/LK", "").trim()
                    subjects.add("$subject GK/LK")
                    L.d(TAG, "Found GK/LK format: '$subject GK/LK'")
                }

                // pattern: "Ph KK" or "Ma LK" or "Ch GK"
                cleanPart.matches(Regex("^[A-Za-z-]+\\s+(KK|LK|GK)$")) -> {
                    val tokens = cleanPart.split(Regex("\\s+"))
                    if (tokens.size >= 2) {
                        val subject = tokens.dropLast(1).joinToString("-") // Handle hyphenated subjects
                        val courseType = tokens.last()
                        subjects.add("$subject $courseType")
                        L.d(TAG, "Found $courseType format: '$subject $courseType'")
                    }
                }

                // pattern: "Ph GK/KK" (both GK and KK)
                cleanPart.matches(Regex("^[A-Za-z-]+\\s+GK/KK$")) -> {
                    val subject = cleanPart.replace("GK/KK", "").trim()
                    subjects.add("$subject GK/KK")
                    L.d(TAG, "Found GK/KK format: '$subject GK/KK'")
                }

                // pattern: "Ph LK/KK" (both LK and KK)
                cleanPart.matches(Regex("^[A-Za-z-]+\\s+LK/KK$")) -> {
                    val subject = cleanPart.replace("LK/KK", "").trim()
                    subjects.add("$subject LK/KK")
                    L.d(TAG, "Found LK/KK format: '$subject LK/KK'")
                }

                // pattern: Complex hyphenated subjects like "Sp-1 KK" or "Et-STUD GK"
                cleanPart.matches(Regex("^[A-Za-z]+-[A-Za-z0-9]+\\s+(KK|LK|GK|GK/LK)$")) -> {
                    val lastSpaceIndex = cleanPart.lastIndexOf(' ')
                    if (lastSpaceIndex > 0) {
                        val subject = cleanPart.substring(0, lastSpaceIndex).trim()
                        val courseType = cleanPart.substring(lastSpaceIndex + 1).trim()
                        subjects.add("$subject $courseType")
                        L.d(TAG, "Found complex hyphenated format: '$subject $courseType'")
                    }
                }

                // fallback: treat as regular subject if it passes validation
                else -> {
                    if (isValidSubjectToken(cleanPart)) {
                        subjects.add(cleanPart)
                        L.d(TAG, "Added as regular subject: '$cleanPart'")
                    }
                }
            }
        }

        return subjects
    }

    private fun cleanExamNumber(subject: String): String {
        // remove [1] and [2] and optionally previous space ("Ma-2 [1]" -> "Ma-2" and "Ma-2[1]" -> "Ma-2")
        val cleaned = subject.replace(Regex("\\s*\\[\\d+]$"), "").trim()
        L.d(TAG, "Cleaned exam number: '$subject' -> '$cleaned'")
        return cleaned
    }

    private fun isValidSubjectToken(token: String): Boolean {
        // valid subject tokens: 2 - 4 letters, optionally followed by hyphen and more characters (for "Sp-1-PESC" or "Ch-2-LK")
        return token.matches(Regex("[A-Za-z]{2,4}(-[A-Za-z0-9]+)*"))
    }

    private fun findMatchingUserSubject(
        examSubject: String,
        studentSubjects: List<String>,
        studentTeachers: List<String>
    ): String? {

        L.d(TAG, "Looking for match: '$examSubject'")

        // handle combined course types
        if (examSubject.contains("GK/LK")) {
            val baseSubject = examSubject.replace(" GK/LK", "").trim()
            return findBaseSubjectMatch(baseSubject, studentSubjects)
        }

        if (examSubject.contains("GK/KK")) {
            val baseSubject = examSubject.replace(" GK/KK", "").trim()
            return findBaseSubjectMatch(baseSubject, studentSubjects)
        }

        if (examSubject.contains("LK/KK")) {
            val baseSubject = examSubject.replace(" LK/KK", "").trim()
            return findBaseSubjectMatch(baseSubject, studentSubjects)
        }

        // handle single course types
        if (examSubject.contains(" GK") || examSubject.contains(" LK") || examSubject.contains(" KK")) {
            val baseSubject = examSubject.replace(Regex("\\s+(GK|LK|KK)$"), "").trim()
            return findBaseSubjectMatch(baseSubject, studentSubjects)
        }

        // handle hyphen subjects
        if (examSubject.contains("-")) {
            val parts = examSubject.split("-")

            if (parts.size == 2) {
                val baseSubject = parts[0]
                val suffix = parts[1]

                if (suffix.length >= 3) {
                    // teacher specific (3+ characters after hyphen)
                    return findTeacherSpecificMatch(baseSubject, suffix, studentSubjects, studentTeachers)
                } else {
                    // exact match
                    L.d(TAG, "Course-specific subject '$examSubject' requires exact match")
                    return studentSubjects.find { it.equals(examSubject, ignoreCase = true) }
                }
            } else if (parts.size == 3) {
                // format: "Sp-1-PESC" or "Ch-2-LK"
                val baseSubject = parts[0]
                val courseNumber = parts[1]
                val suffix = parts[2]

                if (suffix.length >= 3) {
                    // teacher specific with course number: "Sp-1-PESC"
                    val courseSpecific = "$baseSubject-$courseNumber"
                    L.d(TAG, "Teacher-specific with course number, matching against: '$courseSpecific'")
                    return studentSubjects.find { it.equals(courseSpecific, ignoreCase = true) }
                } else {
                    // special course with course number: "Ch-2-LK"
                    val courseSpecific = "$baseSubject-$courseNumber"
                    return studentSubjects.find { it.equals(courseSpecific, ignoreCase = true) }
                        ?: findBaseSubjectMatch(baseSubject, studentSubjects)
                }
            }
        }

        // handle base subjects (without hyphen)
        return findBaseSubjectMatch(examSubject, studentSubjects)
    }

    private fun findBaseSubjectMatch(examSubject: String, studentSubjects: List<String>): String? {
        for (studentSubject in studentSubjects) {
            val studentBase = studentSubject.split("-")[0]

            // exact match
            if (examSubject.equals(studentBase, ignoreCase = true)) {
                L.d(TAG, "Base match: '$examSubject' -> '$studentSubject'")
                return studentSubject
            }

            // special cases (ethik/powi)
            if ((examSubject.equals("Et", ignoreCase = true) || examSubject.equals("Eth", ignoreCase = true)) &&
                (studentBase.equals("Et", ignoreCase = true) || studentBase.equals("Eth", ignoreCase = true))) {
                L.d(TAG, "Et/Eth equivalence match: '$examSubject' -> '$studentSubject'")
                return studentSubject
            }

            if ((examSubject.equals("Powi", ignoreCase = true) && studentBase.equals("PoWi", ignoreCase = true)) ||
                (examSubject.equals("PoWi", ignoreCase = true) && studentBase.equals("Powi", ignoreCase = true))) {
                L.d(TAG, "Powi/PoWi equivalence match: '$examSubject' -> '$studentSubject'")
                return studentSubject
            }
        }

        L.d(TAG, "No base match found for: '$examSubject'")
        return null
    }

    private fun findTeacherSpecificMatch(
        baseSubject: String,
        teacherCode: String,
        studentSubjects: List<String>,
        studentTeachers: List<String>
    ): String? {
        for (i in studentSubjects.indices) {
            val studentSubject = studentSubjects[i]
            val studentTeacher = if (i < studentTeachers.size) studentTeachers[i] else ""

            // check if base subject matches
            val studentBase = studentSubject.split("-")[0]
            val baseMatches = baseSubject.equals(studentBase, ignoreCase = true) ||
                ((baseSubject.equals("Et", ignoreCase = true) || baseSubject.equals("Eth", ignoreCase = true)) &&
                (studentBase.equals("Et", ignoreCase = true) || studentBase.equals("Eth", ignoreCase = true)))

            // check teacher match
            val teacherMatches = studentTeacher.equals(teacherCode, ignoreCase = true)

            if (baseMatches && teacherMatches) {
                L.d(TAG, "Teacher-specific match: '$baseSubject-$teacherCode' -> '$studentSubject' (teacher: $studentTeacher)")
                return studentSubject
            }
        }

        L.d(TAG, "No teacher-specific match found for: '$baseSubject-$teacherCode'")
        return null
    }

    private fun buildExamNote(): String {
        val notes = mutableListOf<String>()
        // just build nothing else
        return notes.joinToString(", ")
    }

    private fun extractExamNumber(content: String): Int? {
        val regex = "\\[([0-9]+)]".toRegex()
        val match = regex.find(content)
        val examNumber = match?.groupValues?.get(1)?.toIntOrNull()

        if (examNumber != null) {
            L.d(TAG, "Extracted exam number: $examNumber from content: '$content'")
        }

        return examNumber
    }

    private fun createExamDate(day: Int, month: Pair<String, Int>): Date? {
        try {
            val monthNumber = getMonthNumber(month.first)
            if (monthNumber == -1) return null

            val calendar = Calendar.getInstance()
            calendar.set(Calendar.YEAR, month.second)
            calendar.set(Calendar.MONTH, monthNumber - 1)
            calendar.set(Calendar.DAY_OF_MONTH, day)
            calendar.set(Calendar.HOUR_OF_DAY, 8) // defaulting exam time
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)

            return calendar.time
        } catch (e: Exception) {
            L.e(TAG, "Error creating exam date", e)
            return null
        }
    }

    private fun getMonthNumber(monthName: String): Int {
        return when (monthName.lowercase()) {
            "januar" -> 1
            "februar" -> 2
            "märz" -> 3
            "april" -> 4
            "mai" -> 5
            "juni" -> 6
            "juli" -> 7
            "august" -> 8
            "september" -> 9
            "oktober" -> 10
            "november" -> 11
            "dezember" -> 12
            else -> -1
        }
    }

    private fun saveScheduleInfo(scheduleInfo: ExamScheduleInfo) {
        val json = Gson().toJson(scheduleInfo)
        sharedPreferences.edit {
            putString(PREFS_EXAM_SCHEDULE_INFO, json)
        }
    }

    private fun saveExportToFile(content: String) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.GERMAN).format(Date())
        val filename = "klausuren_export_${timestamp}.hksk"

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, filename)
        }

        sharedPreferences.edit {putString("temp_export_content", content)}

        try {
            exportLauncher.launch(intent)
        } catch (_: Exception) {
            Toast.makeText(requireContext(), getString(R.string.set_act_error_while_opening_file_dialog), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(requireContext(), getString(R.string.grades_export_error), Toast.LENGTH_LONG).show()
        }
    }

    private fun copyToClipboard(content: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Klausuren Export", content)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), getString(R.string.exam_export_copied), Toast.LENGTH_SHORT).show()
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

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.exam_import_title))
            .setAdapter(adapter) { _, which ->
                when (which) {
                    0 -> importFromFilePicker()
                    1 -> importFromClipboard()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()

        val buttonColor = requireContext().getThemeColor(R.attr.dialogSectionButtonColor)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)
    }

    private fun importFromFilePicker() {
        Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        sharedPreferences.edit { remove("temp_add_to_existing") } // didnt want to change the name
    }

    private fun importFromClipboard() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip

        if (clip != null && clip.itemCount > 0) {
            val content = clip.getItemAt(0).text.toString()
            importExamData(content)
        } else {
            Toast.makeText(requireContext(), getString(R.string.set_act_import_clipboard_empty), Toast.LENGTH_SHORT).show()
        }
    }

    private fun importExamData(content: String) {
        try {
            backupManager.importExamData(content)

            loadExams()

            val calendarManager = CalendarDataManager.getInstance(requireContext())
            for (exam in examList) {
                addExamToCalendarDataManager(exam)
            }
            calendarManager.saveCalendarData()

            filterExams(searchBar.text.toString())
            updateExamCount()

            val importedCount = examList.size
            Toast.makeText(requireContext(), getString(R.string.exam_import_count, importedCount), Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            L.e(TAG, "Error importing exam data", e)

            val errorMessage = when {
                e.message?.contains("Ungültiges Datenformat") == true ->
                    getString(R.string.exam_import_invalid_format)
                e.message?.contains("Importfehler") == true ->
                    e.message!!.removePrefix("Importfehler: ")
                else ->
                    getString(R.string.exam_import_error, e.message)
            }

            Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_LONG).show()
        }
    }

    private fun showAddExamDialog(editExam: ExamEntry? = null) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_exam, null)

        val editTextSubject = dialogView.findViewById<EditText>(R.id.editTextExamSubject)
        val spinnerSubject = dialogView.findViewById<Spinner>(R.id.spinnerSubject)
        val btnExamDate = dialogView.findViewById<Button>(R.id.btnExamDate)
        val editTextNote = dialogView.findViewById<EditText>(R.id.editTextExamNote)

        val layoutMarkSection = dialogView.findViewById<LinearLayout>(R.id.layoutMarkSection)
        val editTextMark = dialogView.findViewById<EditText>(R.id.editTextMark)
        val textGradeDisplay = dialogView.findViewById<TextView>(R.id.textGradeDisplay)

        var selectedDate = editExam?.date ?: Date()

        // setup mark fields
        editTextMark.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val markText = s.toString().trim()
                if (markText.isNotEmpty()) {
                    try {
                        val mark = markText.toInt()
                        if (mark in 0..15) {
                            val grade = getGradeFromMark(mark)
                            textGradeDisplay.text = grade
                            textGradeDisplay.setBackgroundColor(
                                if (mark >= 10) resources.getColor(android.R.color.holo_green_light)
                                else if (mark >= 5) resources.getColor(android.R.color.holo_orange_light)
                                else resources.getColor(android.R.color.holo_red_light)
                            )
                        } else {
                            textGradeDisplay.text = getString(R.string.exam_invalid)
                            textGradeDisplay.setBackgroundColor(resources.getColor(android.R.color.holo_red_light))
                        }
                    } catch (_: NumberFormatException) {
                        textGradeDisplay.text = ""
                        textGradeDisplay.setBackgroundColor(getThemeColor(R.attr.examAddColorSurface))
                    }
                } else {
                    textGradeDisplay.text = ""
                    textGradeDisplay.setBackgroundColor(getThemeColor(R.attr.examAddColorSurface))
                }
            }
        })

        val hasScannedDocument = sharedPreferences.getBoolean("has_scanned_document", false)

        var currentSelectedSubject: String? = null

        if (hasScannedDocument) {
            // show spinner (hide textedit)
            spinnerSubject.visibility = View.VISIBLE
            editTextSubject.visibility = View.GONE

            val subjects = getStudentSubjects()
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, subjects)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerSubject.adapter = adapter

            editExam?.let { exam ->
                val position = subjects.indexOf(exam.subject)
                if (position >= 0) {
                    spinnerSubject.setSelection(position)
                    currentSelectedSubject = exam.subject
                }
            }

            spinnerSubject.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    currentSelectedSubject = spinnerSubject.selectedItem?.toString()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            if (editExam == null && subjects.isNotEmpty()) {
                currentSelectedSubject = subjects[0]
            }
        } else {
            // default show edit text (hide spinner)
            editTextSubject.visibility = View.VISIBLE
            spinnerSubject.visibility = View.GONE

            editExam?.let {
                editTextSubject.setText(it.subject)
            }
        }

        editExam?.let { exam ->
            editTextNote.setText(exam.note)

            // show mark section only if editing and exam is overdue or already has a mark
            if (exam.isTodayOrOverdue() || exam.mark != null) {
                layoutMarkSection.visibility = View.VISIBLE
                exam.mark?.let { mark ->
                    editTextMark.setText(mark.toString())
                }
            }
        }

        // remove instant mark setting when adding new exam
        if (editExam == null) {
            layoutMarkSection.visibility = View.GONE
        }

        updateDateButton(btnExamDate, selectedDate)

        val updateMarkSectionVisibility = {
            if (editExam != null) {
                val isTodayOrOverdue = isTodayOrOverdue(selectedDate)
                layoutMarkSection.visibility = if (isTodayOrOverdue) View.VISIBLE else View.GONE

                // clear mark if exam is no longer overdue
                if (!isTodayOrOverdue) {
                    editTextMark.setText("")
                    textGradeDisplay.text = ""
                }
            }
        }

        btnExamDate.setOnClickListener {
            showCustomDatePicker(selectedDate, currentSelectedSubject) { date ->
                selectedDate = date
                updateDateButton(btnExamDate, selectedDate)
                updateMarkSectionVisibility()
            }
        }

        updateMarkSectionVisibility()

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(if (editExam != null) getString(R.string.exam_edit) else getString(R.string.exam_add))
            .setView(dialogView)
            .setPositiveButton(if (editExam != null) getString(R.string.exam_save) else getString(R.string.slide_add)) { _, _ ->
                val subject = if (hasScannedDocument) {
                    spinnerSubject.selectedItem?.toString() ?: ""
                } else {
                    editTextSubject.text.toString().trim()
                }
                val note = editTextNote.text.toString().trim()

                val mark = if (editExam != null && isTodayOrOverdue(selectedDate)) {
                    val markText = editTextMark.text.toString().trim()
                    if (markText.isNotEmpty()) {
                        try {
                            val markValue = markText.toInt()
                            if (markValue in 0..15) markValue else null
                        } catch (_: NumberFormatException) {
                            null
                        }
                    } else null
                } else null

                if (subject.isNotEmpty()) {
                    if (editExam != null) {
                        updateExam(editExam, subject, selectedDate, note, mark)
                    } else {
                        addExam(subject, selectedDate, note, null)
                    }
                } else {
                    Toast.makeText(requireContext(), getString(R.string.exam_enter_subject), Toast.LENGTH_SHORT).show()
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

    private fun isTodayOrOverdue(date: Date): Boolean {
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val examDate = Calendar.getInstance().apply {
            time = date
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        return examDate.timeInMillis <= today.timeInMillis
    }

    private fun getGradeFromMark(mark: Int): String {
        return when (mark) {
            0 -> "6"
            1 -> "5-"
            2 -> "5"
            3 -> "5+"
            4 -> "4-"
            5 -> "4"
            6 -> "4+"
            7 -> "3-"
            8 -> "3"
            9 -> "3+"
            10 -> "2-"
            11 -> "2"
            12 -> "2+"
            13 -> "1-"
            14 -> "1"
            15 -> "1+"
            else -> "?"
        }
    }

    private fun addExam(subject: String, date: Date, note: String, mark: Int? = null) {
        val exam = ExamEntry(
            subject = subject,
            date = date,
            note = note,
            mark = mark,
            isFromSchedule = false
        )

        examList.add(exam)
        sortExams()
        saveExams()

        addExamToCalendarDataManager(exam)

        filterExams(searchBar.text.toString())
        updateExamCount()

        Toast.makeText(requireContext(), getString(R.string.exam_added), Toast.LENGTH_SHORT).show()
    }

    private fun updateExam(exam: ExamEntry, subject: String, date: Date, note: String, mark: Int? = null) {
        val oldDate = exam.date

        exam.subject = subject
        exam.date = date
        exam.note = note
        exam.mark = mark

        sortExams()
        saveExams()

        removeExamFromCalendarDataManager(exam.copy(date = oldDate))
        addExamToCalendarDataManager(exam)

        filterExams(searchBar.text.toString())

        Toast.makeText(requireContext(), getString(R.string.exam_updated), Toast.LENGTH_SHORT).show()
    }

    private fun deleteExam(exam: ExamEntry) {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.exam_delete))
            .setMessage(getString(R.string.exam_delete_confirm))
            .setPositiveButton(getString(R.string.exam_yes_delete)) { _, _ ->
                examList.remove(exam)
                sortExams()
                saveExams()

                removeExamFromCalendarDataManager(exam)

                filterExams(searchBar.text.toString())
                updateExamCount()
                Toast.makeText(requireContext(), getString(R.string.exam_deleted), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()

        val buttonColor = requireContext().getThemeColor(R.attr.dialogSectionButtonColor)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)
    }

    private fun addExamToCalendarDataManager(exam: ExamEntry) {
        val calendarManager = CalendarDataManager.getInstance(requireContext())
        val existingInfo = calendarManager.getCalendarInfoForDate(exam.date)

        if (existingInfo != null) {
            val updatedExams = existingInfo.exams.toMutableList()
            if (!updatedExams.any { it.id == exam.id }) {
                updatedExams.add(exam)
                val updatedInfo = existingInfo.copy(exams = updatedExams)
                calendarManager.updateCalendarDay(updatedInfo)
                L.d(TAG, "Added exam to existing calendar day: ${exam.subject} on ${existingInfo.getDisplayDate()}")
            }
        } else {
            val cal = Calendar.getInstance().apply { time = exam.date }
            val dayOfWeek = getGermanWeekday(exam.date)

            val newInfo = CalendarDataManager.CalendarDayInfo(
                date = exam.date,
                dayOfWeek = dayOfWeek,
                month = cal.get(Calendar.MONTH) + 1,
                year = cal.get(Calendar.YEAR),
                content = exam.subject,
                exams = listOf(exam),
                isSpecialDay = false,
                specialNote = ""
            )
            calendarManager.addCalendarDay(newInfo)
            L.d(TAG, "Created new calendar day with exam: ${exam.subject} on ${newInfo.getDisplayDate()}")
        }

        calendarManager.saveCalendarData()
    }

    private fun removeExamFromCalendarDataManager(exam: ExamEntry) {
        val calendarManager = CalendarDataManager.getInstance(requireContext())
        val existingInfo = calendarManager.getCalendarInfoForDate(exam.date)

        if (existingInfo != null) {
            val updatedExams = existingInfo.exams.toMutableList()
            updatedExams.removeAll { it.id == exam.id }

            if (updatedExams.isEmpty() && !existingInfo.isSpecialDay) {
                // remove entire day if no exams left and not special day
                calendarManager.removeCalendarDay(exam.date)
                L.d(TAG, "Removed calendar day: ${existingInfo.getDisplayDate()}")
            } else {
                val updatedInfo = existingInfo.copy(exams = updatedExams)
                calendarManager.updateCalendarDay(updatedInfo)
                L.d(TAG, "Updated calendar day, removed exam: ${exam.subject}")
            }

            calendarManager.saveCalendarData()
        }
    }

    private fun filterExams(query: String) {
        filteredExamList.clear()

        if (query.isEmpty()) {
            filteredExamList.addAll(examList)
        } else {
            filteredExamList.addAll(examList.filter { exam ->
                exam.subject.contains(query, ignoreCase = true) ||
                        exam.note.contains(query, ignoreCase = true)
            })
        }

        adapter.notifyDataSetChanged()
    }

    private fun sortExams() {
        examList.sortWith { a, b ->
            // completed exams at the bottom
            if (a.isCompleted != b.isCompleted) {
                return@sortWith if (a.isCompleted) 1 else -1
            }
            // sort by date (ascending)
            a.date.compareTo(b.date)
        }
    }

    private fun saveExams() {
        val json = Gson().toJson(examList)
        sharedPreferences.edit {
            putString(PREFS_EXAM_LIST, json)
        }

        ExamManager.setExams(examList)
    }

    private fun loadExams() {
        val json = sharedPreferences.getString(PREFS_EXAM_LIST, "[]")
        val type = object : TypeToken<MutableList<ExamEntry>>() {}.type
        val loadedList: MutableList<ExamEntry> = Gson().fromJson(json, type) ?: mutableListOf()

        examList.clear()
        examList.addAll(loadedList)

        // mark overdue exams as completed
        examList.forEach { exam ->
            if (exam.isOverdue() && !exam.isCompleted) {
                exam.isCompleted = true
            }
        }

        ExamManager.setExams(examList)

        syncManualExamsToCalendarManager()

        sortExams()
        filterExams("")
    }

    private fun syncManualExamsToCalendarManager() {
        val calendarManager = CalendarDataManager.getInstance(requireContext())

        // find manually added exams (not from schedule)
        val manualExams = examList.filter { !it.isFromSchedule }

        L.d(TAG, "Syncing ${manualExams.size} manual exams to CalendarDataManager")

        for (exam in manualExams) {
            val existingInfo = calendarManager.getCalendarInfoForDate(exam.date)
            val hasThisExam = existingInfo?.exams?.any { it.id == exam.id } ?: false

            if (!hasThisExam) {
                L.d(TAG, "Adding missing manual exam to calendar: ${exam.subject}")
                addExamToCalendarDataManager(exam)
            }
        }
    }

    private fun updateExamCount() {
        val upcomingCount = examList.count { !it.isCompleted }
        val totalCount = examList.size
        tvExamCount.text = getString(R.string.exam_count, upcomingCount, totalCount)
    }

    private fun updateDateButton(button: Button, date: Date) {
        val format = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)
        button.text = format.format(date)
    }

    private fun showExportOptions() {
        val content = exportExamData()

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

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.exam_export_title))
            .setAdapter(adapter) { _, which ->
                when (which) {
                    0 -> saveExportToFile(content)
                    1 -> copyToClipboard(content)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()

        val negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        negativeButton.setTextColor(getThemeColor(R.attr.dialogSectionButtonColor))
    }

    private fun exportExamData(): String {
        return backupManager.exportExamData()
    }

    private fun getGermanWeekday(date: Date): String { // be cautious here
        val calendar = Calendar.getInstance().apply { time = date }
        return when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "Mo"
            Calendar.TUESDAY -> "Di"
            Calendar.WEDNESDAY -> "Mi"
            Calendar.THURSDAY -> "Do"
            Calendar.FRIDAY -> "Fr"
            Calendar.SATURDAY -> "Sa"
            Calendar.SUNDAY -> "So"
            else -> ""
        }
    }

    private fun getSubjectSchedule(subject: String): Map<Int, List<Int>> {
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

    private fun getWeekdayName(dayIndex: Int): String {
        return when (dayIndex) {
            0 -> getString(R.string.monday)
            1 -> getString(R.string.tuesday)
            2 -> getString(R.string.wednesday)
            3 -> getString(R.string.thursday)
            4 -> getString(R.string.friday)
            else -> getString(R.string.unknown)
        }
    }

    private fun showCustomDatePicker(
        currentDate: Date,
        selectedSubject: String?,
        onDateSelected: (Date) -> Unit
    ) {
        val calendar = Calendar.getInstance().apply { time = currentDate }

        val subjectSchedule = if (!selectedSubject.isNullOrEmpty()) {
            getSubjectSchedule(selectedSubject)
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
                onDateSelected(newCalendar.time)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )

        // if we have subject data -> show days with today marked
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

    private fun showExamDetailsDialog(exam: ExamEntry) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_exam_details, null)

        val textSubject = dialogView.findViewById<TextView>(R.id.textDetailSubject)
        val textDate = dialogView.findViewById<TextView>(R.id.textDetailDate)
        val textDetailedDate = dialogView.findViewById<TextView>(R.id.textDetailedDate)
        val textNotes = dialogView.findViewById<TextView>(R.id.textDetailNotes)
        val textMark = dialogView.findViewById<TextView>(R.id.textDetailMark)
        val btnShare = dialogView.findViewById<ImageButton>(R.id.btnShareExam)
        val btnCopy = dialogView.findViewById<ImageButton>(R.id.btnCopyExamToClipboard)

        btnShare.setOnClickListener {
            shareExam(exam)
        }

        btnCopy.setOnClickListener {
            copyExamNotesToClipboard(exam)
        }

        textSubject.text = exam.subject
        textDate.text = exam.getDetailedDateString()

        val detailedDateInfo = buildDetailedDateInfo(exam)
        textDetailedDate.text = detailedDateInfo

        val dateHintColor = requireContext().getThemeColor(R.attr.examDetailsDateHint)
        textDetailedDate.setTextColor(dateHintColor)

        if (exam.note.isNotBlank()) {
            textNotes.text = exam.note
            textNotes.visibility = View.VISIBLE
        } else {
            textNotes.text = getString(R.string.exam_details_no_notes)
            textNotes.visibility = View.VISIBLE
        }

        if (exam.mark != null) {
            val grade = exam.getGradeFromMark()
            textMark.text = getString(R.string.exam_details_grade, exam.mark, grade)
            textMark.visibility = View.VISIBLE
        } else {
            textMark.visibility = View.GONE
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.exam_details_title))
            .setView(dialogView)
            .setNeutralButton(getString(R.string.exam_details_edit)) { _, _ ->
                showAddExamDialog(exam)
            }
            .setPositiveButton(getString(R.string.exam_details_close), null)
            .show()

        val buttonColor = requireContext().getThemeColor(R.attr.dialogSectionButtonColor)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)
    }

    private fun buildDetailedDateInfo(exam: ExamEntry): String {
        val daysUntil = exam.getDaysUntilExam()

        val statusText = when {
            exam.isOverdue() -> getString(R.string.exam_date_past)
            daysUntil == 0L -> getString(R.string.exam_date_today)
            daysUntil == 1L -> getString(R.string.exam_date_tomorrow)
            daysUntil <= 7 -> {
                resources.getQuantityString(R.plurals.exam_days_remaining, daysUntil.toInt(), daysUntil.toInt())
            }
            daysUntil <= 30 -> {
                val weeks = daysUntil / 7
                val remainingDays = daysUntil % 7
                val baseText = when {
                    weeks == 1L && remainingDays == 0L -> getString(R.string.exam_one_week_remaining)
                    weeks == 1L -> resources.getQuantityString(R.plurals.exam_one_week_plus_days_remaining, remainingDays.toInt(), remainingDays.toInt())
                    remainingDays == 0L -> resources.getQuantityString(R.plurals.exam_weeks_remaining, weeks.toInt(), weeks.toInt())
                    else -> resources.getQuantityString(R.plurals.exam_weeks_plus_days_remaining, remainingDays.toInt(), weeks.toInt(), remainingDays.toInt())
                }
                "$baseText ${getString(R.string.exam_in_total_days, daysUntil.toInt())}"
            }
            daysUntil <= 365 -> {
                val months = daysUntil / 30
                val remainingDays = daysUntil % 30
                val baseText = when {
                    months == 1L && remainingDays <= 5 -> getString(R.string.exam_one_month_remaining)
                    months == 1L -> resources.getQuantityString(R.plurals.exam_one_month_plus_days_remaining, remainingDays.toInt(), remainingDays.toInt())
                    remainingDays <= 5 -> resources.getQuantityString(R.plurals.exam_months_remaining, months.toInt(), months.toInt())
                    else -> resources.getQuantityString(R.plurals.exam_months_plus_days_remaining, remainingDays.toInt(), months.toInt(), remainingDays.toInt())
                }
                "$baseText ${getString(R.string.exam_in_total_days, daysUntil.toInt())}"
            }
            else -> {
                val years = daysUntil / 365
                val remainingDays = daysUntil % 365
                val baseText = when {
                    years == 1L && remainingDays <= 10 -> getString(R.string.exam_one_year_remaining)
                    years == 1L -> {
                        val months = remainingDays / 30
                        if (months > 0) {
                            resources.getQuantityString(R.plurals.exam_one_year_plus_months_remaining, months.toInt(), months.toInt())
                        } else {
                            getString(R.string.exam_one_year_remaining)
                        }
                    }
                    remainingDays <= 10 -> resources.getQuantityString(R.plurals.exam_years_remaining, years.toInt(), years.toInt())
                    else -> {
                        val months = remainingDays / 30
                        if (months > 0) {
                            resources.getQuantityString(R.plurals.exam_years_plus_months_remaining, months.toInt(), years.toInt(), months.toInt())
                        } else {
                            resources.getQuantityString(R.plurals.exam_years_remaining, years.toInt(), years.toInt())
                        }
                    }
                }
                "$baseText ${getString(R.string.exam_in_total_days, daysUntil.toInt())}"
            }
        }
        return statusText
    }

    fun handleSharedExam() {
        arguments?.getString("shared_exam_uri")?.let { uriString ->
            handleSharedExamFromUri(uriString)
            arguments?.remove("shared_exam_uri")
        }
    }

    private fun showSharedExamDialog(sharedExam: ExamEntry, sharedBy: String?) {
        val hasCompletedSetup = sharedPreferences.getBoolean("setup_completed", false)
        val selectedKlasse = sharedPreferences.getString("selected_klasse", "")
        val selectedBildungsgang = sharedPreferences.getString("selected_bildungsgang", "")

        if (!hasCompletedSetup || selectedKlasse.isNullOrEmpty() || selectedBildungsgang.isNullOrEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.share_setup_required), Toast.LENGTH_LONG).show()
            clearSharedContentState()
            return
        }

        val existingExam = examList.find {
            it.subject == sharedExam.subject &&
                    isSameDay(it.date, sharedExam.date)
        }

        val message = if (existingExam != null) {
            if (sharedBy != null) {
                getString(R.string.share_exam_overwrite_with_sender, sharedExam.subject, sharedBy)
            } else {
                getString(R.string.share_exam_overwrite, sharedExam.subject)
            }
        } else {
            if (sharedBy != null) {
                getString(R.string.share_exam_add_with_sender, sharedExam.subject, sharedBy)
            } else {
                getString(R.string.share_exam_add, sharedExam.subject)
            }
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.share_exam_received_title))
            .setMessage(message)
            .setPositiveButton(if (existingExam != null) getString(R.string.share_overwrite) else getString(R.string.share_add)) { _, _ ->
                try {
                    if (existingExam != null) {
                        existingExam.note = sharedExam.note
                        sortExams()
                        saveExams()
                        filterExams(searchBar.text.toString())
                        Toast.makeText(requireContext(), getString(R.string.share_exam_updated), Toast.LENGTH_SHORT).show()
                    } else {
                        addExam(sharedExam.subject, sharedExam.date, sharedExam.note, sharedExam.mark)
                        Toast.makeText(requireContext(), getString(R.string.share_exam_added), Toast.LENGTH_SHORT).show()
                    }
                    clearSharedContentState()
                } catch (e: Exception) {
                    L.e(TAG, "Error adding/updating shared exam", e)
                    Toast.makeText(requireContext(), getString(R.string.share_error_generic), Toast.LENGTH_LONG).show()
                    clearSharedContentState()
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                L.d(TAG, "User cancelled shared exam import")
                clearSharedContentState()
            }
            .setOnCancelListener {
                L.d(TAG, "Shared exam dialog was cancelled")
                clearSharedContentState()
            }
            .setOnDismissListener {
                L.d(TAG, "Shared exam dialog was dismissed")
                clearSharedContentState()
            }
            .show()

        val buttonColor = requireContext().getThemeColor(R.attr.dialogSectionButtonColor)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)
    }

    private fun isSameDay(date1: Date, date2: Date): Boolean {
        val cal1 = Calendar.getInstance().apply { time = date1 }
        val cal2 = Calendar.getInstance().apply { time = date2 }

        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    private fun shareExam(exam: ExamEntry) {
        val hasCompletedSetup = sharedPreferences.getBoolean("setup_completed", false)
        val selectedKlasse = sharedPreferences.getString("selected_klasse", "")
        val selectedBildungsgang = sharedPreferences.getString("selected_bildungsgang", "")

        if (!hasCompletedSetup || selectedKlasse.isNullOrEmpty() || selectedBildungsgang.isNullOrEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.share_setup_required), Toast.LENGTH_LONG).show()
            return
        }

        val sharedBy = "$selectedKlasse ($selectedBildungsgang)"
        val shareIntent = examShareHelper.shareExam(exam, sharedBy)

        if (shareIntent != null) {
            try {
                startActivity(shareIntent)
            } catch (e: Exception) {
                L.e(TAG, "Error sharing exam", e)
                Toast.makeText(requireContext(), getString(R.string.share_error_generic), Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(requireContext(), getString(R.string.share_exam_error), Toast.LENGTH_LONG).show()
        }
    }

    private fun copyExamNotesToClipboard(exam: ExamEntry) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Exam Notes", exam.note)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), getString(R.string.exam_notes_copied), Toast.LENGTH_SHORT).show()
    }

    fun highlightAndShowExam(examId: String) {
        var exam = examList.find { it.id == examId }

        if (exam == null) {
            exam = ExamManager.findExamById(examId)
        }

        if (exam == null) {
            Toast.makeText(requireContext(), getString(R.string.exam_exam_not_found), Toast.LENGTH_SHORT).show()
            return
        }

        val position = filteredExamList.indexOf(exam)
        if (position == -1) {
            searchBar.setText("")
            filterExams("")
            val newPosition = filteredExamList.indexOf(exam)
            if (newPosition != -1) {
                highlightAndScrollToPosition(newPosition, exam)
            } else {
                Toast.makeText(requireContext(), getString(R.string.exam_exam_not_in_current_list), Toast.LENGTH_SHORT).show()
            }
        } else {
            highlightAndScrollToPosition(position, exam)
        }
    }

    private fun highlightAndScrollToPosition(position: Int, exam: ExamEntry) {
        recyclerView.scrollToPosition(position)

        recyclerView.post {
            val viewHolder = recyclerView.findViewHolderForAdapterPosition(position)
            if (viewHolder != null) {
                highlightViewHolder(viewHolder, exam)
            } else {
                recyclerView.postDelayed({
                    val vh = recyclerView.findViewHolderForAdapterPosition(position)
                    if (vh != null) {
                        highlightViewHolder(vh, exam)
                    }
                }, 50)
            }
        }
    }

    private fun highlightViewHolder(viewHolder: RecyclerView.ViewHolder, exam: ExamEntry) {
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
                    showExamDetailsDialog(exam)
                } catch (e: Exception) {
                    L.e("ExamFragment", "Error showing exam details dialog", e)
                }
            } else {
                L.d("ExamFragment", "Fragment not in valid state to show dialog - skipping")
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

        Handler(Looper.getMainLooper()).postDelayed({ // restore after 2 seconds (aint working but it is what it is)
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        L.d("ExamFragment", "onViewCreated called")
        L.d("ExamFragment", "Arguments: $arguments")

        arguments?.getString("shared_exam_uri")?.let { uriString ->
            L.d("ExamFragment", "Processing shared exam URI: $uriString")

            if (isLoading) {
                Handler(Looper.getMainLooper()).postDelayed({
                    handleSharedExamFromUri(uriString)
                }, 100)
            } else {
                Handler(Looper.getMainLooper()).postDelayed({
                    handleSharedExamFromUri(uriString)
                }, 100)
            }

            arguments?.remove("shared_exam_uri")
        }

        arguments?.getString("highlight_exam_id")?.let { examId ->
            L.d("ExamFragment", "Processing highlight_exam_id: $examId")

            if (isLoading) {
                Handler(Looper.getMainLooper()).postDelayed({
                    highlightAndShowExam(examId)
                }, 500)
            } else {
                highlightAndShowExam(examId)
            }
        }

        Handler(Looper.getMainLooper()).post {
            L.d("ExamFragment", "Clearing arguments to prevent reuse")
            arguments?.clear()
            arguments = Bundle()
        }
    }

    private fun handleSharedExamFromUri(uriString: String) {
        if (isProcessingSharedExam) {
            L.d(TAG, "Already processing shared exam, ignoring duplicate call")
            return
        }

        isProcessingSharedExam = true

        try {
            val uri = uriString.toUri()
            val sharedExam = examShareHelper.parseSharedExam(uri)

            if (sharedExam != null) {
                val examEntry = examShareHelper.convertToExamEntry(sharedExam)
                if (examEntry != null) {
                    showSharedExamDialog(examEntry, sharedExam.sharedBy)
                } else {
                    Toast.makeText(requireContext(), getString(R.string.share_exam_invalid), Toast.LENGTH_LONG).show()
                    clearSharedContentState()
                }
            } else {
                Toast.makeText(requireContext(), getString(R.string.share_exam_parse_error), Toast.LENGTH_LONG).show()
                clearSharedContentState()
            }
        } catch (e: Exception) {
            L.e(TAG, "Error handling shared exam", e)
            Toast.makeText(requireContext(), getString(R.string.share_error_generic), Toast.LENGTH_LONG).show()
            clearSharedContentState()
        } finally {
            Handler(Looper.getMainLooper()).postDelayed({
                isProcessingSharedExam = false
            }, 1000)
        }
    }

    private fun clearSharedContentState() {
        sharedPreferences.edit {
            remove("pending_shared_exam_uri")
            putBoolean("shared_content_processed", true)
        }
    }

    override fun onResume() {
        super.onResume()
        L.d("ExamFragment", "onResume called")
        L.d("ExamFragment", "Arguments in onResume: $arguments")

        if (arguments?.containsKey("highlight_exam_id") == true) {
            arguments = Bundle()
        }
    }

    private fun getThemeColor(@AttrRes attrRes: Int): Int {
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(attrRes, typedValue, true)
        return ContextCompat.getColor(requireContext(), typedValue.resourceId)
    }
}