package com.thecooker.vertretungsplaner.ui.home

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import com.thecooker.vertretungsplaner.L
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.snackbar.Snackbar
import com.thecooker.vertretungsplaner.R
import com.thecooker.vertretungsplaner.databinding.FragmentHomeBinding
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import android.provider.Settings
import android.graphics.drawable.ColorDrawable
import android.graphics.Color
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import androidx.core.view.isEmpty
import androidx.core.content.edit

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
    private var initializationAttempts = 0
    private val maxInitializationAttempts = 3
    private var isInSharedContentMode = false
    private var isFirstStartup = true
    private var isFromNavigationDrawer = false

    // filter variable
    private var filterOnlyMySubjects = false
    private lateinit var temporaryFilterButton: ImageButton
    private var isTemporaryFilterDisabled = false
    private var currentJsonData: JSONObject? = null

    // 1 minute cooldown variables
    private var cooldownEnabled = true
    private val loadCooldownMs = 60000L // 1 min in ms

    private var hasShownOfflineMessage = false
    private var lastOfflineMessageTime = 0L
    private val offlineMessageCooldown = 30000L

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
        try {
            ViewModelProvider(this)[HomeViewModel::class.java]
            _binding = FragmentHomeBinding.inflate(inflater, container, false)
            val root: View = binding.root

            if (isAdded && context != null) {
                sharedPreferences = requireActivity().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

                isFromNavigationDrawer = arguments?.getBoolean("from_navigation_drawer", false) ?: false
                isInSharedContentMode = sharedPreferences.getBoolean("skip_home_loading", false)

                if (isInSharedContentMode) {
                    L.d("HomeFragment", "In shared content mode - creating minimal UI")
                    setupMinimalUI()
                } else {
                    setupUI()
                    loadStudentSubjects()
                    loadFilterSetting()
                    updateTemporaryFilterButtonVisibility()
                    loadCooldownSetting()
                    loadColorBlindSettings()

                    if (isFirstStartup && !isFromNavigationDrawer) {
                        L.d("HomeFragment", "First startup - using clean startup flow")
                        performCleanStartup()
                        isFirstStartup = false
                    } else {
                        L.d("HomeFragment", "Non-startup load")
                        binding.root.postDelayed({
                            if (isAdded && _binding != null) {
                                startBackgroundDataLoad()
                            }
                        }, 25)
                        isFirstStartup = false
                    }
                }
            }

            L.d("HomeFragment", "onCreateView completed")
            return root
        } catch (e: Exception) {
            L.e("HomeFragment", "Error in onCreateView", e)
            return createErrorView(inflater, container)
        }
    }

    private fun performCleanStartup() {
        L.d("HomeFragment", "Performing clean startup")

        val klasse = sharedPreferences.getString("selected_klasse", getString(R.string.home_not_selected))
        if (klasse == getString(R.string.home_not_selected)) {
            showError(getString(R.string.home_no_class_selected))
            return
        }

        if (::classText.isInitialized) {
            classText.text = getString(R.string.home_class_prefix, klasse)
        }

        if (::lastUpdateText.isInitialized) {
            lastUpdateText.text = ""
        }

        showLoadingState()

        setLastLoadTime(0)
        hasShownOfflineMessage = false
        currentJsonData = null

        scope?.launch(Dispatchers.IO) {
            try {
                if (hasBasicConnectivity()) {
                    L.d("HomeFragment", "Clean startup: Loading network data")
                    loadNetworkDataClean(klasse!!)
                } else {
                    L.d("HomeFragment", "Clean startup: No connectivity, loading cached data")
                    val hasCachedData = loadCachedDataInBackground(klasse!!, true)
                    withContext(Dispatchers.Main) {
                        if (!hasCachedData) {
                            showOfflineNoDataMessage()
                        } else {
                            showOfflineMessageIfNeeded()
                        }
                    }
                }
            } catch (e: Exception) {
                L.e("HomeFragment", "Error in clean startup", e)
                withContext(Dispatchers.Main) {
                    showOfflineNoDataMessage()
                }
            }
        }
    }

    private suspend fun loadNetworkDataClean(klasse: String) {
        try {
            L.d("HomeFragment", "Clean network load attempt")

            val lastUpdate = fetchLastUpdateTime()
            val substitutePlan = fetchSubstitutePlan(klasse)

            saveSubstitutePlanToCache(klasse, substitutePlan.toString(), lastUpdate)
            val filteredSubstitutePlan = filterPastDates(substitutePlan)

            withContext(Dispatchers.Main) {
                if (isAdded && _binding != null) {
                    onConnectivityRestored()

                    if (::lastUpdateText.isInitialized) {
                        lastUpdateText.text = getString(R.string.home_last_update, lastUpdate)
                    }

                    contentLayout.removeAllViews()
                    displaySubstitutePlan(filteredSubstitutePlan)
                    currentJsonData = filteredSubstitutePlan

                    setLastLoadTime(System.currentTimeMillis())
                    setFirstAppLoadCompleted()

                    L.d("HomeFragment", "Clean startup completed successfully")
                }
            }
        } catch (e: Exception) {
            L.e("HomeFragment", "Clean network load failed", e)
            val hasCachedData = loadCachedDataInBackground(klasse, true)
            withContext(Dispatchers.Main) {
                if (isAdded && _binding != null) {
                    if (!hasCachedData) {
                        showOfflineNoDataMessage()
                    } else {
                        view?.let {
                            Snackbar.make(it, getString(R.string.home_network_error_using_cache), Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun showNoDataMessage() {
        if (!::contentLayout.isInitialized || !isAdded) return

        contentLayout.removeAllViews()
        val noDataText = TextView(requireContext()).apply {
            text = getString(R.string.home_no_cached_data)
            gravity = android.view.Gravity.CENTER
            textSize = 16f
            alpha = 0.7f
            setTextColor(getThemeColor(R.attr.textSecondaryColor))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER
                topMargin = 50
            }
        }
        contentLayout.addView(noDataText)
    }

    private fun startBackgroundDataLoad() {
        L.d("HomeFragment", "Starting background data load")

        val klasse = sharedPreferences.getString("selected_klasse", getString(R.string.home_not_selected))
        if (klasse == getString(R.string.home_not_selected)) {
            showError(getString(R.string.home_no_class_selected))
            return
        }

        if (::classText.isInitialized) {
            classText.text = getString(R.string.home_class_prefix, klasse)
        }

        val hasBasicConn = hasBasicConnectivity()

        val willLoadFromNetwork = hasBasicConn && shouldLoadFromNetwork()

        setInitialLastUpdateText(klasse!!, willLoadFromNetwork)

        scope?.launch(Dispatchers.IO) {
            try {
                if (willLoadFromNetwork) {
                    withContext(Dispatchers.Main) {
                        showLoadingState()
                    }
                    loadNetworkDataInBackground(klasse)
                } else {
                    val hasCachedData = loadCachedDataInBackground(klasse)
                    if (!hasCachedData) {
                        withContext(Dispatchers.Main) {
                            if (!hasBasicConn) {
                                showOfflineNoDataMessage()
                            } else {
                                showNoDataMessage()
                            }
                        }
                    } else {
                        if (!hasBasicConn) {
                            withContext(Dispatchers.Main) {
                                showOfflineMessageIfNeeded()
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                L.e("HomeFragment", "Error in background data load", e)
                withContext(Dispatchers.Main) {
                    if (isAdded && _binding != null) {
                        scope?.launch(Dispatchers.IO) {
                            val hasCachedData = loadCachedDataInBackground(klasse)
                            if (!hasCachedData) {
                                withContext(Dispatchers.Main) {
                                    showOfflineNoDataMessage()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showOfflineNoDataMessage() {
        if (!::contentLayout.isInitialized || !isAdded) return

        contentLayout.removeAllViews()
        val noDataText = TextView(requireContext()).apply {
            text = getString(R.string.home_no_cached_data)
            gravity = android.view.Gravity.CENTER
            textSize = 16f
            alpha = 0.7f
            setTextColor(getThemeColor(R.attr.textSecondaryColor))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER
                topMargin = 50
            }
        }
        contentLayout.addView(noDataText)

        view?.let {
            Snackbar.make(it, getString(R.string.home_offline_mode), Snackbar.LENGTH_LONG).show()
        }
    }

    private fun shouldShowOfflineTag(isDisplayingCachedData: Boolean): Boolean {
        return isDisplayingCachedData
    }

    private fun setLastUpdateTextWithCorrectOfflineStatus(klasse: String, lastUpdate: String, isDisplayingCachedData: Boolean) {
        if (!::lastUpdateText.isInitialized) return

        try {
            val showOfflineTag = shouldShowOfflineTag(isDisplayingCachedData)

            lastUpdateText.text = if (showOfflineTag) {
                getString(R.string.home_last_update_offline, lastUpdate)
            } else {
                getString(R.string.home_last_update, lastUpdate)
            }

            L.d("HomeFragment", "Set lastUpdate text: offline=$showOfflineTag, cached=$isDisplayingCachedData, text='$lastUpdate'")
        } catch (e: Exception) {
            L.e("HomeFragment", "Error setting last update text", e)
            lastUpdateText.text = ""
        }
    }

    private fun setInitialLastUpdateText(klasse: String, willLoadFromNetwork: Boolean) {
        if (!::lastUpdateText.isInitialized) return

        try {
            if (isFirstStartup) {
                L.d("HomeFragment", "Skipping initial last update text during startup")
                return
            }

            val lastUpdateFile = File(requireContext().cacheDir, "last_update_$klasse.txt")

            if (isFirstAppLoad()) {
                lastUpdateText.text = ""
                L.d("HomeFragment", "First app load - clearing last update text until load completes")
                return
            }

            if (lastUpdateFile.exists()) {
                val lastUpdate = lastUpdateFile.readText()

                if (willLoadFromNetwork) {
                    lastUpdateText.text = getString(R.string.home_last_update, lastUpdate)
                    L.d("HomeFragment", "Set initial lastUpdate without offline tag (will load fresh): $lastUpdate")
                } else {
                    lastUpdateText.text = getString(R.string.home_last_update_offline, lastUpdate)
                    L.d("HomeFragment", "Set initial lastUpdate with offline tag (cached only): $lastUpdate")
                }
            } else {
                lastUpdateText.text = ""
            }
        } catch (e: Exception) {
            L.e("HomeFragment", "Error setting initial last update text", e)
            lastUpdateText.text = ""
        }
    }

    private fun showLoadingState() {
        if (!::contentLayout.isInitialized || !isAdded) return

        contentLayout.removeAllViews()
        val loadingText = TextView(requireContext()).apply {
            text = getString(R.string.home_loading_plan)
            gravity = android.view.Gravity.CENTER
            textSize = 16f
            alpha = 0.7f
            setTextColor(getThemeColor(R.attr.textSecondaryColor))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER
                topMargin = 50
            }
        }
        contentLayout.addView(loadingText)
    }

    private suspend fun loadCachedDataInBackground(klasse: String): Boolean {
        return loadCachedDataInBackground(klasse, true)
    }

    private suspend fun loadCachedDataInBackground(klasse: String, showAsOffline: Boolean): Boolean {
        return try {
            val cacheFile = File(requireContext().cacheDir, "substitute_plan_$klasse.json")

            if (cacheFile.exists()) {
                val cachedData = cacheFile.readText()
                val rawJsonData = JSONObject(cachedData)
                val filteredJsonData = filterPastDates(rawJsonData)

                currentJsonData = filteredJsonData

                withContext(Dispatchers.Main) {
                    if (isAdded && _binding != null && ::contentLayout.isInitialized) {
                        contentLayout.removeAllViews()
                        displaySubstitutePlan(filteredJsonData)

                        if (::lastUpdateText.isInitialized && showAsOffline) {
                            val lastUpdateFile = File(requireContext().cacheDir, "last_update_$klasse.txt")
                            if (lastUpdateFile.exists()) {
                                val lastUpdate = lastUpdateFile.readText()
                                setLastUpdateTextWithCorrectOfflineStatus(klasse, lastUpdate, true)
                            }
                        }

                        L.d("HomeFragment", "Displayed cached data" + if (showAsOffline) " with offline tag" else "")
                    }
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            L.e("HomeFragment", "Error loading cached data in background", e)
            false
        }
    }

    private fun showOfflineMessageIfNeeded(isManualRefresh: Boolean = false) {
        val currentTime = System.currentTimeMillis()

        if (isManualRefresh || !hasShownOfflineMessage || (currentTime - lastOfflineMessageTime) > offlineMessageCooldown) {
            view?.let {
                Snackbar.make(it, getString(R.string.home_offline_mode), Snackbar.LENGTH_SHORT).show()
                hasShownOfflineMessage = true
                lastOfflineMessageTime = currentTime
                L.d("HomeFragment", "Offline message shown (manual: $isManualRefresh)")
            }
        } else {
            L.d("HomeFragment", "Offline message suppressed (cooldown active)")
        }
    }

    private fun onConnectivityRestored() {
        hasShownOfflineMessage = false
        lastOfflineMessageTime = 0L
    }

    private suspend fun loadNetworkDataInBackground(klasse: String) {
        if (!hasBasicConnectivity()) {
            L.d("HomeFragment", "No connectivity detected at start, loading cached data immediately")
            val hasCachedData = loadCachedDataInBackground(klasse)
            withContext(Dispatchers.Main) {
                if (isAdded && _binding != null) {
                    if (!hasCachedData) {
                        showOfflineNoDataMessage()
                    } else {
                        showOfflineMessageIfNeeded()
                    }
                }
            }
            return
        }

        val retryDelays = listOf(1000L, 2000L, 3000L)

        for (attempt in 0..2) {
            try {
                L.d("HomeFragment", "Network attempt ${attempt + 1}/3")

                if (!hasBasicConnectivity()) {
                    L.d("HomeFragment", "Lost connectivity during attempt ${attempt + 1}, stopping retries")
                    break
                }

                val lastUpdate = fetchLastUpdateTime()
                val substitutePlan = fetchSubstitutePlan(klasse)

                saveSubstitutePlanToCache(klasse, substitutePlan.toString(), lastUpdate)

                val filteredSubstitutePlan = filterPastDates(substitutePlan)

                withContext(Dispatchers.Main) {
                    if (isAdded && _binding != null) {
                        onConnectivityRestored()

                        if (::lastUpdateText.isInitialized) {
                            lastUpdateText.text = getString(R.string.home_last_update, lastUpdate)
                            L.d("HomeFragment", "Network load successful - showing fresh update text: $lastUpdate")
                        }

                        contentLayout.removeAllViews()
                        displaySubstitutePlan(filteredSubstitutePlan)
                        currentJsonData = filteredSubstitutePlan
                        L.d("HomeFragment", "Displayed fresh network data")

                        setLastLoadTime(System.currentTimeMillis())
                        setFirstAppLoadCompleted()
                    }
                }
                return

            } catch (e: Exception) {
                L.w("HomeFragment", "Network attempt ${attempt + 1} failed: ${e.message}")

                if (!hasBasicConnectivity()) {
                    L.d("HomeFragment", "Connectivity lost during network error, stopping retries")
                    break
                }

                if (attempt < 2) {
                    val delay = retryDelays.getOrElse(attempt) { 2000L }
                    L.d("HomeFragment", "Retrying in ${delay}ms...")
                    delay(delay)
                }
            }
        }

        // all retries failed
        val hasCachedData = loadCachedDataInBackground(klasse, true)
        withContext(Dispatchers.Main) {
            if (isAdded && _binding != null) {
                if (!hasCachedData) {
                    showOfflineNoDataMessage()
                }

                val message = if (hasBasicConnectivity()) {
                    getString(R.string.home_network_error_using_cache)
                } else {
                    getString(R.string.home_offline_mode)
                }

                view?.let {
                    Snackbar.make(it, message, Snackbar.LENGTH_LONG).show()
                    if (!hasBasicConnectivity()) {
                        hasShownOfflineMessage = true
                        lastOfflineMessageTime = System.currentTimeMillis()
                    }
                }
            }
        }
    }

    private fun getLastLoadTime(): Long {
        return sharedPreferences.getLong("last_network_load_time", 0L)
    }

    private fun setLastLoadTime(time: Long) {
        sharedPreferences.edit { putLong("last_network_load_time", time) }
    }

    private fun isFirstAppLoad(): Boolean {
        return sharedPreferences.getBoolean("is_first_app_load", true)
    }

    private fun setFirstAppLoadCompleted() {
        sharedPreferences.edit { putBoolean("is_first_app_load", false)}
    }

    private fun shouldLoadFromNetwork(): Boolean {
        if (!cooldownEnabled) return true
        if (isFirstAppLoad()) return true

        val currentTime = System.currentTimeMillis()
        val lastLoad = getLastLoadTime()
        val timeSinceLastLoad = currentTime - lastLoad

        val shouldLoad = timeSinceLastLoad >= loadCooldownMs
        L.d("HomeFragment", "Cooldown check: enabled=$cooldownEnabled, timeSince=${timeSinceLastLoad}ms, threshold=${loadCooldownMs}ms, shouldLoad=$shouldLoad")
        return shouldLoad
    }

    private fun setupMinimalUI() {
        try {
            val constraintLayout = binding.root
            constraintLayout.removeAllViews()

            headerLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                setBackgroundColor(getThemeColor(R.attr.headerBackgroundColor))
                elevation = 8f
            }

            classText = TextView(requireContext()).apply {
                text = getString(R.string.act_set_substitution_plan)
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setTextColor(getThemeColor(R.attr.textPrimaryColor))
            }

            headerLayout.addView(classText)

            contentScrollView = ScrollView(requireContext()).apply {
                isFillViewport = true
                setBackgroundColor(getThemeColor(R.attr.homeBackgroundColor))
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

            showMinimalMessage()

        } catch (e: Exception) {
            L.e("HomeFragment", "Error in setupMinimalUI", e)
        }
    }

    private fun setupUI() {
        val constraintLayout = binding.root
        constraintLayout.removeAllViews()

        constraintLayout.setBackgroundColor(getThemeColor(R.attr.homeBackgroundColor))

        headerLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            elevation = 8f
            setBackgroundColor(getThemeColor(R.attr.headerBackgroundColor))
        }

        classText = TextView(requireContext()).apply {
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 12)
            setTextColor(getThemeColor(R.attr.textPrimaryColor))
        }

        lastUpdateText = TextView(requireContext()).apply {
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 16)
            setTextColor(getThemeColor(R.attr.textSecondaryColor))
        }

        refreshButton = Button(requireContext()).apply {
            text = getString(R.string.home_refresh)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(64, 32, 64, 32)
            setBackgroundColor(getThemeColor(R.attr.filterButtonBackgroundColor))
            setTextColor(getThemeColor(R.attr.refreshButtonTextColor))
            background = createRoundedDrawable(getThemeColor(R.attr.filterButtonBackgroundColor))
            setOnClickListener {
                L.d("HomeFragment", "Refresh button clicked - manual refresh")

                setLastLoadTime(0)
                hasShownOfflineMessage = false

                loadSubstitutePlan()

                setLastLoadTime(System.currentTimeMillis())
                setFirstAppLoadCompleted()
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

        // invisible left button (spacer for temp filter btn)
        invisibleLeftButton = ImageButton(requireContext()).apply {
            setImageResource(android.R.drawable.ic_menu_view)
            setPadding(16, 16, 16, 16)
            isEnabled = false
            background = createRoundedDrawable(getThemeColor(R.attr.filterButtonBackgroundColor))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 16, 0)
            }
        }

        temporaryFilterButton = ImageButton(requireContext()).apply {
            setImageResource(R.drawable.ic_eye_closed)
            setPadding(16, 16, 16, 16)
            background = createRoundedDrawable(getThemeColor(R.attr.filterButtonBackgroundColor))
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
            setBackgroundColor(getThemeColor(R.attr.homeBackgroundColor))
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

        if (isFirstStartup && !isFromNavigationDrawer) {
            setupPullToRefresh()
        } else {
            binding.root.post {
                setupPullToRefresh()
            }
        }

        L.d("HomeFragment", "UI setup completed")
    }

    private fun createErrorView(inflater: LayoutInflater, container: ViewGroup?): View {
        return try {
            _binding = FragmentHomeBinding.inflate(inflater, container, false)
            val root: View = binding.root

            val constraintLayout = root as androidx.constraintlayout.widget.ConstraintLayout
            constraintLayout.removeAllViews()

            val errorLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(24, 24, 24, 24)
                layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                    androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_PARENT,
                    androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_PARENT
                )
            }

            val errorText = TextView(requireContext()).apply {
                text = getString(R.string.home_fragment_init_error)
                textSize = 18f
                gravity = android.view.Gravity.CENTER
                setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
            }

            val retryButton = Button(requireContext()).apply {
                text = getString(R.string.home_retry_button)
                setOnClickListener {
                    retryInitialization()
                }
            }

            errorLayout.addView(errorText)
            errorLayout.addView(retryButton)
            constraintLayout.addView(errorLayout)

            root
        } catch (e: Exception) {
            L.e("HomeFragment", "Critical error creating error view", e)
            inflater.inflate(android.R.layout.simple_list_item_1, container, false)
        }
    }

    private fun showMinimalMessage() {
        contentLayout.let { layout ->
            layout.removeAllViews()
            val messageText = TextView(requireContext()).apply {
                text = getString(R.string.home_loading_minimal)
                gravity = android.view.Gravity.CENTER
                textSize = 16f
                setTextColor(getThemeColor(R.attr.textSecondaryColor))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    gravity = android.view.Gravity.CENTER
                }
            }
            layout.addView(messageText)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        L.d("HomeFragment", "onViewCreated called, shared mode: $isInSharedContentMode")

        arguments?.getString("highlight_substitute_subject")?.let { subject ->
            val lesson = arguments?.getInt("highlight_substitute_lesson", -1) ?: -1
            val lessonEnd = arguments?.getInt("highlight_substitute_lesson_end", lesson) ?: lesson
            val dateString = arguments?.getString("highlight_substitute_date", "") ?: ""
            val type = arguments?.getString("highlight_substitute_type", "") ?: ""
            val room = arguments?.getString("highlight_substitute_room", "") ?: ""

            L.d("HomeFragment", "ARGUMENTS RECEIVED: subject='$subject', lesson=$lesson-$lessonEnd, date='$dateString', type='$type', room='$room'")

            if (lesson != -1 && dateString.isNotEmpty()) {
                highlightWithRetry(subject, lesson, lessonEnd, dateString, type, room, 0)
            }

            arguments?.remove("highlight_substitute_subject")
            arguments?.remove("highlight_substitute_lesson")
            arguments?.remove("highlight_substitute_lesson_end")
            arguments?.remove("highlight_substitute_date")
            arguments?.remove("highlight_substitute_type")
            arguments?.remove("highlight_substitute_room")
        }
    }

    override fun onResume() {
        super.onResume()
        L.d("HomeFragment", "onResume called")

        try {
            if (!isAdded || _binding == null) {
                L.w("HomeFragment", "Fragment not properly attached")
                return
            }

            val wasInSharedMode = isInSharedContentMode
            isInSharedContentMode = sharedPreferences.getBoolean("skip_home_loading", false)

            if (wasInSharedMode && !isInSharedContentMode) {
                L.d("HomeFragment", "Exiting shared content mode - reinitializing")
                initializationAttempts = 0
                isInitialized = false

                binding.root.post {
                    if (isAdded && _binding != null) {
                        try {
                            setupUI()
                            if (isAdded && _binding != null) {
                                loadStudentSubjects()
                                loadFilterSetting()
                                loadCooldownSetting()
                                loadColorBlindSettings()
                                updateTemporaryFilterButtonVisibility()
                                startBackgroundDataLoad()
                            }
                        } catch (e: Exception) {
                            L.e("HomeFragment", "Error during forced reinitialization", e)
                            showSafeErrorState()
                        }
                    }
                }
                return
            }

            if (!isInSharedContentMode) {
                binding.root.post {
                    if (isAdded && _binding != null) {
                        loadStudentSubjects()
                        loadFilterSetting()
                        loadCooldownSetting()
                        loadColorBlindSettings()
                        updateTemporaryFilterButtonVisibility()
                        reorganizeHeaderLayout()

                        val klasse = sharedPreferences.getString("selected_klasse", getString(R.string.home_not_selected))
                        if (klasse != getString(R.string.home_not_selected) && ::classText.isInitialized) {
                            classText.text = getString(R.string.home_class_prefix, klasse)
                        }
                    }
                }

                if (isFirstStartup) {
                    L.d("HomeFragment", "onResume: Skipping data load during startup")
                    return
                }

                val hasConnectivity = hasBasicConnectivity()
                val shouldReload = shouldLoadFromNetwork()
                val hasCurrentData = currentJsonData != null && currentJsonData!!.toString() != "{}"

                L.d("HomeFragment", "onResume data check - hasConnectivity: $hasConnectivity, shouldReload: $shouldReload, hasCurrentData: $hasCurrentData")

                when {
                    hasConnectivity && shouldReload -> {
                        L.d("HomeFragment", "Connectivity available and cooldown allows reload")
                        binding.root.post {
                            if (isAdded && _binding != null && ::contentLayout.isInitialized) {
                                showLoadingState()
                            }
                        }
                        binding.root.post {
                            if (isAdded && _binding != null && !isInSharedContentMode) {
                                startBackgroundDataLoad()
                            }
                        }
                    }
                    !hasCurrentData && hasConnectivity -> {
                        L.d("HomeFragment", "No current data and connectivity available - loading regardless of cooldown")
                        binding.root.post {
                            if (isAdded && _binding != null && ::contentLayout.isInitialized) {
                                showLoadingState()
                            }
                        }
                        binding.root.post {
                            if (isAdded && _binding != null && !isInSharedContentMode) {
                                startBackgroundDataLoad()
                            }
                        }
                    }
                    !hasCurrentData && !hasConnectivity -> {
                        L.d("HomeFragment", "No current data and offline - attempting cached data load")
                        binding.root.post {
                            if (isAdded && _binding != null && !isInSharedContentMode) {
                                val klasse = sharedPreferences.getString("selected_klasse", getString(R.string.home_not_selected))
                                if (klasse != getString(R.string.home_not_selected)) {
                                    scope?.launch(Dispatchers.IO) {
                                        val hasCachedData = loadCachedDataInBackground(klasse!!, false)
                                        if (!hasCachedData) {
                                            withContext(Dispatchers.Main) {
                                                showOfflineNoDataMessage()
                                            }
                                        } else {
                                            withContext(Dispatchers.Main) {
                                                view?.let {
                                                    Snackbar.make(it, getString(R.string.home_offline_mode), Snackbar.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    hasCurrentData && !hasConnectivity -> {
                        L.d("HomeFragment", "Have cached data and offline - showing offline indicator")
                        binding.root.post {
                            view?.let {
                                Snackbar.make(it, getString(R.string.home_offline_mode), Snackbar.LENGTH_SHORT).show()
                            }
                        }
                    }
                    else -> {
                        L.d("HomeFragment", "Data preserved, cooldown active - no reload needed")
                        binding.root.post {
                            if (isAdded && _binding != null && currentJsonData != null) {
                                displaySubstitutePlan(currentJsonData!!)
                                if (::lastUpdateText.isInitialized && !hasConnectivity) {
                                    val klasse = sharedPreferences.getString(
                                        "selected_klasse",
                                        getString(R.string.home_not_selected)
                                    )
                                    if (klasse != getString(R.string.home_not_selected)) {
                                        val lastUpdateFile = File(
                                            requireContext().cacheDir,
                                            "last_update_$klasse.txt"
                                        )
                                        if (lastUpdateFile.exists()) {
                                            val lastUpdate = lastUpdateFile.readText()
                                            lastUpdateText.text = getString(
                                                R.string.home_last_update_offline,
                                                lastUpdate
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            L.e("HomeFragment", "Error in onResume", e)
            showSafeErrorState()
        }
    }

    private fun loadStudentSubjects() {
        try {
            studentSubjects.clear()
            val savedSubjects = sharedPreferences.getString("student_subjects", "")
            if (!savedSubjects.isNullOrEmpty()) {
                studentSubjects.addAll(savedSubjects.split(",").filter { it.isNotBlank() })
            }
            L.d("HomeFragment", "Loaded ${studentSubjects.size} student subjects")
        } catch (e: Exception) {
            L.e("HomeFragment", "Error loading student subjects", e)
        }
    }

    private fun safeLoadSubstitutePlan() {
        try {
            if (!isAdded || _binding == null) {
                L.w("HomeFragment", "Cannot load - fragment not ready")
                return
            }

            if (isInSharedContentMode) {
                L.d("HomeFragment", "Skipping substitute plan loading - in shared content mode")
                return
            }

            if (initializationAttempts >= maxInitializationAttempts) {
                L.e("HomeFragment", "Max initialization attempts reached")
                showRestartDialog()
                return
            }

            initializationAttempts++
            loadSubstitutePlanWithCooldown()
            isInitialized = true

        } catch (e: Exception) {
            L.e("HomeFragment", "Error in safeLoadSubstitutePlan", e)
            if (isAdded && _binding != null && initializationAttempts < maxInitializationAttempts) {
                binding.root.postDelayed({
                    if (isAdded && _binding != null) {
                        safeLoadSubstitutePlan()
                    }
                }, 1000)
            } else if (isAdded && _binding != null) {
                showRestartDialog()
            }
        }
    }

    private fun retryInitialization() {
        try {
            initializationAttempts = 0
            isInitialized = false
            isInSharedContentMode = false

            if (isAdded && _binding != null) {
                setupUI()
                loadStudentSubjects()
                loadFilterSetting()
                updateTemporaryFilterButtonVisibility()
                loadCooldownSetting()
                safeLoadSubstitutePlan()
            }
        } catch (e: Exception) {
            L.e("HomeFragment", "Error in retryInitialization", e)
            showRestartDialog()
        }
    }

    private fun showSafeErrorState() {
        try {
            contentLayout.let { layout ->
                layout.removeAllViews()
                val errorText = TextView(requireContext()).apply {
                    text = getString(R.string.home_fragment_load_error)
                    gravity = android.view.Gravity.CENTER
                    textSize = 16f
                    setTextColor(getThemeColor(R.attr.errorTextColor))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.MATCH_PARENT
                    ).apply {
                        gravity = android.view.Gravity.CENTER
                    }
                }

                val retryButton = Button(requireContext()).apply {
                    text = getString(R.string.home_retry_button)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = android.view.Gravity.CENTER
                        topMargin = 32
                    }
                    setBackgroundColor(getThemeColor(R.attr.settingsColorPrimary))
                    setTextColor(getThemeColor(R.attr.textPrimaryColor))
                    setOnClickListener {
                        retryInitialization()
                    }
                }

                layout.addView(errorText)
                layout.addView(retryButton)
            }
        } catch (e: Exception) {
            L.e("HomeFragment", "Error showing safe error state", e)
        }
    }

    private fun showRestartDialog() {
        try {
            if (!isAdded || context == null) return

            if (isInSharedContentMode || sharedPreferences.getBoolean("skip_home_loading", false)) {
                L.d("HomeFragment", "Skipping restart dialog - in shared content mode")
                return
            }

            if (initializationAttempts < maxInitializationAttempts) {
                L.d("HomeFragment", "Not showing restart dialog - attempts: $initializationAttempts")
                return
            }

            val alertDialog = android.app.AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.home_restart_dialog_title))
                .setMessage(getString(R.string.home_restart_dialog_message))
                .setPositiveButton(getString(R.string.home_restart_button)) { _, _ ->
                    requireActivity().finishAffinity()
                    val packageManager = requireActivity().packageManager
                    val intent = packageManager.getLaunchIntentForPackage(requireActivity().packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        startActivity(intent)
                    }
                }
                .setNegativeButton(getString(R.string.home_cancel_button), null)
                .setCancelable(false)
                .show()

            val buttonColor = requireContext().getThemeColor(R.attr.dialogSectionButtonColor)
            alertDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
            alertDialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
            alertDialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)

        } catch (e: Exception) {
            L.e("HomeFragment", "Error showing restart dialog", e)
        }
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

    private fun updateTemporaryFilterButtonVisibility() {
        try {
            temporaryFilterButton.let { button ->
                val hasScannedDocument = sharedPreferences.getBoolean("has_scanned_document", false)
                val filterEnabled = sharedPreferences.getBoolean("filter_only_my_subjects", false)
                val shouldShowButton = hasScannedDocument && filterEnabled

                button.visibility = if (shouldShowButton) View.VISIBLE else View.GONE
                invisibleLeftButton.visibility = if (shouldShowButton) View.INVISIBLE else View.GONE
            }
        } catch (e: Exception) {
            L.e("HomeFragment", "Error updating button visibility", e)
        }
    }

    private fun loadCooldownSetting() {
        try {
            val removeCooldown = sharedPreferences.getBoolean("remove_update_cooldown", true)
            cooldownEnabled = removeCooldown // this is cooked
            L.d("HomeFragment", "Cooldown setting loaded: $cooldownEnabled")
        } catch (e: Exception) {
            L.e("HomeFragment", "Error loading cooldown setting", e)
        }
    }

    private fun getColorBlindFriendlyColor(originalColor: String): Int {
        return when (colorBlindMode) {
            "protanopia", "deuteranopia" -> when (originalColor) {
                "red" -> getThemeColor(R.attr.colorblindCancelledCellBackgroundColor)
                "green" -> getThemeColor(R.attr.colorblindSubstitutedCellBackgroundColor)
                "orange" -> getThemeColor(R.attr.colorblindSupervisedCellBackgroundColor)
                else -> Color.TRANSPARENT
            }
            "tritanopia" -> when (originalColor) {
                "red" -> getThemeColor(R.attr.colorblindTritanopiaCancelledCellBackgroundColor)
                "green" -> getThemeColor(R.attr.colorblindTritanopiaSubstitutedCellBackgroundColor)
                "orange" -> getThemeColor(R.attr.colorblindTritanopiaSupervisedCellBackgroundColor)
                else -> Color.TRANSPARENT
            }
            else -> when (originalColor) { // normal mode
                "red" -> getThemeColor(R.attr.cancelledCellBackgroundColor)
                "green" -> getThemeColor(R.attr.substitutedCellBackgroundColor)
                "orange" -> getThemeColor(R.attr.supervisedCellBackgroundColor)
                else -> Color.TRANSPARENT
            }
        }
    }

    private fun loadSubstitutePlanWithCooldown() {
        try {
            if (!isAdded || _binding == null) {
                L.w("HomeFragment", "Fragment not ready for loading substitute plan")
                return
            }

            val currentTime = System.currentTimeMillis()
            val timeSinceLastLoad = currentTime - getLastLoadTime()

            val klasse = sharedPreferences.getString("selected_klasse", getString(R.string.home_not_selected))

            if (klasse == getString(R.string.home_not_selected)) {
                showError(getString(R.string.home_no_class_selected))
                return
            }

            classText.text = getString(R.string.home_class_prefix, klasse)

            val hasConnectivity = hasBasicConnectivity()
            val shouldLoadFromNetwork = isFirstAppLoad() || !cooldownEnabled || timeSinceLastLoad >= loadCooldownMs

            L.d("HomeFragment", "Cooldown check - shouldLoadFromNetwork: $shouldLoadFromNetwork, cooldownEnabled: $cooldownEnabled, timeSinceLastLoad: ${timeSinceLastLoad}ms, hasConnectivity: $hasConnectivity")

            if (shouldLoadFromNetwork && hasConnectivity) {
                L.d("HomeFragment", "Loading substitute plan from network")
                loadSubstitutePlan()
                setLastLoadTime(currentTime)
                setFirstAppLoadCompleted()
            } else {
                val remainingCooldown = if (cooldownEnabled) loadCooldownMs - timeSinceLastLoad else 0
                L.d("HomeFragment", "Loading cached data - cooldown remaining: ${remainingCooldown}ms, hasConnectivity: $hasConnectivity")

                scope?.launch(Dispatchers.IO) {
                    val hasCachedData = loadCachedDataInBackground(klasse!!)
                    withContext(Dispatchers.Main) {
                        if (hasCachedData) {
                            if (!hasConnectivity) {
                                view?.let {
                                    Snackbar.make(it, getString(R.string.home_offline_mode), Snackbar.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            if (!hasConnectivity) {
                                showOfflineNoDataMessage()
                            } else {
                                showNoDataMessage()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            L.e("HomeFragment", "Error in loadSubstitutePlanWithCooldown", e)
            showSafeErrorState()
        }
    }

    private fun immediateLoad() {
        if (!isAdded || _binding == null || isInSharedContentMode) {
            return
        }

        L.d("HomeFragment", "Immediate substitute plan loading started")
        startBackgroundDataLoad()
    }

    private fun isSubjectRelevant(subject: String): Boolean {
        return studentSubjects.any { studentSubject ->
            subject.contains(studentSubject, ignoreCase = true)
        }
    }

    private fun ensureCachedDataPersistence() {
        try {
            if (currentJsonData == null || currentJsonData!!.toString() == "{}") {
                L.d("HomeFragment", "No current data - attempting to restore from cache")
                val klasse = sharedPreferences.getString("selected_klasse", getString(R.string.home_not_selected))
                if (klasse != getString(R.string.home_not_selected)) {
                    scope?.launch(Dispatchers.IO) {
                        val hasCachedData = loadCachedDataInBackground(klasse!!)
                        if (hasCachedData) {
                            L.d("HomeFragment", "Successfully restored cached data")
                        } else {
                            L.d("HomeFragment", "No cached data available to restore")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            L.e("HomeFragment", "Error ensuring cached data persistence", e)
        }
    }

    override fun onStart() {
        super.onStart()
        L.d("HomeFragment", "onStart called, isInitialized: $isInitialized")

        loadStudentSubjects()
        loadFilterSetting()
        loadCooldownSetting()

        ensureCachedDataPersistence()

        if (!isInitialized && _binding != null) {
            if (isAdded && _binding != null && !isInitialized) {
                L.d("HomeFragment", "Immediate initialization in onStart")
                immediateLoad()
                isInitialized = true
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        L.d("HomeFragment", "onDestroyView called")

        try {
            cleanupRefreshIndicator()
            scope?.cancel()
        } catch (e: Exception) {
            L.e("HomeFragment", "Error in onDestroyView", e)
        } finally {
            scope = null
            isInitialized = false
            initializationAttempts = 0
            isTemporaryFilterDisabled = false
            isInSharedContentMode = false
            _binding = null
        }
    }

    override fun onDetach() {
        super.onDetach()
        L.d("HomeFragment", "onDetach called")
    }

    private fun getThemeColor(@AttrRes attrRes: Int): Int {
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(attrRes, typedValue, true)
        return ContextCompat.getColor(requireContext(), typedValue.resourceId)
    }

    private fun loadColorBlindSettings() {
        try {
            colorBlindMode = sharedPreferences.getString("colorblind_mode", "none") ?: "none"
            L.d("HomeFragment", "Colorblind mode loaded: $colorBlindMode")
        } catch (e: Exception) {
            L.e("HomeFragment", "Error loading colorblind settings", e)
        }
    }

    private fun hasBasicConnectivity(): Boolean {
        return try {
            val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return false
            val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

            val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val hasValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val hasTransport = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

            val isConnected = hasInternet && hasValidated && hasTransport
            L.d("HomeFragment", "Connectivity check: internet=$hasInternet, validated=$hasValidated, transport=$hasTransport, result=$isConnected")

            return isConnected
        } catch (e: Exception) {
            L.e("HomeFragment", "Error checking connectivity", e)
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

        val klasse = sharedPreferences.getString("selected_klasse", getString(R.string.home_not_selected))

        if (klasse == getString(R.string.home_not_selected)) {
            showError(getString(R.string.home_no_class_selected))
            return
        }

        if (::classText.isInitialized) {
            classText.text = getString(R.string.home_class_prefix, klasse)
        }

        val hasBasicConn = hasBasicConnectivity()

        if (isFirstAppLoad()) {
            setLastLoadTime(0)
            hasShownOfflineMessage = false
            L.d("HomeFragment", "First app load in loadSubstitutePlan - resetting cooldown")
        }

        val willLoadFromNetwork = hasBasicConn && shouldLoadFromNetwork()

        setInitialLastUpdateText(klasse!!, willLoadFromNetwork)

        scope?.launch(Dispatchers.IO) {
            try {
                if (willLoadFromNetwork) {
                    withContext(Dispatchers.Main) {
                        showLoadingState()
                    }
                    loadNetworkDataInBackground(klasse)
                } else {
                    val hasCachedData = loadCachedDataInBackground(klasse)
                    if (!hasCachedData) {
                        withContext(Dispatchers.Main) {
                            if (!hasBasicConn) {
                                showOfflineNoDataMessage()
                            } else {
                                showNoDataMessage()
                            }
                        }
                    } else if (!hasBasicConn) {
                        withContext(Dispatchers.Main) {
                            view?.let {
                                Snackbar.make(it, getString(R.string.home_offline_mode), Snackbar.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                L.e("HomeFragment", "Error in loadSubstitutePlan", e)
                withContext(Dispatchers.Main) {
                    if (isAdded && _binding != null) {
                        val hasCachedData = loadCachedDataInBackground(klasse)
                        if (!hasCachedData) {
                            showOfflineNoDataMessage()
                        }
                    }
                }
            }
        }
    }

    private fun loadCachedSubstitutePlan(klasse: String) = try {
        val cacheFile = File(requireContext().cacheDir, "substitute_plan_$klasse.json")
        val lastUpdateFile = File(requireContext().cacheDir, "last_update_$klasse.txt")

        if (cacheFile.exists() && lastUpdateFile.exists()) {
            val cachedData = cacheFile.readText()
            val lastUpdate = lastUpdateFile.readText()

            val rawJsonData = JSONObject(cachedData)
            val filteredJsonData = filterPastDates(rawJsonData)

            if (::lastUpdateText.isInitialized) {
                lastUpdateText.text = getString(R.string.home_last_update_offline, lastUpdate)
            }
            displaySubstitutePlan(filteredJsonData)

            L.d("HomeFragment", "Loaded cached substitute plan with past dates filtered")
        } else {
            if (!hasBasicConnectivity()) {
                L.d("HomeFragment", "No cache and no connectivity - showing offline message")
                showOfflineNoDataMessage()
            } else {
                L.d("HomeFragment", "No cache but connectivity available - attempting network load")
                loadSubstitutePlan()
            }
        }
    } catch (e: Exception) {
        L.e("HomeFragment", "Error loading cached data", e)
        if (!hasBasicConnectivity()) {
            showOfflineNoDataMessage()
        } else {
            L.d("HomeFragment", "Cache error but connectivity available - attempting network load")
            loadSubstitutePlan()
        }
    }

    private fun saveSubstitutePlanToCache(klasse: String?, jsonData: String, lastUpdate: String) {
        try {
            val cacheFile = File(requireContext().cacheDir, "substitute_plan_$klasse.json")
            val lastUpdateFile = File(requireContext().cacheDir, "last_update_$klasse.txt")

            cacheFile.writeText(jsonData)
            lastUpdateFile.writeText(lastUpdate)

            L.d("HomeFragment", "Saved substitute plan to cache with all entries intact")
        } catch (e: Exception) {
            L.e("HomeFragment", "Error saving to cache", e)
        }
    }

    private fun fetchLastUpdateTime(): String {
        val url = URL("https://www.heinrich-kleyer-schule.de/aktuelles/vertretungsplan/")
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            val html = connection.inputStream.bufferedReader().use { it.readText() }

            // Extract the "Stand" information
            val regex = """<div class="vpstand">Stand: ([^<]+)</div>""".toRegex()
            val matchResult = regex.find(html)
            val originalText = matchResult?.groups?.get(1)?.value ?: "Unbekannt"

            translateWeekdayInText(originalText)
        } finally {
            connection.disconnect()
        }
    }

    private fun translateWeekdayInText(text: String): String {
        val weekdayMap = mapOf(
            "Mo." to getString(R.string.monday_short),
            "Di." to getString(R.string.tuesday_short),
            "Mi." to getString(R.string.wednesday_short),
            "Do." to getString(R.string.thursday_short),
            "Fr." to getString(R.string.friday_short),
            "Sa." to getString(R.string.saturday_short),
            "So." to getString(R.string.sunday_short)
        )

        var translatedText = text
        weekdayMap.forEach { (german, localized) ->
            translatedText = translatedText.replace(german, localized)
        }

        return translatedText
    }

    private fun fetchSubstitutePlan(klasse: String): JSONObject {
        val url = URL("https://www.heinrich-kleyer-schule.de/aktuelles/vertretungsplan/$klasse.json")
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
            val jsonData = JSONObject(jsonString)

            saveSubstitutePlanToCache(klasse, jsonData.toString(), "Online Data")

            jsonData
        } finally {
            connection.disconnect()
        }
    }

    private fun debugSubstituteData(jsonData: JSONObject) {
        try {
            L.d("HomeFragment", "=== SUBSTITUTE DATA DEBUG ===")
            val dates = jsonData.optJSONArray("dates")
            if (dates != null) {
                L.d("HomeFragment", "Total dates in JSON: ${dates.length()}")
                for (i in 0 until dates.length()) {
                    val dateObj = dates.getJSONObject(i)
                    val dateString = dateObj.getString("date")
                    val entries = dateObj.getJSONArray("entries")
                    L.d("HomeFragment", "Date: $dateString has ${entries.length()} entries")

                    for (j in 0 until entries.length()) {
                        val entry = entries.getJSONObject(j)
                        val subject = entry.optString("fach", "")
                        val lesson = entry.getInt("stunde")
                        val lessonEnd = entry.optInt("stundebis", lesson)
                        val type = entry.optString("text", "")

                        if (type.contains("Exkursion", ignoreCase = true) ||
                            type == "Entfällt wegen Exkursion, Praktikum oder Veranstaltung") {
                            L.d("HomeFragment", "  *** EXCURSION ENTRY FOUND: subject='$subject', lesson=$lesson-$lessonEnd, type='$type'")
                        } else {
                            L.d("HomeFragment", "  Entry: subject='$subject', lesson=$lesson-$lessonEnd, type='$type'")
                        }
                    }
                }
            } else {
                L.w("HomeFragment", "No dates array found in JSON data")
            }
            L.d("HomeFragment", "=== END SUBSTITUTE DATA DEBUG ===")
        } catch (e: Exception) {
            L.e("HomeFragment", "Error in debugSubstituteData", e)
        }
    }

    private fun displaySubstitutePlan(jsonData: JSONObject) {
        currentJsonData = jsonData
        if (!::contentLayout.isInitialized || !isAdded) {
            L.w("HomeFragment", "Cannot display substitute plan - fragment not properly initialized")
            return
        }

        if (jsonData.length() == 0) {
            L.w("HomeFragment", "Empty JSON data provided")
            showOfflineNoDataMessage()
            return
        }

        //debugSubstitutePlan(jsonData)

        contentLayout.removeAllViews()

        val dates = jsonData.optJSONArray("dates")

        if (dates == null || dates.length() == 0) {
            val noEntriesText = TextView(requireContext()).apply {
                text = getString(R.string.home_no_entries_all)
                gravity = android.view.Gravity.CENTER
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getThemeColor(R.attr.textSecondaryColor))
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
                            if (isToday) getThemeColor(R.attr.settingsColorPrimary)
                            else getThemeColor(R.attr.textSecondaryColor)
                        )
                        gravity = android.view.Gravity.CENTER
                        setPadding(16, 8, 16, 8)
                        setBackgroundColor(getThemeColor(R.attr.tableBackgroundColor))
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
                getString(R.string.home_no_entries_filtered)
            } else {
                getString(R.string.home_no_entries_all)
            }

            val noEntriesText = TextView(requireContext()).apply {
                text = message
                gravity = android.view.Gravity.CENTER
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getThemeColor(R.attr.textSecondaryColor))
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
                    isTemporaryFilterDisabled -> getString(R.string.home_filter_temp_disabled, totalEntries)
                    totalEntries > filteredEntries -> getString(R.string.home_filter_active, filteredEntries, totalEntries)
                    else -> null
                }

                if (text != null) {
                    gravity = android.view.Gravity.CENTER
                    textSize = 12f
                    setTextColor(
                        if (isTemporaryFilterDisabled) getThemeColor(R.attr.warningTextColor)
                        else getThemeColor(R.attr.textSecondaryColor)
                    )
                    setPadding(16, 12, 16, 12)
                    setTypeface(null, android.graphics.Typeface.ITALIC)
                    setBackgroundColor(getThemeColor(R.attr.tableBackgroundColor))
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
        val germanOutputFormat = SimpleDateFormat("EEEE, dd.MM.yyyy", Locale.GERMAN)

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

            val germanFormattedDate = germanOutputFormat.format(date)
            val localizedDate = translateWeekdayInDate(germanFormattedDate)

            when {
                daysDifference == 0 -> Pair("$localizedDate ${getString(R.string.home_today_suffix)}", true)
                daysDifference == 1 -> Pair("$localizedDate ${getString(R.string.home_tomorrow_suffix)}", false)
                daysDifference == 2 -> Pair("$localizedDate ${getString(R.string.home_day_after_tomorrow_suffix)}", false)
                daysDifference >= 7 -> Pair("$localizedDate ${getString(R.string.home_next_week_suffix)}", false)
                else -> Pair(localizedDate, false)
            }
        } catch (_: Exception) {
            Pair(dateString, false)
        }
    }

    private fun translateWeekdayInDate(germanDate: String): String {
        val weekdayMap = mapOf(
            "Montag" to getString(R.string.monday),
            "Dienstag" to getString(R.string.tuesday),
            "Mittwoch" to getString(R.string.wednesday),
            "Donnerstag" to getString(R.string.thursday),
            "Freitag" to getString(R.string.friday),
            "Samstag" to getString(R.string.saturday), // safety
            "Sonntag" to getString(R.string.sunday)
        )

        var translatedDate = germanDate
        weekdayMap.forEach { (german, localized) ->
            translatedDate = translatedDate.replace(german, localized)
        }

        return translatedDate
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
            background = createRoundedDrawable(getThemeColor(R.attr.tableHeaderBackgroundColor))
            setPadding(0, 0, 0, 0)
            layoutParams = TableLayout.LayoutParams(
                TableLayout.LayoutParams.MATCH_PARENT,
                TableLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 8)
            }
        }

        val headers = arrayOf(getString(R.string.home_table_lesson), getString(R.string.home_table_subject), getString(R.string.home_table_room), getString(R.string.home_table_type))
        val headerWeights = arrayOf(0.9f, 1f, 0.8f, 2.3f)

        headers.forEachIndexed { index, headerText ->
            val headerCell = TextView(requireContext()).apply {
                text = headerText
                setPadding(12, 12, 12, 12)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getThemeColor(R.attr.tableHeaderTextColor))
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
                setBackgroundColor(getThemeColor(R.attr.tableBackgroundColor))
                setPadding(0, 4, 0, 4)
                minimumHeight = 80
            }

            // stunde column
            val stunde = entry.getInt("stunde")
            val stundeBis = entry.optInt("stundebis", -1)
            val stundeText = if (stundeBis != -1 && stundeBis != stunde) {
                val duration = stundeBis - stunde + 1
                getString(R.string.home_lesson_duration_multiple, stunde, stundeBis, duration)
            } else {
                getString(R.string.home_lesson_duration, stunde)
            }

            val stundeCell = TextView(requireContext()).apply {
                text = stundeText
                setPadding(8, 12, 8, 12)
                textSize = 13f
                setTextColor(getThemeColor(R.attr.tableTextColor))
                gravity = android.view.Gravity.CENTER
                layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.MATCH_PARENT, headerWeights[0])
            }
            row.addView(stundeCell)

            // fach column
            val fachText = entry.optString("fach", "")
            val artTextSubject = entry.optString("text", "")
            val isCancelled = artTextSubject.contains("Entfällt") || artTextSubject == "Auf einen anderen Termin verlegt"

            val isSubjectReplaced = artTextSubject.matches(Regex(".*entfällt, stattdessen .*"))
            val isSubjectReplacedWithRoom = artTextSubject.matches(Regex(".*entfällt, stattdessen .* in Raum .*"))

            val fachCell = TextView(requireContext()).apply {
                textSize = 14f
                setTextColor(getThemeColor(R.attr.tableTextColor))
                gravity = android.view.Gravity.CENTER
                layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.MATCH_PARENT, headerWeights[1])

                when {
                    isSubjectReplacedWithRoom -> {
                        val regex = Regex("(.*)entfällt, stattdessen (.*) in Raum .*")
                        val matchResult = regex.find(artTextSubject)
                        if (matchResult != null) {
                            val originalSubject = matchResult.groups[1]?.value?.trim() ?: fachText
                            val newSubject = matchResult.groups[2]?.value?.trim() ?: ""

                            val spannableString = android.text.SpannableString("$originalSubject\n$newSubject")
                            spannableString.setSpan(
                                android.text.style.StrikethroughSpan(),
                                0,
                                originalSubject.length,
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            spannableString.setSpan(
                                android.text.style.ForegroundColorSpan(getThemeColor(R.attr.successTextColor)),
                                originalSubject.length + 1,
                                spannableString.length,
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            text = spannableString

                            if (isStudentSubject(originalSubject) || isStudentSubject(newSubject)) {
                                setTypeface(null, android.graphics.Typeface.BOLD)
                                setTextColor(getThemeColor(R.attr.highlightTextColor))
                                background = createReducedHeightBackground(getThemeColor(R.attr.highlightBackgroundColor))
                                setPadding(12, 12, 12, 12)
                            } else {
                                setPadding(8, 12, 12, 12)
                                setTextColor(getThemeColor(R.attr.tableTextColor))
                            }
                        } else {
                            handleOriginalSubjectLogic(fachText, isCancelled)
                        }
                    }
                    isSubjectReplaced -> {
                        val regex = Regex("(.*) entfällt, stattdessen (.*)")
                        val matchResult = regex.find(artTextSubject)
                        if (matchResult != null) {
                            val originalSubject = matchResult.groups[1]?.value?.trim() ?: fachText
                            val newSubject = matchResult.groups[2]?.value?.trim() ?: ""

                            val spannableString = android.text.SpannableString("$originalSubject\n$newSubject")
                            spannableString.setSpan(
                                android.text.style.StrikethroughSpan(),
                                0,
                                originalSubject.length,
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            spannableString.setSpan(
                                android.text.style.ForegroundColorSpan(getThemeColor(R.attr.successTextColor)),
                                originalSubject.length + 1,
                                spannableString.length,
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            text = spannableString

                            if (isStudentSubject(originalSubject) || isStudentSubject(newSubject)) {
                                setTypeface(null, android.graphics.Typeface.BOLD)
                                setTextColor(getThemeColor(R.attr.highlightTextColor))
                                background = createReducedHeightBackground(getThemeColor(R.attr.highlightBackgroundColor))
                                setPadding(12, 12, 12, 12)
                            } else {
                                setPadding(8, 12, 12, 12)
                                setTextColor(getThemeColor(R.attr.tableTextColor))
                            }
                        } else {
                            handleOriginalSubjectLogic(fachText, isCancelled)
                        }
                    }
                    else -> {
                        handleOriginalSubjectLogic(fachText, isCancelled)
                    }
                }
            }
            row.addView(fachCell)

            // raum column
            val originalRoom = entry.optString("raum", "")
            val artTextRoom = entry.optString("text", "")
            var displayRoom = originalRoom

            val isRoomChanged = artTextRoom.matches(Regex("Findet in Raum .* statt"))
            val isSubjectReplacedWithRoomRaum = artTextRoom.matches(Regex(".*entfällt, stattdessen .* in Raum .*"))

            val raumCell = TextView(requireContext()).apply {
                setPadding(8, 12, 8, 12)
                textSize = 13f
                setTextColor(getThemeColor(R.attr.tableTextColor))
                gravity = android.view.Gravity.CENTER
                layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.MATCH_PARENT, headerWeights[2])

                when {
                    isSubjectReplacedWithRoomRaum -> {
                        val regex = Regex(".*entfällt, stattdessen .* in Raum (.*)")
                        val matchResult = regex.find(artTextRoom)
                        if (matchResult != null) {
                            val newRoom = matchResult.groups[1]?.value?.trim() ?: ""
                            val spannableString = android.text.SpannableString("$originalRoom\n$newRoom")
                            spannableString.setSpan(
                                android.text.style.StrikethroughSpan(),
                                0,
                                originalRoom.length,
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            spannableString.setSpan(
                                android.text.style.ForegroundColorSpan(getThemeColor(R.attr.successTextColor)),
                                originalRoom.length + 1,
                                spannableString.length,
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            text = spannableString
                            setTextColor(getThemeColor(R.attr.tableTextColor))
                        } else {
                            text = originalRoom
                            setTextColor(getThemeColor(R.attr.tableTextColor))
                        }
                    }
                    isRoomChanged -> {
                        val regex = Regex("Findet in Raum (.*) statt")
                        val matchResult = regex.find(artTextRoom)
                        if (matchResult != null) {
                            val newRoom = matchResult.groups[1]?.value ?: ""
                            displayRoom = newRoom
                            val spannableString = android.text.SpannableString("$originalRoom\n$displayRoom")
                            spannableString.setSpan(
                                android.text.style.StrikethroughSpan(),
                                0,
                                originalRoom.length,
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            spannableString.setSpan(
                                android.text.style.ForegroundColorSpan(getThemeColor(R.attr.successTextColor)),
                                originalRoom.length + 1,
                                spannableString.length,
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            text = spannableString
                            setTextColor(getThemeColor(R.attr.tableTextColor))
                        } else {
                            text = displayRoom
                            setTextColor(getThemeColor(R.attr.tableTextColor))
                        }
                    }
                    else -> {
                        text = displayRoom
                        setTextColor(getThemeColor(R.attr.tableTextColor))
                    }
                }
            }
            row.addView(raumCell)

            // art column
            val originalText = entry.optString("text", "")
            val displayText = formatArtText(originalText)
            val artCell = TextView(requireContext()).apply {
                text = displayText
                textSize = 12f
                setTextColor(getThemeColor(R.attr.tableTextColor))
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

    private fun TextView.handleOriginalSubjectLogic(fachText: String, isCancelled: Boolean) {
        if (isStudentSubject(fachText)) {
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getThemeColor(R.attr.highlightTextColor))
            background = createReducedHeightBackground(getThemeColor(R.attr.highlightBackgroundColor))
            setPadding(12, 12, 12, 12)

            if (isCancelled) {
                val spannableString = android.text.SpannableString(fachText)
                spannableString.setSpan(
                    android.text.style.StrikethroughSpan(),
                    0,
                    fachText.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                text = spannableString
            } else {
                text = fachText
            }
        } else {
            setPadding(8, 12, 12, 12)
            setTextColor(getThemeColor(R.attr.tableTextColor))

            if (isCancelled) {
                val spannableString = android.text.SpannableString(fachText)
                spannableString.setSpan(
                    android.text.style.StrikethroughSpan(),
                    0,
                    fachText.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                text = spannableString
            } else {
                text = fachText
            }
        }
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
            originalText == "Auf einen anderen Termin verlegt" -> getString(R.string.home_substitution_moved)

            // "Sp-3 entfällt, stattdessen De-3 in Raum A403"
            originalText.matches(Regex(".*entfällt, stattdessen .* in Raum .*")) -> {
                val regex = Regex(".*entfällt, stattdessen (.*) in Raum (.*)")
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

            // "IT-3 entfällt, stattdessen PI-3"
            originalText.matches(Regex(".*entfällt, stattdessen .*")) -> {
                val regex = Regex("(.*) entfällt, stattdessen (.*)")
                val matchResult = regex.find(originalText)
                if (matchResult != null) {
                    val originalSubject = matchResult.groups[1]?.value ?: ""
                    val newSubject = matchResult.groups[2]?.value ?: ""
                    getString(R.string.home_substitution_subject_replaced, newSubject, originalSubject)
                } else {
                    originalText
                }
            }

            // "Findet in Raum A408 statt"
            originalText.matches(Regex("Findet in Raum .* statt")) -> {
                val regex = Regex("Findet in Raum (.*) statt")
                val matchResult = regex.find(originalText)
                if (matchResult != null) {
                    val newRoom = matchResult.groups[1]?.value ?: ""
                    getString(R.string.home_room_changed, newRoom)
                } else {
                    originalText
                }
            }

            // hard coded cases
            originalText == "Entfällt wegen Exkursion, Praktikum oder Veranstaltung" -> getString(R.string.home_substitution_canceled_event)
            originalText == "Entfällt" -> getString(R.string.home_is_cancelled)
            originalText == "Wird vertreten" -> getString(R.string.home_is_substituted)
            originalText == "Wird betreut" -> getString(R.string.home_is_supervised)

            else -> originalText
        }
    }

    private fun isStudentSubject(fach: String): Boolean {
        return studentSubjects.any { subject ->
            fach.equals(subject, ignoreCase = true)
        }
    }

    private fun showError(message: String) {
        try {
            contentLayout.let { layout ->
                layout.removeAllViews()
                val errorText = TextView(requireContext()).apply {
                    text = message
                    gravity = android.view.Gravity.CENTER
                    textSize = 16f
                    setTextColor(getThemeColor(R.attr.errorTextColor))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.MATCH_PARENT
                    ).apply {
                        gravity = android.view.Gravity.CENTER
                    }
                }
                layout.addView(errorText)
            }
        } catch (e: Exception) {
            L.e("HomeFragment", "Error showing error message", e)
        }
    }

    private fun loadFilterSetting() {
        try {
            filterOnlyMySubjects = sharedPreferences.getBoolean("filter_only_my_subjects", false)
            L.d("HomeFragment", "Filter setting loaded: $filterOnlyMySubjects")
        } catch (e: Exception) {
            L.e("HomeFragment", "Error loading filter setting", e)
        }
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
        try {
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
        } catch (e: Exception) {
            L.e("HomeFragment", "Error reorganizing header layout", e)
        }
    }

    private fun setupPullToRefresh() {
        var initialY = 0f
        var isPulling = false
        var hasTriggeredRefresh = false
        var initialScrollY = 0
        var hasScrolledUp = false
        val maxPullDistance = 400f
        val triggerDistance = 300f
        val minPullThreshold = 80f

        contentScrollView.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    initialY = event.rawY
                    initialScrollY = contentScrollView.scrollY
                    isPulling = false
                    hasTriggeredRefresh = false
                    hasScrolledUp = false

                    if (contentScrollView.scrollY == 0) {
                        cleanupRefreshIndicator()
                        createRefreshIndicator()
                    }
                    false
                }

                android.view.MotionEvent.ACTION_MOVE -> {
                    val currentY = event.rawY
                    val deltaY = currentY - initialY
                    val currentScrollY = contentScrollView.scrollY

                    if (currentScrollY > initialScrollY) {
                        hasScrolledUp = true
                    }

                    // pull to refresh requirements:
                    // at the top of the page, not scrolled downward (safety), pull distance fulfilled
                    if (contentScrollView.scrollY == 0 &&
                        !hasScrolledUp &&
                        refreshContainer != null &&
                        !hasTriggeredRefresh &&
                        deltaY > minPullThreshold) {

                        isPulling = true
                        val constrainedDeltaY = deltaY.coerceAtMost(maxPullDistance)
                        updateRefreshIndicator(constrainedDeltaY, maxPullDistance, triggerDistance)
                        true
                    } else {
                        if (isPulling && (hasScrolledUp || deltaY <= minPullThreshold)) {
                            isPulling = false
                            animateRefreshContainerAway()
                        }
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
                    hasScrolledUp = false
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
                    icon.setColorFilter(getThemeColor(R.attr.settingsColorPrimary))
                    // val vibrator = context?.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                    // vibrator?.vibrate(50)
                } else {
                    icon.setColorFilter(getThemeColor(R.attr.textSecondaryColor))
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

        L.d("HomeFragment", "Pull-to-refresh triggered - manual refresh")

        scope?.launch {
            try {
                if (!hasBasicConnectivity()) {
                    L.d("HomeFragment", "No connectivity detected - loading cached data instead of network refresh")
                    val klasse = sharedPreferences.getString("selected_klasse", getString(R.string.home_not_selected))
                    if (klasse != getString(R.string.home_not_selected)) {
                        withContext(Dispatchers.Main) {
                            loadCachedSubstitutePlan(klasse!!)
                            showOfflineMessageIfNeeded(isManualRefresh = true)
                        }
                    }
                } else {
                    setLastLoadTime(0)
                    hasShownOfflineMessage = false

                    loadSubstitutePlan()
                    setLastLoadTime(System.currentTimeMillis())
                    setFirstAppLoadCompleted()
                }

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
            refreshIcon?.clearAnimation()

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
        try {
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
        } catch (e: Exception) {
            L.e("HomeFragment", "Error in cleanupRefreshIndicator", e)
        }
    }

    private fun debugHighlightData(subject: String, lesson: Int, lessonEnd: Int, dateString: String, type: String, room: String) {
        L.d("HomeFragment", "=== HIGHLIGHT DEBUG ===")
        L.d("HomeFragment", "Target: subject='$subject', lesson=$lesson-$lessonEnd, date='$dateString', type='$type', room='$room'")

        currentJsonData?.let { jsonData ->
            val dates = jsonData.optJSONArray("dates")
            if (dates != null) {
                L.d("HomeFragment", "Available dates: ${dates.length()}")
                for (i in 0 until dates.length()) {
                    val dateObj = dates.getJSONObject(i)
                    val availableDate = dateObj.getString("date")
                    val entries = dateObj.getJSONArray("entries")
                    L.d("HomeFragment", "Date '$availableDate' has ${entries.length()} entries:")

                    for (j in 0 until entries.length()) {
                        val entry = entries.getJSONObject(j)
                        val entrySubject = entry.optString("fach", "")
                        val entryLesson = entry.getInt("stunde")
                        val entryLessonEnd = entry.optInt("stundebis", entryLesson)
                        val entryType = entry.optString("text", "")
                        val entryRoom = entry.optString("raum", "")

                        val willShow = shouldShowEntry(entry)

                        L.d("HomeFragment", "  [$j] subject='$entrySubject', lesson=$entryLesson-$entryLessonEnd, type='$entryType', room='$entryRoom', willShow=$willShow")
                    }
                }
            }
        }
        L.d("HomeFragment", "=== END DEBUG ===")
    }

    private fun highlightAndShowSubstitute(subject: String, lesson: Int, lessonEnd: Int, dateString: String, type: String, room: String) {
        try {
            L.d("HomeFragment", "Starting substitute highlight: subject=$subject, lesson=$lesson-$lessonEnd, date=$dateString, type=$type, room=$room")

            if (currentJsonData == null) {
                L.d("HomeFragment", "No JSON data yet, retrying in 500ms")
                Handler(Looper.getMainLooper()).postDelayed({
                    highlightAndShowSubstitute(subject, lesson, lessonEnd, dateString, type, room)
                }, 500)
                return
            }

            debugHighlightData(subject, lesson, lessonEnd, dateString, type, room)

            Handler(Looper.getMainLooper()).postDelayed({
                val targetEntry = findSubstituteEntry(subject, lesson, lessonEnd, dateString, type, room)
                if (targetEntry == null) {
                    L.w("HomeFragment", "Substitute entry not found - check debug output above")
                    Toast.makeText(requireContext(), "Entry not found: $subject $lesson-$lessonEnd on $dateString", Toast.LENGTH_LONG).show()
                    return@postDelayed
                }

                L.d("HomeFragment", "Found substitute entry, proceeding to highlight")
                scrollToAndHighlightSubstitute(targetEntry)
            }, 100)

        } catch (e: Exception) {
            L.e("HomeFragment", "Error highlighting substitute", e)
            Toast.makeText(requireContext(), getString(R.string.home_substitute_highlight_error), Toast.LENGTH_SHORT).show()
        }
    }

    private fun findSubstituteEntry(subject: String, lesson: Int, lessonEnd: Int, dateString: String, type: String, room: String): Pair<View, JSONObject>? {
        try {
            val dates = currentJsonData?.optJSONArray("dates") ?: return null
            var visibleDateIndex = -1

            L.d("HomeFragment", "Searching through ${dates.length()} dates")

            for (i in 0 until dates.length()) {
                val dateObj = dates.getJSONObject(i)
                val entryDateString = dateObj.getString("date")
                val entries = dateObj.getJSONArray("entries")

                val visibleEntries = mutableListOf<Pair<Int, JSONObject>>()
                for (j in 0 until entries.length()) {
                    val entry = entries.getJSONObject(j)
                    if (shouldShowEntry(entry)) {
                        visibleEntries.add(Pair(j, entry))
                    }
                }

                if (visibleEntries.isNotEmpty()) {
                    visibleDateIndex++
                    L.d("HomeFragment", "Date '$entryDateString' is visible date section $visibleDateIndex with ${visibleEntries.size} visible entries")

                    if (entryDateString == dateString) {
                        L.d("HomeFragment", "Found target date '$entryDateString' at visible section index $visibleDateIndex")

                        visibleEntries.forEachIndexed { visibleIndex, (originalIndex, entry) ->
                            L.d("HomeFragment", "Checking visible entry $visibleIndex (original $originalIndex): ${entry.optString("fach")} ${entry.getInt("stunde")}-${entry.optInt("stundebis", entry.getInt("stunde"))}")

                            if (matchesSubstituteEntry(entry, subject, lesson, lessonEnd, type, room)) {
                                L.d("HomeFragment", "MATCH FOUND at visible index $visibleIndex in visible date section $visibleDateIndex")
                                val tableView = findTableViewForEntry(visibleDateIndex, visibleIndex)
                                if (tableView != null) {
                                    L.d("HomeFragment", "Successfully found table view for entry")
                                    return Pair(tableView, entry)
                                } else {
                                    L.w("HomeFragment", "Table view not found for entry")
                                }
                            }
                        }
                        break
                    }
                } else {
                    L.d("HomeFragment", "Date '$entryDateString' has no visible entries, skipping")
                }
            }

            L.w("HomeFragment", "No matching substitute entry found")
        } catch (e: Exception) {
            L.e("HomeFragment", "Error finding substitute entry", e)
        }
        return null
    }

    private fun matchesSubstituteEntry(entry: JSONObject, subject: String, lesson: Int, lessonEnd: Int, type: String, room: String): Boolean {
        val entrySubject = entry.optString("fach", "")
        val entryLesson = entry.getInt("stunde")
        val entryLessonEnd = entry.optInt("stundebis", entryLesson)
        val entryType = entry.optString("text", "")

        val subjectMatches = entrySubject == subject
        val lessonMatches = entryLesson == lesson && entryLessonEnd == lessonEnd
        val typeMatches = entryType == type

        L.d("HomeFragment", "Detailed match check:")
        L.d("HomeFragment", "  Subject: '$entrySubject' == '$subject' -> $subjectMatches")
        L.d("HomeFragment", "  Lesson: $entryLesson-$entryLessonEnd == $lesson-$lessonEnd -> $lessonMatches")
        L.d("HomeFragment", "  Type: '$entryType' == '$type' -> $typeMatches")
        L.d("HomeFragment", "  Overall match: ${subjectMatches && lessonMatches && typeMatches}")

        return subjectMatches && lessonMatches && typeMatches
    }

    private fun findTableViewForEntry(dateIndex: Int, entryIndex: Int): View? {
        try {
            if (!::contentLayout.isInitialized || contentLayout.isEmpty()) {
                L.w("HomeFragment", "Content layout not initialized or empty")
                return null
            }

            val horizontallyCenteredWrapper = contentLayout.getChildAt(0) as? LinearLayout
            if (horizontallyCenteredWrapper == null) {
                L.w("HomeFragment", "Horizontal wrapper not found")
                return null
            }

            val contentContainer = horizontallyCenteredWrapper.getChildAt(0) as? LinearLayout
            if (contentContainer == null) {
                L.w("HomeFragment", "Content container not found")
                return null
            }

            var currentVisibleDateIndex = -1
            var foundTable: TableLayout? = null

            L.d("HomeFragment", "Searching through ${contentContainer.childCount} children for VISIBLE date index $dateIndex")

            for (i in 0 until contentContainer.childCount) {
                val child = contentContainer.getChildAt(i)

                if (child is TextView &&
                    child.typeface?.isBold == true &&
                    (child.gravity and android.view.Gravity.CENTER_HORIZONTAL) != 0) {

                    currentVisibleDateIndex++
                    L.d("HomeFragment", "Found date header at child index $i, currentVisibleDateIndex = $currentVisibleDateIndex, text = '${child.text}'")

                    if (currentVisibleDateIndex == dateIndex) {
                        L.d("HomeFragment", "This is our target date section!")
                        if (i + 1 < contentContainer.childCount) {
                            val nextChild = contentContainer.getChildAt(i + 1)
                            if (nextChild is TableLayout) {
                                foundTable = nextChild
                                L.d("HomeFragment", "Found table with ${foundTable.childCount} rows (including header)")
                                break
                            } else {
                                L.w("HomeFragment", "Next child after date header is not a TableLayout: ${nextChild.javaClass.simpleName}")
                            }
                        } else {
                            L.w("HomeFragment", "No child found after date header")
                        }
                        break
                    }
                }
            }

            if (foundTable != null) {
                // +1 to skip header row, ensure we don't go out of bounds
                val targetRowIndex = entryIndex + 1
                L.d("HomeFragment", "Looking for row at index $targetRowIndex in table with ${foundTable.childCount} rows")
                if (targetRowIndex < foundTable.childCount) {
                    val targetRow = foundTable.getChildAt(targetRowIndex)
                    L.d("HomeFragment", "Found target row at index $targetRowIndex")
                    return targetRow
                } else {
                    L.w("HomeFragment", "Target row index $targetRowIndex >= table child count ${foundTable.childCount}")
                    for (j in 0 until foundTable.childCount) {
                        val row = foundTable.getChildAt(j)
                        L.d("HomeFragment", "  Row $j: ${row.javaClass.simpleName}")
                    }
                }
            } else {
                L.w("HomeFragment", "No table found for visible date index $dateIndex")
            }

        } catch (e: Exception) {
            L.e("HomeFragment", "Error finding table view", e)
        }
        return null
    }

    private fun scrollToAndHighlightSubstitute(targetEntry: Pair<View, JSONObject>) {
        val (targetView, entry) = targetEntry

        try {
            contentScrollView.post {
                val location = IntArray(2)
                targetView.getLocationInWindow(location)
                val viewTop = location[1]

                val scrollViewLocation = IntArray(2)
                contentScrollView.getLocationInWindow(scrollViewLocation)
                val scrollViewTop = scrollViewLocation[1]

                val relativeTop = viewTop - scrollViewTop
                val scrollViewHeight = contentScrollView.height

                val targetScrollY = contentScrollView.scrollY + relativeTop - (scrollViewHeight / 2)

                contentScrollView.smoothScrollTo(0, targetScrollY.coerceAtLeast(0))

                contentScrollView.postDelayed({
                    highlightSubstituteView(targetView)
                }, 500)
            }
        } catch (e: Exception) {
            L.e("HomeFragment", "Error scrolling to substitute", e)
            highlightSubstituteView(targetView)
        }
    }

    private fun highlightSubstituteView(targetView: View) {
        try {
            val animationsDisabled = Settings.Global.getFloat(
                requireContext().contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) == 0f

            if (animationsDisabled) {
                highlightSubstituteWithoutAnimation(targetView)
            } else {
                highlightSubstituteWithAnimation(targetView)
            }

        } catch (e: Exception) {
            L.e("HomeFragment", "Error highlighting substitute view", e)
        }
    }

    private fun highlightSubstituteWithAnimation(targetView: View) {
        val originalBackground = targetView.background
        val originalBackgroundColor = if (targetView.backgroundTintList != null) {
            targetView.backgroundTintList?.defaultColor
        } else if (targetView.background is ColorDrawable) {
            (targetView.background as ColorDrawable).color
        } else null

        val highlightColor = getThemeColor(R.attr.highlightAnimationColor)
        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f, 0f, 1f, 0f, 1f, 0f)
        animator.duration = 2500

        animator.addUpdateListener { animation ->
            val animatedValue = animation.animatedValue as Float

            if (originalBackgroundColor != null) {
                val blendedColor = blendColors(originalBackgroundColor, highlightColor, animatedValue)
                targetView.setBackgroundColor(blendedColor)
            } else {
                val alpha = (animatedValue * 120).toInt()
                val color = Color.argb(
                    alpha,
                    Color.red(highlightColor),
                    Color.green(highlightColor),
                    Color.blue(highlightColor)
                )
                targetView.setBackgroundColor(color)
            }
        }

        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                targetView.background = originalBackground
            }
        })

        animator.start()
    }

    private fun highlightSubstituteWithoutAnimation(targetView: View) {
        val originalBackground = targetView.background
        val highlightColor = getThemeColor(R.attr.highlightAnimationColor)

        targetView.setBackgroundColor(highlightColor)

        Handler(Looper.getMainLooper()).postDelayed({ // restore after 2 seconds
            targetView.background = originalBackground
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

    override fun onPause() {
        super.onPause()
        cleanupRefreshIndicator()
    }

    private fun filterPastDates(jsonData: JSONObject): JSONObject {
        try {
            val filteredJson = JSONObject(jsonData.toString())
            val dates = filteredJson.optJSONArray("dates") ?: return filteredJson

            val today = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val filteredDates = org.json.JSONArray()

            for (i in 0 until dates.length()) {
                val dateObj = dates.getJSONObject(i)
                val dateString = dateObj.getString("date")

                try {
                    val date = inputFormat.parse(dateString)
                    if (date != null) {
                        val entryDate = Calendar.getInstance().apply {
                            time = date
                            set(Calendar.HOUR_OF_DAY, 0)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }

                        if (entryDate.timeInMillis >= today.timeInMillis) {
                            filteredDates.put(dateObj)
                            L.d("HomeFragment", "Keeping date: $dateString (${entryDate.time})")
                        } else {
                            L.d("HomeFragment", "Filtering out past date: $dateString (${entryDate.time})")
                        }
                    } else {
                        L.w("HomeFragment", "Could not parse date: $dateString, keeping it anyway")
                        filteredDates.put(dateObj)
                    }
                } catch (e: Exception) {
                    L.w("HomeFragment", "Error parsing date: $dateString, keeping it anyway", e)
                    filteredDates.put(dateObj)
                }
            }

            filteredJson.put("dates", filteredDates)
            L.d("HomeFragment", "Filtered past dates: ${dates.length()} -> ${filteredDates.length()}")
            return filteredJson

        } catch (e: Exception) {
            L.e("HomeFragment", "Error filtering past dates", e)
            return jsonData
        }
    }

    private fun highlightWithRetry(subject: String, lesson: Int, lessonEnd: Int, dateString: String, type: String, room: String, attempt: Int) {
        if (attempt >= 10) {
            L.w("HomeFragment", "Max highlight attempts reached")
            Toast.makeText(requireContext(), "Could not find entry after multiple attempts", Toast.LENGTH_SHORT).show()
            return
        }

        if (currentJsonData != null && ::contentLayout.isInitialized) {
            L.d("HomeFragment", "Data ready, attempting highlight (attempt ${attempt + 1})")
            highlightAndShowSubstitute(subject, lesson, lessonEnd, dateString, type, room)
        } else {
            L.d("HomeFragment", "Data not ready yet, retry in 500ms (attempt ${attempt + 1})")
            Handler(Looper.getMainLooper()).postDelayed({
                highlightWithRetry(subject, lesson, lessonEnd, dateString, type, room, attempt + 1)
            }, 500)
        }
    }
}