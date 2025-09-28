package com.thecooker.vertretungsplaner.ui.moodle

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.thecooker.vertretungsplaner.R
import com.thecooker.vertretungsplaner.BuildConfig
import androidx.core.content.edit
import com.thecooker.vertretungsplaner.L
import java.io.File
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.TypedValue
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import java.net.URLDecoder
import androidx.core.net.toUri
import com.thecooker.vertretungsplaner.data.CalendarDataManager
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

class MoodleFragment : Fragment() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var searchBarMoodle: EditText
    private lateinit var searchLayout: LinearLayout
    private lateinit var extendedHeaderLayout: LinearLayout
    private lateinit var urlBar: EditText
    private lateinit var btnSearch: ImageButton
    private lateinit var btnDashboard: ImageButton
    private lateinit var btnNotifications: ImageButton
    private lateinit var btnMessages: ImageButton
    private lateinit var btnMenu: Button
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnClearSearch: ImageButton
    private lateinit var btnSubmitSearch: ImageButton
    private lateinit var spinnerSearchType: Spinner
    private lateinit var tvNotificationCount: TextView
    private lateinit var tvMessageCount: TextView

    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var encryptedPrefs: SharedPreferences

    private var isPageFullyLoaded = false

    private val moodleBaseUrl = "https://moodle.kleyer.eu"
    private val loginUrl = "$moodleBaseUrl/login/index.php"
    private var isLoginDialogShown = false
    private var hasLoginFailed = false
    private var loginRetryCount = 0
    private var lastSuccessfulLoginCheck = 0L
    private var loginSuccessConfirmed = false

    private var searchBarVisible = false
    private var isAtTop = true
    private var scrollThreshold = 50 // pixels to scroll before hiding header
    private var isUserScrolling = false
    private var lastScrollCheckTime = 0L

    private var backPressTime = 0L
    private var backPressCount = 0

    // file picker
    private lateinit var btnOpenInBrowser: ImageButton
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        fileUploadCallback?.onReceiveValue(uris.toTypedArray())
        fileUploadCallback = null
    }

    // calendar data refresh
    private var wasOnLoginPage = false
    private var calendarDataManager: CalendarDataManager? = null
    private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private data class CalendarEvent(
        val date: Date,
        val summary: String,
        val description: String,
        val category: String,
        val courseId: String = ""
    )

    // search dialog
    private var searchProgressDialog: AlertDialog? = null
    private var searchCancelled = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        arguments?.getString("moodle_search_category")?.let { category ->
            val summary = arguments?.getString("moodle_search_summary") ?: ""
            val entryId = arguments?.getString("moodle_entry_id") ?: ""
            Handler(Looper.getMainLooper()).postDelayed({
                searchForMoodleEntry(category, summary, entryId)
            }, 1000)

            arguments?.remove("moodle_search_category")
            arguments?.remove("moodle_search_summary")
            arguments?.remove("moodle_entry_id")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_moodle, container, false)

        initViews(root)
        setupSharedPreferences()
        calendarDataManager = CalendarDataManager.getInstance(requireContext())
        setupWebView()
        setupClickListeners()

        updateUIState()

        Handler(Looper.getMainLooper()).post {
            loadUrlInBackground(loginUrl)
        }

        return root
    }

    private fun updateUIState() {
        val currentUrl = webView.url ?: ""
        val isOnLoginPage = currentUrl == loginUrl || currentUrl.contains("login/index.php")
        val canGoBack = webView.canGoBack()
        val canGoForward = webView.canGoForward()

        btnSearch.isEnabled = !isOnLoginPage
        btnDashboard.isEnabled = !isOnLoginPage
        btnNotifications.isEnabled = !isOnLoginPage
        btnMessages.isEnabled = !isOnLoginPage
        btnMenu.isEnabled = !isOnLoginPage

        btnBack.isEnabled = canGoBack
        btnForward.isEnabled = canGoForward

        btnSearch.alpha = if (isOnLoginPage) 0.5f else 1.0f
        btnDashboard.alpha = if (isOnLoginPage) 0.5f else 1.0f
        btnNotifications.alpha = if (isOnLoginPage) 0.5f else 1.0f
        btnMessages.alpha = if (isOnLoginPage) 0.5f else 1.0f
        btnMenu.alpha = if (isOnLoginPage) 0.5f else 1.0f
        btnBack.alpha = if (canGoBack) 1.0f else 0.5f
        btnForward.alpha = if (canGoForward) 1.0f else 0.5f

        if (isOnLoginPage && searchBarVisible) {
            toggleSearchBar()
        }
    }

    private fun initViews(root: View) {
        webView = root.findViewById(R.id.webViewMoodle)
        progressBar = root.findViewById(R.id.progressBar)
        searchBarMoodle = root.findViewById(R.id.searchBarMoodle)
        searchLayout = root.findViewById(R.id.searchLayout)
        extendedHeaderLayout = root.findViewById(R.id.extendedHeaderLayout)
        urlBar = root.findViewById(R.id.urlBar)
        btnSearch = root.findViewById(R.id.btnSearch)
        btnDashboard = root.findViewById(R.id.btnDashboard)
        btnNotifications = root.findViewById(R.id.btnNotifications)
        btnMessages = root.findViewById(R.id.btnMessages)
        btnMenu = root.findViewById(R.id.btnMenu)
        btnBack = root.findViewById(R.id.btnBack)
        btnForward = root.findViewById(R.id.btnForward)
        btnClearSearch = root.findViewById(R.id.btnClearSearch)
        btnSubmitSearch = root.findViewById(R.id.btnSubmitSearch)
        btnOpenInBrowser = root.findViewById(R.id.btnOpenInBrowser)
        spinnerSearchType = root.findViewById(R.id.spinnerSearchType)
        tvNotificationCount = root.findViewById(R.id.tvNotificationCount)
        tvMessageCount = root.findViewById(R.id.tvMessageCount)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val isDebugMode = BuildConfig.DEBUG && ::sharedPrefs.isInitialized && sharedPrefs.getBoolean("moodle_debug_mode", false)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = !isDebugMode
            displayZoomControls = isDebugMode
            setSupportZoom(true)
            cacheMode = if (isDebugMode) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            allowContentAccess = true

            if (isDebugMode) {
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
                setGeolocationEnabled(true)
            }
        }

        if (isDebugMode) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()

                if (url.contains("forcedownload=1") ||
                    url.contains("pluginfile.php") ||
                    isDownloadableFile(url)) {
                    handleDownload(url)
                    return true
                }

                if (isDebugMode) {
                    view?.loadUrl(url)
                    return false
                }

                return if (url.startsWith(moodleBaseUrl)) {
                    view?.loadUrl(url)
                    false
                } else {
                    true
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                isPageFullyLoaded = true

                if (url?.contains("logout.php") == true) {
                    handleLogout()
                    return
                }

                updateUIState()
                showExtendedHeaderInitially()
                url?.let { updateUrlBar(it) }

                Handler(Looper.getMainLooper()).postDelayed({
                    updateCounters()
                }, 500)

                checkLoginTransition(url)

                if (url == loginUrl || url?.contains("login/index.php") == true) {
                    wasOnLoginPage = true
                    loginSuccessConfirmed = false

                    waitForPageReady { pageReady ->
                        if (pageReady) {
                            isConfirmDialogPage { isConfirmDialog ->
                                if (isConfirmDialog) {
                                    checkConfirmDialog()
                                } else {
                                    checkLoginFailure { loginFailed ->
                                        hasLoginFailed = loginFailed

                                        if (!isLoginDialogShown && !loginSuccessConfirmed) {
                                            checkAutoLogin()
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    isLoginDialogShown = false
                    hasLoginFailed = false

                    isUserLoggedIn { isLoggedIn ->
                        if (isLoggedIn) {
                            loginSuccessConfirmed = true
                            loginRetryCount = 0
                            lastSuccessfulLoginCheck = System.currentTimeMillis()
                        }
                    }
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                isPageFullyLoaded = false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                // potential progress handling
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val acceptTypes = fileChooserParams?.acceptTypes
                val mimeType = when {
                    acceptTypes?.isNotEmpty() == true -> acceptTypes[0]
                    else -> "*/*"
                }

                try {
                    filePickerLauncher.launch(mimeType)
                    return true
                } catch (_: Exception) {
                    fileUploadCallback = null
                    Toast.makeText(requireContext(), "Could not open file picker", Toast.LENGTH_SHORT).show()
                    return false
                }
            }
        }

        webView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isUserScrolling = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isUserScrolling = false
                    Handler(Looper.getMainLooper()).postDelayed({
                        checkExtendedHeaderVisibilityOnScrollEnd()
                    }, 50)
                }
            }
            false
        }

        webView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastScrollCheckTime < 50) return@setOnScrollChangeListener
            lastScrollCheckTime = currentTime

            if (scrollY > scrollThreshold && isAtTop) {
                isAtTop = false
                updateExtendedHeaderVisibility()
            }
        }
    }

    private fun checkLoginTransition(currentUrl: String?) {
        val isCurrentlyOnLoginPage = currentUrl == loginUrl || currentUrl?.contains("login/index.php") == true

        if (wasOnLoginPage && !isCurrentlyOnLoginPage && loginSuccessConfirmed) {
            L.d("MoodleFragment", "Login transition detected - refreshing calendar data")
            refreshCalendarDataInBackground()
            wasOnLoginPage = false
        } else if (isCurrentlyOnLoginPage) {
            wasOnLoginPage = true
        }
    }

    private fun refreshCalendarDataInBackground() {
        try {
            L.d("MoodleFragment", "Starting calendar data refresh...")

            activity?.runOnUiThread {
                refreshCalendarDataViaForm()
            }
        } catch (e: Exception) {
            L.e("MoodleFragment", "Error in background calendar refresh", e)
        }
    }

    private fun waitForPageReady(maxAttempts: Int = 20, attempt: Int = 0, callback: (Boolean) -> Unit) {
        if (attempt >= maxAttempts) {
            callback(false)
            return
        }

        val jsCode = """
        (function() {
            return document.readyState === 'complete' && 
                   document.querySelector('body') !== null;
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            if (result == "true") {
                callback(true)
            } else {
                Handler(Looper.getMainLooper()).postDelayed({
                    waitForPageReady(maxAttempts, attempt + 1, callback)
                }, 200)
            }
        }
    }

    private fun checkExtendedHeaderVisibilityOnScrollEnd() {
        if (isUserScrolling) return

        val scrollY = webView.scrollY
        val newIsAtTop = scrollY < scrollThreshold

        if (newIsAtTop && !isAtTop) {
            isAtTop = true
            updateExtendedHeaderVisibility()
        }
    }

    private fun checkLoginFailure(callback: (Boolean) -> Unit) {
        checkLoginError { errorMessage ->
            callback(errorMessage != null)
        }
    }

    private fun handleLogout() {
        isLoginDialogShown = false
        hasLoginFailed = false
        loginRetryCount = 0
        loginSuccessConfirmed = false
        lastSuccessfulLoginCheck = 0L

        encryptedPrefs.edit { clear() }

        sharedPrefs.edit {
            remove("moodle_dont_show_login_dialog")
        }

        Toast.makeText(requireContext(), getString(R.string.moodle_logged_out), Toast.LENGTH_SHORT).show()
    }

    private fun showExtendedHeaderInitially() {
        isAtTop = true
        updateExtendedHeaderVisibility()
    }

    private fun setupClickListeners() {
        btnSearch.setOnClickListener {
            toggleSearchBar()
        }

        btnDashboard.setOnClickListener {
            loadUrlInBackground("$moodleBaseUrl/my/")
        }

        btnNotifications.setOnClickListener {
            loadUrlInBackground("$moodleBaseUrl/message/output/popup/notifications.php")
        }

        btnMessages.setOnClickListener {
            loadUrlInBackground("$moodleBaseUrl/message/index.php")
        }

        btnMenu.setOnClickListener {
            showMenuPopup()
        }

        btnBack.setOnClickListener {
            if (webView.canGoBack()) {
                webView.goBack()
            }
        }

        btnForward.setOnClickListener {
            if (webView.canGoForward()) {
                webView.goForward()
            }
        }

        btnClearSearch.setOnClickListener {
            searchBarMoodle.setText("")
            btnClearSearch.visibility = View.GONE
        }

        btnSubmitSearch.setOnClickListener {
            performSearch()
        }

        searchBarMoodle.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                btnClearSearch.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
        })

        searchBarMoodle.setOnEditorActionListener { _, _, _ ->
            performSearch()
            true
        }

        urlBar.setOnEditorActionListener { _, _, _ ->
            val url = urlBar.text.toString().trim()
            if (url.isNotEmpty()) {
                navigateToUrl(url)
            }
            true
        }

        btnOpenInBrowser.setOnClickListener {
            val currentUrl = webView.url
            if (!currentUrl.isNullOrBlank()) {
                openInExternalBrowser(currentUrl)
            } else {
                Toast.makeText(requireContext(), "No URL to open", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupSharedPreferences() {
        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        val masterKey = MasterKey.Builder(requireContext())
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        encryptedPrefs = EncryptedSharedPreferences.create(
            requireContext(),
            "moodle_credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun toggleSearchBar() {
        searchBarVisible = !searchBarVisible

        val animationsEnabled = android.provider.Settings.Global.getFloat(
            requireContext().contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f
        ) != 0.0f

        if (animationsEnabled) {
            if (searchBarVisible) {
                searchLayout.visibility = View.VISIBLE

                searchLayout.measure(
                    View.MeasureSpec.makeMeasureSpec(searchLayout.parent.let { (it as View).width }, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                val targetHeight = searchLayout.measuredHeight

                val layoutParams = searchLayout.layoutParams
                layoutParams.height = 0
                searchLayout.layoutParams = layoutParams

                android.animation.ValueAnimator.ofInt(0, targetHeight).apply {
                    duration = 250
                    interpolator = android.view.animation.DecelerateInterpolator()

                    addUpdateListener { animator ->
                        val animatedHeight = animator.animatedValue as Int
                        layoutParams.height = animatedHeight
                        searchLayout.layoutParams = layoutParams
                    }

                    doOnEnd {
                        layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        searchLayout.layoutParams = layoutParams
                    }

                    start()
                }

                searchBarMoodle.requestFocus()
            } else {
                val currentHeight = searchLayout.height
                val layoutParams = searchLayout.layoutParams

                android.animation.ValueAnimator.ofInt(currentHeight, 0).apply {
                    duration = 200
                    interpolator = android.view.animation.AccelerateInterpolator()

                    addUpdateListener { animator ->
                        val animatedHeight = animator.animatedValue as Int
                        layoutParams.height = animatedHeight
                        searchLayout.layoutParams = layoutParams
                    }

                    doOnEnd {
                        searchLayout.visibility = View.GONE
                        layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        searchLayout.layoutParams = layoutParams
                    }

                    start()
                }

                searchBarMoodle.clearFocus()
                searchBarMoodle.setText("")
            }
        } else {
            if (searchBarVisible) {
                searchLayout.visibility = View.VISIBLE
                searchBarMoodle.requestFocus()
            } else {
                searchLayout.visibility = View.GONE
                searchBarMoodle.clearFocus()
                searchBarMoodle.setText("")
            }

            val layoutParams = searchLayout.layoutParams
            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            searchLayout.layoutParams = layoutParams

            searchLayout.requestLayout()

            updateExtendedHeaderVisibility()
        }

        if (animationsEnabled) {
            Handler(Looper.getMainLooper()).postDelayed({
                updateExtendedHeaderVisibility()
            }, 50)
        }
    }

    private fun updateExtendedHeaderVisibility() {
        val shouldShow = isAtTop && !searchBarVisible
        val currentVisibility = extendedHeaderLayout.visibility

        if ((shouldShow && currentVisibility == View.VISIBLE) ||
            (!shouldShow && currentVisibility == View.GONE)) {
            return
        }

        val animationsEnabled = android.provider.Settings.Global.getFloat(
            requireContext().contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f
        ) != 0.0f

        if (animationsEnabled) {
            if (shouldShow) {
                extendedHeaderLayout.visibility = View.VISIBLE

                extendedHeaderLayout.measure(
                    View.MeasureSpec.makeMeasureSpec(extendedHeaderLayout.parent.let { (it as View).width }, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                val targetHeight = extendedHeaderLayout.measuredHeight

                val layoutParams = extendedHeaderLayout.layoutParams
                layoutParams.height = 0
                extendedHeaderLayout.layoutParams = layoutParams

                android.animation.ValueAnimator.ofInt(0, targetHeight).apply {
                    duration = 250
                    interpolator = android.view.animation.DecelerateInterpolator()

                    addUpdateListener { animator ->
                        val animatedHeight = animator.animatedValue as Int
                        layoutParams.height = animatedHeight
                        extendedHeaderLayout.layoutParams = layoutParams
                    }

                    doOnEnd {
                        layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        extendedHeaderLayout.layoutParams = layoutParams
                    }

                    start()
                }

            } else {
                val currentHeight = extendedHeaderLayout.height
                val layoutParams = extendedHeaderLayout.layoutParams

                android.animation.ValueAnimator.ofInt(currentHeight, 0).apply {
                    duration = 200
                    interpolator = android.view.animation.AccelerateInterpolator()

                    addUpdateListener { animator ->
                        val animatedHeight = animator.animatedValue as Int
                        layoutParams.height = animatedHeight
                        extendedHeaderLayout.layoutParams = layoutParams
                    }

                    doOnEnd {
                        extendedHeaderLayout.visibility = View.GONE
                        layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        extendedHeaderLayout.layoutParams = layoutParams
                    }

                    start()
                }
            }
        } else {
            extendedHeaderLayout.visibility = if (shouldShow) View.VISIBLE else View.GONE
            val layoutParams = extendedHeaderLayout.layoutParams
            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            extendedHeaderLayout.layoutParams = layoutParams

            extendedHeaderLayout.requestLayout()
        }
    }

    private inline fun android.animation.ValueAnimator.doOnEnd(crossinline action: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                action()
            }
        })
    }

    private fun updateUrlBar(url: String) {
        val displayUrl = url.removePrefix(moodleBaseUrl).removePrefix("/")
        urlBar.setText(displayUrl)
    }

    private fun navigateToUrl(userInput: String) {
        val isDebugMode = BuildConfig.DEBUG && sharedPrefs.getBoolean("moodle_debug_mode", false)

        val url = when {
            isDebugMode && (userInput.startsWith("https://") || userInput.startsWith("http://")) -> {
                userInput
            }
            userInput.startsWith(moodleBaseUrl) -> {
                userInput
            }
            userInput.startsWith("https://moodle.kleyer.eu/") -> {
                userInput
            }
            else -> {
                "$moodleBaseUrl/$userInput"
            }
        }

        val isValidUrl = if (isDebugMode) {
            url.startsWith("https://") || url.startsWith("http://")
        } else {
            url.startsWith(moodleBaseUrl)
        }

        if (isValidUrl) {
            loadUrlInBackground(url)
        } else {
            val errorMessage = if (isDebugMode) {
                "Invalid URL format"
            } else {
                getString(R.string.moodle_invalid_url)
            }
            Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadUrlInBackground(url: String) {
        progressBar.visibility = View.VISIBLE

        Thread {
            activity?.runOnUiThread {
                webView.loadUrl(url)
            }
        }.start()
    }

    private fun performSearch() {
        val query = searchBarMoodle.text.toString().trim()
        if (query.isEmpty()) return

        val searchType = spinnerSearchType.selectedItemPosition
        val searchUrl = when (searchType) {
            0 -> "$moodleBaseUrl/my/courses.php" // My courses
            1 -> "$moodleBaseUrl/course/index.php" // All courses
            else -> "$moodleBaseUrl/my/courses.php"
        }

        loadUrlInBackground(searchUrl)

        waitForSearchElementsReady(searchType) {
            injectSearchQuery(query, searchType)
        }
    }

    private fun waitForSearchElementsReady(searchType: Int, maxAttempts: Int = 20, attempt: Int = 0, callback: () -> Unit) {
        if (attempt >= maxAttempts) {
            callback()
            return
        }

        val searchFieldXpath = when (searchType) {
            0 -> "/html/body/div[1]/div[2]/div/div[1]/div/div/section/section/div/div/div[1]/div[1]/div/div[2]/div/div/input"
            1 -> "/html/body/div[1]/div[2]/div/div[1]/div/div/div[1]/div/div[2]/div/form/div/input"
            else -> "/html/body/div[1]/div[2]/div/div[1]/div/div/section/section/div/div/div[1]/div[1]/div/div[2]/div/div/input"
        }

        val jsCode = """
        (function() {
            var searchField = document.evaluate('$searchFieldXpath', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
            return searchField !== null && searchField.offsetParent !== null;
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            if (result == "true") {
                Handler(Looper.getMainLooper()).postDelayed({
                    callback()
                }, 500)
            } else {
                Handler(Looper.getMainLooper()).postDelayed({
                    waitForSearchElementsReady(searchType, maxAttempts, attempt + 1, callback)
                }, 300)
            }
        }
    }

    private fun waitForPageLoadComplete(maxAttempts: Int = 15, attempt: Int = 0, callback: () -> Unit) {
        if (attempt >= maxAttempts) {
            callback()
            return
        }

        val jsCode = """
        (function() {
            return document.readyState === 'complete' && 
                   document.querySelector('input') !== null;
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            if (result == "true") {
                Handler(Looper.getMainLooper()).postDelayed({
                    callback()
                }, 300)
            } else {
                Handler(Looper.getMainLooper()).postDelayed({
                    waitForPageLoadComplete(maxAttempts, attempt + 1, callback)
                }, 250)
            }
        }
    }

    private fun injectSearchQuery(query: String, searchType: Int) {
        val searchFieldXpath = when (searchType) {
            0 -> "/html/body/div[1]/div[2]/div/div[1]/div/div/section/section/div/div/div[1]/div[1]/div/div[2]/div/div/input"
            1 -> "/html/body/div[1]/div[2]/div/div[1]/div/div/div[1]/div/div[2]/div/form/div/input"
            else -> "/html/body/div[1]/div[2]/div/div[1]/div/div/section/section/div/div/div[1]/div[1]/div/div[2]/div/div/input"
        }

        val submitButtonXpath = "/html/body/div[1]/div[2]/div/div[1]/div/div/div[1]/div/div[2]/div/form/div/button"

        val jsCode = """
        (function() {
            var searchField = document.evaluate('$searchFieldXpath', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
            if (searchField) {
                searchField.value = '$query';
                searchField.dispatchEvent(new Event('input', { bubbles: true }));

                if ($searchType === 1) {
                    var submitButton = document.evaluate('$submitButtonXpath', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
                    if (submitButton) {
                        setTimeout(function() {
                            submitButton.click();
                        }, 100);
                    }
                } else {
                    searchField.dispatchEvent(new KeyboardEvent('keypress', { key: 'Enter', bubbles: true }));
                }
                return true;
            }
            return false;
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            if (result == "false") {
                Toast.makeText(requireContext(), getString(R.string.moodle_search_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkConfirmDialog() {
        val shouldAutoDismiss = sharedPrefs.getBoolean("moodle_auto_dismiss_confirm", true)

        if (!shouldAutoDismiss) {
            isLoginDialogShown = true
            return
        }

        waitForPageReady { pageReady ->
            if (pageReady) {
                val jsCode = """
            (function() {
                try {
                    var confirmHeader = document.evaluate('/html/body/div[2]/div[2]/div/div/div/div/div/div/div/div/div/div[1]/h4', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
                    if (confirmHeader && (confirmHeader.textContent === 'Bestätigen' || confirmHeader.textContent === 'Confirm')) {
                        var cancelButton = document.evaluate('/html/body/div[2]/div[2]/div/div/div/div/div/div/div/div/div/div[3]/div/div[1]/form/button', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
                        if (cancelButton) {
                            cancelButton.click();
                            return true;
                        }
                    }
                    return false;
                } catch(e) {
                    return false;
                }
            })();
        """.trimIndent()

                webView.evaluateJavascript(jsCode) { result ->
                    if (result == "true") {
                        isLoginDialogShown = true
                        Toast.makeText(requireContext(), "Session termination cancelled", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun isUserLoggedIn(callback: (Boolean) -> Unit) {
        val jsCode = """
        (function() {
            var logoutLink = document.querySelector('a[href*="logout"]');
            if (logoutLink) return true;

            var userMenu = document.querySelector('.usermenu, .user-menu, [data-userid]');
            if (userMenu) return true;

            var dashboardElements = document.querySelectorAll('[data-region="drawer"], .dashboard, #page-my-index');
            if (dashboardElements.length > 0) return true;

            var loginForm = document.querySelector('form[action*="login"], input[name="username"]');
            var currentPath = window.location.pathname;
            
            if (!loginForm && (
                currentPath.includes('/my/') || 
                currentPath.includes('/course/') || 
                currentPath.includes('/grade/') ||
                currentPath.includes('/message/') ||
                currentPath.includes('/user/profile')
            )) {
                return true;
            }

            if (loginForm || currentPath.includes('/login/')) {
                return false;
            }

            return false;
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            val isLoggedIn = result == "true"

            if (isLoggedIn) {
                lastSuccessfulLoginCheck = System.currentTimeMillis()
                loginSuccessConfirmed = true
            }

            callback(isLoggedIn)
        }
    }

    private fun checkAutoLogin() {
        isConfirmDialogPage { isConfirmDialog ->
            if (isConfirmDialog) {
                return@isConfirmDialogPage
            }

            val timeSinceLastCheck = System.currentTimeMillis() - lastSuccessfulLoginCheck
            if (loginSuccessConfirmed && timeSinceLastCheck < 30000) {
                return@isConfirmDialogPage
            }

            isUserLoggedIn { isLoggedIn ->
                if (isLoggedIn) {
                    loginRetryCount = 0
                    loginSuccessConfirmed = true
                    lastSuccessfulLoginCheck = System.currentTimeMillis()
                    return@isUserLoggedIn
                }

                loginSuccessConfirmed = false

                val dontShowDialog = sharedPrefs.getBoolean("moodle_dont_show_login_dialog", false)
                val hasCredentials = encryptedPrefs.contains("moodle_username") &&
                        encryptedPrefs.contains("moodle_password")

                if (hasCredentials && !dontShowDialog && !isLoginDialogShown) {
                    performAutoLogin()
                } else if (!hasCredentials && !dontShowDialog && !isLoginDialogShown) {
                    val delay = if (loginRetryCount > 0) {
                        loginRetryCount * 400L
                    } else {
                        0L
                    }

                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!loginSuccessConfirmed && !isLoginDialogShown) {
                            showLoginDialog()
                        }
                    }, delay)
                }
            }
        }
    }

    private fun isConfirmDialogPage(callback: (Boolean) -> Unit) {
        val jsCode = """
        (function() {
            try {
                var confirmHeader = document.evaluate('/html/body/div[2]/div[2]/div/div/div/div/div/div/div/div/div/div[1]/h4', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
                if (confirmHeader && (confirmHeader.textContent === 'Bestätigen' || confirmHeader.textContent === 'Confirm')) {
                    return true;
                }
                return false;
            } catch(e) {
                return false;
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            callback(result == "true")
        }
    }

    private fun extractUsernameFromForm(callback: (String?) -> Unit) {
        val jsCode = """
        (function() {
            try {
                var usernameField = document.evaluate('/html/body/div[2]/div[2]/div/div/div/div/div/div/div/form/div[1]/input', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
                if (usernameField && usernameField.value) {
                    return usernameField.value.trim();
                }
                return '';
            } catch(e) {
                return '';
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            val username = result.replace("\"", "").trim()
            callback(username.ifEmpty { null })
        }
    }

    private fun checkLoginError(callback: (String?) -> Unit) {
        val jsCode = """
        (function() {
            try {
                var errorDiv = document.evaluate('/html/body/div[2]/div[2]/div/div/div/div/div/div/div/div[1]', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
                if (errorDiv && errorDiv.classList.contains('alert') && errorDiv.classList.contains('alert-danger')) {
                    return errorDiv.textContent.trim();
                }
                return '';
            } catch(e) {
                return '';
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            val errorMessage = result.replace("\"", "").trim()
            callback(errorMessage.ifEmpty { null })
        }
    }

    private fun translateErrorMessage(originalMessage: String): String {
        return when {
            originalMessage.contains("Ungültige Anmeldedaten") -> {
                getString(R.string.moodle_invalid_credentials)
            }
            else -> originalMessage
        }
    }

    private fun showLoginDialog() {
        if (isLoginDialogShown) return

        val dontShowDialog = sharedPrefs.getBoolean("moodle_dont_show_login_dialog", false)
        if (dontShowDialog) {
            isLoginDialogShown = true
            return
        }

        isLoginDialogShown = true
        loginRetryCount++

        val dialogView = layoutInflater.inflate(R.layout.dialog_moodle_login, null)
        val etUsername = dialogView.findViewById<EditText>(R.id.etUsername)
        val etPassword = dialogView.findViewById<EditText>(R.id.etPassword)
        val cbSaveCredentials = dialogView.findViewById<CheckBox>(R.id.cbSaveCredentials)
        val cbDontShowAgain = dialogView.findViewById<CheckBox>(R.id.cbDontShowAgain)
        val tvErrorMessage = dialogView.findViewById<TextView>(R.id.tvErrorMessage)

        checkLoginError { errorMessage ->
            if (errorMessage != null) {
                tvErrorMessage.visibility = View.VISIBLE
                tvErrorMessage.text = translateErrorMessage(errorMessage)
            } else if (hasLoginFailed) {
                tvErrorMessage.visibility = View.VISIBLE
                tvErrorMessage.text = getString(R.string.moodle_login_failed)
            } else {
                tvErrorMessage.visibility = View.GONE
            }

            extractUsernameFromForm { extractedUsername ->
                val savedUsername = encryptedPrefs.getString("moodle_username", "") ?: ""

                val usernameToShow = extractedUsername ?: savedUsername
                if (usernameToShow.isNotEmpty()) {
                    etUsername.setText(usernameToShow)
                }

                val dialog = AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.moodle_auto_login_title))
                    .setMessage(getString(R.string.moodle_auto_login_message))
                    .setView(dialogView)
                    .setPositiveButton(getString(R.string.moodle_continue)) { _, _ ->
                        val username = etUsername.text.toString()
                        val password = etPassword.text.toString()

                        if (cbSaveCredentials.isChecked && username.isNotEmpty() && password.isNotEmpty()) {
                            saveCredentials(username, password)
                        }

                        if (cbDontShowAgain.isChecked) {
                            sharedPrefs.edit {
                                putBoolean("moodle_dont_show_login_dialog", true)
                            }
                        }

                        if (username.isNotEmpty() && password.isNotEmpty()) {
                            fillLoginForm(username, password)
                        }
                    }
                    .setNegativeButton(getString(R.string.moodle_cancel)) { _, _ ->
                        isLoginDialogShown = false
                        loginRetryCount = 0
                    }
                    .setOnDismissListener {
                        isLoginDialogShown = false
                    }
                    .show()

                val buttonColor = requireContext().getThemeColor(R.attr.dialogSectionButtonColor)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)
            }
        }
    }

    private fun saveCredentials(username: String, password: String) {
        encryptedPrefs.edit {
            putString("moodle_username", username)
                .putString("moodle_password", password)
        }
    }

    private fun performAutoLogin() {
        val username = encryptedPrefs.getString("moodle_username", "") ?: ""
        val password = encryptedPrefs.getString("moodle_password", "") ?: ""

        if (username.isNotEmpty() && password.isNotEmpty()) {
            fillLoginForm(username, password)
        }
    }

    private fun fillLoginForm(username: String, password: String) {
        val jsCode = """
        (function() {
            var usernameField = document.evaluate('/html/body/div[2]/div[2]/div/div/div/div/div/div/div/form/div[1]/input', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
            var passwordField = document.evaluate('/html/body/div[2]/div[2]/div/div/div/div/div/div/div/form/div[2]/div/input', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
            var submitButton = document.evaluate('/html/body/div[2]/div[2]/div/div/div/div/div/div/div/form/div[3]/button', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;

            if (!usernameField) usernameField = document.querySelector('input[name="username"], input[type="text"]');
            if (!passwordField) passwordField = document.querySelector('input[name="password"], input[type="password"]');
            if (!submitButton) submitButton = document.querySelector('input[type="submit"], button[type="submit"]');
            
            if (usernameField && passwordField && submitButton) {
                usernameField.value = '$username';
                passwordField.value = '$password';

                usernameField.dispatchEvent(new Event('input', { bubbles: true }));
                passwordField.dispatchEvent(new Event('input', { bubbles: true }));

                setTimeout(function() {
                    submitButton.click();
                }, 500);
                
                return true;
            } else {
                return false;
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            if (result == "false") {
                Toast.makeText(requireContext(), getString(R.string.moodle_login_form_not_found), Toast.LENGTH_SHORT).show()
                isLoginDialogShown = false
            } else {
                Handler(Looper.getMainLooper()).postDelayed({
                    checkLoginSuccess()
                }, 3000)
            }
        }
    }

    private fun checkLoginSuccess() {
        isUserLoggedIn { isLoggedIn ->
            if (isLoggedIn) {
                loginRetryCount = 0
                loginSuccessConfirmed = true
                lastSuccessfulLoginCheck = System.currentTimeMillis()
                isLoginDialogShown = false

                Handler(Looper.getMainLooper()).removeCallbacksAndMessages(null)
            } else {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!loginSuccessConfirmed && !isLoginDialogShown) {
                        hasLoginFailed = true
                        Handler(Looper.getMainLooper()).postDelayed({
                            isLoginDialogShown = false
                        }, 1000)
                    }
                }, 2000)
            }
        }
    }

    private fun showMenuPopup() {
        val popup = PopupMenu(requireContext(), btnMenu)
        val isDebugMode = BuildConfig.DEBUG && sharedPrefs.getBoolean("moodle_debug_mode", false)

        popup.menu.add(0, 1, 0, getString(R.string.moodle_profile)).apply {
            setIcon(R.drawable.ic_person)
        }
        popup.menu.add(0, 2, 0, getString(R.string.moodle_grades)).apply {
            setIcon(R.drawable.ic_star)
        }
        popup.menu.add(0, 3, 0, getString(R.string.moodle_calendar)).apply {
            setIcon(R.drawable.ic_menu_gallery)
        }
        popup.menu.add(0, 4, 0, getString(R.string.moodle_files)).apply {
            setIcon(R.drawable.ic_folder)
        }
        popup.menu.add(0, 5, 0, getString(R.string.moodle_reports)).apply {
            setIcon(R.drawable.ic_report)
        }
        popup.menu.add(0, 6, 0, getString(R.string.moodle_settings)).apply {
            setIcon(R.drawable.ic_gear)
        }

        if (isDebugMode) {
            popup.menu.add(0, 8, 0, "🔧 Debug Menu").apply {
                setIcon(R.drawable.ic_gear)
            }
        }

        popup.menu.add(0, 7, 0, getString(R.string.moodle_logout)).apply {
            setIcon(R.drawable.ic_logout)
        }

        try {
            val fieldMPopup = PopupMenu::class.java.getDeclaredField("mPopup")
            fieldMPopup.isAccessible = true
            val mPopup = fieldMPopup.get(popup)
            mPopup.javaClass
                .getDeclaredMethod("setForceShowIcon", Boolean::class.java)
                .invoke(mPopup, true)
        } catch (_: Exception) {
            // icons wont show but button will work anyways
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    loadUrlInBackground("$moodleBaseUrl/user/profile.php")
                    true
                }
                2 -> {
                    loadUrlInBackground("$moodleBaseUrl/grade/report/overview/index.php")
                    true
                }
                3 -> {
                    loadUrlInBackground("$moodleBaseUrl/calendar/view.php?view=month")
                    true
                }
                4 -> {
                    loadUrlInBackground("$moodleBaseUrl/user/files.php")
                    true
                }
                5 -> {
                    loadUrlInBackground("$moodleBaseUrl/reportbuilder/index.php")
                    true
                }
                6 -> {
                    loadUrlInBackground("$moodleBaseUrl/user/preferences.php")
                    true
                }
                7 -> {
                    loadUrlInBackground("$moodleBaseUrl/login/logout.php")
                    true
                }
                8 -> {
                    showDebugMenu()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    fun clearMoodleCache() {
        webView.clearCache(true)
        webView.clearHistory()
        webView.clearFormData()

        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }

        WebStorage.getInstance().deleteAllData()

        context?.let { ctx ->
            try {
                val cacheDir = ctx.cacheDir
                cacheDir.deleteRecursively()

                val webViewCacheDir = File(ctx.cacheDir, "webview")
                if (webViewCacheDir.exists()) {
                    webViewCacheDir.deleteRecursively()
                }

                val appDataDir = File(ctx.applicationInfo.dataDir)
                val webViewDataDir = File(appDataDir, "app_webview")
                if (webViewDataDir.exists()) {
                    webViewDataDir.deleteRecursively()
                }

                val databasesDir = File(appDataDir, "databases")
                if (databasesDir.exists()) {
                    databasesDir.listFiles()?.filter {
                        it.name.contains("webview", ignoreCase = true)
                    }?.forEach { it.delete() }
                }

            } catch (e: Exception) {
                L.e("MoodleFragment", "Error clearing cache", e)
            }
        }

        Toast.makeText(requireContext(), getString(R.string.moodle_cache_cleared), Toast.LENGTH_SHORT).show()
    }

    fun clearMoodleData() {
        clearMoodleCache()

        context?.let { ctx ->
            val webViewDir = File(ctx.applicationInfo.dataDir, "app_webview")
            if (webViewDir.exists()) {
                webViewDir.deleteRecursively()
            }

            val databasesDir = File(ctx.applicationInfo.dataDir, "databases")
            if (databasesDir.exists()) {
                databasesDir.listFiles()?.filter { it.name.startsWith("webview") }?.forEach { it.delete() }
            }
        }

        encryptedPrefs.edit {clear()}
        sharedPrefs.edit {
            remove("moodle_dont_show_login_dialog")
                .remove("moodle_auto_dismiss_confirm")
                .remove("moodle_debug_mode")
        }

        Toast.makeText(requireContext(), getString(R.string.moodle_data_cleared), Toast.LENGTH_SHORT).show()

        loadUrlInBackground(loginUrl)
    }

    override fun onResume() {
        super.onResume()

        setupSharedPreferences()

        if (webView.url == loginUrl) {
            Handler(Looper.getMainLooper()).postDelayed({
                isConfirmDialogPage { isConfirmDialog ->
                    if (!isConfirmDialog) {
                        checkAutoLogin()
                    }
                }
            }, 500)
        }
    }

    private fun isDownloadableFile(url: String): Boolean {
        val fileExtensions = listOf(
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
            ".zip", ".rar", ".7z", ".txt", ".jpg", ".jpeg", ".png",
            ".gif", ".mp4", ".mp3", ".avi", ".mov"
        )
        return fileExtensions.any { url.lowercase().contains(it) }
    }

    private fun handleDownload(url: String) {
        try {
            val request = DownloadManager.Request(url.toUri())

            var fileName = URLUtil.guessFileName(url, null, null)
            if (fileName.isBlank()) {
                fileName = try {
                    val decodedUrl = URLDecoder.decode(url, "UTF-8")
                    decodedUrl.substringAfterLast("/").substringBefore("?")
                } catch (_: Exception) {
                    "moodle_file_${System.currentTimeMillis()}"
                }
            }

            request.setTitle("Downloading $fileName")
            request.setDescription("Download from Moodle")
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            request.setAllowedOverMetered(true)
            request.setAllowedOverRoaming(true)

            val cookies = CookieManager.getInstance().getCookie(url)
            if (!cookies.isNullOrBlank()) {
                request.addRequestHeader("Cookie", cookies)
            }
            request.addRequestHeader("User-Agent", webView.settings.userAgentString)

            val downloadManager = requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)

            Toast.makeText(requireContext(), "Download started: $fileName", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openInExternalBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(requireContext(), "Could not open external browser", Toast.LENGTH_SHORT).show()
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

    private fun updateCounters() {
        if (!isPageFullyLoaded) return

        val currentUrl = webView.url ?: ""
        val isOnLoginPage = currentUrl == loginUrl || currentUrl.contains("login/index.php")

        if (isOnLoginPage) {
            tvNotificationCount.visibility = View.GONE
            tvMessageCount.visibility = View.GONE
            return
        }

        val notificationCountJs = """
        (function() {
            try {
                var notificationElement = document.evaluate('/html/body/div[1]/nav/div/div/div[2]/div[1]/div', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
                if (notificationElement && notificationElement.textContent) {
                    var count = notificationElement.textContent.trim().match(/\d+/);
                    return count ? count[0] : '0';
                }
                return '0';
            } catch(e) {
                return '0';
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(notificationCountJs) { result ->
            val count = result.replace("\"", "").toIntOrNull() ?: 0
            activity?.runOnUiThread {
                if (count > 0) {
                    tvNotificationCount.text = if (count > 99) "99+" else count.toString()
                    tvNotificationCount.visibility = View.VISIBLE
                } else {
                    tvNotificationCount.visibility = View.GONE
                }
            }
        }

        val messageCountJs = """
        (function() {
            try {
                var messageElement = document.evaluate('/html/body/div[1]/nav/div/div/div[3]/a/div/span[1]', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
                if (messageElement && messageElement.textContent) {
                    var count = messageElement.textContent.trim().match(/\d+/);
                    return count ? count[0] : '0';
                }
                return '0';
            } catch(e) {
                return '0';
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(messageCountJs) { result ->
            val count = result.replace("\"", "").toIntOrNull() ?: 0
            activity?.runOnUiThread {
                if (count > 0) {
                    tvMessageCount.text = if (count > 99) "99+" else count.toString()
                    tvMessageCount.visibility = View.VISIBLE
                } else {
                    tvMessageCount.visibility = View.GONE
                }
            }
        }
    }

    fun onBackPressed(): Boolean {
        val currentTime = System.currentTimeMillis()

        if (currentTime - backPressTime < 500) {
            backPressCount++
        } else {
            backPressCount = 1
        }

        backPressTime = currentTime

        if (webView.canGoBack() && backPressCount == 1) {
            webView.goBack()
            return true
        }

        return false
    }

    private fun refreshCalendarDataViaForm() {
        try {
            L.d("MoodleFragment", "Starting HTTP-based calendar export")

            val userAgent = webView.settings.userAgentString

            extractMoodleSessionCookie { sessionCookie ->
                if (sessionCookie != null) {
                    performHttpCalendarExport(sessionCookie, userAgent)
                } else {
                    L.e("MoodleFragment", "Could not extract session cookie")
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "Calendar export failed - no session", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            L.e("MoodleFragment", "Error in calendar refresh", e)
            activity?.runOnUiThread {
                Toast.makeText(requireContext(), "Calendar export failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun extractMoodleSessionCookie(callback: (String?) -> Unit) {
        val jsCode = """
        (function() {
            try {
                var moodleSession = document.cookie.match(/MoodleSession=([^;]+)/);
                if (moodleSession && moodleSession[1]) {
                    return moodleSession[1];
                }
                return null;
            } catch(e) {
                return null;
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            val cleanResult = result.replace("\"", "").trim()
            val sessionCookie = if (cleanResult == "null" || cleanResult.isEmpty()) null else cleanResult
            L.d("MoodleFragment", "Extracted session cookie: ${sessionCookie?.take(10)}...")
            callback(sessionCookie)
        }
    }

    private fun performHttpCalendarExport(sessionCookie: String, userAgent: String) {
        backgroundExecutor.execute {
            try {
                L.d("MoodleFragment", "Starting HTTP calendar export with session cookie")

                // Step 1: Get the export page to extract session key
                val exportUrl = "https://moodle.kleyer.eu/calendar/export.php"
                val connection1 = java.net.URL(exportUrl).openConnection() as java.net.HttpURLConnection

                connection1.apply {
                    requestMethod = "GET"
                    setRequestProperty("Cookie", "MoodleSession=$sessionCookie")
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    setRequestProperty("Accept-Language", "de-DE,de;q=0.9,en;q=0.8")
                    setRequestProperty("Referer", moodleBaseUrl)
                    connectTimeout = 15000
                    readTimeout = 15000
                }

                val response1Code = connection1.responseCode
                L.d("MoodleFragment", "Step 1 - Export page response: $response1Code")

                if (response1Code != 200) {
                    L.e("MoodleFragment", "Failed to load export page: $response1Code")
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "Calendar export failed - page load error", Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }

                // Read the response to extract session key
                val exportPageContent = connection1.inputStream.bufferedReader().use { it.readText() }
                connection1.disconnect()

                // Check if we're authenticated
                if (exportPageContent.contains("login") && !exportPageContent.contains("logout")) {
                    L.e("MoodleFragment", "Not authenticated - redirected to login")
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "Calendar export failed - please log in", Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }

                // Extract session key using regex
                val sesskeyPattern = """name="sesskey"[^>]*value="([^"]+)"""".toRegex()
                val sesskeyMatch = sesskeyPattern.find(exportPageContent)

                if (sesskeyMatch == null) {
                    L.e("MoodleFragment", "Could not find session key in export page")
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "Calendar export failed - no session key", Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }

                val sesskey = sesskeyMatch.groupValues[1]
                L.d("MoodleFragment", "Extracted session key: ${sesskey.take(10)}...")

                // Step 2: Submit the form with proper form data
                L.d("MoodleFragment", "Step 2 - Submitting export form")

                val connection2 = java.net.URL(exportUrl).openConnection() as java.net.HttpURLConnection

                // Prepare form data
                val formData = buildString {
                    append("sesskey=").append(java.net.URLEncoder.encode(sesskey, "UTF-8")).append("&")
                    append("_qf__core_calendar_export_form=1&")
                    append("events%5Bexportevents%5D=all&")
                    append("period%5Btimeperiod%5D=recentupcoming&")
                    append("export=Export")
                }

                connection2.apply {
                    requestMethod = "POST"
                    doOutput = true
                    instanceFollowRedirects = false // Don't auto-follow redirects
                    setRequestProperty("Cookie", "MoodleSession=$sessionCookie")
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    setRequestProperty("Content-Length", formData.length.toString())
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    setRequestProperty("Accept-Language", "de-DE,de;q=0.9,en;q=0.8")
                    setRequestProperty("Referer", exportUrl)
                    setRequestProperty("Origin", moodleBaseUrl)
                    connectTimeout = 15000
                    readTimeout = 15000
                }

                // Write form data
                connection2.outputStream.use { outputStream ->
                    outputStream.write(formData.toByteArray(Charsets.UTF_8))
                    outputStream.flush()
                }

                val response2Code = connection2.responseCode
                L.d("MoodleFragment", "Form submission response: $response2Code")

                // Step 3: Handle redirect to export_execute.php
                if (response2Code in listOf(301, 302, 303)) {
                    val redirectLocation = connection2.getHeaderField("Location")
                    L.d("MoodleFragment", "Redirect location: $redirectLocation")
                    connection2.disconnect()

                    if (redirectLocation != null && redirectLocation.contains("export_execute.php")) {
                        L.d("MoodleFragment", "Success! Got export_execute redirect")

                        // Step 4: Download the calendar file
                        val finalUrl = if (redirectLocation.startsWith("http")) {
                            redirectLocation
                        } else {
                            "$moodleBaseUrl$redirectLocation"
                        }

                        L.d("MoodleFragment", "Downloading calendar from: $finalUrl")

                        val connection3 = java.net.URL(finalUrl).openConnection() as java.net.HttpURLConnection
                        connection3.apply {
                            requestMethod = "GET"
                            setRequestProperty("Cookie", "MoodleSession=$sessionCookie")
                            setRequestProperty("User-Agent", userAgent)
                            setRequestProperty("Accept", "text/calendar,text/plain,*/*")
                            setRequestProperty("Referer", exportUrl)
                            connectTimeout = 15000
                            readTimeout = 15000
                        }

                        val response3Code = connection3.responseCode
                        L.d("MoodleFragment", "Calendar download response: $response3Code")

                        if (response3Code == 200) {
                            val calendarContent = connection3.inputStream.bufferedReader().use { it.readText() }
                            connection3.disconnect()

                            if (calendarContent.contains("BEGIN:VCALENDAR")) {
                                L.d("MoodleFragment", "Successfully downloaded calendar data (${calendarContent.length} chars)")
                                parseAndSaveCalendarData(calendarContent)
                            } else {
                                L.e("MoodleFragment", "Downloaded content is not valid iCalendar format")
                                L.d("MoodleFragment", "Content preview: ${calendarContent.take(200)}")
                                activity?.runOnUiThread {
                                    Toast.makeText(requireContext(), "Invalid calendar format received", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            L.e("MoodleFragment", "Failed to download calendar: $response3Code")
                            connection3.disconnect()
                            activity?.runOnUiThread {
                                Toast.makeText(requireContext(), "Calendar download failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        L.e("MoodleFragment", "Unexpected redirect location: $redirectLocation")
                        activity?.runOnUiThread {
                            Toast.makeText(requireContext(), "Calendar export failed - unexpected redirect", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else if (response2Code == 200) {
                    // Check if we got the calendar directly (sometimes happens)
                    val directContent = connection2.inputStream.bufferedReader().use { it.readText() }
                    connection2.disconnect()

                    if (directContent.contains("BEGIN:VCALENDAR")) {
                        L.d("MoodleFragment", "Got calendar data directly from form submission")
                        parseAndSaveCalendarData(directContent)
                    } else {
                        L.e("MoodleFragment", "Form submission returned unexpected content")
                        L.d("MoodleFragment", "Content preview: ${directContent.take(200)}")
                        activity?.runOnUiThread {
                            Toast.makeText(requireContext(), "Calendar export failed - unexpected response", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    L.e("MoodleFragment", "Form submission failed: $response2Code")
                    connection2.disconnect()
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "Calendar export failed - form submission error", Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                L.e("MoodleFragment", "Error in HTTP calendar export", e)
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "Calendar export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun parseAndSaveCalendarData(calendarContent: String) {
        try {
            val events = parseICalendarContent(calendarContent)

            calendarDataManager?.let { manager ->
                manager.clearMoodleCalendarData()

                for (event in events) {
                    val dayInfo = CalendarDataManager.CalendarDayInfo(
                        date = event.date,
                        dayOfWeek = SimpleDateFormat("EEEE", Locale.GERMANY).format(event.date),
                        month = SimpleDateFormat("MM", Locale.GERMANY).format(event.date).toInt(),
                        year = SimpleDateFormat("yyyy", Locale.GERMANY).format(event.date).toInt(),
                        content = "Kurs: ${event.category}\nAufgabe: ${event.summary}\n\n${event.description}",
                        exams = emptyList(),
                        isSpecialDay = true,
                        specialNote = "Moodle: ${event.summary} (ID: ${event.courseId})"
                    )
                    manager.addCalendarDay(dayInfo)
                }

                manager.saveCalendarData()

                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "Calendar data refreshed (${events.size} events)", Toast.LENGTH_SHORT).show()
                }

                L.d("MoodleFragment", "Successfully imported ${events.size} calendar events")
            }
        } catch (e: Exception) {
            L.e("MoodleFragment", "Error parsing calendar data", e)
            activity?.runOnUiThread {
                Toast.makeText(requireContext(), "Failed to refresh calendar data", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun parseICalendarContent(content: String): List<CalendarEvent> {
        val events = mutableListOf<CalendarEvent>()
        val lines = content.split("\n")

        var currentEvent: MutableMap<String, String>? = null
        var currentKey = ""

        for (line in lines) {
            val trimmedLine = line.trim()

            when {
                trimmedLine == "BEGIN:VEVENT" -> {
                    currentEvent = mutableMapOf()
                }
                trimmedLine == "END:VEVENT" -> {
                    currentEvent?.let { event ->
                        try {
                            val dtend = event["DTEND"] ?: event["DTSTART"] ?: return@let
                            val summary = event["SUMMARY"] ?: return@let
                            val description = event["DESCRIPTION"] ?: ""
                            val categories = event["CATEGORIES"] ?: ""
                            val uid = event["UID"] ?: ""

                            val date = parseICalendarDate(dtend)
                            if (date != null) {
                                val cleanedSummary = summary.removeSuffix(" ist fällig.").removeSuffix("ist fällig.")

                                val courseId = uid.removePrefix("UID:").substringBefore("@moodle.kleyer.eu")

                                events.add(CalendarEvent(
                                    date = date,
                                    summary = cleanICalendarText(cleanedSummary),
                                    description = cleanICalendarText(description),
                                    category = cleanICalendarText(categories),
                                    courseId = courseId
                                ))
                            }
                        } catch (e: Exception) {
                            L.w("MoodleFragment", "Error parsing calendar event", e)
                        }
                    }
                    currentEvent = null
                }
                currentEvent != null && trimmedLine.isNotEmpty() -> {
                    if (trimmedLine.startsWith(" ") || trimmedLine.startsWith("\t")) {
                        if (currentKey.isNotEmpty() && currentEvent.containsKey(currentKey)) {
                            currentEvent[currentKey] = currentEvent[currentKey] + trimmedLine.trim()
                        }
                    } else {
                        val colonIndex = trimmedLine.indexOf(':')
                        if (colonIndex > 0) {
                            val key = trimmedLine.substring(0, colonIndex).split(';')[0] // Remove parameters
                            val value = trimmedLine.substring(colonIndex + 1)
                            currentEvent[key] = value
                            currentKey = key
                        }
                    }
                }
            }
        }

        return events
    }

    private fun parseICalendarDate(dateString: String): Date? {
        return try {
            // format: 20251023T215900Z (gmt)
            val cleanDateString = dateString.replace("Z", "").replace("T", "")
            val gmtFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.GERMANY)
            gmtFormat.timeZone = TimeZone.getTimeZone("GMT")

            val gmtDate = gmtFormat.parse(cleanDateString)

            // convert to cest
            val germanTimeZone = TimeZone.getTimeZone("Europe/Berlin")
            val calendar = Calendar.getInstance(germanTimeZone)
            if (gmtDate != null) {
                calendar.time = gmtDate
            }

            return calendar.time
        } catch (_: ParseException) {
            try {
                // try alt format: 20251023
                val format = SimpleDateFormat("yyyyMMdd", Locale.GERMANY)
                format.parse(dateString.substring(0, 8))
            } catch (e2: ParseException) {
                L.e("MoodleFragment", "Error parsing date: $dateString", e2)
                null
            }
        }
    }

    private fun cleanICalendarText(text: String): String {
        return text.replace("\\n", "\n")
            .replace("\\,", ",")
            .replace("\\;", ";")
            .replace("\\\\", "\\")
            .trim()
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.shutdown()
    }

    private fun showDebugMenu() {
        if (!BuildConfig.DEBUG || !sharedPrefs.getBoolean("moodle_debug_mode", false)) return

        val debugItems = arrayOf(
            "Test Calendar Export",
            "Test Cookie Extraction",
            "Toggle Debug Mode",
            "View Page Source",
            "Execute Custom JS",
            "Open Chrome Inspect",
            "Check Form Elements",
            "Clear All Data"
        )

        AlertDialog.Builder(requireContext())
            .setTitle("🔧 Debug Menu")
            .setItems(debugItems) { _, which ->
                when (which) {
                    0 -> {
                        Toast.makeText(requireContext(), "Starting manual calendar export test...", Toast.LENGTH_SHORT).show()
                        refreshCalendarDataInBackground()
                    }
                    1 -> {
                        extractMoodleSessionCookie { cookie ->
                            activity?.runOnUiThread {
                                val message = if (cookie != null) {
                                    "Session Cookie: ${cookie.take(20)}..."
                                } else {
                                    "No session cookie found"
                                }
                                showDebugDialog("Cookie Test", message)
                            }
                        }
                    }
                    2 -> {
                        val currentDebugMode = sharedPrefs.getBoolean("moodle_debug_mode", false)
                        sharedPrefs.edit {
                            putBoolean("moodle_debug_mode", !currentDebugMode)
                        }
                        Toast.makeText(requireContext(), "Debug mode: ${!currentDebugMode}", Toast.LENGTH_SHORT).show()

                        Handler(Looper.getMainLooper()).postDelayed({
                            setupWebView()
                            webView.reload()
                        }, 1000)
                    }
                    3 -> {
                        webView.evaluateJavascript("document.documentElement.outerHTML") { html ->
                            showDebugDialog("Page Source", html.take(5000) + if (html.length > 5000) "\n\n... (truncated)" else "")
                        }
                    }
                    4 -> {
                        showCustomJSDialog()
                    }
                    5 -> {
                        showDebugDialog("Chrome Inspect Instructions",
                            "To debug WebView:\n\n" +
                                    "1. Enable USB Debugging on device\n" +
                                    "2. Connect to computer\n" +
                                    "3. Open Chrome on computer\n" +
                                    "4. Go to chrome://inspect\n" +
                                    "5. Find your WebView under 'Remote Target'\n\n" +
                                    "WebView debugging is currently: ${"ENABLED"}\n\n" +
                                    "Current URL: ${webView.url}")
                    }
                    6 -> {
                        checkFormElementsDebug()
                    }
                    7 -> {
                        AlertDialog.Builder(requireContext())
                            .setTitle("Clear All Data")
                            .setMessage("This will clear all Moodle data and restart. Continue?")
                            .setPositiveButton("Yes") { _, _ ->
                                clearMoodleData()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDebugDialog(title: String, content: String) {
        val scrollView = ScrollView(requireContext())
        val textView = TextView(requireContext()).apply {
            text = content
            setPadding(32, 32, 32, 32)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scrollView.addView(textView)

        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ ->
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText(title, content)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showCustomJSDialog() {
        val editText = EditText(requireContext()).apply {
            hint = "Enter JavaScript code..."
            setText("document.title")
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Execute JavaScript")
            .setView(editText)
            .setPositiveButton("Execute") { _, _ ->
                val jsCode = editText.text.toString()
                if (jsCode.isNotBlank()) {
                    webView.evaluateJavascript(jsCode) { result ->
                        showDebugDialog("JS Result", "Code: $jsCode\n\nResult: $result")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkFormElementsDebug() {
        val jsCode = """
        (function() {
            var info = {
                url: window.location.href,
                title: document.title,
                forms: []
            };
            
            var forms = document.querySelectorAll('form');
            for (var i = 0; i < forms.length; i++) {
                var form = forms[i];
                var formInfo = {
                    action: form.action,
                    method: form.method,
                    elements: []
                };
                
                var elements = form.querySelectorAll('input, select, textarea');
                for (var j = 0; j < elements.length; j++) {
                    var el = elements[j];
                    formInfo.elements.push({
                        type: el.type,
                        name: el.name,
                        id: el.id,
                        value: el.value,
                        checked: el.checked,
                        disabled: el.disabled
                    });
                }
                
                info.forms.push(formInfo);
            }
            
            var errors = [];
            var errorDivs = document.querySelectorAll('.invalid-feedback, .error');
            for (var k = 0; k < errorDivs.length; k++) {
                var errorDiv = errorDivs[k];
                if (errorDiv.style.display !== 'none') {
                    errors.push({
                        text: errorDiv.textContent.trim(),
                        visible: errorDiv.offsetParent !== null,
                        id: errorDiv.id
                    });
                }
            }
            info.errors = errors;
            
            return JSON.stringify(info, null, 2);
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            showDebugDialog("Form Elements Debug", result.replace("\\\"", "\"").replace("\\n", "\n"))
        }
    }

    private fun resetSearchState() {
        val jsCode = """
        (function() {
            console.log('Resetting search state...');

            var searchInputs = document.querySelectorAll('input[type="text"]');
            for (var i = 0; i < searchInputs.length; i++) {
                var input = searchInputs[i];
                if (input.placeholder && 
                    (input.placeholder.toLowerCase().includes('search') || 
                     input.placeholder.toLowerCase().includes('suchen'))) {
                    input.value = '';
                    input.dispatchEvent(new Event('input', { bubbles: true }));
                    input.dispatchEvent(new Event('change', { bubbles: true }));
                }
            }

            var resultsContainers = document.querySelectorAll('[data-region="courses-view"]');
            for (var j = 0; j < resultsContainers.length; j++) {
                resultsContainers[j].innerHTML = '';
            }

            var loadingElements = document.querySelectorAll('.loading, .spinner, [data-region="loading"]');
            for (var k = 0; k < loadingElements.length; k++) {
                loadingElements[k].style.display = 'none';
            }
            
            console.log('Search state reset complete');
            return true;
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { _ ->
            L.d("MoodleFragment", "Search state reset completed")
        }
    }

    private fun showSearchProgressDialog(category: String): AlertDialog {
        val dialogView = layoutInflater.inflate(R.layout.dialog_moodle_search_progress, null)
        val tvSearchTitle = dialogView.findViewById<TextView>(R.id.tvSearchTitle)
        val tvSearchStatus = dialogView.findViewById<TextView>(R.id.tvSearchStatus)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressBar)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        tvSearchTitle.text = getString(R.string.moodle_searching_for, category)
        tvSearchStatus.text = getString(R.string.moodle_loading_courses)
        progressBar.progress = 10

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnCancel.setOnClickListener {
            searchCancelled = true
            dialog.dismiss()
            Toast.makeText(requireContext(), getString(R.string.moodle_search_cancelled), Toast.LENGTH_SHORT).show()
        }

        dialog.show()
        return dialog
    }

    private fun updateSearchProgress(progress: Int, status: String) {
        searchProgressDialog?.let { dialog ->
            if (dialog.isShowing && !searchCancelled) {
                val dialogView = dialog.findViewById<View>(android.R.id.content)
                val tvSearchStatus = dialogView?.findViewById<TextView>(R.id.tvSearchStatus)
                val progressBar = dialogView?.findViewById<ProgressBar>(R.id.progressBar)

                activity?.runOnUiThread {
                    tvSearchStatus?.text = status
                    progressBar?.progress = progress
                }
            }
        }
    }

    private fun hideSearchProgressDialog() {
        searchProgressDialog?.dismiss()
        searchProgressDialog = null
        searchCancelled = false
    }

    private fun searchForMoodleEntry(category: String, summary: String, entryId: String) {
        L.d("MoodleFragment", "Searching for Moodle entry - Category: $category, Summary: $summary (ID: $entryId)")

        searchCancelled = false
        searchProgressDialog = showSearchProgressDialog(category)

        loadUrlInBackground("$moodleBaseUrl/my/")

        waitForPageLoadComplete {
            if (searchCancelled) return@waitForPageLoadComplete

            updateSearchProgress(20, getString(R.string.moodle_page_loaded_resetting))
            resetSearchState()

            Handler(Looper.getMainLooper()).postDelayed({
                if (searchCancelled) return@postDelayed
                performMoodleEntrySearch(category, summary, entryId)
            }, 500)
        }
    }

    private fun performMoodleEntrySearch(category: String, summary: String, entryId: String) {
        if (searchCancelled) return

        L.d("MoodleFragment", "Using category as search query: $category")
        updateSearchProgress(30, getString(R.string.moodle_searching_course))

        waitForPageLoadComplete {
            if (searchCancelled) return@waitForPageLoadComplete

            updateSearchProgress(35, getString(R.string.moodle_preparing_search))

            resetSearchState()

            Handler(Looper.getMainLooper()).postDelayed({
                if (searchCancelled) return@postDelayed

                waitForSearchFieldReady { searchFieldReady ->
                    if (searchCancelled) return@waitForSearchFieldReady

                    if (searchFieldReady) {
                        updateSearchProgress(40, getString(R.string.moodle_filling_search))
                        fillSearchFieldWithDelay(category) { searchFilled ->
                            if (searchCancelled) return@fillSearchFieldWithDelay

                            if (searchFilled) {
                                updateSearchProgress(50, getString(R.string.moodle_waiting_results))

                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (searchCancelled) return@postDelayed
                                    clickFirstCourseResult(category, summary, entryId)
                                }, 1500)
                            } else {
                                hideSearchProgressDialog()
                                L.e("MoodleFragment", "Failed to fill search field")
                                Toast.makeText(requireContext(), getString(R.string.moodle_search_course_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        hideSearchProgressDialog()
                        L.e("MoodleFragment", "Search field not ready")
                        Toast.makeText(requireContext(), getString(R.string.moodle_search_not_available), Toast.LENGTH_SHORT).show()
                    }
                }
            }, 1000)
        }
    }

    private fun fillSearchFieldWithDelay(searchQuery: String, callback: (Boolean) -> Unit) {
        L.d("MoodleFragment", "Starting enhanced search field fill with: $searchQuery")

        val jsCode = """
        (function() {
            console.log('Enhanced search field fill starting...');

            var existingResults = document.querySelector('[data-region="courses-view"]');
            if (existingResults) {
                existingResults.innerHTML = '';
            }

            var searchField = null;
            var selectors = [
                '/html/body/div[1]/div[2]/div/div[1]/div/div/section/section/div/div/div[1]/div[1]/div/div[2]/div/div/input',
                'input[placeholder*="Search"]',
                'input[placeholder*="search"]',
                'input[placeholder*="Suchen"]',
                'input[data-region="search-input"]',
                'section input[type="text"]',
                '.search-input-region input',
                '[data-region="view-selector"] input'
            ];

            searchField = document.evaluate(selectors[0], document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;

            if (!searchField) {
                for (var i = 1; i < selectors.length; i++) {
                    searchField = document.querySelector(selectors[i]);
                    if (searchField && searchField.offsetParent !== null) {
                        console.log('Found search field with selector: ' + selectors[i]);
                        break;
                    }
                }
            }
            
            if (!searchField) {
                console.log('No search field found');
                return false;
            }
            
            console.log('Search field found, proceeding with input...');

            searchField.disabled = false;
            searchField.readOnly = false;
            searchField.value = '';

            searchField.focus();
            searchField.select();

            searchField.value = '$searchQuery';

            var inputEvent = new Event('input', { 
                bubbles: true, 
                cancelable: true 
            });
            searchField.dispatchEvent(inputEvent);

            var changeEvent = new Event('change', { 
                bubbles: true, 
                cancelable: true 
            });
            searchField.dispatchEvent(changeEvent);
            
            var keyupEvent = new KeyboardEvent('keyup', { 
                key: 'Enter',
                keyCode: 13,
                bubbles: true,
                cancelable: true
            });

            setTimeout(function() {
                searchField.dispatchEvent(keyupEvent);
                console.log('Search events dispatched for: $searchQuery');
            }, 200);
            
            return true;
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            val success = result == "true"
            L.d("MoodleFragment", "Enhanced search field fill result: $success")

            if (success) {
                Handler(Looper.getMainLooper()).postDelayed({
                    callback(true)
                }, 800)
            } else {
                callback(false)
            }
        }
    }

    private fun waitForSearchFieldReady(maxAttempts: Int = 20, attempt: Int = 0, callback: (Boolean) -> Unit) {
        if (attempt >= maxAttempts) {
            L.w("MoodleFragment", "Search field ready timeout after $maxAttempts attempts")
            callback(false)
            return
        }

        val jsCode = """
        (function() {
            if (document.readyState !== 'complete') {
                console.log('Document not ready yet');
                return false;
            }

            var searchField = null;
            var selectors = [
                '/html/body/div[1]/div[2]/div/div[1]/div/div/section/section/div/div/div[1]/div[1]/div/div[2]/div/div/input',
                'input[placeholder*="Search"]',
                'input[placeholder*="search"]', 
                'input[placeholder*="Suchen"]',
                'input[data-region="search-input"]',
                'section input[type="text"]',
                '.search-input-region input',
                '[data-region="view-selector"] input'
            ];

            searchField = document.evaluate(selectors[0], document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;

            if (!searchField) {
                for (var i = 1; i < selectors.length; i++) {
                    var candidates = document.querySelectorAll(selectors[i]);
                    for (var j = 0; j < candidates.length; j++) {
                        var candidate = candidates[j];
                        if (candidate.offsetParent !== null && !candidate.disabled && !candidate.readOnly) {
                            searchField = candidate;
                            console.log('Found search field with selector: ' + selectors[i]);
                            break;
                        }
                    }
                    if (searchField) break;
                }
            }
            
            if (!searchField) {
                console.log('No search field found on attempt $attempt');
                return false;
            }

            var isReady = searchField.offsetParent !== null && 
                         !searchField.disabled && 
                         !searchField.readOnly &&
                         searchField.style.display !== 'none';
            
            console.log('Search field ready check: ' + isReady);
            return isReady;
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            if (result == "true") {
                L.d("MoodleFragment", "Search field is ready after ${attempt + 1} attempts")
                callback(true)
            } else {
                L.d("MoodleFragment", "Search field not ready, attempt ${attempt + 1}/$maxAttempts")
                Handler(Looper.getMainLooper()).postDelayed({
                    waitForSearchFieldReady(maxAttempts, attempt + 1, callback)
                }, 750)
            }
        }
    }

    private fun clickFirstCourseResult(category: String, summary: String, entryId: String) {
        if (searchCancelled) return

        waitForSearchResults { resultsReady ->
            if (searchCancelled) return@waitForSearchResults

            if (resultsReady) {
                updateSearchProgress(70, getString(R.string.moodle_opening_course))

                val firstCourseLinkXpath = "/html/body/div[1]/div[2]/div/div[1]/div/div/section/section[2]/div/div/div[1]/div[2]/div/div/div[1]/div/ul/li[1]/div/div[2]/a"

                val jsCode = """
                (function() {
                    var courseLink = document.evaluate('$firstCourseLinkXpath', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
                    if (courseLink && courseLink.href) {
                        var courseUrl = courseLink.href;
                        courseLink.click();
                        return courseUrl;
                    }
                    return false;
                })();
            """.trimIndent()

                webView.evaluateJavascript(jsCode) { result ->
                    if (searchCancelled) return@evaluateJavascript

                    val courseUrl = result.replace("\"", "")
                    if (courseUrl != "false" && courseUrl.contains("course/view.php")) {
                        L.d("MoodleFragment", "Clicked first course: $courseUrl")
                        updateSearchProgress(80, getString(R.string.moodle_searching_assignment))

                        Handler(Looper.getMainLooper()).postDelayed({
                            if (searchCancelled) return@postDelayed
                            waitForCoursePageLoad {
                                if (!searchCancelled) {
                                    searchForSpecificEntry(summary, entryId)
                                }
                            }
                        }, 1000)
                    } else {
                        hideSearchProgressDialog()
                        L.e("MoodleFragment", "Failed to find or click first course link")
                        Toast.makeText(requireContext(), getString(R.string.moodle_no_matching_courses, category), Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                hideSearchProgressDialog()
                L.e("MoodleFragment", "Search results not ready")
                Toast.makeText(requireContext(), getString(R.string.moodle_search_results_not_loaded), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun waitForCoursePageLoad(maxAttempts: Int = 15, attempt: Int = 0, callback: () -> Unit) {
        if (attempt >= maxAttempts) {
            L.w("MoodleFragment", "Course page load timeout")
            callback()
            return
        }

        val jsCode = """
        (function() {
            return document.readyState === 'complete' && 
                   document.querySelector('.course-content, .topics, [role="main"]') !== null &&
                   !document.querySelector('.loading, .spinner');
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            if (result == "true") {
                L.d("MoodleFragment", "Course page loaded successfully")
                callback()
            } else {
                L.d("MoodleFragment", "Course page loading, attempt ${attempt + 1}/$maxAttempts")
                Handler(Looper.getMainLooper()).postDelayed({
                    waitForCoursePageLoad(maxAttempts, attempt + 1, callback)
                }, 500)
            }
        }
    }

    private fun waitForSearchResults(maxAttempts: Int = 15, attempt: Int = 0, callback: (Boolean) -> Unit) {
        if (attempt >= maxAttempts) {
            L.w("MoodleFragment", "Search results timeout after $maxAttempts attempts")
            callback(false)
            return
        }

        val jsCode = """
        (function() {
            var resultsContainer = document.querySelector('[data-region="courses-view"]');
            if (!resultsContainer) {
                console.log('Results container not found');
                return false;
            }

            if (resultsContainer.children.length === 0) {
                console.log('Results container empty, attempt $attempt');
                return false;
            }

            var courseLinks = resultsContainer.querySelectorAll('a[href*="course/view.php"]');
            if (courseLinks.length === 0) {
                console.log('No course links found yet');
                return false;
            }

            var firstLink = courseLinks[0];
            if (firstLink.offsetParent === null) {
                console.log('First course link not visible');
                return false;
            }
            
            console.log('Found ' + courseLinks.length + ' course results');
            return true;
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            if (result == "true") {
                L.d("MoodleFragment", "Search results are ready after ${attempt + 1} attempts")
                callback(true)
            } else {
                L.d("MoodleFragment", "Search results not ready, attempt ${attempt + 1}/$maxAttempts")
                Handler(Looper.getMainLooper()).postDelayed({
                    waitForSearchResults(maxAttempts, attempt + 1, callback)
                }, 1000)
            }
        }
    }

    private fun searchForSpecificEntry(summary: String, entryId: String) {
        if (searchCancelled) return

        val cleanSummary = summary.removeSuffix("ist fällig.")
            .removeSuffix(" ist fällig.").trim()
        updateSearchProgress(90, getString(R.string.moodle_looking_for, cleanSummary))

        L.d("MoodleFragment", "Searching for specific entry: $cleanSummary")

        val jsCode = """
        (function() {
            var walker = document.createTreeWalker(
                document.body,
                NodeFilter.SHOW_TEXT,
                null,
                false
            );
            
            var textNode;
            var foundElement = null;
            
            while (textNode = walker.nextNode()) {
                if (textNode.textContent.includes('$cleanSummary')) {
                    var element = textNode.parentElement;
                    while (element && element.tagName !== 'A') {
                        element = element.parentElement;
                        if (!element || element === document.body) break;
                    }
                    
                    if (element && element.tagName === 'A' && element.href) {
                        foundElement = element;
                        break;
                    }
                }
            }
            
            if (foundElement) {
                var entryUrl = foundElement.href;
                window.location.href = entryUrl;
                return entryUrl;
            }
            
            return false;
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            updateSearchProgress(100, getString(R.string.moodle_search_complete))

            Handler(Looper.getMainLooper()).postDelayed({
                hideSearchProgressDialog()
            }, 500)

            val entryUrl = result.replace("\"", "")
            if (entryUrl != "false" && (entryUrl.contains("mod/") || entryUrl.contains("view.php"))) {
                L.d("MoodleFragment", "Found and navigating to entry: $entryUrl")
                Toast.makeText(requireContext(), getString(R.string.moodle_found_entry, cleanSummary), Toast.LENGTH_SHORT).show()
            } else {
                L.w("MoodleFragment", "Specific entry not found on course page")
                Toast.makeText(requireContext(), getString(R.string.moodle_entry_not_found, cleanSummary), Toast.LENGTH_LONG).show()
            }
        }
    }
}