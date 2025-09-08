package com.thecooker.vertretungsplaner

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.edit
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class SubjectSelectionActivity : BaseActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var searchEditText: EditText
    private lateinit var btnAddManual: Button
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnSelectAll: Button
    private lateinit var btnDeselectAll: Button
    private lateinit var btnConfirm: Button
    private lateinit var btnCancel: Button
    private lateinit var tvSelectionCount: TextView
    private lateinit var tvTotalCount: TextView
    private lateinit var tvSearchResults: TextView

    private lateinit var adapter: SubjectAdapter

    // variables to hold subject triplets
    private var allSubjectTriplets = mutableListOf<SubjectTriplet>()
    private var filteredSubjectTriplets = mutableListOf<SubjectTriplet>()
    private var selectedSubjects = mutableSetOf<String>()

    data class SubjectTriplet(
        val subject: String,
        val teacher: String,
        val room: String,
        val alternativeRooms: List<String> = emptyList()
    ) {
        fun getDisplayText(): String {
            val parts = mutableListOf<String>()
            if (subject.isNotBlank()) parts.add(subject)
            if (teacher.isNotBlank() && teacher != "UNKNOWN") parts.add(teacher)
            if (room.isNotBlank() && room != "UNKNOWN") {
                val roomDisplay = if (alternativeRooms.isNotEmpty()) {
                    "$room (+${alternativeRooms.size} alt.)"
                } else {
                    room
                }
                parts.add(roomDisplay)
            }
            return parts.joinToString(" | ")
        }

        fun matchesSearch(searchText: String): Boolean {
            val lowerSearchText = searchText.lowercase()
            return subject.lowercase().contains(lowerSearchText) ||
                    (teacher != "UNKNOWN" && teacher.lowercase().contains(lowerSearchText)) ||
                    (room != "UNKNOWN" && room.lowercase().contains(lowerSearchText)) ||
                    alternativeRooms.any { altRoom -> altRoom.lowercase().contains(lowerSearchText) }
        }
    }

    companion object {
        const val EXTRA_SUBJECTS = "subjects"
        const val EXTRA_TEACHERS = "teachers"
        const val EXTRA_ROOMS = "rooms"
        const val EXTRA_SCHULJAHR = "schuljahr"
        const val EXTRA_KLASSE = "klasse"
        const val RESULT_SELECTED_SUBJECTS = "selected_subjects"
        private const val TAG = "SubjectSelection"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subject_selection)

        sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE)

        initializeViews()
        setupToolbar()
        setupBackPressedCallback()
        loadData()
        setupRecyclerView()
        setupListeners()
        setupSearch()
        updateCounters()
    }

    private fun setupBackPressedCallback() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }

    private fun handleBackPress() {
        if (selectedSubjects.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Auswahl verwerfen?")
                .setMessage("Du hast ${selectedSubjects.size} Fächer ausgewählt. Möchtest du die Auswahl verwerfen?")
                .setPositiveButton("Ja, verwerfen") { _, _ ->
                    finish()
                }
                .setNegativeButton("Zurück zur Auswahl", null)
                .show()
        } else {
            finish()
        }
    }

    private fun initializeViews() {
        toolbar = findViewById(R.id.toolbar)
        searchEditText = findViewById(R.id.searchEditText)
        btnAddManual = findViewById(R.id.btnAddManual)
        recyclerView = findViewById(R.id.recyclerViewSubjects)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnDeselectAll = findViewById(R.id.btnDeselectAll)
        btnConfirm = findViewById(R.id.btnConfirm)
        btnCancel = findViewById(R.id.btnCancel)
        tvSelectionCount = findViewById(R.id.tvSelectionCount)
        tvTotalCount = findViewById(R.id.tvTotalCount)
        tvSearchResults = findViewById(R.id.tvSearchResults)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = "Fächer auswählen"
        }
    }

    private fun loadData() {
        val schuljahr = intent.getStringExtra(EXTRA_SCHULJAHR) ?: ""
        val klasse = intent.getStringExtra(EXTRA_KLASSE) ?: ""

        loadSubjectTriplets()

        L.d(TAG, "Loaded ${allSubjectTriplets.size} subject triplets")
        allSubjectTriplets.forEach { triplet ->
            L.d(TAG, "Triplet: '${triplet.subject}' -> '${triplet.teacher}' -> '${triplet.room}'")
        }

        if (schuljahr.isNotEmpty() && klasse.isNotEmpty()) {
            supportActionBar?.subtitle = "$klasse - $schuljahr"
        }
    }

    private fun loadSubjectTriplets() {
        allSubjectTriplets.clear()
        selectedSubjects.clear()

        val intentSubjects = intent.getStringArrayExtra(EXTRA_SUBJECTS)
        val intentTeachers = intent.getStringArrayExtra(EXTRA_TEACHERS)
        val intentRooms = intent.getStringArrayExtra(EXTRA_ROOMS)

        L.d(TAG, "Intent data - Subjects: ${intentSubjects?.size}, Teachers: ${intentTeachers?.size}, Rooms: ${intentRooms?.size}")

        if (intentSubjects != null && intentSubjects.isNotEmpty()) {
            L.d(TAG, "Using Intent data for triplets")

            val alternativeRoomsMap = loadAlternativeRoomsFromPrefs()

            for (i in intentSubjects.indices) {
                val subject = intentSubjects[i]
                val teacher = intentTeachers?.getOrNull(i) ?: "UNKNOWN"
                val room = intentRooms?.getOrNull(i) ?: "UNKNOWN"
                val altRooms = alternativeRoomsMap[subject] ?: emptyList()

                allSubjectTriplets.add(SubjectTriplet(subject, teacher, room, altRooms))
                L.d(TAG, "Added from Intent: '$subject' -> '$teacher' -> '$room' + ${altRooms.size} alt rooms")
            }
        } else {
            L.d(TAG, "No Intent data, loading from SharedPreferences")

            val allExtractedSubjects = sharedPreferences.getString("all_extracted_subjects", "")
                ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
            val allExtractedTeachers = sharedPreferences.getString("all_extracted_teachers", "")
                ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
            val allExtractedRooms = sharedPreferences.getString("all_extracted_rooms", "")
                ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

            val alternativeRoomsMap = loadAlternativeRoomsFromPrefs()

            L.d(TAG, "SharedPreferences data - Subjects: ${allExtractedSubjects.size}, Teachers: ${allExtractedTeachers.size}, Rooms: ${allExtractedRooms.size}")

            if (allExtractedSubjects.isNotEmpty()) {
                for (i in allExtractedSubjects.indices) {
                    val subject = allExtractedSubjects[i]
                    val teacher = allExtractedTeachers.getOrElse(i) { "UNKNOWN" }
                    val room = allExtractedRooms.getOrElse(i) { "UNKNOWN" }
                    val altRooms = alternativeRoomsMap[subject] ?: emptyList()

                    allSubjectTriplets.add(SubjectTriplet(subject, teacher, room, altRooms))
                    L.d(TAG, "Added from SharedPrefs: '$subject' -> '$teacher' -> '$room' + ${altRooms.size} alt rooms")
                }
            }
        }

        val savedStudentSubjects = sharedPreferences.getString("student_subjects", "")
            ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

        selectedSubjects.addAll(savedStudentSubjects)
        L.d(TAG, "Pre-selected subjects: $savedStudentSubjects")

        allSubjectTriplets.sortBy { it.subject }

        filteredSubjectTriplets.clear()
        filteredSubjectTriplets.addAll(allSubjectTriplets)

        L.d(TAG, "Final triplets loaded: ${allSubjectTriplets.size}")
    }

    private fun loadAlternativeRoomsFromPrefs(): Map<String, List<String>> {
        val json = sharedPreferences.getString("alternative_rooms", "{}")
        return try {
            val type = object : com.google.gson.reflect.TypeToken<MutableMap<String, List<String>>>() {}.type
            com.google.gson.Gson().fromJson(json, type) ?: mutableMapOf()
        } catch (e: Exception) {
            L.e(TAG, "Error loading alternative rooms", e)
            emptyMap()
        }
    }

    private fun saveAlternativeRoomsToPrefs(alternativeRoomsMap: Map<String, List<String>>) {
        val json = com.google.gson.Gson().toJson(alternativeRoomsMap)
        sharedPreferences.edit {
            putString("alternative_rooms", json)
        }
        L.d(TAG, "Saved alternative rooms: $json")
    }

    private fun setupRecyclerView() {
        adapter = SubjectAdapter(mutableListOf(),
            onSelectionChanged = { subject, isSelected ->
                if (isSelected) {
                    selectedSubjects.add(subject)
                    L.d(TAG, "Subject '$subject' selected")
                } else {
                    selectedSubjects.remove(subject)
                    L.d(TAG, "Subject '$subject' deselected")
                }
                updateCounters()
            },
            onDeleteClicked = { triplet ->
                deleteSubject(triplet)
            },
            onEditClicked = { triplet ->
                editSubject(triplet)
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        adapter.updateSubjectTriplets(filteredSubjectTriplets, selectedSubjects)
    }

    private fun deleteSubject(triplet: SubjectTriplet) {
        AlertDialog.Builder(this)
            .setTitle("Fach löschen")
            .setMessage("Willst du das Fach '${triplet.getDisplayText()}' wirklich löschen?")
            .setPositiveButton("Ja, löschen") { _, _ ->
                val index = allSubjectTriplets.indexOf(triplet)
                if (index != -1) {
                    allSubjectTriplets.removeAt(index)
                    L.d(TAG, "Deleted subject at index $index: '${triplet.subject}'")

                    syncDataToSources()
                }

                selectedSubjects.remove(triplet.subject)

                val currentSearch = searchEditText.text.toString().trim()
                filterSubjects(currentSearch)

                Toast.makeText(this, "Fach '${triplet.subject}' wurde gelöscht", Toast.LENGTH_SHORT).show()
                L.d(TAG, "Total subjects now: ${allSubjectTriplets.size}")
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun editSubject(triplet: SubjectTriplet) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_subject_manual, null)

        val editTextSubject = dialogView.findViewById<EditText>(R.id.editTextSubject)
        val editTextTeacher = dialogView.findViewById<EditText>(R.id.editTextTeacher)
        val editTextRoom = dialogView.findViewById<EditText>(R.id.editTextRoom)
        val switchAlternativeRooms = dialogView.findViewById<Switch>(R.id.switchAlternativeRooms)
        val alternativeRoomsContainer = dialogView.findViewById<LinearLayout>(R.id.alternativeRoomsContainer)
        val btnAddAlternativeRoom = dialogView.findViewById<Button>(R.id.btnAddAlternativeRoom)

        editTextSubject.setText(triplet.subject)
        editTextTeacher.setText(if (triplet.teacher == "UNKNOWN") "" else triplet.teacher)
        editTextRoom.setText(if (triplet.room == "UNKNOWN") "" else triplet.room)

        val alternativeRoomEditTexts = mutableListOf<EditText>()

        fun addAlternativeRoomField(text: String = "") {
            if (alternativeRoomEditTexts.size >= 2) {
                Toast.makeText(this, "Maximal 2 alternative Räume erlaubt", Toast.LENGTH_SHORT).show()
                return
            }

            val roomLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }

            val editText = EditText(this).apply {
                hint = "Alternativer Raum"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setText(text)
            }
            alternativeRoomEditTexts.add(editText)

            val removeButton = Button(this).apply {
                setText("×")
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    alternativeRoomsContainer.removeView(roomLayout)
                    alternativeRoomEditTexts.remove(editText)
                    btnAddAlternativeRoom.visibility = if (alternativeRoomEditTexts.size < 2) View.VISIBLE else View.GONE
                }
            }

            roomLayout.addView(editText)
            roomLayout.addView(removeButton)
            alternativeRoomsContainer.addView(roomLayout, alternativeRoomsContainer.childCount - 1)

            btnAddAlternativeRoom.visibility = if (alternativeRoomEditTexts.size < 2) View.VISIBLE else View.GONE
        }

        fun updateAlternativeRoomsVisibility() {
            val mainRoom = editTextRoom.text.toString().trim()
            val hasValidMainRoom = mainRoom.isNotBlank() && mainRoom != "UNKNOWN"
            val switchEnabled = switchAlternativeRooms.isChecked && hasValidMainRoom

            alternativeRoomsContainer.visibility = if (switchEnabled) View.VISIBLE else View.GONE
            switchAlternativeRooms.isEnabled = hasValidMainRoom

            if (!hasValidMainRoom) {
                switchAlternativeRooms.isChecked = false
                alternativeRoomsContainer.removeAllViews()
                alternativeRoomsContainer.addView(btnAddAlternativeRoom)
                alternativeRoomEditTexts.clear()
            }
        }

        editTextRoom.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateAlternativeRoomsVisibility()
            }
        })

        switchAlternativeRooms.setOnCheckedChangeListener { _, isChecked ->
            alternativeRoomsContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        btnAddAlternativeRoom.setOnClickListener {
            addAlternativeRoomField()
        }

        if (triplet.alternativeRooms.isNotEmpty()) {
            switchAlternativeRooms.isChecked = true
            triplet.alternativeRooms.forEach { room ->
                addAlternativeRoomField(room)
            }
        }

        updateAlternativeRoomsVisibility()

        AlertDialog.Builder(this)
            .setTitle("Fach bearbeiten")
            .setView(dialogView)
            .setPositiveButton("Speichern") { _, _ ->
                val subjectText = editTextSubject.text.toString().trim()
                val teacherText = editTextTeacher.text.toString().trim().ifEmpty { "UNKNOWN" }
                val roomText = editTextRoom.text.toString().trim().ifEmpty { "UNKNOWN" }

                val alternativeRooms = if (switchAlternativeRooms.isChecked) {
                    alternativeRoomEditTexts.map { it.text.toString().trim() }
                        .filter { it.isNotBlank() && it != "UNKNOWN" }
                } else {
                    emptyList()
                }

                if (subjectText.isNotEmpty()) {
                    updateSubject(triplet, subjectText, teacherText, roomText, alternativeRooms)
                } else {
                    Toast.makeText(this, "Gebe bitte mindestens ein Fach ein", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun updateSubject(originalTriplet: SubjectTriplet, newSubject: String, newTeacher: String, newRoom: String, alternativeRooms: List<String>) {
        if (newSubject.length < 2) {
            Toast.makeText(this, "Fach muss mindestens 2 Zeichen lang sein", Toast.LENGTH_LONG).show()
            return
        }

        val index = allSubjectTriplets.indexOf(originalTriplet)
        if (index != -1) {
            val conflictingTriplet = allSubjectTriplets.find {
                it != originalTriplet && it.subject.equals(newSubject, ignoreCase = true)
            }

            if (conflictingTriplet != null) {
                Toast.makeText(this, "Ein Fach mit dem Namen '$newSubject' existiert bereits", Toast.LENGTH_SHORT).show()
                return
            }

            val wasSelected = selectedSubjects.contains(originalTriplet.subject)
            if (wasSelected && originalTriplet.subject != newSubject) {
                selectedSubjects.remove(originalTriplet.subject)
                selectedSubjects.add(newSubject)
            }

            val updatedTriplet = SubjectTriplet(newSubject, newTeacher, newRoom, alternativeRooms)
            allSubjectTriplets[index] = updatedTriplet

            allSubjectTriplets.sortBy { it.subject }

            val alternativeRoomsMap = loadAlternativeRoomsFromPrefs().toMutableMap()
            if (alternativeRooms.isNotEmpty()) {
                alternativeRoomsMap[newSubject] = alternativeRooms
            } else {
                alternativeRoomsMap.remove(newSubject)
            }

            if (originalTriplet.subject != newSubject) {
                alternativeRoomsMap.remove(originalTriplet.subject)
            }

            saveAlternativeRoomsToPrefs(alternativeRoomsMap)

            syncDataToSources()

            val currentSearch = searchEditText.text.toString().trim()
            filterSubjects(currentSearch)

            Toast.makeText(this, "Fach wurde aktualisiert", Toast.LENGTH_SHORT).show()
            L.d(TAG, "Successfully updated subject: '$newSubject' -> '$newTeacher' -> '$newRoom' + ${alternativeRooms.size} alt rooms")
        } else {
            L.e(TAG, "Failed to find original triplet for update!")
        }
    }

    private fun setupSearch() {
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val searchText = s.toString().trim()
                filterSubjects(searchText)
            }
        })
    }

    private fun filterSubjects(searchText: String) {
        L.d(TAG, "Filtering subjects with search text: '$searchText'")

        filteredSubjectTriplets.clear()

        if (searchText.isEmpty()) {
            // show all
            filteredSubjectTriplets.addAll(allSubjectTriplets)
            L.d(TAG, "Search empty, showing all ${allSubjectTriplets.size} subjects")
        } else {
            allSubjectTriplets.forEach { triplet ->
                if (triplet.matchesSearch(searchText)) {
                    filteredSubjectTriplets.add(triplet)
                    L.d(TAG, "Match found: '${triplet.getDisplayText()}'")
                }
            }
            L.d(TAG, "Found ${filteredSubjectTriplets.size} matching subjects")
        }

        // update adapter with filtered results + current selections => very important!!
        adapter.updateSubjectTriplets(filteredSubjectTriplets, selectedSubjects)
        updateSearchResults(searchText)
        updateCounters()
    }

    private fun updateSearchResults(searchText: String) = if (searchText.isEmpty()) {
        tvSearchResults.visibility = View.GONE
    } else {
        tvSearchResults.visibility = View.VISIBLE
        "${filteredSubjectTriplets.size} Ergebnisse für '$searchText'".also { tvSearchResults.text = it }
    }

    private fun setupListeners() {
        btnSelectAll.setOnClickListener {
            val visibleSubjects = filteredSubjectTriplets.map { it.subject }
            selectedSubjects.addAll(visibleSubjects)
            adapter.updateSubjectTriplets(filteredSubjectTriplets, selectedSubjects)
            updateCounters()
            L.d(TAG, "Selected all visible subjects (${visibleSubjects.size})")
        }

        btnDeselectAll.setOnClickListener {
            val visibleSubjects = filteredSubjectTriplets.map { it.subject }.toSet()
            selectedSubjects.removeAll(visibleSubjects)
            adapter.updateSubjectTriplets(filteredSubjectTriplets, selectedSubjects)
            updateCounters()
            L.d(TAG, "Deselected all visible subjects")
        }

        btnAddManual.setOnClickListener {
            showManualAddDialog()
        }

        btnConfirm.setOnClickListener {
            if (selectedSubjects.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("Keine Fächer ausgewählt")
                    .setMessage("Du hast keine Fächer ausgewählt. Trotzdem fortfahren?")
                    .setPositiveButton("Ja, fortfahren") { _, _ ->
                        confirmSelection()
                    }
                    .setNegativeButton("Zurück", null)
                    .show()
            } else {
                confirmSelection()
            }
        }

        btnCancel.setOnClickListener {
            L.d(TAG, "Selection cancelled by user")
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun showManualAddDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_subject_manual, null)

        val editTextSubject = dialogView.findViewById<EditText>(R.id.editTextSubject)
        val editTextTeacher = dialogView.findViewById<EditText>(R.id.editTextTeacher)
        val editTextRoom = dialogView.findViewById<EditText>(R.id.editTextRoom)
        val switchAlternativeRooms = dialogView.findViewById<Switch>(R.id.switchAlternativeRooms)
        val alternativeRoomsContainer = dialogView.findViewById<LinearLayout>(R.id.alternativeRoomsContainer)
        val btnAddAlternativeRoom = dialogView.findViewById<Button>(R.id.btnAddAlternativeRoom)

        val alternativeRoomEditTexts = mutableListOf<EditText>()

        fun addAlternativeRoomField(text: String = "") {
            if (alternativeRoomEditTexts.size >= 2) {
                Toast.makeText(this, "Maximal 2 alternative Räume erlaubt", Toast.LENGTH_SHORT).show()
                return
            }

            val roomLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }

            val editText = EditText(this).apply {
                hint = "Alternativer Raum"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setText(text)
            }
            alternativeRoomEditTexts.add(editText)

            val removeButton = Button(this).apply {
                setText("×")
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    alternativeRoomsContainer.removeView(roomLayout)
                    alternativeRoomEditTexts.remove(editText)
                    btnAddAlternativeRoom.visibility = if (alternativeRoomEditTexts.size < 2) View.VISIBLE else View.GONE
                }
            }

            roomLayout.addView(editText)
            roomLayout.addView(removeButton)
            alternativeRoomsContainer.addView(roomLayout, alternativeRoomsContainer.childCount - 1)

            btnAddAlternativeRoom.visibility = if (alternativeRoomEditTexts.size < 2) View.VISIBLE else View.GONE
        }

        fun updateAlternativeRoomsVisibility() {
            val mainRoom = editTextRoom.text.toString().trim()
            val hasValidMainRoom = mainRoom.isNotBlank() && mainRoom != "UNKNOWN"
            val switchEnabled = switchAlternativeRooms.isChecked && hasValidMainRoom

            alternativeRoomsContainer.visibility = if (switchEnabled) View.VISIBLE else View.GONE
            switchAlternativeRooms.isEnabled = hasValidMainRoom

            if (!hasValidMainRoom) {
                switchAlternativeRooms.isChecked = false
                alternativeRoomsContainer.removeAllViews()
                alternativeRoomsContainer.addView(btnAddAlternativeRoom)
                alternativeRoomEditTexts.clear()
            }
        }

        editTextRoom.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateAlternativeRoomsVisibility()
            }
        })

        switchAlternativeRooms.setOnCheckedChangeListener { _, isChecked ->
            alternativeRoomsContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        btnAddAlternativeRoom.setOnClickListener {
            addAlternativeRoomField()
        }

        AlertDialog.Builder(this)
            .setTitle("Fach manuell hinzufügen")
            .setView(dialogView)
            .setPositiveButton("Hinzufügen") { _, _ ->
                val subjectText = editTextSubject.text.toString().trim()
                val teacherText = editTextTeacher.text.toString().trim().ifEmpty { "UNKNOWN" }
                val roomText = editTextRoom.text.toString().trim().ifEmpty { "UNKNOWN" }

                val alternativeRooms = if (switchAlternativeRooms.isChecked) {
                    alternativeRoomEditTexts.map { it.text.toString().trim() }
                        .filter { it.isNotBlank() && it != "UNKNOWN" }
                } else {
                    emptyList()
                }

                if (subjectText.isNotEmpty()) {
                    addManualSubject(subjectText, teacherText, roomText, alternativeRooms)
                } else {
                    Toast.makeText(this, "Bitte gebe mindestens ein Fach ein", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun addManualSubject(subjectText: String, teacherText: String, roomText: String, alternativeRooms: List<String>) {
        if (subjectText.length < 2) {
            Toast.makeText(this, "Fach muss mindestens 2 Zeichen lang sein", Toast.LENGTH_LONG).show()
            return
        }

        val existingIndex = allSubjectTriplets.indexOfFirst { it.subject.equals(subjectText, ignoreCase = true) }

        if (existingIndex != -1) {
            val existingTriplet = allSubjectTriplets[existingIndex]
            if (existingTriplet.teacher != teacherText || existingTriplet.room != roomText || existingTriplet.alternativeRooms != alternativeRooms) {
                allSubjectTriplets[existingIndex] = SubjectTriplet(subjectText, teacherText, roomText, alternativeRooms)
                Toast.makeText(this, "Fach '$subjectText' wurde aktualisiert", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Fach '$subjectText' mit identischen Daten existiert bereits", Toast.LENGTH_SHORT).show()
            }

            if (!selectedSubjects.contains(subjectText)) {
                selectedSubjects.add(subjectText)
            }
        } else {
            val newTriplet = SubjectTriplet(subjectText, teacherText, roomText, alternativeRooms)
            allSubjectTriplets.add(newTriplet)
            allSubjectTriplets.sortBy { it.subject }

            selectedSubjects.add(subjectText)

            Toast.makeText(this, "Fach '$subjectText' hinzugefügt und ausgewählt", Toast.LENGTH_SHORT).show()
        }

        val alternativeRoomsMap = loadAlternativeRoomsFromPrefs().toMutableMap()
        if (alternativeRooms.isNotEmpty()) {
            alternativeRoomsMap[subjectText] = alternativeRooms
        } else {
            alternativeRoomsMap.remove(subjectText)
        }
        saveAlternativeRoomsToPrefs(alternativeRoomsMap)

        syncDataToSources()

        val currentSearch = searchEditText.text.toString().trim()
        filterSubjects(currentSearch)
    }

    private fun syncDataToSources() {
        val subjects = allSubjectTriplets.map { it.subject }.toTypedArray()
        val teachers = allSubjectTriplets.map { it.teacher }.toTypedArray()
        val rooms = allSubjectTriplets.map { it.room }.toTypedArray()

        L.d(TAG, "=== SYNCING DATA TO SOURCES ===")
        L.d(TAG, "Total triplets to sync: ${allSubjectTriplets.size}")

        allSubjectTriplets.forEachIndexed { index, triplet ->
            L.d(TAG, "Sync[$index]: '${triplet.subject}' -> '${triplet.teacher}' -> '${triplet.room}'")
        }

        val editor = sharedPreferences.edit()
        editor.putString("all_extracted_subjects", subjects.joinToString(","))
        editor.putString("all_extracted_teachers", teachers.joinToString(","))
        editor.putString("all_extracted_rooms", rooms.joinToString(","))
        val success = editor.commit()

        L.d(TAG, "SharedPreferences sync success: $success")
        L.d(TAG, "Synced data to SharedPreferences:")
        L.d(TAG, "Subjects: ${subjects.joinToString(",")}")
        L.d(TAG, "Teachers: ${teachers.joinToString(",")}")
        L.d(TAG, "Rooms: ${rooms.joinToString(",")}")
        L.d(TAG, "=== END SYNC ===")

        val verifySubjects = sharedPreferences.getString("all_extracted_subjects", "")
        val verifyTeachers = sharedPreferences.getString("all_extracted_teachers", "")
        val verifyRooms = sharedPreferences.getString("all_extracted_rooms", "")

        L.d(TAG, "=== VERIFICATION ===")
        L.d(TAG, "Verified all_extracted_subjects: '$verifySubjects'")
        L.d(TAG, "Verified all_extracted_teachers: '$verifyTeachers'")
        L.d(TAG, "Verified all_extracted_rooms: '$verifyRooms'")
    }

    private fun confirmSelection() {
        L.d(TAG, "Confirming selection of ${selectedSubjects.size} subjects: $selectedSubjects")

        syncDataToSources()

        val subjects = allSubjectTriplets.map { it.subject }.toTypedArray()
        val teachers = allSubjectTriplets.map { it.teacher }.toTypedArray()
        val rooms = allSubjectTriplets.map { it.room }.toTypedArray()

        val intent = Intent().apply {
            putExtra(RESULT_SELECTED_SUBJECTS, selectedSubjects.toTypedArray())
            putExtra(EXTRA_SUBJECTS, subjects)
            putExtra(EXTRA_TEACHERS, teachers)
            putExtra(EXTRA_ROOMS, rooms)
        }
        setResult(RESULT_OK, intent)
        finish()
    }

    private fun updateCounters() {
        tvSelectionCount.text = selectedSubjects.size.toString()
        tvTotalCount.text = filteredSubjectTriplets.size.toString()

        btnConfirm.text = if (selectedSubjects.isEmpty()) {
            "Bestätigen"
        } else {
            "Bestätigen (${selectedSubjects.size})"
        }

        val visibleSelectedCount = selectedSubjects.intersect(filteredSubjectTriplets.map { it.subject }.toSet()).size
        val visibleTotalCount = filteredSubjectTriplets.size

        btnSelectAll.text = if (visibleTotalCount == 0) {
            "Alle auswählen"
        } else {
            "Alle auswählen ($visibleTotalCount)"
        }

        btnDeselectAll.text = if (visibleSelectedCount == 0) {
            "Alle abwählen"
        } else {
            "Alle abwählen ($visibleSelectedCount)"
        }

        btnSelectAll.isEnabled = visibleSelectedCount < visibleTotalCount
        btnDeselectAll.isEnabled = visibleSelectedCount > 0
    }

    override fun onSupportNavigateUp(): Boolean {
        handleBackPress()
        return true
    }

    class SubjectAdapter(
        private var subjectTriplets: MutableList<SubjectTriplet>,
        private val onSelectionChanged: (String, Boolean) -> Unit,
        private val onDeleteClicked: (SubjectTriplet) -> Unit,
        private val onEditClicked: (SubjectTriplet) -> Unit
    ) : RecyclerView.Adapter<SubjectAdapter.ViewHolder>() {

        private val selectedItems = mutableSetOf<String>()

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val checkBox: CheckBox = view.findViewById(R.id.checkBoxSubject)
            val textSubject: TextView = view.findViewById(R.id.textSubject)
            val btnEdit: ImageButton = view.findViewById(R.id.btnEdit)
            val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_subject_selection, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val triplet = subjectTriplets[position]
            val subject = triplet.subject

            // show the full display text (subject | teacher | room)
            holder.textSubject.text = triplet.getDisplayText()
            holder.checkBox.isChecked = selectedItems.contains(subject)

            val selectionClickListener = View.OnClickListener {
                val isSelected = !selectedItems.contains(subject)

                if (isSelected) {
                    selectedItems.add(subject)
                } else {
                    selectedItems.remove(subject)
                }

                holder.checkBox.isChecked = isSelected
                onSelectionChanged(subject, isSelected)
            }

            holder.checkBox.setOnClickListener(selectionClickListener)
            holder.itemView.setOnClickListener(selectionClickListener)
            holder.textSubject.setOnClickListener(selectionClickListener)

            holder.btnEdit.setOnClickListener {
                onEditClicked(triplet)
            }

            holder.btnDelete.setOnClickListener {
                onDeleteClicked(triplet)
            }
        }

        override fun getItemCount(): Int = subjectTriplets.size

        fun updateSubjectTriplets(newTriplets: List<SubjectTriplet>, selectedSubjects: Set<String>) {
            subjectTriplets.clear()
            subjectTriplets.addAll(newTriplets)

            selectedItems.clear()
            selectedItems.addAll(selectedSubjects)

            notifyDataSetChanged()
        }
    }
}