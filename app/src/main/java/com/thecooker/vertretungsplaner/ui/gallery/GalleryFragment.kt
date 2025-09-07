package com.thecooker.vertretungsplaner.ui.gallery

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import com.thecooker.vertretungsplaner.L
import android.view.*
import android.widget.*
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.thecooker.vertretungsplaner.R
import com.thecooker.vertretungsplaner.data.CalendarDataManager
import com.thecooker.vertretungsplaner.data.SubstituteRepository
import com.thecooker.vertretungsplaner.data.ExamManager
import com.thecooker.vertretungsplaner.databinding.FragmentGalleryBinding
import com.thecooker.vertretungsplaner.ui.slideshow.SlideshowFragment
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import android.text.TextWatcher
import android.text.Editable
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.io.BufferedReader
import java.io.InputStreamReader
import android.widget.CheckBox
import android.os.Handler
import android.os.Looper
import android.animation.ValueAnimator
import android.os.Build
import android.text.Html
import android.util.AttributeSet
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.lifecycle.lifecycleScope
import com.thecooker.vertretungsplaner.utils.BackupManager
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import android.view.ViewGroup
import android.view.Gravity
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import kotlin.math.min

class SwipeInterceptorLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    interface SwipeListener {
        fun onSwipeLeft()
        fun onSwipeRight()
        fun onSwipeProgress(progress: Float, isRightSwipe: Boolean)
        fun onSwipeEnd()
    }

    var swipeListener: SwipeListener? = null

    private var initialX = 0f
    private var initialY = 0f
    private var lastX = 0f
    private var isSwipeGesture = false
    private var hasMovedEnough = false
    private val minSwipeDistance = 150f
    private val minMovementToDetect = 30f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = ev.x
                initialY = ev.y
                lastX = ev.x
                isSwipeGesture = false
                hasMovedEnough = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = ev.x - initialX
                val deltaY = ev.y - initialY
                val totalHorizontalMovement = abs(deltaX)
                val totalVerticalMovement = abs(deltaY)

                if (totalHorizontalMovement > minMovementToDetect) {
                    if (totalVerticalMovement == 0f || (totalHorizontalMovement / totalVerticalMovement) > 0.3f) {

                        if (!isSwipeGesture) {
                            isSwipeGesture = true
                            hasMovedEnough = true
                        }

                        val progress = min(totalHorizontalMovement / minSwipeDistance, 1f)
                        swipeListener?.onSwipeProgress(progress, deltaX > 0)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isSwipeGesture) {
                    swipeListener?.onSwipeEnd()
                }
                reset()
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_MOVE -> {
                if (isSwipeGesture) {
                    val deltaX = event.x - initialX
                    val progress = min(abs(deltaX) / minSwipeDistance, 1f)
                    swipeListener?.onSwipeProgress(progress, deltaX > 0)
                    lastX = event.x
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isSwipeGesture) {
                    val deltaX = event.x - initialX
                    if (abs(deltaX) > minSwipeDistance) {
                        if (deltaX > 0) {
                            swipeListener?.onSwipeRight()
                        } else {
                            swipeListener?.onSwipeLeft()
                        }
                    }
                    swipeListener?.onSwipeEnd()
                }
                reset()
                return isSwipeGesture
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isSwipeGesture) {
                    swipeListener?.onSwipeEnd()
                }
                reset()
                return isSwipeGesture
            }
        }
        return isSwipeGesture
    }

    private fun reset() {
        isSwipeGesture = false
        hasMovedEnough = false
    }
}

class GalleryFragment : Fragment() {

    private var _binding: FragmentGalleryBinding? = null
    private val binding get() = _binding!!

    private lateinit var calendarDataManager: CalendarDataManager
    private lateinit var calendarGrid: TableLayout
    private lateinit var currentWeekTextView: TextView
    private lateinit var editModeControls: LinearLayout
    private lateinit var colorLegend: LinearLayout

    private var isLoading = true

    private var currentWeekStart = Calendar.getInstance(Locale.GERMANY)
    private var isEditMode = false
    private var isDayView = false
    private var currentDayOffset = 0 // day view nav
    private var timetableData = mutableMapOf<String, MutableMap<Int, TimetableEntry>>()
    private var vacationWeeks = mutableMapOf<String, VacationWeek>() // "yyyy-MM-dd" for week starts
    private var undoStack = mutableListOf<String>()
    private var redoStack = mutableListOf<String>()
    private var originalTimetableData: String = ""

    private var realTimeUpdateHandler: Handler? = null
    private var realTimeUpdateRunnable: Runnable? = null
    private var isRealTimeEnabled = false
    private var includeWeekendsInDayView = false
    private var currentHighlightedCell: TextView? = null
    private var highlightAnimator: ValueAnimator? = null

    private lateinit var backupManager: BackupManager

    private var colorBlindMode: String = "none"

    // swipe ux
    private var swipeIndicatorView: View? = null
    private var gestureDetector: GestureDetector? = null

    data class VacationWeek(
        val weekKey: String,
        val name: String,
        val source: VacationSource = VacationSource.MANUAL
    )

    enum class VacationSource {
        MANUAL,
        AUTO_KULTUS
    }

    companion object {
        fun getAlternativeRoomsForSubject(context: Context, subject: String): List<String> {
            val sharedPreferences = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val json = sharedPreferences.getString("alternative_rooms", "{}")
            return try {
                val type = object : TypeToken<MutableMap<String, List<String>>>() {}.type
                val allRooms: MutableMap<String, List<String>> = Gson().fromJson(json, type) ?: mutableMapOf()
                allRooms[subject] ?: emptyList()
            } catch (e: Exception) {
                L.e("GalleryFragment", "Error loading alternative rooms for subject", e)
                emptyList()
            }
        }

    }

    private val cellWidth = 150
    private val cellHeight = 80

    private val lessonTimes = mapOf(
        1 to "07:30", 2 to "08:15", 3 to "09:30", 4 to "10:15", 5 to "11:15",
        6 to "12:00", 7 to "13:15", 8 to "14:00", 9 to "15:00", 10 to "15:45",
        11 to "17:00", 12 to "17:45", 13 to "18:45", 14 to "19:30", 15 to "20:15"
    )

    private val lessonEndTimes = mapOf(
        1 to "08:15", 2 to "09:00", 3 to "10:15", 4 to "11:00", 5 to "12:00",
        6 to "12:45", 7 to "14:00", 8 to "14:45", 9 to "15:45", 10 to "16:30",
        11 to "17:45", 12 to "18:30", 13 to "19:30", 14 to "20:15", 15 to "21:00"
    )

    private val breakTimes = mapOf(
        2 to 30, // 30 min after lesson 2
        4 to 15, // 15 minutes after lesson 4
        6 to 30, // ..
        8 to 15,
        10 to 30,
        12 to 15
    )

    private val weekdays = listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag")
    private val weekdaysShort = listOf("Mo", "Di", "Mi", "Do", "Fr")

    private val colorPriorities = mapOf( // color priorities (largest)
        "homework" to 1,
        "exam" to 2,
        "holiday" to 3,
        "vacation" to 4
    )

    data class TimetableEntry(
        val subject: String,
        val duration: Int = 1,
        val isBreak: Boolean = false,
        val breakDuration: Int = 0,
        val teacher: String = "",
        val room: String = "",
        val useAlternativeRoom: Boolean = false
    )

    data class CalendarEntry(
        val type: EntryType,
        val content: String,
        val subject: String = "",
        val backgroundColor: Int = Color.TRANSPARENT,
        val priority: Int = 0
    )

    enum class EntryType {
        HOMEWORK, EXAM, SUBSTITUTE, SPECIAL_DAY, VACATION
    }

    private val examColor = Color.parseColor("#9C27B0")

    // search bar
    private lateinit var searchBar: EditText
    private var currentSearchQuery = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGalleryBinding.inflate(inflater, container, false)

        calendarDataManager = CalendarDataManager.getInstance(requireContext())

        initializeCurrentWeek()
        setupUI()
        setupNavigationButtons()

        showLoadingState()

        loadDataAsync()

