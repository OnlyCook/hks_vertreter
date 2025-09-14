package com.thecooker.vertretungsplaner.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
        try {
            ViewModelProvider(this)[HomeViewModel::class.java]
            _binding = FragmentHomeBinding.inflate(inflater, container, false)
            val root: View = binding.root

            if (isAdded && context != null) {
                sharedPreferences = requireActivity().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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
                }
            }

            L.d("HomeFragment", "onCreateView completed")
            return root
        } catch (e: Exception) {
            L.e("HomeFragment", "Error in onCreateView", e)
            return createErrorView(inflater, container)
        }
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

    private fun setupMinimalUI() {
        try {
            val constraintLayout = binding.root
            constraintLayout.removeAllViews()

            headerLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                setBackgroundColor(resources.getColor(android.R.color.holo_blue_dark, null))
                elevation = 8f
            }

            classText = TextView(requireContext()).apply {
                text = getString(R.string.act_set_substitution_plan)
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setTextColor(resources.getColor(android.R.color.white, null))
            }

            headerLayout.addView(classText)

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

            showMinimalMessage()

        } catch (e: Exception) {
            L.e("HomeFragment", "Error in setupMinimalUI", e)
        }
    }

    private fun showMinimalMessage() {
        contentLayout.let { layout ->
            layout.removeAllViews()
            val messageText = TextView(requireContext()).apply {
                text = getString(R.string.home_loading_minimal)
                gravity = android.view.Gravity.CENTER
                textSize = 16f
                setTextColor(resources.getColor(android.R.color.darker_gray, null))
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

        if (!isInSharedContentMode) {
            view.post {
                if (isAdded && _binding != null) {
                    L.d("HomeFragment", "Initializing substitute plan loading")
                    safeLoadSubstitutePlan()
                }
            }
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

            initializationAttempts = 0

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
                            loadStudentSubjects()
                            loadFilterSetting()
                            loadCooldownSetting()
                            loadColorBlindSettings()
                            updateTemporaryFilterButtonVisibility()
                            safeLoadSubstitutePlan()
                        } catch (e: Exception) {
                            L.e("HomeFragment", "Error during forced reinitialization", e)
                            showSafeErrorState()
                        }
                    }
                }
                return
            }

            if (!isInSharedContentMode) {
                loadStudentSubjects()
                loadFilterSetting()
                loadCooldownSetting()
                loadColorBlindSettings()
                updateTemporaryFilterButtonVisibility()

                reorganizeHeaderLayout()

                binding.root.postDelayed({
                    if (isAdded && _binding != null && isResumed) {
                        safeLoadSubstitutePlan()
                    }
                }, 100)
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
                    setTextColor(resources.getColor(android.R.color.holo_orange_dark, null))
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

            android.app.AlertDialog.Builder(requireContext())
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
        } catch (e: Exception) {
            L.e("HomeFragment", "Error showing restart dialog", e)
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
            val removeCooldown = sharedPreferences.getBoolean("remove_update_cooldown", false)
            cooldownEnabled = !removeCooldown
            L.d("HomeFragment", "Cooldown setting loaded: $cooldownEnabled")
        } catch (e: Exception) {
            L.e("HomeFragment", "Error loading cooldown setting", e)
        }
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
                "orange" -> resources.getColor(android.R.color.holo_orange_dark, null)
                else -> resources.getColor(android.R.color.transparent, null)
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
            val timeSinceLastLoad = currentTime - lastLoadTime

            val klasse = sharedPreferences.getString("selected_klasse", getString(R.string.home_not_selected))

            if (klasse == getString(R.string.home_not_selected)) {
                showError(getString(R.string.home_no_class_selected))
                return
            }

            classText.text = getString(R.string.home_class_prefix, klasse)

            if (isFirstLoad || !cooldownEnabled || timeSinceLastLoad >= loadCooldownMs) {
                L.d("HomeFragment", "Loading substitute plan")
                loadSubstitutePlan()
                lastLoadTime = currentTime
                isFirstLoad = false
            } else {
                val remainingCooldown = loadCooldownMs - timeSinceLastLoad
                L.d("HomeFragment", "Substitute plan loading skipped; cooldown remaining: ${remainingCooldown}ms")
                loadCachedSubstitutePlan(klasse!!)
            }
        } catch (e: Exception) {
            L.e("HomeFragment", "Error in loadSubstitutePlanWithCooldown", e)
            showSafeErrorState()
        }
    }

    private fun isSubjectRelevant(subject: String): Boolean {
        return studentSubjects.any { studentSubject ->
            subject.contains(studentSubject, ignoreCase = true)
        }
    }

    override fun onStart() {
        super.onStart()
        L.d("HomeFragment", "onStart called, isInitialized: $isInitialized")

        loadStudentSubjects()
        loadFilterSetting()
        loadCooldownSetting()

        if (!isInitialized && _binding != null) {
            binding.root.postDelayed({
                if (isAdded && _binding != null && !isInitialized) {
                    L.d("HomeFragment", "Late initialization in onStart")
                    loadSubstitutePlanWithCooldown()
                    isInitialized = true
                }
            }, 200)
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

    @SuppressLint("ClickableViewAccessibility")
    private fun setupUI() {
        val constraintLayout = binding.root
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
            text = getString(R.string.home_refresh)
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
        try {
            colorBlindMode = sharedPreferences.getString("colorblind_mode", "none") ?: "none"
            L.d("HomeFragment", "Colorblind mode loaded: $colorBlindMode")
        } catch (e: Exception) {
            L.e("HomeFragment", "Error loading colorblind settings", e)
        }
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

        val klasse = sharedPreferences.getString("selected_klasse", getString(R.string.home_not_selected))

        if (klasse == getString(R.string.home_not_selected)) {
            showError(getString(R.string.home_no_class_selected))
            return
        }

        classText.text = getString(R.string.home_class_prefix, klasse)

        if (!isNetworkAvailable()) {
            L.d("HomeFragment", "No internet connection, loading cached data")
            loadCachedSubstitutePlan(klasse!!)
            view?.let {
                Snackbar.make(it, getString(R.string.home_offline_mode), Snackbar.LENGTH_SHORT).show()
            }
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
                        text = getString(R.string.home_loading_plan)
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
                }

                val lastUpdate = withContext(Dispatchers.IO) {
                    fetchLastUpdateTime()
                }

                if (!isAdded || _binding == null) {
                    L.w("HomeFragment", "Fragment detached during network call, aborting")
                    return@launch
                }

                val substitutePlan = withContext(Dispatchers.IO) {
                    fetchSubstitutePlan(klasse!!)
                }

                if (!isAdded || _binding == null) {
                    L.w("HomeFragment", "Fragment detached after network call, aborting UI update")
                    return@launch
                }

                saveSubstitutePlanToCache(klasse, substitutePlan.toString(), lastUpdate)

                if (::lastUpdateText.isInitialized) {
                    lastUpdateText.text = getString(R.string.home_last_update, lastUpdate)
                }
                displaySubstitutePlan(substitutePlan)

                L.d("HomeFragment", "Successfully loaded substitute plan")

            } catch (e: Exception) {
                L.e("HomeFragment", "Error loading substitute plan", e)

                if (!isAdded || _binding == null) {
                    L.w("HomeFragment", "Fragment detached, skipping error handling")
                    return@launch
                }

                L.d("HomeFragment", "Network error, attempting to load cached data")
                loadCachedSubstitutePlan(klasse!!)

                view?.let {
                    Snackbar.make(it, getString(R.string.home_network_error_using_cache), Snackbar.LENGTH_LONG).show()
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

            val jsonData = JSONObject(cachedData)
            if (::lastUpdateText.isInitialized) {
                lastUpdateText.text = getString(R.string.home_last_update_offline, lastUpdate)
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

    private fun fetchLastUpdateTime(): String {
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
            val originalText = matchResult?.groups?.get(1)?.value ?: "Unbekannt"

            translateWeekdayInText(originalText) // translate weekday abbreviations
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
                text = getString(R.string.home_no_entries_all)
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
                getString(R.string.home_no_entries_filtered)
            } else {
                getString(R.string.home_no_entries_all)
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
                    isTemporaryFilterDisabled -> getString(R.string.home_filter_temp_disabled, totalEntries)
                    totalEntries > filteredEntries -> getString(R.string.home_filter_active, filteredEntries, totalEntries)
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

        val headers = arrayOf(getString(R.string.home_table_lesson), getString(R.string.home_table_subject), getString(R.string.home_table_room), getString(R.string.home_table_type))
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
                getString(R.string.home_lesson_duration_multiple, stunde, stundeBis, duration)
            } else {
                getString(R.string.home_lesson_duration, stunde)
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
            val artTextSubject = entry.optString("text", "")
            val isCancelled = artTextSubject.contains("Entfällt") || artTextSubject == "Auf einen anderen Termin verlegt"

            val isSubjectReplaced = artTextSubject.matches(Regex(".*entfällt, stattdessen .*"))
            val isSubjectReplacedWithRoom = artTextSubject.matches(Regex(".*entfällt, stattdessen .* in Raum .*"))

            val fachCell = TextView(requireContext()).apply {
                textSize = 14f
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
                                android.text.style.ForegroundColorSpan(resources.getColor(android.R.color.holo_green_dark, null)),
                                originalSubject.length + 1,
                                spannableString.length,
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            text = spannableString

                            if (isStudentSubject(originalSubject) || isStudentSubject(newSubject)) {
                                setTypeface(null, android.graphics.Typeface.BOLD)
                                setTextColor(resources.getColor(android.R.color.white, null))
                                background = createReducedHeightBackground(resources.getColor(android.R.color.holo_blue_dark, null))
                                setPadding(12, 12, 12, 12)
                            } else {
                                setPadding(8, 12, 12, 12)
                                setTextColor(resources.getColor(android.R.color.black, null))
                            }
                        } else {
                            handleOriginalSubjectLogic(fachText, isCancelled) // fallback
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
                                android.text.style.ForegroundColorSpan(resources.getColor(android.R.color.holo_green_dark, null)),
                                originalSubject.length + 1,
                                spannableString.length,
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            text = spannableString

                            if (isStudentSubject(originalSubject) || isStudentSubject(newSubject)) {
                                setTypeface(null, android.graphics.Typeface.BOLD)
                                setTextColor(resources.getColor(android.R.color.white, null))
                                background = createReducedHeightBackground(resources.getColor(android.R.color.holo_blue_dark, null))
                                setPadding(12, 12, 12, 12)
                            } else {
                                setPadding(8, 12, 12, 12)
                                setTextColor(resources.getColor(android.R.color.black, null))
                            }
                        } else {
                            handleOriginalSubjectLogic(fachText, isCancelled) // fallback
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
                                android.text.style.ForegroundColorSpan(resources.getColor(android.R.color.holo_green_dark, null)),
                                originalRoom.length + 1,
                                spannableString.length,
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            text = spannableString
                            setTextColor(resources.getColor(android.R.color.black, null))
                        } else {
                            text = originalRoom
                            setTextColor(resources.getColor(android.R.color.black, null))
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
                                android.text.style.ForegroundColorSpan(resources.getColor(android.R.color.holo_green_dark, null)),
                                originalRoom.length + 1,
                                spannableString.length,
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            text = spannableString
                            setTextColor(resources.getColor(android.R.color.black, null))
                        } else {
                            text = displayRoom
                            setTextColor(resources.getColor(android.R.color.black, null))
                        }
                    }
                    else -> {
                        text = displayRoom
                        setTextColor(resources.getColor(android.R.color.black, null))
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

    private fun TextView.handleOriginalSubjectLogic(fachText: String, isCancelled: Boolean) {
        if (isStudentSubject(fachText)) {
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(resources.getColor(android.R.color.white, null))
            background = createReducedHeightBackground(resources.getColor(android.R.color.holo_blue_dark, null))
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
            setTextColor(resources.getColor(android.R.color.black, null))

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
                    setTextColor(resources.getColor(android.R.color.holo_red_dark, null))
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

    private fun showNoInternetMessage() {
        if (!::contentLayout.isInitialized || !isAdded) {
            L.w("HomeFragment", "cannot show no internet message, fragment not properly initialized")
            return
        }

        contentLayout.removeAllViews()
        val noInternetText = TextView(requireContext()).apply {
            text = getString(R.string.home_no_internet)
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

    override fun onPause() {
        super.onPause()
        cleanupRefreshIndicator()
    }
}