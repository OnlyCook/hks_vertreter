package com.thecooker.vertretungsplaner.ui.moodle

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Base64
import android.util.TypedValue
import android.view.animation.AccelerateInterpolator
import android.view.animation.Animation
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.view.inputmethod.InputMethodManager
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.graphics.scale
import androidx.core.graphics.createBitmap
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.parser.LocationTextExtractionStrategy
import com.itextpdf.text.pdf.parser.PdfTextExtractor
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.math.pow
import kotlin.math.sqrt

class MoodleFragment : Fragment() {

    private lateinit var webView: WebView
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
    private var isWebViewInitialized = false

    private val moodleBaseUrl = "https://moodle.kleyer.eu"
    private val loginUrl = "$moodleBaseUrl/login/index.php"
    private var isLoginDialogShown = false
    private var hasLoginFailed = false
    private var loginRetryCount = 0
    private var lastSuccessfulLoginCheck = 0L
    private var loginSuccessConfirmed = false

    private var searchBarVisible = false
    private var isAtTop = true
    private var scrollThreshold = 15 // pixels to scroll before hiding header
    private var isUserScrolling = false
    private var scrollEndCheckRunnable: Runnable? = null

    private var backPressTime = 0L
    private var backPressCount = 0

    // file picker
    private lateinit var btnOpenInBrowser: ImageButton

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

    // scroll to refresh
    private var pullStartY = 0f
    private var isPulling = false
    private var pullDistance = 0f
    private var refreshIndicator: ImageView? = null
    private var refreshContainer: FrameLayout? = null
    private var startedPullFromTop = false
    private var isScrollingDown = false
    private var lastScrollY = 0
    private var headerHiddenDuringScroll = false
    private val pullThresholdComplete = 350f
    private var isHeaderAnimating = false

    // session expired
    private var sessionExpiredDetected = false
    private var lastSessionExpiredTime = 0L
    private var consecutiveLoginFailures = 0
    private val MAX_LOGIN_ATTEMPTS = 3
    private val SESSION_RETRY_DELAY = 30000L
    private var isLoginInProgress = false
    private var loginAttemptCount = 0

    private var userAgent: String = ""

    // tabs
    data class TabInfo(
        val id: String = UUID.randomUUID().toString(),
        val url: String,
        val title: String,
        val thumbnail: Bitmap? = null,
        val webViewState: Bundle? = null,
        val isPinned: Boolean = false,
        val isDefault: Boolean = false,
        val createdAt: Long = System.currentTimeMillis()
    )

    // tabs management
    private val tabs = mutableListOf<TabInfo>()
    private var currentTabIndex = 0
    private val MAX_TABS = 10
    private var tabOverlayView: View? = null
    private var isTabViewVisible = false
    private lateinit var tabRecyclerView: RecyclerView
    private lateinit var tabAdapter: TabAdapter
    private var isCompactTabLayout = false
    private var isDraggingTab = false
    private var draggedTabIndex = -1

