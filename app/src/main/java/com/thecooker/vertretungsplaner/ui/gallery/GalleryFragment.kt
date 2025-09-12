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
import android.text.Html
import android.util.AttributeSet
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
import androidx.core.view.isNotEmpty
import androidx.core.view.isEmpty
import androidx.core.graphics.toColorInt
import androidx.core.content.edit
import androidx.core.view.isVisible

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

    object InternalConstants {
        const val FREE_LESSON = "FREE_LESSON"
        const val NO_SCHOOL = "NO_SCHOOL"
        const val HOLIDAY_TYPE_AUTUMN = "AUTUMN_BREAK"
        const val HOLIDAY_TYPE_WINTER = "WINTER_BREAK"
        const val HOLIDAY_TYPE_EASTER = "EASTER_BREAK"
        const val HOLIDAY_TYPE_SUMMER = "SUMMER_BREAK"
        const val HOLIDAY_TYPE_WHITSUN = "WHITSUN_BREAK"
        const val HOLIDAY_TYPE_SPRING = "SPRING_BREAK"
        const val HOLIDAY_TYPE_GENERIC = "VACATION"
        const val HOLIDAY_GENERAL = "HOLIDAY"
    }

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

    private val weekdays by lazy {
        listOf(getString(R.string.monday), getString(R.string.tuesday), getString(R.string.wednesday), getString(R.string.thursday), getString(R.string.friday))
    }
    private val weekdaysShort by lazy {
        listOf(getString(R.string.monday_short), getString(R.string.tuesday_short), getString(R.string.wednesday_short), getString(R.string.thursday_short), getString(R.string.friday_short))
    }

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

    private val examColor = "#9C27B0".toColorInt()

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
            text = getString(R.string.gall_calendar_loading)
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
            text = getString(R.string.gall_error_tap_to_retry)
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
            text = if (isDayView) getString(R.string.frag_gal_btn_edit) else getString(R.string.frag_gal_btn_edit_day)
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
            binding.root.findViewById<Button>(R.id.btnEditCalendar).text = getString(R.string.frag_gal_btn_edit)
            stopCurrentHighlight()
        } else {
            binding.root.findViewById<Button>(R.id.btnEditCalendar).text = getString(R.string.frag_gal_btn_edit_day)
        }
        saveViewPreference()
        updateCalendar()
    }

    private fun saveViewPreference() {
        val sharedPreferences = requireContext().getSharedPreferences(
            "AppPrefs",
            Context.MODE_PRIVATE
        )
        sharedPreferences.edit {
            putBoolean("calendar_day_view", isDayView)
        }
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

        popupMenu.menu.add(0, R.id.action_today, 0, getString(R.string.gall_today)).apply {
            setIcon(R.drawable.ic_today)
        }
        popupMenu.menu.add(0, R.id.action_edit_timetable, 1, getString(R.string.act_set_edit_timetable)).apply {
            setIcon(R.drawable.ic_pencil)
        }
        popupMenu.menu.add(0, R.id.action_vacation, 2, getString(R.string.gall_mark_vacations)).apply {
            setIcon(R.drawable.ic_vacation)
        }
        popupMenu.menu.add(0, R.id.action_statistics, 3, getString(R.string.gall_statistics)).apply {
            setIcon(R.drawable.ic_statistics)
        }
        popupMenu.menu.add(0, R.id.action_export, 4, getString(R.string.act_set_export)).apply {
            setIcon(R.drawable.ic_export)
        }
        popupMenu.menu.add(0, R.id.action_import, 5, getString(R.string.act_set_import)).apply {
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
            appendLine(getString(R.string.gall_next_events))
            appendLine(getString(R.string.gall_next_exam, (if (stats.daysUntilNextExam == -1) getString(R.string.gall_none_planned) else "${stats.daysUntilNextExam} Tage")))
            appendLine(getString(R.string.gall_next_homework, (if (stats.daysUntilNextHomework == -1) getString(R.string.gall_none_planned) else "${stats.daysUntilNextHomework} Tage")))
            appendLine(getString(R.string.gall_next_vacation, (if (stats.daysUntilNextVacation == -1) getString(R.string.gall_none_planned) else "${stats.daysUntilNextVacation} Tage")))
            appendLine(getString(R.string.gall_next_holiday, (if (stats.daysUntilNextHoliday == -1) getString(R.string.gall_none_planned) else "${stats.daysUntilNextHoliday} Tage")))
            appendLine()

            appendLine(getString(R.string.gall_next_30_days)) // this month
            appendLine(getString(R.string.gall_exams_count, stats.examsThisMonth))
            appendLine(getString(R.string.gall_homework_count, stats.homeworkThisMonth))
            appendLine(getString(R.string.gall_holidays_count, stats.holidaysThisMonth))
            appendLine(getString(R.string.gall_vacation_days_count, stats.vacationDaysThisMonth))
            appendLine()

            appendLine(getString(R.string.gall_total_future)) // != aktuelles halbjahr
            appendLine(getString(R.string.gall_total_exams, stats.totalExams))
            appendLine(getString(R.string.gall_total_homework, stats.totalHomework))
            appendLine(getString(R.string.gall_total_vacation_days, stats.totalVacationDays))
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.gall_statistics))
            .setMessage(message)
            .setPositiveButton(getString(R.string.gall_close), null)
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
                    1 -> getString(R.string.gall_one_weeks)
                    else -> getString(R.string.gall_multiple_weeks, it)
                }
            }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        val mainDialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.gall_manage_vacation))
            .setMessage(getString(R.string.gall_mark_remove_vacation))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.gall_mark)) { dialog, _ ->
                val weeksCount = weeksSpinner.selectedItemPosition + 1
                if (removeSwitch.isChecked) {
                    removeVacationWeeks(weeksCount)
                } else {
                    markVacationWeeks(weeksCount)
                }
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .create()

        removeSwitch.setOnCheckedChangeListener { _, isChecked ->
            val positiveButton = mainDialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.text = if (isChecked) getString(R.string.gall_remove) else getString(R.string.gall_mark)
        }

        clearAllButton.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.gall_clear_all))
                .setMessage(getString(R.string.gall_vecation_delete_confirm))
                .setPositiveButton(getString(R.string.slide_yes)) { innerDialog, _ ->
                    clearAllVacations()
                    innerDialog.dismiss()
                    mainDialog.dismiss()
                }
                .setNegativeButton(getString(R.string.slide_no)) { innerDialog, _ ->
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
            getString(R.string.gall_vacations_cleared, vacationCount),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun fetchAndMarkHessenVacations(schoolYear: String, clearExisting: Boolean) {
        val progressDialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.gall_loading_vacation_data))
            .setMessage(getString(R.string.gall_loading_vacation_kultus))
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
                            getString(R.string.gall_couldnt_find_vacation_data, schoolYear),
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
                        getString(R.string.gall_successfully_marked_vacations, schoolYear, markedWeeks),
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    L.e("GalleryFragment", "Error fetching vacation data", e)
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.gall_error_while_loading_vacation_data, e.message),
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
            text = getString(R.string.gall_clear_existing)
            isChecked = true
            setPadding(0, 16, 0, 16)
        }

        val privacyNotice = TextView(requireContext()).apply {
            text = getString(R.string.gall_privacy_notice)
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 16, 0, 16)
        }

        container.addView(TextView(requireContext()).apply {
            text = getString(R.string.gall_select_school_year)
            textSize = 14f
            setTextColor(Color.BLACK)
        })
        container.addView(yearSpinner)
        container.addView(clearExistingCheckbox)
        container.addView(privacyNotice)

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.gall_auto_mark))
            .setMessage(getString(R.string.gall_load_from_kultus))
            .setView(container)
            .setPositiveButton(getString(R.string.gall_load)) { dialog, _ ->
                val selectedSchoolYear = schoolYears[yearSpinner.selectedItemPosition]
                val clearExisting = clearExistingCheckbox.isChecked
                fetchAndMarkHessenVacations(selectedSchoolYear, clearExisting)
                dialog.dismiss()
                onComplete()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
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
                    vacationType.contains("Herbst", ignoreCase = true) -> getString(R.string.gall_autumn_break)
                    vacationType.contains("Weihnacht", ignoreCase = true) ||
                            vacationType.contains("Winter", ignoreCase = true) -> getString(R.string.gall_winter_break)
                    vacationType.contains("Oster", ignoreCase = true) -> getString(R.string.gall_easter_break)
                    vacationType.contains("Sommer", ignoreCase = true) -> getString(R.string.gall_summer_break)
                    vacationType.contains("Pfingst", ignoreCase = true) -> getString(R.string.gall_whitesun_break)
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
            getString(R.string.gall_vacations_removed, removedCount, weeksCount),
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
            getString(R.string.gall_vacations_marked, vacationType, weeksCount),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun determineVacationType(date: Date): String {
        val calendar = Calendar.getInstance().apply { time = date }
        val month = calendar.get(Calendar.MONTH)

        return when (month) {
            Calendar.FEBRUARY, Calendar.MARCH, Calendar.APRIL -> {
                if (month == Calendar.MARCH && calendar.get(Calendar.DAY_OF_MONTH) > 15) InternalConstants.HOLIDAY_TYPE_EASTER
                else if (month == Calendar.APRIL && calendar.get(Calendar.DAY_OF_MONTH) < 20) InternalConstants.HOLIDAY_TYPE_EASTER
                else InternalConstants.HOLIDAY_TYPE_SPRING
            }
            Calendar.JUNE, Calendar.JULY, Calendar.AUGUST -> {
                if (month == Calendar.JUNE && calendar.get(Calendar.DAY_OF_MONTH) > 20) InternalConstants.HOLIDAY_TYPE_SUMMER
                else if (month == Calendar.JULY) InternalConstants.HOLIDAY_TYPE_SUMMER
                else if (month == Calendar.AUGUST && calendar.get(Calendar.DAY_OF_MONTH) < 15) InternalConstants.HOLIDAY_TYPE_SUMMER
                else InternalConstants.HOLIDAY_TYPE_GENERIC
            }
            Calendar.OCTOBER -> InternalConstants.HOLIDAY_TYPE_AUTUMN
            Calendar.DECEMBER, Calendar.JANUARY -> {
                if (month == Calendar.DECEMBER && calendar.get(Calendar.DAY_OF_MONTH) > 15) InternalConstants.HOLIDAY_TYPE_WINTER
                else if (month == Calendar.JANUARY && calendar.get(Calendar.DAY_OF_MONTH) < 15) InternalConstants.HOLIDAY_TYPE_WINTER
                else InternalConstants.HOLIDAY_TYPE_GENERIC
            }
            else -> InternalConstants.HOLIDAY_TYPE_GENERIC
        }
    }

    private fun translateVacationType(internalType: String): String { // suggested to use this
        return when (internalType) {
            InternalConstants.HOLIDAY_TYPE_AUTUMN -> getString(R.string.gall_autumn_break)
            InternalConstants.HOLIDAY_TYPE_WINTER -> getString(R.string.gall_winter_break)
            InternalConstants.HOLIDAY_TYPE_EASTER -> getString(R.string.gall_easter_break)
            InternalConstants.HOLIDAY_TYPE_SUMMER -> getString(R.string.gall_summer_break)
            InternalConstants.HOLIDAY_TYPE_WHITSUN -> getString(R.string.gall_whitesun_break)
            InternalConstants.HOLIDAY_TYPE_SPRING -> getString(R.string.gall_spring_break)
            InternalConstants.HOLIDAY_GENERAL -> getString(R.string.gall_holiday)
            else -> getString(R.string.gall_vacation)
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
                (1..53).map { getString(R.string.gall_week_number, it) }).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }

        val currentYear = currentWeekStart.get(Calendar.YEAR)
        val currentWeek = currentWeekStart.get(Calendar.WEEK_OF_YEAR)

        yearSpinner.setSelection((2024..2026).indexOf(currentYear))
        weekSpinner.setSelection(currentWeek - 1)

        container.addView(TextView(requireContext()).apply {
            text = getString(R.string.gall_year)
            textSize = 14f
            setTextColor(Color.BLACK)
        })
        container.addView(yearSpinner)
        container.addView(TextView(requireContext()).apply {
            text = getString(R.string.gall_calendar_week)
            textSize = 14f
            setTextColor(Color.BLACK)
        })
        container.addView(weekSpinner)

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.gall_select_calendar_week))
            .setView(container)
            .setPositiveButton(getString(R.string.exam_ok)) { _, _ ->
                val selectedYear = 2024 + yearSpinner.selectedItemPosition
                val selectedWeek = weekSpinner.selectedItemPosition + 1

                currentWeekStart.set(Calendar.YEAR, selectedYear)
                currentWeekStart.set(Calendar.WEEK_OF_YEAR, selectedWeek)
                currentWeekStart.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                updateCalendar()
            }
            .setNegativeButton(getString(R.string.cancel), null)
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
            text = getString(R.string.gall_year)
            textSize = 14f
            setTextColor(Color.BLACK)
        })
        container.addView(yearSpinner)
        container.addView(TextView(requireContext()).apply {
            text = getString(R.string.gall_month)
            textSize = 14f
            setTextColor(Color.BLACK)
        })
        container.addView(monthSpinner)
        container.addView(TextView(requireContext()).apply {
            text = getString(R.string.gall_day)
            textSize = 14f
            setTextColor(Color.BLACK)
        })
        container.addView(daySpinner)

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.gall_select_day))
            .setView(container)
            .setPositiveButton(getString(R.string.exam_ok)) { _, _ ->
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
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun updateCalendar() {
        updateWeekDisplay()
        buildCalendarGrid()
        setupColorLegend()
    }

    private fun updateWeekDisplay() = if (isDayView) {
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
        currentWeekTextView.text = getString(R.string.gall_week_text_view, startDay, endDay, monthYear, weekNumber)
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

        startInitialHighlighting()
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
        val timeHeader = createStyledCell(getString(R.string.galL_hour_single), isHeader = true, isLessonColumn = true, isDayView = true)
        timeHeader.setOnClickListener { showAllLessonTimes() }
        headerRow.addView(timeHeader)

        // day header
        val currentDay = Calendar.getInstance().apply {
            time = currentWeekStart.time
            add(Calendar.DAY_OF_WEEK, currentDayOffset)
        }

        val dayName = when (currentDayOffset) {
            0 -> getString(R.string.monday)
            1 -> getString(R.string.tuesday)
            2 -> getString(R.string.wednesday)
            3 -> getString(R.string.thursday)
            4 -> getString(R.string.friday)
            5 -> getString(R.string.saturday)
            6 -> getString(R.string.sunday)
            else -> getString(R.string.unknown)
        }

        val dayDate = SimpleDateFormat("dd.MM", Locale.GERMANY).format(currentDay.time)
        val dayHeader = createStyledCell("$dayName\n$dayDate", isHeader = true, isDayView = true)

        // check for today and override styling after createStyledCell
        val today = Calendar.getInstance()
        val isToday = isSameDay(currentDay.time, today.time)

        if (isToday) {
            dayHeader.background = createRoundedDrawable("#FFC107".toColorInt())
            dayHeader.setTextColor(Color.BLACK)
        }

        // check for notes and mark with asterisk
        if (hasNotesForDay(currentDay.time) || shouldShowAsterisk(currentDay.time)) {
            "${dayHeader.text}*".also { dayHeader.text = it }
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
                createDayBreakRow(breakMinutes)
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

            timeCell.setOnClickListener { showLessonTimeDetails(lesson) }
            lessonRow.addView(timeCell)

            // day column
            val dayCell = createDayLessonCell(currentDayOffset, lesson, isCurrentDay, currentTime)
            lessonRow.addView(dayCell)

            calendarGrid.addView(lessonRow)
        }
    }

    private fun createDayBreakRow(breakMinutes: Int) {
        val breakRow = TableRow(requireContext()).apply {
            layoutParams = TableLayout.LayoutParams(
                TableLayout.LayoutParams.MATCH_PARENT,
                TableLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val breakView = TextView(requireContext()).apply {
            text = getString(R.string.gall_break_with_minutes, breakMinutes)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
            setTextColor(Color.BLACK)

            background = createRoundedDrawable(Color.LTGRAY)

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
            cellText = if (shouldMarkAsHoliday) getString(R.string.gall_holiday) else getString(R.string.gall_free_from_4)
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

            val displaySubject = translateSubjectForDisplay(timetableEntry.subject)

            val mainInfo = if (teacherRoomDisplay.isNotBlank()) {
                "$displaySubject | $teacherRoomDisplay"
            } else {
                displaySubject
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
            var isCancelled = false

            calendarEntries.forEach { entry ->
                when (entry.type) {
                    EntryType.HOMEWORK -> additionalInfo.add(getString(R.string.gall_homework_single))
                    EntryType.EXAM -> additionalInfo.add(getString(R.string.gall_exam_single))
                    EntryType.SUBSTITUTE -> {
                        if (entry.content.contains("statt", ignoreCase = true)) {
                            hasSubstitute = true
                            substituteText = entry.content
                        } else {
                            additionalInfo.add(entry.content)
                            if (entry.content.contains("Entfällt", ignoreCase = true) &&
                                entry.backgroundColor == getColorBlindFriendlyColor("red")) {
                                isCancelled = true
                            }
                        }
                    }
                    EntryType.SPECIAL_DAY -> {
                        if (entry.content.contains("Feiertag", ignoreCase = true)) {
                            additionalInfo.add(getString(R.string.gall_holiday))
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

                        // strikethrough if cancelled
                        val subjectHtml = if (isCancelled) "<s><b>$subject</b></s>" else "<b>$subject</b>"
                        "$subjectHtml$restOfFirstLine"
                    } else {
                        // strikethrough if cancelled
                        if (isCancelled) "<s><b>$firstLine</b></s>" else "<b>$firstLine</b>"
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
        } else if (timetableEntry?.subject == "FREE_LESSON") {
            cell.alpha = 0.6f
        } else {
            cell.alpha = 1.0f
        }

        // search filter
        val baseOpacity = if (timetableEntry?.subject == getString(R.string.slide_free_lesson)) 0.6f else 1.0f

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

        // check for current lesson highlight
        if (isCurrentDay) {
            val isCurrentLessonTime = isCurrentLesson(lesson, currentTime)
            val isCurrentBreakTime = isCurrentBreakTime(lesson - 1, currentTime)

            if ((isCurrentLessonTime && timetableEntry != null) || isCurrentBreakTime) {
                startCurrentLessonHighlight(cell, backgroundColor)
            } else if (backgroundColor != Color.TRANSPARENT) {
                val drawable = createRoundedDrawable(backgroundColor)
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

    private fun showLessonTimeDetails(lesson: Int) {
        val startTime = lessonTimes[lesson] ?: getString(R.string.unknown)
        val endTime = lessonEndTimes[lesson] ?: getString(R.string.unknown)
        val breakAfter = breakTimes[lesson]

        val message = buildString {
            appendLine(getString(R.string.gall_lesson_number, lesson))
            appendLine()
            appendLine(getString(R.string.gall_lesson_start, startTime))
            appendLine(getString(R.string.gall_lesson_end, endTime))
            if (breakAfter != null) {
                appendLine()
                appendLine(getString(R.string.gall_subsequent_break, breakAfter))
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.gall_lesson_times))
            .setMessage(message)
            .setPositiveButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showLessonDetails(
        date: Date,
        lesson: Int,
        timetableEntry: TimetableEntry,
        calendarEntries: List<CalendarEntry>
    ) {
        val dateStr = SimpleDateFormat("EEEE, dd.MM.yyyy", Locale.GERMANY).format(date)
        val startTime = lessonTimes[lesson] ?: getString(R.string.unknown)
        val endTime = lessonEndTimes[lesson] ?: getString(R.string.unknown)

        val message = buildString {
            appendLine(getString(R.string.gall_date, dateStr))
            appendLine(getString(R.string.gall_time, startTime, endTime, lesson))
            appendLine()
            appendLine(getString(R.string.gall_subject_details, timetableEntry.subject))

            if (timetableEntry.teacher.isNotBlank() && timetableEntry.teacher != "UNKNOWN") {
                appendLine(getString(R.string.gall_teacher, timetableEntry.teacher))
            }
            if (timetableEntry.room.isNotBlank() && timetableEntry.room != "UNKNOWN") {
                appendLine(getString(R.string.gall_room, timetableEntry.room))
            }

            if (calendarEntries.isNotEmpty()) {
                appendLine()
                appendLine(getString(R.string.gall_additional_information))
                calendarEntries.forEach { entry ->
                    when (entry.type) {
                        EntryType.HOMEWORK -> appendLine(getString(R.string.gall_homework_due))
                        EntryType.EXAM -> appendLine(getString(R.string.gall_exam_due))
                        EntryType.SUBSTITUTE -> appendLine(getString(R.string.gall_substitute_detail, entry.content))
                        EntryType.SPECIAL_DAY -> appendLine("• ${entry.content}")
                        else -> appendLine("• ${entry.content}")
                    }
                }
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.gall_lesson_details))
            .setMessage(message)
            .setPositiveButton(getString(R.string.gall_close), null)
            .show()
    }

    private fun showEmptyCalendar() {
        val emptyMessage = TextView(requireContext()).apply {
            text = getString(R.string.gall_scan_timetable_first)
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

        val lessonHeader = createStyledCell(getString(R.string.gall_header_hour), isHeader = true, isLessonColumn = true).apply {
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
            text = getString(R.string.gall_notes)
            textSize = 16f
            setTextColor(Color.BLACK)
            setTypeface(null, Typeface.BOLD)
        }

        val notesEditText = EditText(requireContext()).apply {
            hint = getString(R.string.gall_notes_hint)
            minLines = 2
            maxLines = 4
            text = Editable.Factory.getInstance().newEditable(getUserNotesForDate(date))
        }

        val occasionsLabel = TextView(requireContext()).apply {
            text = getString(R.string.gall_special_events)
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
                hint = getString(R.string.gall_occasion_hint)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setText(text)
            }
            occasionEditTexts.add(editText)

            val removeButton = Button(requireContext()).apply {
                setText("×")
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    occasionsContainer.removeView(occasionLayout)
                    occasionEditTexts.remove(editText)
                }
            }

            occasionLayout.addView(editText)
            occasionLayout.addView(removeButton)
            occasionsContainer.addView(occasionLayout)
        }

        userOccasions.forEach { occasion ->
            addOccasionField(occasion)
        }

        val addOccasionButton = Button(requireContext()).apply {
            text = getString(R.string.gall_add_event)
            setOnClickListener { addOccasionField() }
        }

        container.addView(notesLabel)
        container.addView(notesEditText)
        container.addView(occasionsLabel)
        container.addView(occasionsContainer)
        container.addView(addOccasionButton)

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.gall_edit_day, dateStr))
            .setView(container)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.gall_save)) { _, _ ->
                val notes = notesEditText.text.toString().trim()
                saveUserNotesForDate(date, notes)

                val occasions = occasionEditTexts.mapNotNull { editText ->
                    editText.text.toString().trim().takeIf { it.isNotBlank() }
                }
                saveUserSpecialOccasionsForDate(date, occasions)

                updateCalendar()
                Toast.makeText(requireContext(), getString(R.string.gall_changes_saved), Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(getString(R.string.gall_reset)) { _, _ ->
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.gall_reset_confirm))
                    .setMessage(getString(R.string.gall_reset_hint))
                    .setPositiveButton(getString(R.string.slide_yes)) { _, _ ->
                        clearUserDataForDate(date)
                        updateCalendar()
                        Toast.makeText(requireContext(), getString(R.string.gall_reset_day), Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(getString(R.string.slide_no), null)
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
        val sharedPreferences = requireContext().getSharedPreferences(
            "AppPrefs",
            Context.MODE_PRIVATE
        )
        sharedPreferences.edit {
            putString("user_notes_$dateStr", notes)
        }
    }

    private fun saveUserSpecialOccasionsForDate(date: Date, occasions: List<String>) {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(date)
        val sharedPreferences = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val json = Gson().toJson(occasions)
        sharedPreferences.edit {
            putString("user_special_occasions_$dateStr", json)
        }

        saveHolidaysData()
    }

    private fun clearUserDataForDate(date: Date) {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(date)
        val sharedPreferences = requireContext().getSharedPreferences(
            "AppPrefs",
            Context.MODE_PRIVATE
        )
        sharedPreferences.edit {
            remove("user_notes_$dateStr")
                .remove("user_special_occasions_$dateStr")
        }
    }

    private fun showAllLessonTimes() {
        val message = buildString {
            appendLine(getString(R.string.gall_lesson_times_overview))
            appendLine()

            for (lesson in 1..15) {
                val startTime = lessonTimes[lesson]
                val endTime = lessonEndTimes[lesson]
                if (startTime != null && endTime != null) {
                    appendLine(getString(R.string.gall_all_lesson_times_lesson, lesson, startTime, endTime))

                    breakTimes[lesson]?.let { breakMinutes ->
                        appendLine(getString(R.string.gall_all_lesson_times_break, breakMinutes))
                    }
                }
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.gall_all_lesson_times))
            .setMessage(message)
            .setPositiveButton(getString(R.string.gall_close), null)
            .show()
    }

    private fun createLessonRows() {
        val maxLessons = getMaxLessonsForWeek()
        val currentTime = Calendar.getInstance()
        isSameWeek(currentWeekStart.time, currentTime.time)

        for (lesson in 1..maxLessons) {
            val breakMinutes = breakTimes[lesson - 1]
            if (breakMinutes != null && lesson > 1) {
                createBreakRow(breakMinutes)
            }

            val lessonRow = TableRow(requireContext()).apply {
                layoutParams = TableLayout.LayoutParams(
                    TableLayout.LayoutParams.MATCH_PARENT,
                    TableLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val timeCell = createStyledCell("$lesson", isLessonColumn = true).apply {
                setTypeface(null, Typeface.BOLD)

                // set default background without highlighting
                background = createFlatRoundedDrawable("#ECEFF1")
                setTextColor("#37474F".toColorInt())

                setOnClickListener { showLessonTimeDetails(lesson) }
            }
            lessonRow.addView(timeCell)

            // day columns
            for (dayIndex in weekdays.indices) {
                val dayCell = createDayCell(dayIndex, lesson)
                lessonRow.addView(dayCell)
            }

            calendarGrid.addView(lessonRow)
        }
    }

    private fun createDayCell(
        dayIndex: Int,
        lesson: Int
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
            cellText = if (shouldMarkAsHoliday) getString(R.string.gall_holiday) else getString(R.string.gall_free)
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
        var isCancelled = false

        if (timetableEntry != null && !timetableEntry.isBreak) {
            cellText = timetableEntry.subject
            calendarEntries.addAll(
                getCalendarEntriesForDayAndLesson(
                    currentWeekDay.time,
                    lesson,
                    timetableEntry.subject
                )
            )

            calendarEntries.forEach { entry ->
                if (entry.type == EntryType.SUBSTITUTE &&
                    entry.content.contains("Entfällt", ignoreCase = true) &&
                    entry.backgroundColor == getColorBlindFriendlyColor("red")) {
                    isCancelled = true
                }
            }
        } else if (isEditMode) {
            cellText = "＋"
            cell.setTextColor(Color.GRAY)
        }

        // strikethrough if cancelled
        if (isCancelled && cellText.isNotEmpty() && cellText != "＋") {
            cell.text = Html.fromHtml("<s>$cellText</s>", Html.FROM_HTML_MODE_COMPACT)
        } else {
            cell.text = cellText
        }

        val baseOpacity = if (timetableEntry?.subject == getString(R.string.slide_free_lesson)) 0.6f else 1.0f
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

        // set background without automatic highlighting
        if (backgroundColor != Color.TRANSPARENT) {
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

    private fun createBreakRow(breakMinutes: Int) {
        val breakRow = TableRow(requireContext()).apply {
            layoutParams = TableLayout.LayoutParams(
                TableLayout.LayoutParams.MATCH_PARENT,
                TableLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val breakView = TextView(requireContext()).apply {
            "Pause ($breakMinutes Min.)".also { text = it }
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(4, 4, 4, 4)
            setTextColor(Color.BLACK)

            background = createRoundedDrawable(Color.LTGRAY)

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

        try {
            val endParts = lessonEndTime.split(":")
            val startParts = nextLessonStartTime.split(":")
            val currentParts = currentTimeStr.split(":")

            val endMinutes = endParts[0].toInt() * 60 + endParts[1].toInt()
            val startMinutes = startParts[0].toInt() * 60 + startParts[1].toInt()
            val currentMinutes = currentParts[0].toInt() * 60 + currentParts[1].toInt()

            return currentMinutes >= endMinutes && currentMinutes < startMinutes
        } catch (e: Exception) {
            L.w("GalleryFragment", "Error parsing break time", e)
            return false
        }
    }

    private fun setupColorLegend() {
        colorLegend.removeAllViews()

        colorLegend.orientation = LinearLayout.VERTICAL

        val legendItems = listOf(
            Pair(getString(R.string.gall_homework_single), Color.CYAN),
            Pair(getString(R.string.gall_exam_single), examColor),
            Pair(getString(R.string.gall_holiday_or_free), Color.LTGRAY),
            Pair(getString(R.string.home_is_cancelled), getColorBlindFriendlyColor("red")),
            Pair(getString(R.string.gall_is_looked_after), getColorBlindFriendlyColor("orange")),
            Pair(getString(R.string.gall_is_substituted), getColorBlindFriendlyColor("green")),
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
                                getString(R.string.gall_holiday),
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
                                    getString(R.string.gall_free_from_4),
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
                                EntryType.EXAM, getString(R.string.gall_exam_single), exam.subject,
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
                                EntryType.EXAM, getString(R.string.gall_exam_single), exam.subject,
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
                background = createRoundedDrawable("#0f5293".toColorInt())
                setTextColor(Color.WHITE)
            } else if (isLessonColumn) {
                background = createRoundedDrawable("#ECEFF1".toColorInt())
                setTextColor("#37474F".toColorInt())
            } else {
                background = createRoundedDrawable(Color.WHITE)
                setTextColor("#212121".toColorInt())
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
            setColor(colorHex.toColorInt())
            cornerRadius = 8f
        }
    }

    private fun showTimetableEditor(dayIndex: Int, lesson: Int) {
        val sharedPreferences = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val subjects = sharedPreferences.getString("student_subjects", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

        if (subjects.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.gall_no_subjects_found), Toast.LENGTH_LONG).show()
            return
        }

        saveStateForUndo()

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val subjectSpinner = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item,
                listOf(getString(R.string.gall_no_school)) + subjects).apply { // be cautious about this
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }

        val durationSpinner = Spinner(requireContext()).apply {
            val durationOptions = (1..8).map {
                when (it) {
                    1 -> getString(R.string.gall_first_lesson)
                    else -> getString(R.string.gall_lesson_count, it)
                }
            }
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, durationOptions).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(1)
        }

        val roomLabel = TextView(requireContext()).apply {
            text = getString(R.string.gall_this_room)
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
            text = getString(R.string.gall_this_subject)
            textSize = 14f
            setTextColor(Color.BLACK)
        })
        container.addView(subjectSpinner)
        container.addView(TextView(requireContext()).apply {
            text = getString(R.string.gall_this_duration)
            textSize = 14f
            setTextColor(Color.BLACK)
        })
        container.addView(durationSpinner)
        container.addView(roomLabel)
        container.addView(roomSpinner)

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.gall_edit_timetable, weekdays[dayIndex], lesson))
            .setView(container)
            .setPositiveButton(getString(R.string.gall_save)) { _, _ ->
                val selectedSubject = subjectSpinner.selectedItem.toString()
                val duration = durationSpinner.selectedItemPosition + 1
                val selectedRoom = if (roomSpinner.isVisible && roomSpinner.selectedItem != null) {
                    roomSpinner.selectedItem.toString()
                } else ""

                saveTimetableEntryWithRoom(dayIndex, lesson, selectedSubject, duration, selectedRoom)
                updateCalendar()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun saveTimetableEntryWithRoom(dayIndex: Int, lesson: Int, subject: String, duration: Int, alternativeRoom: String) {
        val dayKey = getWeekdayKey(dayIndex)

        if (!timetableData.containsKey(dayKey)) {
            timetableData[dayKey] = mutableMapOf()
        }

        val NO_SCHOOL_INTERNAL = "NO_SCHOOL"

        for (i in lesson until lesson + duration) {
            if (subject == getString(R.string.gall_no_school)) { // be cautious about this
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
        Toast.makeText(requireContext(), getString(R.string.gall_timetable_saved), Toast.LENGTH_SHORT).show()
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

        val scrollView = ScrollView(requireContext())
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val titleText = TextView(requireContext()).apply {
            text = getString(R.string.gall_details_for, dateStr)
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 24)
        }
        container.addView(titleText)

        // vacation
        if (isDateInVacation(date)) {
            val vacationType = determineVacationType(date)
            val vacationText = TextView(requireContext()).apply {
                text = vacationType
                textSize = 16f
                setTextColor(Color.BLACK)
                setPadding(0, 0, 0, 16)
            }
            container.addView(vacationText)
        }

        // notes
        val userNotes = getUserNotesForDate(date)
        if (userNotes.isNotBlank()) {
            val notesContainer = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                background = createRoundedDrawable("#E3F2FD".toColorInt())
                setPadding(24, 20, 24, 20)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 20)
                }
            }

            val notesLabel = TextView(requireContext()).apply {
                text = getString(R.string.gall_personal_notes)
                textSize = 14f
                setTextColor("#1976D2".toColorInt())
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, 8)
            }

            val notesContent = TextView(requireContext()).apply {
                text = userNotes
                textSize = 14f
                setTextColor("#424242".toColorInt())
                background = createRoundedDrawable(Color.WHITE)
                setPadding(16, 12, 16, 12)
            }

            notesContainer.addView(notesLabel)
            notesContainer.addView(notesContent)
            container.addView(notesContainer)
        }

        // user special occasions
        val userOccasions = getUserSpecialOccasionsForDate(date)
        if (userOccasions.isNotEmpty()) {
            val occasionsText = TextView(requireContext()).apply {
                text = getString(R.string.gall_special_occasiions_manual)
                textSize = 16f
                setTextColor(Color.BLACK)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, 8)
            }
            container.addView(occasionsText)

            userOccasions.forEach { occasion ->
                val occasionItem = TextView(requireContext()).apply {
                    "➞ $occasion".also { text = it }
                    textSize = 14f
                    setTextColor(Color.BLACK)
                    setPadding(16, 4, 0, 4)
                }
                container.addView(occasionItem)
            }

            val spacer = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    16
                )
            }
            container.addView(spacer)
        }

        // calendar manager special occasions
        calendarInfo?.specialNote?.let { note ->
            if (note.isNotBlank()) {
                val calendarNotesText = TextView(requireContext()).apply {
                    text = getString(R.string.gall_spcieal_occasions_exam_schedule)
                    textSize = 16f
                    setTextColor(Color.BLACK)
                    setTypeface(null, Typeface.BOLD)
                    setPadding(0, 0, 0, 8)
                }
                container.addView(calendarNotesText)

                val noteItem = TextView(requireContext()).apply {
                    "- $note".also { text = it }
                    textSize = 14f
                    setTextColor(Color.BLACK)
                    setPadding(16, 4, 0, 16)
                }
                container.addView(noteItem)
            }
        }

        // homework
        try {
            val homeworkList = SlideshowFragment.getHomeworkList(requireContext())
            val dayHomework = homeworkList.filter { isSameDay(it.dueDate, date) }
            if (dayHomework.isNotEmpty()) {
                val homeworkText = TextView(requireContext()).apply {
                    text = getString(R.string.gall_homework)
                    textSize = 16f
                    setTextColor(Color.BLACK)
                    setTypeface(null, Typeface.BOLD)
                    setPadding(0, 0, 0, 8)
                }
                container.addView(homeworkText)

                dayHomework.forEach { homework ->
                    var lessonText = ""

                    if (homework.lessonNumber != null && homework.lessonNumber!! > 0) {
                        lessonText = getString(R.string.slide_due_date_lesson, homework.lessonNumber)
                    } else {
                        val dayKey = getWeekdayKey(getDayOfWeekIndex(date))
                        val dayTimetable = timetableData[dayKey]

                        val firstLessonWithSubject = dayTimetable?.entries
                            ?.filter { it.value.subject == homework.subject }
                            ?.minByOrNull { it.key }?.key

                        if (firstLessonWithSubject != null) {
                            lessonText = getString(R.string.slide_due_date_lesson, firstLessonWithSubject)
                        }
                    }

                    val homeworkItem = TextView(requireContext()).apply {
                        "➞ ${homework.subject}$lessonText".also { text = it }
                        textSize = 14f
                        setTextColor(Color.BLACK)
                        setPadding(16, 4, 0, 4)
                    }
                    container.addView(homeworkItem)
                }

                val spacer = View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        16
                    )
                }
                container.addView(spacer)
            }
        } catch (e: Exception) {
            L.w("GalleryFragment", "Error loading homework for details", e)
        }

        // exams
        var examsAdded = false
        calendarInfo?.exams?.let { exams ->
            if (exams.isNotEmpty()) {
                val examsText = TextView(requireContext()).apply {
                    text = getString(R.string.gall_exams)
                    textSize = 16f
                    setTextColor(Color.BLACK)
                    setTypeface(null, Typeface.BOLD)
                    setPadding(0, 0, 0, 8)
                }
                container.addView(examsText)

                exams.forEach { exam ->
                    val examItem = TextView(requireContext()).apply {
                        "➞ ${exam.subject}".also { text = it }
                        textSize = 14f
                        setTextColor(Color.BLACK)
                        setPadding(16, 4, 0, 4)
                    }
                    container.addView(examItem)
                }

                val spacer = View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        16
                    )
                }
                container.addView(spacer)
                examsAdded = true
            }
        }

        val dateStr2 = SimpleDateFormat("yyyyMMdd", Locale.GERMANY).format(date)
        val examsFromManager = ExamManager.getExamsForDate(dateStr2)
        if (examsFromManager.isNotEmpty() && !examsAdded) {
            val examsText = TextView(requireContext()).apply {
                text = getString(R.string.gall_exams)
                textSize = 16f
                setTextColor(Color.BLACK)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, 8)
            }
            container.addView(examsText)

            examsFromManager.forEach { exam ->
                val examItem = TextView(requireContext()).apply {
                    "➞ ${exam.subject}".also { text = it }
                    textSize = 14f
                    setTextColor(Color.BLACK)
                    setPadding(16, 4, 0, 4)
                }
                container.addView(examItem)
            }

            val spacer = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    16
                )
            }
            container.addView(spacer)
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
            val substitutesText = TextView(requireContext()).apply {
                text = getString(R.string.gall_substitutions)
                textSize = 16f
                setTextColor(Color.BLACK)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, 8)
            }
            container.addView(substitutesText)

            relevantSubstitutes.forEach { substitute ->
                val lessonRange = if (substitute.stundeBis != null && substitute.stundeBis != substitute.stunde) {
                    getString(R.string.gall_lesson_range, substitute.stunde, substitute.stundeBis)
                } else {
                    getString(R.string.gall_lesson_number, substitute.stunde)
                }

                val substituteItem = TextView(requireContext()).apply {
                    "➞ $lessonRange ${substitute.fach}: ${substitute.art}".also { text = it }
                    textSize = 14f
                    setTextColor(Color.BLACK)
                    setPadding(16, 4, 0, 4)
                }
                container.addView(substituteItem)
            }
        }

        if (container.childCount <= 1) {
            val emptyText = TextView(requireContext()).apply {
                text = getString(R.string.gall_no_special_occasions_for_give_day)
                textSize = 14f
                setTextColor(Color.GRAY)
                setPadding(0, 16, 0, 0)
            }
            container.addView(emptyText)
        }

        scrollView.addView(container)

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.gall_day_details))
            .setView(scrollView)
            .setPositiveButton(getString(R.string.cancel), null)
            .setNeutralButton(getString(R.string.gall_edit)) { _, _ ->
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
            originalText == "Auf einen anderen Termin verlegt" -> getString(R.string.home_substitution_moved)
            originalText.matches(Regex(".*entfällt, stattdessen .* in Raum .*", RegexOption.IGNORE_CASE)) -> {
                val regex = Regex(".*entfällt, stattdessen (.*) in Raum (.*)", RegexOption.IGNORE_CASE)
                val matchResult = regex.find(originalText)
                if (matchResult != null) {
                    val newSubject = matchResult.groups[1]?.value ?: ""
                    val room = matchResult.groups[2]?.value ?: ""
                    val originalSubject = originalText.substringBefore(" entfällt")
                    getString(R.string.home_substitution_replaced, newSubject, room, originalSubject)
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
                    getString(R.string.home_substitution_subject_replaced, newSubject, originalSubject)
                } else originalText
            }
            originalText == "Entfällt wegen Exkursion, Praktikum oder Veranstaltung" -> getString(R.string.gall_is_cancelled_exc)
            else -> originalText.take(20)
        }
    }

    private fun showExportOptions() {
        val content = exportTimetableData()

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

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.gall_export_timetable))
            .setAdapter(adapter) { _, which ->
                when (which) {
                    0 -> saveToFile(content)
                    1 -> copyToClipboard(content)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
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

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.gall_import_timetable))
            .setAdapter(adapter) { _, which ->
                when (which) {
                    0 -> importFromFile()
                    1 -> importFromClipboard()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
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
            Toast.makeText(requireContext(), getString(R.string.gall_file_manager_error), Toast.LENGTH_SHORT).show()
        }
    }

    private fun importFromClipboard() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip
        if (clipData != null && clipData.itemCount > 0) {
            val content = clipData.getItemAt(0).text.toString()
            importTimetableData(content)
        } else {
            Toast.makeText(requireContext(), getString(R.string.set_act_import_clipboard_empty), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(requireContext(), getString(R.string.set_act_timetable_imported_successfully), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.slide_import_error, e.message), Toast.LENGTH_LONG).show()
            L.e("GalleryFragment", "Error importing timetable", e)
        }
    }

    private fun saveToFile(content: String) {
        try {
            val fileName = "stundenplan_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(Date())}.hksc"
            val file = File(requireContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)
            FileWriter(file).use { it.write(content) }
            Toast.makeText(requireContext(), getString(R.string.gall_file_saved_at, file.absolutePath), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.gall_save_error, e.message), Toast.LENGTH_LONG).show()
            L.e("GalleryFragment", "Error saving file", e)
        }
    }

    private fun copyToClipboard(content: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(getString(R.string.set_act_timetable), content)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), getString(R.string.gall_copied_clipboard), Toast.LENGTH_SHORT).show()
    }

    private fun createRoundedDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = 16f
            setStroke(1, "#E0E0E0".toColorInt())
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
            sharedPreferences.edit {
                putString("timetable_data", json)
            }
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
            sharedPreferences.edit {
                putString("vacation_data", json)
            }
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
                        val vacationName = getString(R.string.gall_vacation) // fallback name
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

    private fun createOptimizedPulseAnimation(cell: TextView, backgroundColor: Int): ValueAnimator {
        return ValueAnimator.ofInt(0, 32).apply { // 32 steps = 4 cycles of 8 steps each
            duration = 4000  // 4 cycles * 1000ms per cycle

            // configure when to stop (0-7 represents position within the final cycle)
            val stopAtStep = 7  // 7 = thickest border, 0 = thinnest border

            addUpdateListener { animator ->
                if (currentHighlightedCell != cell) return@addUpdateListener

                val totalStep = animator.animatedValue as Int
                val currentCycle = totalStep / 8 // which cycle (0-3)
                val stepInCycle = totalStep % 8 // step within current cycle (0-7)

                if (currentCycle >= 4) {
                    animator.cancel()
                    return@addUpdateListener
                }

                if (currentCycle == 3 && stepInCycle >= stopAtStep) {
                    animator.cancel()
                    return@addUpdateListener
                }

                val progress = when {
                    stepInCycle <= 3 -> stepInCycle / 3f // 0 to 1 (expanding)
                    else -> (7 - stepInCycle) / 3f // 1 to 0 (contracting)
                }

                val minBorderWidth = 3
                val maxBorderWidth = 8
                val borderWidth = minBorderWidth + ((maxBorderWidth - minBorderWidth) * progress).toInt()

                val baseAlpha = 0.7f
                val pulseAlpha = 0.3f
                val alpha = baseAlpha + (pulseAlpha * progress)

                val pulseColor = Color.argb(
                    (255 * alpha).toInt(),
                    Color.red(Color.YELLOW),
                    Color.green(Color.YELLOW),
                    Color.blue(Color.YELLOW)
                )

                try {
                    cell.background = createRoundedDrawableWithBorder(
                        backgroundColor,
                        pulseColor,
                        borderWidth
                    )
                } catch (e: Exception) {
                    L.w("GalleryFragment", "Error updating pulse animation", e)
                }
            }

            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    setStaticHighlight(cell, backgroundColor)
                }

                override fun onAnimationCancel(animation: Animator) {
                    setStaticHighlight(cell, backgroundColor)
                }
            })

            highlightAnimator = this
        }
    }

    private fun setStaticHighlight(cell: TextView, backgroundColor: Int) {
        if (currentHighlightedCell == cell) {
            try {
                val parent = cell.parent as? TableRow
                val isLessonTimeCell = parent?.let { row ->
                    row.indexOfChild(cell) == 0
                } ?: false

                if (!isLessonTimeCell) {
                    cell.background = createRoundedDrawableWithBorder(
                        backgroundColor,
                        Color.YELLOW,
                        8
                    )
                }
            } catch (e: Exception) {
                L.w("GalleryFragment", "Error setting final highlight state", e)
            }
        }
        highlightAnimator = null
    }

    private fun stopCurrentHighlight() {
        highlightAnimator?.cancel()
        highlightAnimator = null

        currentHighlightedCell?.let { cell ->
            try {
                val parent = cell.parent as? TableRow
                if (parent != null && parent.getChildAt(0) == cell) {
                    if (isDayView) {
                        cell.background = createRoundedDrawable("#ECEFF1".toColorInt())
                        cell.setTextColor("#37474F".toColorInt())
                    } else {
                        cell.background = createFlatRoundedDrawable("#ECEFF1")
                        cell.setTextColor("#37474F".toColorInt())
                    }
                } else {
                    val calendarEntries = getCalendarEntriesForCurrentHighlightedCell()
                    val backgroundColor = getHighestPriorityBackgroundColor(calendarEntries)
                    cell.background = if (backgroundColor != Color.TRANSPARENT) {
                        createRoundedDrawable(backgroundColor)
                    } else {
                        createRoundedDrawable(Color.WHITE)
                    }
                }
            } catch (e: Exception) {
                L.w("GalleryFragment", "Error stopping highlight", e)
            }
        }
        currentHighlightedCell = null
    }

    private fun getCalendarEntriesForCurrentHighlightedCell(): List<CalendarEntry> {
        return emptyList()
    }

    private fun startCurrentBreakHighlight(breakView: TextView) {
        stopCurrentHighlight()
        currentHighlightedCell = breakView
        val pulseAnimation = createOptimizedPulseAnimation(breakView, Color.LTGRAY)
        pulseAnimation.start()
    }

    private fun startCurrentLessonHighlight(cell: TextView, backgroundColor: Int = Color.WHITE) {
        val parent = cell.parent as? TableRow
        if (parent != null && parent.getChildAt(0) == cell) {
            highlightLessonTimeCell(cell)
            return
        }

        stopCurrentHighlight()
        currentHighlightedCell = cell

        val actualBackgroundColor = if (backgroundColor == Color.WHITE) {
            getCurrentCellBackgroundColor(cell)
        } else {
            backgroundColor
        }

        val pulseAnimation = createOptimizedPulseAnimation(cell, actualBackgroundColor)
        pulseAnimation.start()
    }

    private fun getCurrentCellBackgroundColor(cell: TextView): Int {
        val currentDrawable = cell.background
        if (currentDrawable is GradientDrawable) {
            val parent = cell.parent as? TableRow
            if (parent != null) {
                val cellIndex = parent.indexOfChild(cell)
                val rowIndex = (parent.parent as? TableLayout)?.indexOfChild(parent) ?: -1

                if (cellIndex > 0 && rowIndex > 0) {
                    val dayIndex = if (isDayView) currentDayOffset else (cellIndex - 1)
                    val lesson = getLessonFromRowIndex(rowIndex)

                    if (lesson > 0) {
                        val currentWeekDay = Calendar.getInstance().apply {
                            time = currentWeekStart.time
                            add(Calendar.DAY_OF_WEEK, if (isDayView) currentDayOffset else dayIndex)
                        }

                        val dayKey = getWeekdayKey(dayIndex)
                        val timetableEntry = timetableData[dayKey]?.get(lesson)

                        if (timetableEntry != null) {
                            val calendarEntries = getCalendarEntriesForDayAndLesson(
                                currentWeekDay.time,
                                lesson,
                                timetableEntry.subject
                            )
                            return getHighestPriorityBackgroundColor(calendarEntries)
                        }
                    }
                }
            }
        }

        return Color.WHITE // fallback
    }

    private fun getLessonFromRowIndex(rowIndex: Int): Int {
        var lessonCount = 0

        for (i in 1 until rowIndex) {
            val row = calendarGrid.getChildAt(i) as? TableRow ?: continue
            if (row.isNotEmpty()) {
                val firstCell = row.getChildAt(0) as? TextView ?: continue
                val cellText = firstCell.text.toString()

                if (cellText.contains("(") && cellText.contains("Min.)")) {
                    continue
                } else if (isDayView) {
                    if (cellText.contains(".") && !cellText.contains("(")) {
                        lessonCount++
                    }
                } else {
                    if (cellText.trim().toIntOrNull() != null) {
                        lessonCount++
                    }
                }
            }
        }

        return lessonCount
    }

    private fun startInitialHighlighting() {
        calendarGrid.post {
            val currentTime = Calendar.getInstance()
            val isCurrentWeek = isSameWeek(currentWeekStart.time, currentTime.time)

            if (!isCurrentWeek && !isDayView) return@post

            if (isDayView) {
                val currentDay = Calendar.getInstance().apply {
                    time = currentWeekStart.time
                    add(Calendar.DAY_OF_WEEK, currentDayOffset)
                }
                val isCurrentDay = isSameDay(currentDay.time, currentTime.time)
                if (!isCurrentDay) return@post
            }

            val maxLessons = getMaxLessonsForWeek()
            for (lesson in 1..maxLessons) {
                if (isCurrentBreakTime(lesson, currentTime)) {
                    highlightCurrentBreak(lesson)
                    return@post
                }
            }

            for (lesson in 1..maxLessons) {
                if (isCurrentLesson(lesson, currentTime)) {
                    highlightCurrentLesson(lesson, currentTime)
                    return@post
                }
            }
        }
    }

    private fun highlightCurrentBreak(afterLesson: Int) {
        if (isDayView) {
            // day view
            for (i in 0 until calendarGrid.childCount) {
                val row = calendarGrid.getChildAt(i) as? TableRow ?: continue

                if (row.isNotEmpty()) {
                    val cell = row.getChildAt(0) as? TextView ?: continue
                    val cellText = cell.text.toString()

                    if (cellText.contains(getString(R.string.gall_break), ignoreCase = true)) { // be cautious
                        val breakPosition = getBreakPositionForDayView(i)
                        if (breakPosition == afterLesson) {
                            startCurrentBreakHighlight(cell)
                            return
                        }
                    }
                }
            }
        } else {
            // week view
            for (i in 0 until calendarGrid.childCount) {
                val row = calendarGrid.getChildAt(i) as? TableRow ?: continue

                for (j in 0 until row.childCount) {
                    val cell = row.getChildAt(j) as? TextView ?: continue
                    val cellText = cell.text.toString()

                    if (cellText.contains(getString(R.string.gall_break), ignoreCase = true)) { // be cautious
                        val breakPosition = getBreakPositionFromGrid(i)
                        if (breakPosition == afterLesson) {
                            startCurrentBreakHighlight(cell)
                            return
                        }
                    }
                }
            }
        }
    }

    private fun getBreakPositionFromGrid(gridIndex: Int): Int {
        var lessonCount = 0

        for (i in 1 until gridIndex) {
            val row = calendarGrid.getChildAt(i) as? TableRow ?: continue
            if (row.isNotEmpty()) {
                val firstCell = row.getChildAt(0) as? TextView ?: continue
                val cellText = firstCell.text.toString()

                if (!cellText.contains(getString(R.string.gall_break), ignoreCase = true) && cellText.trim().toIntOrNull() != null) { // be cautious
                    lessonCount++
                }
            }
        }
        return lessonCount
    }

    private fun getBreakPositionForDayView(breakRowIndex: Int): Int {
        var lessonCount = 0

        for (i in 1 until breakRowIndex) {
            val row = calendarGrid.getChildAt(i) as? TableRow ?: continue
            if (row.isNotEmpty()) {
                val firstCell = row.getChildAt(0) as? TextView ?: continue
                val cellText = firstCell.text.toString()

                if (cellText.contains(".") && !cellText.contains(getString(R.string.gall_break), ignoreCase = true)) { // be cautious
                    lessonCount++
                }
            }
        }
        return lessonCount
    }

    private fun highlightCurrentLesson(lesson: Int, currentTime: Calendar) {
        for (i in 0 until calendarGrid.childCount) {
            val row = calendarGrid.getChildAt(i) as? TableRow ?: continue

            if (row.isEmpty()) continue
            val firstCell = row.getChildAt(0) as? TextView ?: continue
            val cellText = firstCell.text.toString()

            if (cellText.trim() == lesson.toString() || (isDayView && cellText.contains("$lesson."))) {

                highlightLessonTimeCell(firstCell)

                for (j in 1 until row.childCount) {
                    val cell = row.getChildAt(j) as? TextView ?: continue

                    if (isDayView) {
                        val currentDay = Calendar.getInstance().apply {
                            time = currentWeekStart.time
                            add(Calendar.DAY_OF_WEEK, currentDayOffset)
                        }
                        if (isSameDay(currentDay.time, currentTime.time)) {
                            val dayKey = getWeekdayKey(currentDayOffset)
                            val timetableEntry = timetableData[dayKey]?.get(lesson)
                            val calendarEntries = if (timetableEntry != null) {
                                getCalendarEntriesForDayAndLesson(currentDay.time, lesson, timetableEntry.subject)
                            } else {
                                emptyList()
                            }
                            val backgroundColor = getHighestPriorityBackgroundColor(calendarEntries).takeIf {
                                it != Color.TRANSPARENT
                            } ?: Color.WHITE

                            startCurrentLessonHighlight(cell, backgroundColor)
                        }
                    } else {
                        val dayIndex = j - 1
                        if (dayIndex < 5) {
                            val currentWeekDay = Calendar.getInstance().apply {
                                time = currentWeekStart.time
                                add(Calendar.DAY_OF_WEEK, dayIndex)
                            }
                            if (isSameDay(currentWeekDay.time, currentTime.time)) {
                                val dayKey = getWeekdayKey(dayIndex)
                                val timetableEntry = timetableData[dayKey]?.get(lesson)
                                val calendarEntries = if (timetableEntry != null) {
                                    getCalendarEntriesForDayAndLesson(currentWeekDay.time, lesson, timetableEntry.subject)
                                } else {
                                    emptyList()
                                }
                                val backgroundColor = getHighestPriorityBackgroundColor(calendarEntries).takeIf {
                                    it != Color.TRANSPARENT
                                } ?: Color.WHITE

                                startCurrentLessonHighlight(cell, backgroundColor)
                            }
                        }
                    }
                }
                return
            }
        }
    }


    private fun highlightLessonTimeCell(cell: TextView) {
        if (currentHighlightedCell == cell) {
            highlightAnimator?.cancel()
            highlightAnimator = null
        }

        try {
            cell.background = createRoundedDrawable(Color.YELLOW)
        } catch (e: Exception) {
            L.w("GalleryFragment", "Error highlighting lesson time cell", e)
        }
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
        val FREE_LESSON_INTERNAL = "FREE_LESSON"

        for ((_, dayTimetable) in timetableData) {
            val lessons = dayTimetable.toSortedMap()

            if (lessons.isEmpty()) continue

            val firstLesson = lessons.firstKey()
            val lastNonEmptyLesson = lessons.keys.filter { lesson ->
                lessons[lesson]?.subject?.isNotBlank() == true
            }.maxOrNull() ?: continue

            for (lessonNum in firstLesson..lastNonEmptyLesson) {
                val entry = lessons[lessonNum]
                if (entry == null || entry.subject.isBlank()) {
                    dayTimetable[lessonNum] = TimetableEntry(
                        subject = FREE_LESSON_INTERNAL, // be cautious
                        duration = 1,
                        isBreak = false,
                        teacher = "",
                        room = ""
                    )
                }
            }
        }
    }

    private fun translateSubjectForDisplay(internalSubject: String): String { // suggested to use this
        return when (internalSubject) {
            "FREE_LESSON" -> getString(R.string.slide_free_lesson)
            "NO_SCHOOL" -> getString(R.string.gall_no_school)
            else -> internalSubject
        }
    }

    private fun cleanDisplayString(input: String): String {
        return input.replace("UNKNOWN", "")
            .replace(getString(R.string.unknown), "")
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
                "orange" -> ContextCompat.getColor(requireContext(), android.R.color.holo_orange_dark)
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
                        if (occasion.contains(getString(R.string.gall_holiday), ignoreCase = true) || // be cautious
                            occasion.contains(getString(R.string.gall_vacation_day), ignoreCase = true)) { // be cautious
                            isHoliday = true
                            holidayName = occasion
                        }
                    }

                    calendarInfo?.let { info ->
                        if (info.isSpecialDay && info.specialNote.isNotBlank()) {
                            if (info.specialNote.contains(getString(R.string.gall_holiday), ignoreCase = true) || // be cautious
                                info.specialNote.contains(getString(R.string.gall_vacation_day), ignoreCase = true)) { // be cautious
                                isHoliday = true
                                if (holidayName.isEmpty()) holidayName = info.specialNote
                            }
                        }
                    }

                    if (isHoliday) {
                        val holidayObject = JSONObject().apply {
                            put("date", dateStr)
                            put("name", holidayName.ifEmpty { getString(R.string.gall_holiday) }) // be cautious
                        }
                        holidaysArray.put(holidayObject)
                        processedDates.add(dateStr)
                    }
                }

                checkDate.add(Calendar.DAY_OF_YEAR, 1)
            }

            sharedPreferences.edit {
                putString("holidays_data", holidaysArray.toString())
            }

            L.d("GalleryFragment", "Holidays data saved: ${holidaysArray.length()} holidays")

        } catch (e: Exception) {
            L.e("GalleryFragment", "Error saving holidays data", e)
        }
    }

    private fun saveVacationsData() {
        try {
            val sharedPreferences = requireContext().getSharedPreferences(
                "AppPrefs",
                Context.MODE_PRIVATE
            )
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
                    val vacationName = vacationWeek?.name ?: getString(R.string.gall_vacation) // be cautious

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

            sharedPreferences.edit {
                putString("vacations_data", vacationsArray.toString())
            }

            L.d("GalleryFragment", "Vacations data saved: ${vacationsArray.length()} vacation periods")

        } catch (e: Exception) {
            L.e("GalleryFragment", "Error saving vacations data", e)
        }
    }

    private fun setupSwipeGestures() {
        val swipeInterceptor =
            binding.root.findViewById<SwipeInterceptorLayout>(R.id.swipeInterceptor)

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

            positionSwipeIndicatorSided(isRightSwipe)

            indicator.alpha = targetAlpha
            indicator.visibility = View.VISIBLE

            val textView = indicator as TextView

            val text = if (isRightSwipe) {
                if (isDayView) getString(R.string.gall_previous_day) else getString(R.string.gall_previous_week)
            } else {
                if (isDayView) getString(R.string.gall_next_day) else getString(R.string.gall_next_week)
            }

            textView.text = text

            val arrowIcon = if (isRightSwipe) {
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_arrow_back)
            } else {
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_arrow_forward)
            }

            arrowIcon?.setTint("#0f5293".toColorInt())

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
        }
    }

    private fun positionSwipeIndicatorSided(isRightSwipe: Boolean) {
        swipeIndicatorView?.let { indicator ->
            val calendarArea = binding.root.findViewById<SwipeInterceptorLayout>(R.id.swipeInterceptor)

            val calendarWidth = calendarArea.width
            val calendarHeight = calendarArea.height

            if (calendarWidth == 0 || calendarHeight == 0) {
                calendarArea.post {
                    positionSwipeIndicatorSided(isRightSwipe)
                }
                return
            }

            if (indicator.width == 0) {
                indicator.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
            }

            val centerY = (calendarHeight - indicator.measuredHeight) / 2
            val horizontalMargin = 60
            val finalX = if (isRightSwipe) {
                horizontalMargin // previous -> left side
            } else {
                calendarWidth - indicator.measuredWidth - horizontalMargin // next -> right side
            }

            val layoutParams = indicator.layoutParams as ViewGroup.MarginLayoutParams
            layoutParams.setMargins(finalX, centerY, 0, 0)
            indicator.layoutParams = layoutParams
        }
    }

    private fun createSwipeIndicator() {
        swipeIndicatorView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
        }

        val calendarContainer = binding.root.findViewById<SwipeInterceptorLayout>(R.id.swipeInterceptor)

        val indicator = TextView(requireContext()).apply {
            textSize = 18f
            setTextColor("#0f5293".toColorInt())
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(40, 20, 40, 20)
            alpha = 0f
            visibility = View.INVISIBLE
            elevation = 30f
            isClickable = false
            isFocusable = false

            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor("#F0FFFFFF".toColorInt())
                cornerRadius = 35f
                setStroke(4, "#0f5293".toColorInt())
                elevation = 15f
            }

            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        calendarContainer.addView(indicator)
        swipeIndicatorView = indicator

        indicator.post {
            indicator.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
        }
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
        prevButton.contentDescription = getString(R.string.gall_previous_week)

        setArrow(nextButton, R.drawable.ic_arrow_right, sizeDp = 32, paddingDp = 0)
        nextButton.contentDescription = getString(R.string.gall_next_week)
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