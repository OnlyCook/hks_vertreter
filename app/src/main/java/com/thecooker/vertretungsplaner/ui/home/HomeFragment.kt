package com.thecooker.vertretungsplaner.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.AttributeSet
import com.thecooker.vertretungsplaner.L
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import com.google.android.material.snackbar.Snackbar
import com.thecooker.vertretungsplaner.R
import com.thecooker.vertretungsplaner.databinding.FragmentHomeBinding
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class TouchScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var headerLayout: LinearLayout
    private lateinit var contentScrollView: ScrollView
    private lateinit var contentLayout: LinearLayout
    private lateinit var refreshButton: Button
    private lateinit var lastUpdateText: TextView
    private lateinit var classText: TextView
    private lateinit var invisibleLeftButton: ImageButton

    // pdf scanner and the users selected subjects
    private val studentSubjects = mutableListOf<String>()

    private var scope: CoroutineScope? = null
    private var isInitialized = false

    // filter variable
    private var filterOnlyMySubjects = false
    private lateinit var temporaryFilterButton: ImageButton
    private var isTemporaryFilterDisabled = false
    private var currentJsonData: JSONObject? = null

    // 1 minute cooldown variables
    private var cooldownEnabled = true
    private var lastLoadTime: Long = 0
    private val loadCooldownMs = 60000L // 1 minute in milliseconds
    private var isFirstLoad = true

    // color blind support
    private var colorBlindMode = "none"

    // scroll to refresh
    private var refreshContainer: LinearLayout? = null
    private var refreshIcon: ImageView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val homeViewModel = ViewModelProvider(this).get(HomeViewModel::class.java)

        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        sharedPreferences = requireActivity().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        setupUI()
        loadStudentSubjects()
        loadFilterSetting()
        updateTemporaryFilterButtonVisibility()
        loadCooldownSetting()

        val startupPageIndex = sharedPreferences.getInt("startup_page_index", 0)
        if (startupPageIndex == 1) {
            scope?.launch {
                delay(50) // delay to correctly load the page
                if (isAdded && _binding != null) {
                    loadSubstitutePlanWithCooldown()
                    isInitialized = true
                }
            }
        }

        L.d("HomeFragment", "onCreateView completed")
        return root
    }

    private fun loadStudentSubjects() {
        studentSubjects.clear()
        val savedSubjects = sharedPreferences.getString("student_subjects", "")
        if (!savedSubjects.isNullOrEmpty()) {
            studentSubjects.addAll(savedSubjects.split(",").filter { it.isNotBlank() })
        }
        L.d("HomeFragment", "Loaded ${studentSubjects.size} student subjects")
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        L.d("HomeFragment", "onViewCreated called")

        val startupPageIndex = sharedPreferences.getInt("startup_page_index", 0)
        if (startupPageIndex == 1) {
            if (isAdded && _binding != null && isResumed) {
                L.d("HomeFragment", "loading substitute plan immediately (startup page)")
                loadSubstitutePlanWithCooldown()
                isInitialized = true
            }
        } else {
            view.postDelayed({
                if (isAdded && _binding != null && isResumed) {
                    L.d("HomeFragment", "loading substitute plan after delay")
                    loadSubstitutePlanWithCooldown()
                    isInitialized = true
                }
            }, 200)
        }
    }

    override fun onResume() {
        super.onResume()
        L.d("HomeFragment", "onResume called, isInitialized: $isInitialized")

        loadStudentSubjects()
        loadFilterSetting()
        loadCooldownSetting()
        loadColorBlindSettings()
        updateTemporaryFilterButtonVisibility()

        if (::temporaryFilterButton.isInitialized) {
            reorganizeHeaderLayout()
        }

        if (isInitialized && _binding != null) {
            val startupPageIndex = sharedPreferences.getInt("startup_page_index", 0)
            if (startupPageIndex == 1) {
                if (isAdded && _binding != null && isResumed) {
                    L.d("HomeFragment", "Checking if substitute plan needs reload on resume (startup page)")
                    loadSubstitutePlanWithCooldown()
                }
            } else {
                binding.root.postDelayed({
                    if (isAdded && _binding != null && isResumed) {
                        L.d("HomeFragment", "Checking if substitute plan needs reload on resume")
                        loadSubstitutePlanWithCooldown()
                    }
                }, 100)
            }
        }
    }


    private fun updateTemporaryFilterButtonVisibility() {
        if (::temporaryFilterButton.isInitialized) {
            val hasScannedDocument = sharedPreferences.getBoolean("has_scanned_document", false)
            val filterEnabled = sharedPreferences.getBoolean("filter_only_my_subjects", false)
            val shouldShowButton = hasScannedDocument && filterEnabled

            temporaryFilterButton.visibility = if (shouldShowButton) {
                View.VISIBLE
            } else {
                View.GONE
            }

            if (::invisibleLeftButton.isInitialized) {
                invisibleLeftButton.visibility = if (shouldShowButton) {
                    View.INVISIBLE
                } else {
                    View.GONE
                }
            }

            L.d("HomeFragment", "Button visibility: hasScanned=$hasScannedDocument, filterEnabled=$filterEnabled, visible=$shouldShowButton")
        }
    }

    private fun loadCooldownSetting() {
        val removeCooldown = sharedPreferences.getBoolean("remove_update_cooldown", false)
        cooldownEnabled = !removeCooldown
        L.d("HomeFragment", "Cooldown setting loaded: $cooldownEnabled (remove_update_cooldown: $removeCooldown)")
    }

    private fun getColorBlindFriendlyColor(originalColor: String): Int {
        return when (colorBlindMode) {
            "protanopia" -> when (originalColor) {
                "red" -> resources.getColor(android.R.color.holo_orange_dark, null)
                "green" -> resources.getColor(android.R.color.holo_blue_light, null)
                "orange" -> resources.getColor(android.R.color.darker_gray, null)
                else -> resources.getColor(android.R.color.transparent, null)
            }
            "deuteranopia" -> when (originalColor) {
                "red" -> resources.getColor(android.R.color.holo_orange_dark, null)
                "green" -> resources.getColor(android.R.color.holo_blue_light, null)
                "orange" -> resources.getColor(android.R.color.darker_gray, null)
                else -> resources.getColor(android.R.color.transparent, null)
            }
            "tritanopia" -> when (originalColor) {
                "red" -> resources.getColor(android.R.color.holo_red_dark, null)
                "green" -> resources.getColor(android.R.color.holo_blue_dark, null)
                "orange" -> resources.getColor(android.R.color.holo_blue_light, null)
                else -> resources.getColor(android.R.color.transparent, null)
            }
            else -> when (originalColor) { // normal
                "red" -> resources.getColor(android.R.color.holo_red_light, null)
                "green" -> resources.getColor(android.R.color.holo_green_light, null)
                "orange" -> resources.getColor(android.R.color.holo_orange_light, null)
                else -> resources.getColor(android.R.color.transparent, null)
            }
        }
    }

    private fun loadSubstitutePlanWithCooldown() {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastLoad = currentTime - lastLoadTime

        val klasse = sharedPreferences.getString("selected_klasse", "Nicht ausgewählt")

        if (klasse == "Nicht ausgewählt") {
            showError("Keine Klasse ausgewählt. Bitte wählen Sie eine Klasse in den Einstellungen.")
            return
        }

        classText.text = "Klasse: $klasse"

        if (isFirstLoad || !cooldownEnabled || timeSinceLastLoad >= loadCooldownMs) {
            L.d("HomeFragment", "loading substitute plan; first load: $isFirstLoad, cooldown enabled: $cooldownEnabled, time since last: ${timeSinceLastLoad}ms")
            loadSubstitutePlan()
            lastLoadTime = currentTime
            isFirstLoad = false
        } else {
            val remainingCooldown = loadCooldownMs - timeSinceLastLoad
            L.d("HomeFragment", "Substitute plan loading skipped; cooldown remaining:${remainingCooldown}ms")

            loadCachedSubstitutePlan(klasse!!)
        }
    }

    private fun isSubjectRelevant(subject: String): Boolean {
        return studentSubjects.any { studentSubject ->
            subject.contains(studentSubject, ignoreCase = true)
        }
    }

    override fun onStart() {
        super.onStart()
        L.d("HomeFragment", "onStart called")

        if (!isInitialized) {
            loadStudentSubjects()
            loadFilterSetting()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        L.d("HomeFragment", "onDestroyView called - cancelling scope")

        cleanupRefreshIndicator()

        scope?.cancel()
        scope = null
        isInitialized = false
        isTemporaryFilterDisabled = false
        _binding = null
    }

    override fun onDetach() {
        super.onDetach()
        L.d("HomeFragment", "onDetach called")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUI() {
        val constraintLayout = binding.root as androidx.constraintlayout.widget.ConstraintLayout
        constraintLayout.removeAllViews()

        headerLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(resources.getColor(android.R.color.holo_blue_dark, null))
            elevation = 8f
        }

        classText = TextView(requireContext()).apply {
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setTextColor(resources.getColor(android.R.color.white, null))
            setPadding(0, 0, 0, 12)
        }

        lastUpdateText = TextView(requireContext()).apply {
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTextColor(resources.getColor(android.R.color.background_light, null))
            setPadding(0, 0, 0, 16)
        }

        refreshButton = Button(requireContext()).apply {
            text = "Aktualisieren"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setBackgroundColor(resources.getColor(android.R.color.white, null))
            setTextColor(resources.getColor(android.R.color.holo_blue_dark, null))
            setPadding(64, 32, 64, 32)
            background = createRoundedDrawable(resources.getColor(android.R.color.white, null))
            setOnClickListener {
                L.d("HomeFragment", "Refresh button clicked - forcing reload")
                isFirstLoad = true
                lastLoadTime = 0
                loadSubstitutePlan()
                lastLoadTime = System.currentTimeMillis()
                isFirstLoad = false
            }
        }

        val topRowLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val hasScannedDocument = sharedPreferences.getBoolean("has_scanned_document", false)
        val filterEnabled = sharedPreferences.getBoolean("filter_only_my_subjects", false)
        val shouldShowButtons = hasScannedDocument && filterEnabled
        val leftFilterLift = sharedPreferences.getBoolean("left_filter_lift", false)

        // invisible left button (spacer  for temp filter btn)
        invisibleLeftButton = ImageButton(requireContext()).apply {
            setImageResource(android.R.drawable.ic_menu_view)
            background = createRoundedDrawable(resources.getColor(android.R.color.white, null))
            setPadding(16, 16, 16, 16)
            isEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 16, 0)
            }
        }

        temporaryFilterButton = ImageButton(requireContext()).apply {
            setImageResource(R.drawable.ic_eye_closed)
            background = createRoundedDrawable(resources.getColor(android.R.color.white, null))
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(16, 0, 0, 0)
            }

            setOnTouchListener { view, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        setImageResource(R.drawable.ic_eye_open)
                        L.d("HomeFragment", "Temporary filter button pressed - lifting filter")
                        isTemporaryFilterDisabled = true
                        displaySubstitutePlan(getCurrentJsonData())
                        true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        setImageResource(R.drawable.ic_eye_closed)
                        L.d("HomeFragment", "Temporary filter button released - restoring filter")
                        isTemporaryFilterDisabled = false
                        displaySubstitutePlan(getCurrentJsonData())
                        true
                    }
                    else -> false
                }
            }
        }

        classText.apply {
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        // filter lift button views based on filter lift pos
        if (!leftFilterLift) {
            if (shouldShowButtons) {
                temporaryFilterButton.visibility = View.VISIBLE
                invisibleLeftButton.visibility = View.INVISIBLE
            } else {
                temporaryFilterButton.visibility = View.GONE
                invisibleLeftButton.visibility = View.GONE
            }
            topRowLayout.addView(temporaryFilterButton)
            topRowLayout.addView(classText)
            topRowLayout.addView(invisibleLeftButton)
        } else {
            if (shouldShowButtons) {
                temporaryFilterButton.visibility = View.VISIBLE
                invisibleLeftButton.visibility = View.INVISIBLE
            } else {
                temporaryFilterButton.visibility = View.GONE
                invisibleLeftButton.visibility = View.GONE
            }
            topRowLayout.addView(invisibleLeftButton)
            topRowLayout.addView(classText)
            topRowLayout.addView(temporaryFilterButton)
        }

        headerLayout.addView(topRowLayout)
        headerLayout.addView(lastUpdateText)
        headerLayout.addView(refreshButton)

        contentScrollView = ScrollView(requireContext()).apply {
            isFillViewport = true
        }
        contentLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 24)
        }
        contentScrollView.addView(contentLayout)

        headerLayout.id = View.generateViewId()
        contentScrollView.id = View.generateViewId()

        val headerParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_PARENT,
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        }

        val scrollParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_PARENT,
            0
        ).apply {
            topToBottom = headerLayout.id
            bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        }

        constraintLayout.addView(headerLayout, headerParams)
        constraintLayout.addView(contentScrollView, scrollParams)

        setupPullToRefresh()

        L.d("HomeFragment", "UI setup completed")
    }

    private fun loadColorBlindSettings() {
        colorBlindMode = sharedPreferences.getString("colorblind_mode", "none") ?: "none"
        L.d("HomeFragment", "Colorblind mode loaded: $colorBlindMode")
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return false
            val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            L.e("HomeFragment", "Error checking network availability", e)
            false
        }
    }

    private fun loadSubstitutePlan() {
        L.d("HomeFragment", "loadSubstitutePlan called")

        if (!isAdded || _binding == null || !isResumed) {
            L.w("HomeFragment", "Fragment not ready for loading substitute plan")
            return
        }

        if (scope == null || !scope!!.isActive) {
            L.d("HomeFragment", "Creating new coroutine scope")
            scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        }

        val klasse = sharedPreferences.getString("selected_klasse", "Nicht ausgewählt")

        if (klasse == "Nicht ausgewählt") {
            showError("Keine Klasse ausgewählt. Bitte wählen Sie eine Klasse in den Einstellungen.")
            return
        }

        classText.text = "Klasse: $klasse"

        if (!isNetworkAvailable()) {
            L.d("HomeFragment", "No internet connection, trying to load cached data")
            loadCachedSubstitutePlan(klasse!!)
            return
        }

        scope?.launch {
            try {
                L.d("HomeFragment", "Starting network request")

                if (!isAdded || _binding == null) {
                    L.w("HomeFragment", "Fragment detached during loading, aborting")
                    return@launch
                }

                if (::contentLayout.isInitialized) {
                    contentLayout.removeAllViews()
                    val loadingText = TextView(requireContext()).apply {
                        text = "Lade Vertretungsplan..."
                        gravity = android.view.Gravity.CENTER
                        textSize = 16f
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            gravity = android.view.Gravity.CENTER
                            topMargin = 100
                        }
                    }
                    contentLayout.addView(loadingText)
                } else {
                    L.w("HomeFragment", "contentLayout not initialized, skipping loading UI")
                }

                // fetch last update time
                val lastUpdate = withContext(Dispatchers.IO) {
                    fetchLastUpdateTime()
                }

                // retry
                if (!isAdded || _binding == null) {
                    L.w("HomeFragment", "Fragment detached during network call, aborting")
                    return@launch
                }

                // fetch substitute plan
                val substitutePlan = withContext(Dispatchers.IO) {
                    fetchSubstitutePlan(klasse!!)
                }

                // retry
                if (!isAdded || _binding == null) {
                    L.w("HomeFragment", "Fragment detached after network call, aborting UI update")
                    return@launch
                }

                saveSubstitutePlanToCache(klasse, substitutePlan.toString(), lastUpdate)

                if (::lastUpdateText.isInitialized) {
                    lastUpdateText.text = "Stand: $lastUpdate"
                }
                displaySubstitutePlan(substitutePlan)

                L.d("HomeFragment", "Successfully loaded substitute plan")

            } catch (e: Exception) {
                L.e("HomeFragment", "Error loading substitute plan", e)

                if (!isAdded || _binding == null) {
                    L.w("HomeFragment", "Fragment detached, skipping error display")
                    return@launch
                }

                view?.let {
                    Snackbar.make(it, "Netzwerkfehler: ${e.message}", Snackbar.LENGTH_LONG).show()
                }

                loadCachedSubstitutePlan(klasse!!)
            }
        }
    }

    private fun loadCachedSubstitutePlan(klasse: String) {
        try {
            val cacheFile = File(requireContext().cacheDir, "substitute_plan_$klasse.json")
            val lastUpdateFile = File(requireContext().cacheDir, "last_update_$klasse.txt")

            if (cacheFile.exists() && lastUpdateFile.exists()) {
                val cachedData = cacheFile.readText()
                val lastUpdate = lastUpdateFile.readText()

                val jsonData = JSONObject(cachedData)
                if (::lastUpdateText.isInitialized) {
                    lastUpdateText.text = "Stand: $lastUpdate (Offline)"
                }
                displaySubstitutePlan(jsonData)

                L.d("HomeFragment", "Loaded cached substitute plan")
            } else {
                showNoInternetMessage()
            }
        } catch (e: Exception) {
            L.e("HomeFragment", "Error loading cached data", e)
            showNoInternetMessage()
        }
    }

    private fun saveSubstitutePlanToCache(klasse: String?, jsonData: String, lastUpdate: String) {
        try {
            val cacheFile = File(requireContext().cacheDir, "substitute_plan_$klasse.json")
            val lastUpdateFile = File(requireContext().cacheDir, "last_update_$klasse.txt")

            cacheFile.writeText(jsonData)
            lastUpdateFile.writeText(lastUpdate)

            L.d("HomeFragment", "Saved substitute plan to cache")
        } catch (e: Exception) {
            L.e("HomeFragment", "Error saving to cache", e)
        }
    }

    private suspend fun fetchLastUpdateTime(): String {
        val url = URL("https://www.heinrich-kleyer-schule.de/aktuelles/vertretungsplan/")
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            val html = connection.inputStream.bufferedReader().use { it.readText() }

            // Extract the "Stand" information
            val regex = """<div class="vpstand">Stand: ([^<]+)</div>""".toRegex()
            val matchResult = regex.find(html)
            matchResult?.groups?.get(1)?.value ?: "Unbekannt"
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun fetchSubstitutePlan(klasse: String): JSONObject {
        val url = URL("https://www.heinrich-kleyer-schule.de/aktuelles/vertretungsplan/$klasse.json")
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
            val jsonData = JSONObject(jsonString)

            saveSubstitutePlanToCache(klasse, jsonData.toString(), "Online Data")

            jsonData
        } finally {
            connection.disconnect()
        }
    }

    private fun displaySubstitutePlan(jsonData: JSONObject) {
        currentJsonData = jsonData
        if (!::contentLayout.isInitialized || !isAdded) {
            L.w("HomeFragment", "Cannot display substitute plan - fragment not properly initialized")
            return
        }

        contentLayout.removeAllViews()

        val dates = jsonData.optJSONArray("dates")

        if (dates == null || dates.length() == 0) {
            val noEntriesText = TextView(requireContext()).apply {
                text = "Leider entfällt nichts. 😭"
                gravity = android.view.Gravity.CENTER
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(resources.getColor(android.R.color.darker_gray, null))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    gravity = android.view.Gravity.CENTER
                }
            }
            contentLayout.addView(noEntriesText)
            return
        }

        var hasVisibleEntries = false
        var totalEntries = 0
        var filteredEntries = 0

        val contentContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        for (i in 0 until dates.length()) {
            val dateObj = dates.getJSONObject(i)
            val dateString = dateObj.getString("date")
            val entries = dateObj.getJSONArray("entries")

            if (entries.length() > 0) {
                totalEntries += entries.length()

                val filteredEntriesForDate = mutableListOf<JSONObject>()
                for (j in 0 until entries.length()) {
                    val entry = entries.getJSONObject(j)
                    if (shouldShowEntry(entry)) {
                        filteredEntriesForDate.add(entry)
                        filteredEntries++
                    }
                }

                if (filteredEntriesForDate.isNotEmpty()) {
                    hasVisibleEntries = true

                    val (formattedDate, isToday) = formatDate(dateString)

                    val dateHeader = TextView(requireContext()).apply {
                        text = formattedDate
                        textSize = 18f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(
                            if (isToday) resources.getColor(android.R.color.holo_blue_dark, null)
                            else resources.getColor(android.R.color.darker_gray, null)
                        )
                        gravity = android.view.Gravity.CENTER
                        setPadding(16, 8, 16, 8)
                        setBackgroundColor(resources.getColor(android.R.color.background_light, null))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(8, 16, 8, 0)
                        }
                    }
                    contentContainer.addView(dateHeader)

                    val table = createSubstituteTable(filteredEntriesForDate)
                    contentContainer.addView(table)
                }
            }
        }

        if (!hasVisibleEntries) {
            val message = if (filterOnlyMySubjects && studentSubjects.isNotEmpty() && totalEntries > 0) {
                "Deine Fächer sind nicht betroffen! 😭"
            } else {
                "Leider entfällt nichts. 😭"
            }

            val noEntriesText = TextView(requireContext()).apply {
                text = message
                gravity = android.view.Gravity.CENTER
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(resources.getColor(android.R.color.darker_gray, null))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    gravity = android.view.Gravity.CENTER
                }
            }
            contentLayout.addView(noEntriesText)
            return
        }

        val maxWidthPx = (resources.displayMetrics.widthPixels * 0.95).toInt()
        contentContainer.layoutParams = LinearLayout.LayoutParams(
            maxWidthPx,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val horizontallyCenteredWrapper = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.TOP
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 16, 0, 0)
        }

        horizontallyCenteredWrapper.addView(contentContainer)
        contentLayout.addView(horizontallyCenteredWrapper)

        // filter
        if ((filterOnlyMySubjects || isTemporaryFilterDisabled) && studentSubjects.isNotEmpty() && totalEntries > 0) {
            val filterInfo = TextView(requireContext()).apply {
                text = when {
                    isTemporaryFilterDisabled -> "Filter temporär deaktiviert: Alle ${totalEntries} Einträge angezeigt"
                    totalEntries > filteredEntries && hasVisibleEntries -> "Filter aktiv: ${filteredEntries} von ${totalEntries} Einträgen angezeigt"
                    totalEntries > filteredEntries -> "Filter aktiv: Alle ${totalEntries} Einträge ausgeblendet"
                    else -> null
                }

                if (text != null) {
                    gravity = android.view.Gravity.CENTER
                    textSize = 12f
                    setTextColor(
                        if (isTemporaryFilterDisabled) resources.getColor(android.R.color.holo_orange_dark, null)
                        else resources.getColor(android.R.color.darker_gray, null)
                    )
                    setPadding(16, 12, 16, 12)
                    setTypeface(null, android.graphics.Typeface.ITALIC)
                    setBackgroundColor(resources.getColor(android.R.color.background_light, null))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 16, 0, 0)
                    }
                }
            }
            if (filterInfo.text != null) {
                contentLayout.addView(filterInfo)
            }
        }
    }

    private fun formatDate(dateString: String): Pair<String, Boolean> {
        val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val outputFormat = SimpleDateFormat("EEEE, dd.MM.yyyy", Locale.GERMAN)

        return try {
            val date = inputFormat.parse(dateString) ?: return Pair(dateString, false)

            val today = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            val targetDate = Calendar.getInstance().apply {
                time = date
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            val daysDifference =
                ((targetDate.timeInMillis - today.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()

            val formattedDate = outputFormat.format(date)
            val isToday = daysDifference == 0

            when {
                daysDifference == 0 -> Pair("$formattedDate (heute)", isToday)
                daysDifference == 1 -> Pair("$formattedDate (morgen)", isToday)
                daysDifference == 2 -> Pair("$formattedDate (übermorgen)", isToday)
                daysDifference >= 7 -> Pair("$formattedDate (nächste Woche)", isToday)
                else -> Pair(formattedDate, isToday)
            }
        } catch (e: Exception) {
            Pair(dateString, false)
        }
    }

    private fun createReducedHeightBackground(color: Int): android.graphics.drawable.LayerDrawable {
        val backgroundDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = 12f
        }

        val insetDrawable = android.graphics.drawable.InsetDrawable(backgroundDrawable, 0, 6, 0, 6)

        return android.graphics.drawable.LayerDrawable(arrayOf(insetDrawable))
    }

    private fun createSubstituteTable(entries: List<JSONObject>): TableLayout {
        val table = TableLayout(requireContext()).apply {
            // setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(16, 8, 16, 24)
            }
        }

        // header row
        val headerRow = TableRow(requireContext()).apply {
            background = createRoundedDrawable(resources.getColor(android.R.color.holo_blue_dark, null))
            setPadding(0, 0, 0, 0)
            layoutParams = TableLayout.LayoutParams(
                TableLayout.LayoutParams.MATCH_PARENT,
                TableLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 8) // rng ah
            }
        }

        val headers = arrayOf("Stunde", "Fach", "Raum", "Art")
        val headerWeights = arrayOf(0.9f, 1f, 0.8f, 2.3f)

        headers.forEachIndexed { index, headerText ->
            val headerCell = TextView(requireContext()).apply {
                text = headerText
                setPadding(12, 12, 12, 12)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(resources.getColor(android.R.color.white, null))
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, headerWeights[index])
            }
            headerRow.addView(headerCell)
        }
        table.addView(headerRow)

        // content rows
        entries.forEachIndexed { i, entry ->
            val row = TableRow(requireContext()).apply {
                setBackgroundColor(resources.getColor(android.R.color.background_light, null))
                setPadding(0, 4, 0, 4)
                minimumHeight = 80
            }

            // stunde column
            val stunde = entry.getInt("stunde")
            val stundeBis = entry.optInt("stundebis", -1)
            val stundeText = if (stundeBis != -1 && stundeBis != stunde) {
                val duration = stundeBis - stunde + 1
                "$stunde.-$stundeBis.\n($duration Std.)"
            } else {
                "$stunde.\n(1 Std.)"
            }

            val stundeCell = TextView(requireContext()).apply {
                text = stundeText
                setPadding(8, 12, 8, 12)
                textSize = 13f
                setTextColor(resources.getColor(android.R.color.black, null))
                gravity = android.view.Gravity.CENTER
                layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.MATCH_PARENT, headerWeights[0])
            }
            row.addView(stundeCell)

            // fach column
            val fachText = entry.optString("fach", "")
            val fachCell = TextView(requireContext()).apply {
                text = fachText
                textSize = 14f
                setTextColor(resources.getColor(android.R.color.black, null))
                gravity = android.view.Gravity.CENTER
                layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.MATCH_PARENT, headerWeights[1])

                if (isStudentSubject(fachText)) {
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(resources.getColor(android.R.color.white, null))
                    background = createReducedHeightBackground(resources.getColor(android.R.color.holo_blue_dark, null))
                    setPadding(12, 12, 12, 12)
                } else {
                    setPadding(8, 12, 8, 12)
                }
            }
            row.addView(fachCell)

            // raum column
            val raumCell = TextView(requireContext()).apply {
                text = entry.optString("raum", "")
                setPadding(8, 12, 8, 12)
                textSize = 13f
                setTextColor(resources.getColor(android.R.color.black, null))
                gravity = android.view.Gravity.CENTER
                layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.MATCH_PARENT, headerWeights[2])
            }
            row.addView(raumCell)

            // art column
            val originalText = entry.optString("text", "")
            val displayText = formatArtText(originalText)
            val artCell = TextView(requireContext()).apply {
                text = displayText
                textSize = 12f
                setTextColor(resources.getColor(android.R.color.black, null))
                gravity = android.view.Gravity.CENTER
                layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.MATCH_PARENT, headerWeights[3])

                val backgroundColor = getArtBackgroundColorColorBlindFriendly(originalText)
                if (backgroundColor != resources.getColor(android.R.color.transparent, null)) {
                    background = createReducedHeightBackground(backgroundColor)
                    setPadding(12, 12, 12, 12)
                } else {
                    setPadding(12, 12, 12, 12)
                }
            }
            row.addView(artCell)

            table.addView(row)
        }

        return table
    }

    private fun getArtBackgroundColorColorBlindFriendly(text: String): Int {
        return when {
            text.contains("Entfällt") || text == "Auf einen anderen Termin verlegt" ->
                getColorBlindFriendlyColor("red")
            text.contains("Wird vertreten") || text.contains("entfällt, stattdessen") ->
                getColorBlindFriendlyColor("green")
            text.contains("Wird betreut") ->
                getColorBlindFriendlyColor("orange")
            else -> resources.getColor(android.R.color.transparent, null)
        }
    }

    private fun createRoundedDrawable(color: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = 12f
        }
    }

    private fun formatArtText(originalText: String): String {
        return when {
            // "Auf einen anderen Termin verlegt"
            originalText == "Auf einen anderen Termin verlegt" -> "Entfällt (verlegt)"

            // "Sp-3 entfällt, stattdessen De-3 in Raum A403"
            originalText.matches(Regex(".*entfällt, stattdessen .* in Raum .*")) -> {
                val regex = Regex(".*entfällt, stattdessen (.*) in Raum (.*)")
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

            // "IT-3 entfällt, stattdessen PI-3"
            originalText.matches(Regex(".*entfällt, stattdessen .*")) -> {
                val regex = Regex("(.*) entfällt, stattdessen (.*)")
                val matchResult = regex.find(originalText)
                if (matchResult != null) {
                    val originalSubject = matchResult.groups[1]?.value ?: ""
                    val newSubject = matchResult.groups[2]?.value ?: ""
                    "$newSubject (statt $originalSubject)"
                } else {
                    originalText
                }
            }

            // hard coded cases
            originalText == "Entfällt wegen Exkursion, Praktikum oder Veranstaltung" -> "Entfällt (Exk., Prak., Veran.)"

            else -> originalText
        }
    }

    private fun isStudentSubject(fach: String): Boolean {
        return studentSubjects.any { subject ->
            fach.equals(subject, ignoreCase = true)
        }
    }

    private fun showError(message: String) {
        if (!::contentLayout.isInitialized || !isAdded) {
            L.w("HomeFragment", "Cannot show error - fragment not properly initialized")
            return
        }

        contentLayout.removeAllViews()
        val errorText = TextView(requireContext()).apply {
            text = message
            gravity = android.view.Gravity.CENTER
            textSize = 16f
            setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = android.view.Gravity.CENTER
            }
        }
        contentLayout.addView(errorText)
    }

    private fun showNoInternetMessage() {
        if (!::contentLayout.isInitialized || !isAdded) {
            L.w("HomeFragment", "cannot show no internet message, fragment not properly initialized")
            return
        }

        contentLayout.removeAllViews()
        val noInternetText = TextView(requireContext()).apply {
            text = "Keine Internetverbindung.\nBitte überprüfe deine Verbindung und versuche es erneut."
            gravity = android.view.Gravity.CENTER
            textSize = 16f
            setTextColor(resources.getColor(android.R.color.holo_orange_dark, null))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = android.view.Gravity.CENTER
            }
        }
        contentLayout.addView(noInternetText)
    }

    private fun loadFilterSetting() {
        filterOnlyMySubjects = sharedPreferences.getBoolean("filter_only_my_subjects", false)
        L.d("HomeFragment", "Filter setting loaded: $filterOnlyMySubjects")
    }

    private fun shouldShowEntry(entry: JSONObject): Boolean {
        if (isTemporaryFilterDisabled || !filterOnlyMySubjects || studentSubjects.isEmpty()) {
            return true
        }

        // check for match inside users selected subjects
        val fachText = entry.optString("fach", "")
        return isSubjectRelevant(fachText)
    }

    private fun getCurrentJsonData(): JSONObject {
        return currentJsonData ?: JSONObject()
    }

    private fun reorganizeHeaderLayout() {
        if (!::temporaryFilterButton.isInitialized || !::invisibleLeftButton.isInitialized || !::classText.isInitialized) {
            return
        }

        val hasScannedDocument = sharedPreferences.getBoolean("has_scanned_document", false)
        val filterEnabled = sharedPreferences.getBoolean("filter_only_my_subjects", false)
        val shouldShowButtons = hasScannedDocument && filterEnabled
        val leftFilterLift = sharedPreferences.getBoolean("left_filter_lift", false)

        val topRowLayout = headerLayout.getChildAt(0) as LinearLayout

        topRowLayout.removeAllViews()

        if (shouldShowButtons) {
            temporaryFilterButton.visibility = View.VISIBLE
            invisibleLeftButton.visibility = View.INVISIBLE
        } else {
            temporaryFilterButton.visibility = View.GONE
            invisibleLeftButton.visibility = View.GONE
        }

        if (!leftFilterLift) {
            topRowLayout.addView(temporaryFilterButton)
            topRowLayout.addView(classText)
            topRowLayout.addView(invisibleLeftButton)
        } else {
            topRowLayout.addView(invisibleLeftButton)
            topRowLayout.addView(classText)
            topRowLayout.addView(temporaryFilterButton)
        }

        L.d("HomeFragment", "Header layout reorganized - leftFilterLift: $leftFilterLift")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPullToRefresh() {
        var initialY = 0f
        var isPulling = false
        var hasTriggeredRefresh = false
        val maxPullDistance = 400f
        val triggerDistance = 300f

        contentScrollView.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    initialY = event.rawY
                    isPulling = false
                    hasTriggeredRefresh = false

                    if (contentScrollView.scrollY == 0) {
                        cleanupRefreshIndicator()
                        createRefreshIndicator()
                    }
                    false
                }

                android.view.MotionEvent.ACTION_MOVE -> {
                    if (contentScrollView.scrollY == 0 && refreshContainer != null && !hasTriggeredRefresh) {
                        val currentY = event.rawY
                        val deltaY = currentY - initialY

                        if (deltaY > 0) {
                            isPulling = true
                            val constrainedDeltaY = deltaY.coerceAtMost(maxPullDistance)
                            updateRefreshIndicator(constrainedDeltaY, maxPullDistance, triggerDistance)
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }

                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (isPulling && refreshContainer != null && !hasTriggeredRefresh) {
                        val currentY = event.rawY
                        val deltaY = currentY - initialY

                        if (deltaY >= triggerDistance) {
                            hasTriggeredRefresh = true
                            triggerRefresh()
                        } else {
                            animateRefreshContainerAway()
                        }
                        view.performClick()
                    }
                    isPulling = false
                    false
                }

                else -> false
            }
        }
    }

    private fun createRefreshIndicator() {
        refreshContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0
            )
        }

        refreshIcon = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_refresh)
            layoutParams = LinearLayout.LayoutParams(
                72,
                72
            ).apply {
                gravity = android.view.Gravity.CENTER
                setMargins(0, 20, 0, 20)
            }
            alpha = 0f
            scaleX = 0.5f
            scaleY = 0.5f
            rotation = 0f
            clearColorFilter()
        }

        refreshContainer?.addView(refreshIcon)
        contentLayout.addView(refreshContainer, 0)
    }

    private fun updateRefreshIndicator(deltaY: Float, maxPullDistance: Float, triggerDistance: Float) {
        refreshContainer?.let { container ->
            refreshIcon?.let { icon ->
                // calculate pull progress (0 to 1)
                val pullProgress = (deltaY / maxPullDistance).coerceAtMost(1f)
                val triggerProgress = (deltaY / triggerDistance).coerceAtMost(1f)

                val newHeight = (deltaY * 0.4f).toInt()
                val layoutParams = container.layoutParams as LinearLayout.LayoutParams
                layoutParams.height = newHeight
                container.layoutParams = layoutParams

                icon.alpha = pullProgress.coerceAtMost(1f)
                icon.rotation = pullProgress * 270f

                val scale = 0.6f + (pullProgress * 0.4f)
                icon.scaleX = scale
                icon.scaleY = scale

                if (triggerProgress >= 1f) {
                    icon.setColorFilter(resources.getColor(android.R.color.holo_blue_dark, null))
                    // val vibrator = context?.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                    // vibrator?.vibrate(50)
                } else {
                    icon.setColorFilter(resources.getColor(android.R.color.darker_gray, null))
                }
            }
        }
    }

    private fun triggerRefresh() {
        refreshIcon?.let { icon ->
            icon.clearAnimation()
            icon.alpha = 1f
            icon.setColorFilter(resources.getColor(android.R.color.holo_blue_dark, null))

            val rotateAnimation = android.view.animation.RotateAnimation(
                0f, 360f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
            ).apply {
                duration = 800
                repeatCount = android.view.animation.Animation.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
            }
            icon.startAnimation(rotateAnimation)
        }

        L.d("HomeFragment", "Pull-to-refresh triggered")

        scope?.launch {
            try {
                isFirstLoad = true
                lastLoadTime = 0
                loadSubstitutePlan()
                lastLoadTime = System.currentTimeMillis()
                isFirstLoad = false

                delay(800)
            } finally {
                if (isAdded && _binding != null) {
                    animateRefreshContainerAway()
                }
            }
        }
    }

    private fun animateRefreshContainerAway() {
        refreshContainer?.let { container ->
            refreshIcon?.let { icon ->
                icon.clearAnimation()
            }

            val currentHeight = container.layoutParams.height.coerceAtLeast(1)
            val animator = android.animation.ValueAnimator.ofInt(currentHeight, 0)
            animator.duration = 250
            animator.interpolator = android.view.animation.AccelerateInterpolator()

            animator.addUpdateListener { animation ->
                refreshContainer?.let {
                    val animatedValue = animation.animatedValue as Int
                    val layoutParams = container.layoutParams as LinearLayout.LayoutParams
                    layoutParams.height = animatedValue
                    container.layoutParams = layoutParams

                    val progress = if (currentHeight > 0) {
                        1f - (animatedValue.toFloat() / currentHeight.toFloat())
                    } else {
                        1f
                    }
                    refreshIcon?.alpha = (1f - progress).coerceAtLeast(0f)
                }
            }

            animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    cleanupRefreshIndicator()
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cleanupRefreshIndicator()
                }
            })

            animator.start()
        } ?: run {
            cleanupRefreshIndicator()
        }
    }

    private fun cleanupRefreshIndicator() {
        refreshIcon?.let { icon ->
            icon.clearAnimation()
            icon.clearColorFilter()
        }

        refreshContainer?.let { container ->
            try {
                if (container.parent == contentLayout) {
                    contentLayout.removeView(container)
                }
            } catch (e: Exception) {
                L.w("HomeFragment", "Error removing refresh container: ${e.message}")
            }
        }

        refreshContainer = null
        refreshIcon = null
    }

    override fun onPause() {
        super.onPause()
        cleanupRefreshIndicator()
    }
}