    private class HorizontalSpacingItemDecoration(
        private val spacing: Int
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val position = parent.getChildAdapterPosition(view)
            outRect.left = if (position == 0) spacing else spacing / 2
            outRect.right = if (position == state.itemCount - 1) spacing else spacing / 2
            outRect.top = spacing / 2
            outRect.bottom = spacing / 2
        }
    }

    // pdf viewer
    private var pdfRenderer: PdfRenderer? = null
    private var currentPdfPage = 0
    private var pdfViewerOverlay: View? = null
    private var isPdfViewerVisible = false
    private lateinit var pdfImageView: ImageView
    private lateinit var pdfPageIndicator: TextView

    // horizontal loading bar
    private lateinit var horizontalProgressBar: ProgressBar
    private lateinit var loadingBarContainer: FrameLayout

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

        if (!isWebViewInitialized) {
            Handler(Looper.getMainLooper()).post {
                setupWebView()
                initializeTabSystem()
                setupClickListeners()
                isWebViewInitialized = true

                Handler(Looper.getMainLooper()).postDelayed({
                    loadUrlInBackground(loginUrl)
                }, 100)
            }
        } else {
            setupWebView()
            initializeTabSystem()
            setupClickListeners()
            Handler(Looper.getMainLooper()).post {
                loadUrlInBackground(loginUrl)
            }
        }

        return root
    }

    private fun updateUIState() {
        val currentUrl = webView.url ?: ""
        val isOnLoginPage = currentUrl == loginUrl || currentUrl.contains("login/index.php")
        val canGoBack = webView.canGoBack()

        btnSearch.isEnabled = !isOnLoginPage
        btnDashboard.isEnabled = !isOnLoginPage
        btnNotifications.isEnabled = !isOnLoginPage
        btnMessages.isEnabled = !isOnLoginPage
        btnMenu.isEnabled = !isOnLoginPage

        btnForward.isEnabled = !isOnLoginPage

        btnSearch.alpha = if (isOnLoginPage) 0.5f else 1.0f
        btnDashboard.alpha = if (isOnLoginPage) 0.5f else 1.0f
        btnNotifications.alpha = if (isOnLoginPage) 0.5f else 1.0f
        btnMessages.alpha = if (isOnLoginPage) 0.5f else 1.0f
        btnMenu.alpha = if (isOnLoginPage) 0.5f else 1.0f
        btnBack.alpha = if (canGoBack) 1.0f else 0.5f
        btnForward.alpha = if (isOnLoginPage) 0.5f else 1.0f

        if (isOnLoginPage && searchBarVisible) {
            toggleSearchBar()
        }
    }

    private fun initViews(root: View) {
        webView = root.findViewById(R.id.webViewMoodle)
        horizontalProgressBar = root.findViewById(R.id.horizontalProgressBar)
        loadingBarContainer = root.findViewById(R.id.loadingBarContainer)
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
        refreshContainer = root.findViewById(R.id.refreshContainer)
        refreshIndicator = root.findViewById(R.id.refreshIndicator)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

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
            setRenderPriority(WebSettings.RenderPriority.HIGH)

            allowFileAccess = true
            allowContentAccess = true

            if (isDebugMode) {
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
                setGeolocationEnabled(true)
            }
        }

        userAgent = webView.settings.userAgentString

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                activity?.runOnUiThread {
                    if (newProgress < 100) {
                        showLoadingBar()
                        horizontalProgressBar.progress = newProgress
                    } else {
                        horizontalProgressBar.progress = 100
                        Handler(Looper.getMainLooper()).postDelayed({
                            hideLoadingBar()
                        }, 200)
                    }
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false

                if (url.contains("/mod/resource/view.php")) {
                    L.d("MoodleFragment", "Intercepted resource view URL: $url")
                    val cookies = CookieManager.getInstance().getCookie(url)
                    resolveDownloadUrl(url, cookies)
                    return true
                }

                if (url.contains("/pluginfile.php") || url.contains("forcedownload=1")) {
                    L.d("MoodleFragment", "Intercepted direct download URL: $url")
                    val cookies = CookieManager.getInstance().getCookie(url)
                    handleInterceptedDownload(url, cookies)
                    return true
                }

                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                activity?.runOnUiThread {
                    hideLoadingBar()
                    updateUIState()
                    updateDashboardButtonIcon()
                }
                isPageFullyLoaded = true

                if (url?.contains("logout.php") == true) {
                    handleLogout()
                    return
                }

                activity?.runOnUiThread {
                    updateUIState()
                    showExtendedHeaderInitially()

                    url?.let {
                        if (!it.contains("forcedownload=1") && !it.contains("pluginfile.php")) {
                            updateUrlBar(it)
                        }
                    }
                }

                Handler(Looper.getMainLooper()).postDelayed({
                    updateCounters()
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
                }, 300)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                activity?.runOnUiThread {
                    showLoadingBar()
                    horizontalProgressBar.progress = 0
                }
                isPageFullyLoaded = false
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            L.d("MoodleFragment", "Download listener triggered - URL: $url")

            val cookies = CookieManager.getInstance().getCookie(url)
            handleInterceptedDownload(url, cookies, contentDisposition, mimetype)
        }

        webView.setOnLongClickListener { view ->
            val hitTestResult = (view as WebView).hitTestResult
            if (hitTestResult.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                hitTestResult.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {

                val url = hitTestResult.extra ?: return@setOnLongClickListener false
                showLinkContextMenu(url)
                return@setOnLongClickListener true
            }
            false
        }

        webView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isUserScrolling = true
                    pullStartY = event.rawY
                    lastScrollY = webView.scrollY
                    isScrollingDown = false
                    headerHiddenDuringScroll = false
                    startedPullFromTop = (webView.scrollY == 0 && isAtTop && extendedHeaderLayout.isVisible)

                    if (startedPullFromTop) {
                        isPulling = true
                        pullDistance = 0f
                        createRefreshIndicator()
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val currentScrollY = webView.scrollY
                    val scrollDelta = currentScrollY - lastScrollY

                    if (scrollDelta > 3) {
                        if (!isScrollingDown) {
                            isScrollingDown = true
                        }

                        if (currentScrollY > scrollThreshold && extendedHeaderLayout.isVisible && !headerHiddenDuringScroll && !isHeaderAnimating) {
                            headerHiddenDuringScroll = true
                            hideExtendedHeaderWithAnimation()
                        }
                    }

                    lastScrollY = currentScrollY

                    if (startedPullFromTop && webView.scrollY == 0) {
                        val currentY = event.rawY
                        val deltaY = currentY - pullStartY

                        if (deltaY > 0) {
                            isPulling = true
                            pullDistance = deltaY
                            updateRefreshIndicator(pullDistance)
                            return@setOnTouchListener true
                        } else if (deltaY < 0 && isPulling) {
                            isPulling = false
                            startedPullFromTop = false
                            animateRefreshIndicatorAway()
                        }
                    } else {
                        if (isPulling) {
                            isPulling = false
                            startedPullFromTop = false
                            animateRefreshIndicatorAway()
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isPulling && startedPullFromTop && pullDistance > pullThresholdComplete && webView.scrollY == 0) {
                        triggerWebViewRefresh()
                    } else if (isPulling) {
                        animateRefreshIndicatorAway()
                    }

                    isUserScrolling = false
                    isPulling = false
                    startedPullFromTop = false
                    pullDistance = 0f
                    isScrollingDown = false
                    headerHiddenDuringScroll = false

                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!isUserScrolling) {
                            checkScrollEndedAtTop()
                        }
                    }, 100)

                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!isUserScrolling) {
                            checkScrollEndedAtTop()
                        }
                    }, 300)

                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!isUserScrolling) {
                            checkScrollEndedAtTop()
                        }
                    }, 600)
                }
            }
            false
        }

        webView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            scrollEndCheckRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }

            scrollEndCheckRunnable = Runnable {
                checkScrollEndedAtTop()
            }
            Handler(Looper.getMainLooper()).postDelayed(scrollEndCheckRunnable!!, 150)
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
        consecutiveLoginFailures = 0
        isLoginInProgress = false
        loginAttemptCount = 0

        encryptedPrefs.edit { clear() }

        sharedPrefs.edit {
            remove("moodle_dont_show_login_dialog")
        }
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
            if (!isOnLoginPage()) {
                showTabOverlay()
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
                Toast.makeText(requireContext(), getString(R.string.moodle_no_url_to_open), Toast.LENGTH_SHORT).show()
            }
        }

        btnOpenInBrowser.setOnLongClickListener {
            showGlobeButtonMenu()
            true
        }

        btnBack.setOnLongClickListener {
            showBackButtonMenu()
            true
        }
    }

    private fun updateTabButtonIcon() {
        btnForward.setImageResource(
            if (isTabViewVisible) R.drawable.ic_tabs_filled else R.drawable.ic_tabs
        )
    }

    private fun updateDashboardButtonIcon() {
        val currentUrl = webView.url ?: ""
        val isOnDashboard = currentUrl == "$moodleBaseUrl/my/" ||
                currentUrl.endsWith("/my") ||
                currentUrl.contains("/my/index.php")

        btnDashboard.setImageResource(
            if (isOnDashboard) R.drawable.ic_dashboard_filled else R.drawable.ic_dashboard
        )
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

        val animationsEnabled = Settings.Global.getFloat(
            requireContext().contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f
        ) != 0.0f

        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

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

                ValueAnimator.ofInt(0, targetHeight).apply {
                    duration = 250
                    interpolator = DecelerateInterpolator()

                    addUpdateListener { animator ->
                        val animatedHeight = animator.animatedValue as Int
                        layoutParams.height = animatedHeight
                        searchLayout.layoutParams = layoutParams
                    }

                    doOnEnd {
                        layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        searchLayout.layoutParams = layoutParams
                        searchBarMoodle.requestFocus()
                        imm.showSoftInput(searchBarMoodle, InputMethodManager.SHOW_IMPLICIT)
                    }

                    start()
                }
            } else {
                imm.hideSoftInputFromWindow(searchBarMoodle.windowToken, 0)

                val currentHeight = searchLayout.height
                val layoutParams = searchLayout.layoutParams

                ValueAnimator.ofInt(currentHeight, 0).apply {
                    duration = 200
                    interpolator = AccelerateInterpolator()

                    addUpdateListener { animator ->
                        val animatedHeight = animator.animatedValue as Int
                        layoutParams.height = animatedHeight
                        searchLayout.layoutParams = layoutParams
                    }

                    doOnEnd {
                        searchLayout.visibility = View.GONE
                        layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        searchLayout.layoutParams = layoutParams
                        searchBarMoodle.clearFocus()
                    }

                    start()
                }

                searchBarMoodle.setText("")
            }
        } else {
            if (searchBarVisible) {
                searchLayout.visibility = View.VISIBLE
                searchBarMoodle.requestFocus()
                imm.showSoftInput(searchBarMoodle, InputMethodManager.SHOW_IMPLICIT)
            } else {
                imm.hideSoftInputFromWindow(searchBarMoodle.windowToken, 0)
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

        if (shouldShow) {
            showExtendedHeaderWithAnimation()
        } else {
            hideExtendedHeaderWithAnimation()
        }
    }

    private inline fun ValueAnimator.doOnEnd(crossinline action: () -> Unit) {
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                action()
            }
        })
    }

    private fun updateUrlBar(url: String) {
        Handler(Looper.getMainLooper()).postDelayed({
            val currentUrl = webView.url
            if (currentUrl != null && !currentUrl.contains("forcedownload=1") &&
                !currentUrl.contains("pluginfile.php")) {
                val displayUrl = currentUrl.removePrefix(moodleBaseUrl).removePrefix("/")
                urlBar.setText(displayUrl)
            } else if (currentUrl == null) {
                val displayUrl = url.removePrefix(moodleBaseUrl).removePrefix("/")
                urlBar.setText(displayUrl)
            }
        }, 200)
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
        showLoadingBar()
        webView.loadUrl(url)
    }

    private fun performSearch() {
        val query = searchBarMoodle.text.toString().trim()
        if (query.isEmpty()) return

        val searchType = spinnerSearchType.selectedItemPosition
        val currentUrl = webView.url ?: ""

        val isOnMyCoursesPage = currentUrl.contains("/my/") || currentUrl == "$moodleBaseUrl/my/"
        val isOnAllCoursesPage = currentUrl.contains("/course/index.php")

        val shouldNavigate = when (searchType) {
            0 -> !isOnMyCoursesPage  // My courses
            1 -> !isOnAllCoursesPage  // All courses
            else -> true
        }

        if (shouldNavigate) {
            val searchUrl = when (searchType) {
                0 -> "$moodleBaseUrl/my/"
                1 -> "$moodleBaseUrl/course/index.php"
                else -> "$moodleBaseUrl/my/"
            }

            loadUrlInBackground(searchUrl)

            waitForPageLoadComplete {
                performSearchOnCurrentPage(query, searchType)
            }
        } else {
            performSearchOnCurrentPage(query, searchType)
        }
    }

    private fun performSearchOnCurrentPage(query: String, searchType: Int) {
        resetSearchState()

        Handler(Looper.getMainLooper()).postDelayed({
            waitForSearchElementsReady(searchType) {
                injectSearchQuery(query, searchType)
            }
        }, 300)
    }

    private fun waitForSearchElementsReady(searchType: Int, maxAttempts: Int = 50, attempt: Int = 0, callback: () -> Unit) {
        if (attempt >= maxAttempts) {
            Toast.makeText(requireContext(), getString(R.string.moodle_search_failed), Toast.LENGTH_SHORT).show()
            callback()
            return
        }

        val jsCode = when (searchType) {
            0 -> {
                """
            (function() {
                var searchField = null;

                var selectors = [
                    'input[data-region="search-input"]',
                    'input[placeholder*="Search"]',
                    'input[placeholder*="search"]',
                    'input[placeholder*="Suchen"]',
                    '[data-region="view-selector"] input[type="text"]',
                    '.coursesearchbox input',
                    'input[name="search"]'
                ];
                
                for (var i = 0; i < selectors.length; i++) {
                    var candidates = document.querySelectorAll(selectors[i]);
                    for (var j = 0; j < candidates.length; j++) {
                        var candidate = candidates[j];
                        if (candidate.offsetParent !== null && !candidate.disabled) {
                            searchField = candidate;
                            break;
                        }
                    }
                    if (searchField) break;
                }
                
                return searchField !== null && searchField.offsetParent !== null && !searchField.disabled;
            })();
            """.trimIndent()
            }
            1 -> {
                """
            (function() {
                var searchField = document.querySelector('input[name="search"]');
                if (!searchField) {
                    var selectors = [
                        'input[placeholder*="Search"]',
                        'input[placeholder*="search"]',
                        'input[placeholder*="Suchen"]',
                        'form[action*="index.php"] input[type="text"]'
                    ];
                    for (var i = 0; i < selectors.length; i++) {
                        searchField = document.querySelector(selectors[i]);
                        if (searchField && searchField.offsetParent !== null) break;
                    }
                }
                return searchField !== null && searchField.offsetParent !== null && !searchField.disabled;
            })();
            """.trimIndent()
            }
            else -> {
                """
            (function() {
                return true;
            })();
            """.trimIndent()
            }
        }

        webView.evaluateJavascript(jsCode) { result ->
            if (result == "true") {
                Handler(Looper.getMainLooper()).postDelayed({
                    callback()
                }, 200)
            } else {
                Handler(Looper.getMainLooper()).postDelayed({
                    waitForSearchElementsReady(searchType, maxAttempts, attempt + 1, callback)
                }, 150)
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
        val jsCode = when (searchType) {
            0 -> {
                """
            (function() {
                var searchField = null;
                
                var selectors = [
                    'input[data-region="search-input"]',
                    'input[placeholder*="Search"]',
                    'input[placeholder*="search"]',
                    'input[placeholder*="Suchen"]',
                    '[data-region="view-selector"] input[type="text"]',
                    '.coursesearchbox input',
                    'input[name="search"]'
                ];
                
                for (var i = 0; i < selectors.length; i++) {
                    var candidates = document.querySelectorAll(selectors[i]);
                    for (var j = 0; j < candidates.length; j++) {
                        var candidate = candidates[j];
                        if (candidate.offsetParent !== null && !candidate.disabled) {
                            searchField = candidate;
                            break;
                        }
                    }
                    if (searchField) break;
                }
                
                if (searchField) {
                    searchField.focus();
                    searchField.value = '';
                    
                    var nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
                    nativeInputValueSetter.call(searchField, '$query');
                    
                    searchField.dispatchEvent(new Event('input', { bubbles: true }));
                    searchField.dispatchEvent(new Event('change', { bubbles: true }));
                    
                    setTimeout(function() {
                        searchField.dispatchEvent(new KeyboardEvent('keyup', { 
                            key: 'Enter',
                            keyCode: 13,
                            bubbles: true
                        }));
                    }, 100);
                    
                    return true;
                }
                return false;
            })();
            """.trimIndent()
            }
            1 -> {
                """
            (function() {
                var searchField = document.querySelector('input[name="search"]');
                if (!searchField) {
                    var selectors = [
                        'input[placeholder*="Search"]',
                        'input[placeholder*="search"]',
                        'input[placeholder*="Suchen"]',
                        'form[action*="index.php"] input[type="text"]'
                    ];
                    for (var i = 0; i < selectors.length; i++) {
                        searchField = document.querySelector(selectors[i]);
                        if (searchField && searchField.offsetParent !== null) break;
                    }
                }
                
                if (searchField) {
                    searchField.focus();
                    searchField.value = '$query';
                    searchField.dispatchEvent(new Event('input', { bubbles: true }));
                    
                    var submitButton = searchField.closest('form')?.querySelector('button[type="submit"]');
                    if (!submitButton) {
                        submitButton = searchField.closest('div')?.querySelector('button');
                    }
                    
                    if (submitButton) {
                        setTimeout(function() {
                            submitButton.click();
                        }, 150);
                    } else {
                        searchField.dispatchEvent(new KeyboardEvent('keypress', { key: 'Enter', bubbles: true }));
                    }
                    return true;
                }
                return false;
            })();
            """.trimIndent()
            }
            else -> "false"
        }

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
                        Toast.makeText(requireContext(), getString(R.string.moodle_session_term_cancel), Toast.LENGTH_SHORT).show()
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
        if (isLoginInProgress) {
            L.d("MoodleFragment", "Login already in progress, skipping checkAutoLogin")
            return
        }

        if (isLoginDialogShown) {
            L.d("MoodleFragment", "Login dialog already shown, skipping")
            return
        }

        if (consecutiveLoginFailures >= MAX_LOGIN_ATTEMPTS) {
            L.d("MoodleFragment", "Max login failures reached, skipping auto-login")
            return
        }

        if (sessionExpiredDetected && System.currentTimeMillis() - lastSessionExpiredTime < SESSION_RETRY_DELAY) {
            L.d("MoodleFragment", "In session expired cooldown, skipping auto-login")
            return
        }

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
                    consecutiveLoginFailures = 0
                    sessionExpiredDetected = false
                    loginSuccessConfirmed = true
                    lastSuccessfulLoginCheck = System.currentTimeMillis()
                    isLoginDialogShown = false
                    return@isUserLoggedIn
                }

                loginSuccessConfirmed = false

                checkSessionExpired { isSessionExpired ->
                    if (isSessionExpired) {
                        L.e("MoodleFragment", "Session expired detected")
                        sessionExpiredDetected = true
                        lastSessionExpiredTime = System.currentTimeMillis()
                        consecutiveLoginFailures++

                        activity?.runOnUiThread {
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.moodle_session_expired_retry_later),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        return@checkSessionExpired
                    }

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
                            if (!loginSuccessConfirmed && !isLoginDialogShown && !sessionExpiredDetected) {
                                showLoginDialog()
                            }
                        }, delay)
                    }
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
                    var errorText = errorDiv.textContent.trim();
                    return JSON.stringify({
                        message: errorText,
                        isSessionExpired: errorText.toLowerCase().includes('sitzung') || 
                                         errorText.toLowerCase().includes('session') || 
                                         errorText.toLowerCase().includes('expired') || 
                                         errorText.toLowerCase().includes('abgelaufen'),
                        isInvalidCredentials: errorText.toLowerCase().includes('ungültige') || 
                                            errorText.toLowerCase().includes('invalid')
                    });
                }
                return '';
            } catch(e) {
                return '';
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            val cleanResult = result.replace("\"", "").trim()
            if (cleanResult.isEmpty() || cleanResult == "null") {
                callback(null)
            } else {
                try {
                    val jsonResult = JSONObject(cleanResult)
                    callback(jsonResult.getString("message"))
                } catch (_: Exception) {
                    callback(cleanResult.ifEmpty { null })
                }
            }
        }
    }

    private fun checkSessionExpired(callback: (Boolean) -> Unit) {
        val jsCode = """
        (function() {
            try {
                var errorDiv = document.evaluate('/html/body/div[2]/div[2]/div/div/div/div/div/div/div/div[1]', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
                if (errorDiv && errorDiv.classList.contains('alert')) {
                    var errorText = errorDiv.textContent.trim().toLowerCase();
                    if (errorText.includes('sitzung') || errorText.includes('session') || 
                        errorText.includes('expired') || errorText.includes('abgelaufen')) {
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
            callback(result == "true")
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
        if (dontShowDialog && consecutiveLoginFailures < MAX_LOGIN_ATTEMPTS) {
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

        if (consecutiveLoginFailures >= MAX_LOGIN_ATTEMPTS) {
            cbSaveCredentials.isChecked = false
            cbDontShowAgain.isChecked = false
        }

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

                val usernameToShow = if (consecutiveLoginFailures >= MAX_LOGIN_ATTEMPTS) {
                    extractedUsername ?: ""
                } else {
                    extractedUsername ?: savedUsername
                }

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

                        consecutiveLoginFailures = 0
                        hasLoginFailed = false

                        if (cbSaveCredentials.isChecked && username.isNotEmpty() && password.isNotEmpty()) {
                            saveCredentials(username, password)
                        } else {
                            encryptedPrefs.edit { clear() }
                        }

                        if (cbDontShowAgain.isChecked) {
                            sharedPrefs.edit {
                                putBoolean("moodle_dont_show_login_dialog", true)
                            }
                        } else {
                            sharedPrefs.edit {
                                remove("moodle_dont_show_login_dialog")
                            }
                        }

                        if (username.isNotEmpty() && password.isNotEmpty()) {
                            fillLoginForm(username, password)
                        }
                    }
                    .setNegativeButton(getString(R.string.moodle_cancel)) { _, _ ->
                        isLoginDialogShown = false
                        loginRetryCount = 0
                        consecutiveLoginFailures = 0
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
        if (consecutiveLoginFailures >= MAX_LOGIN_ATTEMPTS) {
            L.d("MoodleFragment", "Skipping auto-login due to previous failures")
            return
        }

        val username = encryptedPrefs.getString("moodle_username", "") ?: ""
        val password = encryptedPrefs.getString("moodle_password", "") ?: ""

        if (username.isNotEmpty() && password.isNotEmpty()) {
            fillLoginForm(username, password)
        }
    }

    private fun fillLoginForm(username: String, password: String) {
        if (isLoginInProgress) {
            L.d("MoodleFragment", "Login already in progress, ignoring duplicate call")
            return
        }

        if (sessionExpiredDetected && System.currentTimeMillis() - lastSessionExpiredTime < SESSION_RETRY_DELAY) {
            L.d("MoodleFragment", "Skipping login - session expired cooldown active")
            Toast.makeText(requireContext(), getString(R.string.moodle_session_expired_wait), Toast.LENGTH_SHORT).show()
            return
        }

        if (consecutiveLoginFailures >= MAX_LOGIN_ATTEMPTS) {
            L.d("MoodleFragment", "Max failures reached - not attempting login")
            return
        }

        isLoginInProgress = true
        loginAttemptCount++
        L.d("MoodleFragment", "Starting login attempt #$loginAttemptCount")

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
                isLoginInProgress = false
                Toast.makeText(requireContext(), getString(R.string.moodle_login_form_not_found), Toast.LENGTH_SHORT).show()
                isLoginDialogShown = false
            } else {
                L.d("MoodleFragment", "Login form submitted, waiting for response...")
                Handler(Looper.getMainLooper()).postDelayed({
                    checkLoginResult()
                }, 1000)
            }
        }
    }

    private fun checkLoginResult() {
        L.d("MoodleFragment", "Checking login result for attempt #$loginAttemptCount")

        isUserLoggedIn { isLoggedIn ->
            if (isLoggedIn) {
                L.d("MoodleFragment", "Login successful!")
                isLoginInProgress = false
                loginAttemptCount = 0
                loginRetryCount = 0
                consecutiveLoginFailures = 0
                sessionExpiredDetected = false
                loginSuccessConfirmed = true
                lastSuccessfulLoginCheck = System.currentTimeMillis()
                isLoginDialogShown = false
                Handler(Looper.getMainLooper()).removeCallbacksAndMessages(null)
                return@isUserLoggedIn
            }

            val jsCode = """
            (function() {
                try {
                    var errorDiv = document.evaluate('/html/body/div[2]/div[2]/div/div/div/div/div/div/div/div[1]', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
                    if (errorDiv && errorDiv.textContent && errorDiv.textContent.trim() !== '') {
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

                if (errorMessage.isNotEmpty() && errorMessage != "null") {
                    L.e("MoodleFragment", "Login error detected: $errorMessage")

                    val isInvalidCredentials = errorMessage.contains("Ungültige", ignoreCase = true) ||
                            errorMessage.contains("Invalid", ignoreCase = true) ||
                            errorMessage.contains("falsch", ignoreCase = true)

                    val isSessionExpired = errorMessage.lowercase().let { msg ->
                        msg.contains("sitzung") || msg.contains("session") ||
                                msg.contains("expired") || msg.contains("abgelaufen")
                    }

                    if (isInvalidCredentials) {
                        L.e("MoodleFragment", "Invalid credentials - stopping all attempts")
                        isLoginInProgress = false
                        loginAttemptCount = 0
                        loginRetryCount = 0
                        consecutiveLoginFailures = MAX_LOGIN_ATTEMPTS
                        hasLoginFailed = true
                        isLoginDialogShown = false
                        loginSuccessConfirmed = false

                        encryptedPrefs.edit { clear() }
                        sharedPrefs.edit {
                            remove("moodle_dont_show_login_dialog")
                        }

                        Handler(Looper.getMainLooper()).postDelayed({
                            if (!isLoginDialogShown) {
                                showLoginDialog()
                            }
                        }, 500)

                    } else if (isSessionExpired) {
                        if (consecutiveLoginFailures >= 1) {
                            L.e("MoodleFragment", "Session expired on retry - entering cooldown")
                            isLoginInProgress = false
                            loginAttemptCount = 0
                            sessionExpiredDetected = true
                            lastSessionExpiredTime = System.currentTimeMillis()
                            hasLoginFailed = true
                            isLoginDialogShown = false

                            activity?.runOnUiThread {
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.moodle_session_expired_retry_later),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } else {
                            L.d("MoodleFragment", "Session expired - will retry once")
                            consecutiveLoginFailures = 1
                            isLoginInProgress = false

                            Handler(Looper.getMainLooper()).postDelayed({
                                val username = encryptedPrefs.getString("moodle_username", "") ?: ""
                                val password = encryptedPrefs.getString("moodle_password", "") ?: ""
                                if (username.isNotEmpty() && password.isNotEmpty()) {
                                    fillLoginForm(username, password)
                                }
                            }, 2000)
                        }
                    } else {
                        L.e("MoodleFragment", "Unknown error - stopping")
                        isLoginInProgress = false
                        loginAttemptCount = 0
                        consecutiveLoginFailures = MAX_LOGIN_ATTEMPTS
                        hasLoginFailed = true
                        isLoginDialogShown = false
                    }
                } else {
                    L.w("MoodleFragment", "No error message found, login status unclear")
                    isLoginInProgress = false
                    hasLoginFailed = true
                    isLoginDialogShown = false
                }
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
        try {
            webView.clearCache(true)
            webView.clearHistory()
            webView.clearFormData()

            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }

            WebStorage.getInstance().deleteAllData()

            context?.let { ctx ->
                ctx.cacheDir.deleteRecursively()

                File(ctx.cacheDir.parent, "app_webview").deleteRecursively()
                File(ctx.cacheDir.parent, "app_WebView").deleteRecursively()

                ctx.codeCacheDir.deleteRecursively()

                Toast.makeText(ctx, getString(R.string.moodle_cache_cleared), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            L.e("MoodleFragment", "Error clearing cache", e)
            Toast.makeText(requireContext(), getString(R.string.moodle_cache_fetch_failed), Toast.LENGTH_SHORT).show()
        }
    }

    fun clearMoodleData() {
        try {
            clearMoodleCache()

            context?.let { ctx ->
                val dataDir = File(ctx.applicationInfo.dataDir)

                File(dataDir, "app_webview").deleteRecursively()
                File(dataDir, "app_WebView").deleteRecursively()

                File(dataDir, "databases").listFiles()?.filter {
                    it.name.contains("webview", ignoreCase = true) ||
                            it.name.contains("WebView", ignoreCase = true)
                }?.forEach { it.deleteRecursively() }

                File(dataDir, "shared_prefs").listFiles()?.filter {
                    it.name.contains("webview", ignoreCase = true) ||
                            it.name.contains("WebView", ignoreCase = true)
                }?.forEach { it.delete() }
            }

            encryptedPrefs.edit { clear() }

            sharedPrefs.edit {
                remove("moodle_dont_show_login_dialog")
                remove("moodle_auto_dismiss_confirm")
                remove("moodle_debug_mode")
            }

            Toast.makeText(requireContext(), getString(R.string.moodle_data_cleared), Toast.LENGTH_SHORT).show()

            loadUrlInBackground(loginUrl)

        } catch (e: Exception) {
            L.e("MoodleFragment", "Error clearing data", e)
            Toast.makeText(requireContext(), getString(R.string.moodle_clear_data_failed), Toast.LENGTH_SHORT).show()
        }
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

    private fun openInExternalBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(requireContext(), getString(R.string.moodle_browser_open_failed), Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(requireContext(), getString(R.string.moodle_calendar_export_no_session), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            L.e("MoodleFragment", "Error in calendar refresh", e)
            activity?.runOnUiThread {
                Toast.makeText(requireContext(), getString(R.string.moodle_calendar_export_failed), Toast.LENGTH_SHORT).show()
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
                val connection1 = URL(exportUrl).openConnection() as HttpURLConnection

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
                        Toast.makeText(requireContext(), getString(R.string.moodle_calendar_export_page_error), Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(requireContext(), getString(R.string.moodle_calendar_export_login_required), Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }

                // Extract session key using regex
                val sesskeyPattern = """name="sesskey"[^>]*value="([^"]+)"""".toRegex()
                val sesskeyMatch = sesskeyPattern.find(exportPageContent)

                if (sesskeyMatch == null) {
                    L.e("MoodleFragment", "Could not find session key in export page")
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), getString(R.string.moodle_calendar_export_no_key), Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }

                val sesskey = sesskeyMatch.groupValues[1]
                L.d("MoodleFragment", "Extracted session key: ${sesskey.take(10)}...")

                // Step 2: Submit the form with proper form data
                L.d("MoodleFragment", "Step 2 - Submitting export form")

                val connection2 = URL(exportUrl).openConnection() as HttpURLConnection

                // Prepare form data
                val formData = buildString {
                    append("sesskey=").append(URLEncoder.encode(sesskey, "UTF-8")).append("&")
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

                        val connection3 = URL(finalUrl).openConnection() as HttpURLConnection
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
                                    Toast.makeText(requireContext(), getString(R.string.moodle_calendar_invalid_format), Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            L.e("MoodleFragment", "Failed to download calendar: $response3Code")
                            connection3.disconnect()
                            activity?.runOnUiThread {
                                Toast.makeText(requireContext(), getString(R.string.moodle_calendar_download_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        L.e("MoodleFragment", "Unexpected redirect location: $redirectLocation")
                        activity?.runOnUiThread {
                            Toast.makeText(requireContext(), getString(R.string.moodle_calendar_export_redirect), Toast.LENGTH_SHORT).show()
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
                            Toast.makeText(requireContext(), getString(R.string.moodle_calendar_export_unexpected_response), Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    L.e("MoodleFragment", "Form submission failed: $response2Code")
                    connection2.disconnect()
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), getString(R.string.moodle_calendar_export_form_error), Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                L.e("MoodleFragment", "Error in HTTP calendar export", e)
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), getString(R.string.moodle_calendar_export_error_format, e.message), Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(requireContext(), getString(R.string.moodle_calendar_refresh_success, events.size), Toast.LENGTH_SHORT).show()
                }

                L.d("MoodleFragment", "Successfully imported ${events.size} calendar events")
            }
        } catch (e: Exception) {
            L.e("MoodleFragment", "Error parsing calendar data", e)
            activity?.runOnUiThread {
                Toast.makeText(requireContext(), getString(R.string.moodle_calendar_refresh_failed), Toast.LENGTH_SHORT).show()
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
        savePinnedTabs()
        tabs.forEach { it.thumbnail?.recycle() }
        pdfRenderer?.close()
        backgroundExecutor.shutdown()
        cleanupRefreshIndicator()
        scrollEndCheckRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
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
                        Toast.makeText(requireContext(), getString(R.string.moodle_calendar_export_test_start), Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(requireContext(), getString(R.string.moodle_debug_mode ,(!currentDebugMode).toString()), Toast.LENGTH_SHORT).show()

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
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scrollView.addView(textView)

        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ ->
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(title, content)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), getString(R.string.moodle_copied_to_clipboard), Toast.LENGTH_SHORT).show()
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

        val currentUrl = webView.url ?: ""
        val isOnMyCoursesPage = currentUrl.contains("/my/") || currentUrl == "$moodleBaseUrl/my/"

        if (isOnMyCoursesPage) {
            updateSearchProgress(20, getString(R.string.moodle_page_loaded_resetting))
            performMoodleEntrySearch(category, summary, entryId)
        } else {
            loadUrlInBackground("$moodleBaseUrl/my/")

            waitForPageLoadComplete {
                if (searchCancelled) return@waitForPageLoadComplete
                updateSearchProgress(20, getString(R.string.moodle_page_loaded_resetting))
                performMoodleEntrySearch(category, summary, entryId)
            }
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
                        break;
                    }
                }
            }
            
            if (!searchField) {
                return false;
            }

            searchField.disabled = false;
            searchField.readOnly = false;
            searchField.value = '';
            searchField.focus();

            var nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
            nativeInputValueSetter.call(searchField, '$searchQuery');

            var inputEvent = new Event('input', { bubbles: true });
            searchField.dispatchEvent(inputEvent);

            var changeEvent = new Event('change', { bubbles: true });
            searchField.dispatchEvent(changeEvent);
            
            setTimeout(function() {
                var keyupEvent = new KeyboardEvent('keyup', { 
                    key: 'Enter',
                    keyCode: 13,
                    bubbles: true
                });
                searchField.dispatchEvent(keyupEvent);
            }, 150);
            
            return true;
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            val success = result == "true"
            L.d("MoodleFragment", "Enhanced search field fill result: $success")

            if (success) {
                Handler(Looper.getMainLooper()).postDelayed({
                    callback(true)
                }, 500)
            } else {
                callback(false)
            }
        }
    }

    private fun waitForSearchFieldReady(maxAttempts: Int = 40, attempt: Int = 0, callback: (Boolean) -> Unit) {
        if (attempt >= maxAttempts) {
            L.w("MoodleFragment", "Search field ready timeout after $maxAttempts attempts")
            callback(false)
            return
        }

        val jsCode = """
        (function() {
            if (document.readyState !== 'complete') {
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
                            break;
                        }
                    }
                    if (searchField) break;
                }
            }
            
            if (!searchField) {
                return false;
            }

            var isReady = searchField.offsetParent !== null && 
                         !searchField.disabled && 
                         !searchField.readOnly &&
                         searchField.style.display !== 'none';
            
            return isReady;
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            if (result == "true") {
                L.d("MoodleFragment", "Search field is ready after ${attempt + 1} attempts")
                Handler(Looper.getMainLooper()).postDelayed({
                    callback(true)
                }, 100)
            } else {
                Handler(Looper.getMainLooper()).postDelayed({
                    waitForSearchFieldReady(maxAttempts, attempt + 1, callback)
                }, 100)
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
                                    searchForSpecificEntry(summary)
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

    private fun searchForSpecificEntry(summary: String) {
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

    private fun createRefreshIndicator() {
        refreshContainer?.visibility = View.VISIBLE
        refreshIndicator?.apply {
            alpha = 0f
            scaleX = 0.3f
            scaleY = 0.3f
            rotation = 0f
            clearAnimation()
        }
    }

    private fun updateRefreshIndicator(distance: Float) {
        refreshContainer?.let { container ->
            refreshIndicator?.let { icon ->
                val progress = (distance / pullThresholdComplete).coerceAtMost(1.5f)
                val triggerProgress = (distance / pullThresholdComplete).coerceAtMost(1f)

                val maxContainerHeight = (80 * resources.displayMetrics.density).toInt()
                val containerHeight = ((distance * 0.3f).toInt()).coerceIn(0, maxContainerHeight)

                val layoutParams = container.layoutParams
                layoutParams.height = containerHeight
                container.layoutParams = layoutParams

                icon.alpha = (progress * 0.8f).coerceAtMost(1f)
                icon.rotation = progress * 180f

                val scale = (0.3f + (progress * 0.7f)).coerceAtMost(1f)
                icon.scaleX = scale
                icon.scaleY = scale

                val iconLayoutParams = icon.layoutParams as FrameLayout.LayoutParams
                iconLayoutParams.topMargin = (containerHeight * 0.2f).toInt().coerceAtLeast(8)
                icon.layoutParams = iconLayoutParams

                if (triggerProgress >= 1f) {
                    icon.setColorFilter(
                        requireContext().getThemeColor(R.attr.settingsColorPrimary),
                        PorterDuff.Mode.SRC_IN
                    )
                } else {
                    icon.setColorFilter(
                        requireContext().getThemeColor(R.attr.textSecondaryColor),
                        PorterDuff.Mode.SRC_IN
                    )
                }
            }
        }
    }

    private fun triggerWebViewRefresh() {
        refreshIndicator?.let { icon ->
            icon.clearAnimation()
            icon.alpha = 1f
            icon.setColorFilter(
                requireContext().getThemeColor(R.attr.settingsColorPrimary),
                PorterDuff.Mode.SRC_IN
            )

            val rotateAnimation = RotateAnimation(
                0f, 360f,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f
            ).apply {
                duration = 800
                repeatCount = Animation.INFINITE
                interpolator = LinearInterpolator()
            }
            icon.startAnimation(rotateAnimation)
        }

        webView.reload()
        Toast.makeText(requireContext(), getString(R.string.moodle_refreshing), Toast.LENGTH_SHORT).show()

        Handler(Looper.getMainLooper()).postDelayed({
            animateRefreshIndicatorAway()
        }, 600)
    }

    private fun animateRefreshIndicatorAway() {
        refreshContainer?.let { container ->
            val currentHeight = container.layoutParams.height.coerceAtLeast(1)

            val heightAnimator = ValueAnimator.ofInt(currentHeight, 0)
            heightAnimator.duration = 300
            heightAnimator.interpolator = DecelerateInterpolator()

            heightAnimator.addUpdateListener { animation ->
                val animatedValue = animation.animatedValue as Int
                val layoutParams = container.layoutParams
                layoutParams.height = animatedValue
                container.layoutParams = layoutParams

                val progress = if (currentHeight > 0) {
                    animatedValue.toFloat() / currentHeight.toFloat()
                } else {
                    0f
                }
                refreshIndicator?.alpha = progress.coerceAtLeast(0f)
            }

            heightAnimator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    cleanupRefreshIndicator()
                }

                override fun onAnimationCancel(animation: Animator) {
                    cleanupRefreshIndicator()
                }
            })

            heightAnimator.start()
        } ?: cleanupRefreshIndicator()
    }

    private fun cleanupRefreshIndicator() {
        try {
            refreshIndicator?.clearAnimation()
            refreshIndicator?.clearColorFilter()
            refreshIndicator?.alpha = 0f
            refreshIndicator?.scaleX = 0.3f
            refreshIndicator?.scaleY = 0.3f
            refreshIndicator?.rotation = 0f

            refreshContainer?.let { container ->
                container.visibility = View.GONE
                val layoutParams = container.layoutParams
                layoutParams.height = 0
                container.layoutParams = layoutParams
            }
        } catch (e: Exception) {
            L.e("MoodleFragment", "Error cleaning up refresh indicator", e)
        }
    }

    private fun showExtendedHeaderWithAnimation() {
        if (extendedHeaderLayout.isVisible || searchBarVisible || isHeaderAnimating) {
            return
        }

        val animationsEnabled = Settings.Global.getFloat(
            requireContext().contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f
        ) != 0.0f

        if (animationsEnabled) {
            isHeaderAnimating = true
            extendedHeaderLayout.visibility = View.VISIBLE

            extendedHeaderLayout.measure(
                View.MeasureSpec.makeMeasureSpec((extendedHeaderLayout.parent as View).width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val targetHeight = extendedHeaderLayout.measuredHeight

            val layoutParams = extendedHeaderLayout.layoutParams
            layoutParams.height = 0
            extendedHeaderLayout.layoutParams = layoutParams

            ValueAnimator.ofInt(0, targetHeight).apply {
                duration = 250
                interpolator = DecelerateInterpolator()

                addUpdateListener { animator ->
                    val animatedHeight = animator.animatedValue as Int
                    layoutParams.height = animatedHeight
                    extendedHeaderLayout.layoutParams = layoutParams
                }

                doOnEnd {
                    layoutParams.height = targetHeight
                    extendedHeaderLayout.layoutParams = layoutParams
                    isHeaderAnimating = false
                }

                start()
            }
        } else {
            extendedHeaderLayout.visibility = View.VISIBLE
            val layoutParams = extendedHeaderLayout.layoutParams
            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            extendedHeaderLayout.layoutParams = layoutParams
            extendedHeaderLayout.requestLayout()
        }
    }

    private fun hideExtendedHeaderWithAnimation() {
        if (extendedHeaderLayout.isGone || isHeaderAnimating) {
            return
        }

        val animationsEnabled = Settings.Global.getFloat(
            requireContext().contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f
        ) != 0.0f

        if (animationsEnabled) {
            isHeaderAnimating = true

            val currentHeight = if (extendedHeaderLayout.height <= 0 || extendedHeaderLayout.layoutParams.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
                extendedHeaderLayout.measure(
                    View.MeasureSpec.makeMeasureSpec((extendedHeaderLayout.parent as View).width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                extendedHeaderLayout.measuredHeight
            } else {
                extendedHeaderLayout.height
            }

            val layoutParams = extendedHeaderLayout.layoutParams
            layoutParams.height = currentHeight
            extendedHeaderLayout.layoutParams = layoutParams

            ValueAnimator.ofInt(currentHeight, 0).apply {
                duration = 250
                interpolator = AccelerateInterpolator()

                addUpdateListener { animator ->
                    val animatedHeight = animator.animatedValue as Int
                    layoutParams.height = animatedHeight
                    extendedHeaderLayout.layoutParams = layoutParams
                }

                doOnEnd {
                    extendedHeaderLayout.visibility = View.GONE
                    layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    extendedHeaderLayout.layoutParams = layoutParams
                    isHeaderAnimating = false
                }

                start()
            }
        } else {
            extendedHeaderLayout.visibility = View.GONE
            val layoutParams = extendedHeaderLayout.layoutParams
            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            extendedHeaderLayout.layoutParams = layoutParams
            extendedHeaderLayout.requestLayout()
        }
    }

    private fun checkScrollEndedAtTop() {
        if (!isUserScrolling && !isHeaderAnimating) {
            val currentScrollY = webView.scrollY
            val shouldBeAtTop = currentScrollY <= scrollThreshold

            isAtTop = shouldBeAtTop

            if (shouldBeAtTop && !searchBarVisible && extendedHeaderLayout.visibility != View.VISIBLE) {
                showExtendedHeaderWithAnimation()
            } else if (!shouldBeAtTop && extendedHeaderLayout.isVisible) {
                hideExtendedHeaderWithAnimation()
            }
        }
    }

    private fun showBackButtonMenu() {
        val popup = PopupMenu(requireContext(), btnBack)

        popup.menu.add(0, 1, 0, getString(R.string.moodle_forward)).apply {
            setIcon(R.drawable.ic_arrow_forward)
            isEnabled = webView.canGoForward()
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
                    if (webView.canGoForward()) {
                        webView.goForward()
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showGlobeButtonMenu() {
        val currentUrl = webView.url
        if (currentUrl.isNullOrBlank()) {
            Toast.makeText(requireContext(), getString(R.string.moodle_no_url), Toast.LENGTH_SHORT).show()
            return
        }

        val popup = PopupMenu(requireContext(), btnOpenInBrowser)

        popup.menu.add(0, 1, 0, getString(R.string.moodle_copy_url)).apply {
            setIcon(R.drawable.ic_import_clipboard)
        }
        popup.menu.add(0, 2, 0, "Open in new tab").apply {
            setIcon(R.drawable.ic_tab_background)
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
                    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText(getString(R.string.moodle_url), currentUrl)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(requireContext(), getString(R.string.moodle_url_copied), Toast.LENGTH_SHORT).show()
                    true
                }
                2 -> {
                    createNewTab(currentUrl)
                    Toast.makeText(requireContext(), getString(R.string.moodle_tab_opened_in_new_tab), Toast.LENGTH_SHORT).show() // strings.xml
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun parseDocxToText(inputStream: InputStream): String? {
        try {
            val zipInputStream = ZipInputStream(inputStream)
            var entry: ZipEntry?
            val textBuilder = StringBuilder()

            while (zipInputStream.nextEntry.also { entry = it } != null) {
                if (entry?.name == "word/document.xml") {
                    val xmlContent = zipInputStream.bufferedReader().readText()

                    val textPattern = "<w:t[^>]*>([^<]+)</w:t>".toRegex()
                    val matches = textPattern.findAll(xmlContent)

                    matches.forEach { match ->
                        textBuilder.append(match.groupValues[1])
                    }

                    val paragraphPattern = "</w:p>".toRegex()
                    val paragraphs = xmlContent.split(paragraphPattern)

                    textBuilder.clear()
                    paragraphs.forEach { paragraph ->
                        val textMatches = textPattern.findAll(paragraph)
                        val paragraphText = textMatches.joinToString("") { it.groupValues[1] }
                        if (paragraphText.isNotEmpty()) {
                            textBuilder.append(paragraphText).append("\n")
                        }
                    }

                    break
                }
            }

            zipInputStream.close()
            return textBuilder.toString()
        } catch (e: Exception) {
            L.e("MoodleFragment", "Error parsing DOCX", e)
            return null
        }
    }

    private fun showLinkContextMenu(url: String) {
        val startTime = System.currentTimeMillis()

        analyzeLinkCapabilities(url) { linkInfo ->
            val elapsed = System.currentTimeMillis() - startTime
            val delay = if (elapsed < 150) 150 - elapsed else 0

            Handler(Looper.getMainLooper()).postDelayed({
                activity?.runOnUiThread {
                    showLinkOptionsDialog(url, linkInfo)
                }
            }, delay)
        }
    }

    private data class LinkInfo(
        val isDownloadable: Boolean,
        val isCopyable: Boolean,
        val fileType: String? = null,
        val isImage: Boolean = false,
        val directDownloadUrl: String? = null
    )

    private fun analyzeLinkCapabilities(url: String, callback: (LinkInfo) -> Unit) {
        val jsCode = """
        (function() {
            try {
                var links = document.querySelectorAll('a[href="$url"]');
                var linkInfo = {
                    isDownloadable: false,
                    isCopyable: false,
                    fileType: null,
                    isImage: false,
                    directDownloadUrl: null
                };
                
                for (var i = 0; i < links.length; i++) {
                    var link = links[i];

                    var accessHideSpan = link.querySelector('span.accesshide');
                    if (accessHideSpan) {
                        var text = accessHideSpan.textContent.trim().toLowerCase();
                        if (text.includes('datei') || text.includes('file')) {
                            linkInfo.isDownloadable = true;
                        }
                    }

                    var container = link.closest('.activity-item, .activityinstance');
                    if (container) {
                        var activityIcon = container.querySelector('img.activityicon, img[data-region="activity-icon"]');
                        if (activityIcon && activityIcon.src) {
                            var imgSrc = activityIcon.src.toLowerCase();

                            if (imgSrc.includes('/f/image?')) {
                                linkInfo.isImage = true;
                                linkInfo.isDownloadable = false;
                            }
                            else if (imgSrc.includes('/f/pdf')) {
                                linkInfo.isCopyable = true;
                                linkInfo.fileType = 'pdf';
                            }
                            else if (imgSrc.includes('/f/document') || imgSrc.includes('/f/docx')) {
                                linkInfo.isCopyable = true;
                                linkInfo.fileType = 'docx';
                            }
                        }
                    }
                    
                    if (linkInfo.isDownloadable || linkInfo.isCopyable) {
                        break;
                    }
                }
                
                return JSON.stringify(linkInfo);
            } catch(e) {
                return JSON.stringify({
                    isDownloadable: false,
                    isCopyable: false,
                    fileType: null,
                    isImage: false,
                    directDownloadUrl: null,
                    error: e.message
                });
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            try {
                val cleanResult = result.replace("\\\"", "\"").trim('"')
                val jsonObject = JSONObject(cleanResult)

                val linkInfo = LinkInfo(
                    isDownloadable = jsonObject.optBoolean("isDownloadable", false),
                    isCopyable = jsonObject.optBoolean("isCopyable", false),
                    fileType = jsonObject.optString("fileType").takeIf { it.isNotEmpty() },
                    isImage = jsonObject.optBoolean("isImage", false)
                )

                L.d("MoodleFragment", "Link analysis - Download: ${linkInfo.isDownloadable}, Copy: ${linkInfo.isCopyable}, Type: ${linkInfo.fileType}, Image: ${linkInfo.isImage}")
                callback(linkInfo)
            } catch (e: Exception) {
                L.e("MoodleFragment", "Error parsing link info", e)
                callback(LinkInfo(
                    isDownloadable = isDownloadableFile(url),
                    isCopyable = url.endsWith(".pdf", ignoreCase = true) || url.endsWith(".docx", ignoreCase = true),
                    isImage = false
                ))
            }
        }
    }

    private fun showLinkOptionsDialog(url: String, linkInfo: LinkInfo) {
        val options = mutableListOf<Pair<String, Int>>()
        val actions = mutableListOf<() -> Unit>()

        val cookies = CookieManager.getInstance().getCookie(url)

        options.add(Pair("Open in new tab", R.drawable.ic_tab_background))
        actions.add {
            createNewTab(url)
            Toast.makeText(requireContext(), getString(R.string.moodle_tab_opened_in_new_tab), Toast.LENGTH_SHORT).show()
        }

        // download to user device
        if (linkInfo.isDownloadable && !linkInfo.isImage) {
            options.add(Pair(getString(R.string.moodle_download), R.drawable.ic_download))
            actions.add {
                if (url.contains("/mod/resource/view.php")) {
                    resolveDownloadUrl(url, cookies)
                } else {
                    handleInterceptedDownload(url, cookies)
                }
            }
        }

        // open in user browser
        options.add(Pair(getString(R.string.moodle_open_browser), R.drawable.ic_globe))
        actions.add { openInExternalBrowser(url) }

        // copy text (pdf/docx)
        if (linkInfo.isCopyable && linkInfo.fileType != null) {
            options.add(Pair(getString(R.string.moodle_copy_text_format, linkInfo.fileType.uppercase()), R.drawable.ic_copy_text))
            actions.add {
                val progressDialog = AlertDialog.Builder(requireContext())
                    .setMessage(getString(R.string.moodle_parsing_document))
                    .setCancelable(false)
                    .show()

                if (url.contains("/mod/resource/view.php")) {
                    resolveAndParseDocument(url, cookies, progressDialog)
                } else {
                    downloadAndParseDocumentWithCookies(url, cookies) { text ->
                        activity?.runOnUiThread {
                            progressDialog.dismiss()
                            if (text != null) {
                                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText(getString(R.string.moodle_document_text), text)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(requireContext(), getString(R.string.moodle_text_copied), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(requireContext(), getString(R.string.moodle_parse_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }

        // copy url
        options.add(Pair(getString(R.string.moodle_copy_url), R.drawable.ic_copy_url))
        actions.add {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(getString(R.string.moodle_url), url)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), getString(R.string.moodle_url_copied), Toast.LENGTH_SHORT).show()
        }

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
                view.compoundDrawablePadding = (16 * resources.displayMetrics.density).toInt()
                view.setPadding(
                    (16 * resources.displayMetrics.density).toInt(),
                    (12 * resources.displayMetrics.density).toInt(),
                    (16 * resources.displayMetrics.density).toInt(),
                    (12 * resources.displayMetrics.density).toInt()
                )
                return view
            }
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.moodle_link_options))
            .setAdapter(adapter) { _, which ->
                if (which in actions.indices) {
                    actions[which].invoke()
                }
            }
            .setNegativeButton(getString(R.string.moodle_cancel), null)
            .show()

        val buttonColor = requireContext().getThemeColor(R.attr.dialogSectionButtonColor)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
    }

    private fun isOnLoginPage(): Boolean {
        val currentUrl = webView.url ?: ""
        return currentUrl == loginUrl || currentUrl.contains("login/index.php")
    }

    private fun initializeTabSystem() {
        isCompactTabLayout = sharedPrefs.getBoolean("moodle_tab_layout_compact", false)

        tabs.clear()

        val currentUrl = webView.url ?: loginUrl
        val currentTitle = webView.title ?: "Moodle"

        val defaultTab = TabInfo(
            id = "default_tab",
            url = currentUrl,
            title = currentTitle,
            isPinned = false,
            isDefault = true,
            thumbnail = captureWebViewThumbnail()
        )
        tabs.add(defaultTab)
        currentTabIndex = 0

        loadSavedTabs()

        L.d("MoodleFragment", "Initialized tab system with ${tabs.size} tabs")
    }

    private fun savePinnedTabs() {
        val pinnedTabs = tabs.filter { it.isPinned && !it.isDefault }
        val jsonArray = JSONArray()

        pinnedTabs.forEach { tab ->
            val tabJson = JSONObject().apply {
                put("id", tab.id)
                put("url", tab.url)
                put("title", tab.title)
                put("isPinned", true)
                put("createdAt", tab.createdAt)

                tab.thumbnail?.let { bitmap ->
                    try {
                        val outputStream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
                        val base64 = Base64.encodeToString(
                            outputStream.toByteArray(),
                            Base64.DEFAULT
                        )
                        put("thumbnail", base64)
                    } catch (e: Exception) {
                        L.e("MoodleFragment", "Error saving thumbnail", e)
                    }
                }
            }
            jsonArray.put(tabJson)
        }

        sharedPrefs.edit {
            putString("saved_tabs", jsonArray.toString())
        }
        L.d("MoodleFragment", "Saved ${pinnedTabs.size} pinned tabs (excluding default)")
    }

    private fun loadSavedTabs() {
        val savedTabsJson = sharedPrefs.getString("saved_tabs", null) ?: return

        try {
            val jsonArray = JSONArray(savedTabsJson)
            L.d("MoodleFragment", "Loading ${jsonArray.length()} saved tabs")

            val loadedTabs = mutableListOf<TabInfo>()

            for (i in 0 until jsonArray.length()) {
                val tabJson = jsonArray.getJSONObject(i)
                val tabId = tabJson.getString("id")

                if (tabId == "default_tab") continue

                if (!tabJson.optBoolean("isPinned", false)) continue

                val thumbnail = try {
                    val base64 = tabJson.optString("thumbnail", "")
                    if (base64.isNotEmpty()) {
                        val decodedBytes = Base64.decode(base64, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                    } else null
                } catch (e: Exception) {
                    L.e("MoodleFragment", "Error decoding thumbnail", e)
                    null
                }

                loadedTabs.add(TabInfo(
                    id = tabId,
                    url = tabJson.getString("url"),
                    title = tabJson.getString("title"),
                    isPinned = true,
                    isDefault = false,
                    thumbnail = thumbnail,
                    createdAt = tabJson.optLong("createdAt", System.currentTimeMillis())
                ))
            }

            tabs.addAll(loadedTabs)
            L.d("MoodleFragment", "Loaded ${loadedTabs.size} pinned tabs")
        } catch (e: Exception) {
            L.e("MoodleFragment", "Error loading saved tabs", e)
        }
    }

    private fun captureWebViewThumbnail(): Bitmap? {
        try {
            val bitmap = createBitmap(webView.width, webView.height)
            val canvas = Canvas(bitmap)
            webView.draw(canvas)

            val scaledBitmap = bitmap.scale(bitmap.width / 4, bitmap.height / 4)
            bitmap.recycle()
            return scaledBitmap
        } catch (e: Exception) {
            L.e("MoodleFragment", "Error capturing thumbnail", e)
            return null
        }
    }

    private fun saveCurrentTabState() {
        if (currentTabIndex in tabs.indices) {
            val currentTab = tabs[currentTabIndex]
            val bundle = Bundle()
            webView.saveState(bundle)

            tabs[currentTabIndex] = currentTab.copy(
                url = webView.url ?: currentTab.url,
                title = webView.title ?: currentTab.title,
                thumbnail = captureWebViewThumbnail(),
                webViewState = bundle
            )

            savePinnedTabs()
        }
    }

    private fun switchToTab(index: Int) {
        if (index !in tabs.indices || index == currentTabIndex) return

        saveCurrentTabState()
        currentTabIndex = index

        val tab = tabs[index]

        if (tab.webViewState != null) {
            webView.restoreState(tab.webViewState)
        } else {
            webView.loadUrl(tab.url)
        }

        updateUIState()
    }

    private fun refreshTabViewLayout() {
        tabOverlayView?.let { overlayView ->
            setupTabOverlayControls(overlayView)

            tabRecyclerView = overlayView.findViewById(R.id.tabRecyclerView)
            setupTabRecyclerView(overlayView)
        }
    }

    private fun createNewTab(url: String = "$moodleBaseUrl/my/") {
        if (tabs.size >= MAX_TABS) {
            Toast.makeText(requireContext(), getString(R.string.moodle_max_tabs_reached), Toast.LENGTH_SHORT).show()
            return
        }

        saveCurrentTabState()

        val newTab = TabInfo(
            url = url,
            title = "New Tab"
        )
        tabs.add(newTab)
        currentTabIndex = tabs.size - 1

        webView.loadUrl(url)
        updateUIState()

        if (isTabViewVisible) {
            refreshTabViewLayout()
        }

        hideTabOverlay()
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return

        if (tabs.size == 1) {
            Toast.makeText(requireContext(), getString(R.string.moodle_cannot_close_last_tab), Toast.LENGTH_SHORT).show()
            return
        }

        tabs[index].thumbnail?.recycle()
        tabs.removeAt(index)

        if (currentTabIndex >= tabs.size) {
            currentTabIndex = tabs.size - 1
        } else if (index <= currentTabIndex && currentTabIndex > 0) {
            currentTabIndex--
        }

        switchToTab(currentTabIndex)
        savePinnedTabs()

        if (isTabViewVisible) {
            refreshTabViewLayout()
        }
    }

    private fun clearAllTabs() {
        tabs.forEach { it.thumbnail?.recycle() }
        tabs.clear()

        tabs.add(TabInfo(url = "$moodleBaseUrl/my/", title = "Dashboard"))
        currentTabIndex = 0
        webView.loadUrl("$moodleBaseUrl/my/")

        Toast.makeText(requireContext(), getString(R.string.moodle_tabs_cleared), Toast.LENGTH_SHORT).show()
    }

    private fun showTabOverlay() {
        L.d("MoodleFragment", "showTabOverlay called. Current tabs: ${tabs.size}, compact=$isCompactTabLayout")

        if (isTabViewVisible) {
            hideTabOverlay()
            return
        }

        if (currentTabIndex in tabs.indices) {
            val currentTab = tabs[currentTabIndex]
            tabs[currentTabIndex] = currentTab.copy(
                url = webView.url ?: currentTab.url,
                title = webView.title ?: currentTab.title,
                thumbnail = captureWebViewThumbnail()
            )
        } else if (tabs.isEmpty()) {
            val defaultTab = TabInfo(
                id = "default_tab",
                url = webView.url ?: loginUrl,
                title = webView.title ?: "Moodle",
                isPinned = false,
                isDefault = true,
                thumbnail = captureWebViewThumbnail()
            )
            tabs.add(defaultTab)
            currentTabIndex = 0
        }

        isTabViewVisible = true
        updateTabButtonIcon()

        val overlayView = layoutInflater.inflate(R.layout.overlay_tab_view, null)
        tabOverlayView = overlayView

        val rootView = view as ViewGroup
        rootView.addView(overlayView)

        setupTabRecyclerView(overlayView)
        setupTabOverlayControls(overlayView)

        overlayView.alpha = 0f
        overlayView.animate()
            .alpha(1f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()

        L.d("MoodleFragment", "Tab overlay shown with ${tabs.size} tabs")
    }

    override fun onPause() {
        super.onPause()
        saveCurrentTabState()
        savePinnedTabs()
        L.d("MoodleFragment", "onPause - saved tabs")
    }

    override fun onStop() {
        super.onStop()
        savePinnedTabs()
        L.d("MoodleFragment", "onStop - saved tabs")
    }

    private fun setupTabOverlayControls(overlayView: View) {
        val btnClearAll = overlayView.findViewById<ImageButton>(R.id.btnClearAllTabs)
        val btnCloseOverlay = overlayView.findViewById<ImageButton>(R.id.btnCloseTabOverlay)
        val layoutToggleHandle = overlayView.findViewById<View>(R.id.layoutToggleHandle)
        val tabHeaderLayout = overlayView.findViewById<LinearLayout>(R.id.tabHeaderLayout)

        btnClearAll.setOnClickListener {
            showClearAllTabsDialog()
        }

        btnCloseOverlay.setOnClickListener {
            hideTabOverlay()
        }

        val density = resources.displayMetrics.density

        if (isCompactTabLayout) {
            tabHeaderLayout?.setPadding(
                (8 * density).toInt(),
                (2 * density).toInt(),
                (8 * density).toInt(),
                (2 * density).toInt()
            )

            val compactButtonSize = (36 * density).toInt()
            btnClearAll.layoutParams?.apply {
                width = compactButtonSize
                height = compactButtonSize
            }
            btnCloseOverlay.layoutParams?.apply {
                width = compactButtonSize
                height = compactButtonSize
            }

            layoutToggleHandle.layoutParams?.height = (24 * density).toInt()

            (layoutToggleHandle.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
                topMargin = (8 * density).toInt()
                bottomMargin = 0
            }
        } else {
            // Normal view - standard spacing
            tabHeaderLayout?.setPadding(
                (16 * density).toInt(),
                (8 * density).toInt(),
                (16 * density).toInt(),
                (12 * density).toInt()
            )

            val normalButtonSize = (40 * density).toInt()
            btnClearAll.layoutParams?.apply {
                width = normalButtonSize
                height = normalButtonSize
            }
            btnCloseOverlay.layoutParams?.apply {
                width = normalButtonSize
                height = normalButtonSize
            }

            layoutToggleHandle.layoutParams?.height = (28 * density).toInt()

            (layoutToggleHandle.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
                topMargin = (8 * density).toInt()
                bottomMargin = (4 * density).toInt()
            }
        }

        btnClearAll.requestLayout()
        btnCloseOverlay.requestLayout()
        layoutToggleHandle.requestLayout()
        tabHeaderLayout?.requestLayout()

        setupLayoutToggle(layoutToggleHandle, overlayView)
    }

    private fun setupLayoutToggle(handle: View, overlayView: View) {
        val handleIcon = handle.findViewById<ImageView>(R.id.handleIcon)

        var startY = 0f
        var isDragging = false
        var overlayHeight: Int
        val density = resources.displayMetrics.density
        val minHeight = (200 * density).toInt()
        val maxHeight = (320 * density).toInt()
        val closeThreshold = (50 * density).toInt()

        overlayHeight = if (isCompactTabLayout) minHeight else maxHeight
        val layoutParams = overlayView.layoutParams
        layoutParams.height = overlayHeight
        overlayView.layoutParams = layoutParams

        handle.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    isDragging = true

                    handle.animate()
                        .scaleX(1.1f)
                        .scaleY(1.2f)
                        .alpha(1f)
                        .setDuration(100)
                        .start()

                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!isDragging) return@setOnTouchListener false

                    val deltaY = startY - event.rawY
                    val newHeight = (overlayHeight + deltaY).toInt()
                        .coerceIn(closeThreshold, maxHeight)

                    val layoutParams = overlayView.layoutParams
                    layoutParams.height = newHeight
                    overlayView.layoutParams = layoutParams

                    val heightRatio = (newHeight - minHeight).toFloat() / (maxHeight - minHeight)
                    val wasCompact = isCompactTabLayout

                    if (heightRatio < 0.3f && !isCompactTabLayout) {
                        isCompactTabLayout = true
                        if (wasCompact != isCompactTabLayout) {
                            refreshTabLayout(overlayView)
                        }
                    } else if (heightRatio > 0.7f && isCompactTabLayout) {
                        isCompactTabLayout = false
                        if (wasCompact != isCompactTabLayout) {
                            refreshTabLayout(overlayView)
                        }
                    }

                    if (newHeight < minHeight) {
                        val closeProgress = (minHeight - newHeight).toFloat() / (minHeight - closeThreshold)
                        overlayView.alpha = 1f - (closeProgress * 0.5f)
                    }

                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isDragging) return@setOnTouchListener false
                    isDragging = false

                    handle.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(0.6f)
                        .setDuration(100)
                        .start()

                    val layoutParams = overlayView.layoutParams
                    val currentHeight = layoutParams.height

                    if (currentHeight <= closeThreshold) {
                        hideTabOverlay()
                        return@setOnTouchListener true
                    }

                    val targetHeight = if (currentHeight < (minHeight + maxHeight) / 2) {
                        isCompactTabLayout = true
                        minHeight
                    } else {
                        isCompactTabLayout = false
                        maxHeight
                    }

                    ValueAnimator.ofInt(currentHeight, targetHeight).apply {
                        duration = 200
                        addUpdateListener { animator ->
                            layoutParams.height = animator.animatedValue as Int
                            overlayView.layoutParams = layoutParams
                        }
                        doOnEnd {
                            setupTabOverlayControls(overlayView)
                        }
                        start()
                    }

                    overlayHeight = targetHeight
                    overlayView.alpha = 1f

                    sharedPrefs.edit {
                        putBoolean("moodle_tab_layout_compact", isCompactTabLayout)
                    }

                    refreshTabLayout(overlayView)
                    true
                }
                else -> false
            }
        }
    }

    private fun refreshTabLayout(overlayView: View) {
        setupTabOverlayControls(overlayView)

        tabRecyclerView = overlayView.findViewById(R.id.tabRecyclerView)
        setupTabRecyclerView(overlayView)

        overlayView.animate()
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(100)
            .withEndAction {
                overlayView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100)
                    .start()
            }
            .start()
    }

    private fun hideTabOverlay() {
        if (!isTabViewVisible) return

        tabOverlayView?.let { overlay ->
            val animationsEnabled = Settings.Global.getFloat(
                requireContext().contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f
            ) != 0.0f

            if (animationsEnabled) {
                overlay.animate()
                    .alpha(0f)
                    .setDuration(150)
                    .setInterpolator(AccelerateInterpolator())
                    .withEndAction {
                        (view as? ViewGroup)?.removeView(overlay)
                        tabOverlayView = null
                        isTabViewVisible = false
                        updateTabButtonIcon()
                    }
                    .start()
            } else {
                (view as? ViewGroup)?.removeView(overlay)
                tabOverlayView = null
                isTabViewVisible = false
                updateTabButtonIcon()
            }
        }
    }

    private fun setupTabRecyclerView(overlayView: View) {
        tabRecyclerView = overlayView.findViewById(R.id.tabRecyclerView)

        val screenWidth = resources.displayMetrics.widthPixels
        val density = resources.displayMetrics.density

        val horizontalSpacing: Int
        val sideMargin: Int
        val itemWidth: Int
        val itemHeight: Int

        if (isCompactTabLayout) {
            horizontalSpacing = (6 * density).toInt()
            sideMargin = (8 * density).toInt()
            val totalHorizontalPadding = (2 * sideMargin) + (3 * horizontalSpacing)
            itemWidth = ((screenWidth - totalHorizontalPadding) / 4f).toInt()
            itemHeight = (100 * density).toInt()
        } else {
            horizontalSpacing = (8 * density).toInt()
            sideMargin = (12 * density).toInt()
            val totalHorizontalPadding = (2 * sideMargin) + horizontalSpacing
            itemWidth = ((screenWidth - totalHorizontalPadding) / 2f).toInt()
            itemHeight = (180 * density).toInt()
        }

        val floatingDeleteZone = view?.findViewById<FrameLayout>(R.id.floatingDeleteZone)
        val floatingDeleteIcon = view?.findViewById<ImageView>(R.id.floatingDeleteIcon)

        tabAdapter = TabAdapter(
            tabs = tabs,
            currentTabIndex = currentTabIndex,
            isCompactLayout = isCompactTabLayout,
            itemWidth = itemWidth,
            itemHeight = itemHeight,
            onTabClick = { index ->
                if (index in tabs.indices) {
                    switchToTab(index)
                    hideTabOverlay()
                }
            },
            onTabClose = { index ->
                if (index in tabs.indices && !tabs[index].isDefault) {
                    closeTab(index)
                    tabAdapter.notifyDataSetChanged()
                }
            },
            onTabPin = { index ->
                if (index in tabs.indices && !tabs[index].isDefault) {
                    tabs[index] = tabs[index].copy(isPinned = !tabs[index].isPinned)
                    savePinnedTabs()
                    tabAdapter.notifyItemChanged(index)
                }
            },
            onTabDragStart = { index ->
                if (index in tabs.indices && !tabs[index].isDefault && !isCompactTabLayout) {
                    isDraggingTab = true
                    draggedTabIndex = index

                    floatingDeleteZone?.visibility = View.VISIBLE
                    floatingDeleteIcon?.apply {
                        scaleX = 1f
                        scaleY = 1f
                        translationY = 0f
                        clearAnimation()
                        alpha = 1f
                    }
                }
            },
            onTabDragEnd = { index, x, y ->
                if (index in tabs.indices && !tabs[index].isDefault && isDraggingTab) {
                    var shouldDelete = false

                    floatingDeleteIcon?.let { icon ->
                        val iconLocation = IntArray(2)
                        icon.getLocationOnScreen(iconLocation)
                        val iconCenterX = iconLocation[0] + icon.width / 2
                        val iconCenterY = iconLocation[1] + icon.height / 2

                        val distance = sqrt(
                            (x - iconCenterX).toDouble().pow(2.0) +
                                    (y - iconCenterY).toDouble().pow(2.0)
                        )

                        val hitRadius = (icon.width / 2f + 10 * density)
                        shouldDelete = distance < hitRadius
                    }

                    if (shouldDelete) {
                        animateTabDeletion(index)
                    }

                    Handler(Looper.getMainLooper()).postDelayed({
                        floatingDeleteIcon?.clearAnimation()
                        floatingDeleteIcon?.animate()
                            ?.scaleX(0.8f)
                            ?.scaleY(0.8f)
                            ?.alpha(0f)
                            ?.setDuration(200)
                            ?.withEndAction {
                                floatingDeleteZone?.visibility = View.GONE
                            }
                            ?.start()
                    }, 100)
                }
                draggedTabIndex = -1
                isDraggingTab = false
            },
            onTabMove = { fromIndex, toIndex ->
                if (fromIndex in tabs.indices && toIndex in tabs.indices &&
                    !tabs[fromIndex].isDefault && !tabs[toIndex].isDefault) {
                    Collections.swap(tabs, fromIndex, toIndex)
                    tabAdapter.notifyItemMoved(fromIndex, toIndex)
                    if (currentTabIndex == fromIndex) {
                        currentTabIndex = toIndex
                    } else if (currentTabIndex == toIndex) {
                        currentTabIndex = fromIndex
                    }
                    savePinnedTabs()
                }
            },
            onTabDragUpdate = { index, x, y ->
                if (!isCompactTabLayout && isDraggingTab) {
                    floatingDeleteIcon?.let { icon ->
                        val iconLocation = IntArray(2)
                        icon.getLocationOnScreen(iconLocation)
                        val iconCenterX = iconLocation[0] + icon.width / 2
                        val iconCenterY = iconLocation[1] + icon.height / 2

                        val distance = sqrt(
                            (x - iconCenterX).toDouble().pow(2.0) +
                                    (y - iconCenterY).toDouble().pow(2.0)
                        )

                        val hitRadius = (icon.width / 2f + 10 * density)
                        val hoverRadius = (icon.width / 2f + 50 * density)

                        when {
                            distance < hitRadius -> {
                                // shake
                                icon.clearAnimation()
                                icon.animate()
                                    .translationY(-10f * density)
                                    .scaleX(1.3f)
                                    .scaleY(1.3f)
                                    .setDuration(150)
                                    .start()

                                val shake = RotateAnimation(
                                    -10f, 10f,
                                    Animation.RELATIVE_TO_SELF, 0.5f,
                                    Animation.RELATIVE_TO_SELF, 0.5f
                                ).apply {
                                    duration = 100
                                    repeatCount = Animation.INFINITE
                                    repeatMode = Animation.REVERSE
                                }
                                icon.startAnimation(shake)
                            }
                            distance < hoverRadius -> {
                                // slight hover
                                icon.clearAnimation()
                                icon.animate()
                                    .translationY(-5f * density)
                                    .scaleX(1.1f)
                                    .scaleY(1.1f)
                                    .setDuration(150)
                                    .start()
                            }
                            else -> {
                                // resting state
                                icon.clearAnimation()
                                icon.animate()
                                    .translationY(0f)
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .setDuration(150)
                                    .start()
                            }
                        }
                    }
                }
            },
            onNewTabClick = {
                if (tabs.size < MAX_TABS) {
                    createNewTab()
                }
            }
        )

        val layoutManager = LinearLayoutManager(
            requireContext(),
            LinearLayoutManager.HORIZONTAL,
            false
        )

        tabRecyclerView.apply {
            this.layoutManager = layoutManager
            adapter = tabAdapter
            itemAnimator = DefaultItemAnimator().apply {
                changeDuration = 200
                moveDuration = 200
            }

            while (itemDecorationCount > 0) {
                removeItemDecorationAt(0)
            }
            addItemDecoration(HorizontalSpacingItemDecoration(horizontalSpacing))

            setPadding(sideMargin, 0, sideMargin, 0)
            clipToPadding = false
            clipChildren = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
    }

    private fun animateTabDeletion(index: Int) {
        closeTab(index)
        tabAdapter.notifyDataSetChanged()
    }

    private fun showClearAllTabsDialog() {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.moodle_clear_all_tabs))
            .setMessage(getString(R.string.moodle_clear_all_tabs_confirm))
            .setPositiveButton(getString(R.string.moodle_clear)) { _, _ ->
                clearAllTabs()
                hideTabOverlay()
            }
            .setNegativeButton(getString(R.string.moodle_cancel), null)
            .show()

        val buttonColor = requireContext().getThemeColor(R.attr.dialogSectionButtonColor)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
    }

    private fun showPdfViewer(pdfFile: File, fileName: String) {
        try {
            val fileDescriptor = ParcelFileDescriptor.open(
                pdfFile,
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            pdfRenderer = PdfRenderer(fileDescriptor)
            currentPdfPage = 0

            saveCurrentTabState()
            val pdfTab = TabInfo(
                url = pdfFile.absolutePath,
                title = fileName.removeSuffix(".pdf")
            )
            tabs.add(pdfTab)
            currentTabIndex = tabs.size - 1

            val overlayView = layoutInflater.inflate(R.layout.overlay_pdf_viewer, null)
            pdfViewerOverlay = overlayView

            val rootView = view as ViewGroup
            rootView.addView(overlayView)

            setupPdfViewerControls(overlayView, pdfFile, fileName)
            renderPdfPage()

            isPdfViewerVisible = true

        } catch (e: Exception) {
            L.e("MoodleFragment", "Error opening PDF", e)
            Toast.makeText(requireContext(), getString(R.string.moodle_pdf_render_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupPdfViewerControls(overlayView: View, pdfFile: File, fileName: String) {
        pdfImageView = overlayView.findViewById(R.id.pdfImageView)
        pdfPageIndicator = overlayView.findViewById(R.id.pdfPageIndicator)

        val btnPrevPage = overlayView.findViewById<ImageButton>(R.id.btnPrevPage)
        val btnNextPage = overlayView.findViewById<ImageButton>(R.id.btnNextPage)
        val btnClosePdf = overlayView.findViewById<ImageButton>(R.id.btnClosePdf)
        val btnPdfOptions = overlayView.findViewById<ImageButton>(R.id.btnPdfOptions)

        btnPrevPage.setOnClickListener {
            if (currentPdfPage > 0) {
                currentPdfPage--
                renderPdfPage()
            }
        }

        btnNextPage.setOnClickListener {
            pdfRenderer?.let { renderer ->
                if (currentPdfPage < renderer.pageCount - 1) {
                    currentPdfPage++
                    renderPdfPage()
                }
            }
        }

        btnClosePdf.setOnClickListener {
            closePdfViewer()
            closeTab(currentTabIndex)
        }

        btnPdfOptions.setOnClickListener {
            showPdfOptionsMenu(pdfFile, fileName)
        }
    }

    private fun renderPdfPage() {
        pdfRenderer?.let { renderer ->
            if (currentPdfPage >= 0 && currentPdfPage < renderer.pageCount) {
                val page = renderer.openPage(currentPdfPage)

                val bitmap = createBitmap(page.width * 2, page.height * 2)

                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                pdfImageView.setImageBitmap(bitmap)

                pdfPageIndicator.text = getString(
                    R.string.moodle_pdf_page_indicator,
                    currentPdfPage + 1,
                    renderer.pageCount
                )

                page.close()
            }
        }
    }

    private fun closePdfViewer() {
        pdfRenderer?.close()
        pdfRenderer = null

        pdfViewerOverlay?.let { overlay ->
            (view as? ViewGroup)?.removeView(overlay)
            pdfViewerOverlay = null
        }

        isPdfViewerVisible = false
    }

    private fun showPdfOptionsMenu(pdfFile: File, fileName: String) {
        val options = arrayOf(
            getString(R.string.moodle_pdf_download),
            getString(R.string.moodle_pdf_share),
            getString(R.string.moodle_pdf_copy_text),
            getString(R.string.moodle_pdf_search)
        )

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.moodle_pdf_options))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> savePdfToDownloads(pdfFile, fileName)
                    1 -> sharePdf(pdfFile)
                    2 -> copyPdfText(pdfFile)
                    3 -> searchInPdf()
                }
            }
            .setNegativeButton(getString(R.string.moodle_cancel), null)
            .show()

        val buttonColor = requireContext().getThemeColor(R.attr.dialogSectionButtonColor)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
    }


    private fun savePdfToDownloads(pdfFile: File, fileName: String) {
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val destFile = File(downloadsDir, fileName)

            pdfFile.copyTo(destFile, overwrite = true)
            Toast.makeText(requireContext(), getString(R.string.moodle_pdf_saved, fileName), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            L.e("MoodleFragment", "Error saving PDF", e)
            Toast.makeText(requireContext(), getString(R.string.moodle_pdf_save_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun sharePdf(pdfFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                pdfFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(intent, getString(R.string.moodle_share_pdf)))
        } catch (e: Exception) {
            L.e("MoodleFragment", "Error sharing PDF", e)
            Toast.makeText(requireContext(), getString(R.string.moodle_pdf_share_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyPdfText(pdfFile: File) {
        backgroundExecutor.execute {
            try {
                val fileInputStream = FileInputStream(pdfFile)
                val pdfReader = PdfReader(fileInputStream)
                val textBuilder = StringBuilder()

                for (page in 1..pdfReader.numberOfPages) {
                    val strategy = LocationTextExtractionStrategy()
                    val pageText = PdfTextExtractor.getTextFromPage(
                        pdfReader,
                        page,
                        strategy
                    )
                    textBuilder.append(pageText)
                    if (page < pdfReader.numberOfPages) {
                        textBuilder.append("\n\n--- Page ${page + 1} ---\n\n")
                    }
                }

                pdfReader.close()
                fileInputStream.close()

                activity?.runOnUiThread {
                    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("PDF Text", textBuilder.toString())
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(requireContext(), getString(R.string.moodle_pdf_text_copied), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                L.e("MoodleFragment", "Error copying PDF text", e)
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), getString(R.string.moodle_pdf_copy_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun searchInPdf() {
        Toast.makeText(requireContext(), "PDF search coming soon", Toast.LENGTH_SHORT).show()
    }

    private fun handleInterceptedDownload(
        url: String,
        cookies: String?,
        contentDisposition: String = "",
        mimetype: String? = null
    ) {
        L.d("MoodleFragment", "Handling intercepted download")
        L.d("MoodleFragment", "URL: $url")
        L.d("MoodleFragment", "MimeType: $mimetype")
        L.d("MoodleFragment", "Content-Disposition: $contentDisposition")

        val isPdf = mimetype == "application/pdf" ||
                url.contains(".pdf", ignoreCase = true) ||
                contentDisposition.contains(".pdf", ignoreCase = true) ||
                contentDisposition.contains("filename=\"", ignoreCase = true) &&
                contentDisposition.substringAfter("filename=\"").substringBefore("\"").endsWith(".pdf", ignoreCase = true)

        if (isPdf && tabs.size < MAX_TABS) {
            L.d("MoodleFragment", "Opening PDF in viewer")
            handlePdfForViewerWithCookies(url, cookies)
        } else {
            L.d("MoodleFragment", "Starting download to device")
            downloadToDeviceWithCookies(url, cookies, contentDisposition)
        }
    }

    private fun downloadToDeviceWithCookies(url: String, cookies: String?, contentDisposition: String = "") {
        try {
            val request = DownloadManager.Request(url.toUri())

            var fileName = URLUtil.guessFileName(url, contentDisposition, null)
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

            if (!cookies.isNullOrBlank()) {
                request.addRequestHeader("Cookie", cookies)
                L.d("MoodleFragment", "Added cookies to download: ${cookies.take(50)}...")
            } else {
                L.w("MoodleFragment", "WARNING: No cookies available for download!")
            }

            request.addRequestHeader("User-Agent", userAgent)
            request.addRequestHeader("Referer", moodleBaseUrl)
            request.addRequestHeader("Accept", "*/*")

            val downloadManager = requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = downloadManager.enqueue(request)

            L.d("MoodleFragment", "Download enqueued with ID: $downloadId")
            Toast.makeText(requireContext(), getString(R.string.moodle_download_started, fileName), Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            L.e("MoodleFragment", "Download failed", e)
            Toast.makeText(requireContext(), getString(R.string.moodle_download_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun handlePdfForViewerWithCookies(url: String, cookies: String?) {
        val progressDialog = AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.moodle_loading_pdf))
            .setCancelable(false)
            .show()

        val buttonColor = requireContext().getThemeColor(R.attr.dialogSectionButtonColor)
        progressDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        progressDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        progressDialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(buttonColor)

        backgroundExecutor.execute {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"

                if (!cookies.isNullOrBlank()) {
                    connection.setRequestProperty("Cookie", cookies)
                    L.d("MoodleFragment", "Using pre-captured cookies for PDF")
                } else {
                    L.w("MoodleFragment", "WARNING: No cookies for PDF download!")
                }

                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Referer", moodleBaseUrl)
                connection.setRequestProperty("Accept", "application/pdf,*/*")
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = false

                val responseCode = connection.responseCode
                L.d("MoodleFragment", "PDF download response: $responseCode")

                if (responseCode == 200) {
                    val cacheDir = requireContext().cacheDir
                    val pdfFile = File(cacheDir, "moodle_pdf_${System.currentTimeMillis()}.pdf")

                    connection.inputStream.use { input ->
                        pdfFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    connection.disconnect()

                    val fileName = try {
                        URLDecoder.decode(url.substringAfterLast("/").substringBefore("?"), "UTF-8")
                    } catch (_: Exception) {
                        "document.pdf"
                    }

                    activity?.runOnUiThread {
                        progressDialog.dismiss()
                        showPdfViewer(pdfFile, fileName)
                    }
                } else {
                    connection.disconnect()
                    activity?.runOnUiThread {
                        progressDialog.dismiss()
                        val message = if (responseCode in 300..399) {
                            "Session expired during download"
                        } else {
                            "HTTP error: $responseCode"
                        }
                        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                L.e("MoodleFragment", "Error downloading PDF", e)
                activity?.runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(requireContext(), getString(R.string.moodle_pdf_load_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun resolveDownloadUrl(viewUrl: String, cookies: String?) {
        backgroundExecutor.execute {
            try {
                L.d("MoodleFragment", "Resolving actual download URL from: $viewUrl")

                val connection = URL(viewUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false

                if (!cookies.isNullOrBlank()) {
                    connection.setRequestProperty("Cookie", cookies)
                }

                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Referer", moodleBaseUrl)
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                connection.connectTimeout = 15000
                connection.readTimeout = 15000

                val responseCode = connection.responseCode
                L.d("MoodleFragment", "Response code: $responseCode")

                if (responseCode in 300..399) {
                    val actualFileUrl = connection.getHeaderField("Location")
                    connection.disconnect()

                    if (actualFileUrl != null) {
                        val fullUrl = if (actualFileUrl.startsWith("http")) {
                            actualFileUrl
                        } else {
                            "$moodleBaseUrl$actualFileUrl"
                        }

                        L.d("MoodleFragment", "Resolved to actual file URL: $fullUrl")

                        val contentType = connection.getHeaderField("Content-Type")

                        activity?.runOnUiThread {
                            handleInterceptedDownload(fullUrl, cookies, "", contentType)
                        }
                    } else {
                        L.e("MoodleFragment", "No redirect location found")
                        connection.disconnect()
                        activity?.runOnUiThread {
                            Toast.makeText(requireContext(), getString(R.string.moodle_couldnt_resolve_download_url), Toast.LENGTH_SHORT).show()
                        }
                    }
                } else if (responseCode == 200) {
                    val contentType = connection.getHeaderField("Content-Type")
                    val contentDisposition = connection.getHeaderField("Content-Disposition")

                    L.d("MoodleFragment", "Direct download without redirect")
                    L.d("MoodleFragment", "Content-Type: $contentType")
                    L.d("MoodleFragment", "Content-Disposition: $contentDisposition")

                    connection.disconnect()

                    activity?.runOnUiThread {
                        handleInterceptedDownload(viewUrl, cookies, contentDisposition ?: "", contentType)
                    }
                } else {
                    L.e("MoodleFragment", "Unexpected response code: $responseCode")
                    connection.disconnect()

                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), getString(R.string.moodle_download_failed_with_http_error, responseCode), Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                L.e("MoodleFragment", "Error resolving download URL", e)
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), getString(R.string.moodle_error_resolving_download, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun resolveAndParseDocument(viewUrl: String, cookies: String?, progressDialog: AlertDialog) {
        backgroundExecutor.execute {
            try {
                val connection = URL(viewUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false

                if (!cookies.isNullOrBlank()) {
                    connection.setRequestProperty("Cookie", cookies)
                }

                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Referer", moodleBaseUrl)
                connection.connectTimeout = 15000
                connection.readTimeout = 15000

                val responseCode = connection.responseCode

                if (responseCode in 300..399) {
                    val actualFileUrl = connection.getHeaderField("Location")
                    connection.disconnect()

                    if (actualFileUrl != null) {
                        val fullUrl = if (actualFileUrl.startsWith("http")) {
                            actualFileUrl
                        } else {
                            "$moodleBaseUrl$actualFileUrl"
                        }

                        downloadAndParseDocumentWithCookies(fullUrl, cookies) { text ->
                            activity?.runOnUiThread {
                                progressDialog.dismiss()
                                if (text != null) {
                                    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText(getString(R.string.moodle_document_text), text)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(requireContext(), getString(R.string.moodle_text_copied), Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(requireContext(), getString(R.string.moodle_parse_failed), Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } else {
                        connection.disconnect()
                        activity?.runOnUiThread {
                            progressDialog.dismiss()
                            Toast.makeText(requireContext(), getString(R.string.moodle_couldnt_resolve_file_url), Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    connection.disconnect()
                    activity?.runOnUiThread {
                        progressDialog.dismiss()
                        Toast.makeText(requireContext(), getString(R.string.moodle_failed_resolving_url_with_http_error, responseCode), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                L.e("MoodleFragment", "Error resolving document URL", e)
                activity?.runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(requireContext(), getString(R.string.moodle_error_details, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun downloadAndParseDocumentWithCookies(url: String, cookies: String?, callback: (String?) -> Unit) {
        backgroundExecutor.execute {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"

                if (!cookies.isNullOrBlank()) {
                    connection.setRequestProperty("Cookie", cookies)
                }

                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Referer", moodleBaseUrl)
                connection.setRequestProperty("Accept", "*/*")
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.instanceFollowRedirects = false

                val responseCode = connection.responseCode

                if (responseCode == 200) {
                    val inputStream = connection.inputStream
                    val text = when {
                        url.endsWith(".pdf", ignoreCase = true) -> {
                            try {
                                val pdfReader = PdfReader(inputStream)
                                val textBuilder = StringBuilder()

                                for (page in 1..pdfReader.numberOfPages) {
                                    val strategy = LocationTextExtractionStrategy()
                                    val pageText = PdfTextExtractor.getTextFromPage(
                                        pdfReader,
                                        page,
                                        strategy
                                    )
                                    textBuilder.append(pageText)
                                    if (page < pdfReader.numberOfPages) {
                                        textBuilder.append("\n\n--- Page ${page + 1} ---\n\n")
                                    }
                                }

                                pdfReader.close()
                                textBuilder.toString()
                            } catch (e: Exception) {
                                L.e("MoodleFragment", "Error parsing PDF", e)
                                null
                            }
                        }
                        url.endsWith(".docx", ignoreCase = true) -> {
                            parseDocxToText(inputStream)
                        }
                        else -> null
                    }

                    inputStream.close()
                    connection.disconnect()
                    callback(text)
                } else {
                    L.e("MoodleFragment", "Document download failed with code: $responseCode")
                    connection.disconnect()
                    callback(null)
                }
            } catch (e: Exception) {
                L.e("MoodleFragment", "Error downloading document", e)
                callback(null)
            }
        }
    }

    private fun showLoadingBar() {
        if (loadingBarContainer.isVisible) return

        val animationsEnabled = Settings.Global.getFloat(
            requireContext().contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f
        ) != 0.0f

        if (animationsEnabled) {
            loadingBarContainer.visibility = View.VISIBLE
            loadingBarContainer.measure(
                View.MeasureSpec.makeMeasureSpec((loadingBarContainer.parent as View).width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val targetHeight = loadingBarContainer.measuredHeight

            val layoutParams = loadingBarContainer.layoutParams
            layoutParams.height = 0
            loadingBarContainer.layoutParams = layoutParams

            ValueAnimator.ofInt(0, targetHeight).apply {
                duration = 200
                interpolator = DecelerateInterpolator()

                addUpdateListener { animator ->
                    val animatedHeight = animator.animatedValue as Int
                    layoutParams.height = animatedHeight
                    loadingBarContainer.layoutParams = layoutParams
                }

                doOnEnd {
                    layoutParams.height = targetHeight
                    loadingBarContainer.layoutParams = layoutParams
                }

                start()
            }
        } else {
            loadingBarContainer.visibility = View.VISIBLE
            val layoutParams = loadingBarContainer.layoutParams
            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            loadingBarContainer.layoutParams = layoutParams
        }
    }

    private fun hideLoadingBar() {
        if (loadingBarContainer.visibility != View.VISIBLE) return

        val animationsEnabled = Settings.Global.getFloat(
            requireContext().contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f
        ) != 0.0f

        if (animationsEnabled) {
            val currentHeight = loadingBarContainer.height
            val layoutParams = loadingBarContainer.layoutParams

            ValueAnimator.ofInt(currentHeight, 0).apply {
                duration = 200
                interpolator = AccelerateInterpolator()

                addUpdateListener { animator ->
                    val animatedHeight = animator.animatedValue as Int
                    layoutParams.height = animatedHeight
                    loadingBarContainer.layoutParams = layoutParams
                }

                doOnEnd {
                    loadingBarContainer.visibility = View.GONE
                    layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    loadingBarContainer.layoutParams = layoutParams
                    horizontalProgressBar.progress = 0
                }

                start()
            }
        } else {
            loadingBarContainer.visibility = View.GONE
            val layoutParams = loadingBarContainer.layoutParams
            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            loadingBarContainer.layoutParams = layoutParams
            horizontalProgressBar.progress = 0
        }
    }
}