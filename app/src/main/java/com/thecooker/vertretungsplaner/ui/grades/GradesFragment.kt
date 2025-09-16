package com.thecooker.vertretungsplaner.ui.grades

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.*
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.thecooker.vertretungsplaner.R
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.filter
import com.thecooker.vertretungsplaner.ui.exams.ExamFragment
import androidx.core.content.ContextCompat
import com.thecooker.vertretungsplaner.L
import com.thecooker.vertretungsplaner.utils.BackupManager
import androidx.core.content.edit

class GradesFragment : Fragment() {

    private lateinit var loadingView: View

    private lateinit var searchBar: EditText
    private lateinit var btnMenu: Button
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvGradeCount: TextView
    private lateinit var tvFinalGrade: TextView
    private lateinit var adapter: GradesAdapter
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var exportLauncher: ActivityResultLauncher<Intent>

    private var gradeList = mutableListOf<SubjectGradeInfo>()
    private var filteredGradeList = mutableListOf<SubjectGradeInfo>()

    private lateinit var layoutSubjectChangeWarning: LinearLayout
    private lateinit var btnAcceptSubjectChanges: Button
    private lateinit var btnDeclineSubjectChanges: Button
    private var hasSubjectChanges = false

    private lateinit var backupManager: BackupManager

    data class SubjectGradeInfo(
        val subject: String,
        val teacher: String,
        var oralGradeHistory: MutableMap<Int, Double?> = mutableMapOf(),
        var writtenGradesHistory: MutableMap<Int, List<Double>> = mutableMapOf(),
        var ratio: Pair<Int, Int> = Pair(50, 50),
        var isPruefungsfach: Boolean = false,
        var pruefungsergebnis: Double? = null,
        var selectedHalfYears: Int = 2
    ) {

        fun isLK(): Boolean = subject.endsWith("LK") || subject.endsWith("L")

        fun isCoreSubject(): Boolean = subject.startsWith("Ma") ||
                subject.startsWith("De") ||
                subject.startsWith("En")

        fun getHalfYearGrades(currentHalfyear: Int): List<Pair<Int, Double?>> {
            val halfyearGrades = mutableListOf<Pair<Int, Double?>>()

            for (halfyear in 1..currentHalfyear) {
                val oral = oralGradeHistory[halfyear]
                val written = writtenGradesHistory[halfyear]?.takeIf { it.isNotEmpty() }?.average()

                val finalGrade = when {
                    written != null && oral != null -> {
                        val writtenWeight = ratio.second / 100.0
                        val oralWeight = ratio.first / 100.0
                        (written * writtenWeight) + (oral * oralWeight)
                    }
                    written != null && oral == null -> written
                    written == null && oral != null -> oral
                    else -> null
                }

                halfyearGrades.add(Pair(halfyear, finalGrade))
            }

            return halfyearGrades
        }

        fun getBestHalfYears(requirements: SubjectRequirements, currentHalfyear: Int): List<Pair<Int, Double>> {
            val halfyearGrades = getHalfYearGrades(currentHalfyear)
                .mapNotNull { (halfyear, grade) ->
                    if (grade != null) Pair(halfyear, grade) else null
                }
                .sortedBy { it.second } // sort by grade (ascending, best to worst)

            return if (requirements.mustCountAllHalfYears || isLK() || isCoreSubject()) {
                halfyearGrades
            } else {
                val minRequired = requirements.minRequiredHalfYears
                val selectedCount = maxOf(minRequired, selectedHalfYears)
                halfyearGrades.take(selectedCount)
            }
        }

        fun getFinalGrade(requirements: SubjectRequirements, currentHalfyear: Int): Double? {
            val bestHalfYears = getBestHalfYears(requirements, currentHalfyear)
            if (bestHalfYears.isEmpty()) return null

            val baseGrade = bestHalfYears.map { it.second }.average()

            return if (isPruefungsfach && pruefungsergebnis != null) {
                ((baseGrade * bestHalfYears.size) + (pruefungsergebnis!! * 4)) / (bestHalfYears.size + 4)
            } else {
                baseGrade
            }
        }

        fun getFormattedFinalGrade(requirements: SubjectRequirements, currentHalfyear: Int): String {
            val final = getFinalGrade(requirements, currentHalfyear)
            return if (final != null) DecimalFormat("0.0").format(final) else "-"
        }

        fun getFormattedOralGrade(currentHalfyear: Int): String {
            val oralGrade = oralGradeHistory[currentHalfyear]
            return if (oralGrade != null) DecimalFormat("0.0").format(oralGrade) else "-"
        }

        fun getFormattedWrittenAverage(currentHalfyear: Int): String {
            val writtenGrades = writtenGradesHistory[currentHalfyear]
            val avg = if (writtenGrades != null && writtenGrades.isNotEmpty()) {
                writtenGrades.average()
            } else null
            return if (avg != null) DecimalFormat("0.0").format(avg) else "-"
        }

        fun getWeightingInfo(requirements: SubjectRequirements, currentHalfyear: Int, context: Context): String {
            val info = mutableListOf<String>()

            if (isLK()) info.add(context.getString(R.string.grades_lk_all_semesters_count))
            else if (isCoreSubject()) info.add(context.getString(R.string.grades_core_subject_all_semesters_count))
            else if (requirements.mustCountAllHalfYears) info.add(context.getString(R.string.grades_all_semesters_count))
            else info.add(context.getString(R.string.grades_best_semesters_count, getBestHalfYears(requirements, currentHalfyear).size))

            if (isPruefungsfach) {
                info.add(context.getString(R.string.grades_examination_subject))
                if (pruefungsergebnis != null) info.add(context.getString(R.string.grades_examination_score_4x_count))
            }

            if (requirements.minRequiredHalfYears > 1) {
                info.add(context.getString(R.string.grades_atleast_count_semester_required, requirements.minRequiredHalfYears))
            }

            return info.joinToString(" • ")
        }

        fun getSimpleFinalGrade(currentHalfyear: Int): Double? {
            val oral = oralGradeHistory[currentHalfyear]
            val written = writtenGradesHistory[currentHalfyear]?.takeIf { it.isNotEmpty() }?.average()

            return when {
                written != null && oral != null -> {
                    val writtenWeight = ratio.second / 100.0
                    val oralWeight = ratio.first / 100.0
                    (written * writtenWeight) + (oral * oralWeight)
                }
                written != null && oral == null -> written
                written == null && oral != null -> oral
                else -> null
            }
        }
    }

    data class SubjectRequirements(
        val mustCountAllHalfYears: Boolean = false,
        val minRequiredHalfYears: Int = 1,
        val maxSelectableHalfYears: Int = 4,
        val canBePruefungsfach: Boolean = true,
        val reservedCourseSlots: Int = 0
    )

    companion object {
        private const val PREFS_ORAL_GRADES = "oral_grades"
        private const val PREFS_ORAL_GRADES_HISTORY = "oral_grades_history"
        private const val PREFS_WRITTEN_GRADES_HISTORY = "written_grades_history"
        private const val PREFS_GRADE_RATIOS = "grade_ratios"
        private const val PREFS_PRUEFUNGSFAECHER = "pruefungsfaecher"
        private const val PREFS_PRUEFUNGSERGEBNISSE = "pruefungsergebnisse"
        private const val PREFS_SELECTED_HALF_YEARS = "selected_half_years"
        private const val PREFS_GOAL_GRADE = "goal_grade"
        private const val PREFS_GRADE_HISTORY = "grade_history"
        private const val PREFS_CURRENT_HALFYEAR = "current_halfyear"
        private const val PREFS_USE_SIMPLE_GRADING = "use_simple_grading"
        private const val PREFS_GRADES_SUBJECTS = "grades_subjects"
        private const val PREFS_GRADES_TEACHERS = "grades_teachers"
        private const val PREFS_GRADES_ROOMS = "grades_rooms"
        private const val PREFS_SUBJECT_GRADE_SYSTEM = "subject_grade_system"
        private const val PREFS_SUBJECT_RANGE_MODE = "subject_range_mode"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_grades, container, false)

        sharedPreferences = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        backupManager = BackupManager(requireContext())

        val hasScannedDocument = sharedPreferences.getBoolean("has_scanned_document", false)
        val documentInfo = sharedPreferences.getString("scanned_document_info", "")

        if (!hasScannedDocument || documentInfo?.isEmpty() == true) {
            showNoTimetableMessage(view)
            return view
        }

        initializeViews(view)
        setupExportLauncher()

        showLoadingState()

        view.post {
            setupRecyclerView()
            loadGrades()
            checkForSubjectChanges()
            setupListeners()
            updateGradeCount()
            updateFinalGrade()

            hideLoadingState()
        }

