package com.thecooker.vertretungsplaner.ui.grades

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.thecooker.vertretungsplaner.R
import com.thecooker.vertretungsplaner.data.ExamManager
import com.thecooker.vertretungsplaner.ui.exams.ExamFragment.ExamEntry
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.round
import kotlin.collections.filter
import com.thecooker.vertretungsplaner.ui.exams.ExamFragment
import android.R.attr.gravity
import com.thecooker.vertretungsplaner.utils.BackupManager

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

        fun getWeightingInfo(requirements: SubjectRequirements, currentHalfyear: Int): String {
            val info = mutableListOf<String>()

            if (isLK()) info.add("LK - alle Halbjahre zählen")
            else if (isCoreSubject()) info.add("Kernfach - alle Halbjahre zählen")
            else if (requirements.mustCountAllHalfYears) info.add("Alle Halbjahre zählen")
            else info.add("${getBestHalfYears(requirements, currentHalfyear).size} beste Halbjahre zählen")

            if (isPruefungsfach) {
                info.add("Prüfungsfach")
                if (pruefungsergebnis != null) info.add("Prüfungsergebnis: 4x Gewichtung")
            }

            if (requirements.minRequiredHalfYears > 1) {
                info.add("Mind. ${requirements.minRequiredHalfYears} Halbjahre erforderlich")
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
        private const val TAG = "GradesFragment"
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

    private fun showLoadingState() {
        loadingView = LayoutInflater.from(requireContext()).inflate(android.R.layout.simple_list_item_1, null)

        val loadingText = loadingView.findViewById<TextView>(android.R.id.text1)
        loadingText.apply {
            text = "Noten werden geladen..."
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(32, 64, 32, 64)
            setTextColor(resources.getColor(android.R.color.black))
            setTypeface(null, android.graphics.Typeface.BOLD)
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
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    val tempContent = sharedPreferences.getString("temp_export_content", "")
                    if (tempContent?.isNotEmpty() == true) {
                        saveToSelectedFile(uri, tempContent)
                        sharedPreferences.edit().remove("temp_export_content").apply()
                    } else {
                        try {
                            requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                                val content = inputStream.bufferedReader().use { it.readText() }
                                importGradeData(content)
                            }
                        } catch (e: Exception) {
                            Toast.makeText(requireContext(), "Fehler beim Lesen der Datei: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun showNoTimetableMessage(view: View) {
        val messageView = TextView(requireContext()).apply {
            text = "Du musst zuerst deinen Stundenplan scannen, um deine Noten zu sehen."
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
        popup.menu.add(0, 1, 0, "Neues Halbjahr beginnen")
        popup.menu.add(0, 2, 0, "Ziel-Note setzen")
        popup.menu.add(0, 3, 0, "Graph anzeigen")
        popup.menu.add(0, 4, 0, "Graph zurücksetzen")
        popup.menu.add(0, 5, 0, "Exportieren")
        popup.menu.add(0, 6, 0, "Importieren")

        // add complex grading toggle if user is in "BG" bildungsgang
        val bildungsgang = sharedPreferences.getString("selected_bildungsgang", "")
        if (bildungsgang == "BG") {
            val useSimple = sharedPreferences.getBoolean(PREFS_USE_SIMPLE_GRADING, false)
            val toggleText = if (useSimple) "Abitur-Modus aktivieren" else "Einfachen Modus aktivieren"
            popup.menu.add(0, 7, 0, toggleText)
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

        sharedPreferences.edit()
            .putBoolean(PREFS_USE_SIMPLE_GRADING, newValue)
            .apply()

        val message = if (newValue) "Einfacher Modus aktiviert" else "Abitur-Modus aktiviert"
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()

        loadGrades()
        updateFinalGrade()
    }

    private fun showGradeGraphDialog() {
        val historyJson = sharedPreferences.getString(PREFS_GRADE_HISTORY, "[]")
        val historyType = object : TypeToken<List<GradeHistoryEntry>>() {}.type
        val history: List<GradeHistoryEntry> = Gson().fromJson(historyJson, historyType) ?: emptyList()

        if (history.isEmpty()) {
            Toast.makeText(requireContext(), "Keine Notenverlaufsdaten vorhanden", Toast.LENGTH_SHORT).show()
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
            appendLine("Notenverlauf (Textdaten):")
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
                btnShowTextData.text = "Textdaten ausblenden"
            } else {
                scrollViewTextData.visibility = View.GONE
                btnShowTextData.text = "Textdaten anzeigen"
            }
        }

        dialog.setTitle("Notenverlauf")
            .setView(dialogView)
            .setPositiveButton("Schließen", null)
            .show()
    }

    private fun getMonthName(month: Int): String {
        return when (month) {
            1 -> "Januar"
            2 -> "Februar"
            3 -> "März"
            4 -> "April"
            5 -> "Mai"
            6 -> "Juni"
            7 -> "Juli"
            8 -> "August"
            9 -> "September"
            10 -> "Oktober"
            11 -> "November"
            12 -> "Dezember"
            else -> "Monat $month"
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
        } catch (e: Exception) {
            emptyMap()
        }

        val writtenGradesHistoryJson = sharedPreferences.getString(PREFS_WRITTEN_GRADES_HISTORY, "{}")
        val writtenGradesHistoryType = object : TypeToken<Map<String, Map<Int, List<Double>>>>() {}.type
        val writtenGradesHistoryLoaded: Map<String, Map<Int, List<Double>>> = try {
            Gson().fromJson(writtenGradesHistoryJson, writtenGradesHistoryType) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }

        val ratiosJson = sharedPreferences.getString(PREFS_GRADE_RATIOS, "{}")
        val ratiosType = object : TypeToken<Map<String, Pair<Int, Int>>>() {}.type
        val ratios: Map<String, Pair<Int, Int>> = try {
            Gson().fromJson(ratiosJson, ratiosType) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }

        val pruefungsfaecherJson = sharedPreferences.getString(PREFS_PRUEFUNGSFAECHER, "{}")
        val pruefungsfaecherType = object : TypeToken<Map<String, Boolean>>() {}.type
        val pruefungsfaecher: Map<String, Boolean> = try {
            Gson().fromJson(pruefungsfaecherJson, pruefungsfaecherType) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }

        val pruefungsergebnisseJson = sharedPreferences.getString(PREFS_PRUEFUNGSERGEBNISSE, "{}")
        val pruefungsergebnisseType = object : TypeToken<Map<String, Double>>() {}.type
        val pruefungsergebnisse: Map<String, Double> = try {
            Gson().fromJson(pruefungsergebnisseJson, pruefungsergebnisseType) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }

        val selectedHalfYearsJson = sharedPreferences.getString(PREFS_SELECTED_HALF_YEARS, "{}")
        val selectedHalfYearsType = object : TypeToken<Map<String, Int>>() {}.type
        val selectedHalfYears: Map<String, Int> = try {
            Gson().fromJson(selectedHalfYearsJson, selectedHalfYearsType) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }

        val examJson = sharedPreferences.getString("exam_list", "[]")
        val examType = object : TypeToken<List<ExamFragment.ExamEntry>>() {}.type
        val examList: List<ExamFragment.ExamEntry> = try {
            Gson().fromJson(examJson, examType) ?: emptyList()
        } catch (e: Exception) {
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
        tvGradeCount.text = "$subjectsWithGrades / $totalSubjects Fächer benotet"
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
                    "$totalCourseCount/44 Kurse"
                } else {
                    "$totalCourseCount/44 Kurse (ÜBERSCHRITTEN!)"
                }

                tvFinalGrade.text = "Gesamtnote: ${DecimalFormat("0.0").format(average)} ($courseCountText)"

                val goalGrade = sharedPreferences.getFloat(PREFS_GOAL_GRADE, 0f)
                val color = when {
                    totalCourseCount > 44 -> android.R.color.holo_red_dark
                    goalGrade > 0 && average <= goalGrade -> android.R.color.holo_green_dark
                    goalGrade > 0 && average > goalGrade -> android.R.color.holo_red_dark
                    else -> android.R.color.black
                }
                tvFinalGrade.setTextColor(resources.getColor(color))
            } else {
                tvFinalGrade.text = "Gesamtnote: -"
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
                tvFinalGrade.text = "Gesamtnote: ${DecimalFormat("0.0").format(average)}"

                val goalGrade = sharedPreferences.getFloat(PREFS_GOAL_GRADE, 0f)
                val color = when {
                    goalGrade > 0 && average <= goalGrade -> android.R.color.holo_green_dark
                    goalGrade > 0 && average > goalGrade -> android.R.color.holo_red_dark
                    else -> android.R.color.black
                }
                tvFinalGrade.setTextColor(resources.getColor(color))
            } else {
                tvFinalGrade.text = "Gesamtnote: -"
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
        tvTeacher.text = "Lehrer: ${subject.teacher}"
        tvOralGrade.text = "Mündlich: ${subject.getFormattedOralGrade(currentHalfyear)}"
        tvWrittenAverage.text = "Schriftlich: ${subject.getFormattedWrittenAverage(currentHalfyear)}"
        tvRatio.text = "Verhältnis: ${subject.ratio.first}% : ${subject.ratio.second}%"
        tvFinalGrade.text = "Endnote: ${subject.getFormattedFinalGrade(requirements, currentHalfyear)}"

        val examJson = sharedPreferences.getString("exam_list", "[]")
        val examType = object : TypeToken<List<ExamFragment.ExamEntry>>() {}.type
        val allExams: List<ExamFragment.ExamEntry> = Gson().fromJson(examJson, examType) ?: emptyList()

        val examList = allExams.filter { it.subject == subject.subject && it.isOverdue() && it.mark != null }

        val examGradesAdapter = ExamGradesAdapter(examList)
        recyclerExamGrades.layoutManager = LinearLayoutManager(requireContext())
        recyclerExamGrades.adapter = examGradesAdapter

        dialog.setTitle("Details: ${subject.subject}")
            .setView(dialogView)
            .setPositiveButton("Schließen", null)
            .show()
    }

    private fun showEditSubjectDialog(subject: SubjectGradeInfo) {
        val bildungsgang = sharedPreferences.getString("selected_bildungsgang", "")
        val useComplexGrading = bildungsgang == "BG" && sharedPreferences.getBoolean(PREFS_USE_SIMPLE_GRADING, false).not()

        val dialog = AlertDialog.Builder(requireContext())
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_subject, null)

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

            val pruefungsfachCard = dialogView.findViewById<androidx.cardview.widget.CardView>(R.id.cardPruefungsfach)
            pruefungsfachCard?.visibility = View.GONE

            val halfYearsCard = dialogView.findViewById<androidx.cardview.widget.CardView>(R.id.cardHalfYears)
            halfYearsCard?.visibility = View.GONE
        }

        val isLK = subject.subject.endsWith("LK") || subject.subject.endsWith("L")
        if (isLK) {
            switchExamCount.isChecked = false // 2 exams
            switchExamCount.isEnabled = false
            seekBarOral.progress = 50
            seekBarWritten.progress = 50
        } else {
            switchExamCount.isEnabled = true
        }

        switchGradeSystem.isChecked = true

        val requirements = if (useComplexGrading) getSubjectRequirements(subject.subject) else SubjectRequirements()
        val currentHalfyear = getCurrentHalfyear()
        var selectedPruefungsfaecher = if (useComplexGrading) gradeList.count { it.isPruefungsfach } else 0

        if (useComplexGrading) {
            tvSubjectInfo.text = subject.getWeightingInfo(requirements, currentHalfyear)
        }

        val currentOralGrade = subject.oralGradeHistory[currentHalfyear]
        if (currentOralGrade != null) {
            editOralGrade.setText(DecimalFormat("0.0").format(currentOralGrade))
        }

        if (useComplexGrading && subject.pruefungsergebnis != null) {
            editPruefungsergebnis.setText(DecimalFormat("0.0").format(subject.pruefungsergebnis!!))
        }

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
                    tvHalfYearsCount.text = "Alle Halbjahre zählen (${allGrades.size})"
                } else {
                    val minHalfYears = requirements.minRequiredHalfYears
                    val count = maxOf(minHalfYears, seekBarHalfYears.progress)
                    tvHalfYearsCount.text = "$count beste Halbjahre zählen"
                }
            }
        }
        updateHalfYearsDisplay()

        if (!isLK) {
            val isOneExam = subject.ratio.first == 70 && subject.ratio.second == 30
            switchExamCount.isChecked = isOneExam
        }

        var isPointSystem = true // default points (0 - 15)
        var isRangeMode = false

        val updateRatioDisplay = {
            val oral = seekBarOral.progress
            val written = seekBarWritten.progress
            tvRatioDisplay.text = "Verhältnis: $oral% : $written%"
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
                        Toast.makeText(requireContext(), "Maximal 5 Prüfungsfächer erlaubt", Toast.LENGTH_SHORT).show()
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
                        val nextPoint = (input + 1).toInt()
                        if (nextPoint <= 15) {
                            tvGradeRange.text = "≈ ${input.toInt()}-$nextPoint Punkte"
                            tvGradeRange.visibility = View.VISIBLE
                        } else {
                            tvGradeRange.visibility = View.GONE
                        }
                    } else {
                        val lower = maxOf(1.0, input - 0.3)
                        val upper = minOf(6.0, input + 0.3)
                        tvGradeRange.text = "≈ ${DecimalFormat("0.0").format(lower)}-${DecimalFormat("0.0").format(upper)}"
                        tvGradeRange.visibility = View.VISIBLE
                    }
                } catch (e: NumberFormatException) {
                    tvGradeRange.visibility = View.GONE
                }
            } else {
                tvGradeRange.visibility = View.GONE
            }
        }

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
            if (isPointSystem) {
                editOralGrade.hint = "z.B. 11 (Punkte 0-15)"
                if (useComplexGrading) {
                    editPruefungsergebnis.hint = "z.B. 12 (Punkte 0-15)"
                }
            } else {
                editOralGrade.hint = "z.B. 2.5 (Note 1.0-6.0)"
                if (useComplexGrading) {
                    editPruefungsergebnis.hint = "z.B. 2.3 (Note 1.0-6.0)"
                }
            }
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

        seekBarOral.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !isLK) {
                    seekBarWritten.progress = 100 - progress
                    updateRatioDisplay()
                    switchExamCount.isChecked = (progress == 70)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekBarWritten.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !isLK) {
                    seekBarOral.progress = 100 - progress
                    updateRatioDisplay()
                    switchExamCount.isChecked = (seekBarOral.progress == 70)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        if (useComplexGrading) {
            seekBarHalfYears.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
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

        dialog.setTitle("${subject.subject} bearbeiten")
            .setView(dialogView)
            .setPositiveButton("Speichern") { _, _ ->
                val oralGradeText = editOralGrade.text.toString().trim()
                val pruefungsergebnisText = if (useComplexGrading) editPruefungsergebnis.text.toString().trim() else ""
                var newOralGrade: Double? = null
                var newPruefungsergebnis: Double? = null

                if (oralGradeText.isNotEmpty()) {
                    try {
                        val inputValue = oralGradeText.replace(",", ".").toDouble()
                        newOralGrade = if (isPointSystem) {
                            if (inputValue >= 0 && inputValue <= 15) {
                                val baseGrade = (17.0 - inputValue) / 3.0
                                if (isRangeMode && inputValue < 15) {
                                    val upperPoints = inputValue + 1
                                    val upperGrade = (17.0 - upperPoints) / 3.0
                                    (baseGrade + upperGrade) / 2.0
                                } else {
                                    baseGrade
                                }
                            } else null
                        } else {
                            if (inputValue >= 1.0 && inputValue <= 6.0) {
                                if (isRangeMode) {
                                    maxOf(1.0, inputValue - 0.15)
                                } else {
                                    inputValue
                                }
                            } else null
                        }
                    } catch (e: NumberFormatException) {
                        newOralGrade = null
                    }
                }

                if (useComplexGrading && pruefungsergebnisText.isNotEmpty()) {
                    try {
                        val inputValue = pruefungsergebnisText.replace(",", ".").toDouble()
                        newPruefungsergebnis = if (isPointSystem) {
                            if (inputValue >= 0 && inputValue <= 15) {
                                (17.0 - inputValue) / 3.0
                            } else null
                        } else {
                            if (inputValue >= 1.0 && inputValue <= 6.0) inputValue else null
                        }
                    } catch (e: NumberFormatException) {
                        newPruefungsergebnis = null
                    }
                }
                val newRatio = Pair(seekBarOral.progress, seekBarWritten.progress)
                val isPruefungsfach = if (useComplexGrading) switchPruefungsfach.isChecked else false
                val selectedHalfYears = if (useComplexGrading) {
                    if (requirements.mustCountAllHalfYears) {
                        subject.getHalfYearGrades(currentHalfyear).size
                    } else {
                        maxOf(requirements.minRequiredHalfYears, seekBarHalfYears.progress)
                    }
                } else {
                    1
                }

                saveSubjectGradeData(subject.subject, newOralGrade, newRatio, isPruefungsfach, newPruefungsergebnis, selectedHalfYears)
                loadGrades()
                updateFinalGrade()

                Toast.makeText(requireContext(), "Gespeichert", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun saveSubjectGradeData(subject: String, oralGrade: Double?, ratio: Pair<Int, Int>,
                                     isPruefungsfach: Boolean, pruefungsergebnis: Double?, selectedHalfYears: Int) {
        val currentHalfyear = getCurrentHalfyear()

        val oralGradesHistoryJson = sharedPreferences.getString(PREFS_ORAL_GRADES_HISTORY, "{}")
        val oralGradesHistoryType = object : TypeToken<MutableMap<String, MutableMap<Int, Double?>>>() {}.type
        val oralGradesHistory: MutableMap<String, MutableMap<Int, Double?>> = try {
            Gson().fromJson(oralGradesHistoryJson, oralGradesHistoryType) ?: mutableMapOf()
        } catch (e: Exception) {
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
        } catch (e: Exception) {
            mutableMapOf()
        }

        ratios[subject] = ratio

        val pruefungsfaecherJson = sharedPreferences.getString(PREFS_PRUEFUNGSFAECHER, "{}")
        val pruefungsfaecherType = object : TypeToken<MutableMap<String, Boolean>>() {}.type
        val pruefungsfaecher: MutableMap<String, Boolean> = try {
            Gson().fromJson(pruefungsfaecherJson, pruefungsfaecherType) ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }

        pruefungsfaecher[subject] = isPruefungsfach

        val pruefungsergebnisseJson = sharedPreferences.getString(PREFS_PRUEFUNGSERGEBNISSE, "{}")
        val pruefungsergebnisseType = object : TypeToken<MutableMap<String, Double>>() {}.type
        val pruefungsergebnisse: MutableMap<String, Double> = try {
            Gson().fromJson(pruefungsergebnisseJson, pruefungsergebnisseType) ?: mutableMapOf()
        } catch (e: Exception) {
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
        } catch (e: Exception) {
            mutableMapOf()
        }

        selectedHalfYearsMap[subject] = selectedHalfYears

        sharedPreferences.edit()
            .putString(PREFS_ORAL_GRADES_HISTORY, Gson().toJson(oralGradesHistory))
            .putString(PREFS_GRADE_RATIOS, Gson().toJson(ratios))
            .putString(PREFS_PRUEFUNGSFAECHER, Gson().toJson(pruefungsfaecher))
            .putString(PREFS_PRUEFUNGSERGEBNISSE, Gson().toJson(pruefungsergebnisse))
            .putString(PREFS_SELECTED_HALF_YEARS, Gson().toJson(selectedHalfYearsMap))
            .apply()
    }

    private fun showStartNewHalfyearDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Neues Halbjahr beginnen")
            .setMessage("Dies wird die Notenberechnung zurücksetzen, aber deine bisherigen Noten für die Grafik behalten. Möchtest du fortfahren?")
            .setPositiveButton("Ja, neues Halbjahr") { _, _ ->
                startNewHalfyear()
            }
            .setNegativeButton("Abbrechen", null)
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
        } catch (e: Exception) {
            mutableMapOf()
        }

        val writtenGradesHistoryJson = sharedPreferences.getString(PREFS_WRITTEN_GRADES_HISTORY, "{}")
        val writtenGradesHistoryType = object : TypeToken<MutableMap<String, MutableMap<Int, List<Double>>>>() {}.type
        val writtenGradesHistory: MutableMap<String, MutableMap<Int, List<Double>>> = try {
            Gson().fromJson(writtenGradesHistoryJson, writtenGradesHistoryType) ?: mutableMapOf()
        } catch (e: Exception) {
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

        sharedPreferences.edit()
            .putString(PREFS_ORAL_GRADES_HISTORY, Gson().toJson(oralGradesHistory))
            .putString(PREFS_WRITTEN_GRADES_HISTORY, Gson().toJson(writtenGradesHistory))
            .apply()

        sharedPreferences.edit()
            .remove(PREFS_ORAL_GRADES)
            .apply()

        val newHalfyear = currentHalfyear + 1
        sharedPreferences.edit()
            .putInt(PREFS_CURRENT_HALFYEAR, newHalfyear)
            .apply()

        sharedPreferences.edit()
            .remove("exam_list")
            .apply()

        loadGrades()
        updateFinalGrade()

        Toast.makeText(requireContext(), "Neues Halbjahr ($newHalfyear) begonnen", Toast.LENGTH_SHORT).show()
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

        sharedPreferences.edit()
            .putString(PREFS_GRADE_HISTORY, Gson().toJson(history))
            .apply()
    }

    private fun showSetGoalGradeDialog() {
        val currentGoal = sharedPreferences.getFloat(PREFS_GOAL_GRADE, 2.0f)

        val editText = EditText(requireContext()).apply {
            setText(DecimalFormat("0.0").format(currentGoal))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "1.0 - 6.0"
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Ziel-Note setzen")
            .setMessage("Deine Ziel-Note für dieses Halbjahr:")
            .setView(editText)
            .setPositiveButton("Speichern") { _, _ ->
                try {
                    val goalGrade = editText.text.toString().replace(",", ".").toFloat()
                    if (goalGrade >= 1.0f && goalGrade <= 6.0f) {
                        sharedPreferences.edit()
                            .putFloat(PREFS_GOAL_GRADE, goalGrade)
                            .apply()
                        updateFinalGrade()
                        Toast.makeText(requireContext(), "Ziel-Note gespeichert", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "Bitte gib eine Note zwischen 1.0 und 6.0 ein", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: NumberFormatException) {
                    Toast.makeText(requireContext(), "Ungültige Eingabe", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showResetGraphDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Graph zurücksetzen")
            .setMessage("WARNUNG: Dies wird alle deine gespeicherten Notenverlaufsdaten permanent löschen. Diese Aktion kann nicht rückgängig gemacht werden!")
            .setPositiveButton("Ja, Graph löschen") { _, _ ->
                sharedPreferences.edit()
                    .remove(PREFS_GRADE_HISTORY)
                    .apply()
                Toast.makeText(requireContext(), "Graph wurde zurückgesetzt", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showExportOptions() {
        val content = exportGradeData()
        val options = arrayOf("Als Datei speichern", "In Zwischenablage kopieren")

        AlertDialog.Builder(requireContext())
            .setTitle("Noten exportieren")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> saveExportToFile(content)
                    1 -> copyToClipboard(content)
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showImportOptions() {
        val options = arrayOf("Aus Datei importieren", "Aus Zwischenablage importieren")

        AlertDialog.Builder(requireContext())
            .setTitle("Noten importieren")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> importFromFilePicker()
                    1 -> importFromClipboard()
                }
            }
            .setNegativeButton("Abbrechen", null)
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
            Toast.makeText(requireContext(), "Zwischenablage ist leer", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importGradeData(content: String) {
        try {
            backupManager.importGradeData(content)

            loadGrades()
            updateFinalGrade()

            Toast.makeText(requireContext(), "Noten erfolgreich importiert", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Fehler beim Importieren: ${e.message}", Toast.LENGTH_LONG).show()
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

        sharedPreferences.edit().putString("temp_export_content", content).apply()

        try {
            exportLauncher.launch(intent)
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
            Toast.makeText(requireContext(), "Fehler beim Speichern der Datei", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyToClipboard(content: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Noten Export", content)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "Export in Zwischenablage kopiert", Toast.LENGTH_SHORT).show()
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

        sharedPreferences.edit()
            .putString(PREFS_GRADES_SUBJECTS, currentSubjects)
            .putString(PREFS_GRADES_TEACHERS, currentTeachers)
            .putString(PREFS_GRADES_ROOMS, currentRooms)
            .apply()
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
            .setTitle("Fächer aktualisieren")
            .setMessage("Alle bisherigen Notendaten werden gelöscht und die neuen Fächer werden geladen. Möchtest du fortfahren?")
            .setPositiveButton("Ja, aktualisieren") { _, _ ->
                clearAllGradeData()
                saveCurrentSubjectsAsGradesSubjects()
                hasSubjectChanges = false
                hideSubjectChangeWarning()
                loadGrades()
                updateFinalGrade()
                Toast.makeText(requireContext(), "Fächer wurden aktualisiert", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showDeclineSubjectChangesDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Änderungen verwerfen")
            .setMessage("Warnung: Dies wird deine aktuellen Stundenplan-Einstellungen auf den Stand der Notenseite zurücksetzen. Möchtest du fortfahren?")
            .setPositiveButton("Ja, zurücksetzen") { _, _ ->
                restoreSubjectsFromGradesData()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun restoreSubjectsFromGradesData() {
        val gradesSubjects = sharedPreferences.getString(PREFS_GRADES_SUBJECTS, "") ?: ""
        val gradesTeachers = sharedPreferences.getString(PREFS_GRADES_TEACHERS, "") ?: ""
        val gradesRooms = sharedPreferences.getString(PREFS_GRADES_ROOMS, "") ?: ""

        sharedPreferences.edit()
            .putString("student_subjects", gradesSubjects)
            .putString("student_teachers", gradesTeachers)
            .putString("student_rooms", gradesRooms)
            .apply()

        hasSubjectChanges = false
        hideSubjectChangeWarning()
        Toast.makeText(requireContext(), "Stundenplan wurde zurückgesetzt", Toast.LENGTH_SHORT).show()
    }

    private fun clearAllGradeData() {
        sharedPreferences.edit()
            .remove(PREFS_ORAL_GRADES_HISTORY)
            .remove(PREFS_WRITTEN_GRADES_HISTORY)
            .remove(PREFS_GRADE_RATIOS)
            .remove(PREFS_PRUEFUNGSFAECHER)
            .remove(PREFS_PRUEFUNGSERGEBNISSE)
            .remove(PREFS_SELECTED_HALF_YEARS)
            .remove(PREFS_GOAL_GRADE)
            .remove(PREFS_GRADE_HISTORY)
            .remove(PREFS_ORAL_GRADES)
            .apply()
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
}