        return binding.root
    }

    private fun loadDataAsync() {
        lifecycleScope.launch {
            try {
                // load big data in background thread
                withContext(Dispatchers.IO) {
                    loadTimetableData()
                    loadVacationData()
                    identifyFreePeriods()
                }

                // update ui on main thread (for ux)
                withContext(Dispatchers.Main) {
                    if (isDayView) {
                        currentDayOffset = getCurrentDayOffset()
                    }

                    isLoading = false
                    updateCalendar()
                    setupRealTimeUpdates()
                }
            } catch (e: Exception) {
                L.e("GalleryFragment", "Error loading calendar data", e)
                withContext(Dispatchers.Main) {
                    isLoading = false
                    showErrorState()
                }
            }
        }
    }

    private fun showLoadingState() {
        calendarGrid.removeAllViews()

        val loadingRow = TableRow(requireContext()).apply {
            layoutParams = TableLayout.LayoutParams(
                TableLayout.LayoutParams.MATCH_PARENT,
                TableLayout.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
        }

        val loadingMessage = TextView(requireContext()).apply {
            text = "Kalender wird geladen..."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(32, 64, 32, 64)
            setTextColor(Color.BLACK)
            setTypeface(null, Typeface.BOLD)

            layoutParams = TableRow.LayoutParams(
                TableRow.LayoutParams.MATCH_PARENT,
                TableRow.LayoutParams.MATCH_PARENT
            )
        }

        loadingRow.addView(loadingMessage)
        calendarGrid.addView(loadingRow)
    }

    private fun showErrorState() {
        calendarGrid.removeAllViews()

        val errorMessage = TextView(requireContext()).apply {
            text = "Fehler beim Laden des Kalenders.\nTippe hier um es erneut zu versuchen."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(32, 64, 32, 64)
            setTextColor(Color.RED)
            setTypeface(null, Typeface.BOLD)

            setOnClickListener {
                showLoadingState()
                loadDataAsync()
            }
        }

        val row = TableRow(requireContext()).apply {
            layoutParams = TableLayout.LayoutParams(
                TableLayout.LayoutParams.MATCH_PARENT,
                TableLayout.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
        }

        row.addView(errorMessage)
        calendarGrid.addView(row)
    }

    private fun initializeCurrentWeek() {
        currentWeekStart.firstDayOfWeek = Calendar.MONDAY
        currentWeekStart.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        currentWeekStart.set(Calendar.HOUR_OF_DAY, 0)
        currentWeekStart.set(Calendar.MINUTE, 0)
        currentWeekStart.set(Calendar.SECOND, 0)
        currentWeekStart.set(Calendar.MILLISECOND, 0)
    }

    private fun setupUI() {
        loadViewPreference()
        loadRealTimePreference()
        loadWeekendPreference()
        loadColorBlindSettings()
        backupManager = BackupManager(requireContext())

        binding.root.findViewById<Button>(R.id.btnEditCalendar).apply {
            text = if (isDayView) "W" else "T"
            setOnClickListener { toggleCalendarView() }
        }

        binding.root.findViewById<Button>(R.id.btnMenuCalendar).setOnClickListener {
            showHamburgerMenu(it)
        }

        setupWeekButtons()

        setupSearchBar()

        binding.root.findViewById<Button>(R.id.btnPreviousWeek).setOnClickListener {
            if (isDayView) {
                currentDayOffset--
                if (currentDayOffset < 0) {
                    currentWeekStart.add(Calendar.WEEK_OF_YEAR, -1)
                    currentDayOffset = 4 // friday
                }
            } else {
                currentWeekStart.add(Calendar.WEEK_OF_YEAR, -1)
            }
            updateCalendar()
        }

        binding.root.findViewById<Button>(R.id.btnNextWeek).setOnClickListener {
            if (isDayView) {
                currentDayOffset++
                if (currentDayOffset > 4) {
                    currentWeekStart.add(Calendar.WEEK_OF_YEAR, 1)
                    currentDayOffset = 0 // monday
                }
            } else {
                currentWeekStart.add(Calendar.WEEK_OF_YEAR, 1)
            }
            updateCalendar()
        }

        editModeControls = binding.root.findViewById(R.id.editModeControls)

        binding.root.findViewById<Button>(R.id.btnUndo).setOnClickListener { performUndo() }
        binding.root.findViewById<Button>(R.id.btnRedo).setOnClickListener { performRedo() }
        binding.root.findViewById<Button>(R.id.btnCancel).setOnClickListener { cancelEdit() }
        binding.root.findViewById<Button>(R.id.btnSave).setOnClickListener { saveEdit() }

        currentWeekTextView = binding.root.findViewById(R.id.currentWeekTextView)
        colorLegend = binding.root.findViewById(R.id.colorLegend)

        currentWeekTextView.setOnClickListener {
            if (isDayView) {
                showDayPicker()
            } else {
                showWeekPicker()
            }
        }

        calendarGrid = binding.root.findViewById(R.id.calendarGrid)

        setupSwipeGestures()
        setupAlternativeSwipeGestures()
    }

    private fun toggleCalendarView() {
        isDayView = !isDayView
        if (isDayView) {
            currentDayOffset = getCurrentDayOffset()
            binding.root.findViewById<Button>(R.id.btnEditCalendar).text = "W"
            stopCurrentHighlight()
        } else {
            binding.root.findViewById<Button>(R.id.btnEditCalendar).text = "T"
        }
        saveViewPreference()
        updateCalendar()
    }

    private fun saveViewPreference() {
        val sharedPreferences = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        sharedPreferences.edit()
            .putBoolean("calendar_day_view", isDayView)
            .apply()
    }

    private fun loadViewPreference() {
        val sharedPreferences = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        isDayView = sharedPreferences.getBoolean("calendar_day_view", false) // defaulting week view
    }

    private fun getCurrentDayOffset(): Int {
        val today = Calendar.getInstance()
        return if (isSameWeek(currentWeekStart.time, today.time)) {
            when (today.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> 0
                Calendar.TUESDAY -> 1
                Calendar.WEDNESDAY -> 2
                Calendar.THURSDAY -> 3
                Calendar.FRIDAY -> 4
                Calendar.SATURDAY -> if (includeWeekendsInDayView) 5 else 0
                Calendar.SUNDAY -> if (includeWeekendsInDayView) 6 else 0
                else -> 0
            }
        } else {
            0
        }
    }

    private fun showHamburgerMenu(anchor: View) {
        val popupMenu = PopupMenu(requireContext(), anchor)

        popupMenu.menu.add(0, R.id.action_today, 0, "Heute").apply {
            setIcon(R.drawable.ic_today)
        }
        popupMenu.menu.add(0, R.id.action_edit_timetable, 1, "Stundenplan bearbeiten").apply {
            setIcon(R.drawable.ic_pencil)
        }
        popupMenu.menu.add(0, R.id.action_vacation, 2, "Ferien markieren").apply {
            setIcon(R.drawable.ic_vacation)
        }
        popupMenu.menu.add(0, R.id.action_statistics, 3, "Statistiken").apply {
            setIcon(R.drawable.ic_statistics)
        }
        popupMenu.menu.add(0, R.id.action_export, 4, "Exportieren").apply {
            setIcon(R.drawable.ic_export)
        }
        popupMenu.menu.add(0, R.id.action_import, 5, "Importieren").apply {
            setIcon(R.drawable.ic_import)
        }

        try {
            val fieldMPopup = PopupMenu::class.java.getDeclaredField("mPopup")
            fieldMPopup.isAccessible = true
            val mPopup = fieldMPopup.get(popupMenu)
            mPopup.javaClass
                .getDeclaredMethod("setForceShowIcon", Boolean::class.java)
                .invoke(mPopup, true)
        } catch (e: Exception) {
            L.w("GalleryFragment", "Could not force show icons", e)
        }

        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_today -> {
                    goToCurrentWeek()
                    true
                }
                R.id.action_edit_timetable -> {
                    toggleEditMode()
                    true
                }
                R.id.action_vacation -> {
                    showMarkVacationDialog()
                    true
                }
                R.id.action_statistics -> {
                    showStatistics()
                    true
                }
                R.id.action_export -> {
                    showExportOptions()
                    true
                }
                R.id.action_import -> {
                    showImportOptions()
                    true
                }
                else -> false
            }
        }

        popupMenu.show()
    }

    private fun showStatistics() {
        val stats = calculateStatistics()
        val message = buildString {
            appendLine("Nächste Termine:")
            appendLine("• Bis nächste Klausur: ${if (stats.daysUntilNextExam == -1) "Keine geplant" else "${stats.daysUntilNextExam} Tage"}")
            appendLine("• Bis nächste Hausaufgabe: ${if (stats.daysUntilNextHomework == -1) "Keine geplant" else "${stats.daysUntilNextHomework} Tage"}")
            appendLine("• Bis nächste Ferien: ${if (stats.daysUntilNextVacation == -1) "Keine geplant" else "${stats.daysUntilNextVacation} Tage"}")
            appendLine("• Bis nächster Feiertag: ${if (stats.daysUntilNextHoliday == -1) "Keiner geplant" else "${stats.daysUntilNextHoliday} Tage"}")
            appendLine()

            appendLine("Nächste 30 Tage:") // this month
            appendLine("• Klausuren: ${stats.examsThisMonth}")
            appendLine("• Hausaufgaben: ${stats.homeworkThisMonth}")
            appendLine("• Feiertage: ${stats.holidaysThisMonth}")
            appendLine("• Ferientage: ${stats.vacationDaysThisMonth}")
            appendLine()

            appendLine("Gesamt:") // != aktuelles halbjahr
            appendLine("• Klausuren gesamt: ${stats.totalExams}")
            appendLine("• Hausaufgaben gesamt: ${stats.totalHomework}")
            appendLine("• Ferientage gesamt: ${stats.totalVacationDays}")
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Statistiken")
            .setMessage(message)
            .setPositiveButton("Schließen", null)
            .show()
    }

    private fun calculateStatistics(): Statistics {
        val now = Calendar.getInstance()
        val currentMonth = now.get(Calendar.MONTH)
        val currentYear = now.get(Calendar.YEAR)

        var daysUntilNextExam = Int.MAX_VALUE
        var daysUntilNextHomework = Int.MAX_VALUE
        var daysUntilNextVacation = Int.MAX_VALUE
        var daysUntilNextHoliday = Int.MAX_VALUE

        var examsThisMonth = 0
        var homeworkThisMonth = 0
        var holidaysThisMonth = 0
        var vacationDaysThisMonth = 0

        var totalExams = 0
        var totalHomework = 0
        val totalVacationDays = vacationWeeks.size * 7

        val checkDate = Calendar.getInstance()
        repeat(365) { dayOffset ->
            checkDate.time = now.time
            checkDate.add(Calendar.DAY_OF_YEAR, dayOffset)

            SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(checkDate.time)
            val calendarInfo = calendarDataManager.getCalendarInfoForDate(checkDate.time)

            calendarInfo?.exams?.let { exams ->
                if (exams.isNotEmpty()) {
                    if (dayOffset >= 0 && daysUntilNextExam == Int.MAX_VALUE) {
                        daysUntilNextExam = dayOffset
                    }
                    totalExams += exams.size
                    if (checkDate.get(Calendar.MONTH) == currentMonth && checkDate.get(Calendar.YEAR) == currentYear) {
                        examsThisMonth += exams.size
                    }
                }
            }

            // check homework
            try {
                val homeworkList = SlideshowFragment.getHomeworkList(requireContext())
                val dayHomework = homeworkList.filter { isSameDay(it.dueDate, checkDate.time) }
                if (dayHomework.isNotEmpty()) {
                    if (dayOffset >= 0 && daysUntilNextHomework == Int.MAX_VALUE) {
                        daysUntilNextHomework = dayOffset
                    }
                    totalHomework += dayHomework.size
                    if (checkDate.get(Calendar.MONTH) == currentMonth && checkDate.get(Calendar.YEAR) == currentYear) {
                        homeworkThisMonth += dayHomework.size
                    }
                }
            } catch (e: Exception) {
                L.w("GalleryFragment", "Error loading homework for statistics", e)
            }

            // check holidays
            var isHoliday = false

            // check manual user occasions
            val userOccasions = getUserSpecialOccasionsForDate(checkDate.time)
            userOccasions.forEach { occasion ->
                if (occasion.contains("Feiertag", ignoreCase = true) ||
                    occasion.contains("Ferientag", ignoreCase = true) ||
                    occasion.contains("Bew. Feiertag", ignoreCase = true) ||
                    occasion.contains("Bew. Ferientag", ignoreCase = true)) {
                    isHoliday = true
                }
            }

            // check calendar manager holidays
            calendarInfo?.let { info ->
                if (info.isSpecialDay && (info.specialNote.contains("Feiertag", ignoreCase = true) ||
                            info.specialNote.contains("Ferientag", ignoreCase = true) ||
                            info.specialNote.contains("Bew. Feiertag", ignoreCase = true) ||
                            info.specialNote.contains("Bew. Ferientag", ignoreCase = true))) {
                    isHoliday = true
                }
            }

            if (isHoliday) {
                if (dayOffset >= 0 && daysUntilNextHoliday == Int.MAX_VALUE) {
                    daysUntilNextHoliday = dayOffset
                }
                if (checkDate.get(Calendar.MONTH) == currentMonth && checkDate.get(Calendar.YEAR) == currentYear) {
                    holidaysThisMonth++
                }
            }

            // check vacations (ferien)
            if (isDateInVacation(checkDate.time)) {
                if (dayOffset >= 0 && daysUntilNextVacation == Int.MAX_VALUE) {  // include today
                    daysUntilNextVacation = dayOffset
                }
                if (checkDate.get(Calendar.MONTH) == currentMonth && checkDate.get(Calendar.YEAR) == currentYear) {
                    vacationDaysThisMonth++
                }
            }
        }

        return Statistics(
            if (daysUntilNextExam == Int.MAX_VALUE) -1 else daysUntilNextExam,
            if (daysUntilNextHomework == Int.MAX_VALUE) -1 else daysUntilNextHomework,
            if (daysUntilNextVacation == Int.MAX_VALUE) -1 else daysUntilNextVacation,
            if (daysUntilNextHoliday == Int.MAX_VALUE) -1 else daysUntilNextHoliday,
            examsThisMonth,
            homeworkThisMonth,
            holidaysThisMonth,
            vacationDaysThisMonth,
            totalExams,
            totalHomework,
            totalVacationDays
        )
    }

    data class Statistics(
        val daysUntilNextExam: Int,
        val daysUntilNextHomework: Int,
        val daysUntilNextVacation: Int,
        val daysUntilNextHoliday: Int,
        val examsThisMonth: Int,
        val homeworkThisMonth: Int,
        val holidaysThisMonth: Int,
        val vacationDaysThisMonth: Int,
        val totalExams: Int,
        val totalHomework: Int,
        val totalVacationDays: Int
    )

    private fun showMarkVacationDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_mark_vacation, null)

        val weeksSpinner = dialogView.findViewById<Spinner>(R.id.spinner_weeks)
        val removeSwitch = dialogView.findViewById<Switch>(R.id.switch_remove_vacation)
        val autoMarkButton = dialogView.findViewById<Button>(R.id.btn_auto_mark)
        val clearAllButton = dialogView.findViewById<Button>(R.id.btn_clear_all)

        weeksSpinner.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item,
            (1..8).map {
                when (it) {
                    1 -> "1 Woche"
                    else -> "$it Wochen"
                }
            }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        val mainDialog = AlertDialog.Builder(requireContext())
            .setTitle("Ferien verwalten")
            .setMessage("Markiert/entfernt Ferien ab der aktuellen Woche")
            .setView(dialogView)
            .setPositiveButton("MARKIEREN") { dialog, _ ->
                val weeksCount = weeksSpinner.selectedItemPosition + 1
                if (removeSwitch.isChecked) {
                    removeVacationWeeks(weeksCount)
                } else {
                    markVacationWeeks(weeksCount)
                }
                dialog.dismiss()
            }
            .setNegativeButton("ABBRECHEN") { dialog, _ ->
                dialog.dismiss()
            }
            .create()

        removeSwitch.setOnCheckedChangeListener { _, isChecked ->
            val positiveButton = mainDialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.text = if (isChecked) "ENTFERNEN" else "MARKIEREN"
        }

        clearAllButton.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Alle Ferien löschen")
                .setMessage("Möchten Sie wirklich alle markierten Ferien löschen?")
                .setPositiveButton("JA") { innerDialog, _ ->
                    clearAllVacations()
                    innerDialog.dismiss()
                    mainDialog.dismiss()
                }
                .setNegativeButton("NEIN") { innerDialog, _ ->
                    innerDialog.dismiss()
                }
                .show()
        }

        autoMarkButton.setOnClickListener {
            showAutoMarkVacationDialog { mainDialog.dismiss() }
        }

        mainDialog.show()
    }

    private fun clearAllVacations() {
        val vacationCount = vacationWeeks.size
        vacationWeeks.clear()
        saveVacationData()
        updateCalendar()
        Toast.makeText(
            requireContext(),
            "Alle Ferien gelöscht ($vacationCount Wochen entfernt)",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun fetchAndMarkHessenVacations(schoolYear: String, clearExisting: Boolean) {
        val progressDialog = AlertDialog.Builder(requireContext())
            .setTitle("Feriendaten laden")
            .setMessage("Lade Ferientermine von kultus.hessen.de...")
            .setCancelable(false)
            .create()

        progressDialog.show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val vacationPeriods = fetchVacationDataFromWebsite(schoolYear)

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()

                    if (vacationPeriods.isEmpty()) {
                        Toast.makeText(
                            requireContext(),
                            "Keine Feriendaten für $schoolYear gefunden",
                            Toast.LENGTH_LONG
                        ).show()
                        return@withContext
                    }

                    if (clearExisting) {
                        // clear only auto vacations (keep manual)
                        val manualVacations = vacationWeeks.filterValues { it.source == VacationSource.MANUAL }
                        vacationWeeks.clear()
                        vacationWeeks.putAll(manualVacations)
                    }

                    var markedWeeks = 0

                    for ((vacationName, period) in vacationPeriods) {
                        val calendar = Calendar.getInstance(Locale.GERMANY).apply {
                            time = period.first
                            firstDayOfWeek = Calendar.MONDAY
                            while (get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
                                add(Calendar.DAY_OF_YEAR, -1)
                            }
                        }

                        while (!calendar.time.after(period.second)) {
                            val weekKey = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(calendar.time)

                            if (!vacationWeeks.containsKey(weekKey) ||
                                vacationWeeks[weekKey]?.name != vacationName) {
                                vacationWeeks[weekKey] = VacationWeek(weekKey, vacationName, VacationSource.AUTO_KULTUS)
                                markedWeeks++
                            }

                            calendar.add(Calendar.WEEK_OF_YEAR, 1)
                        }
                    }

                    saveVacationData()
                    updateCalendar()

                    Toast.makeText(
                        requireContext(),
                        "Ferien für $schoolYear markiert ($markedWeeks Wochen)",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    L.e("GalleryFragment", "Error fetching vacation data", e)
                    Toast.makeText(
                        requireContext(),
                        "Fehler beim Laden der Feriendaten: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showAutoMarkVacationDialog(onComplete: () -> Unit = {}) {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val schoolYears = mutableListOf<String>()

        for (i in 0..5) {
            val startYear = currentYear + i
            val endYear = startYear + 1
            schoolYears.add("$startYear/$endYear")
        }

        val yearSpinner = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_item,
                schoolYears
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }

        val clearExistingCheckbox = CheckBox(requireContext()).apply {
            text = "Vorhandene automatische Ferien löschen"
            isChecked = true
            setPadding(0, 16, 0, 16)
        }

        val privacyNotice = TextView(requireContext()).apply {
            text = "Hinweis: Diese Funktion lädt Feriendaten von kultus.hessen.de. " +
                    "Es werden keine persönlichen Daten übertragen."
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 16, 0, 16)
        }

        container.addView(TextView(requireContext()).apply {
            text = "Schuljahr auswählen:"
            textSize = 14f
            setTextColor(Color.BLACK)
        })
        container.addView(yearSpinner)
        container.addView(clearExistingCheckbox)
        container.addView(privacyNotice)

        AlertDialog.Builder(requireContext())
            .setTitle("Ferien automatisch markieren")
            .setMessage("Lädt offizielle Ferientermine von kultus.hessen.de")
            .setView(container)
            .setPositiveButton("Laden") { dialog, _ ->
                val selectedSchoolYear = schoolYears[yearSpinner.selectedItemPosition]
                val clearExisting = clearExistingCheckbox.isChecked
                fetchAndMarkHessenVacations(selectedSchoolYear, clearExisting)
                dialog.dismiss()
                onComplete()
            }
            .setNegativeButton("Abbrechen") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun fetchVacationDataFromWebsite(schoolYear: String): List<Pair<String, Pair<Date, Date>>> {
        val url = URL("https://kultus.hessen.de/schulsystem/ferien/ferientermine")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP Error: $responseCode")
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream, "UTF-8"))
            val htmlContent = reader.readText()
            reader.close()

            return parseVacationDatesWithNames(htmlContent, schoolYear)

        } finally {
            connection.disconnect()
        }
    }

    private fun parseVacationDatesWithNames(htmlContent: String, targetSchoolYear: String): List<Pair<String, Pair<Date, Date>>> {
        val vacationPeriods = mutableListOf<Pair<String, Pair<Date, Date>>>()
        val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)

        try {
            val schoolYearPattern = Regex("<h2[^>]*>\\s*Schuljahr\\s+$targetSchoolYear\\s*</h2>", RegexOption.IGNORE_CASE)
            val schoolYearMatch = schoolYearPattern.find(htmlContent)

            if (schoolYearMatch == null) {
                L.w("GalleryFragment", "School year $targetSchoolYear not found in HTML")
                return vacationPeriods
            }

            val startIndex = schoolYearMatch.range.last
            val nextH2Pattern = Regex("<h2[^>]*>", RegexOption.IGNORE_CASE)
            val nextH2Match = nextH2Pattern.find(htmlContent, startIndex)
            val endIndex = nextH2Match?.range?.first ?: htmlContent.length

            val sectionContent = htmlContent.substring(startIndex, endIndex)

            val rowPattern = Regex("<tr[^>]*>\\s*<td[^>]*>([^<]+)</td>\\s*<td[^>]*>([^<]+)</td>\\s*</tr>", RegexOption.IGNORE_CASE)
            val matches = rowPattern.findAll(sectionContent)

            for (match in matches) {
                val vacationType = match.groups[1]?.value?.trim() ?: continue
                val dateRange = match.groups[2]?.value?.trim() ?: continue

                if (vacationType.contains("bewegliche", ignoreCase = true)) {
                    continue
                }

                val vacationName = when {
                    vacationType.contains("Herbst", ignoreCase = true) -> "Herbstferien"
                    vacationType.contains("Weihnacht", ignoreCase = true) ||
                            vacationType.contains("Winter", ignoreCase = true) -> "Winterferien"
                    vacationType.contains("Oster", ignoreCase = true) -> "Osterferien"
                    vacationType.contains("Sommer", ignoreCase = true) -> "Sommerferien"
                    vacationType.contains("Pfingst", ignoreCase = true) -> "Pfingstferien"
                    else -> vacationType
                }

                val dateRangePattern = Regex("(\\d{1,2}\\.\\d{1,2}\\.)\\s*(\\d{4})?\\s*-\\s*(\\d{1,2}\\.\\d{1,2}\\.)\\s*(\\d{4})")
                val dateMatch = dateRangePattern.find(dateRange)

                if (dateMatch != null) {
                    val startDateStr = dateMatch.groups[1]?.value ?: continue
                    val startYear = dateMatch.groups[2]?.value
                    val endDateStr = dateMatch.groups[3]?.value ?: continue
                    val endYear = dateMatch.groups[4]?.value ?: continue

                    try {
                        val actualStartYear = startYear ?: endYear
                        val startDate = dateFormat.parse("$startDateStr$actualStartYear")
                        val endDate = dateFormat.parse("$endDateStr$endYear")

                        if (startDate != null && endDate != null) {
                            vacationPeriods.add(Pair(vacationName, Pair(startDate, endDate)))
                            L.d("GalleryFragment", "Found vacation: $vacationName from $startDate to $endDate")
                        }
                    } catch (e: Exception) {
                        L.w("GalleryFragment", "Error parsing dates for $vacationType: $dateRange", e)
                    }
                }
            }

        } catch (e: Exception) {
            L.e("GalleryFragment", "Error parsing HTML content", e)
        }

        return vacationPeriods
    }

    private fun removeVacationWeeks(weeksCount: Int) {
        val weekStart = Calendar.getInstance().apply {
            time = currentWeekStart.time
        }

        var removedCount = 0
        for (i in 0 until weeksCount) {
            val weekKey = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(weekStart.time)
            if (vacationWeeks.remove(weekKey) != null) {
                removedCount++
            }
            weekStart.add(Calendar.WEEK_OF_YEAR, 1)
        }

        saveVacationData()
        updateCalendar()

        Toast.makeText(
            requireContext(),
            "Ferien entfernt ($removedCount von $weeksCount Wochen)",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun markVacationWeeks(weeksCount: Int) {
        val weekStart = Calendar.getInstance().apply {
            time = currentWeekStart.time
        }

        for (i in 0 until weeksCount) {
            val weekKey = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(weekStart.time)
            val vacationName = determineVacationType(weekStart.time)
            vacationWeeks[weekKey] = VacationWeek(weekKey, vacationName, VacationSource.MANUAL)
            weekStart.add(Calendar.WEEK_OF_YEAR, 1)
        }

        saveVacationData()
        updateCalendar()

        val vacationType = determineVacationType(currentWeekStart.time)
        Toast.makeText(
            requireContext(),
            "$vacationType markiert ($weeksCount Wochen)",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun determineVacationType(date: Date): String {
        val calendar = Calendar.getInstance().apply { time = date }
        val month = calendar.get(Calendar.MONTH)
        calendar.get(Calendar.DAY_OF_YEAR)

        return when (month) {
            Calendar.FEBRUARY, Calendar.MARCH, Calendar.APRIL -> {
                if (month == Calendar.MARCH && calendar.get(Calendar.DAY_OF_MONTH) > 15) "Osterferien"
                else if (month == Calendar.APRIL && calendar.get(Calendar.DAY_OF_MONTH) < 20) "Osterferien"
                else "Frühjahrsferien"
            }
            Calendar.JUNE, Calendar.JULY, Calendar.AUGUST -> {
                if (month == Calendar.JUNE && calendar.get(Calendar.DAY_OF_MONTH) > 20) "Sommerferien"
                else if (month == Calendar.JULY) "Sommerferien"
                else if (month == Calendar.AUGUST && calendar.get(Calendar.DAY_OF_MONTH) < 15) "Sommerferien"
                else "Ferien"
            }
            Calendar.OCTOBER -> "Herbstferien"
            Calendar.DECEMBER, Calendar.JANUARY -> {
                if (month == Calendar.DECEMBER && calendar.get(Calendar.DAY_OF_MONTH) > 15) "Winterferien"
                else if (month == Calendar.JANUARY && calendar.get(Calendar.DAY_OF_MONTH) < 15) "Winterferien"
                else "Ferien"
            }
            else -> "Ferien"
        }
    }

    private fun isDateInVacation(date: Date): Boolean {
        val calendar = Calendar.getInstance().apply {
            time = date
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        }
        val weekKey = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(calendar.time)
        return vacationWeeks.containsKey(weekKey)
    }

    private fun getVacationName(date: Date): String {
        val calendar = Calendar.getInstance().apply {
            time = date
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        }
        val weekKey = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(calendar.time)
        return vacationWeeks[weekKey]?.name ?: determineVacationType(date)
    }

    private fun goToCurrentWeek() {
        val now = Calendar.getInstance(Locale.GERMANY)
        currentWeekStart.time = now.time
        initializeCurrentWeek()
        if (isDayView) {
            currentDayOffset = getCurrentDayOffset()
        }
        updateCalendar()
    }

    private fun showWeekPicker() {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val yearSpinner = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(
                requireContext(), android.R.layout.simple_spinner_item,
                (2024..2026).toList()
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }

        val weekSpinner = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(
                requireContext(), android.R.layout.simple_spinner_item,
                (1..53).map { "Woche $it" }).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }

        val currentYear = currentWeekStart.get(Calendar.YEAR)
        val currentWeek = currentWeekStart.get(Calendar.WEEK_OF_YEAR)

        yearSpinner.setSelection((2024..2026).indexOf(currentYear))
        weekSpinner.setSelection(currentWeek - 1)

        container.addView(TextView(requireContext()).apply {
            text = "Jahr:"
            textSize = 14f
            setTextColor(Color.BLACK)
        })
        container.addView(yearSpinner)
        container.addView(TextView(requireContext()).apply {
            text = "Kalenderwoche:"
            textSize = 14f
            setTextColor(Color.BLACK)
        })
        container.addView(weekSpinner)

        AlertDialog.Builder(requireContext())
            .setTitle("Kalenderwoche auswählen")
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                val selectedYear = 2024 + yearSpinner.selectedItemPosition
                val selectedWeek = weekSpinner.selectedItemPosition + 1

                currentWeekStart.set(Calendar.YEAR, selectedYear)
                currentWeekStart.set(Calendar.WEEK_OF_YEAR, selectedWeek)
                currentWeekStart.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                updateCalendar()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showDayPicker() {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val yearSpinner = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(
                requireContext(), android.R.layout.simple_spinner_item,
                (2024..2026).toList()
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }

        val monthSpinner = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(
                requireContext(), android.R.layout.simple_spinner_item,
                (1..12).map { String.format("%02d", it) }).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }

        val daySpinner = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(
                requireContext(), android.R.layout.simple_spinner_item,
                (1..31).map { String.format("%02d", it) }).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }

        val currentDay = Calendar.getInstance().apply {
            time = currentWeekStart.time
            add(Calendar.DAY_OF_WEEK, currentDayOffset)
        }

        yearSpinner.setSelection((2024..2026).indexOf(currentDay.get(Calendar.YEAR)))
        monthSpinner.setSelection(currentDay.get(Calendar.MONTH))
        daySpinner.setSelection(currentDay.get(Calendar.DAY_OF_MONTH) - 1)

        container.addView(TextView(requireContext()).apply {
            text = "Jahr:"
            textSize = 14f
            setTextColor(Color.BLACK)
        })
        container.addView(yearSpinner)
        container.addView(TextView(requireContext()).apply {
            text = "Monat:"
            textSize = 14f
            setTextColor(Color.BLACK)
        })
        container.addView(monthSpinner)
        container.addView(TextView(requireContext()).apply {
            text = "Tag:"
            textSize = 14f
            setTextColor(Color.BLACK)
        })
        container.addView(daySpinner)

        AlertDialog.Builder(requireContext())
            .setTitle("Tag auswählen")
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                val selectedYear = 2024 + yearSpinner.selectedItemPosition
                val selectedMonth = monthSpinner.selectedItemPosition
                val selectedDay = daySpinner.selectedItemPosition + 1

                val newDate = Calendar.getInstance().apply {
                    set(selectedYear, selectedMonth, selectedDay)
                    firstDayOfWeek = Calendar.MONDAY
                }

                currentWeekStart.time = newDate.time
                currentWeekStart.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)

                currentDayOffset = when (newDate.get(Calendar.DAY_OF_WEEK)) {
                    Calendar.MONDAY -> 0
                    Calendar.TUESDAY -> 1
                    Calendar.WEDNESDAY -> 2
                    Calendar.THURSDAY -> 3
                    Calendar.FRIDAY -> 4
                    else -> 0 // weekend -> defaulting to monday
                }

                updateCalendar()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun updateCalendar() {
        updateWeekDisplay()
        buildCalendarGrid()
        setupColorLegend()
    }

    private fun updateWeekDisplay() {
        if (isDayView) {
            val currentDay = Calendar.getInstance().apply {
                time = currentWeekStart.time
                add(Calendar.DAY_OF_WEEK, currentDayOffset)
            }

            val dateFormat = SimpleDateFormat("EEEE, dd.MM.yyyy", Locale.GERMANY)
            currentWeekTextView.text = dateFormat.format(currentDay.time)
        } else {
            val weekEnd = Calendar.getInstance().apply {
                time = currentWeekStart.time
                add(Calendar.DAY_OF_WEEK, 4)
            }

            val dateFormat = SimpleDateFormat("dd.", Locale.GERMANY)
            val weekFormat = SimpleDateFormat("w", Locale.GERMANY)

            val startDay = dateFormat.format(currentWeekStart.time)
            val endDay = dateFormat.format(weekEnd.time)
            val monthYear = SimpleDateFormat("MM.yyyy", Locale.GERMANY).format(weekEnd.time)
            val weekNumber = weekFormat.format(currentWeekStart.time)

            currentWeekTextView.text = "$startDay - $endDay$monthYear\nKalenderwoche $weekNumber"
        }
    }

    private fun buildCalendarGrid() {
        if (isLoading) {
            return
        }

        calendarGrid.removeAllViews()

        val sharedPreferences = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val hasScannedDocument = sharedPreferences.getBoolean("has_scanned_document", false)

        if (!hasScannedDocument) {
            showEmptyCalendar()
            return
        }

        if (isDayView && currentDayOffset < 0) {
            currentDayOffset = getCurrentDayOffset()
        }

        if (isDayView) {
            createDayView()
        } else {
            createWeekView()
        }
    }

    private fun createWeekView() {
        createHeaderRow()
        createLessonRows()
    }

    private fun createDayView() {
        createDayHeaderRow()
        createDayLessonRows()
    }

    private fun createDayHeaderRow() {
        val headerRow = TableRow(requireContext()).apply {
            layoutParams = TableLayout.LayoutParams(
                TableLayout.LayoutParams.MATCH_PARENT,
                TableLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // lesson/time column header
        val timeHeader = createStyledCell("Stunde", isHeader = true, isLessonColumn = true, isDayView = true)
        timeHeader.setOnClickListener { showAllLessonTimes() }
        headerRow.addView(timeHeader)

        // day header
        val currentDay = Calendar.getInstance().apply {
            time = currentWeekStart.time
            add(Calendar.DAY_OF_WEEK, currentDayOffset)
        }

        val dayName = when (currentDayOffset) {
            0 -> "Montag"
            1 -> "Dienstag"
            2 -> "Mittwoch"
            3 -> "Donnerstag"
            4 -> "Freitag"
            5 -> "Samstag"
            6 -> "Sonntag"
            else -> "Unbekannt"
        }

        val dayDate = SimpleDateFormat("dd.MM", Locale.GERMANY).format(currentDay.time)
        val dayHeader = createStyledCell("$dayName\n$dayDate", isHeader = true, isDayView = true)

        // check for today and override styling after createStyledCell
        val today = Calendar.getInstance()
        val isToday = isSameDay(currentDay.time, today.time)

        if (isToday) {
            dayHeader.background = createRoundedDrawable(Color.parseColor("#FFC107"))
            dayHeader.setTextColor(Color.BLACK)
        }

        // check for notes and mark with asterisk
        if (hasNotesForDay(currentDay.time) || shouldShowAsterisk(currentDay.time)) {
            dayHeader.text = "${dayHeader.text}*"
        }

        dayHeader.setOnClickListener { showDayDetails(currentDay.time) }
        headerRow.addView(dayHeader)

        calendarGrid.addView(headerRow)
    }

    private fun createDayLessonRows() {
        val maxLessons = getMaxLessonsForWeek()
        val currentTime = Calendar.getInstance()
        val currentDay = Calendar.getInstance().apply {
            time = currentWeekStart.time
            add(Calendar.DAY_OF_WEEK, currentDayOffset)
        }
        val isCurrentDay = isSameDay(currentDay.time, currentTime.time)

        for (lesson in 1..maxLessons) {
            val breakMinutes = breakTimes[lesson - 1]
            if (breakMinutes != null && lesson > 1) {
                createDayBreakRow(breakMinutes, isCurrentDay, currentTime, lesson)
            }

            val lessonRow = TableRow(requireContext()).apply {
                layoutParams = TableLayout.LayoutParams(
                    TableLayout.LayoutParams.MATCH_PARENT,
                    TableLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val lessonStartTime = lessonTimes[lesson] ?: ""
            val lessonEndTime = lessonEndTimes[lesson] ?: ""

            val timeCell = createStyledCell("", isLessonColumn = true, isDayView = true).apply {
                val timeText = "$lesson.\n$lessonStartTime - $lessonEndTime"
                val spannableText = SpannableString(timeText)
                val lessonNumberEnd = timeText.indexOf('.') + 1
                spannableText.setSpan(
                    StyleSpan(Typeface.BOLD),
                    0,
                    lessonNumberEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                text = spannableText
            }

            if (isCurrentDay && isCurrentLesson(lesson, currentTime)) {
                timeCell.background = createFlatRoundedDrawable("#FFC107")
                timeCell.setTextColor(Color.BLACK)
            }

            timeCell.setOnClickListener { showLessonTimeDetails(lesson) }
            lessonRow.addView(timeCell)

            // day column
            val dayCell = createDayLessonCell(currentDayOffset, lesson, isCurrentDay, currentTime)
            lessonRow.addView(dayCell)

            calendarGrid.addView(lessonRow)
        }
    }

    private fun createDayBreakRow(breakMinutes: Int, isCurrentDay: Boolean, currentTime: Calendar, afterLesson: Int) {
        val breakRow = TableRow(requireContext()).apply {
            layoutParams = TableLayout.LayoutParams(
                TableLayout.LayoutParams.MATCH_PARENT,
                TableLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val breakView = TextView(requireContext()).apply {
            text = "Pause\n($breakMinutes Min.)"
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
            setTextColor(Color.BLACK)

            val drawable = createRoundedDrawable(Color.LTGRAY)
            background = drawable

            // check if currently in break
            if (isCurrentDay && isCurrentBreakTime(afterLesson - 1, currentTime)) {
                background = createRoundedDrawableWithBorder(Color.LTGRAY, Color.YELLOW, 8)
            }

            layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT).apply {
                span = 2
                setMargins(2, 1, 2, 1)
            }
        }

        breakRow.addView(breakView)
        calendarGrid.addView(breakRow)
    }

    private fun createDayLessonCell(
        dayIndex: Int,
        lesson: Int,
        isCurrentDay: Boolean,
        currentTime: Calendar
    ): TextView {
        val currentWeekDay = Calendar.getInstance().apply {
            time = currentWeekStart.time
            add(Calendar.DAY_OF_WEEK, dayIndex)
        }

        val dayKey = getWeekdayKey(dayIndex)
        val timetableEntry = timetableData[dayKey]?.get(lesson)

        val cell = createStyledCell("", isDayView = true)
        var cellText: String
        val calendarEntries = mutableListOf<CalendarEntry>()

        // check if in vacation
        if (isDateInVacation(currentWeekDay.time)) {
            val vacationType = getVacationName(currentWeekDay.time)
            cellText = vacationType
            val drawable = createRoundedDrawable(Color.LTGRAY)
            cell.background = drawable
            cell.text = cellText

            if (currentSearchQuery.isNotBlank() && !matchesSearchWithSpecialOccasions(
                    cellText, "", "", calendarEntries,
                    getUserSpecialOccasionsForDate(currentWeekDay.time),
                    calendarDataManager.getCalendarInfoForDate(currentWeekDay.time)?.specialNote ?: ""
                )) {
                cell.alpha = 0.3f
            } else {
                cell.alpha = 1.0f
            }

            if (isEditMode) {
                cell.setOnClickListener {
                    showTimetableEditor(dayIndex, lesson)
                }
            }
            return cell
        }

        // check for user special occasions with effect
        val userOccasions = getUserSpecialOccasionsForDate(currentWeekDay.time)
        var shouldMarkAsHoliday = false
        var shouldMarkFromLesson4 = false

        userOccasions.forEach { occasion ->
            when {
                occasion.contains("Feiertag", ignoreCase = true) ||
                        occasion.contains("Ferientag", ignoreCase = true) -> {
                    shouldMarkAsHoliday = true
                }
                occasion.contains("3. Std", ignoreCase = true) ||
                        occasion.contains("3. Stunde", ignoreCase = true) -> {
                    if (lesson >= 4) shouldMarkFromLesson4 = true
                }
            }
        }

        // check calendar data manager
        val calendarInfo = calendarDataManager.getCalendarInfoForDate(currentWeekDay.time)
        calendarInfo?.let { info ->
            if (info.isSpecialDay && info.specialNote.isNotBlank()) {
                when {
                    info.specialNote.contains("Feiertag", ignoreCase = true) ||
                            info.specialNote.contains("Ferientag", ignoreCase = true) -> {
                        shouldMarkAsHoliday = true
                    }
                    info.specialNote.contains("3. Std.", ignoreCase = true) -> {
                        if (lesson >= 4) shouldMarkFromLesson4 = true
                    }
                }
            }
        }

        if (shouldMarkAsHoliday || shouldMarkFromLesson4) {
            cellText = if (shouldMarkAsHoliday) "Feiertag" else "Frei ab 4. Std."
            cell.text = cellText
            val drawable = createRoundedDrawable(Color.LTGRAY)
            cell.background = drawable

            if (currentSearchQuery.isNotBlank() && !matchesSearchWithSpecialOccasions(
                    "", "", "", emptyList(), userOccasions, calendarInfo?.specialNote ?: "")) {
                cell.alpha = 0.3f
            } else {
                cell.alpha = 1.0f
            }

            if (isEditMode) {
                cell.setOnClickListener {
                    showTimetableEditor(dayIndex, lesson)
                }
            }
            return cell
        }

        if (timetableEntry != null && !timetableEntry.isBreak) {
            val teacherToShow = timetableEntry.teacher.takeIf { it.isNotBlank() }
                ?: getTeacherAndRoomForSubject(timetableEntry.subject).first

            val roomToShow = timetableEntry.room.takeIf { it.isNotBlank() }
                ?: getTeacherAndRoomForSubject(timetableEntry.subject).second

            val teacherRoomDisplay = formatTeacherRoomDisplay(teacherToShow, roomToShow)

            val mainInfo = if (teacherRoomDisplay.isNotBlank()) {
                "${timetableEntry.subject} | $teacherRoomDisplay"
            } else {
                timetableEntry.subject
            }

            cellText = mainInfo

            calendarEntries.addAll(
                getCalendarEntriesForDayAndLesson(
                    currentWeekDay.time,
                    lesson,
                    timetableEntry.subject
                )
            )

            val additionalInfo = mutableListOf<String>()
            var hasSubstitute = false
            var substituteText = ""

            calendarEntries.forEach { entry ->
                when (entry.type) {
                    EntryType.HOMEWORK -> additionalInfo.add("Hausaufgabe")
                    EntryType.EXAM -> additionalInfo.add("Klausur")
                    EntryType.SUBSTITUTE -> {
                        if (entry.content.contains("statt", ignoreCase = true)) {
                            hasSubstitute = true
                            substituteText = entry.content
                        } else {
                            additionalInfo.add(entry.content)
                        }
                    }
                    EntryType.SPECIAL_DAY -> {
                        if (entry.content.contains("Feiertag", ignoreCase = true)) {
                            additionalInfo.add("Feiertag")
                        } else {
                            additionalInfo.add(entry.content)
                        }
                    }
                    else -> {}
                }
            }

            if (hasSubstitute) {
                // strike through original and new subject below (for "verlegte" subjects)
                val originalSubject = timetableEntry.subject
                val newSubjectInfo = substituteText

                val htmlText = "<small><s>$originalSubject</s></small><br><b>$newSubjectInfo</b>"

                if (additionalInfo.isNotEmpty()) {
                    val finalHtml = htmlText + "<br><small>" + additionalInfo.joinToString(" | ") + "</small>"
                    cell.text = Html.fromHtml(finalHtml, Html.FROM_HTML_MODE_COMPACT)
                } else {
                    cell.text = Html.fromHtml(htmlText, Html.FROM_HTML_MODE_COMPACT)
                }
            } else {
                // Normal display logic
                if (additionalInfo.isNotEmpty()) {
                    cellText += "\n" + additionalInfo.joinToString(" | ")
                }

                val lines = cellText.split("\n")
                if (lines.isNotEmpty()) {
                    val firstLine = lines[0]
                    val subjectEndIndex = firstLine.indexOf(" |")

                    val formattedFirstLine = if (subjectEndIndex > 0) {
                        val subject = firstLine.substring(0, subjectEndIndex)
                        val restOfFirstLine = firstLine.substring(subjectEndIndex)
                        "<b>$subject</b>$restOfFirstLine"
                    } else {
                        "<b>$firstLine</b>"
                    }

                    val finalText = if (lines.size > 1) {
                        formattedFirstLine + "<br>" + lines.drop(1).joinToString("<br>")
                    } else {
                        formattedFirstLine
                    }

                    cell.text = Html.fromHtml(finalText, Html.FROM_HTML_MODE_COMPACT)
                } else {
                    cell.text = cellText
                }
            }
        } else if (isEditMode) {
            cellText = "＋"
            cell.setTextColor(Color.GRAY)
            cell.text = cellText
        }

        if (currentSearchQuery.isNotBlank() && !matchesSearchWithSpecialOccasions(
                timetableEntry?.subject ?: "",
                getTeacherAndRoomForSubject(timetableEntry?.subject ?: "").first,
                getTeacherAndRoomForSubject(timetableEntry?.subject ?: "").second,
                calendarEntries,
                userOccasions,
                calendarInfo?.specialNote ?: ""
            )) {
            cell.alpha = 0.3f
        } else if (timetableEntry?.subject == "Freistunde") {
            cell.alpha = 0.6f
        } else {
            cell.alpha = 1.0f
        }

        // search filter
        val baseOpacity = if (timetableEntry?.subject == "Freistunde") 0.6f else 1.0f

        val actualRoom = timetableEntry?.room ?: ""
        val actualTeacher = timetableEntry?.teacher ?: ""

        if (currentSearchQuery.isNotBlank() && !matchesSearchWithSpecialOccasions(
                timetableEntry?.subject ?: "",
                actualTeacher,
                actualRoom,
                calendarEntries,
                userOccasions,
                calendarInfo?.specialNote ?: ""
            )) {
            cell.alpha = baseOpacity * 0.3f
        } else {
            cell.alpha = baseOpacity
        }

        val backgroundColor = getHighestPriorityBackgroundColor(calendarEntries)

        if (isCurrentDay && isCurrentLesson(lesson, currentTime) && timetableEntry != null) {
            val drawable = createRoundedDrawableWithBorder(
                backgroundColor,
                Color.YELLOW, 12
            )
            cell.background = drawable
        } else if (backgroundColor != Color.TRANSPARENT) {
            val drawable = createRoundedDrawable(backgroundColor)
            cell.background = drawable
        }

        if (isEditMode) {
            cell.setOnClickListener {
                showTimetableEditor(dayIndex, lesson)
            }
        } else if (timetableEntry != null) {
            cell.setOnClickListener {
                showLessonDetails(currentWeekDay.time, lesson, timetableEntry, calendarEntries)
            }
        }

        return cell
    }

    private fun showLessonTimeDetails(lesson: Int) {
        val startTime = lessonTimes[lesson] ?: "Unbekannt"
        val endTime = lessonEndTimes[lesson] ?: "Unbekannt"
        val breakAfter = breakTimes[lesson]

        val message = buildString {
            appendLine("${lesson}. Stunde")
            appendLine()
            appendLine("Beginn: $startTime")
            appendLine("Ende: $endTime")
            if (breakAfter != null) {
                appendLine()
                appendLine("Anschließende Pause: $breakAfter Minuten")
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Stundenzeiten")
            .setMessage(message)
            .setPositiveButton("Schließen", null)
            .show()
    }

    private fun showLessonDetails(
        date: Date,
        lesson: Int,
        timetableEntry: TimetableEntry,
        calendarEntries: List<CalendarEntry>
    ) {
        val dateStr = SimpleDateFormat("EEEE, dd.MM.yyyy", Locale.GERMANY).format(date)
        val startTime = lessonTimes[lesson] ?: "Unbekannt"
        val endTime = lessonEndTimes[lesson] ?: "Unbekannt"

        val message = buildString {
            appendLine("Datum: $dateStr")
            appendLine("Zeit: $startTime - $endTime (${lesson}. Stunde)")
            appendLine()
            appendLine("Fach: ${timetableEntry.subject}")

            if (timetableEntry.teacher.isNotBlank() && timetableEntry.teacher != "UNKNOWN") {
                appendLine("Lehrer: ${timetableEntry.teacher}")
            }
            if (timetableEntry.room.isNotBlank() && timetableEntry.room != "UNKNOWN") {
                appendLine("Raum: ${timetableEntry.room}")
            }

            if (calendarEntries.isNotEmpty()) {
                appendLine()
                appendLine("Zusätzliche Informationen:")
                calendarEntries.forEach { entry ->
                    when (entry.type) {
                        EntryType.HOMEWORK -> appendLine("• Hausaufgabe fällig")
                        EntryType.EXAM -> appendLine("• Klausur")
                        EntryType.SUBSTITUTE -> appendLine("• Vertretung: ${entry.content}")
                        EntryType.SPECIAL_DAY -> appendLine("• ${entry.content}")
                        else -> appendLine("• ${entry.content}")
                    }
                }
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Stundendetails")
            .setMessage(message)
            .setPositiveButton("Schließen", null)
            .show()
    }

    private fun showEmptyCalendar() {
        val emptyMessage = TextView(requireContext()).apply {
            text = "Du musst zuerst deinen Stundenplan scannen, um den Kalender verwenden zu können."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(32, 64, 32, 64)
            setTextColor(Color.BLACK)
        }

        val row = TableRow(requireContext())
        row.addView(emptyMessage)
        calendarGrid.addView(row)
    }

    private fun createHeaderRow() {
        val headerRow = TableRow(requireContext()).apply {
            layoutParams = TableLayout.LayoutParams(
                TableLayout.LayoutParams.MATCH_PARENT,
                TableLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val lessonHeader = createStyledCell("Std.", isHeader = true, isLessonColumn = true).apply {
            setTypeface(null, Typeface.BOLD)
            background = createFlatRoundedDrawable("#0f5293")
            setTextColor(Color.WHITE)
            setOnClickListener { showAllLessonTimes() }
        }
        headerRow.addView(lessonHeader)

        val today = Calendar.getInstance()

        for (i in weekdays.indices) {
            val currentWeekDay = Calendar.getInstance().apply {
                time = currentWeekStart.time
                add(Calendar.DAY_OF_WEEK, i)
            }

            val dayHeader = createStyledCell(weekdaysShort[i], isHeader = true).apply {
                setTypeface(null, Typeface.BOLD)

                val isToday = isSameDay(currentWeekDay.time, today.time) &&
                        isSameWeek(currentWeekStart.time, today.time)

                background = if (isToday) {
                    createFlatRoundedDrawable("#FFC107")
                } else {
                    createFlatRoundedDrawable("#0f5293")
                }

                setTextColor(if (isToday) Color.BLACK else Color.WHITE)

                var displayText = weekdaysShort[i]
                if (shouldShowAsterisk(currentWeekDay.time)) {
                    displayText += "*"
                }

                text = displayText
                setOnClickListener { showDayDetails(currentWeekDay.time) }
            }

            headerRow.addView(dayHeader)
        }

        calendarGrid.addView(headerRow)
    }

    private fun showEditDayDialog(date: Date) {
        val dateStr = SimpleDateFormat("EEEE, dd.MM.yyyy", Locale.GERMANY).format(date)

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val notesLabel = TextView(requireContext()).apply {
            text = "Notizen:"
            textSize = 16f
            setTextColor(Color.BLACK)
            setTypeface(null, Typeface.BOLD)
        }

        val notesEditText = EditText(requireContext()).apply {
            hint = "Persönliche Notizen für diesen Tag..."
            minLines = 2
            maxLines = 4
            text = Editable.Factory.getInstance().newEditable(getUserNotesForDate(date))
        }

        val occasionsLabel = TextView(requireContext()).apply {
            text = "Besondere Ereignisse:"
            textSize = 16f
            setTextColor(Color.BLACK)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 24, 0, 8)
        }

        val occasionsContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }

        val userOccasions = getUserSpecialOccasionsForDate(date).toMutableList()
        val occasionEditTexts = mutableListOf<EditText>()

        fun addOccasionField(text: String = "") {
            val occasionLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 4, 0, 4)
            }

            val editText = EditText(requireContext()).apply {
                hint = "z.B. 'Infotag', 'Päd. Tag', 'Feiertag', '3. Std.'"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setText(text)
            }
            occasionEditTexts.add(editText)

            val removeButton = ImageButton(requireContext()).apply {
                setImageResource(R.drawable.ic_trash_can)
                setColorFilter(Color.RED)
                background = null
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = 8
                }
                setOnClickListener {
                    occasionsContainer.removeView(occasionLayout)
                    occasionEditTexts.remove(editText)
                }
            }

            occasionLayout.addView(editText)
            occasionLayout.addView(removeButton)
            occasionsContainer.addView(occasionLayout)
        }

        if (userOccasions.isEmpty()) {
            addOccasionField()
        } else {
            userOccasions.forEach { occasion ->
                addOccasionField(occasion)
            }
        }

        val addOccasionButton = Button(requireContext()).apply {
            text = "+ Ereignis hinzufügen"
            setOnClickListener { addOccasionField() }
        }

        container.addView(notesLabel)
        container.addView(notesEditText)
        container.addView(occasionsLabel)
        container.addView(occasionsContainer)
        container.addView(addOccasionButton)

        AlertDialog.Builder(requireContext())
            .setTitle("Tag bearbeiten - $dateStr")
            .setView(container)
            .setPositiveButton("Speichern") { _, _ ->

                val notes = notesEditText.text.toString().trim()
                saveUserNotesForDate(date, notes)

                val occasions = occasionEditTexts.mapNotNull { editText ->
                    editText.text.toString().trim().takeIf { it.isNotBlank() }
                }
                saveUserSpecialOccasionsForDate(date, occasions)

                updateCalendar()
                Toast.makeText(requireContext(), "Änderungen gespeichert", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Abbrechen", null)
            .setNeutralButton("Zurücksetzen") { _, _ ->
                AlertDialog.Builder(requireContext())
                    .setTitle("Zurücksetzen bestätigen")
                    .setMessage("Alle manuell hinzugefügten Notizen und besonderen Ereignisse für diesen Tag werden gelöscht. Fortfahren?")
                    .setPositiveButton("Ja") { _, _ ->
                        clearUserDataForDate(date)
                        updateCalendar()
                        Toast.makeText(requireContext(), "Tag zurückgesetzt", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Nein", null)
                    .show()
            }
            .show()
    }

    private fun shouldShowAsterisk(date: Date): Boolean {
        val calendarInfo = calendarDataManager.getCalendarInfoForDate(date)

        val userNotes = getUserNotesForDate(date)
        val userSpecialOccasions = getUserSpecialOccasionsForDate(date)

        if (userNotes.isNotBlank() || userSpecialOccasions.isNotEmpty()) {
            return true
        }

        return calendarInfo?.let { info ->
            info.isSpecialDay && info.specialNote.isNotBlank()
        } ?: false
    }

    private fun getUserNotesForDate(date: Date): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(date)
        val sharedPreferences = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        return sharedPreferences.getString("user_notes_$dateStr", "") ?: ""
    }

    private fun getUserSpecialOccasionsForDate(date: Date): List<String> {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(date)
        val sharedPreferences = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val json = sharedPreferences.getString("user_special_occasions_$dateStr", "[]")
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            Gson().fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveUserNotesForDate(date: Date, notes: String) {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(date)
        val sharedPreferences = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        sharedPreferences.edit()
            .putString("user_notes_$dateStr", notes)
            .apply()
    }

    private fun saveUserSpecialOccasionsForDate(date: Date, occasions: List<String>) {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(date)
        val sharedPreferences = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val json = Gson().toJson(occasions)
        sharedPreferences.edit()
            .putString("user_special_occasions_$dateStr", json)
            .apply()

        saveHolidaysData()
    }

    private fun clearUserDataForDate(date: Date) {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(date)
        val sharedPreferences = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        sharedPreferences.edit()
            .remove("user_notes_$dateStr")
            .remove("user_special_occasions_$dateStr")
            .apply()
    }

    private fun showAllLessonTimes() {
        val message = buildString {
            appendLine("Stundenzeiten Übersicht")
            appendLine()

            for (lesson in 1..15) {
                val startTime = lessonTimes[lesson]
                val endTime = lessonEndTimes[lesson]
                if (startTime != null && endTime != null) {
                    appendLine("${lesson}. Stunde: $startTime - $endTime")

                    breakTimes[lesson]?.let { breakMinutes ->
                        appendLine("   ➞ Pause: $breakMinutes Minuten")
                    }
                }
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Alle Stundenzeiten")
            .setMessage(message)
            .setPositiveButton("Schließen", null)
            .show()
    }

    private fun createLessonRows() {
        val maxLessons = getMaxLessonsForWeek()
        val currentTime = Calendar.getInstance()
        val isCurrentWeek = isSameWeek(currentWeekStart.time, currentTime.time)

        for (lesson in 1..maxLessons) {
            val breakMinutes = breakTimes[lesson - 1]
            if (breakMinutes != null && lesson > 1) {
                createBreakRow(breakMinutes, isCurrentWeek, currentTime, lesson - 1)
            }

            val lessonRow = TableRow(requireContext()).apply {
                layoutParams = TableLayout.LayoutParams(
                    TableLayout.LayoutParams.MATCH_PARENT,
                    TableLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val timeCell = createStyledCell("$lesson", isLessonColumn = true).apply {
                setTypeface(null, Typeface.BOLD)

                val isCurrentLessonTime = isCurrentWeek &&
                        isCurrentLesson(lesson, currentTime) &&
                        !isCurrentBreakTime(lesson - 1, currentTime)

                background = if (isCurrentLessonTime) {
                    createFlatRoundedDrawable("#FFC107")
                } else {
                    createFlatRoundedDrawable("#ECEFF1")
                }

                setTextColor(if (isCurrentLessonTime) Color.BLACK else Color.parseColor("#37474F"))

                setOnClickListener { showLessonTimeDetails(lesson) }
            }
            lessonRow.addView(timeCell)

            // day columns
            for (dayIndex in weekdays.indices) {
                val dayCell = createDayCell(dayIndex, lesson, isCurrentWeek, currentTime)
                lessonRow.addView(dayCell)
            }

            calendarGrid.addView(lessonRow)
        }
    }

    private fun createDayCell(
        dayIndex: Int,
        lesson: Int,
        isCurrentWeek: Boolean,
        currentTime: Calendar
    ): TextView {
        val currentWeekDay = Calendar.getInstance().apply {
            time = currentWeekStart.time
            add(Calendar.DAY_OF_WEEK, dayIndex)
        }

        val dayKey = getWeekdayKey(dayIndex)
        val timetableEntry = timetableData[dayKey]?.get(lesson)

        val cell = createStyledCell("")
        var cellText = ""

        if (isDateInVacation(currentWeekDay.time)) {
            val vacationType = getVacationName(currentWeekDay.time)
            cell.text = vacationType.take(8)
            val drawable = createRoundedDrawable(Color.LTGRAY)
            cell.background = drawable

            if (currentSearchQuery.isNotBlank() && !vacationType.contains(currentSearchQuery, ignoreCase = true)) {
                cell.alpha = 0.3f
            } else {
                cell.alpha = 1.0f
            }

            if (isEditMode) {
                cell.setOnClickListener {
                    showTimetableEditor(dayIndex, lesson)
                }
            }
            return cell
        }

        val userOccasions = getUserSpecialOccasionsForDate(currentWeekDay.time)
        var shouldMarkAsHoliday = false
        var shouldMarkFromLesson4 = false

        userOccasions.forEach { occasion ->
            when {
                occasion.contains("Feiertag", ignoreCase = true) ||
                        occasion.contains("Ferientag", ignoreCase = true) -> {
                    shouldMarkAsHoliday = true
                }
                occasion.contains("3. Std", ignoreCase = true) ||
                        occasion.contains("3. Stunde", ignoreCase = true) -> {
                    if (lesson >= 4) shouldMarkFromLesson4 = true
                }
            }
        }

        val calendarInfo = calendarDataManager.getCalendarInfoForDate(currentWeekDay.time)
        calendarInfo?.let { info ->
            if (info.isSpecialDay && info.specialNote.isNotBlank()) {
                when {
                    info.specialNote.contains("Feiertag", ignoreCase = true) ||
                            info.specialNote.contains("Ferientag", ignoreCase = true) -> {
                        shouldMarkAsHoliday = true
                    }
                    info.specialNote.contains("3. Std.", ignoreCase = true) -> {
                        if (lesson >= 4) shouldMarkFromLesson4 = true
                    }
                }
            }
        }

        if (shouldMarkAsHoliday || shouldMarkFromLesson4) {
            cellText = if (shouldMarkAsHoliday) "Feiertag" else "Frei"
            cell.text = cellText
            val drawable = createRoundedDrawable(Color.LTGRAY)
            cell.background = drawable

            if (currentSearchQuery.isNotBlank() && !matchesSearchWithSpecialOccasions(
                    "", "", "", emptyList(), userOccasions, calendarInfo?.specialNote ?: "")) {
                cell.alpha = 0.3f
            } else {
                cell.alpha = 1.0f
            }

            if (isEditMode) {
                cell.setOnClickListener {
                    showTimetableEditor(dayIndex, lesson)
                }
            }
            return cell
        }

        val calendarEntries = mutableListOf<CalendarEntry>()

        if (timetableEntry != null && !timetableEntry.isBreak) {
            cellText = timetableEntry.subject
            calendarEntries.addAll(
                getCalendarEntriesForDayAndLesson(
                    currentWeekDay.time,
                    lesson,
                    timetableEntry.subject
                )
            )
        } else if (isEditMode) {
            cellText = "＋"
            cell.setTextColor(Color.GRAY)
        }

        cell.text = cellText

        val baseOpacity = if (timetableEntry?.subject == "Freistunde") 0.6f else 1.0f

        // get actual room being used (could be alternative room from timetableEntry)
        val actualRoom = timetableEntry?.room ?: ""
        val actualTeacher = timetableEntry?.teacher ?: ""

        if (currentSearchQuery.isNotBlank() && !matchesSearchWithSpecialOccasions(
                timetableEntry?.subject ?: "",
                actualTeacher,
                actualRoom,
                calendarEntries,
                userOccasions,
                calendarInfo?.specialNote ?: ""
            )) {
            cell.alpha = baseOpacity * 0.3f
        } else {
            cell.alpha = baseOpacity
        }

        val backgroundColor = getHighestPriorityBackgroundColor(calendarEntries)

        if (isCurrentWeek && isSameDay(currentWeekDay.time, currentTime.time) &&
            isCurrentLesson(lesson, currentTime) && timetableEntry != null
        ) {
            if (!isDayView) {
                startCurrentLessonHighlight(cell)
            } else {
                val drawable = createRoundedDrawableWithBorder(
                    backgroundColor,
                    Color.YELLOW, 12
                )
                cell.background = drawable
            }
        } else if (backgroundColor != Color.TRANSPARENT) {
            val drawable = createRoundedDrawable(backgroundColor)
            cell.background = drawable
        }

        if (isEditMode) {
            cell.setOnClickListener {
                showTimetableEditor(dayIndex, lesson)
            }
        } else if (timetableEntry != null) {
            cell.setOnClickListener {
                showLessonDetails(currentWeekDay.time, lesson, timetableEntry, calendarEntries)
            }
        }

        return cell
    }

    private fun matchesSearchWithSpecialOccasions(
        subject: String,
        teacher: String,
        room: String,
        calendarEntries: List<CalendarEntry>,
        userOccasions: List<String>,
        calendarSpecialNote: String
    ): Boolean {
        if (currentSearchQuery.isBlank()) return true

        val query = currentSearchQuery.lowercase()

        if (subject.lowercase().contains(query)) return true

        if (teacher.lowercase().contains(query)) return true

        if (room.lowercase().contains(query)) return true

        // additional calendar entries
        for (entry in calendarEntries) {
            when (entry.type) {
                EntryType.HOMEWORK -> if ("hausaufgabe".contains(query)) return true
                EntryType.EXAM -> if ("klausur".contains(query)) return true
                EntryType.SUBSTITUTE -> if (entry.content.lowercase().contains(query)) return true
                EntryType.SPECIAL_DAY -> if (entry.content.lowercase().contains(query)) return true
                EntryType.VACATION -> if ("ferien".contains(query) || "ferientag".contains(query)) return true
            }
        }

        if (userOccasions.any { it.lowercase().contains(query) }) return true

        if (calendarSpecialNote.lowercase().contains(query)) return true

        return false
    }

    private fun getHighestPriorityBackgroundColor(calendarEntries: List<CalendarEntry>): Int {
        if (calendarEntries.isEmpty()) return Color.TRANSPARENT

        val highestPriorityEntry = calendarEntries.maxByOrNull { it.priority }
        return highestPriorityEntry?.backgroundColor ?: Color.TRANSPARENT
    }

    private fun createBreakRow(breakMinutes: Int, isCurrentWeek: Boolean, currentTime: Calendar, afterLesson: Int) {
        val breakRow = TableRow(requireContext()).apply {
            layoutParams = TableLayout.LayoutParams(
                TableLayout.LayoutParams.MATCH_PARENT,
                TableLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val breakView = TextView(requireContext()).apply {
            text = "Pause ($breakMinutes Min.)"
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(4, 4, 4, 4)
            setTextColor(Color.BLACK)

            val drawable = createRoundedDrawable(Color.LTGRAY)
            background = drawable

            // break highlight
            if (isCurrentWeek && isCurrentBreakTime(afterLesson, currentTime)) {
                background = createRoundedDrawableWithBorder(Color.LTGRAY, Color.YELLOW, 8)
            }

            layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT).apply {
                span = 6 // all columns
                setMargins(2, 1, 2, 1)
            }
        }

        breakRow.addView(breakView)
        calendarGrid.addView(breakRow)
    }

    private fun isCurrentBreakTime(afterLesson: Int, currentTime: Calendar): Boolean {
        breakTimes[afterLesson] ?: return false
        val lessonEndTime = lessonEndTimes[afterLesson] ?: return false
        val nextLessonStartTime = lessonTimes[afterLesson + 1] ?: return false

        val currentTimeStr = SimpleDateFormat("HH:mm", Locale.GERMANY).format(currentTime.time)

        val endParts = lessonEndTime.split(":")
        val startParts = nextLessonStartTime.split(":")
        val currentParts = currentTimeStr.split(":")

        val endMinutes = endParts[0].toInt() * 60 + endParts[1].toInt()
        val startMinutes = startParts[0].toInt() * 60 + startParts[1].toInt()
        val currentMinutes = currentParts[0].toInt() * 60 + currentParts[1].toInt()

        return currentMinutes >= endMinutes && currentMinutes < startMinutes
    }

    private fun setupColorLegend() {
        colorLegend.removeAllViews()

        colorLegend.orientation = LinearLayout.VERTICAL

        val legendItems = listOf(
            Pair("Hausaufgabe", Color.CYAN),
            Pair("Klausur", examColor),
            Pair("Feiertag/Ferien", Color.LTGRAY),
            Pair("Entfällt", getColorBlindFriendlyColor("red")),
            Pair("Betreuung", getColorBlindFriendlyColor("orange")),
            Pair("Vertretung", getColorBlindFriendlyColor("green")),
        )

        val firstRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 12, 0, 6)
            }
        }

        val secondRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 6, 0, 12)
            }
        }

        legendItems.take(3).forEach { (label, color) ->
            val legendItem = createLegendItem(label, color)
            firstRow.addView(legendItem)
        }

        legendItems.drop(3).forEach { (label, color) ->
            val legendItem = createLegendItem(label, color)
            secondRow.addView(legendItem)
        }

        colorLegend.addView(firstRow)
        colorLegend.addView(secondRow)
    }

    private fun createLegendItem(label: String, color: Int): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(16, 0, 16, 0)
            }
            setPadding(12, 6, 12, 6)

            val colorBox = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(40, 40).apply {
                    setMargins(0, 0, 12, 0)
                }
                background = createRoundedDrawable(color)
            }

            val labelText = TextView(requireContext()).apply {
                text = label
                textSize = 14f
                setTextColor(Color.BLACK)
            }

            addView(colorBox)
            addView(labelText)
        }
    }

    private fun getCalendarEntriesForDayAndLesson(
        date: Date,
        lesson: Int,
        subject: String
    ): List<CalendarEntry> {
        val entries = mutableListOf<CalendarEntry>()

        // ferien check
        if (isDateInVacation(date)) {
            entries.add(
                CalendarEntry(
                    EntryType.VACATION,
                    "Ferien",
                    "",
                    Color.LTGRAY,
                    colorPriorities["vacation"] ?: 0
                )
            )
            return entries
        }

        // special days check
        val calendarInfo = calendarDataManager.getCalendarInfoForDate(date)
        calendarInfo?.let { info ->
            if (info.isSpecialDay && info.specialNote.isNotBlank()) {
                when {
                    info.specialNote.contains("Feiertag", ignoreCase = true) ||
                            info.specialNote.contains("Bew. Feiertag", ignoreCase = true) ||
                            info.specialNote.contains("Ferientag", ignoreCase = true) ||
                            info.specialNote.contains("Bew. Ferientag", ignoreCase = true) -> {
                        entries.add(
                            CalendarEntry(
                                EntryType.SPECIAL_DAY,
                                "Feiertag",
                                "",
                                Color.LTGRAY,
                                colorPriorities["holiday"] ?: 0
                            )
                        )
                        return entries
                    }

                    info.specialNote.contains("3. Std.", ignoreCase = true) -> {
                        if (lesson >= 4) {
                            entries.add(
                                CalendarEntry(
                                    EntryType.SPECIAL_DAY,
                                    "Frei ab 4. Std.",
                                    "",
                                    Color.LTGRAY,
                                    colorPriorities["holiday"] ?: 0
                                )
                            )
                            return entries
                        }
                    }
                }
            }
        }

        // add homework entries
        try {
            val homeworkList = SlideshowFragment.getHomeworkList(requireContext())
            homeworkList.forEach { homework ->
                if (isSameDay(homework.dueDate, date)) {
                    // use specific lesson if given
                    if (homework.lessonNumber != null && homework.lessonNumber!! > 0) {
                        if (homework.lessonNumber == lesson) {
                            entries.add(
                                CalendarEntry(
                                    EntryType.HOMEWORK,
                                    "HA",
                                    homework.subject,
                                    Color.CYAN,
                                    colorPriorities["homework"] ?: 0
                                )
                            )
                        }
                    } else {
                        // else find first occurrence of homework subject
                        val dayKey = getWeekdayKey(getDayOfWeekIndex(date))
                        val dayTimetable = timetableData[dayKey] ?: return@forEach

                        val firstLessonWithSubject = dayTimetable.entries
                            .filter { it.value.subject == homework.subject }
                            .minByOrNull { it.key }?.key

                        if (firstLessonWithSubject == lesson) {
                            entries.add(
                                CalendarEntry(
                                    EntryType.HOMEWORK,
                                    "HA",
                                    homework.subject,
                                    Color.CYAN,
                                    colorPriorities["homework"] ?: 0
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            L.w("GalleryFragment", "Error loading homework", e)
        }

        // add exam entries
        try {
            calendarInfo?.exams?.forEach { exam ->
                if (exam.subject == subject) {
                    val dayKey = getWeekdayKey(getDayOfWeekIndex(date))
                    val dayTimetable = timetableData[dayKey] ?: return@forEach

                    val firstLesson = dayTimetable.entries
                        .filter { it.value.subject == subject }
                        .minByOrNull { it.key }?.key ?: return@forEach

                    if (isPartOfConsecutiveSubjectBlock(
                            dayTimetable,
                            firstLesson,
                            lesson,
                            subject
                        )
                    ) {
                        entries.add(
                            CalendarEntry(
                                EntryType.EXAM, "Klausur", exam.subject,
                                examColor,
                                colorPriorities["exam"] ?: 0
                            )
                        )
                    }
                }
            }

            // fallback with exammanager
            val dateStr = SimpleDateFormat("yyyyMMdd", Locale.GERMANY).format(date)
            val examsFromManager = ExamManager.getExamsForDate(dateStr)
            examsFromManager.forEach { exam ->
                if (exam.subject == subject) {
                    val dayKey = getWeekdayKey(getDayOfWeekIndex(date))
                    val dayTimetable = timetableData[dayKey]
                    if (dayTimetable?.get(lesson)?.subject == subject) {
                        entries.add(
                            CalendarEntry(
                                EntryType.EXAM, "Klausur", exam.subject,
                                examColor,
                                colorPriorities["exam"] ?: 0
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            L.w("GalleryFragment", "Error loading exams", e)
        }

        // add substitute entries
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(date)
        val substituteEntries =
            SubstituteRepository.getSubstituteEntriesByDate(requireContext(), dateStr)
        substituteEntries.forEach { substitute ->
            val startLesson = substitute.stunde
            val endLesson = substitute.stundeBis ?: substitute.stunde

            if (lesson >= startLesson && lesson <= endLesson && substitute.fach == subject) {
                val backgroundColor = getSubstituteBackgroundColor(substitute.art)
                val formattedText = formatSubstituteText(substitute.art)
                entries.add(
                    CalendarEntry(
                        EntryType.SUBSTITUTE,
                        formattedText.take(15),
                        substitute.fach,
                        backgroundColor,
                        colorPriorities["exam"] ?: 0
                    )
                )
            }
        }

        return entries
    }

    private fun createStyledCell(
        text: String,
        isHeader: Boolean = false,
        isLessonColumn: Boolean = false,
        isDayView: Boolean = false
    ): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            textSize = if (isHeader) 14f else if (isDayView) 12f else 10f
            gravity = Gravity.CENTER
            if (isDayView) {
                if (isHeader && isLessonColumn) {
                    // "Zeit"
                    setPadding(16, 24, 16, 24)
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                } else if (isHeader) {
                    // day name header
                    setPadding(20, 24, 20, 24)
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                } else if (isLessonColumn) {
                    // time cells on left
                    setPadding(12, 24, 12, 24)
                    textSize = 11f
                } else {
                    // main subject content cells
                    setPadding(16, 24, 16, 24)
                    textSize = 12f
                }
            } else {
                setPadding(8, 16, 8, 16)
            }

            if (isHeader) {
                background = createRoundedDrawable(Color.parseColor("#0f5293"))
                setTextColor(Color.WHITE)
            } else if (isLessonColumn) {
                background = createRoundedDrawable(Color.parseColor("#ECEFF1"))
                setTextColor(Color.parseColor("#37474F"))
            } else {
                background = createRoundedDrawable(Color.WHITE)
                setTextColor(Color.parseColor("#212121"))
            }

            if (isDayView) {
                if (isLessonColumn) {
                    if (isHeader) {
                        // "Zeit"
                        width = 160
                        height = 160
                    } else {
                        // lesson/time cells
                        width = 160
                        height = 150
                    }
                } else {
                    if (isHeader) {
                        // day header
                        width = 500
                        height = 160
                    } else {
                        // subject cells
                        width = 500
                        height = 150
                    }
                }
                layoutParams = TableRow.LayoutParams(
                    if (isLessonColumn) 160 else 500,
                    if (isHeader) 160 else 150
                ).apply {
                    setMargins(3, 3, 3, 3)
                    gravity = Gravity.CENTER_VERTICAL
                }
            } else {
                if (isLessonColumn) {
                    width = 60
                    height = cellHeight
                } else {
                    width = cellWidth
                    height = if (isHeader) 80 else cellHeight
                }
                layoutParams = TableRow.LayoutParams().apply {
                    setMargins(3, 3, 3, 3)
                    if (!isLessonColumn) weight = 1f
                }
            }
        }
    }

    private fun createFlatRoundedDrawable(colorHex: String): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(colorHex))
            cornerRadius = 8f
        }
    }

    private fun showTimetableEditor(dayIndex: Int, lesson: Int) {
        val sharedPreferences = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val subjects = sharedPreferences.getString("student_subjects", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

        if (subjects.isEmpty()) {
            Toast.makeText(requireContext(), "Keine Fächer gefunden. Scanne zuerst deinen Stundenplan ein.", Toast.LENGTH_LONG).show()
            return
        }

        saveStateForUndo()

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val subjectSpinner = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item,
                listOf("Kein Unterricht") + subjects).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }

        val durationSpinner = Spinner(requireContext()).apply {
            val durationOptions = (1..8).map {
                when (it) {
                    1 -> "1 Stunde"
                    else -> "$it Stunden"
                }
            }
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, durationOptions).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(1)
        }

        val roomLabel = TextView(requireContext()).apply {
            text = "Raum:"
            textSize = 14f
            setTextColor(Color.BLACK)
            visibility = View.GONE
        }

        val roomSpinner = Spinner(requireContext()).apply {
            visibility = View.GONE
        }

        subjectSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedSubject = subjectSpinner.selectedItem.toString()
                if (position > 0) { // not "Kein Unterricht"
                    val alternativeRooms = getAlternativeRoomsForSubject(requireContext(), selectedSubject)
                    if (alternativeRooms.isNotEmpty()) {
                        val (_, mainRoom) = getTeacherAndRoomForSubject(selectedSubject)

                        val roomOptions = mutableListOf<String>()
                        if (mainRoom.isNotBlank() && mainRoom != "UNKNOWN") {
                            roomOptions.add(mainRoom)
                        }
                        alternativeRooms.forEach { room ->
                            if (room != mainRoom) {
                                roomOptions.add(room)
                            }
                        }

                        if (roomOptions.isNotEmpty()) {
                            roomSpinner.adapter = ArrayAdapter(requireContext(),
                                android.R.layout.simple_spinner_item, roomOptions).apply {
                                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                            }
                            roomLabel.visibility = View.VISIBLE
                            roomSpinner.visibility = View.VISIBLE

                            // main room default (index 0)
                            roomSpinner.setSelection(0)
                        } else {
                            roomLabel.visibility = View.GONE
                            roomSpinner.visibility = View.GONE
                        }
                    } else {
                        roomLabel.visibility = View.GONE
                        roomSpinner.visibility = View.GONE
                    }
                } else {
                    roomLabel.visibility = View.GONE
                    roomSpinner.visibility = View.GONE
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val dayKey = getWeekdayKey(dayIndex)
        val existingEntry = timetableData[dayKey]?.get(lesson)
        existingEntry?.let { entry ->
            val subjectIndex = subjects.indexOf(entry.subject)
            if (subjectIndex != -1) {
                subjectSpinner.setSelection(subjectIndex + 1)

                if (entry.room.isNotBlank()) {
                    subjectSpinner.onItemSelectedListener?.onItemSelected(subjectSpinner, null, subjectIndex + 1, 0)

                    val roomAdapter = roomSpinner.adapter
                    if (roomAdapter != null) {
                        for (i in 0 until roomAdapter.count) {
                            if (roomAdapter.getItem(i).toString() == entry.room) {
                                roomSpinner.setSelection(i)
                                break
                            }
                        }
                    }
                }
            }
            durationSpinner.setSelection(entry.duration - 1)
        }

        container.addView(TextView(requireContext()).apply {
            text = "Fach:"
            textSize = 14f
            setTextColor(Color.BLACK)
        })
        container.addView(subjectSpinner)
        container.addView(TextView(requireContext()).apply {
            text = "Dauer:"
            textSize = 14f
            setTextColor(Color.BLACK)
        })
        container.addView(durationSpinner)
        container.addView(roomLabel)
        container.addView(roomSpinner)

        AlertDialog.Builder(requireContext())
            .setTitle("Stundenplan bearbeiten - ${weekdays[dayIndex]} ${lesson}. Stunde")
            .setView(container)
            .setPositiveButton("Speichern") { _, _ ->
                val selectedSubject = subjectSpinner.selectedItem.toString()
                val duration = durationSpinner.selectedItemPosition + 1
                val selectedRoom = if (roomSpinner.visibility == View.VISIBLE && roomSpinner.selectedItem != null) {
                    roomSpinner.selectedItem.toString()
                } else ""

                saveTimetableEntryWithRoom(dayIndex, lesson, selectedSubject, duration, selectedRoom)
                updateCalendar()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun saveTimetableEntryWithRoom(dayIndex: Int, lesson: Int, subject: String, duration: Int, alternativeRoom: String) {
        val dayKey = getWeekdayKey(dayIndex)

        if (!timetableData.containsKey(dayKey)) {
            timetableData[dayKey] = mutableMapOf()
        }

        for (i in lesson until lesson + duration) {
            if (subject == "Kein Unterricht") {
                timetableData[dayKey]?.remove(i)
            } else {
                val (teacher, defaultRoom) = getTeacherAndRoomForSubject(subject)
                val roomToUse = alternativeRoom.takeIf { it.isNotBlank() } ?: defaultRoom
                val isUsingAlternativeRoom = alternativeRoom.isNotBlank() && alternativeRoom != defaultRoom

                timetableData[dayKey]?.set(i, TimetableEntry(
                    subject = subject,
                    duration = duration,
                    teacher = teacher,
                    room = roomToUse,
                    useAlternativeRoom = isUsingAlternativeRoom
                ))
            }
        }

        saveTimetableData()
    }

    private fun getWeekdayKey(dayIndex: Int): String {
        return "weekday_$dayIndex"
    }

    private fun getDayOfWeekIndex(date: Date): Int {
        val cal = Calendar.getInstance(Locale.GERMANY)
        cal.time = date
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            else -> 0
        }
    }

    private fun toggleEditMode() {
        isEditMode = !isEditMode

        if (isEditMode) {
            originalTimetableData = Gson().toJson(timetableData)
            editModeControls.visibility = View.VISIBLE
        } else {
            editModeControls.visibility = View.GONE
        }

        updateCalendar()
    }

    private fun saveStateForUndo() {
        val currentState = Gson().toJson(timetableData)
        undoStack.add(currentState)
        redoStack.clear()

        if (undoStack.size > 20) { // limit
            undoStack.removeAt(0)
        }
    }

    private fun performUndo() {
        if (undoStack.isNotEmpty()) {
            val currentState = Gson().toJson(timetableData)
            redoStack.add(currentState)

            val previousState = undoStack.removeAt(undoStack.size - 1)
            val type = object : TypeToken<MutableMap<String, MutableMap<Int, TimetableEntry>>>() {}.type
            timetableData = Gson().fromJson(previousState, type)
            saveTimetableData()
            updateCalendar()
        }
    }

    private fun performRedo() {
        if (redoStack.isNotEmpty()) {
            val currentState = Gson().toJson(timetableData)
            undoStack.add(currentState)

            val nextState = redoStack.removeAt(redoStack.size - 1)
            val type = object : TypeToken<MutableMap<String, MutableMap<Int, TimetableEntry>>>() {}.type
            timetableData = Gson().fromJson(nextState, type)
            saveTimetableData()
            updateCalendar()
        }
    }

    private fun cancelEdit() {
        if (originalTimetableData.isNotBlank()) {
            try {
                val type = object : TypeToken<MutableMap<String, MutableMap<Int, TimetableEntry>>>() {}.type
                timetableData = Gson().fromJson(originalTimetableData, type)
            } catch (e: Exception) {
                L.e("GalleryFragment", "Error restoring original state", e)
            }
        }

        undoStack.clear()
        redoStack.clear()
        isEditMode = false
        editModeControls.visibility = View.GONE
        updateCalendar()
    }

    private fun saveEdit() {
        saveTimetableData()
        undoStack.clear()
        redoStack.clear()
        isEditMode = false
        editModeControls.visibility = View.GONE
        updateCalendar()
        Toast.makeText(requireContext(), "Stundenplan gespeichert", Toast.LENGTH_SHORT).show()
    }

    private fun getMaxLessonsForWeek(): Int {
        var maxLessons = 8 // default max (so initial min)

        for (dayData in timetableData.values) {
            val dayMax = dayData.keys.maxOrNull() ?: 0
            if (dayMax > maxLessons) {
                maxLessons = dayMax
            }
        }

        return minOf(maxLessons + 1, 15)
    }

    private fun isPartOfConsecutiveSubjectBlock(dayTimetable: Map<Int, TimetableEntry>, startLesson: Int, currentLesson: Int, subject: String): Boolean {
        if (currentLesson < startLesson) return false

        for (lesson in startLesson..currentLesson) {
            val entry = dayTimetable[lesson]
            if (entry?.subject != subject) {
                return false
            }
        }
        return true
    }

    private fun isCurrentLesson(lesson: Int, currentTime: Calendar): Boolean {
        val dayOfWeek = currentTime.get(Calendar.DAY_OF_WEEK)
        if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) {
            return false
        }

        val lessonStartTime = lessonTimes[lesson] ?: return false
        val lessonEndTime = lessonEndTimes[lesson] ?: "23:59"

        val currentTimeStr = SimpleDateFormat("HH:mm", Locale.GERMANY).format(currentTime.time)

        val startParts = lessonStartTime.split(":")
        val endParts = lessonEndTime.split(":")
        val currentParts = currentTimeStr.split(":")

        val startMinutes = startParts[0].toInt() * 60 + startParts[1].toInt()
        val endMinutes = endParts[0].toInt() * 60 + endParts[1].toInt()
        val currentMinutes = currentParts[0].toInt() * 60 + currentParts[1].toInt()

        return currentMinutes >= startMinutes && currentMinutes < endMinutes
    }

    private fun isSameWeek(date1: Date, date2: Date): Boolean {
        val cal1 = Calendar.getInstance(Locale.GERMANY).apply {
            time = date1
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        }
        val cal2 = Calendar.getInstance(Locale.GERMANY).apply {
            time = date2
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        }
        return cal1.get(Calendar.WEEK_OF_YEAR) == cal2.get(Calendar.WEEK_OF_YEAR) &&
                cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)
    }

    private fun isSameDay(date1: Date, date2: Date): Boolean {
        val cal1 = Calendar.getInstance().apply { time = date1 }
        val cal2 = Calendar.getInstance().apply { time = date2 }

        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    private fun hasNotesForDay(date: Date): Boolean {
        val calendarInfo = calendarDataManager.getCalendarInfoForDate(date)
        return calendarInfo?.specialNote?.isNotBlank() == true ||
                calendarInfo?.isSpecialDay == true
    }

    private fun showDayDetails(date: Date) {
        val calendarInfo = calendarDataManager.getCalendarInfoForDate(date)
        val dateStr = SimpleDateFormat("EEEE, dd.MM.yyyy", Locale.GERMANY).format(date)

        val details = StringBuilder()
        details.append("Details für $dateStr\n\n")

        // vacation
        if (isDateInVacation(date)) {
            val vacationType = determineVacationType(date)
            details.append("$vacationType\n\n")
        }

        // notes
        val userNotes = getUserNotesForDate(date)
        if (userNotes.isNotBlank()) {
            details.append("Persönliche Notizen:\n$userNotes\n\n")
        }

        // user special occasions
        val userOccasions = getUserSpecialOccasionsForDate(date)
        if (userOccasions.isNotEmpty()) {
            details.append("Besondere Ereignisse (manuell hinzugefügt):\n")
            userOccasions.forEach { occasion ->
                details.append("➞ $occasion\n")
            }
            details.append("\n")
        }

        // calendar manager special occasions
        calendarInfo?.specialNote?.let { note ->
            if (note.isNotBlank()) {
                details.append("Besondere Ereignisse (aus Stundenplan):\n- $note\n\n")
            }
        }

        // homework
        try {
            val homeworkList = SlideshowFragment.getHomeworkList(requireContext())
            val dayHomework = homeworkList.filter { isSameDay(it.dueDate, date) }
            if (dayHomework.isNotEmpty()) {
                details.append("Hausaufgaben:\n")
                dayHomework.forEach { homework ->
                    var lessonText = ""

                    if (homework.lessonNumber != null && homework.lessonNumber!! > 0) {
                        lessonText = " (${homework.lessonNumber}. Stunde)"
                    } else {
                        val dayKey = getWeekdayKey(getDayOfWeekIndex(date))
                        val dayTimetable = timetableData[dayKey]

                        val firstLessonWithSubject = dayTimetable?.entries
                            ?.filter { it.value.subject == homework.subject }
                            ?.minByOrNull { it.key }?.key

                        if (firstLessonWithSubject != null) {
                            lessonText = " (${firstLessonWithSubject}. Stunde)"
                        }
                    }

                    details.append("➞ ${homework.subject}$lessonText\n")
                }
                details.append("\n")
            }
        } catch (e: Exception) {
            L.w("GalleryFragment", "Error loading homework for details", e)
        }

        // exams
        var examsAdded = false
        calendarInfo?.exams?.let { exams ->
            if (exams.isNotEmpty()) {
                details.append("Klausuren:\n")
                exams.forEach { exam ->
                    details.append("➞ ${exam.subject}\n")
                }
                details.append("\n")
                examsAdded = true
            }
        }

        val dateStr2 = SimpleDateFormat("yyyyMMdd", Locale.GERMANY).format(date)
        val examsFromManager = ExamManager.getExamsForDate(dateStr2)
        if (examsFromManager.isNotEmpty() && !examsAdded) {
            details.append("Klausuren:\n")
            examsFromManager.forEach { exam ->
                details.append("➞ ${exam.subject}\n")
            }
            details.append("\n")
        }

        // substitutes
        val dateStr3 = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(date)
        val allSubstituteEntries = SubstituteRepository.getSubstituteEntriesByDate(requireContext(), dateStr3)

        val dayKey = getWeekdayKey(getDayOfWeekIndex(date))
        val dayTimetable = timetableData[dayKey] ?: emptyMap()
        val lessonSubjectMap = dayTimetable.mapValues { it.value.subject }
        val sharedPreferences = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val userSubjects = sharedPreferences.getString("student_subjects", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

        val relevantSubstitutes = allSubstituteEntries.filter { substitute ->
            val lessonRange = substitute.stunde..(substitute.stundeBis ?: substitute.stunde)
            lessonRange.any { lesson ->
                val userSubjectForLesson = lessonSubjectMap[lesson]
                userSubjectForLesson != null && userSubjects.any { userSubject ->
                    userSubject == substitute.fach ||
                            userSubject.equals(substitute.fach, ignoreCase = true) ||
                            (userSubject.contains("-") && substitute.fach.contains("-") &&
                                    userSubject.equals(substitute.fach, ignoreCase = true))
                }
            }
        }

        if (relevantSubstitutes.isNotEmpty()) {
            details.append("Vertretungen:\n")
            relevantSubstitutes.forEach { substitute ->
                val lessonRange = if (substitute.stundeBis != null && substitute.stundeBis != substitute.stunde) {
                    "${substitute.stunde}-${substitute.stundeBis}. Stunde"
                } else {
                    "${substitute.stunde}. Stunde"
                }
                details.append("➞ $lessonRange ${substitute.fach}: ${substitute.art}\n")
            }
            details.append("\n")
        }

        if (details.length <= 50) {
            details.append("Keine besonderen Ereignisse für diesen Tag.")
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Tagesdetails")
            .setMessage(details.toString())
            .setPositiveButton("Schließen", null)
            .setNeutralButton("Bearbeiten") { _, _ ->
                showEditDayDialog(date)
            }
            .show()
    }

    private fun getSubstituteBackgroundColor(text: String): Int {
        return when {
            text.contains("Entfällt", ignoreCase = true) || text == "Auf einen anderen Termin verlegt" ->
                getColorBlindFriendlyColor("red")
            text.contains("entfällt, stattdessen", ignoreCase = true) ->
                getColorBlindFriendlyColor("green")
            text.contains("Wird vertreten", ignoreCase = true) ->
                getColorBlindFriendlyColor("green")
            text.contains("Wird betreut", ignoreCase = true) ->
                getColorBlindFriendlyColor("orange")
            else -> Color.TRANSPARENT
        }
    }

    private fun formatSubstituteText(originalText: String): String {
        return when {
            originalText == "Auf einen anderen Termin verlegt" -> "Entfällt (verlegt)"
            originalText.matches(Regex(".*entfällt, stattdessen .* in Raum .*", RegexOption.IGNORE_CASE)) -> {
                val regex = Regex(".*entfällt, stattdessen (.*) in Raum (.*)", RegexOption.IGNORE_CASE)
                val matchResult = regex.find(originalText)
                if (matchResult != null) {
                    val newSubject = matchResult.groups[1]?.value ?: ""
                    val room = matchResult.groups[2]?.value ?: ""
                    val originalSubject = originalText.substringBefore(" entfällt")
                    "$newSubject in Raum $room (statt $originalSubject)"
                } else {
                    originalText
                }
            }
            originalText.matches(Regex(".*entfällt, stattdessen .*", RegexOption.IGNORE_CASE)) &&
                    !originalText.contains("in Raum", ignoreCase = true) -> {
                val regex = Regex("(.*) entfällt, stattdessen (.*)", RegexOption.IGNORE_CASE)
                val matchResult = regex.find(originalText)
                if (matchResult != null) {
                    val originalSubject = matchResult.groups[1]?.value ?: ""
                    val newSubject = matchResult.groups[2]?.value ?: ""
                    "$newSubject (statt $originalSubject)"
                } else originalText
            }
            originalText == "Entfällt wegen Exkursion, Praktikum oder Veranstaltung" -> "Entfällt (Exk./Prak.)"
            else -> originalText.take(20)
        }
    }

    private fun showExportOptions() {
        val content = exportTimetableData()

        val options = listOf(
            Pair("Als Datei speichern", R.drawable.ic_export_file),
            Pair("In Zwischenablage kopieren", R.drawable.ic_export_clipboard)
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

        AlertDialog.Builder(requireContext())
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

    private fun showImportOptions() {
        val options = listOf(
            Pair("Aus Datei importieren", R.drawable.ic_import_file),
            Pair("Aus Zwischenablage einfügen", R.drawable.ic_import_clipboard)
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

        AlertDialog.Builder(requireContext())
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
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(intent, 1001)
        } catch (_: Exception) {
            Toast.makeText(requireContext(), "Dateimanager nicht verfügbar", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importFromClipboard() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip
        if (clipData != null && clipData.itemCount > 0) {
            val content = clipData.getItemAt(0).text.toString()
            importTimetableData(content)
        } else {
            Toast.makeText(requireContext(), "Zwischenablage ist leer", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportTimetableData(): String {
        return backupManager.exportCalendarData()
    }

    fun importTimetableData(content: String) {
        try {
            backupManager.importCalendarData(content)

            loadTimetableData()
            loadVacationData()

            updateCalendar()
            Toast.makeText(requireContext(), "Stundenplan erfolgreich importiert", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Fehler beim Importieren: ${e.message}", Toast.LENGTH_LONG).show()
            L.e("GalleryFragment", "Error importing timetable", e)
        }
    }

    private fun saveToFile(content: String) {
        try {
            val fileName = "stundenplan_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(Date())}.hksc"
            val file = File(requireContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)
            FileWriter(file).use { it.write(content) }
            Toast.makeText(requireContext(), "Datei gespeichert: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Fehler beim Speichern: ${e.message}", Toast.LENGTH_LONG).show()
            L.e("GalleryFragment", "Error saving file", e)
        }
    }

    private fun copyToClipboard(content: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Stundenplan", content)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "In Zwischenablage kopiert", Toast.LENGTH_SHORT).show()
    }

    private fun createRoundedDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = 16f
            setStroke(1, Color.parseColor("#E0E0E0"))
        }
    }

    private fun createRoundedDrawableWithBorder(fillColor: Int, borderColor: Int, borderWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = 12f
            setStroke(borderWidth, borderColor)
        }
    }

    private fun saveTimetableData() {
        try {
            val sharedPreferences = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val json = Gson().toJson(timetableData)
            sharedPreferences.edit()
                .putString("timetable_data", json)
                .apply()
            L.d("GalleryFragment", "Timetable data saved")
        } catch (e: Exception) {
            L.e("GalleryFragment", "Error saving timetable data", e)
        }
    }

    private fun loadTimetableData() {
        try {
            val sharedPreferences = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val json = sharedPreferences.getString("timetable_data", "{}")
            val type = object : TypeToken<MutableMap<String, MutableMap<Int, TimetableEntry>>>() {}.type
            val loadedData: MutableMap<String, MutableMap<Int, TimetableEntry>> = Gson().fromJson(json, type) ?: mutableMapOf()
            timetableData.clear()
            timetableData.putAll(loadedData)
            L.d("GalleryFragment", "Timetable data loaded: ${timetableData.size} days")
        } catch (e: Exception) {
            L.e("GalleryFragment", "Error loading timetable data", e)
            timetableData.clear()
        }
    }

    private fun saveVacationData() {
        try {
            val sharedPreferences = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val json = Gson().toJson(vacationWeeks)
            sharedPreferences.edit()
                .putString("vacation_data", json)
                .apply()
            L.d("GalleryFragment", "Vacation data saved")

            // Also save in homework-compatible format
            saveVacationsData()

        } catch (e: Exception) {
            L.e("GalleryFragment", "Error saving vacation data", e)
        }
    }

    private fun loadVacationData() {
        try {
            val sharedPreferences = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val json = sharedPreferences.getString("vacation_data", "{}")

            if (json == "[]" || json == "{}") {
                vacationWeeks.clear()
                return
            }

            try {
                val type = object : TypeToken<MutableMap<String, VacationWeek>>() {}.type
                val loadedData: MutableMap<String, VacationWeek> = Gson().fromJson(json, type) ?: mutableMapOf()
                vacationWeeks.clear()
                vacationWeeks.putAll(loadedData)
            } catch (_: Exception) {
                val oldType = object : TypeToken<MutableSet<String>>() {}.type
                try {
                    val oldData: MutableSet<String> = Gson().fromJson(json, oldType) ?: mutableSetOf()
                    vacationWeeks.clear()
                    oldData.forEach { weekKey ->
                        val vacationName = "Ferien" // fallback name
                        vacationWeeks[weekKey] = VacationWeek(weekKey, vacationName, VacationSource.MANUAL)
                    }
                    saveVacationData()
                } catch (migrateException: Exception) {
                    L.e("GalleryFragment", "Error migrating vacation data", migrateException)
                    vacationWeeks.clear()
                }
            }

            L.d("GalleryFragment", "Vacation data loaded: ${vacationWeeks.size} weeks")
        } catch (e: Exception) {
            L.e("GalleryFragment", "Error loading vacation data", e)
            vacationWeeks.clear()
        }
    }

    private fun getTeacherAndRoomForSubject(subject: String): Pair<String, String> {
        val sharedPreferences = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

        L.d("GalleryFragment", "Looking for subject: '$subject'")

        val subjects = sharedPreferences.getString("student_subjects", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        val teachers = sharedPreferences.getString("student_teachers", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        val rooms = sharedPreferences.getString("student_rooms", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

        L.d("GalleryFragment", "Available subjects: $subjects")
        L.d("GalleryFragment", "Available teachers: $teachers")
        L.d("GalleryFragment", "Available rooms: $rooms")

        // exact match
        var subjectIndex = subjects.indexOf(subject)

        // teacher specific
        if (subjectIndex == -1 && subject.contains("-")) {
            val baseSubject = subject.split("-")[0]
            subjectIndex = subjects.indexOfFirst { it.startsWith(baseSubject) }
            L.d("GalleryFragment", "Partial match for base '$baseSubject': index $subjectIndex")
        }

        // base subject matching
        if (subjectIndex == -1) {
            subjectIndex = subjects.indexOfFirst { storedSubject ->
                val storedBase = storedSubject.split("-")[0]
                val lookupBase = subject.split("-")[0]
                storedBase.equals(lookupBase, ignoreCase = true)
            }
            L.d("GalleryFragment", "Base subject match: index $subjectIndex")
        }

        val teacher = if (subjectIndex >= 0 && subjectIndex < teachers.size) {
            teachers[subjectIndex].takeIf { it.isNotBlank() } ?: ""
        } else ""

        val room = if (subjectIndex >= 0 && subjectIndex < rooms.size) {
            rooms[subjectIndex].takeIf { it.isNotBlank() } ?: ""
        } else ""

        L.d("GalleryFragment", "Result for '$subject': teacher='$teacher', room='$room'")

        return Pair(teacher, room)
    }

    private fun setupSearchBar() {
        searchBar = binding.root.findViewById(R.id.searchBarCalendar)

        searchBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                currentSearchQuery = s?.toString()?.trim() ?: ""
                updateCalendar()
            }
        })

        searchBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else false
        }
    }

    private fun loadRealTimePreference() {
        val sharedPreferences = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        isRealTimeEnabled = sharedPreferences.getBoolean("calendar_real_time_enabled", false)
    }

    private fun loadWeekendPreference() {
        val sharedPreferences = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        includeWeekendsInDayView = sharedPreferences.getBoolean("calendar_include_weekends_dayview", false)
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchBar.windowToken, 0)
    }

    private fun setupRealTimeUpdates() {
        if (isRealTimeEnabled) {
            realTimeUpdateHandler = Handler(Looper.getMainLooper())
            realTimeUpdateRunnable = object : Runnable {
                override fun run() {
                    val now = Calendar.getInstance()
                    val secondsUntilNextMinute = 60 - now.get(Calendar.SECOND)

                    // update if at start of a new minute
                    if (now.get(Calendar.SECOND) == 0) {
                        updateCalendar()
                    }

                    realTimeUpdateHandler?.postDelayed(this, secondsUntilNextMinute * 1000L)
                }
            }

            realTimeUpdateHandler?.post(realTimeUpdateRunnable!!)
        } else {
            stopRealTimeUpdates()
        }
    }

    private fun stopRealTimeUpdates() {
        realTimeUpdateHandler?.removeCallbacks(realTimeUpdateRunnable ?: return)
        realTimeUpdateHandler = null
        realTimeUpdateRunnable = null
        stopCurrentHighlight()
    }

    private fun stopCurrentHighlight() {
        highlightAnimator?.cancel()
        highlightAnimator = null
        currentHighlightedCell?.let { cell ->
            val calendarEntries = getCalendarEntriesForCurrentCell()
            val backgroundColor = getHighestPriorityBackgroundColor(calendarEntries)
            cell.background = if (backgroundColor != Color.TRANSPARENT) {
                createRoundedDrawable(backgroundColor)
            } else {
                createRoundedDrawable(Color.WHITE)
            }
        }
        currentHighlightedCell = null
    }

    private fun startCurrentLessonHighlight(cell: TextView) {
        stopCurrentHighlight()

        if (isDayView) return

        currentHighlightedCell = cell

        // pulsing animation
        highlightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            repeatCount = 3
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()

            addUpdateListener { animator ->
                val animatedValue = animator.animatedValue as Float
                val alpha = 0.3f + (animatedValue * 0.7f)

                val borderWidth = (8 + (animatedValue * 6)).toInt()
                val pulseColor = Color.argb(
                    (255 * alpha).toInt(),
                    Color.red(Color.YELLOW),
                    Color.green(Color.YELLOW),
                    Color.blue(Color.YELLOW)
                )

                val calendarEntries = getCalendarEntriesForCurrentCell()
                val backgroundColor = getHighestPriorityBackgroundColor(calendarEntries)

                cell.background = createRoundedDrawableWithBorder(
                    backgroundColor.takeIf { it != Color.TRANSPARENT } ?: Color.WHITE,
                    pulseColor,
                    borderWidth
                )
            }

            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // reset to normal after anim end
                    currentHighlightedCell?.let { cell ->
                        val calendarEntries = getCalendarEntriesForCurrentCell()
                        val backgroundColor = getHighestPriorityBackgroundColor(calendarEntries)
                        cell.background = createRoundedDrawableWithBorder(
                            backgroundColor.takeIf { it != Color.TRANSPARENT } ?: Color.WHITE,
                            Color.YELLOW,
                            12
                        )
                    }
                    currentHighlightedCell = null
                    highlightAnimator = null
                }
            })
        }

        try {
            highlightAnimator?.start()
        } catch (_: Exception) {
            // fallback static border
            val calendarEntries = getCalendarEntriesForCurrentCell()
            val backgroundColor = getHighestPriorityBackgroundColor(calendarEntries)
            cell.background = createRoundedDrawableWithBorder(
                backgroundColor.takeIf { it != Color.TRANSPARENT } ?: Color.WHITE,
                Color.YELLOW,
                12
            )
        }
    }

    private fun getCalendarEntriesForCurrentCell(): List<CalendarEntry> {
        // helper method to get calendar entries for current cell
        return emptyList() // fallback
    }

    private fun setupNavigationButtons() {
        binding.root.findViewById<Button>(R.id.btnPreviousWeek).setOnClickListener {
            if (isDayView) {
                if (includeWeekendsInDayView) {
                    currentDayOffset--
                    if (currentDayOffset < 0) {
                        currentWeekStart.add(Calendar.WEEK_OF_YEAR, -1)
                        currentDayOffset = 6
                    }
                } else {
                    currentDayOffset--
                    if (currentDayOffset < 0) {
                        currentWeekStart.add(Calendar.WEEK_OF_YEAR, -1)
                        currentDayOffset = 4
                    }
                }
            } else {
                currentWeekStart.add(Calendar.WEEK_OF_YEAR, -1)
            }
            updateCalendar()
        }

        binding.root.findViewById<Button>(R.id.btnNextWeek).setOnClickListener {
            if (isDayView) {
                if (includeWeekendsInDayView) {
                    currentDayOffset++
                    if (currentDayOffset > 6) {
                        currentWeekStart.add(Calendar.WEEK_OF_YEAR, 1)
                        currentDayOffset = 0
                    }
                } else {
                    currentDayOffset++
                    if (currentDayOffset > 4) {
                        currentWeekStart.add(Calendar.WEEK_OF_YEAR, 1)
                        currentDayOffset = 0
                    }
                }
            } else {
                currentWeekStart.add(Calendar.WEEK_OF_YEAR, 1)
            }
            updateCalendar()
        }
    }

    private fun identifyFreePeriods() {
        for ((_, dayTimetable) in timetableData) {
            val lessons = dayTimetable.toSortedMap()

            if (lessons.isEmpty()) continue

            val firstLesson = lessons.firstKey()
            val lastNonEmptyLesson = lessons.keys.filter { lesson ->
                lessons[lesson]?.subject?.isNotBlank() == true
            }.maxOrNull() ?: continue

            // Mark free periods between first and last lesson
            for (lessonNum in firstLesson..lastNonEmptyLesson) {
                val entry = lessons[lessonNum]
                if (entry == null || entry.subject.isBlank()) {
                    // freistunde
                    dayTimetable[lessonNum] = TimetableEntry(
                        subject = "Freistunde",
                        duration = 1,
                        isBreak = false,
                        teacher = "",
                        room = ""
                    )
                }
            }
        }
    }

    private fun cleanDisplayString(input: String): String {
        return input.replace("UNKNOWN", "")
            .replace("Unbekannt", "")
            .trim()
            .takeIf { it.isNotBlank() } ?: ""
    }

    private fun formatTeacherRoomDisplay(teacher: String, room: String): String {
        val cleanTeacher = cleanDisplayString(teacher)
        val cleanRoom = cleanDisplayString(room)

        return listOf(cleanTeacher, cleanRoom)
            .filter { it.isNotBlank() }
            .joinToString(" | ")
    }

    private fun loadColorBlindSettings() {
        val sharedPreferences = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        colorBlindMode = sharedPreferences.getString("colorblind_mode", "none") ?: "none"
        L.d("GalleryFragment", "Colorblind mode loaded: $colorBlindMode")
    }

    private fun getColorBlindFriendlyColor(originalColor: String): Int {
        return when (colorBlindMode) {
            "protanopia" -> when (originalColor) {
                "red" -> ContextCompat.getColor(requireContext(), android.R.color.holo_orange_dark)
                "green" -> ContextCompat.getColor(requireContext(), android.R.color.holo_blue_light)
                "orange" -> ContextCompat.getColor(requireContext(), android.R.color.darker_gray)
                else -> Color.TRANSPARENT
            }
            "deuteranopia" -> when (originalColor) {
                "red" -> ContextCompat.getColor(requireContext(), android.R.color.holo_orange_dark)
                "green" -> ContextCompat.getColor(requireContext(), android.R.color.holo_blue_light)
                "orange" -> ContextCompat.getColor(requireContext(), android.R.color.darker_gray)
                else -> Color.TRANSPARENT
            }
            "tritanopia" -> when (originalColor) {
                "red" -> ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark)
                "green" -> ContextCompat.getColor(requireContext(), android.R.color.holo_blue_dark)
                "orange" -> ContextCompat.getColor(requireContext(), android.R.color.holo_blue_light)
                else -> Color.TRANSPARENT
            }
            else -> when (originalColor) { // normal
                "red" -> ContextCompat.getColor(requireContext(), android.R.color.holo_red_light)
                "green" -> ContextCompat.getColor(requireContext(), android.R.color.holo_green_light)
                "orange" -> ContextCompat.getColor(requireContext(), android.R.color.holo_orange_light)
                else -> Color.TRANSPARENT
            }
        }
    }

    private fun saveHolidaysData() {
        try {
            val sharedPreferences = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val holidaysArray = JSONArray()

            val processedDates = mutableSetOf<String>()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY)

            val checkDate = Calendar.getInstance()
            checkDate.add(Calendar.YEAR, -1)
            val endDate = Calendar.getInstance()
            endDate.add(Calendar.YEAR, 2)

            while (!checkDate.after(endDate)) {
                val dateStr = dateFormat.format(checkDate.time)

                if (!processedDates.contains(dateStr)) {
                    val userOccasions = getUserSpecialOccasionsForDate(checkDate.time)
                    val calendarInfo = calendarDataManager.getCalendarInfoForDate(checkDate.time)

                    var isHoliday = false
                    var holidayName = ""

                    userOccasions.forEach { occasion ->
                        if (occasion.contains("Feiertag", ignoreCase = true) ||
                            occasion.contains("Ferientag", ignoreCase = true)) {
                            isHoliday = true
                            holidayName = occasion
                        }
                    }

                    calendarInfo?.let { info ->
                        if (info.isSpecialDay && info.specialNote.isNotBlank()) {
                            if (info.specialNote.contains("Feiertag", ignoreCase = true) ||
                                info.specialNote.contains("Ferientag", ignoreCase = true)) {
                                isHoliday = true
                                if (holidayName.isEmpty()) holidayName = info.specialNote
                            }
                        }
                    }

                    if (isHoliday) {
                        val holidayObject = JSONObject().apply {
                            put("date", dateStr)
                            put("name", holidayName.ifEmpty { "Feiertag" })
                        }
                        holidaysArray.put(holidayObject)
                        processedDates.add(dateStr)
                    }
                }

                checkDate.add(Calendar.DAY_OF_YEAR, 1)
            }

            sharedPreferences.edit()
                .putString("holidays_data", holidaysArray.toString())
                .apply()

            L.d("GalleryFragment", "Holidays data saved: ${holidaysArray.length()} holidays")

        } catch (e: Exception) {
            L.e("GalleryFragment", "Error saving holidays data", e)
        }
    }

    private fun saveVacationsData() {
        try {
            val sharedPreferences = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val vacationsArray = JSONArray()

            mutableListOf<Pair<Date, Date>>()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY)

            val sortedVacationWeeks = vacationWeeks.keys.sorted()
            if (sortedVacationWeeks.isNotEmpty()) {
                var currentStart: Date? = null
                var currentEnd: Date? = null
                var currentName = ""

                for (weekKey in sortedVacationWeeks) {
                    val weekStart = dateFormat.parse(weekKey)
                    val weekEnd = Calendar.getInstance().apply {
                        if (weekStart != null) {
                            time = weekStart
                        }
                        add(Calendar.DAY_OF_YEAR, 6) // sunday
                    }.time

                    val vacationWeek = vacationWeeks[weekKey]
                    val vacationName = vacationWeek?.name ?: "Ferien"

                    if (currentStart == null) {
                        currentStart = weekStart
                        currentEnd = weekEnd
                        currentName = vacationName
                    } else {
                        val expectedNextWeek = Calendar.getInstance().apply {
                            if (currentEnd != null) {
                                time = currentEnd
                            }
                            add(Calendar.DAY_OF_YEAR, 1)
                        }.time

                        if (weekStart != null) {
                            if (abs(weekStart.time - expectedNextWeek.time) <= 24 * 60 * 60 * 1000 &&
                                vacationName == currentName) {
                                currentEnd = weekEnd
                            } else {
                                val vacationObject = JSONObject().apply {
                                    put("start_date", dateFormat.format(currentStart))
                                    put("end_date", dateFormat.format(currentEnd))
                                    put("name", currentName)
                                }
                                vacationsArray.put(vacationObject)

                                currentStart = weekStart
                                currentEnd = weekEnd
                                currentName = vacationName
                            }
                        }
                    }
                }

                if (currentStart != null && currentEnd != null) {
                    val vacationObject = JSONObject().apply {
                        put("start_date", dateFormat.format(currentStart))
                        put("end_date", dateFormat.format(currentEnd))
                        put("name", currentName)
                    }
                    vacationsArray.put(vacationObject)
                }
            }

            sharedPreferences.edit()
                .putString("vacations_data", vacationsArray.toString())
                .apply()

            L.d("GalleryFragment", "Vacations data saved: ${vacationsArray.length()} vacation periods")

        } catch (e: Exception) {
            L.e("GalleryFragment", "Error saving vacations data", e)
        }
    }

    private fun setupSwipeGestures() {
        val swipeInterceptor = binding.root.findViewById<SwipeInterceptorLayout>(R.id.swipeInterceptor)

        swipeInterceptor.swipeListener = object : SwipeInterceptorLayout.SwipeListener {
            override fun onSwipeLeft() {
                navigateToNext()
            }

            override fun onSwipeRight() {
                navigateToPrevious()
            }

            override fun onSwipeProgress(progress: Float, isRightSwipe: Boolean) {
                showSwipeIndicator(isRightSwipe, progress)
            }

            override fun onSwipeEnd() {
                hideSwipeIndicator()
            }
        }
    }

    private fun navigateToPrevious() {
        if (isDayView) {
            if (includeWeekendsInDayView) {
                currentDayOffset--
                if (currentDayOffset < 0) {
                    currentWeekStart.add(Calendar.WEEK_OF_YEAR, -1)
                    currentDayOffset = 6 // sunday
                }
            } else {
                currentDayOffset--
                if (currentDayOffset < 0) {
                    currentWeekStart.add(Calendar.WEEK_OF_YEAR, -1)
                    currentDayOffset = 4 // friday
                }
            }
        } else {
            currentWeekStart.add(Calendar.WEEK_OF_YEAR, -1)
        }
        updateCalendar()
    }

    private fun navigateToNext() {
        if (isDayView) {
            if (includeWeekendsInDayView) {
                currentDayOffset++
                if (currentDayOffset > 6) {
                    currentWeekStart.add(Calendar.WEEK_OF_YEAR, 1)
                    currentDayOffset = 0 // monday
                }
            } else {
                currentDayOffset++
                if (currentDayOffset > 4) {
                    currentWeekStart.add(Calendar.WEEK_OF_YEAR, 1)
                    currentDayOffset = 0 // monday
                }
            }
        } else {
            currentWeekStart.add(Calendar.WEEK_OF_YEAR, 1)
        }
        updateCalendar()
    }

    private fun showSwipeIndicator(isRightSwipe: Boolean, progress: Float) {
        if (swipeIndicatorView == null) {
            createSwipeIndicator()
        }

        swipeIndicatorView?.let { indicator ->
            val scaledProgress = min(progress, 1f)
            val targetAlpha = min(scaledProgress * 1.2f, 0.95f)

            indicator.alpha = targetAlpha
            indicator.visibility = View.VISIBLE

            val textView = indicator as TextView

            val text = if (isRightSwipe) {
                if (isDayView) "Vorheriger Tag" else "Vorherige Woche"
            } else {
                if (isDayView) "Nächster Tag" else "Nächste Woche"
            }

            textView.text = text

            val arrowIcon = if (isRightSwipe) {
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_arrow_back)
            } else {
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_arrow_forward)
            }

            arrowIcon?.setTint(Color.parseColor("#0f5293"))

            if (isRightSwipe) {
                textView.setCompoundDrawablesWithIntrinsicBounds(arrowIcon, null, null, null)
            } else {
                textView.setCompoundDrawablesWithIntrinsicBounds(null, null, arrowIcon, null)
            }

            textView.compoundDrawablePadding = 16

            val scale = 0.7f + (scaledProgress * 0.3f)
            indicator.scaleX = scale
            indicator.scaleY = scale

            val rotation = if (isRightSwipe) -scaledProgress * 3f else scaledProgress * 3f
            indicator.rotation = rotation

            positionSwipeIndicatorSided(isRightSwipe)
        }
    }

    private fun positionSwipeIndicatorSided(isRightSwipe: Boolean) {
        swipeIndicatorView?.let { indicator ->
            indicator.post {
                val calendarArea = binding.root.findViewById<SwipeInterceptorLayout>(R.id.swipeInterceptor)

                val calendarWidth = calendarArea.width
                val calendarHeight = calendarArea.height

                val centerY = (calendarHeight - indicator.height) / 2 // center vertically

                val horizontalMargin = 60
                val finalX = if (isRightSwipe) {
                    horizontalMargin // previous -> left side
                } else {
                    calendarWidth - indicator.width - horizontalMargin // next -> right side
                }

                val layoutParams = indicator.layoutParams as ViewGroup.MarginLayoutParams
                layoutParams.setMargins(finalX, centerY, 0, 0)
                indicator.layoutParams = layoutParams
            }
        }
    }

    private fun createSwipeIndicator() {
        swipeIndicatorView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
        }

        val calendarContainer = binding.root.findViewById<SwipeInterceptorLayout>(R.id.swipeInterceptor)

        val indicator = TextView(requireContext()).apply {
            textSize = 18f
            setTextColor(Color.parseColor("#0f5293"))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(40, 20, 40, 20)
            alpha = 0f
            elevation = 30f
            isClickable = false
            isFocusable = false

            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#F0FFFFFF"))
                cornerRadius = 35f
                setStroke(4, Color.parseColor("#0f5293"))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    elevation = 15f
                }
            }

            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        calendarContainer.addView(indicator)
        swipeIndicatorView = indicator
    }

    private fun hideSwipeIndicator() {
        swipeIndicatorView?.let { indicator ->
            indicator.animate()
                .alpha(0f)
                .scaleX(0.8f)
                .scaleY(0.8f)
                .rotation(0f)
                .setDuration(200)
                .withEndAction {
                    indicator.visibility = View.GONE
                }
                .start()
        }
    }

    private fun setupAlternativeSwipeGestures() {
        gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false

                val deltaX = e2.x - e1.x
                val deltaY = e2.y - e1.y

                if (abs(deltaX) > 50 && abs(velocityX) > 200) {
                    if (abs(deltaX) > abs(deltaY) * 0.5f) {
                        if (deltaX > 0) {
                            navigateToPrevious()
                        } else {
                            navigateToNext()
                        }
                        return true
                    }
                }
                return false
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (e1 == null) return false

                val deltaX = e2.x - e1.x
                val deltaY = e2.y - e1.y

                if (abs(deltaX) > 30 && abs(deltaX) > abs(deltaY) * 0.4f) {
                    val progress = min(abs(deltaX) / 120f, 1f)
                    showSwipeIndicator(deltaX > 0, progress)
                    return true
                }
                return false
            }
        })

        binding.root.findViewById<SwipeInterceptorLayout>(R.id.swipeInterceptor).setOnTouchListener { _, event ->
            gestureDetector?.onTouchEvent(event) ?: false
        }
    }

    private fun setupWeekButtons() {
        val prevButton = binding.root.findViewById<Button>(R.id.btnPreviousWeek)
        val nextButton = binding.root.findViewById<Button>(R.id.btnNextWeek)

        fun setArrow(button: Button, iconRes: Int, sizeDp: Int, paddingDp: Int) {
            val drawable = AppCompatResources.getDrawable(requireContext(), iconRes)
            val sizePx = (sizeDp * resources.displayMetrics.density).toInt()
            drawable?.setBounds(0, 0, sizePx, sizePx)

            button.text = ""
            button.setCompoundDrawables(
                if (iconRes == R.drawable.ic_arrow_left) drawable else null,
                null,
                if (iconRes == R.drawable.ic_arrow_right) drawable else null,
                null
            )
            button.compoundDrawablePadding = (-paddingDp * resources.displayMetrics.density).toInt()
        }

        setArrow(prevButton, R.drawable.ic_arrow_left, sizeDp = 32, paddingDp = 0)
        prevButton.contentDescription = "Vorherige Woche"

        setArrow(nextButton, R.drawable.ic_arrow_right, sizeDp = 32, paddingDp = 0)
        nextButton.contentDescription = "Nächste Woche"
    }

    override fun onResume() {
        super.onResume()
        loadRealTimePreference()
        loadWeekendPreference()
        loadColorBlindSettings()

        setupRealTimeUpdates()

        updateCalendar()
    }

    override fun onPause() {
        super.onPause()
        stopRealTimeUpdates()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopRealTimeUpdates()
        hideSwipeIndicator()
        gestureDetector = null
        _binding = null
    }
}