        return view
    }

    @SuppressLint("InflateParams")
    private fun showLoadingState() {
        loadingView = LayoutInflater.from(requireContext()).inflate(android.R.layout.simple_list_item_1, null)

        val loadingText = loadingView.findViewById<TextView>(android.R.id.text1)
        loadingText.apply {
            text = getString(R.string.grades_loading)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(32, 64, 32, 64)
            setTextColor(resources.getColor(android.R.color.black))
            setTypeface(null, Typeface.BOLD)
        }

        recyclerView.visibility = View.GONE
        searchBar.visibility = View.GONE
        btnMenu.visibility = View.GONE
        tvGradeCount.visibility = View.GONE
        tvFinalGrade.visibility = View.GONE

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
        btnMenu.visibility = View.VISIBLE
        tvGradeCount.visibility = View.VISIBLE
        tvFinalGrade.visibility = View.VISIBLE
    }

    private fun initializeViews(view: View) {
        searchBar = view.findViewById(R.id.searchBarGrades)
        btnMenu = view.findViewById(R.id.btnMenuGrades)
        recyclerView = view.findViewById(R.id.recyclerViewGrades)
        tvGradeCount = view.findViewById(R.id.tvGradeCount)
        tvFinalGrade = view.findViewById(R.id.tvFinalGrade)
        layoutSubjectChangeWarning = view.findViewById(R.id.layoutSubjectChangeWarning)
        btnAcceptSubjectChanges = view.findViewById(R.id.btnAcceptSubjectChanges)
        btnDeclineSubjectChanges = view.findViewById(R.id.btnDeclineSubjectChanges)
    }

    private fun setupExportLauncher() {
        exportLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    val tempContent = sharedPreferences.getString("temp_export_content", "")
                    if (tempContent?.isNotEmpty() == true) {
                        saveToSelectedFile(uri, tempContent)
                        sharedPreferences.edit { remove("temp_export_content") }
                    } else {
                        try {
                            requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                                val content = inputStream.bufferedReader().use { it.readText() }
                                importGradeData(content)
                            }
                        } catch (e: Exception) {
                            Toast.makeText(requireContext(), getString(R.string.grades_error_trying_to_read_file, e.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun showNoTimetableMessage(view: View) {
        val messageView = TextView(requireContext()).apply {
            text = getString(R.string.grades_scan_timetable_first)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(32, 64, 32, 64)
            setTextColor(resources.getColor(android.R.color.black))
        }

        val rootLayout = view.findViewById<LinearLayout>(R.id.gradesRootLayout)
        rootLayout.removeAllViews()
        rootLayout.addView(messageView)
    }

    private fun setupRecyclerView() {
        val currentHalfyear = getCurrentHalfyear()
        adapter = GradesAdapter(
            gradeList = filteredGradeList,
            currentHalfyear = currentHalfyear,
            getSubjectRequirements = { subject -> getSubjectRequirements(subject) },
            onSubjectClicked = { subject ->
                showSubjectDetails(subject)
            },
            onSubjectEdited = { subject ->
                showEditSubjectDialog(subject)
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
    }

    private fun setupListeners() {
        btnMenu.setOnClickListener {
            showMenuPopup()
        }

        searchBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterGrades(s.toString())
            }
        })

        setupSubjectChangeListeners()
    }

    private fun showMenuPopup() {
        val popup = PopupMenu(requireContext(), btnMenu)
        popup.menu.add(0, 1, 0, getString(R.string.grades_new_halfyear_title)).apply {
            setIcon(R.drawable.ic_rocket)
        }
        popup.menu.add(0, 2, 0, getString(R.string.grades_menu_set_goal)).apply {
            setIcon(R.drawable.ic_goal)
        }
        popup.menu.add(0, 3, 0, getString(R.string.grades_menu_show_graph)).apply {
            setIcon(R.drawable.ic_statistics)
        }
        popup.menu.add(0, 4, 0, getString(R.string.grades_reset_graph_title)).apply {
            setIcon(R.drawable.ic_statistics_clear)
        }
        popup.menu.add(0, 5, 0, getString(R.string.grades_menu_export)).apply {
            setIcon(R.drawable.ic_export)
        }
        popup.menu.add(0, 6, 0, getString(R.string.grades_menu_import)).apply {
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

        // add complex grading toggle if user is in "BG" bildungsgang
        val bildungsgang = sharedPreferences.getString("selected_bildungsgang", "")
        if (bildungsgang == "BG") {
            val useSimple = sharedPreferences.getBoolean(PREFS_USE_SIMPLE_GRADING, false)
            val toggleText = if (useSimple) getString(R.string.grades_mode_abi) else getString(R.string.grades_mode_simple_activated)
            popup.menu.add(0, 7, 0, toggleText).apply {
                icon = if (useSimple) {
                    ContextCompat.getDrawable(context, R.drawable.ic_grades_easy)
                } else {
                    ContextCompat.getDrawable(context, R.drawable.ic_grades_abi)
                }
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    showStartNewHalfyearDialog()
                    true
                }
                2 -> {
                    showSetGoalGradeDialog()
                    true
                }
                3 -> {
                    showGradeGraphDialog()
                    true
                }
                4 -> {
                    showResetGraphDialog()
                    true
                }
                5 -> {
                    showExportOptions()
                    true
                }
                6 -> {
                    showImportOptions()
                    true
                }
                7 -> {
                    toggleGradingSystem()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun toggleGradingSystem() {
        val useSimple = sharedPreferences.getBoolean(PREFS_USE_SIMPLE_GRADING, false)
        val newValue = !useSimple

        sharedPreferences.edit {
            putBoolean(PREFS_USE_SIMPLE_GRADING, newValue)
        }

        val message = if (newValue) getString(R.string.grades_mode_simple_activated) else getString(R.string.grades_mode_abi_activated)
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()

        loadGrades()
        updateFinalGrade()
    }

    private fun showGradeGraphDialog() {
        val historyJson = sharedPreferences.getString(PREFS_GRADE_HISTORY, "[]")
        val historyType = object : TypeToken<List<GradeHistoryEntry>>() {}.type
        val history: List<GradeHistoryEntry> = Gson().fromJson(historyJson, historyType) ?: emptyList()

        if (history.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.grades_no_history_data), Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = AlertDialog.Builder(requireContext())
        val dialogView = layoutInflater.inflate(R.layout.dialog_grade_graph, null)

        val chart = dialogView.findViewById<GradeGraphView>(R.id.gradeChart)
        val btnShowTextData = dialogView.findViewById<Button>(R.id.btnShowTextData)
        val scrollViewTextData = dialogView.findViewById<ScrollView>(R.id.scrollViewTextData)
        val tvTextData = dialogView.findViewById<TextView>(R.id.tvTextData)

        chart.setData(history, sharedPreferences.getFloat(PREFS_GOAL_GRADE, 0f))

        val textData = buildString {
            appendLine(getString(R.string.grades_history_text))
            appendLine()
            for (entry in history) {
                val monthName = getMonthName(entry.month)
                appendLine("$monthName ${entry.year}: ${DecimalFormat("0.0").format(entry.grade)}")
            }
        }
        tvTextData.text = textData

        var isTextVisible = false
        btnShowTextData.setOnClickListener {
            isTextVisible = !isTextVisible
            if (isTextVisible) {
                scrollViewTextData.visibility = View.VISIBLE
                btnShowTextData.text = getString(R.string.grades_hide_text)
            } else {
                scrollViewTextData.visibility = View.GONE
                btnShowTextData.text = getString(R.string.grades_show_text)
            }
        }

        dialog.setTitle(getString(R.string.grades_history_title))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.grades_close), null)
            .show()
    }

    private fun getMonthName(month: Int): String {
        return when (month) {
            1 -> getString(R.string.january)
            2 -> getString(R.string.february)
            3 -> getString(R.string.march)
            4 -> getString(R.string.april)
            5 -> getString(R.string.may)
            6 -> getString(R.string.june)
            7 -> getString(R.string.june)
            8 -> getString(R.string.august)
            9 -> getString(R.string.september)
            10 -> getString(R.string.october)
            11 -> getString(R.string.november)
            12 -> getString(R.string.december)
            else -> getString(R.string.grades_month, month)
        }
    }

    private fun loadGrades() {
        gradeList.clear()

        val subjects = getGradesSubjects()
        val teachers = getGradesTeachers()
        val currentHalfyear = getCurrentHalfyear()

        val bildungsgang = sharedPreferences.getString("selected_bildungsgang", "")
        val useComplexGrading = bildungsgang == "BG" && sharedPreferences.getBoolean(PREFS_USE_SIMPLE_GRADING, false).not()

        val oralGradesHistoryJson = sharedPreferences.getString(PREFS_ORAL_GRADES_HISTORY, "{}")
        val oralGradesHistoryType = object : TypeToken<Map<String, Map<Int, Double>>>() {}.type
        val oralGradesHistoryLoaded: Map<String, Map<Int, Double>> = try {
            Gson().fromJson(oralGradesHistoryJson, oralGradesHistoryType) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }

        val writtenGradesHistoryJson = sharedPreferences.getString(PREFS_WRITTEN_GRADES_HISTORY, "{}")
        val writtenGradesHistoryType = object : TypeToken<Map<String, Map<Int, List<Double>>>>() {}.type
        val writtenGradesHistoryLoaded: Map<String, Map<Int, List<Double>>> = try {
            Gson().fromJson(writtenGradesHistoryJson, writtenGradesHistoryType) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }

        val ratiosJson = sharedPreferences.getString(PREFS_GRADE_RATIOS, "{}")
        val ratiosType = object : TypeToken<Map<String, Pair<Int, Int>>>() {}.type
        val ratios: Map<String, Pair<Int, Int>> = try {
            Gson().fromJson(ratiosJson, ratiosType) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }

        val pruefungsfaecherJson = sharedPreferences.getString(PREFS_PRUEFUNGSFAECHER, "{}")
        val pruefungsfaecherType = object : TypeToken<Map<String, Boolean>>() {}.type
        val pruefungsfaecher: Map<String, Boolean> = try {
            Gson().fromJson(pruefungsfaecherJson, pruefungsfaecherType) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }

        val pruefungsergebnisseJson = sharedPreferences.getString(PREFS_PRUEFUNGSERGEBNISSE, "{}")
        val pruefungsergebnisseType = object : TypeToken<Map<String, Double>>() {}.type
        val pruefungsergebnisse: Map<String, Double> = try {
            Gson().fromJson(pruefungsergebnisseJson, pruefungsergebnisseType) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }

        val selectedHalfYearsJson = sharedPreferences.getString(PREFS_SELECTED_HALF_YEARS, "{}")
        val selectedHalfYearsType = object : TypeToken<Map<String, Int>>() {}.type
        val selectedHalfYears: Map<String, Int> = try {
            Gson().fromJson(selectedHalfYearsJson, selectedHalfYearsType) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }

        val examJson = sharedPreferences.getString("exam_list", "[]")
        val examType = object : TypeToken<List<ExamFragment.ExamEntry>>() {}.type
        val examList: List<ExamFragment.ExamEntry> = try {
            Gson().fromJson(examJson, examType) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        for (i in subjects.indices) {
            val subject = subjects[i].trim()
            if (subject.isEmpty() || subject.startsWith("tu", ignoreCase = true)) continue // skip "tu" subjects as those cannot be graded

            val teacher = if (i < teachers.size) teachers[i].trim() else ""
            val ratio = ratios[subject] ?: Pair(50, 50)

            val subjectOralHistory = oralGradesHistoryLoaded[subject]?.let { immutableMap ->
                mutableMapOf<Int, Double?>().apply {
                    putAll(immutableMap.mapValues { it.value as Double? })
                }
            } ?: mutableMapOf()

            val subjectWrittenHistory = writtenGradesHistoryLoaded[subject]?.let { immutableMap ->
                mutableMapOf<Int, List<Double>>().apply {
                    putAll(immutableMap)
                }
            } ?: mutableMapOf()

            val currentWrittenGrades = examList
                .filter { it.subject == subject && it.isOverdue() && it.mark != null }
                .mapNotNull { it.getNumericalGrade() }

            if (currentWrittenGrades.isNotEmpty()) {
                subjectWrittenHistory[currentHalfyear] = currentWrittenGrades
            }

            val gradeInfo = SubjectGradeInfo(
                subject = subject,
                teacher = teacher,
                oralGradeHistory = subjectOralHistory,
                writtenGradesHistory = subjectWrittenHistory,
                ratio = ratio,
                isPruefungsfach = if (useComplexGrading) {
                    pruefungsfaecher[subject] == true || subject.endsWith("LK") || subject.endsWith("L")
                } else false,
                pruefungsergebnis = if (useComplexGrading) pruefungsergebnisse[subject] else null,
                selectedHalfYears = if (useComplexGrading) selectedHalfYears[subject] ?: 2 else 1
            )

            gradeList.add(gradeInfo)
        }

        filterGrades(searchBar.text.toString())
    }

    private fun filterGrades(query: String) {
        filteredGradeList.clear()

        if (query.isEmpty()) {
            filteredGradeList.addAll(gradeList)
        } else {
            filteredGradeList.addAll(gradeList.filter { grade ->
                grade.subject.contains(query, ignoreCase = true) ||
                        grade.teacher.contains(query, ignoreCase = true)
            })
        }

        adapter.notifyDataSetChanged()
        updateGradeCount()
    }

    private fun updateGradeCount() {
        val totalSubjects = gradeList.size
        val currentHalfyear = getCurrentHalfyear()
        val subjectsWithGrades = gradeList.count {
            val requirements = getSubjectRequirements(it.subject)
            it.getFinalGrade(requirements, currentHalfyear) != null
        }
        tvGradeCount.text = getString(R.string.grades_count, subjectsWithGrades, totalSubjects)
    }

    private fun updateFinalGrade() {
        val bildungsgang = sharedPreferences.getString("selected_bildungsgang", "")
        val useComplexGrading = bildungsgang == "BG" && sharedPreferences.getBoolean(PREFS_USE_SIMPLE_GRADING, false).not()
        val currentHalfyear = getCurrentHalfyear()

        if (useComplexGrading) {
            val allCourseGrades = mutableListOf<Double>()
            var totalCourseCount = 0

            for (subject in gradeList) {
                val requirements = getSubjectRequirements(subject.subject)
                val bestHalfYears = subject.getBestHalfYears(requirements, currentHalfyear)
                totalCourseCount += bestHalfYears.size

                if (subject.isPruefungsfach && subject.pruefungsergebnis != null) {
                    allCourseGrades.addAll(bestHalfYears.map { it.second })
                    repeat(4) {
                        allCourseGrades.add(subject.pruefungsergebnis!!)
                    }
                    totalCourseCount += 4
                } else {
                    allCourseGrades.addAll(bestHalfYears.map { it.second })
                }
            }

            if (allCourseGrades.isNotEmpty()) {
                val average = allCourseGrades.average()
                val courseCountText = if (totalCourseCount <= 44) {
                    getString(R.string.grades_courses_count, totalCourseCount)
                } else {
                    getString(R.string.grades_courses_count_exceeded, totalCourseCount)
                }

                tvFinalGrade.text = getString(R.string.grades_total_grade_formatted, DecimalFormat("0.0").format(average), courseCountText)

                val goalGrade = sharedPreferences.getFloat(PREFS_GOAL_GRADE, 0f)
                val color = when {
                    totalCourseCount > 44 -> android.R.color.holo_red_dark
                    goalGrade > 0 && average <= goalGrade -> android.R.color.holo_green_dark
                    goalGrade > 0 && average > goalGrade -> android.R.color.holo_red_dark
                    else -> android.R.color.black
                }
                tvFinalGrade.setTextColor(resources.getColor(color))
            } else {
                tvFinalGrade.text = getString(R.string.grades_total_grade_none)
                tvFinalGrade.setTextColor(resources.getColor(android.R.color.black))
            }
        } else { // simple grading logic
            val currentGrades = mutableListOf<Double>()

            for (subject in gradeList) {
                val oralGrade = subject.oralGradeHistory[currentHalfyear]
                val writtenGrades = subject.writtenGradesHistory[currentHalfyear]?.takeIf { it.isNotEmpty() }?.average()

                val finalGrade = when {
                    writtenGrades != null && oralGrade != null -> {
                        val writtenWeight = subject.ratio.second / 100.0
                        val oralWeight = subject.ratio.first / 100.0
                        (writtenGrades * writtenWeight) + (oralGrade * oralWeight)
                    }
                    writtenGrades != null && oralGrade == null -> writtenGrades
                    writtenGrades == null && oralGrade != null -> oralGrade
                    else -> null
                }

                if (finalGrade != null) {
                    currentGrades.add(finalGrade)
                }
            }

            if (currentGrades.isNotEmpty()) {
                val average = currentGrades.average()
                tvFinalGrade.text = getString(R.string.grades_total_grade, DecimalFormat("0.0").format(average))

                val goalGrade = sharedPreferences.getFloat(PREFS_GOAL_GRADE, 0f)
                val color = when {
                    goalGrade > 0 && average <= goalGrade -> android.R.color.holo_green_dark
                    goalGrade > 0 && average > goalGrade -> android.R.color.holo_red_dark
                    else -> android.R.color.black
                }
                tvFinalGrade.setTextColor(resources.getColor(color))
            } else {
                tvFinalGrade.text = getString(R.string.grades_total_grade_none)
                tvFinalGrade.setTextColor(resources.getColor(android.R.color.black))
            }
        }
    }

    private fun showSubjectDetails(subject: SubjectGradeInfo) {
        val dialog = AlertDialog.Builder(requireContext())
        val dialogView = layoutInflater.inflate(R.layout.dialog_subject_details, null)
        val currentHalfyear = getCurrentHalfyear()
        val requirements = getSubjectRequirements(subject.subject)

        val tvSubjectName = dialogView.findViewById<TextView>(R.id.tvSubjectName)
        val tvTeacher = dialogView.findViewById<TextView>(R.id.tvTeacher)
        val tvOralGrade = dialogView.findViewById<TextView>(R.id.tvOralGrade)
        val tvWrittenAverage = dialogView.findViewById<TextView>(R.id.tvWrittenAverage)
        val tvRatio = dialogView.findViewById<TextView>(R.id.tvRatio)
        val tvFinalGrade = dialogView.findViewById<TextView>(R.id.tvFinalGrade)
        val recyclerExamGrades = dialogView.findViewById<RecyclerView>(R.id.recyclerExamGrades)

        tvSubjectName.text = subject.subject
        tvTeacher.text = getString(R.string.grades_subject_teacher, subject.teacher)
        tvOralGrade.text = getString(R.string.grades_subject_oral, subject.getFormattedOralGrade(currentHalfyear))
        tvWrittenAverage.text = getString(R.string.grades_subject_written, subject.getFormattedWrittenAverage(currentHalfyear))

        val ratioFirst = subject.ratio.first
        val ratioSecond = subject.ratio.second
        tvRatio.text = getString(R.string.grades_subject_ratio, ratioFirst, ratioSecond)

        tvFinalGrade.text = getString(R.string.grades_subject_final, subject.getFormattedFinalGrade(requirements, currentHalfyear))

        val examJson = sharedPreferences.getString("exam_list", "[]")
        val examType = object : TypeToken<List<ExamFragment.ExamEntry>>() {}.type
        val allExams: List<ExamFragment.ExamEntry> = Gson().fromJson(examJson, examType) ?: emptyList()

        val examList = allExams.filter { it.subject == subject.subject && it.isOverdue() && it.mark != null }

        val examGradesAdapter = ExamGradesAdapter(examList)
        recyclerExamGrades.layoutManager = LinearLayoutManager(requireContext())
        recyclerExamGrades.adapter = examGradesAdapter

        dialog.setTitle("Details: ${subject.subject}")
            .setView(dialogView)
            .setPositiveButton(getString(R.string.grades_close), null)
            .show()
    }

    private fun showEditSubjectDialog(subject: SubjectGradeInfo) {
        val bildungsgang = sharedPreferences.getString("selected_bildungsgang", "")
        val useComplexGrading =
            bildungsgang == "BG" && sharedPreferences.getBoolean(PREFS_USE_SIMPLE_GRADING, false)
                .not()

        val dialog = AlertDialog.Builder(requireContext())
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_subject, null)

        val pointsToGradeMap = mapOf(
            15 to 0.7, 14 to 1.0, 13 to 1.3, 12 to 1.7, 11 to 2.0, 10 to 2.3,
            9 to 2.7, 8 to 3.0, 7 to 3.3, 6 to 3.7, 5 to 4.0, 4 to 4.3,
            3 to 4.7, 2 to 5.0, 1 to 5.3, 0 to 5.7
        )

        val gradeToPointsMap = pointsToGradeMap.entries.associate { (k, v) -> v to k }

        val sortedGrades = pointsToGradeMap.values.sorted()

        val convertPointsToGrade = { points: Int -> pointsToGradeMap[points] ?: 6.0 }
        val convertGradeToPoints = { grade: Double ->
            gradeToPointsMap[grade] ?: run {
                val nearestGrade = sortedGrades.firstOrNull { it >= grade } ?: sortedGrades.last()
                gradeToPointsMap[nearestGrade] ?: 0
            }
        }

        val snapToValidGrade = { inputGrade: Double ->
            if (gradeToPointsMap.containsKey(inputGrade)) {
                inputGrade
            } else {
                sortedGrades.firstOrNull { it >= inputGrade } ?: sortedGrades.last()
            }
        }

        val editOralGrade = dialogView.findViewById<EditText>(R.id.editOralGrade)
        val editPruefungsergebnis = dialogView.findViewById<EditText>(R.id.editPruefungsergebnis)
        val seekBarOral = dialogView.findViewById<SeekBar>(R.id.seekBarOral)
        val seekBarWritten = dialogView.findViewById<SeekBar>(R.id.seekBarWritten)
        val tvRatioDisplay = dialogView.findViewById<TextView>(R.id.tvRatioDisplay)
        val switchExamCount = dialogView.findViewById<Switch>(R.id.switchExamCount)
        val switchGradeSystem = dialogView.findViewById<Switch>(R.id.switchGradeSystem)
        val switchGradeRange = dialogView.findViewById<Switch>(R.id.switchGradeRange)
        val switchPruefungsfach = dialogView.findViewById<Switch>(R.id.switchPruefungsfach)
        val layoutPruefungsergebnis = dialogView.findViewById<LinearLayout>(R.id.layoutPruefungsergebnis)
        val seekBarHalfYears = dialogView.findViewById<SeekBar>(R.id.seekBarHalfYears)
        val tvHalfYearsCount = dialogView.findViewById<TextView>(R.id.tvHalfYearsCount)
        val tvGradeRange = dialogView.findViewById<TextView>(R.id.tvGradeRange)
        val tvSubjectInfo = dialogView.findViewById<TextView>(R.id.tvSubjectInfo)

        if (!useComplexGrading) {
            tvSubjectInfo.visibility = View.GONE
            switchPruefungsfach.visibility = View.GONE
            layoutPruefungsergebnis.visibility = View.GONE
            seekBarHalfYears.visibility = View.GONE
            tvHalfYearsCount.visibility = View.GONE

            val pruefungsfachCard =
                dialogView.findViewById<CardView>(R.id.cardPruefungsfach)
            pruefungsfachCard?.visibility = View.GONE

            val halfYearsCard =
                dialogView.findViewById<CardView>(R.id.cardHalfYears)
            halfYearsCard?.visibility = View.GONE
        }

        val isLK = subject.subject.endsWith("LK") || subject.subject.endsWith("L")
        if (isLK) {
            switchExamCount.isChecked = false // 2 exams
            switchExamCount.isEnabled = false
            seekBarOral.progress = 50
            seekBarWritten.progress = 50
            seekBarOral.isEnabled = false
            seekBarWritten.isEnabled = false
        } else {
            switchExamCount.isEnabled = true
            seekBarOral.isEnabled = true
            seekBarWritten.isEnabled = true
        }

        val gradeSystemSwitchesJson = sharedPreferences.getString(PREFS_SUBJECT_GRADE_SYSTEM, "{}")
        val gradeSystemSwitchesType = object : TypeToken<Map<String, Boolean>>() {}.type
        val gradeSystemSwitches: Map<String, Boolean> = try {
            Gson().fromJson(gradeSystemSwitchesJson, gradeSystemSwitchesType) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }

        val gradeRangeSwitchesJson = sharedPreferences.getString(PREFS_SUBJECT_RANGE_MODE, "{}")
        val gradeRangeSwitchesType = object : TypeToken<Map<String, Boolean>>() {}.type
        val gradeRangeSwitches: Map<String, Boolean> = try {
            Gson().fromJson(gradeRangeSwitchesJson, gradeRangeSwitchesType) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }

        switchGradeSystem.isChecked = gradeSystemSwitches[subject.subject] ?: true
        switchGradeRange.isChecked = gradeRangeSwitches[subject.subject] ?: false

        val requirements =
            if (useComplexGrading) getSubjectRequirements(subject.subject) else SubjectRequirements()
        val currentHalfyear = getCurrentHalfyear()
        var selectedPruefungsfaecher =
            if (useComplexGrading) gradeList.count { it.isPruefungsfach } else 0

        if (useComplexGrading) {
            tvSubjectInfo.text = subject.getWeightingInfo(requirements, currentHalfyear, requireContext())
        }

        val currentOralGrade = subject.oralGradeHistory[currentHalfyear]
        val currentPruefungsergebnis = if (useComplexGrading) subject.pruefungsergebnis else null

        val originalOralDecimal = currentOralGrade?.let { snapToValidGrade(it) }
        val originalPruefungDecimal = currentPruefungsergebnis?.let { snapToValidGrade(it) }

        var currentOralDisplayValue: String
        var currentPruefungDisplayValue: String

        seekBarOral.max = 100
        seekBarWritten.max = 100
        seekBarOral.progress = subject.ratio.first
        seekBarWritten.progress = subject.ratio.second

        if (useComplexGrading) {
            switchPruefungsfach.isChecked = subject.isPruefungsfach
            switchPruefungsfach.isEnabled = if (subject.isLK()) {
                false
            } else {
                requirements.canBePruefungsfach
            }
        }

        if (useComplexGrading) {
            seekBarHalfYears.max = requirements.maxSelectableHalfYears
            val minHalfYears = requirements.minRequiredHalfYears
            seekBarHalfYears.progress = maxOf(minHalfYears, subject.selectedHalfYears)
            seekBarHalfYears.isEnabled = !requirements.mustCountAllHalfYears
        }

        val updateHalfYearsDisplay = {
            if (useComplexGrading) {
                if (requirements.mustCountAllHalfYears) {
                    val allGrades = subject.getHalfYearGrades(currentHalfyear)
                    tvHalfYearsCount.text = getString(R.string.grades_all_semesters_count_with_count, allGrades.size)
                } else {
                    val minHalfYears = requirements.minRequiredHalfYears
                    val count = maxOf(minHalfYears, seekBarHalfYears.progress)
                    tvHalfYearsCount.text = getString(R.string.grades_best_semesters_count, count)
                }
            }
        }
        updateHalfYearsDisplay()

        if (!isLK) {
            val isOneExam = subject.ratio.first == 70 && subject.ratio.second == 30
            switchExamCount.isChecked = isOneExam
        }

        var isPointSystem = switchGradeSystem.isChecked
        var isRangeMode = switchGradeRange.isChecked

        val setupInputFilters = {
            if (isPointSystem) {
                // points mode -> max 2 characters, no commas
                editOralGrade.filters = arrayOf(
                    InputFilter.LengthFilter(2),
                    InputFilter { source, _, _, _, _, _ ->
                        if (source.contains(",") || source.contains(".")) "" else null
                    }
                )
                editOralGrade.inputType = InputType.TYPE_CLASS_NUMBER

                if (useComplexGrading) {
                    editPruefungsergebnis.filters = arrayOf(
                        InputFilter.LengthFilter(2),
                        InputFilter { source, _, _, _, _, _ ->
                            if (source.contains(",") || source.contains(".")) "" else null
                        }
                    )
                    editPruefungsergebnis.inputType = InputType.TYPE_CLASS_NUMBER
                }
            } else {
                // decimal mode -> max 4 characters, only one comma/dot allowed
                editOralGrade.filters = arrayOf(
                    InputFilter.LengthFilter(4),
                    InputFilter { source, start, end, dest, dstart, dend ->
                        val input = source.subSequence(start, end).toString()
                        val existing = dest.subSequence(0, dstart).toString() +
                                dest.subSequence(dend, dest.length).toString()
                        val result =
                            existing.substring(0, dstart) + input + existing.substring(dstart)

                        val commaCount = result.count { it == ',' || it == '.' }
                        if (commaCount > 1) return@InputFilter ""

                        if (result.length == 4 && result.contains('.')) {
                            val parts = result.split('.')
                            if (parts.size == 2 && parts[1].length == 2) {
                                val rounded = String.format("%.1f", result.toDoubleOrNull() ?: 0.0)
                                editOralGrade.setText(rounded)
                                editOralGrade.setSelection(rounded.length)
                                return@InputFilter ""
                            }
                        }

                        null
                    }
                )
                editOralGrade.inputType =
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

                if (useComplexGrading) {
                    editPruefungsergebnis.filters = arrayOf(
                        InputFilter.LengthFilter(4),
                        InputFilter { source, start, end, dest, dstart, dend ->
                            val input = source.subSequence(start, end).toString()
                            val existing = dest.subSequence(0, dstart).toString() +
                                    dest.subSequence(dend, dest.length).toString()
                            val result =
                                existing.substring(0, dstart) + input + existing.substring(dstart)

                            val commaCount = result.count { it == ',' || it == '.' }
                            if (commaCount > 1) return@InputFilter ""

                            if (result.length == 4 && result.contains('.')) {
                                val parts = result.split('.')
                                if (parts.size == 2 && parts[1].length == 2) {
                                    val rounded =
                                        String.format("%.1f", result.toDoubleOrNull() ?: 0.0)
                                    editPruefungsergebnis.setText(rounded)
                                    editPruefungsergebnis.setSelection(rounded.length)
                                    return@InputFilter ""
                                }
                            }

                            null
                        }
                    )
                    editPruefungsergebnis.inputType =
                        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                }
            }
        }

        val convertAndDisplayGrades = {
            if (originalOralDecimal != null) {
                currentOralDisplayValue = if (isPointSystem) {
                    val points = convertGradeToPoints(originalOralDecimal)
                    points.toString()
                } else {
                    DecimalFormat("0.0").format(originalOralDecimal)
                }
                editOralGrade.setText(currentOralDisplayValue)
            }

            if (useComplexGrading && originalPruefungDecimal != null) {
                currentPruefungDisplayValue = if (isPointSystem) {
                    val points = convertGradeToPoints(originalPruefungDecimal)
                    points.toString()
                } else {
                    DecimalFormat("0.0").format(originalPruefungDecimal)
                }
                editPruefungsergebnis.setText(currentPruefungDisplayValue)
            }
        }

        val updateRatioDisplay = {
            val oral = seekBarOral.progress
            val written = seekBarWritten.progress
            tvRatioDisplay.text = getString(R.string.grades_subject_ratio, oral, written)
        }
        updateRatioDisplay()

        val updatePruefungsergebnisVisibility = {
            if (useComplexGrading) {
                val showPruefung = switchPruefungsfach.isChecked
                layoutPruefungsergebnis.visibility = if (showPruefung) View.VISIBLE else View.GONE
            }
        }
        updatePruefungsergebnisVisibility()

        val validatePruefungsfachLimit = {
            if (useComplexGrading) {
                if (switchPruefungsfach.isChecked && !subject.isPruefungsfach) {
                    if (selectedPruefungsfaecher >= 5) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.grades_max_exam_subjects),
                            Toast.LENGTH_SHORT
                        ).show()
                        switchPruefungsfach.isChecked = false
                        false
                    } else {
                        selectedPruefungsfaecher++
                        true
                    }
                } else if (!switchPruefungsfach.isChecked && subject.isPruefungsfach) {
                    selectedPruefungsfaecher--
                    true
                } else {
                    true
                }
            } else {
                true
            }
        }

        val updateGradeRange = {
            if (isRangeMode && editOralGrade.text.isNotEmpty()) {
                try {
                    val input = editOralGrade.text.toString().replace(",", ".").toDouble()
                    if (isPointSystem) {
                        val currentPoints = input.toInt().coerceIn(0, 15)
                        val nextBetterPoints = (currentPoints + 1).coerceAtMost(15)

                        if (nextBetterPoints != currentPoints) {
                            tvGradeRange.text = getString(R.string.grades_points_range, currentPoints, nextBetterPoints)
                        } else {
                            tvGradeRange.text = getString(R.string.grades_points_single, currentPoints)
                        }
                        tvGradeRange.visibility = View.VISIBLE
                    } else {
                        // snap input to a valid grade
                        val snappedGrade = snapToValidGrade(input.coerceIn(1.0, 6.0))
                        val currentPoints = convertGradeToPoints(snappedGrade)
                        val nextBetterPoints = (currentPoints + 1).coerceAtMost(15)
                        val betterGrade = if (nextBetterPoints <= 15) convertPointsToGrade(nextBetterPoints) else snappedGrade

                        if (betterGrade != snappedGrade) {
                            tvGradeRange.text = getString(R.string.grades_grade_range, DecimalFormat("0.0").format(snappedGrade), DecimalFormat("0.0").format(betterGrade))
                        } else {
                            tvGradeRange.text = getString(R.string.grades_grade_single, DecimalFormat("0.0").format(snappedGrade))
                        }
                        tvGradeRange.visibility = View.VISIBLE
                    }
                } catch (_: NumberFormatException) {
                    tvGradeRange.visibility = View.GONE
                }
            } else {
                tvGradeRange.visibility = View.GONE
            }
        }

        val saveSwitchStates = {
            val updatedGradeSystemSwitches = gradeSystemSwitches.toMutableMap()
            updatedGradeSystemSwitches[subject.subject] = isPointSystem
            sharedPreferences.edit {
                putString(PREFS_SUBJECT_GRADE_SYSTEM, Gson().toJson(updatedGradeSystemSwitches))
            }

            val updatedGradeRangeSwitches = gradeRangeSwitches.toMutableMap()
            updatedGradeRangeSwitches[subject.subject] = isRangeMode
            sharedPreferences.edit {
                putString(PREFS_SUBJECT_RANGE_MODE, Gson().toJson(updatedGradeRangeSwitches))
            }
        }

        val updateHints = {
            if (isPointSystem) {
                editOralGrade.hint = getString(R.string.dlg_edit_sub_oral_grade_hint)
                if (useComplexGrading) {
                    editPruefungsergebnis.hint = getString(R.string.dlg_edit_sub_exam_result_hint)
                }
            } else {
                editOralGrade.hint = getString(R.string.grades_edit_grade_hint)
                if (useComplexGrading) {
                    editPruefungsergebnis.hint = getString(R.string.grades_edit_exam_grade_hint)
                }
            }
        }

        setupInputFilters()
        updateHints()
        convertAndDisplayGrades()
        updateGradeRange()

        if (useComplexGrading) {
            switchPruefungsfach.setOnCheckedChangeListener { _, _ ->
                if (validatePruefungsfachLimit()) {
                    updatePruefungsergebnisVisibility()
                }
            }
        }

        if (!isLK) {
            switchExamCount.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    seekBarOral.progress = 70
                    seekBarWritten.progress = 30
                } else {
                    seekBarOral.progress = 50
                    seekBarWritten.progress = 50
                }
                updateRatioDisplay()
            }
        }

        switchGradeSystem.setOnCheckedChangeListener { _, isChecked ->
            isPointSystem = isChecked
            setupInputFilters()
            updateHints()
            convertAndDisplayGrades()
            updateGradeRange()
        }

        switchGradeRange.setOnCheckedChangeListener { _, isChecked ->
            isRangeMode = isChecked
            updateGradeRange()
        }

        editOralGrade.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateGradeRange()
            }
        })

        if (!isLK) {
            seekBarOral.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    if (fromUser) {
                        seekBarWritten.progress = 100 - progress
                        updateRatioDisplay()
                        switchExamCount.isChecked = (progress == 70)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })

            seekBarWritten.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    if (fromUser) {
                        seekBarOral.progress = 100 - progress
                        updateRatioDisplay()
                        switchExamCount.isChecked = (seekBarOral.progress == 70)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        if (useComplexGrading) {
            seekBarHalfYears.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    if (fromUser) {
                        val minHalfYears = requirements.minRequiredHalfYears
                        val adjustedProgress = maxOf(minHalfYears, progress)
                        if (adjustedProgress != progress) {
                            seekBar?.progress = adjustedProgress
                        }
                        updateHalfYearsDisplay()
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        dialog.setTitle(getString(R.string.grades_edit_title, subject.subject))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.grades_save)) { _, _ ->
                val oralGradeText = editOralGrade.text.toString().trim()
                val pruefungsergebnisText =
                    if (useComplexGrading) editPruefungsergebnis.text.toString().trim() else ""
                var newOralGrade: Double? = null
                var newPruefungsergebnis: Double? = null

                if (oralGradeText.isNotEmpty()) {
                    try {
                        if (isPointSystem) {
                            val points = oralGradeText.toInt()
                            if (points >= 0 && points <= 15) {
                                newOralGrade = convertPointsToGrade(points)
                            }
                        } else {
                            val grade = oralGradeText.replace(",", ".").toDouble()
                            if (grade >= 1.0 && grade <= 6.0) {
                                // snap to valid grade
                                newOralGrade = snapToValidGrade(grade)
                            }
                        }
                    } catch (_: NumberFormatException) {
                        newOralGrade = null
                    }
                }

                if (useComplexGrading && pruefungsergebnisText.isNotEmpty()) {
                    try {
                        if (isPointSystem) {
                            val points = pruefungsergebnisText.toInt()
                            if (points >= 0 && points <= 15) {
                                newPruefungsergebnis = convertPointsToGrade(points)
                            }
                        } else {
                            val grade = pruefungsergebnisText.replace(",", ".").toDouble()
                            if (grade >= 1.0 && grade <= 6.0) {
                                newPruefungsergebnis = snapToValidGrade(grade)
                            }
                        }
                    } catch (_: NumberFormatException) {
                        newPruefungsergebnis = null
                    }
                }

                val newRatio = Pair(seekBarOral.progress, seekBarWritten.progress)
                val isPruefungsfach =
                    if (useComplexGrading) switchPruefungsfach.isChecked else false
                val selectedHalfYears = if (useComplexGrading) {
                    if (requirements.mustCountAllHalfYears) {
                        subject.getHalfYearGrades(currentHalfyear).size
                    } else {
                        maxOf(requirements.minRequiredHalfYears, seekBarHalfYears.progress)
                    }
                } else {
                    1
                }

                saveSwitchStates()

                saveSubjectGradeData(
                    subject.subject,
                    newOralGrade,
                    newRatio,
                    isPruefungsfach,
                    newPruefungsergebnis,
                    selectedHalfYears
                )
                loadGrades()
                updateFinalGrade()

                Toast.makeText(requireContext(), getString(R.string.grades_saved), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                saveSwitchStates()
            }
            .show()
    }

    private fun saveSubjectGradeData(subject: String, oralGrade: Double?, ratio: Pair<Int, Int>,
                                     isPruefungsfach: Boolean, pruefungsergebnis: Double?, selectedHalfYears: Int) {
        val currentHalfyear = getCurrentHalfyear()

        val oralGradesHistoryJson = sharedPreferences.getString(PREFS_ORAL_GRADES_HISTORY, "{}")
        val oralGradesHistoryType = object : TypeToken<MutableMap<String, MutableMap<Int, Double?>>>() {}.type
        val oralGradesHistory: MutableMap<String, MutableMap<Int, Double?>> = try {
            Gson().fromJson(oralGradesHistoryJson, oralGradesHistoryType) ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }

        if (!oralGradesHistory.containsKey(subject)) {
            oralGradesHistory[subject] = mutableMapOf()
        }

        if (oralGrade != null) {
            oralGradesHistory[subject]!![currentHalfyear] = oralGrade
        } else {
            oralGradesHistory[subject]!!.remove(currentHalfyear)
        }

        val ratiosJson = sharedPreferences.getString(PREFS_GRADE_RATIOS, "{}")
        val ratiosType = object : TypeToken<MutableMap<String, Pair<Int, Int>>>() {}.type
        val ratios: MutableMap<String, Pair<Int, Int>> = try {
            Gson().fromJson(ratiosJson, ratiosType) ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }

        ratios[subject] = ratio

        val pruefungsfaecherJson = sharedPreferences.getString(PREFS_PRUEFUNGSFAECHER, "{}")
        val pruefungsfaecherType = object : TypeToken<MutableMap<String, Boolean>>() {}.type
        val pruefungsfaecher: MutableMap<String, Boolean> = try {
            Gson().fromJson(pruefungsfaecherJson, pruefungsfaecherType) ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }

        pruefungsfaecher[subject] = isPruefungsfach

        val pruefungsergebnisseJson = sharedPreferences.getString(PREFS_PRUEFUNGSERGEBNISSE, "{}")
        val pruefungsergebnisseType = object : TypeToken<MutableMap<String, Double>>() {}.type
        val pruefungsergebnisse: MutableMap<String, Double> = try {
            Gson().fromJson(pruefungsergebnisseJson, pruefungsergebnisseType) ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }

        if (pruefungsergebnis != null) {
            pruefungsergebnisse[subject] = pruefungsergebnis
        } else {
            pruefungsergebnisse.remove(subject)
        }

        val selectedHalfYearsJson = sharedPreferences.getString(PREFS_SELECTED_HALF_YEARS, "{}")
        val selectedHalfYearsType = object : TypeToken<MutableMap<String, Int>>() {}.type
        val selectedHalfYearsMap: MutableMap<String, Int> = try {
            Gson().fromJson(selectedHalfYearsJson, selectedHalfYearsType) ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }

        selectedHalfYearsMap[subject] = selectedHalfYears

        sharedPreferences.edit {
            putString(PREFS_ORAL_GRADES_HISTORY, Gson().toJson(oralGradesHistory))
                .putString(PREFS_GRADE_RATIOS, Gson().toJson(ratios))
                .putString(PREFS_PRUEFUNGSFAECHER, Gson().toJson(pruefungsfaecher))
                .putString(PREFS_PRUEFUNGSERGEBNISSE, Gson().toJson(pruefungsergebnisse))
                .putString(PREFS_SELECTED_HALF_YEARS, Gson().toJson(selectedHalfYearsMap))
        }
    }

    private fun showStartNewHalfyearDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.grades_new_halfyear_title))
            .setMessage(getString(R.string.grades_new_halfyear_message))
            .setPositiveButton(getString(R.string.grades_new_halfyear_confirm)) { _, _ ->
                startNewHalfyear()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun startNewHalfyear() {
        val currentHalfyear = getCurrentHalfyear()
        val bildungsgang = sharedPreferences.getString("selected_bildungsgang", "")
        val useComplexGrading = bildungsgang == "BG" && sharedPreferences.getBoolean(PREFS_USE_SIMPLE_GRADING, false).not()

        if (useComplexGrading) {
            val currentFinalGrades = gradeList.mapNotNull {
                val requirements = getSubjectRequirements(it.subject)
                it.getFinalGrade(requirements, currentHalfyear)
            }
            if (currentFinalGrades.isNotEmpty()) {
                val average = currentFinalGrades.average()
                saveGradeToHistory(average)
            }
        } else {
            val currentGrades = mutableListOf<Double>()
            for (subject in gradeList) {
                val oralGrade = subject.oralGradeHistory[currentHalfyear]
                val writtenGrades = subject.writtenGradesHistory[currentHalfyear]?.takeIf { it.isNotEmpty() }?.average()

                val finalGrade = when {
                    writtenGrades != null && oralGrade != null -> {
                        val writtenWeight = subject.ratio.second / 100.0
                        val oralWeight = subject.ratio.first / 100.0
                        (writtenGrades * writtenWeight) + (oralGrade * oralWeight)
                    }
                    writtenGrades != null && oralGrade == null -> writtenGrades
                    writtenGrades == null && oralGrade != null -> oralGrade
                    else -> null
                }

                if (finalGrade != null) {
                    currentGrades.add(finalGrade)
                }
            }

            if (currentGrades.isNotEmpty()) {
                val average = currentGrades.average()
                saveGradeToHistory(average)
            }
        }

        val oralGradesHistoryJson = sharedPreferences.getString(PREFS_ORAL_GRADES_HISTORY, "{}")
        val oralGradesHistoryType = object : TypeToken<MutableMap<String, MutableMap<Int, Double?>>>() {}.type
        val oralGradesHistory: MutableMap<String, MutableMap<Int, Double?>> = try {
            Gson().fromJson(oralGradesHistoryJson, oralGradesHistoryType) ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }

        val writtenGradesHistoryJson = sharedPreferences.getString(PREFS_WRITTEN_GRADES_HISTORY, "{}")
        val writtenGradesHistoryType = object : TypeToken<MutableMap<String, MutableMap<Int, List<Double>>>>() {}.type
        val writtenGradesHistory: MutableMap<String, MutableMap<Int, List<Double>>> = try {
            Gson().fromJson(writtenGradesHistoryJson, writtenGradesHistoryType) ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }

        val examJson = sharedPreferences.getString("exam_list", "[]")
        val examType = object : TypeToken<List<ExamFragment.ExamEntry>>() {}.type
        val examList: List<ExamFragment.ExamEntry> = Gson().fromJson(examJson, examType) ?: emptyList()

        for (subject in gradeList) {
            if (!oralGradesHistory.containsKey(subject.subject)) {
                oralGradesHistory[subject.subject] = mutableMapOf()
            }
            if (!writtenGradesHistory.containsKey(subject.subject)) {
                writtenGradesHistory[subject.subject] = mutableMapOf()
            }

            val currentOralGrade = subject.oralGradeHistory[currentHalfyear]
            if (currentOralGrade != null) {
                oralGradesHistory[subject.subject]!![currentHalfyear] = currentOralGrade
            }

            val currentWrittenGrades = examList
                .filter { it.subject == subject.subject && it.isOverdue() && it.mark != null }
                .mapNotNull { it.getNumericalGrade() }

            if (currentWrittenGrades.isNotEmpty()) {
                writtenGradesHistory[subject.subject]!![currentHalfyear] = currentWrittenGrades
            }
        }

        sharedPreferences.edit {
            putString(PREFS_ORAL_GRADES_HISTORY, Gson().toJson(oralGradesHistory))
                .putString(PREFS_WRITTEN_GRADES_HISTORY, Gson().toJson(writtenGradesHistory))
        }

        sharedPreferences.edit {
            remove(PREFS_ORAL_GRADES)
        }

        val newHalfyear = currentHalfyear + 1
        sharedPreferences.edit {
            putInt(PREFS_CURRENT_HALFYEAR, newHalfyear)
        }

        sharedPreferences.edit {
            remove("exam_list")
        }

        loadGrades()
        updateFinalGrade()

        Toast.makeText(requireContext(), getString(R.string.grades_new_halfyear_started, newHalfyear), Toast.LENGTH_SHORT).show()
    }

    private fun saveGradeToHistory(grade: Double) {
        val historyJson = sharedPreferences.getString(PREFS_GRADE_HISTORY, "[]")
        val historyType = object : TypeToken<MutableList<GradeHistoryEntry>>() {}.type
        val history: MutableList<GradeHistoryEntry> = Gson().fromJson(historyJson, historyType) ?: mutableListOf()

        val calendar = Calendar.getInstance()
        val currentMonth = calendar.get(Calendar.MONTH) + 1
        val currentYear = calendar.get(Calendar.YEAR)

        val existingEntry = history.find { it.month == currentMonth && it.year == currentYear }
        if (existingEntry != null) {
            existingEntry.grade = grade
        } else {
            history.add(GradeHistoryEntry(currentMonth, currentYear, grade))
        }

        history.sortWith { a, b ->
            when {
                a.year != b.year -> a.year.compareTo(b.year)
                else -> a.month.compareTo(b.month)
            }
        }

        sharedPreferences.edit {
            putString(PREFS_GRADE_HISTORY, Gson().toJson(history))
        }
    }

    private fun showSetGoalGradeDialog() {
        val currentGoal = sharedPreferences.getFloat(PREFS_GOAL_GRADE, 2.0f)

        val editText = EditText(requireContext()).apply {
            setText(DecimalFormat("0.0").format(currentGoal))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "1.0 - 6.0"
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.grades_menu_set_goal))
            .setMessage(getString(R.string.grades_set_goal_message))
            .setView(editText)
            .setPositiveButton(getString(R.string.grades_save)) { _, _ ->
                try {
                    val goalGrade = editText.text.toString().replace(",", ".").toFloat()
                    if (goalGrade >= 1.0f && goalGrade <= 6.0f) {
                        sharedPreferences.edit {
                            putFloat(PREFS_GOAL_GRADE, goalGrade)
                        }
                        updateFinalGrade()
                        Toast.makeText(requireContext(), getString(R.string.grades_set_goal_saved), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.grades_set_goal_invalid_range), Toast.LENGTH_SHORT).show()
                    }
                } catch (_: NumberFormatException) {
                    Toast.makeText(requireContext(), getString(R.string.grades_set_goal_invalid_input), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showResetGraphDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Graph zurücksetzen")
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setMessage(getString(R.string.grades_reset_graph_message))
            .setPositiveButton(getString(R.string.grades_reset_graph_confirm)) { _, _ ->
                sharedPreferences.edit {
                    remove(PREFS_GRADE_HISTORY)
                }
                Toast.makeText(requireContext(), getString(R.string.grades_reset_graph_complete), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showExportOptions() {
        val content = exportGradeData()

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
            .setTitle(getString(R.string.grades_export_title))
            .setAdapter(adapter) { _, which ->
                when (which) {
                    0 -> saveExportToFile(content)
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
            .setTitle(getString(R.string.grades_import_title))
            .setAdapter(adapter) { _, which ->
                when (which) {
                    0 -> importFromFilePicker()
                    1 -> importFromClipboard()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun exportGradeData(): String {
        return backupManager.exportGradeData()
    }

    private fun importFromFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        exportLauncher.launch(intent)
    }

    private fun importFromClipboard() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip

        if (clip != null && clip.itemCount > 0) {
            val content = clip.getItemAt(0).text.toString()
            importGradeData(content)
        } else {
            Toast.makeText(requireContext(), getString(R.string.set_act_import_clipboard_empty), Toast.LENGTH_SHORT).show()
        }
    }

    private fun importGradeData(content: String) {
        try {
            backupManager.importGradeData(content)

            loadGrades()
            updateFinalGrade()

            Toast.makeText(requireContext(), getString(R.string.grades_import_success), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.set_act_import_error, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun saveExportToFile(content: String) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.GERMAN).format(Date())
        val filename = "noten_export_${timestamp}.hksn"

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, filename)
        }

        sharedPreferences.edit {putString("temp_export_content", content) }

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
        } catch (_: Exception) {
            Toast.makeText(requireContext(), getString(R.string.grades_export_error), Toast.LENGTH_LONG).show()
        }
    }

    private fun copyToClipboard(content: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Noten Export", content)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), getString(R.string.slide_export_copied), Toast.LENGTH_SHORT).show()
    }

    private fun getSubjectRequirements(subject: String): SubjectRequirements {
        return when {
            // LK
            subject.endsWith("LK") || subject.endsWith("L") -> SubjectRequirements(
                mustCountAllHalfYears = true,
                minRequiredHalfYears = 4,
                maxSelectableHalfYears = 4,
                canBePruefungsfach = true,
                reservedCourseSlots = 4
            )

            // core subjects (ma, de, en)
            subject.startsWith("Ma") || subject.startsWith("De") || subject.startsWith("En") -> SubjectRequirements(
                mustCountAllHalfYears = true,
                minRequiredHalfYears = 4,
                maxSelectableHalfYears = 4,
                canBePruefungsfach = true,
                reservedCourseSlots = 3
            )

            // french
            subject.startsWith("Fr") -> SubjectRequirements(
                mustCountAllHalfYears = false,
                minRequiredHalfYears = 2,
                maxSelectableHalfYears = 4,
                canBePruefungsfach = true,
                reservedCourseSlots = 2
            )

            // powi
            subject.startsWith("PoWi", ignoreCase = true) || subject.startsWith("Powi", ignoreCase = true) -> SubjectRequirements(
                mustCountAllHalfYears = false,
                minRequiredHalfYears = 1,
                maxSelectableHalfYears = 4,
                canBePruefungsfach = true,
                reservedCourseSlots = 0
            )

            // geschichte
            subject.startsWith("Ge") -> SubjectRequirements(
                mustCountAllHalfYears = false,
                minRequiredHalfYears = 2,
                maxSelectableHalfYears = 4,
                canBePruefungsfach = true,
                reservedCourseSlots = 0
            )

            // other
            else -> SubjectRequirements(
                mustCountAllHalfYears = false,
                minRequiredHalfYears = 1,
                maxSelectableHalfYears = 4,
                canBePruefungsfach = true,
                reservedCourseSlots = 0
            )
        }
    }

    private fun getCurrentHalfyear(): Int {
        return sharedPreferences.getInt(PREFS_CURRENT_HALFYEAR, 1)
    }

    private fun checkForSubjectChanges() {
        val currentSubjects = sharedPreferences.getString("student_subjects", "") ?: ""
        val currentTeachers = sharedPreferences.getString("student_teachers", "") ?: ""
        val currentRooms = sharedPreferences.getString("student_rooms", "") ?: ""

        val gradesSubjects = sharedPreferences.getString(PREFS_GRADES_SUBJECTS, "")
        val gradesTeachers = sharedPreferences.getString(PREFS_GRADES_TEACHERS, "")
        val gradesRooms = sharedPreferences.getString(PREFS_GRADES_ROOMS, "")

        val isFirstTimeSetup = gradesSubjects.isNullOrEmpty() && hasNoGradeData()

        if (isFirstTimeSetup) {
            saveCurrentSubjectsAsGradesSubjects()
            hasSubjectChanges = false
        } else if (gradesSubjects.isNullOrEmpty()) {
            saveCurrentSubjectsAsGradesSubjects()
            hasSubjectChanges = false
        } else {
            // check for changes
            hasSubjectChanges = currentSubjects != gradesSubjects || currentTeachers != gradesTeachers || currentRooms != gradesRooms
        }

        if (hasSubjectChanges) {
            showSubjectChangeWarning()
        } else {
            hideSubjectChangeWarning()
        }
    }

    private fun hasNoGradeData(): Boolean {
        val oralGradesHistory = sharedPreferences.getString(PREFS_ORAL_GRADES_HISTORY, "{}")
        val writtenGradesHistory = sharedPreferences.getString(PREFS_WRITTEN_GRADES_HISTORY, "{}")
        val ratios = sharedPreferences.getString(PREFS_GRADE_RATIOS, "{}")

        return (oralGradesHistory == "{}" || oralGradesHistory.isNullOrEmpty()) &&
                (writtenGradesHistory == "{}" || writtenGradesHistory.isNullOrEmpty()) &&
                (ratios == "{}" || ratios.isNullOrEmpty())
    }

    private fun saveCurrentSubjectsAsGradesSubjects() {
        val currentSubjects = sharedPreferences.getString("student_subjects", "") ?: ""
        val currentTeachers = sharedPreferences.getString("student_teachers", "") ?: ""
        val currentRooms = sharedPreferences.getString("student_rooms", "") ?: ""

        sharedPreferences.edit {
            putString(PREFS_GRADES_SUBJECTS, currentSubjects)
                .putString(PREFS_GRADES_TEACHERS, currentTeachers)
                .putString(PREFS_GRADES_ROOMS, currentRooms)
        }
    }

    private fun showSubjectChangeWarning() {
        layoutSubjectChangeWarning.visibility = View.VISIBLE
    }

    private fun hideSubjectChangeWarning() {
        layoutSubjectChangeWarning.visibility = View.GONE
    }

    private fun setupSubjectChangeListeners() {
        btnAcceptSubjectChanges.setOnClickListener {
            acceptSubjectChanges()
        }

        btnDeclineSubjectChanges.setOnClickListener {
            showDeclineSubjectChangesDialog()
        }
    }

    private fun acceptSubjectChanges() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.grades_subject_update_title))
            .setMessage(getString(R.string.grades_subject_update_message))
            .setPositiveButton(getString(R.string.grades_subject_update_confirm)) { _, _ ->
                val oldSubjects = getGradesSubjects()
                val newSubjects = sharedPreferences.getString("student_subjects", "")?.split(",")?.map { it.trim() } ?: emptyList()

                migrateGradeDataForSubjectChanges(oldSubjects, newSubjects)

                saveCurrentSubjectsAsGradesSubjects()
                hasSubjectChanges = false
                hideSubjectChangeWarning()
                loadGrades()
                updateFinalGrade()

                val migratedCount = newSubjects.count { newSubject ->
                    findMatchingSubject(newSubject, oldSubjects) != null
                }
                val totalOldSubjects = oldSubjects.size
                val totalNewSubjects = newSubjects.size

                Toast.makeText(
                    requireContext(),
                    getString(R.string.grades_subject_migration_complete, migratedCount, totalNewSubjects, totalOldSubjects),
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showDeclineSubjectChangesDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.grades_subject_reset_title))
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setMessage(getString(R.string.grades_subject_reset_message))
            .setPositiveButton(getString(R.string.set_act_yes_reset)) { _, _ ->
                restoreSubjectsFromGradesData()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun restoreSubjectsFromGradesData() {
        val gradesSubjects = sharedPreferences.getString(PREFS_GRADES_SUBJECTS, "") ?: ""
        val gradesTeachers = sharedPreferences.getString(PREFS_GRADES_TEACHERS, "") ?: ""
        val gradesRooms = sharedPreferences.getString(PREFS_GRADES_ROOMS, "") ?: ""

        sharedPreferences.edit {
            putString("student_subjects", gradesSubjects)
                .putString("student_teachers", gradesTeachers)
                .putString("student_rooms", gradesRooms)
        }

        hasSubjectChanges = false
        hideSubjectChangeWarning()
        Toast.makeText(requireContext(), getString(R.string.grades_subject_reset_complete), Toast.LENGTH_SHORT).show()
    }

    private fun getGradesSubjects(): List<String> {
        val gradesSubjects = sharedPreferences.getString(PREFS_GRADES_SUBJECTS, "") ?: ""
        return if (gradesSubjects.isEmpty()) emptyList() else gradesSubjects.split(",")
    }

    private fun getGradesTeachers(): List<String> {
        val gradesTeachers = sharedPreferences.getString(PREFS_GRADES_TEACHERS, "") ?: ""
        return if (gradesTeachers.isEmpty()) emptyList() else gradesTeachers.split(",")
    }

    data class GradeHistoryEntry(
        val month: Int,
        val year: Int,
        var grade: Double
    )

    private fun extractSubjectBaseName(subject: String): String {
        val firstHyphenIndex = subject.indexOf("-")

        // no hyphen
        if (firstHyphenIndex == -1) {
            return subject
        }

        val prefix = subject.substring(0, firstHyphenIndex)
        val remainder = subject.substring(firstHyphenIndex + 1)

        // find end of course (next hyphen or end of string)
        val nextHyphenIndex = remainder.indexOf("-")

        return if (nextHyphenIndex == -1) {
            // no second hyphen
            prefix
        } else {
            // second hyphen found => keep prefix + everything after the course
            prefix + remainder.substring(nextHyphenIndex)
        }
    }

    private fun findMatchingSubject(targetSubject: String, availableSubjects: List<String>): String? {
        val targetBaseName = extractSubjectBaseName(targetSubject)

        return availableSubjects.find { subject ->
            extractSubjectBaseName(subject) == targetBaseName
        }
    }

    private fun migrateGradeDataForSubjectChanges(oldSubjects: List<String>, newSubjects: List<String>) {
        val oralGradesHistoryJson = sharedPreferences.getString(PREFS_ORAL_GRADES_HISTORY, "{}")
        val oralGradesHistoryType = object : TypeToken<MutableMap<String, MutableMap<Int, Double?>>>() {}.type
        val oralGradesHistory: MutableMap<String, MutableMap<Int, Double?>> = try {
            Gson().fromJson(oralGradesHistoryJson, oralGradesHistoryType) ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }

        val writtenGradesHistoryJson = sharedPreferences.getString(PREFS_WRITTEN_GRADES_HISTORY, "{}")
        val writtenGradesHistoryType = object : TypeToken<MutableMap<String, MutableMap<Int, List<Double>>>>() {}.type
        val writtenGradesHistory: MutableMap<String, MutableMap<Int, List<Double>>> = try {
            Gson().fromJson(writtenGradesHistoryJson, writtenGradesHistoryType) ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }

        val ratiosJson = sharedPreferences.getString(PREFS_GRADE_RATIOS, "{}")
        val ratiosType = object : TypeToken<MutableMap<String, Any>>() {}.type
        val ratiosRaw: MutableMap<String, Any> = try {
            Gson().fromJson(ratiosJson, ratiosType) ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }

        val ratios = mutableMapOf<String, Pair<Int, Int>>()
        for ((key, value) in ratiosRaw) {
            when (value) {
                is Map<*, *> -> {
                    try {
                        val first = (value["first"] as? Number)?.toInt() ?: 50
                        val second = (value["second"] as? Number)?.toInt() ?: 50
                        ratios[key] = Pair(first, second)
                    } catch (e: Exception) {
                        ratios[key] = Pair(50, 50)
                    }
                }
                else -> {
                    ratios[key] = Pair(50, 50)
                }
            }
        }

        val pruefungsfaecherJson = sharedPreferences.getString(PREFS_PRUEFUNGSFAECHER, "{}")
        val pruefungsfaecherType = object : TypeToken<MutableMap<String, Boolean>>() {}.type
        val pruefungsfaecher: MutableMap<String, Boolean> = try {
            Gson().fromJson(pruefungsfaecherJson, pruefungsfaecherType) ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }

        val pruefungsergebnisseJson = sharedPreferences.getString(PREFS_PRUEFUNGSERGEBNISSE, "{}")
        val pruefungsergebnisseType = object : TypeToken<MutableMap<String, Double>>() {}.type
        val pruefungsergebnisse: MutableMap<String, Double> = try {
            Gson().fromJson(pruefungsergebnisseJson, pruefungsergebnisseType) ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }

        val selectedHalfYearsJson = sharedPreferences.getString(PREFS_SELECTED_HALF_YEARS, "{}")
        val selectedHalfYearsType = object : TypeToken<MutableMap<String, Int>>() {}.type
        val selectedHalfYearsMap: MutableMap<String, Int> = try {
            Gson().fromJson(selectedHalfYearsJson, selectedHalfYearsType) ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }

        val newOralGradesHistory = mutableMapOf<String, MutableMap<Int, Double?>>()
        val newWrittenGradesHistory = mutableMapOf<String, MutableMap<Int, List<Double>>>()
        val newRatios = mutableMapOf<String, Pair<Int, Int>>()
        val newPruefungsfaecher = mutableMapOf<String, Boolean>()
        val newPruefungsergebnisse = mutableMapOf<String, Double>()
        val newSelectedHalfYears = mutableMapOf<String, Int>()

        for (newSubject in newSubjects) {
            val matchingOldSubject = findMatchingSubject(newSubject, oldSubjects)

            if (matchingOldSubject != null) {
                oralGradesHistory[matchingOldSubject]?.let {
                    newOralGradesHistory[newSubject] = it
                }
                writtenGradesHistory[matchingOldSubject]?.let {
                    newWrittenGradesHistory[newSubject] = it
                }
                ratios[matchingOldSubject]?.let {
                    newRatios[newSubject] = it
                }
                pruefungsfaecher[matchingOldSubject]?.let {
                    newPruefungsfaecher[newSubject] = it
                }
                pruefungsergebnisse[matchingOldSubject]?.let {
                    newPruefungsergebnisse[newSubject] = it
                }
                selectedHalfYearsMap[matchingOldSubject]?.let {
                    newSelectedHalfYears[newSubject] = it
                }
            }
        }

        sharedPreferences.edit {
            putString(PREFS_ORAL_GRADES_HISTORY, Gson().toJson(newOralGradesHistory))
                .putString(PREFS_WRITTEN_GRADES_HISTORY, Gson().toJson(newWrittenGradesHistory))
                .putString(PREFS_GRADE_RATIOS, Gson().toJson(newRatios))
                .putString(PREFS_PRUEFUNGSFAECHER, Gson().toJson(newPruefungsfaecher))
                .putString(PREFS_PRUEFUNGSERGEBNISSE, Gson().toJson(newPruefungsergebnisse))
                .putString(PREFS_SELECTED_HALF_YEARS, Gson().toJson(newSelectedHalfYears))
        }
    }
}