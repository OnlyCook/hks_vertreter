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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
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
import androidx.constraintlayout.widget.ConstraintLayout
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
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.graphics.scale
import androidx.core.graphics.createBitmap
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.parser.LocationTextExtractionStrategy
import com.itextpdf.text.pdf.parser.PdfTextExtractor
import com.thecooker.vertretungsplaner.FetchType
import com.thecooker.vertretungsplaner.SettingsActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.math.abs
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

    private lateinit var btnOpenInBrowser: ImageButton
    private lateinit var btnOpenInBrowser2: ImageButton // pdf viewer globe button

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
    private var isPullToRefreshActive = false

    // session expired
    private var sessionExpiredDetected = false
    private var lastSessionExpiredTime = 0L
    private var consecutiveLoginFailures = 0
    private val MAX_LOGIN_ATTEMPTS = 3
    private val SESSION_RETRY_DELAY = 30000L
    private var isLoginInProgress = false
    private var loginAttemptCount = 0
    private var sessionExpiredRetryCount = 0

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
    private var pdfViewerManager: PdfViewerManager? = null
    private val PDF_TAB_PREFIX = "pdf_tab_"
    private lateinit var pdfContainer: FrameLayout
    private lateinit var pdfScrollContainer: ScrollView
    private lateinit var pdfPagesContainer: LinearLayout
    private lateinit var pdfSinglePageContainer: ConstraintLayout
    private lateinit var pdfSinglePageView: ImageView
    private lateinit var webControlsLayout: RelativeLayout
    private lateinit var pdfControlsLayout: LinearLayout
    private lateinit var pdfPageCounter: TextView
    private lateinit var pdfKebabMenu: ImageButton
    private var isDarkTheme = false
    private var pdfFileUrl: String? = null
    private var isScrolling = false
    private var scrollStopHandler: Handler? = null
    private var scrollStopRunnable: Runnable? = null
    private var lastUnloadTime = 0L
    private val UNLOAD_THROTTLE_MS = 500L
    private var isNavigating = false
    private var ignoreScrollUpdates = false

    // horizontal loading bar
    private lateinit var horizontalProgressBar: ProgressBar
    private lateinit var loadingBarContainer: FrameLayout

    // pdf fetching
    private var moodleFetchProgressDialog: AlertDialog? = null
    private var isFetchingFromMoodle = false
    private var fetchCancelled = false
    private var fetchStartTime = 0L
    private val FETCH_TIMEOUT = 180000L // 3 min
    private var fetchPreserveNotes = false
    private var fetchProgramName: String? = null
    private var userProvidedProgramName: String? = null
    private var currentFetchType: FetchType? = null
    private var savedProgramCourseName: String? = null
    private var savedTimetableEntryName: String? = null
    private var fetchReturnDestination: Int? = null

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

        arguments?.getBoolean("moodle_fetch_in_progress", false)?.let { fetchInProgress ->
            if (fetchInProgress) {
                currentFetchType = arguments?.getString("moodle_fetch_type")?.let { typeString ->
                    try {
                        FetchType.valueOf(typeString)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }
                fetchPreserveNotes = arguments?.getBoolean("moodle_fetch_preserve_notes", false) ?: false
                fetchProgramName = arguments?.getString("moodle_fetch_program_name")

                val passedClass = arguments?.getString("moodle_fetch_class")
                if (passedClass != null) {
                    L.d("MoodleFragment", "Received class from Intent: $passedClass")
                    requireActivity().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                        .edit {
                            putString("temp_fetch_class", passedClass)
                        }
                }

                fetchReturnDestination = arguments?.getInt("moodle_fetch_return_destination")

                arguments?.remove("moodle_fetch_in_progress")
                arguments?.remove("moodle_fetch_type")
                arguments?.remove("moodle_fetch_preserve_notes")
                arguments?.remove("moodle_fetch_program_name")
                arguments?.remove("moodle_fetch_return_destination")
                arguments?.remove("moodle_fetch_class")

                Handler(Looper.getMainLooper()).postDelayed({
                    startMoodleFetchProcess()
                }, 1000)
            }
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

        detectCurrentTheme()

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

        btnSearch.alpha = if (isOnLoginPage) 0.5f else 1.0f
        btnDashboard.alpha = if (isOnLoginPage) 0.5f else 1.0f
        btnNotifications.alpha = if (isOnLoginPage) 0.5f else 1.0f
        btnMessages.alpha = if (isOnLoginPage) 0.5f else 1.0f
        btnMenu.alpha = if (isOnLoginPage) 0.5f else 1.0f
        btnBack.alpha = if (canGoBack) 1.0f else 0.5f

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
        btnOpenInBrowser2 = root.findViewById(R.id.btnOpenInBrowser2)
        spinnerSearchType = root.findViewById(R.id.spinnerSearchType)
        tvNotificationCount = root.findViewById(R.id.tvNotificationCount)
        tvMessageCount = root.findViewById(R.id.tvMessageCount)
        refreshContainer = root.findViewById(R.id.refreshContainer)
        refreshIndicator = root.findViewById(R.id.refreshIndicator)
        pdfContainer = root.findViewById(R.id.pdfContainer)
        pdfScrollContainer = root.findViewById(R.id.pdfScrollContainer)
        pdfPagesContainer = root.findViewById(R.id.pdfPagesContainer)
        pdfSinglePageContainer = root.findViewById(R.id.pdfSinglePageContainer)
        pdfSinglePageView = root.findViewById(R.id.pdfSinglePageView)
        webControlsLayout = root.findViewById(R.id.webControlsLayout)
        pdfControlsLayout = root.findViewById(R.id.pdfControlsLayout)
        pdfPageCounter = root.findViewById(R.id.pdfPageCounter)
        pdfKebabMenu = root.findViewById(R.id.pdfKebabMenu)
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
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
            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                val dialog = AlertDialog.Builder(requireActivity())
                    .setTitle(getString(R.string.moodle_confirm_navigation))
                    .setMessage(message)
                    .setPositiveButton(getString(R.string.moodle_leave_page)) { _, _ ->
                        result?.confirm()
                    }
                    .setNegativeButton(getString(R.string.moodle_stay_page)) { _, _ ->
                        result?.cancel()
                    }
                    .setOnCancelListener {
                        result?.cancel()
                    }
                    .show()

                dialog.setOnShowListener {
                    val buttonColor = requireActivity().getThemeColor(R.attr.dialogSectionButtonColor)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
                }

                return true
            }

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

                L.d("MoodleFragment", "=== shouldOverrideUrlLoading ===")
                L.d("MoodleFragment", "URL: $url")
                L.d("MoodleFragment", "Current page: ${webView.url}")

                if (url.contains("/mod/resource/view.php")) {
                    L.d("MoodleFragment", "Intercepted resource view URL: $url")
                    val cookies = CookieManager.getInstance().getCookie(url)
                    L.d("MoodleFragment", "Cookie preview: ${cookies?.take(50)}...")

                    verifyFileLink(url) { isFileLink ->
                        L.d("MoodleFragment", "verifyFileLink returned: $isFileLink")

                        if (isFileLink) {
                            L.d("MoodleFragment", "Treating as file download, resolving URL")
                            resolveDownloadUrl(url, cookies)
                        } else {
                            L.d("MoodleFragment", "Detected image page - NOT opening to avoid session loss")
                            activity?.runOnUiThread {
                                showImagePageDialog(url)
                            }
                        }
                    }
                    return true
                }

                if (url.contains("/pluginfile.php")) {
                    L.d("MoodleFragment", "Intercepted direct file URL: $url")
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

                    if (isTabViewVisible && currentTabIndex in tabs.indices) {
                        val currentTab = tabs[currentTabIndex]
                        tabs[currentTabIndex] = currentTab.copy(
                            url = webView.url ?: currentTab.url,
                            title = webView.title ?: currentTab.title,
                            thumbnail = captureWebViewThumbnail()
                        )
                        tabAdapter.notifyItemChanged(currentTabIndex)
                    }
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
            if (isPdfTab(currentTabIndex)) {
                return@setOnTouchListener false
            }

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
                            isPullToRefreshActive = true
                            pullDistance = deltaY
                            updateRefreshIndicator(pullDistance)
                            return@setOnTouchListener true
                        } else if (deltaY < 0 && isPulling) {
                            isPulling = false
                            isPullToRefreshActive = false
                            startedPullFromTop = false
                            animateRefreshIndicatorAway()
                        }
                    } else {
                        if (isPulling) {
                            isPulling = false
                            isPullToRefreshActive = false
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
                    isPullToRefreshActive = false
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
            if (isPdfTab(currentTabIndex)) {
                return@setOnScrollChangeListener
            }

            scrollEndCheckRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }

            scrollEndCheckRunnable = Runnable {
                checkScrollEndedAtTop()
            }
            Handler(Looper.getMainLooper()).postDelayed(scrollEndCheckRunnable!!, 150)
        }
    }

    private fun verifyFileLink(url: String, callback: (Boolean) -> Unit) {
        val idMatch = "id=(\\d+)".toRegex().find(url)
        val resourceId = idMatch?.groupValues?.get(1)

        L.d("MoodleFragment", "=== verifyFileLink Debug ===")
        L.d("MoodleFragment", "URL: $url")
        L.d("MoodleFragment", "Resource ID: $resourceId")
        L.d("MoodleFragment", "Current WebView URL: ${webView.url}")

        if (resourceId == null) {
            L.e("MoodleFragment", "No resource ID found in URL")
            callback(false)
            return
        }

        val jsCode = """
    (function() {
        try {
            console.log('=== Starting File Verification ===');
            console.log('Checking resource ID: $resourceId');
            console.log('Document URL: ' + document.location.href);
            console.log('Document ready state: ' + document.readyState);
            
            var links = document.querySelectorAll('a[href*="id=$resourceId"]');
            console.log('Found ' + links.length + ' links with this ID');
            
            for (var i = 0; i < links.length; i++) {
                var link = links[i];
                console.log('Link ' + i + ' href: ' + link.href);
                console.log('Link ' + i + ' HTML: ' + link.outerHTML.substring(0, 200));

                var accessHideSpan = link.querySelector('span.accesshide');
                if (accessHideSpan) {
                    var text = accessHideSpan.textContent.trim().toLowerCase();
                    console.log('AccessHide text: "' + text + '"');
                    
                    if (text.includes('datei') || text.includes('file')) {
                        var container = link.closest('.activity-item, .activityinstance');
                        if (container) {
                            console.log('Found container');
                            var activityIcon = container.querySelector('img.activityicon, img[data-region="activity-icon"]');
                            if (activityIcon && activityIcon.src) {
                                var imgSrc = activityIcon.src.toLowerCase();
                                console.log('Activity icon src: ' + imgSrc);

                                if (imgSrc.includes('/f/image?')) {
                                    console.log('*** DETECTED IMAGE PAGE ***');
                                    return JSON.stringify({isFile: false, isImagePage: true, iconSrc: imgSrc});
                                }

                                if (imgSrc.includes('/f/pdf') || 
                                    imgSrc.includes('/f/document') || 
                                    imgSrc.includes('/f/archive') ||
                                    imgSrc.includes('resource')) {
                                    console.log('*** DETECTED DOWNLOADABLE FILE ***');
                                    return JSON.stringify({isFile: true, isImagePage: false, iconSrc: imgSrc});
                                }
                            } else {
                                console.log('No activity icon found in container');
                            }
                        } else {
                            console.log('No container found for link');
                        }
                        console.log('Has file/datei tag but no definitive icon match');
                        return JSON.stringify({isFile: true, isImagePage: false, iconSrc: 'none'});
                    } else {
                        console.log('AccessHide text does not contain file/datei keywords');
                    }
                } else {
                    console.log('Link ' + i + ' has no accesshide span');
                }
            }
            
            console.log('*** NO FILE INDICATORS FOUND - DEFAULTING TO IMAGE PAGE ***');
            return JSON.stringify({isFile: false, isImagePage: false, iconSrc: 'none'});
        } catch(e) {
            console.error('Error in verifyFileLink: ' + e.message);
            console.error('Stack: ' + e.stack);
            return JSON.stringify({isFile: false, error: e.message, stack: e.stack});
        }
    })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            try {
                val cleanResult = result.replace("\\\"", "\"").trim('"')
                L.d("MoodleFragment", "Raw JS result: $cleanResult")

                val jsonResult = JSONObject(cleanResult)

                val isFile = jsonResult.optBoolean("isFile", false)
                val isImagePage = jsonResult.optBoolean("isImagePage", false)
                val iconSrc = jsonResult.optString("iconSrc", "unknown")
                val error = jsonResult.optString("error", "")

                L.d("MoodleFragment", "Verification result - isFile: $isFile, isImagePage: $isImagePage, iconSrc: $iconSrc")
                if (error.isNotEmpty()) {
                    L.e("MoodleFragment", "JS Error: $error")
                    val stack = jsonResult.optString("stack", "")
                    if (stack.isNotEmpty()) {
                        L.e("MoodleFragment", "JS Stack: $stack")
                    }
                }

                callback(isFile)
            } catch (e: Exception) {
                L.e("MoodleFragment", "Error parsing verification result", e)
                L.e("MoodleFragment", "Raw result was: $result")
                callback(false)
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
            navigateFromCurrentTab("$moodleBaseUrl/my/")
        }

        btnNotifications.setOnClickListener {
            navigateFromCurrentTab("$moodleBaseUrl/message/output/popup/notifications.php")
        }

        btnMessages.setOnClickListener {
            navigateFromCurrentTab("$moodleBaseUrl/message/index.php")
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
            showTabOverlay()
        }

        btnClearSearch.setOnClickListener {
            searchBarMoodle.setText("")
            btnClearSearch.visibility = View.GONE
        }

        btnSubmitSearch.setOnClickListener {
            if (!isNetworkAvailable()) {
                Toast.makeText(requireContext(), getString(R.string.moodle_offline_error), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnSubmitSearch.animate()
                .scaleX(0.85f)
                .scaleY(0.85f)
                .setDuration(100)
                .withEndAction {
                    btnSubmitSearch.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(100)
                        .start()
                }
                .start()

            btnSubmitSearch.isEnabled = false
            Handler(Looper.getMainLooper()).postDelayed({
                btnSubmitSearch.isEnabled = true
            }, 1000)

            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(searchBarMoodle.windowToken, 0)

            val originalDrawable = btnSubmitSearch.drawable
            btnSubmitSearch.setImageResource(R.drawable.ic_check)

            Handler(Looper.getMainLooper()).postDelayed({
                btnSubmitSearch.setImageDrawable(originalDrawable)
            }, 800)

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
            if (!isNetworkAvailable()) {
                Toast.makeText(requireContext(), getString(R.string.moodle_offline_error), Toast.LENGTH_SHORT).show()
                return@setOnEditorActionListener true
            }

            btnSubmitSearch.animate()
                .scaleX(0.85f)
                .scaleY(0.85f)
                .setDuration(100)
                .withEndAction {
                    btnSubmitSearch.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(100)
                        .start()
                }
                .start()

            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(searchBarMoodle.windowToken, 0)

            val originalDrawable = btnSubmitSearch.drawable
            btnSubmitSearch.setImageResource(R.drawable.ic_check)

            Handler(Looper.getMainLooper()).postDelayed({
                btnSubmitSearch.setImageDrawable(originalDrawable)
            }, 800)

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

    private fun navigateFromCurrentTab(url: String) {
        if (isPdfTab(currentTabIndex)) {
            saveCurrentTabState()

            pdfViewerManager?.closePdf()
            pdfViewerManager = null
            pdfFileUrl = null

            val currentTab = tabs[currentTabIndex]
            tabs[currentTabIndex] = currentTab.copy(
                id = UUID.randomUUID().toString(),
                url = url,
                title = getString(R.string.act_set_loading),
                thumbnail = null,
                webViewState = null
            )

            webView.visibility = View.VISIBLE
            pdfContainer.visibility = View.GONE
            webControlsLayout.visibility = View.VISIBLE
            pdfControlsLayout.visibility = View.GONE
            btnBack.visibility = View.VISIBLE
            btnOpenInBrowser.visibility = View.VISIBLE

            showLoadingBar()
            webView.loadUrl(url)
        } else {
            loadUrlInBackground(url)
        }
    }

    private fun updateTabButtonIcon() {
        btnForward.setImageResource(
            if (isTabViewVisible) R.drawable.ic_tabs_filled else R.drawable.ic_tabs
        )
    }

    private fun updateDashboardButtonIcon() {
        val currentUrl = if (isPdfTab(currentTabIndex)) {
            tabs.getOrNull(currentTabIndex)?.url ?: ""
        } else {
            webView.url ?: ""
        }

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
        val isPdf = isPdfTab(currentTabIndex)

        if (isPdf) {
            if (!extendedHeaderLayout.isVisible) {
                showExtendedHeaderWithAnimation()
            }
            return
        }

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
        if (isPdfTab(currentTabIndex)) {
            saveCurrentTabState()

            val defaultTabIndex = tabs.indexOfFirst { it.isDefault }
            if (defaultTabIndex != -1) {
                currentTabIndex = defaultTabIndex
                switchToTab(defaultTabIndex)
            } else {
                val newTab = TabInfo(
                    url = url,
                    title = getString(R.string.act_set_loading)
                )
                tabs.add(newTab)
                currentTabIndex = tabs.size - 1
            }

            webView.visibility = View.VISIBLE
            pdfContainer.visibility = View.GONE
            webControlsLayout.visibility = View.VISIBLE
            pdfControlsLayout.visibility = View.GONE
            btnBack.visibility = View.VISIBLE
        }

        showLoadingBar()
        webView.loadUrl(url)
    }

    private fun performSearch() {
        val query = searchBarMoodle.text.toString().trim()
        if (query.isEmpty()) return

        val searchType = spinnerSearchType.selectedItemPosition

        // If on PDF tab, convert to web tab first
        if (isPdfTab(currentTabIndex)) {
            saveCurrentTabState()
            pdfViewerManager?.closePdf()
            pdfViewerManager = null
            pdfFileUrl = null

            val currentTab = tabs[currentTabIndex]
            tabs[currentTabIndex] = currentTab.copy(
                id = UUID.randomUUID().toString(),
                url = "$moodleBaseUrl/my/",
                title = "My courses",
                thumbnail = null,
                webViewState = null
            )

            webView.visibility = View.VISIBLE
            pdfContainer.visibility = View.GONE
            webControlsLayout.visibility = View.VISIBLE
            pdfControlsLayout.visibility = View.GONE
            btnBack.visibility = View.VISIBLE
            btnOpenInBrowser.visibility = View.VISIBLE
        }

        val currentUrl = webView.url ?: ""

        val isOnMyCoursesPage = currentUrl.contains("/my/") || currentUrl == "$moodleBaseUrl/my/"
        val isOnAllCoursesPage = currentUrl.contains("/course/index.php")

        val shouldNavigate = when (searchType) {
            0 -> !isOnMyCoursesPage
            1 -> !isOnAllCoursesPage
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

        if (!isNetworkAvailable()) {
            L.d("MoodleFragment", "No network connection, skipping auto-login")
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
            console.log('=== Checking for session expired ===');
            console.log('Current URL: ' + window.location.href);
            console.log('Document title: ' + document.title);
            console.log('Referrer: ' + document.referrer);
            
            var errorDiv = document.evaluate('/html/body/div[2]/div[2]/div/div/div/div/div/div/div/div[1]', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
            
            if (errorDiv && errorDiv.classList.contains('alert')) {
                var errorText = errorDiv.textContent.trim().toLowerCase();
                console.log('Found error alert: "' + errorText + '"');
                
                if (errorText.includes('sitzung') || errorText.includes('session') || 
                    errorText.includes('expired') || errorText.includes('abgelaufen')) {
                    console.log('*** SESSION EXPIRED DETECTED ***');
                    return true;
                }
            }

            if (window.location.href.includes('/login/')) {
                console.log('*** ON LOGIN PAGE - Possible session loss ***');
                var loginForm = document.querySelector('form[action*="login"]');
                if (loginForm) {
                    console.log('Login form detected - session likely expired');
                }
            }
            
            console.log('No session expiry detected');
            return false;
        } catch(e) {
            console.error('Error checking session: ' + e.message);
            return false;
        }
    })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            val isExpired = result == "true"
            L.d("MoodleFragment", "Session expired check result: $isExpired")
            L.d("MoodleFragment", "WebView URL when checking: ${webView.url}")
            callback(isExpired)
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

        if (!isNetworkAvailable()) {
            Toast.makeText(requireContext(), getString(R.string.moodle_offline_error), Toast.LENGTH_LONG).show()
            return
        }

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
                sessionExpiredRetryCount = 0
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
                        sessionExpiredRetryCount = 0
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
                        if (sessionExpiredRetryCount == 0) {
                            L.d("MoodleFragment", "Session expired - retrying once")
                            sessionExpiredRetryCount = 1
                            isLoginInProgress = false

                            Handler(Looper.getMainLooper()).postDelayed({
                                val username = encryptedPrefs.getString("moodle_username", "") ?: ""
                                val password = encryptedPrefs.getString("moodle_password", "") ?: ""
                                if (username.isNotEmpty() && password.isNotEmpty()) {
                                    fillLoginForm(username, password)
                                }
                            }, 2000)
                        } else {
                            L.e("MoodleFragment", "Session expired on retry - entering cooldown")
                            isLoginInProgress = false
                            loginAttemptCount = 0
                            sessionExpiredDetected = true
                            sessionExpiredRetryCount = 0
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
                        }
                    } else {
                        L.e("MoodleFragment", "Unknown error - stopping")
                        isLoginInProgress = false
                        loginAttemptCount = 0
                        sessionExpiredRetryCount = 0
                        consecutiveLoginFailures = MAX_LOGIN_ATTEMPTS
                        hasLoginFailed = true
                        isLoginDialogShown = false
                    }
                } else {
                    L.w("MoodleFragment", "No error message found, login status unclear")
                    isLoginInProgress = false
                    sessionExpiredRetryCount = 0
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
                    navigateFromCurrentTab("$moodleBaseUrl/user/profile.php")
                    true
                }
                2 -> {
                    navigateFromCurrentTab("$moodleBaseUrl/grade/report/overview/index.php")
                    true
                }
                3 -> {
                    navigateFromCurrentTab("$moodleBaseUrl/calendar/view.php?view=month")
                    true
                }
                4 -> {
                    navigateFromCurrentTab("$moodleBaseUrl/user/files.php")
                    true
                }
                5 -> {
                    navigateFromCurrentTab("$moodleBaseUrl/reportbuilder/index.php")
                    true
                }
                6 -> {
                    navigateFromCurrentTab("$moodleBaseUrl/user/preferences.php")
                    true
                }
                7 -> {
                    navigateFromCurrentTab("$moodleBaseUrl/login/logout.php")
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

            WebStorage.getInstance().deleteAllData()

            context?.let { ctx ->
                ctx.cacheDir.deleteRecursively()
                ctx.codeCacheDir.deleteRecursively()

                cleanupOldCachedPdfs()

                Toast.makeText(ctx, getString(R.string.moodle_cache_cleared), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            L.e("MoodleFragment", "Error clearing cache", e)
            Toast.makeText(requireContext(), getString(R.string.moodle_cache_fetch_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun cleanupOldCachedPdfs() {
        try {
            context?.let { ctx ->
                val currentTime = System.currentTimeMillis()
                val maxAge = 24 * 60 * 60 * 1000L // 24 hours

                ctx.cacheDir.listFiles()?.filter { file ->
                    file.name.startsWith("moodle_pdf_") &&
                            (currentTime - file.lastModified()) > maxAge
                }?.forEach { it.delete() }
            }
        } catch (e: Exception) {
            L.e("MoodleFragment", "Error cleaning cached PDFs", e)
        }
    }

    fun clearMoodleData() {
        try {
            // clear webview cache
            webView.clearCache(true)
            webView.clearHistory()
            webView.clearFormData()

            // clear cookies
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }

            WebStorage.getInstance().deleteAllData()

            context?.let { ctx ->
                // clear cache
                ctx.cacheDir.deleteRecursively()
                ctx.codeCacheDir.deleteRecursively()

                // clear webview (general) data
                val dataDir = File(ctx.applicationInfo.dataDir)
                File(dataDir, "app_webview").deleteRecursively()
                File(dataDir, "app_WebView").deleteRecursively()

                // clear webview databases
                File(dataDir, "databases").listFiles()?.filter {
                    it.name.contains("webview", ignoreCase = true)
                }?.forEach { it.deleteRecursively() }

                // clear shared prefs
                File(dataDir, "shared_prefs").listFiles()?.filter {
                    it.name.contains("webview", ignoreCase = true)
                }?.forEach { it.delete() }

                // clear saved pdfs
                File(ctx.filesDir, "moodle_pdfs").deleteRecursively()
            }

            encryptedPrefs.edit { clear() }

            sharedPrefs.edit {
                remove("moodle_dont_show_login_dialog")
                remove("moodle_auto_dismiss_confirm")
                remove("moodle_debug_mode")
                remove("saved_program_course_name")
                remove("saved_timetable_entry_name")
                remove("saved_tabs")
                remove("moodle_tab_layout_compact")
                remove("last_moodle_cache_cleanup")
            }

            Toast.makeText(requireContext(), getString(R.string.moodle_data_cleared), Toast.LENGTH_SHORT).show()

            loadUrlInBackground(loginUrl)

        } catch (e: Exception) {
            L.e("MoodleFragment", "Error clearing data", e)
            Toast.makeText(requireContext(), getString(R.string.moodle_clear_data_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun performPeriodicCacheCleanup() {
        val lastCleanup = sharedPrefs.getLong("last_moodle_cache_cleanup", 0L)
        val now = System.currentTimeMillis()
        val cleanupInterval = 7 * 24 * 60 * 60 * 1000L

        if (now - lastCleanup > cleanupInterval) {
            backgroundExecutor.execute {
                try {
                    context?.let { ctx ->
                        ctx.cacheDir.listFiles()?.filter { file ->
                            (now - file.lastModified()) > cleanupInterval
                        }?.forEach { it.deleteRecursively() }

                        val permanentPdfDir = File(ctx.filesDir, "moodle_pdfs")
                        if (permanentPdfDir.exists()) {
                            val activePdfPaths = tabs.filter { isPdfTab(tabs.indexOf(it)) }
                                .mapNotNull { it.webViewState?.getString("pdf_file_path") }
                                .toSet()

                            permanentPdfDir.listFiles()?.forEach { file ->
                                if (file.absolutePath !in activePdfPaths &&
                                    (now - file.lastModified()) > cleanupInterval) {
                                    file.delete()
                                    L.d("MoodleFragment", "Deleted orphaned PDF: ${file.name}")
                                }
                            }
                        }

                        sharedPrefs.edit {
                            putLong("last_moodle_cache_cleanup", now)
                        }

                        L.d("MoodleFragment", "Periodic cache cleanup completed")
                    }
                } catch (e: Exception) {
                    L.e("MoodleFragment", "Error in periodic cleanup", e)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupSharedPreferences()
        performPeriodicCacheCleanup()

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

    private fun cleanupTemporaryPdfs() {
        try {
            context?.let { ctx ->
                val cacheDir = ctx.cacheDir
                val currentTime = System.currentTimeMillis()
                val maxAge = 24 * 60 * 60 * 1000L

                cacheDir.listFiles()?.filter { file ->
                    file.name.startsWith("moodle_pdf_") &&
                            (currentTime - file.lastModified()) > maxAge
                }?.forEach { file ->
                    try {
                        file.delete()
                        L.d("MoodleFragment", "Deleted old cached PDF: ${file.name}")
                    } catch (e: Exception) {
                        L.e("MoodleFragment", "Failed to delete cached PDF: ${file.name}", e)
                    }
                }
            }
        } catch (e: Exception) {
            L.e("MoodleFragment", "Error cleaning temporary PDFs", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        pdfViewerManager?.closePdf()
        savePinnedTabs()
        tabs.forEach { it.thumbnail?.recycle() }
        pdfRenderer?.close()
        backgroundExecutor.shutdown()
        cleanupRefreshIndicator()
        scrollEndCheckRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        scrollStopRunnable?.let { scrollStopHandler?.removeCallbacks(it) }
        cleanupFetchProcess()

        cleanupTemporaryPdfs()
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

        AlertDialog.Builder(requireContext()) // debug only
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
                        AlertDialog.Builder(requireContext()) // debug only
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

        AlertDialog.Builder(requireContext()) // debug only
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

        AlertDialog.Builder(requireContext()) // debug only
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

    private fun showSearchProgressDialog(category: String, summary: String): AlertDialog {
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

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun searchForMoodleEntry(category: String, summary: String, entryId: String) {
        L.d("MoodleFragment", "Searching for Moodle entry - Category: $category, Summary: $summary (ID: $entryId)")

        if (!isNetworkAvailable()) {
            Toast.makeText(requireContext(), getString(R.string.moodle_offline_error), Toast.LENGTH_LONG).show()
            return
        }

        searchCancelled = false
        searchProgressDialog = showSearchProgressDialog(category, summary)

        val currentUrl = webView.url ?: ""
        val isOnLoginPage = currentUrl == loginUrl || currentUrl.contains("login/index.php")

        if (isOnLoginPage) {
            updateSearchProgress(15, getString(R.string.moodle_please_login))
            searchProgressDialog?.hide()

            Toast.makeText(
                requireContext(),
                getString(R.string.moodle_please_login_to_continue),
                Toast.LENGTH_LONG
            ).show()

            monitorLoginCompletionForSearch(category, summary, entryId)
        } else {
            continueSearchAfterLogin(category, summary, entryId)
        }
    }

    private fun monitorLoginCompletionForSearch(category: String, summary: String, entryId: String) {
        val searchStartTime = System.currentTimeMillis()
        val checkInterval = 1000L
        val timeout = 180000L // 3 minutes
        val handler = Handler(Looper.getMainLooper())

        val loginCheckRunnable = object : Runnable {
            override fun run() {
                if (searchCancelled || System.currentTimeMillis() - searchStartTime > timeout) {
                    hideSearchProgressDialog()
                    if (!searchCancelled) {
                        Toast.makeText(requireContext(), getString(R.string.moodle_search_timeout), Toast.LENGTH_SHORT).show()
                    }
                    return
                }

                val currentUrl = webView.url ?: ""
                val isStillOnLoginPage = currentUrl == loginUrl || currentUrl.contains("login/index.php")

                if (!isStillOnLoginPage) {
                    searchProgressDialog?.show()
                    updateSearchProgress(20, getString(R.string.moodle_login_successful))

                    Handler(Looper.getMainLooper()).postDelayed({
                        continueSearchAfterLogin(category, summary, entryId)
                    }, 500)
                } else {
                    handler.postDelayed(this, checkInterval)
                }
            }
        }

        handler.post(loginCheckRunnable)
    }

    private fun continueSearchAfterLogin(category: String, summary: String, entryId: String) {
        if (searchCancelled) return

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

        val currentUrl = webView.url ?: ""
        val isOnMyCoursesPage = currentUrl.contains("/my/") || currentUrl == "$moodleBaseUrl/my/"
        val initialDelay = if (isOnMyCoursesPage) 300L else 1000L

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

                                    searchProgressDialog?.let { dialog ->
                                        val dialogView = dialog.findViewById<View>(android.R.id.content)
                                        val tvSearchTitle = dialogView?.findViewById<TextView>(R.id.tvSearchTitle)
                                        tvSearchTitle?.text = getString(R.string.moodle_searching_for, summary)
                                    }

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
            }, initialDelay)
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

                val jsCode = """
                (function() {
                    var courseLink = document.evaluate(
                        '/html/body/div[1]/div[2]/div/div[1]/div/div/section/section[1]/div/div/div[1]/div[2]/div/div/div[1]/div/ul/li[1]/div/div[2]/a',
                        document,
                        null,
                        XPathResult.FIRST_ORDERED_NODE_TYPE,
                        null
                    ).singleNodeValue;
                    
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
        //Toast.makeText(requireContext(), getString(R.string.moodle_refreshing), Toast.LENGTH_SHORT).show()

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
        if (isPdfTab(currentTabIndex)) {
            return
        }

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
            val isPdf = isPdfTab(currentTabIndex)

            if (isPdf) {
                isAtTop = true
                if (!extendedHeaderLayout.isVisible) {
                    showExtendedHeaderWithAnimation()
                }
                return
            }

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

        popup.menu.add(0, 1, 0, getString(R.string.moodle_open_in_new_tab)).apply {
            setIcon(R.drawable.ic_tab_background)
        }
        popup.menu.add(0, 2, 0, getString(R.string.moodle_copy_url)).apply {
            setIcon(R.drawable.ic_import_clipboard)
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
                    createNewTab(currentUrl)
                    Toast.makeText(requireContext(), getString(R.string.moodle_tab_opened_in_new_tab), Toast.LENGTH_SHORT).show() // strings.xml
                    true
                }
                2 -> {
                    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText(getString(R.string.moodle_url), currentUrl)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(requireContext(), getString(R.string.moodle_url_copied), Toast.LENGTH_SHORT).show()
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
        if (isPullToRefreshActive) {
            return
        }

        val startTime = System.currentTimeMillis()

        analyzeLinkCapabilities(url) { linkInfo ->
            val elapsed = System.currentTimeMillis() - startTime
            val delay = if (elapsed < 150) 150 - elapsed else 0

            Handler(Looper.getMainLooper()).postDelayed({
                activity?.runOnUiThread {
                    if (!isPullToRefreshActive) {
                        showLinkOptionsDialog(url, linkInfo)
                    }
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
                            var container = link.closest('.activity-item, .activityinstance');
                            if (container) {
                                var activityIcon = container.querySelector('img.activityicon, img[data-region="activity-icon"]');
                                if (activityIcon && activityIcon.src) {
                                    var imgSrc = activityIcon.src.toLowerCase();

                                    if (imgSrc.includes('/f/image?')) {
                                        linkInfo.isImage = true;
                                        linkInfo.isDownloadable = false;
                                        linkInfo.isCopyable = false;
                                        break;
                                    }
                                    else if (imgSrc.includes('/f/pdf')) {
                                        linkInfo.isDownloadable = true;
                                        linkInfo.isCopyable = true;
                                        linkInfo.fileType = 'pdf';
                                    }
                                    else if (imgSrc.includes('/f/document') || imgSrc.includes('/f/docx')) {
                                        linkInfo.isDownloadable = true;
                                        linkInfo.isCopyable = true;
                                        linkInfo.fileType = 'docx';
                                    }
                                }
                            }
                        }
                    }
                    
                    if (linkInfo.isDownloadable || linkInfo.isCopyable || linkInfo.isImage) {
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

        // open in new tab (add only when not downloadable file)
        if (!linkInfo.isDownloadable || linkInfo.isImage) {
            options.add(Pair(getString(R.string.moodle_open_in_new_tab), R.drawable.ic_tab_background))
            actions.add {
                createNewTab(url)
                Toast.makeText(requireContext(), getString(R.string.moodle_tab_opened_in_new_tab), Toast.LENGTH_SHORT).show()
            }
        }

        // download to user device
        if (linkInfo.isDownloadable && !linkInfo.isImage) {
            options.add(Pair(getString(R.string.moodle_download), R.drawable.ic_download))
            actions.add {
                if (url.contains("/mod/resource/view.php")) {
                    resolveDownloadUrl(url, cookies, forceDownload = true)
                } else {
                    if (url.endsWith(".docx", ignoreCase = true)) {
                        handleDocxFile(url, cookies, forceDownload = true)
                    } else {
                        downloadToDeviceWithCookies(url, cookies)
                    }
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

                if (tab.id.startsWith(PDF_TAB_PREFIX)) {
                    tab.webViewState?.let { bundle ->
                        put("pdf_file_path", bundle.getString("pdf_file_path"))
                        put("pdf_current_page", bundle.getInt("pdf_current_page", 0))
                        put("pdf_scroll_mode", bundle.getBoolean("pdf_scroll_mode", true))
                        put("pdf_dark_mode", bundle.getBoolean("pdf_dark_mode", false))
                    }
                }

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

                val webViewState = if (tabId.startsWith(PDF_TAB_PREFIX)) {
                    Bundle().apply {
                        putString("pdf_file_path", tabJson.optString("pdf_file_path"))
                        putInt("pdf_current_page", tabJson.optInt("pdf_current_page", 0))
                        putBoolean("pdf_scroll_mode", tabJson.optBoolean("pdf_scroll_mode", true))
                        putBoolean("pdf_dark_mode", tabJson.optBoolean("pdf_dark_mode", false))
                    }
                } else null

                loadedTabs.add(TabInfo(
                    id = tabId,
                    url = tabJson.getString("url"),
                    title = tabJson.getString("title"),
                    isPinned = true,
                    isDefault = false,
                    thumbnail = thumbnail,
                    webViewState = webViewState,
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
            if (webView.width <= 0 || webView.height <= 0) {
                L.w("MoodleFragment", "WebView has invalid dimensions: ${webView.width}x${webView.height}")
                return null
            }

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

            if (isPdfTab(currentTabIndex)) {
                val pdfState = pdfViewerManager?.getCurrentState()
                val bundle = Bundle().apply {
                    putString("pdf_file_path", pdfState?.file?.absolutePath)
                    putInt("pdf_current_page", pdfState?.currentPage ?: 0)
                    putBoolean("pdf_scroll_mode", pdfState?.scrollMode ?: true)
                    putBoolean("pdf_dark_mode", pdfState?.darkMode ?: false)
                }

                tabs[currentTabIndex] = currentTab.copy(
                    url = currentTab.url,
                    title = currentTab.title,
                    thumbnail = capturePdfThumbnail(),
                    webViewState = bundle
                )
            } else {
                val bundle = Bundle()
                webView.saveState(bundle)

                tabs[currentTabIndex] = currentTab.copy(
                    url = webView.url ?: currentTab.url,
                    title = webView.title ?: currentTab.title,
                    thumbnail = captureWebViewThumbnail(),
                    webViewState = bundle
                )
            }

            savePinnedTabs()
        }
    }

    private fun capturePdfThumbnail(): Bitmap? {
        return try {
            val bitmap = pdfViewerManager?.renderPage(0)
            bitmap?.scale(bitmap.width / 4, bitmap.height / 4)
        } catch (_: Exception) {
            null
        }
    }

    private fun switchToTab(index: Int) {
        if (index !in tabs.indices) return

        if (index == currentTabIndex) {
            L.d("MoodleFragment", "Already on tab $index, skipping switch")
            return
        }

        L.d("MoodleFragment", "Switching from tab $currentTabIndex to tab $index")

        saveCurrentTabState()

        val previousIndex = currentTabIndex
        currentTabIndex = index

        val tab = tabs[index]

        if (isPdfTab(index)) {
            loadPdfTab(tab)
        } else {
            if (isPdfTab(previousIndex)) {
                pdfViewerManager?.closePdf()
                pdfViewerManager = null
                pdfFileUrl = null
            }

            activity?.runOnUiThread {
                webView.visibility = View.VISIBLE
                pdfContainer.visibility = View.GONE
                webControlsLayout.visibility = View.VISIBLE
                pdfControlsLayout.visibility = View.GONE
                btnBack.visibility = View.VISIBLE
                btnOpenInBrowser.visibility = View.VISIBLE
            }

            if (tab.webViewState != null) {
                webView.restoreState(tab.webViewState)
            } else {
                webView.loadUrl(tab.url)
            }
        }

        updateUIState()
        updateDashboardButtonIcon()

        L.d("MoodleFragment", "Switch complete - now on tab $currentTabIndex showing ${tab.url}")
    }

    private fun loadPdfTab(tab: TabInfo) {
        val pdfFilePath = tab.webViewState?.getString("pdf_file_path")

        if (pdfFilePath != null) {
            val pdfFile = File(pdfFilePath)

            if (pdfFile.exists()) {
                pdfViewerManager?.closePdf()
                pdfViewerManager = null

                pdfViewerManager = PdfViewerManager(requireContext())

                if (pdfViewerManager?.openPdf(pdfFile) == true) {
                    val savedPage = tab.webViewState.getInt("pdf_current_page", 0)
                    val scrollMode = tab.webViewState.getBoolean("pdf_scroll_mode", true)
                    val darkMode = tab.webViewState.getBoolean("pdf_dark_mode", isDarkTheme)

                    pdfViewerManager?.setCurrentPage(savedPage)
                    if (!scrollMode) pdfViewerManager?.toggleScrollMode()
                    if (darkMode != isDarkTheme) pdfViewerManager?.toggleDarkMode()

                    webView.visibility = View.GONE
                    pdfContainer.visibility = View.VISIBLE
                    webControlsLayout.visibility = View.GONE
                    pdfControlsLayout.visibility = View.VISIBLE

                    btnBack.visibility = View.GONE
                    btnOpenInBrowser.visibility = View.VISIBLE

                    setupPdfControls()
                    renderPdfContent()

                    isAtTop = true
                    showExtendedHeaderWithAnimation()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.moodle_pdf_load_failed), Toast.LENGTH_SHORT).show()
                    closeTab(currentTabIndex)
                }
            } else {
                Toast.makeText(requireContext(), getString(R.string.moodle_pdf_file_not_found), Toast.LENGTH_SHORT).show()
                closeTab(currentTabIndex)
            }
        }
    }

    private fun setupPdfControls() {
        btnOpenInBrowser2.setOnClickListener {
            val pdfUrl = pdfFileUrl ?: tabs.getOrNull(currentTabIndex)?.url
            if (!pdfUrl.isNullOrBlank()) {
                openInExternalBrowser(pdfUrl)
            } else {
                Toast.makeText(requireContext(), getString(R.string.moodle_no_url_to_open), Toast.LENGTH_SHORT).show()
            }
        }

        btnOpenInBrowser2.setOnLongClickListener {
            showPdfGlobeButtonMenu()
            true
        }

        pdfPageCounter.setOnClickListener {
            showPdfPageMenu()
        }

        pdfKebabMenu.setOnClickListener {
            showPdfOptionsMenu()
        }

        updatePdfControls()
    }

    private fun showPdfGlobeButtonMenu() {
        val pdfUrl = pdfFileUrl ?: tabs.getOrNull(currentTabIndex)?.url
        if (pdfUrl.isNullOrBlank()) {
            Toast.makeText(requireContext(), getString(R.string.moodle_no_url), Toast.LENGTH_SHORT).show()
            return
        }

        val popup = PopupMenu(requireContext(), btnOpenInBrowser2)

        popup.menu.add(0, 1, 0, getString(R.string.moodle_open_in_new_tab)).apply {
            setIcon(R.drawable.ic_tab_background)
        }
        popup.menu.add(0, 2, 0, getString(R.string.moodle_copy_url)).apply {
            setIcon(R.drawable.ic_import_clipboard)
        }

        try {
            val fieldMPopup = PopupMenu::class.java.getDeclaredField("mPopup")
            fieldMPopup.isAccessible = true
            val mPopup = fieldMPopup.get(popup)
            mPopup.javaClass
                .getDeclaredMethod("setForceShowIcon", Boolean::class.java)
                .invoke(mPopup, true)
        } catch (_: Exception) { }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    createNewTabFromPdf(pdfUrl)
                    Toast.makeText(requireContext(), getString(R.string.moodle_tab_opened_in_new_tab), Toast.LENGTH_SHORT).show()
                    true
                }
                2 -> {
                    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText(getString(R.string.moodle_url), pdfUrl)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(requireContext(), getString(R.string.moodle_url_copied), Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun createNewTabFromPdf(url: String) {
        if (tabs.size >= MAX_TABS) {
            Toast.makeText(requireContext(), getString(R.string.moodle_max_tabs_reached), Toast.LENGTH_SHORT).show()
            return
        }

        saveCurrentTabState()

        val newTab = TabInfo(
            url = url,
            title = getString(R.string.act_set_loading)
        )
        tabs.add(newTab)
        currentTabIndex = tabs.size - 1

        webView.visibility = View.VISIBLE
        pdfContainer.visibility = View.GONE
        webControlsLayout.visibility = View.VISIBLE
        pdfControlsLayout.visibility = View.GONE
        btnBack.visibility = View.VISIBLE

        webView.loadUrl(url)
        updateUIState()
    }

    private fun renderPdfContent() {
        if (pdfViewerManager?.scrollModeEnabled == true) {
            pdfScrollContainer.visibility = View.VISIBLE
            pdfSinglePageContainer.visibility = View.GONE
            renderAllPdfPages()
            setupScrollListener()
            updatePdfControlsForScrollMode()
        } else {
            pdfScrollContainer.visibility = View.GONE
            pdfSinglePageContainer.visibility = View.VISIBLE
            renderSinglePdfPage(pdfViewerManager?.getCurrentPage() ?: 0)
            setupSwipeGestures()
            updatePdfControls()
        }
    }

    private fun updatePdfControlsForScrollMode() {
        pdfScrollContainer.viewTreeObserver.addOnScrollChangedListener {
            updateCurrentPageFromScroll()
        }
        updatePdfControls()
    }

    private fun updateCurrentPageFromScroll() {
        val scrollY = pdfScrollContainer.scrollY
        var currentPageIndex = 0
        var accumulatedHeight = 0

        for (i in 0 until pdfPagesContainer.childCount) {
            val child = pdfPagesContainer.getChildAt(i)
            accumulatedHeight += child.height + child.marginBottom
            if (scrollY < accumulatedHeight) {
                currentPageIndex = i
                break
            }
        }

        if (currentPageIndex != pdfViewerManager?.getCurrentPage()) {
            pdfViewerManager?.setCurrentPage(currentPageIndex)
            updatePdfControls()
        }
    }

    private val View.marginBottom: Int
        get() = (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0

    private fun setupScrollListener() {
        if (scrollStopHandler == null) {
            scrollStopHandler = Handler(Looper.getMainLooper())
        }

        pdfScrollContainer.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (ignoreScrollUpdates) {
                return@setOnScrollChangeListener
            }

            val wasAtTop = isAtTop
            isAtTop = scrollY <= scrollThreshold

            if (wasAtTop != isAtTop) {
                if (isAtTop) {
                    showExtendedHeaderWithAnimation()
                } else {
                    hideExtendedHeaderWithAnimation()
                }
            }

            isScrolling = true

            scrollStopRunnable?.let { scrollStopHandler?.removeCallbacks(it) }

            loadVisiblePages()

            if (!isNavigating) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastUnloadTime > UNLOAD_THROTTLE_MS) {
                    lastUnloadTime = currentTime
                    unloadDistantPages()
                }
            }

            scrollStopRunnable = Runnable {
                isScrolling = false
                if (!isNavigating && !ignoreScrollUpdates) {
                    unloadDistantPages()
                }
            }
            scrollStopHandler?.postDelayed(scrollStopRunnable!!, 200)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSwipeGestures() {
        var startX = 0f
        var startY = 0f

        pdfSinglePageView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = event.x - startX
                    val deltaY = event.y - startY

                    if (abs(deltaX) > abs(deltaY) && abs(deltaX) > 100) {
                        if (deltaX > 0) {
                            // swipe right -> previous page
                            val currentPage = pdfViewerManager?.getCurrentPage() ?: 0
                            if (currentPage > 0) {
                                pdfViewerManager?.setCurrentPage(currentPage - 1)
                                renderSinglePdfPage(currentPage - 1)
                                updatePdfControls()
                            }
                        } else {
                            // swipe left -> next page
                            val currentPage = pdfViewerManager?.getCurrentPage() ?: 0
                            val pageCount = pdfViewerManager?.getPageCount() ?: 0
                            if (currentPage < pageCount - 1) {
                                pdfViewerManager?.setCurrentPage(currentPage + 1)
                                renderSinglePdfPage(currentPage + 1)
                                updatePdfControls()
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun createNewTab(url: String = "$moodleBaseUrl/my/") {
        if (tabs.size >= MAX_TABS) {
            Toast.makeText(requireContext(), getString(R.string.moodle_max_tabs_reached), Toast.LENGTH_SHORT).show()
            return
        }

        saveCurrentTabState()

        val wasPdfTab = isPdfTab(currentTabIndex)
        if (wasPdfTab) {
            pdfViewerManager?.closePdf()
        }

        val newTab = TabInfo(
            url = url,
            title = getString(R.string.moodle_new_tab)
        )
        tabs.add(newTab)
        currentTabIndex = tabs.size - 1

        webView.visibility = View.VISIBLE
        pdfContainer.visibility = View.GONE
        webControlsLayout.visibility = View.VISIBLE
        pdfControlsLayout.visibility = View.GONE
        btnBack.visibility = View.VISIBLE
        btnOpenInBrowser.visibility = View.VISIBLE

        webView.loadUrl(url)
        updateUIState()
        updateDashboardButtonIcon()

        if (isTabViewVisible) {
            hideTabOverlay()
        }
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return

        if (tabs.size == 1) {
            Toast.makeText(requireContext(), getString(R.string.moodle_cannot_close_last_tab), Toast.LENGTH_SHORT).show()
            return
        }

        val wasClosingCurrentTab = (index == currentTabIndex)

        val tabToClose = tabs[index]
        if (isPdfTab(index) && !tabToClose.isPinned) {
            tabToClose.webViewState?.getString("pdf_file_path")?.let { pdfPath ->
                try {
                    File(pdfPath).delete()
                    L.d("MoodleFragment", "Deleted PDF file for closed tab: $pdfPath")
                } catch (e: Exception) {
                    L.e("MoodleFragment", "Failed to delete PDF file", e)
                }
            }
        }

        tabs[index].thumbnail?.recycle()
        tabs.removeAt(index)

        val newCurrentIndex = when {
            index < currentTabIndex -> currentTabIndex - 1
            index == currentTabIndex -> {
                if (index >= tabs.size) tabs.size - 1 else index
            }
            else -> currentTabIndex
        }

        if (wasClosingCurrentTab) {
            switchToTab(newCurrentIndex)
        } else {
            currentTabIndex = newCurrentIndex
        }

        savePinnedTabs()

        if (isTabViewVisible && !isDraggingTab) {
            tabAdapter.notifyDataSetChanged()
        }
    }

    private fun clearAllTabs() {
        tabs.forEach { it.thumbnail?.recycle() }
        tabs.clear()

        tabs.add(TabInfo(
            id = "default_tab",
            url = "$moodleBaseUrl/my/",
            title = "Dashboard",
            isDefault = true
        ))
        currentTabIndex = 0
        webView.loadUrl("$moodleBaseUrl/my/")

        savePinnedTabs()
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

            if (isPdfTab(currentTabIndex)) {
                val pdfState = pdfViewerManager?.getCurrentState()
                val bundle = Bundle().apply {
                    putString("pdf_file_path", pdfState?.file?.absolutePath)
                    putInt("pdf_current_page", pdfState?.currentPage ?: 0)
                    putBoolean("pdf_scroll_mode", pdfState?.scrollMode ?: true)
                    putBoolean("pdf_dark_mode", pdfState?.darkMode ?: false)
                }

                tabs[currentTabIndex] = currentTab.copy(
                    url = pdfFileUrl ?: currentTab.url,
                    title = currentTab.title,
                    thumbnail = capturePdfThumbnail(),
                    webViewState = bundle
                )
            } else {
                tabs[currentTabIndex] = currentTab.copy(
                    url = webView.url ?: currentTab.url,
                    title = webView.title ?: currentTab.title,
                    thumbnail = captureWebViewThumbnail()
                )
            }
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
            // Normal view
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

    @SuppressLint("ClickableViewAccessibility")
    private fun setupLayoutToggle(handle: View, overlayView: View) {
        handle.alpha = 0.6f
        handle.scaleX = 1f
        handle.scaleY = 1f

        var startY = 0f
        var isDragging = false
        var overlayHeight: Int
        var initialOverlayHeight = 0
        val density = resources.displayMetrics.density
        val minHeight = (190 * density).toInt()
        val maxHeight = (310 * density).toInt()
        val closeThreshold = (50 * density).toInt()

        overlayHeight = if (isCompactTabLayout) minHeight else maxHeight
        val layoutParams = overlayView.layoutParams
        layoutParams.height = overlayHeight
        overlayView.layoutParams = layoutParams

        var lastCompactState = isCompactTabLayout

        handle.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    initialOverlayHeight = overlayView.layoutParams.height
                    isDragging = true
                    lastCompactState = isCompactTabLayout

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
                    val newHeight = (initialOverlayHeight + deltaY).toInt()
                        .coerceIn(closeThreshold, maxHeight)

                    val layoutParams = overlayView.layoutParams
                    layoutParams.height = newHeight
                    overlayView.layoutParams = layoutParams

                    val heightRatio = (newHeight - minHeight).toFloat() / (maxHeight - minHeight)

                    val shouldBeCompact = heightRatio < 0.3f

                    if (shouldBeCompact != isCompactTabLayout) {
                        isCompactTabLayout = shouldBeCompact
                        refreshTabLayoutDuringDrag(overlayView)
                        lastCompactState = isCompactTabLayout
                    }

                    if (newHeight < minHeight) {
                        val closeProgress = (minHeight - newHeight).toFloat() / (minHeight - closeThreshold)
                        overlayView.alpha = 1f - (closeProgress * 0.5f)
                    } else {
                        overlayView.alpha = 1f
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

                    if (currentHeight != targetHeight) {
                        ValueAnimator.ofInt(currentHeight, targetHeight).apply {
                            duration = 200
                            addUpdateListener { animator ->
                                layoutParams.height = animator.animatedValue as Int
                                overlayView.layoutParams = layoutParams
                            }
                            doOnEnd {
                                if (lastCompactState != isCompactTabLayout) {
                                    setupTabOverlayControls(overlayView)
                                }
                            }
                            start()
                        }
                    } else if (lastCompactState != isCompactTabLayout) {
                        setupTabOverlayControls(overlayView)
                    }

                    overlayHeight = targetHeight
                    overlayView.alpha = 1f

                    sharedPrefs.edit {
                        putBoolean("moodle_tab_layout_compact", isCompactTabLayout)
                    }

                    true
                }
                else -> false
            }
        }
    }

    private fun refreshTabLayoutDuringDrag(overlayView: View) {
        overlayView.animate()
            .alpha(0.85f)
            .setDuration(100)
            .withEndAction {
                overlayView.animate()
                    .alpha(1f)
                    .setDuration(100)
                    .start()
            }
            .start()

        val btnClearAll = overlayView.findViewById<ImageButton>(R.id.btnClearAllTabs)
        val btnCloseOverlay = overlayView.findViewById<ImageButton>(R.id.btnCloseTabOverlay)
        val layoutToggleHandle = overlayView.findViewById<View>(R.id.layoutToggleHandle)
        val tabHeaderLayout = overlayView.findViewById<LinearLayout>(R.id.tabHeaderLayout)

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

        tabRecyclerView = overlayView.findViewById(R.id.tabRecyclerView)
        tabRecyclerView.animate()
            .scaleX(0.97f)
            .scaleY(0.97f)
            .setDuration(80)
            .withEndAction {
                tabRecyclerView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(120)
                    .start()
            }
            .start()

        setupTabRecyclerView(overlayView)
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
            horizontalSpacing = (4 * density).toInt()
            sideMargin = (6 * density).toInt()
            val totalHorizontalPadding = (2 * sideMargin) + (3 * horizontalSpacing)
            itemWidth = ((screenWidth - totalHorizontalPadding) / 4.46f).toInt()
            itemHeight = (100 * density).toInt()
        } else {
            horizontalSpacing = (6 * density).toInt()
            sideMargin = (8 * density).toInt()
            val totalHorizontalPadding = (2 * sideMargin) + horizontalSpacing
            itemWidth = ((screenWidth - totalHorizontalPadding) / 2.27f).toInt()
            itemHeight = (180 * density).toInt()
        }

        val floatingDeleteZone = view?.findViewById<FrameLayout>(R.id.floatingDeleteZone)
        val floatingDeleteIcon = view?.findViewById<ImageView>(R.id.floatingDeleteIcon)

        tabAdapter = TabAdapter(
            tabs = tabs,
            getCurrentTabIndex = { currentTabIndex },
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
        if (index !in tabs.indices) return

        val viewHolder = tabRecyclerView.findViewHolderForAdapterPosition(index)
        viewHolder?.itemView?.let { itemView ->
            itemView.animate()
                .alpha(0f)
                .scaleX(0.8f)
                .scaleY(0.8f)
                .translationX(itemView.width.toFloat())
                .setDuration(200)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    closeTab(index)
                    tabAdapter.notifyDataSetChanged()
                }
                .start()
        } ?: run {
            closeTab(index)
            tabAdapter.notifyDataSetChanged()
        }
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

    private fun showPdfViewer(pdfFile: File, fileName: String, pdfUrl: String) {
        if (tabs.size >= MAX_TABS) {
            Toast.makeText(requireContext(), getString(R.string.moodle_max_tabs_reached), Toast.LENGTH_SHORT).show()
            return
        }

        val permanentPdfDir = File(requireContext().filesDir, "moodle_pdfs")
        if (!permanentPdfDir.exists()) {
            permanentPdfDir.mkdirs()
        }

        val uniqueFileName = "${System.currentTimeMillis()}_${UUID.randomUUID()}_${fileName}"
        val permanentPdfFile = File(permanentPdfDir, uniqueFileName)

        if (permanentPdfFile.exists()) {
            permanentPdfFile.delete()
        }
        pdfFile.copyTo(permanentPdfFile, overwrite = false)

        saveCurrentTabState()

        if (isPdfTab(currentTabIndex)) {
            pdfViewerManager?.closePdf()
            pdfViewerManager = null
        }

        val actualPdfUrl = pdfFileUrl ?: pdfUrl
        val cleanFileName = fileName.removeSuffix(".pdf")

        val pdfTab = TabInfo(
            id = "$PDF_TAB_PREFIX${System.currentTimeMillis()}",
            url = actualPdfUrl,
            title = cleanFileName,
            isPinned = false,
            isDefault = false,
            thumbnail = null,
            webViewState = Bundle().apply {
                putString("pdf_file_path", permanentPdfFile.absolutePath)
                putInt("pdf_current_page", 0)
                putBoolean("pdf_scroll_mode", true)
                putBoolean("pdf_dark_mode", isDarkTheme)
            }
        )
        tabs.add(pdfTab)
        currentTabIndex = tabs.size - 1

        loadPdfTab(pdfTab)

        Handler(Looper.getMainLooper()).postDelayed({
            if (currentTabIndex in tabs.indices && tabs[currentTabIndex].id == pdfTab.id) {
                tabs[currentTabIndex] = tabs[currentTabIndex].copy(
                    thumbnail = capturePdfThumbnail()
                )
                savePinnedTabs()
            }
        }, 500)

        if (isTabViewVisible) {
            hideTabOverlay()
        }
    }

    private fun renderAllPdfPages() {
        pdfPagesContainer.removeAllViews()

        val pageCount = pdfViewerManager?.getPageCount() ?: 0

        for (page in 0 until pageCount) {
            val imageView = ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (16 * resources.displayMetrics.density).toInt()
                }
                adjustViewBounds = true
                tag = page

                setBackgroundColor(android.graphics.Color.LTGRAY)
                minimumHeight = (800 * resources.displayMetrics.density).toInt()
            }

            if (page < 3) {
                val bitmap = pdfViewerManager?.renderPage(page)
                if (bitmap != null && !bitmap.isRecycled) {
                    imageView.setImageBitmap(bitmap)
                    imageView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    imageView.minimumHeight = 0
                }
            }

            pdfPagesContainer.addView(imageView)
        }

        pdfScrollContainer.viewTreeObserver.addOnScrollChangedListener {
            loadVisiblePages()
        }
    }

    private fun loadVisiblePages() {
        val scrollY = pdfScrollContainer.scrollY
        val height = pdfScrollContainer.height

        val currentlyVisiblePages = mutableSetOf<Int>()
        val pagesToLoad = mutableListOf<Pair<Int, Int>>()

        for (i in 0 until pdfPagesContainer.childCount) {
            val child = pdfPagesContainer.getChildAt(i)
            val childTop = child.top
            val childBottom = child.bottom
            val pageNumber = child.tag as? Int ?: continue

            val isInViewport = childBottom > scrollY && childTop < scrollY + height
            val isNearby = childBottom > scrollY - height * 1.5 && childTop < scrollY + height * 2.5

            if (isInViewport) {
                currentlyVisiblePages.add(pageNumber)
            }

            val imageView = child as? ImageView ?: continue

            if (isNearby && imageView.drawable == null) {
                val distanceFromViewport = when {
                    childTop > scrollY + height -> childTop - (scrollY + height)
                    childBottom < scrollY -> scrollY - childBottom
                    else -> 0
                }
                pagesToLoad.add(Pair(pageNumber, distanceFromViewport))
            }
        }

        pdfViewerManager?.updateDisplayedPages(currentlyVisiblePages)

        pagesToLoad.sortedBy { it.second }.take(5).forEach { (pageNumber, _) ->
            loadPageInBackground(pageNumber)
        }
    }

    private fun loadPageInBackground(pageNumber: Int) {
        backgroundExecutor.execute {
            try {
                val bitmap = pdfViewerManager?.renderPage(pageNumber, forceRender = false)

                if (bitmap != null && !bitmap.isRecycled) {
                    activity?.runOnUiThread {
                        if (bitmap.isRecycled) {
                            L.w("MoodleFragment", "Bitmap for page $pageNumber was recycled before UI update")
                            return@runOnUiThread
                        }

                        val child = pdfPagesContainer.getChildAt(pageNumber)
                        val imageView = child as? ImageView

                        if (imageView != null && imageView.drawable == null) {
                            try {
                                imageView.setImageBitmap(bitmap)
                                imageView.setBackgroundColor(android.graphics.Color.TRANSPARENT)

                                imageView.minimumHeight = 0
                                val layoutParams = imageView.layoutParams
                                layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                                imageView.layoutParams = layoutParams
                            } catch (e: Exception) {
                                L.e("MoodleFragment", "Failed to set bitmap on ImageView", e)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                L.e("MoodleFragment", "Error loading page $pageNumber in background", e)
            }
        }
    }

    private fun unloadDistantPages() {
        if (isScrolling || isNavigating) return

        val scrollY = pdfScrollContainer.scrollY
        val height = pdfScrollContainer.height

        val pagesToUnload = mutableListOf<Int>()

        for (i in 0 until pdfPagesContainer.childCount) {
            val child = pdfPagesContainer.getChildAt(i)
            val childTop = child.top
            val childBottom = child.bottom
            val pageNumber = child.tag as? Int ?: continue

            val imageView = child as? ImageView ?: continue

            val isFarAway = (childBottom < scrollY - height * 1.5) || (childTop > scrollY + height * 2.5)
            val hasContent = imageView.drawable != null

            if (isFarAway && hasContent) {
                pagesToUnload.add(pageNumber)
            }
        }

        if (pagesToUnload.isNotEmpty()) {
            L.d("MoodleFragment", "Unloading ${pagesToUnload.size} distant pages")
        }

        pagesToUnload.forEach { pageNumber ->
            val child = pdfPagesContainer.getChildAt(pageNumber)
            val imageView = child as? ImageView ?: return@forEach

            val currentHeight = imageView.height

            imageView.setImageDrawable(null)
            imageView.setBackgroundColor(android.graphics.Color.LTGRAY)

            if (currentHeight > 0) {
                imageView.minimumHeight = currentHeight
                val layoutParams = imageView.layoutParams
                layoutParams.height = currentHeight
                imageView.layoutParams = layoutParams
            } else {
                imageView.minimumHeight = (800 * resources.displayMetrics.density).toInt()
            }
        }
    }

    private fun renderSinglePdfPage(page: Int) {
        val bitmap = pdfViewerManager?.renderPage(page)
        pdfSinglePageView.setImageBitmap(bitmap)
    }

    private fun updatePdfControls() {
        val currentPage = (pdfViewerManager?.getCurrentPage() ?: 0) + 1
        val totalPages = pdfViewerManager?.getPageCount() ?: 0
        pdfPageCounter.text = getString(R.string.moodle_pdf_page_indicator, currentPage, totalPages)
    }

    private fun isPdfTab(index: Int): Boolean {
        return index in tabs.indices && tabs[index].id.startsWith(PDF_TAB_PREFIX)
    }

    private fun showPdfPageMenu() {
        val popup = PopupMenu(requireContext(), pdfPageCounter)

        popup.menu.add(0, 1, 0, getString(R.string.moodle_pdf_navigate_to_page)).setIcon(R.drawable.ic_navigate)
        popup.menu.add(0, 2, 0, getString(R.string.moodle_pdf_copy_current_page)).setIcon(R.drawable.ic_copy_text)

        val scrollText = if (pdfViewerManager?.scrollModeEnabled == true) {
            getString(R.string.moodle_pdf_enable_swipe_mode)
        } else {
            getString(R.string.moodle_pdf_enable_scroll_mode)
        }
        val scrollIcon = if (pdfViewerManager?.scrollModeEnabled == true) {
            R.drawable.ic_scroll_horizontal
        } else {
            R.drawable.ic_scroll_vertical
        }
        popup.menu.add(0, 3, 0, scrollText).setIcon(scrollIcon)

        try {
            val fieldMPopup = PopupMenu::class.java.getDeclaredField("mPopup")
            fieldMPopup.isAccessible = true
            val mPopup = fieldMPopup.get(popup)
            mPopup.javaClass.getDeclaredMethod("setForceShowIcon", Boolean::class.java).invoke(mPopup, true)
        } catch (_: Exception) { }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showNavigateToPageDialog()
                2 -> copyCurrentPageText()
                3 -> togglePdfScrollMode()
            }
            true
        }
        popup.show()
    }

    private fun showNavigateToPageDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_single_input, null)
        val tvInfo = dialogView.findViewById<TextView>(R.id.tvInputInfo)
        val editText = dialogView.findViewById<EditText>(R.id.editTextSingleInput)

        val pageCount = pdfViewerManager?.getPageCount() ?: 0
        tvInfo.text = getString(R.string.moodle_pdf_navigate_info, pageCount)
        editText.hint = getString(R.string.moodle_pdf_page_number_hint)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.moodle_pdf_navigate_to_page))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.moodle_go)) { _, _ ->
                val pageNum = editText.text.toString().toIntOrNull()
                if (pageNum != null && pageNum > 0 && pageNum <= pageCount) {
                    navigateToPdfPage(pageNum - 1)
                } else {
                    Toast.makeText(requireContext(), getString(R.string.moodle_pdf_invalid_page), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.moodle_cancel), null)
            .show()

        val buttonColor = requireContext().getThemeColor(R.attr.dialogSectionButtonColor)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
    }

    private fun navigateToPdfPage(targetPage: Int) {
        isNavigating = true

        val currentPagePosition = pdfViewerManager?.getCurrentPage() ?: 0
        val pageDistance = abs(targetPage - currentPagePosition)

        // close pages -> smooth nav
        if (pageDistance <= 5) {
            performSmoothPageNavigation(targetPage)
        } else {
            // far away pages -> optimized nav
            performJumpPageNavigation(targetPage)
        }
    }

    private fun performSmoothPageNavigation(targetPage: Int) {
        val loadingDialogView = layoutInflater.inflate(R.layout.dialog_pdf_loading, null)
        val tvStatus = loadingDialogView.findViewById<TextView>(R.id.tvPdfLoadingStatus)
        val progressBar = loadingDialogView.findViewById<ProgressBar>(R.id.progressBarPdfLoading)
        val btnCancel = loadingDialogView.findViewById<Button>(R.id.btnCancelPdfLoading)

        btnCancel.visibility = View.GONE

        val loadingDialog = AlertDialog.Builder(requireContext())
            .setView(loadingDialogView)
            .setCancelable(false)
            .create()

        loadingDialog.show()

        backgroundExecutor.execute {
            try {
                activity?.runOnUiThread {
                    tvStatus.text = getString(R.string.moodle_loading_page, targetPage + 1)
                    progressBar.progress = 20
                }

                val pagesToLoad = mutableListOf<Int>()

                if (pdfViewerManager?.scrollModeEnabled == true) {
                    pagesToLoad.add(targetPage)
                    if (targetPage > 0) pagesToLoad.add(targetPage - 1)
                    if (targetPage < (pdfViewerManager?.getPageCount() ?: 0) - 1) {
                        pagesToLoad.add(targetPage + 1)
                        if (targetPage < (pdfViewerManager?.getPageCount() ?: 0) - 2) {
                            pagesToLoad.add(targetPage + 2)
                        }
                    }
                } else {
                    pagesToLoad.add(targetPage)
                    if (targetPage > 0) pagesToLoad.add(targetPage - 1)
                    if (targetPage < (pdfViewerManager?.getPageCount() ?: 0) - 1) {
                        pagesToLoad.add(targetPage + 1)
                    }
                }

                pagesToLoad.forEachIndexed { index, page ->
                    pdfViewerManager?.renderPage(page, forceRender = false)
                    val progress = 20 + ((index + 1) * 60 / pagesToLoad.size)
                    activity?.runOnUiThread {
                        progressBar.progress = progress
                    }
                }

                pdfViewerManager?.setCurrentPage(targetPage)

                activity?.runOnUiThread {
                    progressBar.progress = 90

                    if (pdfViewerManager?.scrollModeEnabled == true) {
                        updateAllLoadedPagesInScrollMode(pagesToLoad)
                        scrollToPage(targetPage)
                    } else {
                        renderSinglePdfPage(targetPage)
                    }
                    updatePdfControls()

                    progressBar.progress = 100
                    loadingDialog.dismiss()

                    Handler(Looper.getMainLooper()).postDelayed({
                        isNavigating = false
                    }, 1000)
                }
            } catch (e: Exception) {
                L.e("MoodleFragment", "Error navigating to page", e)
                activity?.runOnUiThread {
                    loadingDialog.dismiss()
                    isNavigating = false
                    Toast.makeText(requireContext(), getString(R.string.moodle_page_load_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun performJumpPageNavigation(targetPage: Int) {
        val loadingDialogView = layoutInflater.inflate(R.layout.dialog_pdf_loading, null)
        val tvStatus = loadingDialogView.findViewById<TextView>(R.id.tvPdfLoadingStatus)
        val progressBar = loadingDialogView.findViewById<ProgressBar>(R.id.progressBarPdfLoading)
        val btnCancel = loadingDialogView.findViewById<Button>(R.id.btnCancelPdfLoading)

        btnCancel.visibility = View.GONE

        val loadingDialog = AlertDialog.Builder(requireContext())
            .setView(loadingDialogView)
            .setCancelable(false)
            .create()

        loadingDialog.show()

        pdfScrollContainer.setOnScrollChangeListener(null)

        backgroundExecutor.execute {
            try {
                activity?.runOnUiThread {
                    tvStatus.text = getString(R.string.moodle_loading_page, targetPage + 1)
                    progressBar.progress = 10
                }

                pdfViewerManager?.clearAllCacheForJump()

                activity?.runOnUiThread {
                    progressBar.progress = 20
                    tvStatus.text = getString(R.string.moodle_preparing_pages)
                }

                var averageHeight = 0
                val samplesToCheck = minOf(3, pdfViewerManager?.getPageCount() ?: 0)
                for (i in 0 until samplesToCheck) {
                    val bitmap = pdfViewerManager?.renderPage(i, forceRender = false)
                    if (bitmap != null && !bitmap.isRecycled) {
                        averageHeight += bitmap.height
                    }
                }
                averageHeight = if (samplesToCheck > 0) averageHeight / samplesToCheck else (1400 * requireContext().resources.displayMetrics.density).toInt()

                L.d("MoodleFragment", "Average page height: $averageHeight")

                activity?.runOnUiThread {
                    progressBar.progress = 40

                    for (i in 0 until pdfPagesContainer.childCount) {
                        val child = pdfPagesContainer.getChildAt(i)
                        val imageView = child as? ImageView ?: continue

                        imageView.setImageDrawable(null)
                        imageView.setBackgroundColor(android.graphics.Color.LTGRAY)

                        val layoutParams = imageView.layoutParams
                        layoutParams.height = averageHeight
                        imageView.layoutParams = layoutParams
                    }
                }

                Thread.sleep(100)

                pdfViewerManager?.setCurrentPage(targetPage)

                activity?.runOnUiThread {
                    progressBar.progress = 50
                }

                val pagesToLoad = mutableListOf<Int>()

                for (i in (targetPage - 3).coerceAtLeast(0) until (targetPage + 6).coerceAtMost(pdfViewerManager?.getPageCount() ?: 0)) {
                    pagesToLoad.add(i)
                }

                pagesToLoad.forEachIndexed { index, page ->
                    pdfViewerManager?.renderPage(page, forceRender = false)
                    val progress = 50 + ((index + 1) * 30 / pagesToLoad.size)
                    activity?.runOnUiThread {
                        progressBar.progress = progress
                    }
                }

                activity?.runOnUiThread {
                    progressBar.progress = 85

                    updateLoadedPagesAfterJumpWithExplicitHeights(pagesToLoad)

                    progressBar.progress = 90

                    pdfPagesContainer.requestLayout()

                    pdfPagesContainer.viewTreeObserver.addOnGlobalLayoutListener(
                        object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                            override fun onGlobalLayout() {
                                pdfPagesContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)

                                val targetChild = pdfPagesContainer.getChildAt(targetPage)
                                if (targetChild != null) {
                                    val targetY = targetChild.top
                                    pdfScrollContainer.scrollTo(0, targetY)
                                    L.d("MoodleFragment", "Scrolled to page $targetPage at Y=$targetY")
                                }

                                progressBar.progress = 100
                                loadingDialog.dismiss()

                                Handler(Looper.getMainLooper()).postDelayed({
                                    setupScrollListener()
                                    isNavigating = false

                                    backgroundLoadSurroundingPages(targetPage)
                                }, 800)
                            }
                        }
                    )

                    updatePdfControls()
                }
            } catch (e: Exception) {
                L.e("MoodleFragment", "Error in jump navigation", e)
                activity?.runOnUiThread {
                    loadingDialog.dismiss()
                    setupScrollListener()
                    isNavigating = false
                    Toast.makeText(requireContext(), getString(R.string.moodle_page_load_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun backgroundLoadSurroundingPages(centerPage: Int) {
        backgroundExecutor.execute {
            try {
                val pageCount = pdfViewerManager?.getPageCount() ?: 0
                val pagesToLoad = mutableListOf<Int>()

                for (i in (centerPage - 8).coerceAtLeast(0) until (centerPage + 11).coerceAtMost(pageCount)) {
                    if (pdfViewerManager?.getCachedPage(i) == null) {
                        pagesToLoad.add(i)
                    }
                }

                L.d("MoodleFragment", "Background loading ${pagesToLoad.size} surrounding pages")

                pagesToLoad.forEach { page ->
                    val bitmap = pdfViewerManager?.renderPage(page, forceRender = false)

                    if (bitmap != null && !bitmap.isRecycled) {
                        activity?.runOnUiThread {
                            try {
                                val child = pdfPagesContainer.getChildAt(page)
                                val imageView = child as? ImageView ?: return@runOnUiThread

                                if (!bitmap.isRecycled) {
                                    imageView.setImageBitmap(bitmap)
                                    imageView.setBackgroundColor(android.graphics.Color.TRANSPARENT)

                                    val layoutParams = imageView.layoutParams
                                    layoutParams.height = bitmap.height
                                    imageView.layoutParams = layoutParams
                                }
                            } catch (e: Exception) {
                                L.e("MoodleFragment", "Error updating page $page in background", e)
                            }
                        }
                    }

                    Thread.sleep(50)
                }

                L.d("MoodleFragment", "Background loading complete")
            } catch (e: Exception) {
                L.e("MoodleFragment", "Error in background loading", e)
            }
        }
    }

    private fun updateLoadedPagesAfterJumpWithExplicitHeights(pagesToUpdate: List<Int>) {
        for (i in 0 until pdfPagesContainer.childCount) {
            val child = pdfPagesContainer.getChildAt(i)
            val pageNumber = child.tag as? Int ?: continue
            val imageView = child as? ImageView ?: continue

            if (pageNumber in pagesToUpdate) {
                val bitmap = pdfViewerManager?.getCachedPage(pageNumber)
                if (bitmap != null && !bitmap.isRecycled) {
                    try {
                        imageView.setImageBitmap(bitmap)
                        imageView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        imageView.minimumHeight = 0

                        val layoutParams = imageView.layoutParams
                        layoutParams.height = bitmap.height
                        imageView.layoutParams = layoutParams

                        L.d("MoodleFragment", "Set page $pageNumber to explicit height ${bitmap.height}")
                    } catch (e: Exception) {
                        L.e("MoodleFragment", "Failed to set bitmap after jump", e)
                    }
                }
            }
        }
    }

    private fun unloadAllPdfPages() {
        for (i in 0 until pdfPagesContainer.childCount) {
            val child = pdfPagesContainer.getChildAt(i)
            val imageView = child as? ImageView ?: continue

            val currentHeight = imageView.height.coerceAtLeast((800 * resources.displayMetrics.density).toInt())

            imageView.setImageDrawable(null)
            imageView.setBackgroundColor(android.graphics.Color.LTGRAY)
            imageView.minimumHeight = currentHeight

            val layoutParams = imageView.layoutParams
            layoutParams.height = currentHeight
            imageView.layoutParams = layoutParams
        }
    }

    private fun scrollToPage(pageIndex: Int) {
        var targetY = 0
        for (i in 0 until pageIndex.coerceAtMost(pdfPagesContainer.childCount)) {
            val child = pdfPagesContainer.getChildAt(i)
            targetY += child.height + child.marginBottom
        }
        pdfScrollContainer.smoothScrollTo(0, targetY)
    }

    private fun copyCurrentPageText() {
        val text = pdfViewerManager?.extractCurrentPageText()
        if (text != null) {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(getString(R.string.moodle_pdf_page_text), text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), getString(R.string.moodle_pdf_page_text_copied), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), getString(R.string.moodle_pdf_extract_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun togglePdfScrollMode() {
        pdfViewerManager?.toggleScrollMode()
        renderPdfContent()
    }

    private fun copyAllPdfText() {
        val text = pdfViewerManager?.extractAllText()
        if (text != null) {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(getString(R.string.moodle_pdf_text), text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), getString(R.string.moodle_pdf_all_text_copied), Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadCurrentPdf() {
        if (pdfViewerManager?.downloadPdf() == true) {
            Toast.makeText(requireContext(), getString(R.string.moodle_pdf_downloaded), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), getString(R.string.moodle_pdf_download_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareCurrentPdf() {
        pdfViewerManager?.sharePdf()?.let { intent ->
            startActivity(Intent.createChooser(intent, getString(R.string.moodle_pdf_share)))
        }
    }

    private fun togglePdfDarkMode() {
        val loadingDialogView = layoutInflater.inflate(R.layout.dialog_pdf_loading, null)
        val tvStatus = loadingDialogView.findViewById<TextView>(R.id.tvPdfLoadingStatus)
        val progressBar = loadingDialogView.findViewById<ProgressBar>(R.id.progressBarPdfLoading)
        val btnCancel = loadingDialogView.findViewById<Button>(R.id.btnCancelPdfLoading)

        btnCancel.visibility = View.GONE

        val loadingDialog = AlertDialog.Builder(requireContext())
            .setView(loadingDialogView)
            .setCancelable(false)
            .create()

        loadingDialog.show()

        backgroundExecutor.execute {
            try {
                activity?.runOnUiThread {
                    tvStatus.text = getString(R.string.moodle_applying_theme)
                    progressBar.progress = 20
                }

                val visiblePages = getVisiblePageIndices()

                pdfViewerManager?.toggleDarkMode()

                activity?.runOnUiThread {
                    progressBar.progress = 40
                    tvStatus.text = getString(R.string.moodle_clearing_pages)
                }

                activity?.runOnUiThread {
                    clearAllPdfPageViews()
                    progressBar.progress = 60
                    tvStatus.text = getString(R.string.moodle_reloading_pages)
                }

                visiblePages.forEachIndexed { index, page ->
                    pdfViewerManager?.renderPage(page, forceRender = true)
                    val progress = 60 + ((index + 1) * 30 / visiblePages.size)
                    activity?.runOnUiThread {
                        progressBar.progress = progress
                    }
                }

                activity?.runOnUiThread {
                    progressBar.progress = 90

                    if (pdfViewerManager?.scrollModeEnabled == true) {
                        updateVisiblePdfPages(visiblePages)
                        loadVisiblePages()
                    } else {
                        renderSinglePdfPage(pdfViewerManager?.getCurrentPage() ?: 0)
                    }

                    progressBar.progress = 100
                    loadingDialog.dismiss()
                }
            } catch (e: Exception) {
                L.e("MoodleFragment", "Error toggling dark mode", e)
                activity?.runOnUiThread {
                    loadingDialog.dismiss()
                }
            }
        }
    }

    private fun clearAllPdfPageViews() {
        for (i in 0 until pdfPagesContainer.childCount) {
            val child = pdfPagesContainer.getChildAt(i)
            val imageView = child as? ImageView ?: continue

            (imageView.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.recycle()

            imageView.setImageDrawable(null)
            imageView.setBackgroundColor(android.graphics.Color.LTGRAY)
            imageView.minimumHeight = (800 * resources.displayMetrics.density).toInt()
        }
    }

    private fun updateVisiblePdfPages(pagesToUpdate: List<Int>) {
        for (i in 0 until pdfPagesContainer.childCount) {
            val child = pdfPagesContainer.getChildAt(i)
            val pageNumber = child.tag as? Int ?: continue

            if (pageNumber in pagesToUpdate) {
                val imageView = child as? ImageView ?: continue
                val bitmap = pdfViewerManager?.getCachedPage(pageNumber)

                if (bitmap != null && !bitmap.isRecycled) {
                    imageView.setImageBitmap(bitmap)
                    imageView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    imageView.minimumHeight = 0
                }
            }
        }
    }

    private fun getVisiblePageIndices(): List<Int> {
        val visiblePages = mutableListOf<Int>()

        if (pdfViewerManager?.scrollModeEnabled == true) {
            val scrollY = pdfScrollContainer.scrollY
            val height = pdfScrollContainer.height

            for (i in 0 until pdfPagesContainer.childCount) {
                val child = pdfPagesContainer.getChildAt(i)
                val childTop = child.top
                val childBottom = child.bottom

                if (childBottom > scrollY && childTop < scrollY + height) {
                    val pageNumber = child.tag as? Int
                    if (pageNumber != null) {
                        visiblePages.add(pageNumber)
                    }
                }
            }
        } else {
            val currentPage = pdfViewerManager?.getCurrentPage() ?: 0
            visiblePages.add(currentPage)
        }

        return visiblePages
    }

    private fun updateAllLoadedPagesInScrollMode(pagesToUpdate: List<Int>) {
        for (i in 0 until pdfPagesContainer.childCount) {
            val child = pdfPagesContainer.getChildAt(i)
            val pageNumber = child.tag as? Int ?: continue

            if (pageNumber in pagesToUpdate) {
                val imageView = child as? ImageView ?: continue
                val bitmap = pdfViewerManager?.getCachedPage(pageNumber)

                if (bitmap != null && !bitmap.isRecycled) {
                    try {
                        imageView.setImageBitmap(bitmap)
                        imageView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        imageView.minimumHeight = 0
                    } catch (e: Exception) {
                        L.e("MoodleFragment", "Bitmap was recycled while setting on ImageView", e)
                        imageView.setImageDrawable(null)
                        imageView.setBackgroundColor(android.graphics.Color.LTGRAY)
                        imageView.minimumHeight = (800 * resources.displayMetrics.density).toInt()
                    }
                }
            }
        }
    }

    private fun searchInPdfImplemented() {
        Toast.makeText(requireContext(), getString(R.string.moodle_pdf_search_coming_soon), Toast.LENGTH_SHORT).show()
    }

    private fun showPdfOptionsMenu() {
        val popup = PopupMenu(requireContext(), pdfKebabMenu)

        popup.menu.add(0, 1, 0, getString(R.string.moodle_pdf_search)).setIcon(R.drawable.ic_search)
        popup.menu.add(0, 2, 0, getString(R.string.moodle_pdf_copy_all_text)).setIcon(R.drawable.ic_copy_text)
        popup.menu.add(0, 3, 0, getString(R.string.moodle_pdf_download)).setIcon(R.drawable.ic_download)
        popup.menu.add(0, 4, 0, getString(R.string.moodle_pdf_share)).setIcon(R.drawable.ic_share)

        val darkModeText = if (pdfViewerManager?.forceDarkMode == true) {
            getString(R.string.moodle_pdf_disable_dark_mode)
        } else {
            getString(R.string.moodle_pdf_enable_dark_mode)
        }
        val darkModeIcon = if (pdfViewerManager?.forceDarkMode == true) {
            R.drawable.ic_light_mode
        } else {
            R.drawable.ic_dark_mode
        }
        popup.menu.add(0, 5, 0, darkModeText).setIcon(darkModeIcon)

        try {
            val fieldMPopup = PopupMenu::class.java.getDeclaredField("mPopup")
            fieldMPopup.isAccessible = true
            val mPopup = fieldMPopup.get(popup)
            mPopup.javaClass.getDeclaredMethod("setForceShowIcon", Boolean::class.java).invoke(mPopup, true)
        } catch (_: Exception) { }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> searchInPdfImplemented()
                2 -> copyAllPdfText()
                3 -> downloadCurrentPdf()
                4 -> shareCurrentPdf()
                5 -> togglePdfDarkMode()
            }
            true
        }
        popup.show()
    }

    private fun handleInterceptedDownload(
        url: String,
        cookies: String?,
        contentDisposition: String = "",
        mimetype: String? = null
    ) {
        L.d("MoodleFragment", "Handling intercepted download")

        val fileName = when {
            contentDisposition.contains("filename=") -> {
                val match = "filename=\"?([^\"]+)\"?".toRegex().find(contentDisposition)
                match?.groupValues?.get(1) ?: ""
            }
            else -> {
                try {
                    URLDecoder.decode(url.substringAfterLast("/").substringBefore("?"), "UTF-8")
                } catch (_: Exception) {
                    ""
                }
            }
        }

        val isPdf = mimetype == "application/pdf" || fileName.endsWith(".pdf", ignoreCase = true)
        val isDocx = mimetype?.contains("wordprocessingml") == true ||
                fileName.endsWith(".docx", ignoreCase = true)

        when {
            isPdf && tabs.size < MAX_TABS -> handlePdfForViewerWithCookies(url, cookies)
            isDocx && tabs.size < MAX_TABS -> handleDocxFile(url, cookies, forceDownload = false)
            else -> downloadToDeviceWithCookies(url, cookies, contentDisposition)
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

            request.setTitle(fileName)
            request.setDescription("Moodle Download")
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
        pdfFileUrl = url

        var loadingCancelled = false
        val dialogView = layoutInflater.inflate(R.layout.dialog_pdf_loading, null)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tvPdfLoadingStatus)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressBarPdfLoading)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelPdfLoading)

        val progressDialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .show()

        btnCancel.setOnClickListener {
            loadingCancelled = true
            progressDialog.dismiss()
        }

        backgroundExecutor.execute {
            try {
                activity?.runOnUiThread {
                    tvStatus.text = getString(R.string.moodle_downloading_pdf)
                    progressBar.progress = 0
                }

                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"

                if (!cookies.isNullOrBlank()) {
                    connection.setRequestProperty("Cookie", cookies)
                }

                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Referer", moodleBaseUrl)
                connection.connectTimeout = 30000
                connection.readTimeout = 30000

                if (connection.responseCode == 200 && !loadingCancelled) {
                    val contentLength = connection.contentLength
                    val inputStream = connection.inputStream
                    val outputStream = ByteArrayOutputStream()

                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L

                    while (inputStream.read(buffer).also { bytesRead = it } != -1 && !loadingCancelled) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        if (contentLength > 0) {
                            val progress = ((totalBytesRead * 50) / contentLength).toInt()
                            activity?.runOnUiThread {
                                progressBar.progress = progress
                            }
                        }
                    }

                    inputStream.close()
                    connection.disconnect()

                    val pdfBytes = outputStream.toByteArray()

                    if (!loadingCancelled) {
                        activity?.runOnUiThread {
                            tvStatus.text = getString(R.string.moodle_saving_pdf)
                            progressBar.progress = 50
                        }

                        val cacheDir = requireContext().cacheDir
                        val pdfFile = File(cacheDir, "moodle_pdf_${System.currentTimeMillis()}.pdf")

                        pdfFile.outputStream().use { output ->
                            output.write(pdfBytes)
                        }

                        val fileName = try {
                            URLDecoder.decode(url.substringAfterLast("/").substringBefore("?"), "UTF-8")
                        } catch (_: Exception) {
                            "document.pdf"
                        }

                        if (!loadingCancelled) {
                            activity?.runOnUiThread {
                                tvStatus.text = getString(R.string.moodle_opening_pdf)
                                progressBar.progress = 80
                            }

                            Thread.sleep(200)

                            activity?.runOnUiThread {
                                progressBar.progress = 100
                                progressDialog.dismiss()
                                showPdfViewer(pdfFile, fileName, url)
                            }
                        }
                    } else {
                        connection.disconnect()
                    }
                } else {
                    connection.disconnect()
                    if (!loadingCancelled) {
                        activity?.runOnUiThread {
                            progressDialog.dismiss()
                            Toast.makeText(requireContext(),
                                getString(R.string.moodle_pdf_load_failed),
                                Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                L.e("MoodleFragment", "Error downloading PDF", e)
                if (!loadingCancelled) {
                    activity?.runOnUiThread {
                        progressDialog.dismiss()
                        Toast.makeText(requireContext(),
                            getString(R.string.moodle_pdf_load_failed),
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun resolveDownloadUrl(viewUrl: String, cookies: String?, forceDownload: Boolean = false) {
        if (pdfFileUrl == null) {
            pdfFileUrl = viewUrl
        }

        backgroundExecutor.execute {
            try {
                L.d("MoodleFragment", "=== Starting Download URL Resolution ===")
                L.d("MoodleFragment", "Initial URL: $viewUrl")
                L.d("MoodleFragment", "Force download to device: $forceDownload")

                val freshCookies = CookieManager.getInstance().getCookie(moodleBaseUrl)
                val cookiesToUse = freshCookies ?: cookies

                L.d("MoodleFragment", "Fresh cookies from CookieManager: ${freshCookies != null}")
                if (cookiesToUse != null) {
                    L.d("MoodleFragment", "Cookie preview: ${cookiesToUse.take(100)}...")
                } else {
                    L.e("MoodleFragment", "ERROR: No cookies available!")
                    activity?.runOnUiThread {
                        Toast.makeText(
                            requireContext(),
                            "No session cookies available. Please reload the page.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@execute
                }

                var currentUrl = viewUrl
                var redirectCount = 0
                val maxRedirects = 10
                var finalUrl: String? = null
                var contentType: String? = null
                var contentDisposition: String? = null

                while (redirectCount < maxRedirects) {
                    L.d("MoodleFragment", "--- Redirect attempt $redirectCount ---")
                    L.d("MoodleFragment", "Current URL: $currentUrl")

                    if (currentUrl.contains("/login/index.php")) {
                        L.w("MoodleFragment", "Hit login page - session may have expired")

                        activity?.runOnUiThread {
                            dismissSessionConfirmDialog {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    val newCookies = CookieManager.getInstance().getCookie(moodleBaseUrl)
                                    resolveDownloadUrl(viewUrl, newCookies, forceDownload)
                                }, 1000)
                            }
                        }
                        return@execute
                    }

                    val conn = URL(currentUrl).openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.instanceFollowRedirects = false

                    conn.setRequestProperty("Cookie", cookiesToUse)
                    conn.setRequestProperty("User-Agent", userAgent)
                    conn.setRequestProperty("Referer", moodleBaseUrl)
                    conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000

                    val responseCode = conn.responseCode
                    L.d("MoodleFragment", "Response code: $responseCode")

                    when (responseCode) {
                        in 300..399 -> {
                            val location = conn.getHeaderField("Location")
                            L.d("MoodleFragment", "Got redirect to: $location")

                            val setCookie = conn.getHeaderField("Set-Cookie")
                            if (setCookie != null) {
                                L.d("MoodleFragment", "Server set new cookie: ${setCookie.take(100)}...")
                            }

                            conn.disconnect()

                            if (location.isNullOrBlank()) {
                                L.e("MoodleFragment", "ERROR: Redirect without Location header")
                                break
                            }

                            currentUrl = if (location.startsWith("http")) {
                                location
                            } else if (location.startsWith("/")) {
                                "$moodleBaseUrl$location"
                            } else {
                                val baseUrl = currentUrl.substringBeforeLast("/")
                                "$baseUrl/$location"
                            }

                            redirectCount++
                        }
                        200 -> {
                            finalUrl = currentUrl
                            contentType = conn.getHeaderField("Content-Type")
                            contentDisposition = conn.getHeaderField("Content-Disposition")
                            L.d("MoodleFragment", "SUCCESS: Found final file")
                            L.d("MoodleFragment", "Final URL: $finalUrl")
                            L.d("MoodleFragment", "Content-Type: $contentType")
                            L.d("MoodleFragment", "Content-Disposition: $contentDisposition")
                            conn.disconnect()
                            break
                        }
                        401, 403 -> {
                            L.e("MoodleFragment", "ERROR: Authentication failed (code $responseCode)")
                            conn.disconnect()

                            activity?.runOnUiThread {
                                Toast.makeText(
                                    requireContext(),
                                    "Session expired. Please reload the page.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            return@execute
                        }
                        else -> {
                            L.e("MoodleFragment", "ERROR: Unexpected response code: $responseCode")
                            conn.disconnect()
                            break
                        }
                    }
                }

                if (finalUrl != null) {
                    L.d("MoodleFragment", "SUCCESS: Will handle download for: $finalUrl")
                    pdfFileUrl = finalUrl

                    activity?.runOnUiThread {
                        if (forceDownload) {
                            downloadToDeviceWithCookies(finalUrl, cookiesToUse, contentDisposition ?: "")
                        } else {
                            handleInterceptedDownload(finalUrl, cookiesToUse, contentDisposition ?: "", contentType)
                        }
                    }
                } else {
                    L.e("MoodleFragment", "FAILED: Could not resolve after $redirectCount attempts")
                    activity?.runOnUiThread {
                        Toast.makeText(
                            requireContext(),
                            "Could not resolve download URL. Try again or reload the page.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

            } catch (e: Exception) {
                L.e("MoodleFragment", "EXCEPTION in resolveDownloadUrl", e)
                activity?.runOnUiThread {
                    Toast.makeText(
                        requireContext(),
                        "Error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun dismissSessionConfirmDialog(onDismissed: () -> Unit) {
        val jsCode = """
        (function() {
            try {
                var cancelButton = document.evaluate(
                    '/html/body/div[2]/div[2]/div/div/div/div/div/div/div/div/div/div[3]/div/div[1]/form/button',
                    document,
                    null,
                    XPathResult.FIRST_ORDERED_NODE_TYPE,
                    null
                ).singleNodeValue;
                
                if (cancelButton) {
                    cancelButton.click();
                    return true;
                }

                var dialogs = document.querySelectorAll('[role="dialog"], .modal');
                for (var i = 0; i < dialogs.length; i++) {
                    var dialog = dialogs[i];
                    var cancelBtn = dialog.querySelector('button[data-action="cancel"], button:contains("Abbrechen"), button:contains("Cancel")');
                    if (cancelBtn) {
                        cancelBtn.click();
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
                L.d("MoodleFragment", "Session dialog dismissed")
                Handler(Looper.getMainLooper()).postDelayed({
                    onDismissed()
                }, 500)
            } else {
                L.w("MoodleFragment", "Could not find session dialog to dismiss")
                onDismissed()
            }
        }
    }

    private fun resolveAndParseDocument(viewUrl: String, cookies: String?, progressDialog: AlertDialog) {
        backgroundExecutor.execute {
            try {
                L.d("MoodleFragment", "=== Resolving document for parsing ===")
                L.d("MoodleFragment", "View URL: $viewUrl")

                val freshCookies = CookieManager.getInstance().getCookie(moodleBaseUrl)
                val cookiesToUse = freshCookies ?: cookies

                if (cookiesToUse == null) {
                    L.e("MoodleFragment", "No cookies available for parsing")
                    activity?.runOnUiThread {
                        progressDialog.dismiss()
                        Toast.makeText(
                            requireContext(),
                            "No session cookies available",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@execute
                }

                var currentUrl = viewUrl
                var redirectCount = 0
                val maxRedirects = 10
                var finalUrl: String? = null

                while (redirectCount < maxRedirects) {
                    L.d("MoodleFragment", "Parse redirect attempt $redirectCount: $currentUrl")

                    if (currentUrl.contains("/login/index.php")) {
                        L.w("MoodleFragment", "Hit login page during parsing")

                        activity?.runOnUiThread {
                            progressDialog.dismiss()
                            dismissSessionConfirmDialog {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    val newProgressDialog = AlertDialog.Builder(requireContext())
                                        .setMessage(getString(R.string.moodle_parsing_document))
                                        .setCancelable(false)
                                        .show()

                                    val newCookies = CookieManager.getInstance().getCookie(moodleBaseUrl)
                                    resolveAndParseDocument(viewUrl, newCookies, newProgressDialog)
                                }, 1000)
                            }
                        }
                        return@execute
                    }

                    val conn = URL(currentUrl).openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.instanceFollowRedirects = false

                    conn.setRequestProperty("Cookie", cookiesToUse)
                    conn.setRequestProperty("User-Agent", userAgent)
                    conn.setRequestProperty("Referer", moodleBaseUrl)
                    conn.setRequestProperty("Accept", "*/*")
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000

                    val responseCode = conn.responseCode
                    L.d("MoodleFragment", "Parse redirect response: $responseCode")

                    when (responseCode) {
                        in 300..399 -> {
                            val location = conn.getHeaderField("Location")
                            conn.disconnect()

                            if (location.isNullOrBlank()) {
                                L.e("MoodleFragment", "Redirect without location")
                                break
                            }

                            currentUrl = if (location.startsWith("http")) {
                                location
                            } else if (location.startsWith("/")) {
                                "$moodleBaseUrl$location"
                            } else {
                                val baseUrl = currentUrl.substringBeforeLast("/")
                                "$baseUrl/$location"
                            }

                            redirectCount++
                        }
                        200 -> {
                            finalUrl = currentUrl
                            L.d("MoodleFragment", "Found final document URL: $finalUrl")
                            conn.disconnect()
                            break
                        }
                        else -> {
                            L.e("MoodleFragment", "Unexpected response: $responseCode")
                            conn.disconnect()
                            break
                        }
                    }
                }

                if (finalUrl != null) {
                    L.d("MoodleFragment", "Downloading and parsing: $finalUrl")
                    downloadAndParseDocumentWithCookies(finalUrl, cookiesToUse) { text ->
                        activity?.runOnUiThread {
                            progressDialog.dismiss()
                            if (text != null) {
                                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText(getString(R.string.moodle_document_text), text)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(requireContext(), getString(R.string.moodle_text_copied), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(requireContext(), "Failed to parse document", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    L.e("MoodleFragment", "Failed to resolve document URL")
                    activity?.runOnUiThread {
                        progressDialog.dismiss()
                        Toast.makeText(
                            requireContext(),
                            "Could not resolve file URL for parsing",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                L.e("MoodleFragment", "Error resolving document", e)
                activity?.runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(
                        requireContext(),
                        "Error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun downloadAndParseDocumentWithCookies(url: String, cookies: String?, callback: (String?) -> Unit) {
        backgroundExecutor.execute {
            try {
                L.d("MoodleFragment", "=== Downloading document for parsing ===")
                L.d("MoodleFragment", "URL: $url")

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
                L.d("MoodleFragment", "Download response code: $responseCode")

                if (responseCode == 200) {
                    val contentType = connection.getHeaderField("Content-Type")
                    L.d("MoodleFragment", "Content-Type: $contentType")

                    val inputStream = connection.inputStream
                    val text = when {
                        url.endsWith(".pdf", ignoreCase = true) -> {
                            L.d("MoodleFragment", "Parsing as PDF")
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
                                val result = textBuilder.toString()
                                L.d("MoodleFragment", "PDF parsed successfully, ${result.length} chars")
                                result
                            } catch (e: Exception) {
                                L.e("MoodleFragment", "Error parsing PDF", e)
                                null
                            }
                        }
                        url.endsWith(".docx", ignoreCase = true) -> {
                            L.d("MoodleFragment", "Parsing as DOCX")
                            val result = parseDocxToText(inputStream)
                            if (result != null) {
                                L.d("MoodleFragment", "DOCX parsed successfully, ${result.length} chars")
                            } else {
                                L.e("MoodleFragment", "DOCX parsing returned null")
                            }
                            result
                        }
                        else -> {
                            L.e("MoodleFragment", "Unknown file type for URL: $url")
                            null
                        }
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
                L.e("MoodleFragment", "Exception downloading document", e)
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

    private fun startMoodleFetchProcess() {
        if (isFetchingFromMoodle) {
            L.d("MoodleFragment", "Fetch already in progress")
            return
        }

        if (!isNetworkAvailable()) {
            Toast.makeText(requireContext(), getString(R.string.moodle_offline_error), Toast.LENGTH_LONG).show()
            return
        }

        isFetchingFromMoodle = true
        fetchCancelled = false
        fetchStartTime = System.currentTimeMillis()

        showMoodleFetchProgressDialog()

        Handler(Looper.getMainLooper()).postDelayed({
            checkLoginStatusForFetch()
        }, 1000)
    }

    private fun showMoodleFetchProgressDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_moodle_fetch_progress, null)
        val tvFetchTitle = dialogView.findViewById<TextView>(R.id.tvFetchTitle)
        val tvFetchStatus = dialogView.findViewById<TextView>(R.id.tvFetchStatus)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressBarFetch)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnFetchCancel)

        tvFetchTitle.text = when (currentFetchType) {
            FetchType.EXAM_SCHEDULE -> getString(R.string.moodle_fetching_exam_schedule)
            FetchType.TIMETABLE -> getString(R.string.moodle_fetching_timetable)
            else -> getString(R.string.moodle_fetching_title)
        }

        tvFetchStatus.text = getString(R.string.moodle_waiting_for_login)
        progressBar.progress = 10

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnCancel.setOnClickListener {
            fetchCancelled = true
            dialog.dismiss()
            cleanupFetchProcess()
            Toast.makeText(requireContext(), getString(R.string.moodle_fetch_cancelled), Toast.LENGTH_SHORT).show()
        }

        moodleFetchProgressDialog = dialog
        dialog.show()
    }

    private fun checkLoginStatusForFetch() {
        if (fetchCancelled || System.currentTimeMillis() - fetchStartTime > FETCH_TIMEOUT) {
            cleanupFetchProcess()
            return
        }

        val currentUrl = webView.url ?: ""
        val isOnLoginPage = currentUrl == loginUrl || currentUrl.contains("login/index.php")

        if (isOnLoginPage) {
            updateFetchProgress(15, getString(R.string.moodle_please_login))

            moodleFetchProgressDialog?.hide()

            Toast.makeText(
                requireContext(),
                getString(R.string.moodle_please_login_to_continue),
                Toast.LENGTH_LONG
            ).show()

            monitorLoginCompletion()
        } else {
            continueAfterLogin()
        }
    }

    private fun monitorLoginCompletion() {
        val checkInterval = 1000L
        val handler = Handler(Looper.getMainLooper())

        val loginCheckRunnable = object : Runnable {
            override fun run() {
                if (fetchCancelled || System.currentTimeMillis() - fetchStartTime > FETCH_TIMEOUT) {
                    cleanupFetchProcess()
                    return
                }

                val currentUrl = webView.url ?: ""
                val isStillOnLoginPage = currentUrl == loginUrl || currentUrl.contains("login/index.php")

                if (!isStillOnLoginPage) {
                    moodleFetchProgressDialog?.show()
                    updateFetchProgress(20, getString(R.string.moodle_login_successful))

                    Handler(Looper.getMainLooper()).postDelayed({
                        continueAfterLogin()
                    }, 500)
                } else {
                    handler.postDelayed(this, checkInterval)
                }
            }
        }

        handler.post(loginCheckRunnable)
    }

    private fun continueAfterLogin() {
        if (fetchCancelled) return

        updateFetchProgress(25, getString(R.string.moodle_navigating_to_my_page))

        loadUrlInBackground("$moodleBaseUrl/my/")

        Handler(Looper.getMainLooper()).postDelayed({
            if (!fetchCancelled) {
                extractProgramCourseNameFromPage()
            }
        }, 2000)
    }

    private fun extractProgramCourseNameFromPage() {
        if (fetchCancelled) return

        updateFetchProgress(30, getString(R.string.moodle_detecting_program))

        val jsCode = """
        (function() {
            try {
                var programNameElement = document.evaluate(
                    '/html/body/div[1]/div[2]/div/div[1]/div/div/section/section[1]/div/div/div[1]/div[2]/div/div/div[1]/div/ul/li[1]/div/div[2]/div/span[2]/text()',
                    document,
                    null,
                    XPathResult.FIRST_ORDERED_NODE_TYPE,
                    null
                ).singleNodeValue;
                
                if (programNameElement && programNameElement.textContent) {
                    return programNameElement.textContent.trim().toLowerCase();
                }
                return false;
            } catch(e) {
                return false;
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            if (fetchCancelled) return@evaluateJavascript

            val programName = result.replace("\"", "").trim()
            if (programName != "false" && programName.isNotEmpty()) {
                L.d("MoodleFragment", "Extracted program name: $programName")
                searchForExamProgramWithName(programName)
            } else {
                val savedName = sharedPrefs.getString("saved_program_course_name", null)
                if (savedName != null) {
                    searchForExamProgramWithName(savedName)
                } else {
                    requestProgramNameFromUser(isAutoDetectFailed = true)
                }
            }
        }
    }

    private fun searchForExamProgramWithName(programName: String) {
        if (fetchCancelled) return

        updateFetchProgress(35, getString(R.string.moodle_searching_exam_program, programName))

        performProgramSearch(programName)
    }

    private fun requestProgramNameFromUser(isAutoDetectFailed: Boolean = false) {
        activity?.runOnUiThread {
            moodleFetchProgressDialog?.let { dialog ->
                val dialogView = dialog.findViewById<View>(android.R.id.content)
                val editText = dialogView?.findViewById<EditText>(R.id.editTextExamProgramName)
                val btnContinue = dialogView?.findViewById<Button>(R.id.btnFetchContinue)
                val tvStatus = dialogView?.findViewById<TextView>(R.id.tvFetchStatus)
                val checkBoxSave = dialogView?.findViewById<CheckBox>(R.id.checkBoxSaveProgramName)

                val message = if (isAutoDetectFailed) {
                    getString(R.string.moodle_program_auto_detect_failed)
                } else {
                    getString(R.string.moodle_enter_exam_program_prompt)
                }

                tvStatus?.text = message
                editText?.visibility = View.VISIBLE
                btnContinue?.visibility = View.VISIBLE
                checkBoxSave?.visibility = View.VISIBLE

                btnContinue?.setOnClickListener {
                    val enteredName = editText?.text?.toString()?.trim()
                    if (!enteredName.isNullOrEmpty()) {
                        userProvidedProgramName = enteredName

                        if (checkBoxSave?.isChecked == true) {
                            savedProgramCourseName = enteredName
                            sharedPrefs.edit {
                                putString("saved_program_course_name", enteredName)
                            }
                        }

                        editText.visibility = View.GONE
                        btnContinue.visibility = View.GONE
                        checkBoxSave?.visibility = View.GONE
                        performProgramSearch(enteredName)
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.moodle_program_name_required), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun performProgramSearch(programName: String) {
        if (fetchCancelled) return

        waitForPageLoadComplete {
            if (fetchCancelled) return@waitForPageLoadComplete

            updateFetchProgress(35, getString(R.string.moodle_preparing_search))
            resetSearchState()

            Handler(Looper.getMainLooper()).postDelayed({
                if (fetchCancelled) return@postDelayed

                waitForSearchFieldReady { searchFieldReady ->
                    if (fetchCancelled) return@waitForSearchFieldReady

                    if (searchFieldReady) {
                        updateFetchProgress(40, getString(R.string.moodle_filling_search))
                        fillSearchFieldWithDelay(programName) { searchFilled ->
                            if (fetchCancelled) return@fillSearchFieldWithDelay

                            if (searchFilled) {
                                updateFetchProgress(50, getString(R.string.moodle_waiting_results))

                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (!fetchCancelled) {
                                        clickFirstProgramResult()
                                    }
                                }, 1500)
                            } else {
                                handleFetchError(getString(R.string.moodle_search_course_failed))
                            }
                        }
                    } else {
                        handleFetchError(getString(R.string.moodle_search_not_available))
                    }
                }
            }, 1000)
        }
    }

    private fun clickFirstProgramResult() {
        if (fetchCancelled) return

        waitForSearchResults { resultsReady ->
            if (fetchCancelled) return@waitForSearchResults

            if (resultsReady) {
                updateFetchProgress(70, getString(R.string.moodle_opening_course))

                val jsCode = """
                (function() {
                    var courseLink = document.evaluate(
                        '/html/body/div[1]/div[2]/div/div[1]/div/div/section/section[1]/div/div/div[1]/div[2]/div/div/div[1]/div/ul/li[1]/div/div[2]/a',
                        document,
                        null,
                        XPathResult.FIRST_ORDERED_NODE_TYPE,
                        null
                    ).singleNodeValue;
                    
                    if (courseLink && courseLink.href) {
                        var courseUrl = courseLink.href;
                        courseLink.click();
                        return courseUrl;
                    }
                    return false;
                })();
            """.trimIndent()

                webView.evaluateJavascript(jsCode) { result ->
                    if (fetchCancelled) return@evaluateJavascript

                    val courseUrl = result.replace("\"", "")
                    if (courseUrl != "false" && courseUrl.contains("course/view.php")) {
                        L.d("MoodleFragment", "Opened program course: $courseUrl")
                        updateFetchProgress(80, getString(R.string.moodle_searching_schedule))

                        Handler(Looper.getMainLooper()).postDelayed({
                            if (!fetchCancelled) {
                                waitForCoursePageLoad {
                                    if (!fetchCancelled) {
                                        when (currentFetchType) {
                                            FetchType.EXAM_SCHEDULE -> searchForScheduleEntry()
                                            FetchType.TIMETABLE -> searchForTimetableEntry()
                                            else -> handleFetchError(getString(R.string.moodle_unknown_fetch_type))
                                        }
                                    }
                                }
                            }
                        }, 1500)
                    } else {
                        if (userProvidedProgramName != null) {
                            sharedPrefs.edit { remove("saved_program_course_name") }
                            savedProgramCourseName = null
                        }
                        requestProgramNameFromUser(isAutoDetectFailed = false)
                    }
                }
            } else {
                handleFetchError(getString(R.string.moodle_search_results_not_loaded))
            }
        }
    }

    private fun searchForTimetableEntry() {
        if (fetchCancelled) return

        updateFetchProgress(85, getString(R.string.moodle_locating_timetable_file))

        val tempPrefs = requireActivity().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val klasse = tempPrefs.getString("temp_fetch_class", "") ?: ""
        val klasseBase = klasse.replace(Regex("\\d+$"), "")

        L.d("MoodleFragment", "Searching for timetable - Full class: $klasse, Base class: $klasseBase")

        if (klasse.isEmpty()) {
            handleFetchError("No class information available for timetable fetch")
            return
        }

        val jsCode = """
        (function() {
            try {
                var links = document.querySelectorAll('a[href*="/mod/resource/view.php"]');
                var stundenplanLinks = [];

                for (var i = 0; i < links.length; i++) {
                    var link = links[i];
                    var linkText = link.textContent.toLowerCase();
                    
                    // Must contain "stundenplan" and NOT be inside an h4 header
                    if (linkText.includes('stundenplan')) {
                        var parentH4 = link.closest('h4');
                        if (!parentH4) {
                            stundenplanLinks.push({
                                href: link.href,
                                text: link.textContent.trim()
                            });
                        }
                    }
                }
                
                console.log('Found ' + stundenplanLinks.length + ' timetable entries');
                
                if (stundenplanLinks.length === 0) {
                    return JSON.stringify({ found: false, reason: 'no_timetables', count: 0 });
                }

                for (var j = 0; j < stundenplanLinks.length; j++) {
                    console.log('Timetable ' + (j+1) + ': ' + stundenplanLinks[j].text);
                }

                for (var k = 0; k < stundenplanLinks.length; k++) {
                    var text = stundenplanLinks[k].text.toLowerCase();
                    if (text.includes('$klasse'.toLowerCase())) {
                        console.log('Found exact match for $klasse');
                        return JSON.stringify({ 
                            found: true, 
                            url: stundenplanLinks[k].href,
                            matchType: 'exact',
                            count: stundenplanLinks.length
                        });
                    }
                }

                for (var m = 0; m < stundenplanLinks.length; m++) {
                    var text = stundenplanLinks[m].text.toLowerCase();
                    if (text.includes('$klasseBase'.toLowerCase())) {
                        console.log('Found partial match for $klasseBase');
                        return JSON.stringify({ 
                            found: true, 
                            url: stundenplanLinks[m].href,
                            matchType: 'partial',
                            count: stundenplanLinks.length
                        });
                    }
                }

                console.log('No match found for $klasse or $klasseBase');
                return JSON.stringify({ 
                    found: false, 
                    reason: 'no_match',
                    count: stundenplanLinks.length,
                    available: stundenplanLinks.map(function(l) { return l.text; })
                });
                
            } catch(e) {
                console.error('Error searching timetables: ' + e.message);
                return JSON.stringify({ found: false, error: e.message });
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            if (fetchCancelled) return@evaluateJavascript

            try {
                val cleanResult = result.replace("\\\"", "\"").trim('"')
                val jsonResult = JSONObject(cleanResult)

                val found = jsonResult.optBoolean("found", false)
                val url = jsonResult.optString("url", "")
                val count = jsonResult.optInt("count", 0)

                L.d("MoodleFragment", "Timetable search result - Found: $found, Count: $count")

                if (found && url.isNotEmpty()) {
                    val matchType = jsonResult.optString("matchType", "unknown")
                    L.d("MoodleFragment", "Found timetable ($matchType match): $url")
                    updateFetchProgress(90, getString(R.string.moodle_downloading_timetable))
                    downloadSchedulePdf(url)
                } else {
                    val reason = jsonResult.optString("reason", "unknown")
                    val available = jsonResult.optJSONArray("available")
                    val availableList = mutableListOf<String>()
                    available?.let {
                        for (i in 0 until it.length()) {
                            availableList.add(it.getString(i))
                        }
                    }

                    L.e("MoodleFragment", "Timetable not found. Reason: $reason, Found $count timetables")
                    L.e("MoodleFragment", "Available timetables: $availableList")

                    handleFetchError(getString(R.string.moodle_timetable_not_found_for_class_detail, klasse))
                }
            } catch (e: Exception) {
                L.e("MoodleFragment", "Error parsing timetable search result", e)
                handleFetchError(getString(R.string.moodle_timetable_parse_error))
            }
        }
    }

    private fun searchForScheduleEntry() {
        if (fetchCancelled) return

        updateFetchProgress(85, getString(R.string.moodle_locating_schedule_file))

        val jsCode = """
        (function() {
            try {
                var links = document.querySelectorAll('a[href*="/mod/resource/view.php"]');
                
                for (var i = 0; i < links.length; i++) {
                    var link = links[i];
                    var linkText = link.textContent.toLowerCase();
                    
                    if (linkText.includes('klausurplan')) {
                        var parent = link.closest('h4');
                        if (!parent) {
                            return link.href;
                        }
                    }
                }
                
                return false;
            } catch(e) {
                return false;
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            if (fetchCancelled) return@evaluateJavascript

            val scheduleUrl = result.replace("\"", "")
            if (scheduleUrl != "false" && scheduleUrl.contains("/mod/resource/view.php")) {
                L.d("MoodleFragment", "Found exam schedule URL: $scheduleUrl")
                updateFetchProgress(90, getString(R.string.moodle_downloading_schedule))

                downloadSchedulePdf(scheduleUrl)
            } else {
                handleFetchError(getString(R.string.moodle_schedule_not_found))
            }
        }
    }

    private fun requestTimetableEntryNameFromUser() {
        activity?.runOnUiThread {
            moodleFetchProgressDialog?.let { dialog ->
                val dialogView = dialog.findViewById<View>(android.R.id.content)
                val editText = dialogView?.findViewById<EditText>(R.id.editTextExamProgramName)
                val btnContinue = dialogView?.findViewById<Button>(R.id.btnFetchContinue)
                val tvStatus = dialogView?.findViewById<TextView>(R.id.tvFetchStatus)
                val checkBoxSave = dialogView?.findViewById<CheckBox>(R.id.checkBoxSaveProgramName)

                editText?.hint = getString(R.string.moodle_enter_timetable_entry_name)
                tvStatus?.text = getString(R.string.moodle_timetable_not_found_prompt)
                editText?.visibility = View.VISIBLE
                btnContinue?.visibility = View.VISIBLE
                checkBoxSave?.visibility = View.VISIBLE

                btnContinue?.setOnClickListener {
                    val enteredName = editText?.text?.toString()?.trim()
                    if (!enteredName.isNullOrEmpty()) {
                        if (checkBoxSave?.isChecked == true) {
                            savedTimetableEntryName = enteredName
                            sharedPrefs.edit {
                                putString("saved_timetable_entry_name", enteredName)
                            }
                        }

                        editText.visibility = View.GONE
                        btnContinue.visibility = View.GONE
                        checkBoxSave?.visibility = View.GONE
                        searchForSpecificTimetableEntry(enteredName)
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.moodle_timetable_name_required), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun searchForSpecificTimetableEntry(entryName: String) {
        if (fetchCancelled) return

        updateFetchProgress(87, getString(R.string.moodle_searching_specific_timetable, entryName))

        val jsCode = """
        (function() {
            try {
                var links = document.querySelectorAll('a[href*="/mod/resource/view.php"]');
                
                for (var i = 0; i < links.length; i++) {
                    var link = links[i];
                    var linkText = link.textContent.toLowerCase();
                    
                    if (linkText.includes('$entryName'.toLowerCase())) {
                        var parent = link.closest('h4');
                        if (!parent) {
                            return link.href;
                        }
                    }
                }
                
                return false;
            } catch(e) {
                return false;
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            if (fetchCancelled) return@evaluateJavascript

            val timetableUrl = result.replace("\"", "")
            if (timetableUrl != "false" && timetableUrl.contains("/mod/resource/view.php")) {
                L.d("MoodleFragment", "Found specific timetable: $timetableUrl")
                updateFetchProgress(90, getString(R.string.moodle_downloading_timetable))
                downloadSchedulePdf(timetableUrl)
            } else {
                sharedPrefs.edit { remove("saved_timetable_entry_name") }
                savedTimetableEntryName = null
                requestTimetableEntryNameFromUser()
            }
        }
    }

    private fun downloadSchedulePdf(url: String) {
        if (fetchCancelled) return

        L.d("MoodleFragment", "Starting PDF download from: $url")

        val cookies = CookieManager.getInstance().getCookie(url)
        if (cookies == null) {
            L.e("MoodleFragment", "No cookies available for PDF download")
            handleFetchError("Session expired. Please try again.")
            return
        }

        dismissSessionConfirmDialog {
            Handler(Looper.getMainLooper()).postDelayed({
                proceedWithPdfDownload(url, cookies)
            }, 500)
        }
    }

    private fun proceedWithPdfDownload(url: String, cookies: String) {
        backgroundExecutor.execute {
            try {
                var currentUrl = url
                var redirectCount = 0
                val maxRedirects = 10
                var pdfBytes: ByteArray? = null

                while (redirectCount < maxRedirects && !fetchCancelled) {
                    L.d("MoodleFragment", "PDF download attempt $redirectCount: $currentUrl")

                    if (currentUrl.contains("/login/index.php")) {
                        L.w("MoodleFragment", "Hit login page during download - attempting to dismiss confirmation dialog")

                        activity?.runOnUiThread {
                            dismissSessionConfirmDialog {
                                L.d("MoodleFragment", "Session dialog dismissed, retrying download")

                                // Get fresh cookies after dismissal
                                Handler(Looper.getMainLooper()).postDelayed({
                                    val freshCookies = CookieManager.getInstance().getCookie(moodleBaseUrl)
                                    if (freshCookies != null) {
                                        proceedWithPdfDownload(url, freshCookies)
                                    } else {
                                        handleFetchError("Session expired. Please reload the page and try again.")
                                    }
                                }, 1000)
                            }
                        }
                        return@execute
                    }

                    val conn = URL(currentUrl).openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.instanceFollowRedirects = false

                    conn.setRequestProperty("Cookie", cookies)
                    conn.setRequestProperty("User-Agent", userAgent)
                    conn.setRequestProperty("Referer", moodleBaseUrl)
                    conn.setRequestProperty("Accept", "application/pdf,*/*")
                    conn.connectTimeout = 30000
                    conn.readTimeout = 30000

                    val responseCode = conn.responseCode
                    L.d("MoodleFragment", "PDF download response code: $responseCode")

                    when (responseCode) {
                        in 300..399 -> {
                            val location = conn.getHeaderField("Location")
                            L.d("MoodleFragment", "Redirect to: $location")
                            conn.disconnect()

                            if (location.isNullOrBlank()) {
                                L.e("MoodleFragment", "Redirect without location header")
                                break
                            }

                            currentUrl = if (location.startsWith("http")) {
                                location
                            } else if (location.startsWith("/")) {
                                "$moodleBaseUrl$location"
                            } else {
                                val baseUrl = currentUrl.substringBeforeLast("/")
                                "$baseUrl/$location"
                            }

                            redirectCount++
                        }
                        200 -> {
                            val contentType = conn.getHeaderField("Content-Type")
                            L.d("MoodleFragment", "Final content type: $contentType")

                            if (contentType?.contains("text/html") == true) {
                                L.w("MoodleFragment", "Got HTML instead of PDF - likely redirected to login")
                                conn.disconnect()

                                activity?.runOnUiThread {
                                    dismissSessionConfirmDialog {
                                        Handler(Looper.getMainLooper()).postDelayed({
                                            val freshCookies = CookieManager.getInstance().getCookie(moodleBaseUrl)
                                            if (freshCookies != null) {
                                                proceedWithPdfDownload(url, freshCookies)
                                            } else {
                                                handleFetchError("Session expired. Please reload the page and try again.")
                                            }
                                        }, 1000)
                                    }
                                }
                                return@execute
                            }

                            pdfBytes = conn.inputStream.use { it.readBytes() }
                            conn.disconnect()

                            L.d("MoodleFragment", "Downloaded ${pdfBytes.size} bytes")
                            break
                        }
                        401, 403 -> {
                            L.e("MoodleFragment", "Authentication failed: $responseCode")
                            conn.disconnect()

                            activity?.runOnUiThread {
                                dismissSessionConfirmDialog {
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        val freshCookies = CookieManager.getInstance().getCookie(moodleBaseUrl)
                                        if (freshCookies != null) {
                                            proceedWithPdfDownload(url, freshCookies)
                                        } else {
                                            handleFetchError("Session expired. Please reload the page and try again.")
                                        }
                                    }, 1000)
                                }
                            }
                            return@execute
                        }
                        else -> {
                            L.e("MoodleFragment", "Unexpected response code: $responseCode")
                            conn.disconnect()
                            break
                        }
                    }
                }

                activity?.runOnUiThread {
                    if (fetchCancelled) return@runOnUiThread

                    if (pdfBytes != null && pdfBytes.isNotEmpty()) {
                        val isPdf = pdfBytes.size >= 4 &&
                                pdfBytes[0] == '%'.code.toByte() &&
                                pdfBytes[1] == 'P'.code.toByte() &&
                                pdfBytes[2] == 'D'.code.toByte() &&
                                pdfBytes[3] == 'F'.code.toByte()

                        if (isPdf) {
                            L.d("MoodleFragment", "Valid PDF downloaded, processing...")
                            updateFetchProgress(95, getString(R.string.moodle_processing_schedule))
                            returnToDestinationWithPdf(pdfBytes)
                        } else {
                            L.e("MoodleFragment", "Downloaded file is not a valid PDF")
                            L.d("MoodleFragment", "File header: ${pdfBytes.take(10).map { it.toInt() }}")
                            handleFetchError("Downloaded file is not a valid PDF. Please try again.")
                        }
                    } else {
                        L.e("MoodleFragment", "No PDF data downloaded after $redirectCount redirects")
                        handleFetchError(getString(R.string.moodle_schedule_download_failed))
                    }
                }

            } catch (e: Exception) {
                L.e("MoodleFragment", "Error downloading schedule PDF", e)
                activity?.runOnUiThread {
                    if (!fetchCancelled) {
                        handleFetchError(getString(R.string.moodle_fetch_download_error, e.message))
                    }
                }
            }
        }
    }

    private fun returnToDestinationWithPdf(pdfBytes: ByteArray) {
        updateFetchProgress(100, getString(R.string.moodle_fetch_complete))

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                when (currentFetchType) {
                    FetchType.EXAM_SCHEDULE -> {
                        val navController = findNavController()
                        val bundle = Bundle().apply {
                            putByteArray("moodle_fetched_pdf", pdfBytes)
                            putBoolean("moodle_fetch_preserve_notes", fetchPreserveNotes)
                        }
                        cleanupFetchProcess()
                        navController.navigate(R.id.nav_klausuren, bundle)
                    }
                    FetchType.TIMETABLE -> {
                        val cacheDir = requireContext().cacheDir
                        val pdfFile = File(cacheDir, "moodle_timetable_${System.currentTimeMillis()}.pdf")
                        pdfFile.outputStream().use { it.write(pdfBytes) }

                        val pdfUrl = tabs.getOrNull(currentTabIndex)?.url ?: ""

                        requireActivity().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                            .edit {
                                putString("pending_timetable_pdf_path", pdfFile.absolutePath)
                                .putString("pending_timetable_pdf_url", pdfUrl)
                                .remove("temp_fetch_class")
                            }

                        cleanupFetchProcess()

                        activity?.let { act ->
                            val intent = Intent(act, SettingsActivity::class.java)
                            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            act.startActivity(intent)
                        }
                    }
                    else -> {
                        handleFetchError(getString(R.string.moodle_unknown_fetch_type))
                    }
                }
            } catch (e: Exception) {
                L.e("MoodleFragment", "Error returning with PDF", e)
                activity?.runOnUiThread {
                    Toast.makeText(activity, getString(R.string.moodle_return_error), Toast.LENGTH_SHORT).show()
                }
            }
        }, 500)
    }

    private fun updateFetchProgress(progress: Int, status: String, details: String = "") {
        moodleFetchProgressDialog?.let { dialog ->
            if (dialog.isShowing && !fetchCancelled) {
                val dialogView = dialog.findViewById<View>(android.R.id.content)
                val tvFetchStatus = dialogView?.findViewById<TextView>(R.id.tvFetchStatus)
                val tvFetchDetails = dialogView?.findViewById<TextView>(R.id.tvFetchDetails)
                val progressBar = dialogView?.findViewById<ProgressBar>(R.id.progressBarFetch)

                activity?.runOnUiThread {
                    tvFetchStatus?.text = status
                    progressBar?.progress = progress

                    if (details.isNotEmpty()) {
                        tvFetchDetails?.text = details
                        tvFetchDetails?.visibility = View.VISIBLE
                    } else {
                        tvFetchDetails?.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun handleFetchError(errorMessage: String) {
        L.e("MoodleFragment", "Fetch error: $errorMessage")

        activity?.runOnUiThread {
            moodleFetchProgressDialog?.dismiss()
            cleanupFetchProcess()

            Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_LONG).show()

            try {
                findNavController().navigate(R.id.nav_klausuren)
            } catch (e: Exception) {
                L.e("MoodleFragment", "Error navigating back to exam fragment", e)
            }
        }
    }

    private fun cleanupFetchProcess() {
        isFetchingFromMoodle = false
        fetchCancelled = false
        currentFetchType = null
        fetchPreserveNotes = false
        fetchProgramName = null
        userProvidedProgramName = null
        moodleFetchProgressDialog?.dismiss()
        moodleFetchProgressDialog = null
    }

    private fun detectCurrentTheme() {
        val sharedPreferences = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val followSystemTheme = sharedPreferences.getBoolean("follow_system_theme", true)

        isDarkTheme = if (followSystemTheme) {
            val nightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        } else {
            sharedPreferences.getBoolean("dark_mode_enabled", false)
        }
    }

    private fun handleDocxFile(url: String, cookies: String?, forceDownload: Boolean = false) {
        if (forceDownload) {
            downloadToDeviceWithCookies(url, cookies)
        } else {
            convertDocxToPdfAndOpen(url, cookies)
        }
    }

    private fun convertDocxToPdfAndOpen(url: String, cookies: String?) {
        var loadingCancelled = false
        val dialogView = layoutInflater.inflate(R.layout.dialog_pdf_loading, null)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tvPdfLoadingStatus)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressBarPdfLoading)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelPdfLoading)

        val progressDialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .show()

        btnCancel.setOnClickListener {
            loadingCancelled = true
            progressDialog.dismiss()
        }

        backgroundExecutor.execute {
            try {
                activity?.runOnUiThread {
                    tvStatus.text = getString(R.string.moodle_downloading_docx)
                    progressBar.progress = 0
                }

                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"

                if (!cookies.isNullOrBlank()) {
                    connection.setRequestProperty("Cookie", cookies)
                }

                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Referer", moodleBaseUrl)
                connection.connectTimeout = 30000
                connection.readTimeout = 30000

                if (connection.responseCode == 200 && !loadingCancelled) {
                    val contentLength = connection.contentLength
                    val inputStream = connection.inputStream
                    val outputStream = ByteArrayOutputStream()

                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L

                    while (inputStream.read(buffer).also { bytesRead = it } != -1 && !loadingCancelled) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        if (contentLength > 0) {
                            val progress = ((totalBytesRead * 30) / contentLength).toInt()
                            activity?.runOnUiThread {
                                progressBar.progress = progress
                            }
                        }
                    }

                    val docxBytes = outputStream.toByteArray()
                    inputStream.close()
                    connection.disconnect()

                    if (!loadingCancelled) {
                        activity?.runOnUiThread {
                            tvStatus.text = getString(R.string.moodle_converting_docx)
                            progressBar.progress = 30
                        }

                        val pdfBytes = convertDocxByteArrayToPdf(docxBytes)

                        if (pdfBytes != null && !loadingCancelled) {
                            activity?.runOnUiThread {
                                progressBar.progress = 80
                                tvStatus.text = getString(R.string.moodle_saving_pdf)
                            }

                            val cacheDir = requireContext().cacheDir
                            val pdfFile = File(cacheDir, "converted_${System.currentTimeMillis()}.pdf")
                            pdfFile.outputStream().use { it.write(pdfBytes) }

                            val fileName = try {
                                URLDecoder.decode(url.substringAfterLast("/").substringBefore("?"), "UTF-8")
                                    .replace(".docx", ".pdf")
                            } catch (_: Exception) {
                                "document.pdf"
                            }

                            if (!loadingCancelled) {
                                activity?.runOnUiThread {
                                    progressBar.progress = 100
                                    progressDialog.dismiss()
                                    showPdfViewer(pdfFile, fileName, url)
                                }
                            }
                        } else {
                            throw Exception("Failed to convert DOCX to PDF")
                        }
                    }
                } else {
                    connection.disconnect()
                    throw Exception("HTTP error: ${connection.responseCode}")
                }
            } catch (e: Exception) {
                L.e("MoodleFragment", "Error converting DOCX", e)
                if (!loadingCancelled) {
                    activity?.runOnUiThread {
                        progressDialog.dismiss()
                        Toast.makeText(requireContext(),
                            getString(R.string.moodle_docx_conversion_failed),
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun convertDocxByteArrayToPdf(docxBytes: ByteArray): ByteArray? {
        return try {
            L.d("MoodleFragment", "Starting DOCX to PDF conversion")

            val outputStream = ByteArrayOutputStream()
            val pdfDocument = com.itextpdf.text.Document(com.itextpdf.text.PageSize.A4, 50f, 50f, 50f, 50f)
            com.itextpdf.text.pdf.PdfWriter.getInstance(pdfDocument, outputStream)
            pdfDocument.open()

            val docxData = parseDocxStructure(docxBytes)

            renderDocxToPdf(docxData, pdfDocument)

            pdfDocument.close()

            val result = outputStream.toByteArray()
            L.d("MoodleFragment", "Conversion successful: ${result.size} bytes")
            result

        } catch (e: Exception) {
            L.e("MoodleFragment", "Error in DOCX to PDF conversion", e)
            null
        }
    }

    private data class DocxData(
        val paragraphs: List<DocxParagraph>,
        val images: Map<String, ByteArray>
    )

    private data class DocxParagraph(
        val runs: List<DocxRun>,
        val alignment: String? = null,
        val spacingBefore: Int = 0,
        val spacingAfter: Int = 0,
        val isTable: Boolean = false,
        val tableData: List<List<String>>? = null
    )

    private data class DocxRun(
        val text: String,
        val isBold: Boolean = false,
        val isItalic: Boolean = false,
        val isUnderline: Boolean = false,
        val fontSize: Float = 11f,
        val color: String? = null,
        val imageRef: String? = null
    )

    private fun parseDocxStructure(docxBytes: ByteArray): DocxData {
        val zipInputStream = ZipInputStream(docxBytes.inputStream())
        var entry: ZipEntry?
        val images = mutableMapOf<String, ByteArray>()
        val imageRelations = mutableMapOf<String, String>()
        var documentXml = ""

        while (zipInputStream.nextEntry.also { entry = it } != null) {
            when {
                entry?.name == "word/document.xml" -> {
                    documentXml = zipInputStream.bufferedReader().readText()
                }
                entry?.name == "word/_rels/document.xml.rels" -> {
                    val relsXml = zipInputStream.bufferedReader().readText()
                    parseImageRelationships(relsXml, imageRelations)
                }
                entry?.name?.startsWith("word/media/") == true -> {
                    val imageName = entry.name.substringAfterLast("/")
                    images[imageName] = zipInputStream.readBytes()
                }
            }
        }
        zipInputStream.close()

        L.d("MoodleFragment", "Found ${images.size} images in DOCX")
        L.d("MoodleFragment", "Found ${imageRelations.size} image relationships")

        val mappedImages = mutableMapOf<String, ByteArray>()
        imageRelations.forEach { (relId, imagePath) ->
            val imageName = imagePath.substringAfterLast("/")
            images[imageName]?.let { imageData ->
                mappedImages[relId] = imageData
                L.d("MoodleFragment", "Mapped image: $relId -> $imageName")
            }
        }

        val paragraphs = parseDocumentXml(documentXml)

        return DocxData(paragraphs, mappedImages)
    }

    private fun parseImageRelationships(relsXml: String, relations: MutableMap<String, String>) {
        val relRegex = "<Relationship[^>]+Id=\"([^\"]+)\"[^>]+Target=\"([^\"]+)\"[^>]*>".toRegex()
        relRegex.findAll(relsXml).forEach { match ->
            val id = match.groupValues[1]
            val target = match.groupValues[2]
            if (target.contains("media/")) {
                relations[id] = target
                L.d("MoodleFragment", "Found image relationship: $id -> $target")
            }
        }
    }

    private fun parseDocumentXml(xml: String): List<DocxParagraph> {
        val paragraphs = mutableListOf<DocxParagraph>()

        try {
            val bodyRegex = "<w:body>(.*?)</w:body>".toRegex(RegexOption.DOT_MATCHES_ALL)
            val bodyMatch = bodyRegex.find(xml)
            val bodyContent = bodyMatch?.groupValues?.get(1) ?: xml

            data class ElementWithPosition(val position: Int, val paragraph: DocxParagraph)
            val allElements = mutableListOf<ElementWithPosition>()

            val paragraphRegex = "<w:p[\\s>](.*?)</w:p>".toRegex(RegexOption.DOT_MATCHES_ALL)
            paragraphRegex.findAll(bodyContent).forEach { paraMatch ->
                val paraXml = paraMatch.value
                val alignment = extractAlignment(paraXml)
                val runs = parseRuns(paraXml)

                if (runs.isNotEmpty()) {
                    allElements.add(ElementWithPosition(
                        paraMatch.range.first,
                        DocxParagraph(runs = runs, alignment = alignment)
                    ))
                } else {
                    allElements.add(ElementWithPosition(
                        paraMatch.range.first,
                        DocxParagraph(runs = listOf(DocxRun(" ")))
                    ))
                }
            }

            val tableRegex = "<w:tbl>(.*?)</w:tbl>".toRegex(RegexOption.DOT_MATCHES_ALL)
            tableRegex.findAll(bodyContent).forEach { tableMatch ->
                val tableXml = tableMatch.value
                val tableData = parseTable(tableXml)
                if (tableData.isNotEmpty()) {
                    allElements.add(ElementWithPosition(
                        tableMatch.range.first,
                        DocxParagraph(runs = emptyList(), isTable = true, tableData = tableData)
                    ))
                }
            }

            allElements.sortBy { it.position }
            paragraphs.addAll(allElements.map { it.paragraph })

            L.d("MoodleFragment", "Parsed ${paragraphs.size} elements from DOCX in correct order")

        } catch (e: Exception) {
            L.e("MoodleFragment", "Error parsing document XML", e)
        }

        return paragraphs
    }

    private fun parseTable(tableXml: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()

        val rowRegex = "<w:tr[\\s>](.*?)</w:tr>".toRegex(RegexOption.DOT_MATCHES_ALL)

        rowRegex.findAll(tableXml).forEach { rowMatch ->
            val rowXml = rowMatch.value
            val cells = mutableListOf<String>()

            val cellRegex = "<w:tc>(.*?)</w:tc>".toRegex(RegexOption.DOT_MATCHES_ALL)

            cellRegex.findAll(rowXml).forEach { cellMatch ->
                val cellXml = cellMatch.value
                val cellText = extractTextFromXml(cellXml)
                cells.add(cellText)
            }

            if (cells.isNotEmpty()) {
                rows.add(cells)
            }
        }

        return rows
    }

    private fun extractAlignment(paraXml: String): String? {
        val alignRegex = "<w:jc\\s+w:val=\"([^\"]+)\"".toRegex()
        return alignRegex.find(paraXml)?.groupValues?.get(1)
    }

    private fun parseRuns(paraXml: String): List<DocxRun> {
        val runs = mutableListOf<DocxRun>()

        val runRegex = "<w:r[\\s>](.*?)</w:r>".toRegex(RegexOption.DOT_MATCHES_ALL)

        runRegex.findAll(paraXml).forEach { runMatch ->
            val runXml = runMatch.value

            if (runXml.contains("<w:drawing>") || runXml.contains("<w:pict>") || runXml.contains("<a:blip")) {
                val imageRef = extractImageReference(runXml)
                if (imageRef != null) {
                    L.d("MoodleFragment", "Found image reference in run: $imageRef")
                    runs.add(DocxRun("", imageRef = imageRef))
                    return@forEach
                }
            }

            val text = extractTextFromXml(runXml)
            if (text.isNotEmpty()) {
                val isBold = runXml.contains("<w:b/>") || runXml.contains("<w:b ")
                val isItalic = runXml.contains("<w:i/>") || runXml.contains("<w:i ")
                val isUnderline = runXml.contains("<w:u ")
                val fontSize = extractFontSize(runXml)
                val color = extractColor(runXml)

                runs.add(DocxRun(
                    text = text,
                    isBold = isBold,
                    isItalic = isItalic,
                    isUnderline = isUnderline,
                    fontSize = fontSize,
                    color = color
                ))
            }
        }

        return runs
    }

    private fun extractTextFromXml(xml: String): String {
        val textRegex = "<w:t[^>]*>([^<]*)</w:t>".toRegex()
        return textRegex.findAll(xml)
            .joinToString("") { it.groupValues[1] }
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    private fun extractFontSize(runXml: String): Float {
        val sizeRegex = "<w:sz\\s+w:val=\"(\\d+)\"".toRegex()
        val match = sizeRegex.find(runXml)
        return if (match != null) {
            val halfPoints = match.groupValues[1].toFloatOrNull() ?: 22f
            (halfPoints / 2f).coerceIn(6f, 72f)
        } else {
            11f
        }
    }

    private fun extractColor(runXml: String): String? {
        val colorRegex = "<w:color\\s+w:val=\"([^\"]+)\"".toRegex()
        return colorRegex.find(runXml)?.groupValues?.get(1)
    }

    private fun extractImageReference(runXml: String): String? {
        // Format 1: r:embed in drawing (what does that even mean)
        var embedRegex = "r:embed=\"([^\"]+)\"".toRegex()
        var match = embedRegex.find(runXml)
        if (match != null) {
            return match.groupValues[1]
        }

        // Format 2: r:id in blip
        embedRegex = "r:id=\"([^\"]+)\"".toRegex()
        match = embedRegex.find(runXml)
        if (match != null) {
            return match.groupValues[1]
        }

        return null
    }

    private fun renderDocxToPdf(docxData: DocxData, pdfDocument: com.itextpdf.text.Document) {
        for (paragraph in docxData.paragraphs) {
            if (paragraph.isTable && paragraph.tableData != null) {
                renderTable(paragraph.tableData, pdfDocument)
            } else {
                renderParagraph(paragraph, docxData.images, pdfDocument)
            }
        }
    }

    private fun renderParagraph(
        paragraph: DocxParagraph,
        images: Map<String, ByteArray>,
        pdfDocument: com.itextpdf.text.Document
    ) {
        val pdfPara = com.itextpdf.text.Paragraph()

        when (paragraph.alignment) {
            "center" -> pdfPara.alignment = com.itextpdf.text.Element.ALIGN_CENTER
            "right" -> pdfPara.alignment = com.itextpdf.text.Element.ALIGN_RIGHT
            "both" -> pdfPara.alignment = com.itextpdf.text.Element.ALIGN_JUSTIFIED
            else -> pdfPara.alignment = com.itextpdf.text.Element.ALIGN_LEFT
        }

        pdfPara.spacingBefore = paragraph.spacingBefore * 0.35f
        pdfPara.spacingAfter = if (paragraph.spacingAfter > 0) paragraph.spacingAfter * 0.35f else 3f

        var hasContent = false

        for (run in paragraph.runs) {
            if (run.imageRef != null) {
                val imageData = images[run.imageRef]
                if (imageData != null) {
                    try {
                        val image = com.itextpdf.text.Image.getInstance(imageData)

                        val maxWidth = pdfDocument.pageSize.width - pdfDocument.leftMargin() - pdfDocument.rightMargin()
                        val maxHeight = (pdfDocument.pageSize.height - pdfDocument.topMargin() - pdfDocument.bottomMargin()) * 0.4f

                        val widthScale = maxWidth / image.width
                        val heightScale = maxHeight / image.height
                        val scale = minOf(widthScale, heightScale, 1f)

                        if (scale < 1f) {
                            image.scalePercent(scale * 100)
                        }

                        image.alignment = when (paragraph.alignment) {
                            "center" -> com.itextpdf.text.Element.ALIGN_CENTER
                            "right" -> com.itextpdf.text.Element.ALIGN_RIGHT
                            else -> com.itextpdf.text.Element.ALIGN_LEFT
                        }

                        if (hasContent) {
                            pdfDocument.add(pdfPara)
                            pdfPara.clear()
                            hasContent = false
                        }

                        pdfDocument.add(image)
                        L.d("MoodleFragment", "Added image: ${image.width}x${image.height} scaled to ${image.scaledWidth}x${image.scaledHeight}")

                    } catch (e: Exception) {
                        L.e("MoodleFragment", "Error adding image with ref ${run.imageRef}", e)
                    }
                } else {
                    L.w("MoodleFragment", "Image data not found for reference: ${run.imageRef}")
                }
            } else if (run.text.isNotEmpty()) {
                val fontStyle = when {
                    run.isBold && run.isItalic -> com.itextpdf.text.Font.BOLDITALIC
                    run.isBold -> com.itextpdf.text.Font.BOLD
                    run.isItalic -> com.itextpdf.text.Font.ITALIC
                    else -> com.itextpdf.text.Font.NORMAL
                }

                val font = com.itextpdf.text.FontFactory.getFont(
                    com.itextpdf.text.FontFactory.HELVETICA,
                    run.fontSize,
                    fontStyle
                )

                if (run.color != null && run.color != "auto") {
                    try {
                        val colorInt = Integer.parseInt(run.color, 16)
                        val r = (colorInt shr 16 and 0xFF) / 255f
                        val g = (colorInt shr 8 and 0xFF) / 255f
                        val b = (colorInt and 0xFF) / 255f
                        font.color = com.itextpdf.text.BaseColor(r, g, b)
                    } catch (_: Exception) {
                        // stay on default color
                    }
                }

                val chunk = com.itextpdf.text.Chunk(run.text, font)
                if (run.isUnderline) {
                    chunk.setUnderline(0.5f, -2f)
                }

                pdfPara.add(chunk)
                hasContent = true
            }
        }

        if (hasContent || paragraph.runs.isEmpty()) {
            pdfDocument.add(pdfPara)
        }
    }

    private fun renderTable(
        tableData: List<List<String>>,
        pdfDocument: com.itextpdf.text.Document
    ) {
        if (tableData.isEmpty()) return

        val maxCols = tableData.maxOfOrNull { it.size } ?: return
        val table = com.itextpdf.text.pdf.PdfPTable(maxCols)
        table.widthPercentage = 100f
        table.spacingBefore = 10f
        table.spacingAfter = 10f

        val font = com.itextpdf.text.FontFactory.getFont(
            com.itextpdf.text.FontFactory.HELVETICA,
            10f
        )

        for (row in tableData) {
            for (cellText in row) {
                val cell = com.itextpdf.text.pdf.PdfPCell(com.itextpdf.text.Phrase(cellText, font))
                cell.setPadding(5f)
                cell.borderWidth = 1f
                table.addCell(cell)
            }
            repeat(maxCols - row.size) {
                val emptyCell = com.itextpdf.text.pdf.PdfPCell()
                emptyCell.setPadding(5f)
                table.addCell(emptyCell)
            }
        }

        pdfDocument.add(table)
    }

    private fun showImagePageDialog(url: String) { // may be temporary, as i cant figure out how to preserve session cookies on moodle image pages
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.moodle_image_page_detected))
            .setMessage(getString(R.string.moodle_image_page_message))
            .setPositiveButton(getString(R.string.moodle_open_browser)) { _, _ ->
                openInExternalBrowser(url)
            }
            .setNegativeButton(getString(R.string.moodle_cancel), null)
            .show()
            .apply {
                val buttonColor = requireContext().getThemeColor(R.attr.dialogSectionButtonColor)
                getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
                getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
            }
    }
}