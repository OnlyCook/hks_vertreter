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
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.thecooker.vertretungsplaner.R
import androidx.core.content.edit
import com.thecooker.vertretungsplaner.L
import java.io.File
import android.app.DownloadManager
import android.app.UiModeManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.ScaleGestureDetector
import android.view.ViewTreeObserver
import android.view.animation.AccelerateInterpolator
import android.view.animation.Animation
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.view.inputmethod.InputMethodManager
import androidx.annotation.AttrRes
import androidx.constraintlayout.widget.ConstraintLayout
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
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.maps.model.StyleSpan
import com.itextpdf.text.BaseColor
import com.itextpdf.text.Chunk
import com.itextpdf.text.Document
import com.itextpdf.text.Element
import com.itextpdf.text.Font
import com.itextpdf.text.FontFactory
import com.itextpdf.text.Image
import com.itextpdf.text.PageSize
import com.itextpdf.text.Paragraph
import com.itextpdf.text.Phrase
import com.itextpdf.text.pdf.BaseFont
import com.itextpdf.text.pdf.PdfPCell
import com.itextpdf.text.pdf.PdfPTable
import com.itextpdf.text.pdf.PdfPageEventHelper
import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.PdfWriter
import com.itextpdf.text.pdf.parser.LocationTextExtractionStrategy
import com.itextpdf.text.pdf.parser.PdfTextExtractor
import com.thecooker.vertretungsplaner.FetchType
import com.thecooker.vertretungsplaner.SettingsActivity
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.Jsoup
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class MoodleFragment : Fragment() {

    private var isFragmentActive = false
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

    companion object {
        private const val CACHE_SIZE_LIMIT = 50 * 1024 * 1024L // 50mb
        private const val CACHE_RETENTION_DAYS = 7L
    }

    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var encryptedPrefs: SharedPreferences

    private var isPageFullyLoaded = false
    private var isWebViewInitialized = false
    private var isAppClosing = false

    private val moodleBaseUrl = "https://moodle.kleyer.eu"
    private val loginUrl = "$moodleBaseUrl/login/index.php"
    private var isLoginDialogShown = false
    private var hasLoginFailed = false
    private var loginRetryCount = 0
    private var lastSuccessfulLoginCheck = 0L
    private var loginSuccessConfirmed = false

    // login dialog
    private var dialogButtonMonitorHandler: Handler? = null
    private var dialogButtonMonitorRunnable: Runnable? = null
    private var isMonitoringDialogButton = false
    private var wasOnLogoutConfirmPage = false
    private var logoutConfirmPageUrl = ""
    private var isHandlingLogout = false
    private var isProcessingInstantLogout = false

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

    // pdf viewer swipe indicator
    private var pdfSwipeIndicator: TextView? = null
    private var isPdfSwiping = false
    private var pdfSwipeStartX = 0f
    private var pdfSwipeProgress = 0f

    // pdf viewer swipe mode zooming
    private var pdfScaleFactor = 1f
    private var pdfScaleDetector: ScaleGestureDetector? = null
    private val MIN_ZOOM = 1.0f
    private val MAX_ZOOM = 3.0f
    private var isCurrentlyZooming = false
    private var zoomHandler: Handler? = null
    private var zoomStabilizeRunnable: Runnable? = null
    private var lastAppliedScale = 1f

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

    // moodle chat override
    private var messageInputDialog: AlertDialog? = null
    private var isMonitoringMessageInput = false
    private var messageInputMonitorHandler: Handler? = null
    private var messageInputMonitorRunnable: Runnable? = null
    private var currentMessageChatType: MessageChatType? = null
    private var lastMessageInputSetupTime = 0L
    private val MESSAGE_INPUT_SETUP_COOLDOWN = 1000L
    private var messageDialogSuppressedForSession = false

    private enum class MessageChatType {
        MAIN_CHAT,
        SIDEBAR_CHAT
    }

    private data class MessageRecipientInfo(
        val username: String,
        val userIconUrl: String?
    )

    // text viewer
    private var textViewerManager: TextViewerManager? = null
    private var isTextViewerMode = false
    private lateinit var textViewerContainer: FrameLayout
    private lateinit var textContentScrollView: ScrollView
    private lateinit var lineNumberScrollView: ScrollView
    private lateinit var textViewerContent: TextView
    private lateinit var lineNumbersContainer: LinearLayout
    private var textSearchDialog: AlertDialog? = null
    private var searchResults = listOf<Int>()
    private var currentSearchIndex = 0
    private var originalBackgroundColor: Int = 0

    // dark mode injection
    private var isDarkModeInjected = false
    private var moodleDarkModeEnabled = false
    private var isDarkModeReady = false
    private var pendingDarkModeUrl: String? = null
    private var darkModeInjectionAttempts = 0
    private val MAX_DARK_MODE_RETRIES = 5
    private var darkModeRetryHandler: Handler? = null
    private var darkModeRetryRunnable: Runnable? = null

    // course entry downloads
    data class CourseEntry(
        val url: String,
        val iconUrl: String,
        val linkType: String,
        val name: String,
        val sectionName: String
    )

    data class CourseSection(
        val sectionName: String,
        val entries: List<CourseEntry>,
        var isExpanded: Boolean = false,
        var isSelected: Boolean = false
    )

    private var downloadButtonMonitorHandler: Handler? = null
    private var downloadButtonMonitorRunnable: Runnable? = null
    private var isMonitoringDownloadButton = false

    private val downloadsDirectory: File
        get() {
            val baseDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requireContext().getExternalFilesDir(null)
            } else {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            }
            return File(baseDir, "HKS_Moodle_Downloads")
        }

    //region **SETUP**

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        arguments?.getString("moodle_search_category")?.let { category ->
            val summary = arguments?.getString("moodle_search_summary") ?: ""
            val entryId = arguments?.getString("moodle_entry_id") ?: ""
            Handler(Looper.getMainLooper()).postDelayed({
                searchForMoodleEntry(category, summary, entryId)
            }, 2500)

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

    private fun configureCacheManagement() {
        backgroundExecutor.execute {
            try {
                val cacheDir = requireContext().cacheDir
                val now = System.currentTimeMillis()
                val retentionMillis = CACHE_RETENTION_DAYS * 24 * 60 * 60 * 1000L

                var totalSize = 0L
                val files = cacheDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return@execute

                files.forEach { file ->
                    if (now - file.lastModified() > retentionMillis) {
                        file.deleteRecursively()
                    } else {
                        totalSize += file.length()
                    }
                }

                if (totalSize > CACHE_SIZE_LIMIT) {
                    var removedSize = 0L
                    files.reversed().forEach { file ->
                        if (totalSize - removedSize > CACHE_SIZE_LIMIT) {
                            removedSize += file.length()
                            file.deleteRecursively()
                        }
                    }
                }

                L.d("MoodleFragment", "Cache management: kept ${totalSize / 1024 / 1024}MB")
            } catch (e: Exception) {
                L.e("MoodleFragment", "Error in cache management", e)
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
            clearStaleCookies()
        }

        if (!isWebViewInitialized) {
            Handler(Looper.getMainLooper()).post {
                setupWebView()
                configureCacheManagement()
                ensureCleanDefaultTab()
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

    private fun clearStaleCookies() {
        L.d("MoodleFragment", "Clearing stale cookies from previous session")
        CookieManager.getInstance().apply {
            removeAllCookies { success ->
                L.d("MoodleFragment", "Cleared stale cookies: $success")
            }
            removeSessionCookies { success ->
                L.d("MoodleFragment", "Cleared stale session cookies: $success")
            }
            flush()
        }
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
        textViewerContainer = root.findViewById(R.id.textViewerContainer)
        textContentScrollView = root.findViewById(R.id.textContentScrollView)
        lineNumberScrollView = root.findViewById(R.id.lineNumberScrollView)
        textViewerContent = root.findViewById(R.id.textViewerContent)
        lineNumbersContainer = root.findViewById(R.id.lineNumbersContainer)
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun setupWebView() {
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)

            cacheMode = WebSettings.LOAD_DEFAULT
            setRenderPriority(WebSettings.RenderPriority.HIGH)

            databaseEnabled = true
            domStorageEnabled = true

            allowFileAccess = true
            allowContentAccess = true
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
            flush()
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

                if (!url.startsWith("https://moodle.kleyer.eu/")) {
                    L.d("MoodleFragment", "Blocked external URL: $url")
                    return true
                }

                if (url.contains("/login/logout.php?sesskey=")) {
                    L.d("MoodleFragment", "Intercepted instant logout - redirecting to confirmation page")
                    webView.loadUrl("https://moodle.kleyer.eu/login/logout.php")
                    return true
                }

                if (url.contains("/mod/")) {
                    L.d("MoodleFragment", "Detected /mod/ URL, analyzing link element: $url")
                    analyzeLinkElementAndHandle(url)
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

                if (moodleDarkModeEnabled && url != null) {
                    verifyAndFixDarkMode()
                } else {
                    activity?.runOnUiThread {
                        webView.visibility = View.VISIBLE
                        hideLoadingBar()
                    }
                }

                activity?.runOnUiThread {
                    updateUIState()
                    updateDashboardButtonIcon()

                    if (url?.contains("/message/index.php") == true) {
                        isAtTop = false
                        hideExtendedHeaderWithAnimation()
                    }

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

                if (url == "https://moodle.kleyer.eu/calendar/export.php") {
                    L.d("MoodleFragment", "Detected calendar export page - redirecting to dashboard")
                    webView.loadUrl("$moodleBaseUrl/my/")
                    return
                }

                val wasOnLoginPageBefore = wasOnLoginPage
                val isNowOnLoginPage = (url == loginUrl || url?.contains("login/index.php") == true)

                if (wasOnLoginPageBefore && !isNowOnLoginPage && url != null) {
                    L.d("MoodleFragment", "User logged in successfully - triggering calendar refresh")
                    Handler(Looper.getMainLooper()).postDelayed({
                        refreshCalendarDataInBackground()
                    }, 500)
                }

                if (isProcessingInstantLogout && url == "https://moodle.kleyer.eu/login/index.php") {
                    L.d("MoodleFragment", "Instant logout completed - user is now on login page")
                    isProcessingInstantLogout = false

                    handleLogout()
                    return
                }

                if (isProcessingInstantLogout) {
                    L.d("MoodleFragment", "Still processing instant logout, skipping normal page processing")
                    return
                }

                if (url?.contains("/login/logout.php?sesskey=") == true) {
                    L.d("MoodleFragment", "Still on instant logout URL, waiting for redirect")
                    return
                }

                if (url == "https://moodle.kleyer.eu/login/logout.php") {
                    L.d("MoodleFragment", "On logout confirmation page")
                    wasOnLogoutConfirmPage = true
                    logoutConfirmPageUrl = url
                    return
                }

                if (wasOnLogoutConfirmPage &&
                    logoutConfirmPageUrl == "https://moodle.kleyer.eu/login/logout.php" &&
                    url == "https://moodle.kleyer.eu/login/index.php") {

                    L.d("MoodleFragment", "User confirmed logout - clearing data and showing login UI")
                    wasOnLogoutConfirmPage = false
                    logoutConfirmPageUrl = ""

                    handleLogout()
                    return
                }

                if (wasOnLogoutConfirmPage && url != "https://moodle.kleyer.eu/login/index.php") {
                    L.d("MoodleFragment", "User cancelled logout - staying logged in")
                    wasOnLogoutConfirmPage = false
                    logoutConfirmPageUrl = ""
                    return
                }

                if (url?.startsWith("https://moodle.kleyer.eu/course/view.php") == true) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        injectCourseDownloadButton()
                    }, 500)
                }

                if (isNowOnLoginPage) {
                    if (!wasOnLoginPage) {
                        wasOnLoginPage = true
                        loginSuccessConfirmed = false

                        Handler(Looper.getMainLooper()).postDelayed({
                            waitForPageReady(maxAttempts = 30) { pageReady ->
                                if (!pageReady) {
                                    L.e("MoodleFragment", "Page ready timeout")
                                    return@waitForPageReady
                                }

                                checkIfSessionTerminationDialog { isTerminationDialog ->
                                    if (isTerminationDialog) {
                                        L.d("MoodleFragment", "SESSION TERMINATION DIALOG DETECTED - Dismissing")
                                        dismissSessionTerminationDialog()
                                    } else {
                                        L.d("MoodleFragment", "REGULAR LOGIN PAGE DETECTED")
                                        stopDialogButtonMonitoring()

                                        val dontShowDialog = sharedPrefs.getBoolean("moodle_dont_show_login_dialog", false)

                                        if (!dontShowDialog) {
                                            Handler(Looper.getMainLooper()).postDelayed({
                                                injectDialogButtonOnLoginPage()
                                            }, 400)
                                        }

                                        checkLoginFailure { loginFailed ->
                                            hasLoginFailed = loginFailed

                                            if (!isLoginDialogShown && !loginSuccessConfirmed && !isHandlingLogout) {
                                                checkAutoLogin()
                                            }
                                        }
                                    }
                                }
                            }
                        }, 600)
                    }
                }

                if (url != null) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        injectMobileOptimizations(url)
                    }, 100)
                }

                activity?.runOnUiThread {
                    updateUIState()
                    showExtendedHeaderInitially()

                    url?.let {
                        if (!it.contains("forcedownload=1") && !it.contains("pluginfile.php") &&
                            !it.contains("calendar/export.php")) {
                            updateUrlBar(it)
                        }
                    }
                }

                Handler(Looper.getMainLooper()).postDelayed({
                    updateCounters()
                }, 300)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                activity?.runOnUiThread {
                    showLoadingBar()
                    horizontalProgressBar.progress = 0
                }
                isPageFullyLoaded = false

                darkModeInjectionAttempts = 0

                if (moodleDarkModeEnabled && url != null) {
                    L.d("MoodleFragment", "Page started - injecting dark mode CSS immediately")
                    webView.visibility = View.INVISIBLE
                    injectDarkTheme()
                } else {
                    webView.visibility = View.VISIBLE
                }
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

            val currentUrl = webView.url ?: ""
            val isOnMessagesPage = currentUrl.contains("/message/index.php")

            if (isOnMessagesPage && event.action == MotionEvent.ACTION_DOWN) {
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

        val btnCourseDownloads = view?.findViewById<ImageButton>(R.id.btnCourseDownloads)
        btnCourseDownloads?.setOnClickListener {
            val intent = Intent(requireContext(), CourseDownloadsActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupSharedPreferences() {
        sharedPrefs = requireActivity().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

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

        loadMoodleDarkModePreference()
    }

    private fun showExtendedHeaderInitially() {
        isAtTop = true
        updateExtendedHeaderVisibility()
    }

    private fun checkAutoLogin() {
        if (!isAdded || context == null) {
            L.d("MoodleFragment", "Fragment not attached, skipping auto-login")
            return
        }

        if (isLoginInProgress) {
            L.d("MoodleFragment", "Login already in progress, skipping checkAutoLogin")
            return
        }

        if (isLoginDialogShown) {
            L.d("MoodleFragment", "Login dialog already shown, skipping")
            return
        }

        if (isHandlingLogout || isProcessingInstantLogout) {
            L.d("MoodleFragment", "Currently handling logout, skipping auto-login")
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

        ensureCleanDefaultTab()

        checkSessionExpired { isSessionExpired ->
            if (!isAdded || context == null) {
                L.d("MoodleFragment", "Fragment detached during session check")
                return@checkSessionExpired
            }

            if (isSessionExpired) {
                L.w("MoodleFragment", "Session expired detected on page load - refreshing and retrying")
                sessionExpiredRetryCount = 0

                webView.loadUrl(loginUrl)

                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isAdded || context == null) {
                        L.d("MoodleFragment", "Fragment detached during session refresh")
                        return@postDelayed
                    }

                    waitForPageReady { pageReady ->
                        if (!isAdded || context == null) {
                            L.d("MoodleFragment", "Fragment detached after page ready")
                            return@waitForPageReady
                        }

                        if (pageReady) {
                            checkSessionExpired { stillExpired ->
                                if (!isAdded || context == null) {
                                    L.d("MoodleFragment", "Fragment detached during second session check")
                                    return@checkSessionExpired
                                }

                                if (!stillExpired) {
                                    proceedWithAutoLogin()
                                } else {
                                    L.e("MoodleFragment", "Session still expired after refresh - skipping auto-login")
                                    sessionExpiredDetected = true
                                    lastSessionExpiredTime = System.currentTimeMillis()
                                }
                            }
                        }
                    }
                }, 1000)
                return@checkSessionExpired
            }

            proceedWithAutoLogin()
        }
    }

    private fun proceedWithAutoLogin() {
        if (!isAdded || context == null) {
            L.d("MoodleFragment", "Fragment not attached, aborting auto-login")
            return
        }

        isConfirmDialogPage { isConfirmDialog ->
            if (!isAdded || context == null) {
                L.d("MoodleFragment", "Fragment detached during confirm check")
                return@isConfirmDialogPage
            }

            if (isConfirmDialog) {
                return@isConfirmDialogPage
            }

            val timeSinceLastCheck = System.currentTimeMillis() - lastSuccessfulLoginCheck
            if (loginSuccessConfirmed && timeSinceLastCheck < 30000) {
                return@isConfirmDialogPage
            }

            isUserLoggedIn { isLoggedIn ->
                if (!isAdded || context == null) {
                    L.d("MoodleFragment", "Fragment detached during logged-in check")
                    return@isUserLoggedIn
                }

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

                val dontShowDialog = sharedPrefs.getBoolean("moodle_dont_show_login_dialog", false)
                val hasCredentials = encryptedPrefs.contains("moodle_username") &&
                        encryptedPrefs.contains("moodle_password")

                if (hasCredentials && !dontShowDialog && !isLoginDialogShown && !sessionExpiredDetected) {
                    performAutoLogin()
                } else if (!hasCredentials && !dontShowDialog && !isLoginDialogShown) {
                    val delay = if (loginRetryCount > 0) {
                        loginRetryCount * 400L
                    } else {
                        0L
                    }

                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!loginSuccessConfirmed && !isLoginDialogShown && !sessionExpiredDetected && isFragmentActive && isAdded && context != null) {
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
                    var errorText = errorDiv.textContent.trim();
                    return errorText;
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
                callback(cleanResult.ifEmpty { null })
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

    private fun showLoginDialog() {
        if (isLoginDialogShown) return

        if (!isNetworkAvailable()) {
            Toast.makeText(requireContext(), getString(R.string.moodle_offline_error), Toast.LENGTH_LONG).show()
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
        val btnTogglePassword = dialogView.findViewById<ImageButton>(R.id.btnTogglePasswordVisibility)

        val monoFont = Typeface.MONOSPACE
        etUsername.typeface = monoFont
        etPassword.typeface = monoFont

        cbSaveCredentials.isChecked = encryptedPrefs.contains("moodle_username")
        cbDontShowAgain.isChecked = false

        if (consecutiveLoginFailures >= MAX_LOGIN_ATTEMPTS) {
            cbSaveCredentials.isChecked = false
            cbDontShowAgain.isChecked = false
        }

        var isPasswordVisible = false
        btnTogglePassword.setOnClickListener {
            isPasswordVisible = !isPasswordVisible
            if (isPasswordVisible) {
                etPassword.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                btnTogglePassword.setImageResource(R.drawable.ic_eye_open)
            } else {
                etPassword.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                btnTogglePassword.setImageResource(R.drawable.ic_eye_closed)
            }
            etPassword.typeface = monoFont
            etPassword.setSelection(etPassword.text.length)
        }

        checkLoginError { errorMessage ->
            if (errorMessage != null) {
                tvErrorMessage.visibility = View.VISIBLE
                tvErrorMessage.text = errorMessage
            } else if (hasLoginFailed) {
                tvErrorMessage.visibility = View.VISIBLE
                tvErrorMessage.text = getString(R.string.moodle_login_failed)
            } else {
                tvErrorMessage.visibility = View.GONE
            }

            extractUsernameFromForm { extractedUsername ->
                val savedUsername = if (encryptedPrefs.contains("moodle_username")) {
                    encryptedPrefs.getString("moodle_username", "") ?: ""
                } else ""

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
                    .setView(dialogView)
                    .setPositiveButton(getString(R.string.moodle_continue)) { _, _ ->
                        val username = etUsername.text.toString()
                        val password = etPassword.text.toString()

                        consecutiveLoginFailures = 0
                        hasLoginFailed = false

                        if (cbSaveCredentials.isChecked && username.isNotEmpty() && password.isNotEmpty()) {
                            saveCredentials(username, password)
                            L.d("MoodleFragment", "Credentials saved by user choice")
                        } else {
                            encryptedPrefs.edit {
                                clear()
                                apply()
                            }
                            L.d("MoodleFragment", "Credentials not saved or cleared")
                        }

                        if (cbDontShowAgain.isChecked) {
                            sharedPrefs.edit {
                                putBoolean("moodle_dont_show_login_dialog", true)
                                apply()
                            }
                        } else {
                            sharedPrefs.edit {
                                remove("moodle_dont_show_login_dialog")
                                apply()
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
            }
        }
    }

    private fun saveCredentials(username: String, password: String) {
        if (username.isEmpty() || password.isEmpty()) {
            L.e("MoodleFragment", "Cannot save empty credentials")
            return
        }

        L.d("MoodleFragment", "Saving credentials - Username: $username (length: ${username.length}), Password length: ${password.length}")

        encryptedPrefs.edit {
            putString("moodle_username", username)
            putString("moodle_password", password)
            apply()
        }

        val savedUsername = encryptedPrefs.getString("moodle_username", "")
        val savedPassword = encryptedPrefs.getString("moodle_password", "")

        if (savedUsername == username && savedPassword == password) {
            L.d("MoodleFragment", "Credentials saved and verified successfully")
        } else {
            L.e("MoodleFragment", "Credential verification failed!")
            L.e("MoodleFragment", "Expected username: $username, Got: $savedUsername")
            L.e("MoodleFragment", "Password match: ${savedPassword == password}")
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

        if (isLoginDialogShown) {
            L.d("MoodleFragment", "Login dialog already shown, preventing duplicate")
            return
        }

        if (sessionExpiredDetected && System.currentTimeMillis() - lastSessionExpiredTime < SESSION_RETRY_DELAY) {
            L.d("MoodleFragment", "Skipping login - session expired cooldown active")
            if (isAdded && context != null) {
                Toast.makeText(requireContext(), getString(R.string.moodle_session_expired_wait), Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (consecutiveLoginFailures >= MAX_LOGIN_ATTEMPTS) {
            L.d("MoodleFragment", "Max failures reached - not attempting login")
            return
        }

        if (isHandlingLogout) {
            L.d("MoodleFragment", "Currently handling logout - not attempting login")
            return
        }

        isLoginInProgress = true
        loginAttemptCount++
        L.d("MoodleFragment", "Starting login attempt #$loginAttemptCount")
        L.d("MoodleFragment", "Username: $username (length: ${username.length})")
        L.d("MoodleFragment", "Password length: ${password.length}")

        fun escapeForJS(str: String): String {
            return str
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("`", "\\`")
                .replace("$", "\\$")
                .replace("\b", "\\b")
                .replace("\u000C", "\\f")
        }

        val escapedUsername = escapeForJS(username)
        val escapedPassword = escapeForJS(password)

        L.d("MoodleFragment", "Escaped username: $escapedUsername")
        L.d("MoodleFragment", "Escaped password length: ${escapedPassword.length}")

        val jsCode = """
        (function() {
            try {
                console.log('=== Login Form Fill (Using Dialog Method) ===');
                console.log('Looking for login form elements...');
                
                var usernameField = document.evaluate('/html/body/div[2]/div[2]/div/div/div/div/div/div/div/form/div[1]/input', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
                var passwordField = document.evaluate('/html/body/div[2]/div[2]/div/div/div/div/div/div/div/form/div[2]/div/input', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
                var submitButton = document.evaluate('/html/body/div[2]/div[2]/div/div/div/div/div/div/div/form/div[3]/button', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;

                if (!usernameField) {
                    console.log('Username field not found via XPath, trying alternatives...');
                    usernameField = document.querySelector('input[name="username"], input[type="text"]');
                }
                if (!passwordField) {
                    console.log('Password field not found via XPath, trying alternatives...');
                    passwordField = document.querySelector('input[name="password"], input[type="password"]');
                }
                if (!submitButton) {
                    console.log('Submit button not found via XPath, trying alternatives...');
                    submitButton = document.querySelector('input[type="submit"], button[type="submit"]');
                }
                
                if (usernameField && passwordField && submitButton) {
                    console.log('All form elements found');

                    // disable all event listeners temporarily (important!)
                    var originalAddEventListener = EventTarget.prototype.addEventListener;
                    var eventListenersDisabled = true;
                    EventTarget.prototype.addEventListener = function() {
                        if (eventListenersDisabled) {
                            console.log('Blocked event listener during fill');
                            return;
                        }
                        return originalAddEventListener.apply(this, arguments);
                    };

                    var nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;

                    // clear inputs
                    nativeInputValueSetter.call(usernameField, '');
                    nativeInputValueSetter.call(passwordField, '');
                    
                    console.log('Fields cleared');

                    return new Promise(function(resolve) {
                        setTimeout(function() {
                            // set username
                            nativeInputValueSetter.call(usernameField, '$escapedUsername');
                            console.log('Username set: ' + usernameField.value);

                            setTimeout(function() {
                                // set password
                                nativeInputValueSetter.call(passwordField, '$escapedPassword');
                                console.log('Password set, length: ' + passwordField.value.length);

                                // trigger input events
                                usernameField.dispatchEvent(new Event('input', { bubbles: true }));
                                usernameField.dispatchEvent(new Event('change', { bubbles: true }));
                                
                                setTimeout(function() {
                                    passwordField.dispatchEvent(new Event('input', { bubbles: true }));
                                    passwordField.dispatchEvent(new Event('change', { bubbles: true }));

                                    setTimeout(function() {
                                        console.log('Pre-submit verification:');
                                        console.log('Username: "' + usernameField.value + '"');
                                        console.log('Password length: ' + passwordField.value.length);
                                        console.log('Expected: username="$escapedUsername", password length=${escapedPassword.length}');
                                        
                                        if (usernameField.value === '$escapedUsername' && passwordField.value.length === ${escapedPassword.length}) {
                                            console.log('✓ Values verified - submitting');
                                            
                                            // re-enable event listeners before submit
                                            setTimeout(function() {
                                                eventListenersDisabled = false;
                                                EventTarget.prototype.addEventListener = originalAddEventListener;
                                            }, 400);
                                            
                                            submitButton.click();
                                            resolve('submitted');
                                        } else {
                                            console.error('✗ Verification failed!');
                                            console.error('Username match: ' + (usernameField.value === '$escapedUsername'));
                                            console.error('Password length match: ' + (passwordField.value.length === ${escapedPassword.length}));
                                            
                                            // retry once with direct input
                                            nativeInputValueSetter.call(usernameField, '$escapedUsername');
                                            nativeInputValueSetter.call(passwordField, '$escapedPassword');
                                            
                                            setTimeout(function() {
                                                console.log('After retry:');
                                                console.log('Username: ' + usernameField.value);
                                                console.log('Password length: ' + passwordField.value.length);
                                                
                                                // re-enable event listeners
                                                setTimeout(function() {
                                                    eventListenersDisabled = false;
                                                    EventTarget.prototype.addEventListener = originalAddEventListener;
                                                }, 400);
                                                
                                                submitButton.click();
                                                resolve('submitted_after_retry');
                                            }, 200);
                                        }
                                    }, 300);
                                }, 200);
                            }, 200);
                        }, 100);
                    });
                } else {
                    console.error('Form elements missing:');
                    console.error('Username field: ' + (usernameField ? 'found' : 'NOT FOUND'));
                    console.error('Password field: ' + (passwordField ? 'found' : 'NOT FOUND'));
                    console.error('Submit button: ' + (submitButton ? 'found' : 'NOT FOUND'));
                    return 'elements_not_found';
                }
            } catch(e) {
                console.error('Exception in fillLoginForm: ' + e.message);
                console.error('Stack: ' + e.stack);
                return 'error';
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            L.d("MoodleFragment", "Login form JS evaluation result: $result")

            Handler(Looper.getMainLooper()).postDelayed({
                if (!isAdded || context == null) {
                    L.d("MoodleFragment", "Fragment detached, aborting login")
                    isLoginInProgress = false
                    return@postDelayed
                }

                if (result?.contains("elements_not_found") == true || result?.contains("error") == true) {
                    isLoginInProgress = false
                    L.e("MoodleFragment", "Failed to fill login form - elements not found")
                    context?.let {
                        Toast.makeText(it, getString(R.string.moodle_login_form_not_found), Toast.LENGTH_SHORT).show()
                    }
                    isLoginDialogShown = false
                } else {
                    L.d("MoodleFragment", "Login form submitted, waiting for response...")
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (isAdded && context != null) {
                            checkLoginResult()
                        } else {
                            L.d("MoodleFragment", "Fragment detached during login, cleaning up")
                            isLoginInProgress = false
                        }
                    }, 1000)
                }
            }, 100)
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
                            if (isFragmentActive && isAdded && !isLoginDialogShown) {
                                val dontShowDialog = sharedPrefs.getBoolean("moodle_dont_show_login_dialog", false)
                                if (!dontShowDialog) {
                                    injectDialogButtonOnLoginPage()
                                }
                                showLoginDialog()
                            }
                        }, 500)

                    } else if (isSessionExpired) {
                        if (sessionExpiredRetryCount == 0) {
                            L.d("MoodleFragment", "Session expired - refreshing page and retrying")
                            sessionExpiredRetryCount = 1
                            isLoginInProgress = false

                            webView.loadUrl(loginUrl)

                            Handler(Looper.getMainLooper()).postDelayed({
                                waitForPageReady { pageReady ->
                                    if (pageReady) {
                                        val username = encryptedPrefs.getString("moodle_username", "") ?: ""
                                        val password = encryptedPrefs.getString("moodle_password", "") ?: ""
                                        if (username.isNotEmpty() && password.isNotEmpty()) {
                                            fillLoginForm(username, password)
                                        }
                                    }
                                }
                            }, 500)
                        } else {
                            L.e("MoodleFragment", "Session expired on retry - giving up for this session")
                            isLoginInProgress = false
                            loginAttemptCount = 0
                            sessionExpiredDetected = true
                            sessionExpiredRetryCount = 0
                            lastSessionExpiredTime = System.currentTimeMillis()
                            hasLoginFailed = false
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

                        Handler(Looper.getMainLooper()).postDelayed({
                            if (isFragmentActive && isAdded) {
                                val dontShowDialog = sharedPrefs.getBoolean("moodle_dont_show_login_dialog", false)
                                if (!dontShowDialog) {
                                    injectDialogButtonOnLoginPage()
                                }
                            }
                        }, 500)
                    }
                } else {
                    L.w("MoodleFragment", "No error message found, login status unclear")
                    isLoginInProgress = false
                    sessionExpiredRetryCount = 0
                    hasLoginFailed = true
                    isLoginDialogShown = false

                    Handler(Looper.getMainLooper()).postDelayed({
                        if (isFragmentActive && isAdded) {
                            val dontShowDialog = sharedPrefs.getBoolean("moodle_dont_show_login_dialog", false)
                            if (!dontShowDialog) {
                                injectDialogButtonOnLoginPage()
                            }
                        }
                    }, 500)
                }
            }
        }
    }

    private fun loadMoodleDarkModePreference() {
        val moodleFollowsAppTheme = sharedPrefs.getBoolean("moodle_follow_app_theme", true)

        if (moodleFollowsAppTheme) {
            val followSystemTheme = sharedPrefs.getBoolean("follow_system_theme", true)
            moodleDarkModeEnabled = if (followSystemTheme) {
                getSystemDarkMode()
            } else {
                sharedPrefs.getBoolean("dark_mode_enabled", false)
            }
        } else {
            moodleDarkModeEnabled = sharedPrefs.getBoolean("moodle_dark_mode_enabled", false)
        }

        L.d("MoodleFragment", "Loaded Moodle dark mode preference: $moodleDarkModeEnabled")
    }

    //endregion

    //region **HELPERS**

    override fun onResume() {
        super.onResume()
        isFragmentActive = true
        setupSharedPreferences()
        performPeriodicCacheCleanup()

        updateCourseDownloadsCounter()

        CourseDownloadQueue.getInstance().addListener(object : CourseDownloadQueue.QueueListener {
            override fun onQueueChanged() {
                if (isFragmentActive) {
                    updateCourseDownloadsCounter()
                }
            }

            override fun onEntryStatusChanged(entry: CourseDownloadQueue.DownloadQueueEntry) {
                if (isFragmentActive && entry.status == "completed") {
                    updateCourseDownloadsCounter()
                }
            }
        })

        val currentUrl = webView.url ?: ""
        if (currentUrl.startsWith("https://moodle.kleyer.eu/course/view.php")) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (isFragmentActive && isAdded) {
                    injectCourseDownloadButton()
                }
            }, 500)
        }

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

    override fun onPause() {
        super.onPause()
        isFragmentActive = false
        stopDialogButtonMonitoring()
        stopMessageInputMonitoring()
        stopDownloadButtonMonitoring()
        messageInputDialog?.dismiss()

        if (wasOnLogoutConfirmPage) {
            L.d("MoodleFragment", "User left while on logout page - cancelling logout")
            wasOnLogoutConfirmPage = false
            logoutConfirmPageUrl = ""
        }

        if (isProcessingInstantLogout) {
            L.d("MoodleFragment", "User left during instant logout - resetting state")
            isProcessingInstantLogout = false
        }

        saveCurrentTabState()

        savePinnedTabs()

        if (isAppClosing) {
            resetDefaultTab()
        }

        L.d("MoodleFragment", "onPause - saved all tabs" + if (isAppClosing) " and reset default tab" else "")
    }

    override fun onDestroy() {
        super.onDestroy()
        isAppClosing = true

        stopDialogButtonMonitoring()
        stopMessageInputMonitoring()
        stopDownloadButtonMonitoring()
        messageInputDialog?.dismiss()
        wasOnLogoutConfirmPage = false
        logoutConfirmPageUrl = ""
        isHandlingLogout = false

        darkModeRetryHandler?.removeCallbacks(darkModeRetryRunnable ?: Runnable {})
        darkModeRetryHandler = null
        darkModeRetryRunnable = null

        pdfViewerManager?.closePdf()
        resetDefaultTabToLogin()
        savePinnedTabs()
        tabs.forEach { it.thumbnail?.recycle() }
        pdfRenderer?.close()
        backgroundExecutor.shutdown()
        cleanupRefreshIndicator()
        scrollEndCheckRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        scrollStopRunnable?.let { scrollStopHandler?.removeCallbacks(it) }
        cleanupFetchProcess()
        cleanupTemporaryPdfs()
        textViewerManager = null
        textSearchDialog?.dismiss()

        pdfSwipeIndicator?.let {
            (it.parent as? ViewGroup)?.removeView(it)
        }
        pdfSwipeIndicator = null
        pdfScaleDetector = null
        zoomStabilizeRunnable?.let { zoomHandler?.removeCallbacks(it) }
        zoomHandler = null

        L.d("MoodleFragment", "onDestroy - app is closing")
    }

    override fun onDestroyView() {
        super.onDestroyView()

        pdfScrollContainer.setOnScrollChangeListener(null)
        pdfScrollContainer.viewTreeObserver.removeOnScrollChangedListener(fun() {
            // throwing some warnings i dont understand
        })

        scrollStopRunnable?.let { scrollStopHandler?.removeCallbacks(it) }
        scrollStopHandler = null
        scrollStopRunnable = null
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

    private fun detectCurrentTheme() {
        val sharedPreferences = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val followSystemTheme = sharedPreferences.getBoolean("follow_system_theme", true)

        isDarkTheme = if (followSystemTheme) {
            val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            nightMode == Configuration.UI_MODE_NIGHT_YES
        } else {
            sharedPreferences.getBoolean("dark_mode_enabled", false)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            if (!isAdded || context == null) {
                L.d("MoodleFragment", "Fragment not attached, cannot check network")
                return false
            }

            val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            L.e("MoodleFragment", "Error checking network availability", e)
            false
        }
    }

    //endregion

    //region **LOGIN**

    private fun checkIfSessionTerminationDialog(callback: (Boolean) -> Unit) {
        val jsCode = """
        (function() {
            try {
                console.log('=== Checking for session termination dialog ===');
                console.log('Current URL: ' + window.location.href);
                console.log('Document ready state: ' + document.readyState);
                console.log('Document title: ' + document.title);

                if (!window.location.pathname.includes('/login/index.php')) {
                    console.log('Not on login page path: ' + window.location.pathname);
                    return JSON.stringify({ isDialog: false, reason: 'wrong_path' });
                }

                if (document.readyState !== 'complete') {
                    console.log('Document not fully loaded yet, state: ' + document.readyState);
                    return JSON.stringify({ isDialog: false, reason: 'not_ready' });
                }

                var bodyContent = document.body ? document.body.innerHTML.length : 0;
                console.log('Body content length: ' + bodyContent);
                
                if (bodyContent < 100) {
                    console.log('Body content too small, page may not be loaded');
                    return JSON.stringify({ isDialog: false, reason: 'content_too_small' });
                }

                var confirmHeader = document.evaluate(
                    '/html/body/div[2]/div[2]/div/div/div/div/div/div/div/div/div/div[1]/h4',
                    document,
                    null,
                    XPathResult.FIRST_ORDERED_NODE_TYPE,
                    null
                ).singleNodeValue;
                
                if (!confirmHeader) {
                    console.log('No h4 header found - likely login page');

                    var loginForm = document.querySelector('form[action*="login"]');
                    var usernameField = document.querySelector('input[name="username"]');
                    
                    if (loginForm || usernameField) {
                        console.log('Found login form elements - confirmed login page');
                        return JSON.stringify({ isDialog: false, reason: 'login_form_found' });
                    }
                    
                    return JSON.stringify({ isDialog: false, reason: 'no_header' });
                }
                
                var headerText = confirmHeader.textContent.trim();
                console.log('Found header with text: "' + headerText + '"');
                console.log('Header HTML: ' + confirmHeader.outerHTML);
                
                if (headerText === 'Bestätigen' || headerText === 'Confirm') {
                    console.log('✓ This is the session termination dialog');

                    var cancelButton = document.evaluate(
                        '/html/body/div[2]/div[2]/div/div/div/div/div/div/div/div/div/div[3]/div/div[1]/form/button',
                        document,
                        null,
                        XPathResult.FIRST_ORDERED_NODE_TYPE,
                        null
                    ).singleNodeValue;
                    
                    if (cancelButton) {
                        console.log('Cancel button found: "' + cancelButton.textContent.trim() + '"');
                        console.log('Button HTML: ' + cancelButton.outerHTML.substring(0, 100));
                        return JSON.stringify({ 
                            isDialog: true, 
                            reason: 'dialog_detected',
                            buttonText: cancelButton.textContent.trim()
                        });
                    } else {
                        console.log('Header matches but cancel button not found!');
                        console.log('Looking for button with alternative methods...');

                        var allButtons = document.querySelectorAll('button');
                        console.log('Found ' + allButtons.length + ' buttons on page');
                        
                        return JSON.stringify({ 
                            isDialog: true, 
                            reason: 'dialog_no_button',
                            buttonText: null,
                            totalButtons: allButtons.length
                        });
                    }
                }
                
                console.log('Header found but text does not match - this is login page');
                console.log('Expected: "Bestätigen" or "Confirm", Got: "' + headerText + '"');
                return JSON.stringify({ 
                    isDialog: false, 
                    reason: 'header_mismatch',
                    foundText: headerText
                });
                
            } catch(e) {
                console.error('Error checking for termination dialog: ' + e.message);
                console.error('Stack: ' + e.stack);
                return JSON.stringify({ 
                    isDialog: false, 
                    reason: 'error',
                    error: e.message,
                    stack: e.stack
                });
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            try {
                val cleanResult = result.replace("\\\"", "\"").trim('"')
                L.d("MoodleFragment", "Session dialog check raw result: $cleanResult")

                val jsonResult = JSONObject(cleanResult)
                val isDialog = jsonResult.optBoolean("isDialog", false)
                val reason = jsonResult.optString("reason", "unknown")

                L.d("MoodleFragment", "Session dialog check - isDialog: $isDialog, reason: $reason")

                when (reason) {
                    "error" -> {
                        val error = jsonResult.optString("error", "")
                        val stack = jsonResult.optString("stack", "")
                        L.e("MoodleFragment", "JS Error during dialog check: $error")
                        L.e("MoodleFragment", "Stack: $stack")
                    }
                    "header_mismatch" -> {
                        val foundText = jsonResult.optString("foundText", "")
                        L.d("MoodleFragment", "Header text mismatch. Found: '$foundText'")
                    }
                    "dialog_no_button" -> {
                        val totalButtons = jsonResult.optInt("totalButtons", 0)
                        L.e("MoodleFragment", "Dialog detected but cancel button not found! Total buttons on page: $totalButtons")
                    }
                    "dialog_detected" -> {
                        val buttonText = jsonResult.optString("buttonText", "")
                        L.d("MoodleFragment", "Dialog confirmed with button text: '$buttonText'")
                    }
                    "not_ready" -> {
                        L.d("MoodleFragment", "Document not ready yet, will retry")
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (isFragmentActive && isAdded) {
                                checkIfSessionTerminationDialog(callback)
                            }
                        }, 500)
                        return@evaluateJavascript
                    }
                    "content_too_small" -> {
                        L.d("MoodleFragment", "Page content too small, will retry")
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (isFragmentActive && isAdded) {
                                checkIfSessionTerminationDialog(callback)
                            }
                        }, 500)
                        return@evaluateJavascript
                    }
                }

                callback(isDialog)
            } catch (e: Exception) {
                L.e("MoodleFragment", "Error parsing dialog check result: $result", e)
                callback(false)
            }
        }
    }

    private fun dismissSessionTerminationDialog() {
        val shouldAutoDismiss = sharedPrefs.getBoolean("moodle_auto_dismiss_confirm", true)

        if (!shouldAutoDismiss) {
            L.d("MoodleFragment", "Auto-dismiss disabled by user")
            isLoginDialogShown = true
            return
        }

        L.d("MoodleFragment", "Attempting to dismiss session termination dialog...")

        val jsCode = """
        (function() {
            try {
                console.log('=== Dismissing session termination dialog ===');
                console.log('Current URL: ' + window.location.href);
                console.log('Document ready state: ' + document.readyState);

                var cancelButton = document.evaluate(
                    '/html/body/div[2]/div[2]/div/div/div/div/div/div/div/div/div/div[3]/div/div[1]/form/button',
                    document,
                    null,
                    XPathResult.FIRST_ORDERED_NODE_TYPE,
                    null
                ).singleNodeValue;
                
                if (!cancelButton) {
                    console.error('✗ Cancel button not found');
                    console.log('Trying alternative selectors...');

                    var alternatives = [
                        'button[data-action="cancel"]',
                        'form button[type="button"]',
                        '.modal-footer button:first-child'
                    ];
                    
                    for (var i = 0; i < alternatives.length; i++) {
                        cancelButton = document.querySelector(alternatives[i]);
                        if (cancelButton) {
                            console.log('Found button with selector: ' + alternatives[i]);
                            break;
                        }
                    }
                    
                    if (!cancelButton) {
                        return JSON.stringify({ 
                            success: false, 
                            reason: 'button_not_found',
                            triedSelectors: alternatives.length 
                        });
                    }
                }
                
                console.log('Found cancel button: ' + cancelButton.textContent.trim());
                console.log('Button type: ' + cancelButton.type);
                console.log('Button disabled: ' + cancelButton.disabled);
                console.log('Button visible: ' + (cancelButton.offsetParent !== null));
                
                if (cancelButton.disabled) {
                    console.warn('Button is disabled!');
                    return JSON.stringify({ 
                        success: false, 
                        reason: 'button_disabled' 
                    });
                }
                
                if (cancelButton.offsetParent === null) {
                    console.warn('Button is not visible!');
                    return JSON.stringify({ 
                        success: false, 
                        reason: 'button_not_visible' 
                    });
                }

                console.log('Clicking cancel button...');
                cancelButton.click();

                setTimeout(function() {
                    var stillVisible = document.evaluate(
                        '/html/body/div[2]/div[2]/div/div/div/div/div/div/div/div/div/div[1]/h4',
                        document,
                        null,
                        XPathResult.FIRST_ORDERED_NODE_TYPE,
                        null
                    ).singleNodeValue;
                    
                    if (stillVisible) {
                        console.log('Dialog still visible after click - may need page reload');
                    } else {
                        console.log('Dialog dismissed successfully');
                    }
                }, 500);
                
                console.log('✓ Cancel button clicked');
                return JSON.stringify({ 
                    success: true, 
                    buttonText: cancelButton.textContent.trim() 
                });
                
            } catch(e) {
                console.error('Error dismissing dialog: ' + e.message);
                console.error('Stack: ' + e.stack);
                return JSON.stringify({ 
                    success: false, 
                    reason: 'exception',
                    error: e.message 
                });
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            if (!isFragmentActive || !isAdded) {
                L.d("MoodleFragment", "Fragment not active, skipping dialog handling")
                return@evaluateJavascript
            }

            try {
                val cleanResult = result.replace("\\\"", "\"").trim('"')
                L.d("MoodleFragment", "Dismiss dialog raw result: $cleanResult")

                val jsonResult = JSONObject(cleanResult)
                val success = jsonResult.optBoolean("success", false)
                val reason = jsonResult.optString("reason", "")

                if (success) {
                    val buttonText = jsonResult.optString("buttonText", "")
                    L.d("MoodleFragment", "Session termination dialog dismissed successfully (button: '$buttonText')")
                    isLoginDialogShown = true

                    context?.let {
                        Toast.makeText(it, getString(R.string.moodle_session_term_cancel), Toast.LENGTH_SHORT).show()
                    }

                    Handler(Looper.getMainLooper()).postDelayed({
                        verifyDialogDismissed()
                    }, 1000)

                } else {
                    L.e("MoodleFragment", "Failed to dismiss session termination dialog: $reason")

                    when (reason) {
                        "button_not_found" -> {
                            L.e("MoodleFragment", "Cancel button not found with any selector")
                            webView.reload()
                        }
                        "button_disabled" -> {
                            L.e("MoodleFragment", "Cancel button is disabled")
                        }
                        "button_not_visible" -> {
                            L.e("MoodleFragment", "Cancel button is not visible")
                        }
                        "exception" -> {
                            val error = jsonResult.optString("error", "")
                            L.e("MoodleFragment", "Exception during dismissal: $error")
                        }
                    }
                }
            } catch (e: Exception) {
                L.e("MoodleFragment", "Error parsing dismiss result: $result", e)
            }
        }
    }

    private fun verifyDialogDismissed() {
        if (!isFragmentActive || !isAdded) return

        checkIfSessionTerminationDialog { stillPresent ->
            if (stillPresent) {
                L.w("MoodleFragment", "Dialog still present after dismissal attempt - trying again")
                dismissSessionTerminationDialog()
            } else {
                L.d("MoodleFragment", "Dialog successfully dismissed and verified")
            }
        }
    }

    private fun waitForPageReady(maxAttempts: Int = 20, attempt: Int = 0, callback: (Boolean) -> Unit) {
        if (attempt >= maxAttempts) {
            L.w("MoodleFragment", "Page ready timeout after $maxAttempts attempts")
            callback(false)
            return
        }

        val jsCode = """
        (function() {
            try {
                if (document.readyState !== 'complete') {
                    console.log('Document not complete, state: ' + document.readyState);
                    return false;
                }

                if (!document.querySelector('body')) {
                    console.log('Body element not found');
                    return false;
                }

                var currentPath = window.location.pathname;
                if (currentPath.includes('/login/')) {
                    var usernameField = document.evaluate(
                        '/html/body/div[2]/div[2]/div/div/div/div/div/div/div/form/div[1]/input',
                        document,
                        null,
                        XPathResult.FIRST_ORDERED_NODE_TYPE,
                        null
                    ).singleNodeValue;
                    
                    if (!usernameField) {
                        console.log('Login form not yet ready');
                        return false;
                    }
                }
                
                console.log('Page is ready');
                return true;
            } catch(e) {
                console.error('Error checking page ready: ' + e.message);
                return false;
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            if (result == "true") {
                L.d("MoodleFragment", "Page ready after ${attempt + 1} attempts")
                callback(true)
            } else {
                L.d("MoodleFragment", "Page not ready, attempt ${attempt + 1}/$maxAttempts")
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

    private fun stopDialogButtonMonitoring() {
        isMonitoringDialogButton = false
        dialogButtonMonitorRunnable?.let {
            dialogButtonMonitorHandler?.removeCallbacks(it)
        }
        dialogButtonMonitorHandler = null
        dialogButtonMonitorRunnable = null
        L.d("MoodleFragment", "Stopped dialog button monitoring")
    }

    private fun handleLogout() {
        if (isHandlingLogout && !isProcessingInstantLogout) {
            L.d("MoodleFragment", "Already handling logout, skipping duplicate call")
            return
        }

        isHandlingLogout = true
        isProcessingInstantLogout = false
        L.d("MoodleFragment", "Handling logout - clearing all login data")

        Handler(Looper.getMainLooper()).removeCallbacksAndMessages(null)
        stopDialogButtonMonitoring()

        isLoginDialogShown = false
        hasLoginFailed = false
        loginRetryCount = 0
        loginSuccessConfirmed = false
        lastSuccessfulLoginCheck = 0L
        consecutiveLoginFailures = 0
        isLoginInProgress = false
        loginAttemptCount = 0
        sessionExpiredDetected = false
        sessionExpiredRetryCount = 0
        lastSessionExpiredTime = 0L
        wasOnLoginPage = false

        encryptedPrefs.edit {
            clear()
            apply()
        }
        L.d("MoodleFragment", "Cleared encrypted credentials")

        sharedPrefs.edit {
            remove("moodle_dont_show_login_dialog")
            apply()
        }
        L.d("MoodleFragment", "Cleared login-related preferences")

        CookieManager.getInstance().apply {
            removeAllCookies { success ->
                L.d("MoodleFragment", "Cleared all cookies: $success")
            }
            removeSessionCookies { success ->
                L.d("MoodleFragment", "Cleared session cookies: $success")
            }
            flush()
        }

        webView.clearHistory()
        webView.clearFormData()
        WebStorage.getInstance().deleteAllData()

        webView.loadUrl(loginUrl)

        L.d("MoodleFragment", "Logout complete - all login data cleared")

        Handler(Looper.getMainLooper()).postDelayed({
            isHandlingLogout = false
            wasOnLoginPage = true

            Handler(Looper.getMainLooper()).postDelayed({
                if (isFragmentActive && isAdded) {
                    val currentUrl = webView.url ?: ""
                    if (currentUrl == loginUrl || currentUrl.contains("login/index.php")) {
                        L.d("MoodleFragment", "On login page after logout - checking for dialog setup")

                        waitForPageReady(maxAttempts = 30) { pageReady ->
                            if (pageReady && isFragmentActive && isAdded) {
                                val dontShowDialog = sharedPrefs.getBoolean("moodle_dont_show_login_dialog", false)

                                if (!dontShowDialog) {
                                    L.d("MoodleFragment", "Injecting dialog button after logout")
                                    injectDialogButtonOnLoginPage()
                                } else {
                                    L.d("MoodleFragment", "Dialog disabled by user preference")
                                }
                            }
                        }
                    }
                }
            }, 800)
        }, 1000)
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

    private fun injectDialogButtonOnLoginPage() {
        if (!isFragmentActive || !isAdded) {
            L.d("MoodleFragment", "Fragment not active, skipping button injection")
            return
        }

        stopDialogButtonMonitoring()

        val jsCode = """
        (function() {
            try {
                var loginHeading = document.evaluate(
                    '/html/body/div[2]/div[2]/div/div/div/div/div/div/div/h1',
                    document,
                    null,
                    XPathResult.FIRST_ORDERED_NODE_TYPE,
                    null
                ).singleNodeValue;
                
                if (!loginHeading || !loginHeading.textContent.toLowerCase().includes('login')) {
                    return false;
                }

                var confirmHeader = document.evaluate(
                    '/html/body/div[2]/div[2]/div/div/div/div/div/div/div/div/div/div[1]/h4',
                    document,
                    null,
                    XPathResult.FIRST_ORDERED_NODE_TYPE,
                    null
                ).singleNodeValue;
                
                if (confirmHeader && (confirmHeader.textContent === 'Bestätigen' || confirmHeader.textContent === 'Confirm')) {
                    return false;
                }

                var existingButtons = document.querySelectorAll('#logindialogbtn');
                existingButtons.forEach(function(btn) { btn.remove(); });

                var submitContainer = document.evaluate(
                    '/html/body/div[2]/div[2]/div/div/div/div/div/div/div/form/div[3]',
                    document,
                    null,
                    XPathResult.FIRST_ORDERED_NODE_TYPE,
                    null
                ).singleNodeValue;
                
                if (!submitContainer) {
                    return false;
                }

                var dialogButton = document.createElement('button');
                dialogButton.id = 'logindialogbtn';
                dialogButton.type = 'button';
                dialogButton.className = 'btn btn-secondary btn-lg';
                dialogButton.textContent = '${getString(R.string.moodle_show_dialog)}';
                dialogButton.style.marginLeft = '8px';
                dialogButton.style.zIndex = '9999';

                dialogButton.onclick = function(e) {
                    e.preventDefault();
                    e.stopPropagation();
                    window.showLoginDialogFromApp = true;
                    console.log('Dialog button clicked');
                    return false;
                };

                submitContainer.appendChild(dialogButton);
                console.log('Dialog button injected successfully');
                
                return true;
            } catch(e) {
                console.error('Error injecting button: ' + e.message);
                return false;
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            if (!isFragmentActive || !isAdded) {
                L.d("MoodleFragment", "Fragment detached during button injection")
                return@evaluateJavascript
            }

            if (result == "true") {
                L.d("MoodleFragment", "Dialog button injected successfully")
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isFragmentActive && isAdded) {
                        monitorDialogButtonClick()
                    }
                }, 100)
            } else {
                L.d("MoodleFragment", "Could not inject dialog button (might not be on login page)")
            }
        }
    }

    private fun monitorDialogButtonClick() {
        if (!isFragmentActive || !isAdded) {
            L.d("MoodleFragment", "Fragment not active, stopping dialog button monitoring")
            return
        }

        stopDialogButtonMonitoring()

        isMonitoringDialogButton = true
        val checkInterval = 300L
        dialogButtonMonitorHandler = Handler(Looper.getMainLooper())

        dialogButtonMonitorRunnable = object : Runnable {
            override fun run() {
                if (!isFragmentActive || !isAdded || !isMonitoringDialogButton) {
                    L.d("MoodleFragment", "Fragment detached or monitoring stopped")
                    stopDialogButtonMonitoring()
                    return
                }

                val currentUrl = webView.url ?: ""

                if (!currentUrl.contains("login/index.php")) {
                    L.d("MoodleFragment", "No longer on login page, stopping monitoring")
                    stopDialogButtonMonitoring()
                    return
                }

                val jsCode = """
                (function() {
                    try {
                        var confirmHeader = document.evaluate(
                            '/html/body/div[2]/div[2]/div/div/div/div/div/div/div/div/div/div[1]/h4',
                            document,
                            null,
                            XPathResult.FIRST_ORDERED_NODE_TYPE,
                            null
                        ).singleNodeValue;
                        
                        if (confirmHeader && (confirmHeader.textContent === 'Bestätigen' || confirmHeader.textContent === 'Confirm')) {
                            return 'terminate';
                        }
                        
                        if (window.showLoginDialogFromApp === true) {
                            window.showLoginDialogFromApp = false;
                            return true;
                        }
                        
                        var button = document.getElementById('logindialogbtn');
                        if (!button) {
                            return 'reinject';
                        }
                        
                        return false;
                    } catch(e) {
                        return false;
                    }
                })();
            """.trimIndent()

                webView.evaluateJavascript(jsCode) { result ->
                    if (!isFragmentActive || !isAdded || !isMonitoringDialogButton) {
                        L.d("MoodleFragment", "Fragment detached during evaluation")
                        stopDialogButtonMonitoring()
                        return@evaluateJavascript
                    }

                    when (result.trim('"')) {
                        "true" -> {
                            L.d("MoodleFragment", "Dialog button clicked - showing login dialog")
                            activity?.runOnUiThread {
                                if (isFragmentActive && isAdded && !isLoginDialogShown) {
                                    showLoginDialog()
                                }
                            }
                            dialogButtonMonitorHandler?.postDelayed(this, checkInterval)
                        }
                        "reinject" -> {
                            L.d("MoodleFragment", "Button missing, re-injecting")
                            if (isFragmentActive && isAdded) {
                                injectDialogButtonOnLoginPage()
                            }
                            dialogButtonMonitorHandler?.postDelayed(this, checkInterval)
                        }
                        "terminate" -> {
                            L.d("MoodleFragment", "On termination page, stopping monitoring")
                            stopDialogButtonMonitoring()
                        }
                        else -> {
                            dialogButtonMonitorHandler?.postDelayed(this, checkInterval)
                        }
                    }
                }
            }
        }

        dialogButtonMonitorHandler?.post(dialogButtonMonitorRunnable!!)
    }

    //endregion

    //region **TABS**

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
            textViewerContainer.visibility = View.GONE
            webControlsLayout.visibility = View.VISIBLE
            pdfControlsLayout.visibility = View.GONE
            btnBack.visibility = View.VISIBLE
            btnOpenInBrowser.visibility = View.VISIBLE

            showLoadingBar()
            webView.loadUrl(url)
        } else {
            loadUrlInBackground(url)
        }

        if (url.contains("/message/index.php")) {
            Handler(Looper.getMainLooper()).postDelayed({
                clickMessageDrawerToggle()
            }, 800)
        }
    }

    private fun updateTabButtonIcon() {
        btnForward.setImageResource(
            if (isTabViewVisible) R.drawable.ic_tabs_filled else R.drawable.ic_tabs
        )
    }

    private fun ensureCleanDefaultTab() {
        if (tabs.isNotEmpty() && tabs[0].isDefault) {
            val defaultTab = tabs[0]

            if (defaultTab.webViewState == null && defaultTab.url == loginUrl) {
                L.d("MoodleFragment", "Initializing clean default tab")

                tabs[0] = defaultTab.copy(
                    webViewState = null,
                    thumbnail = null
                )

                if (currentTabIndex == 0) {
                    webView.clearHistory()
                    webView.clearCache(false)
                }
            } else {
                L.d("MoodleFragment", "Default tab has state, preserving it")
            }
        }
    }

    private fun initializeTabSystem() {
        isCompactTabLayout = sharedPrefs.getBoolean("moodle_tab_layout_compact", false)

        tabs.clear()

        val defaultTab = TabInfo(
            id = "default_tab",
            url = loginUrl,
            title = "Moodle",
            isPinned = false,
            isDefault = true,
            thumbnail = null,
            webViewState = null
        )
        tabs.add(defaultTab)
        currentTabIndex = 0

        loadSavedTabs()

        L.d("MoodleFragment", "Initialized tab system with ${tabs.size} tabs (clean default tab)")
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

                val currentUrl = webView.url ?: currentTab.url
                val currentTitle = webView.title ?: currentTab.title

                L.d("MoodleFragment", "Saving tab state - URL: $currentUrl, Title: $currentTitle")

                tabs[currentTabIndex] = currentTab.copy(
                    url = currentUrl,
                    title = currentTitle,
                    thumbnail = captureWebViewThumbnail(),
                    webViewState = bundle
                )
            }

            if (currentTab.isPinned) {
                savePinnedTabs()
            }
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

        if (isTextViewerMode) {
            isTextViewerMode = false
            textViewerContainer.visibility = View.GONE
            textViewerManager = null
        }

        if (isPdfTab(index)) {
            loadPdfTab(tab)
        } else {
            if (isPdfTab(previousIndex)) {
                pdfViewerManager?.closePdf()
                pdfViewerManager = null
                pdfFileUrl = null
            }

            activity?.runOnUiThread {
                webView.visibility = if (moodleDarkModeEnabled) View.INVISIBLE else View.VISIBLE
                pdfContainer.visibility = View.GONE
                textViewerContainer.visibility = View.GONE
                webControlsLayout.visibility = View.VISIBLE
                pdfControlsLayout.visibility = View.GONE
                btnBack.visibility = View.VISIBLE
                btnOpenInBrowser.visibility = View.VISIBLE

                webView.post {
                    if (tab.webViewState != null) {
                        webView.restoreState(tab.webViewState)

                        L.d("MoodleFragment", "Tab state restored for URL: ${tab.url}")

                        Handler(Looper.getMainLooper()).postDelayed({
                            checkForSessionDialogAfterTabSwitch()
                        }, 100)

                        Handler(Looper.getMainLooper()).postDelayed({
                            checkForSessionDialogAfterTabSwitch()
                        }, 300)

                        Handler(Looper.getMainLooper()).postDelayed({
                            checkForSessionDialogAfterTabSwitch()
                        }, 800)
                    } else {
                        L.d("MoodleFragment", "No saved state, loading URL: ${tab.url}")
                        webView.loadUrl(tab.url)
                    }
                }
            }
        }

        updateUIState()
        updateDashboardButtonIcon()

        L.d("MoodleFragment", "Switch complete - now on tab $currentTabIndex showing ${tab.url}")
    }

    private fun checkForSessionDialogAfterTabSwitch() {
        if (!isFragmentActive || !isAdded) {
            L.d("MoodleFragment", "Fragment not active, skipping session dialog check")
            return
        }

        val currentUrl = webView.url ?: ""

        L.d("MoodleFragment", "=== Checking for session dialog after tab switch ===")
        L.d("MoodleFragment", "Current URL: $currentUrl")
        L.d("MoodleFragment", "Current tab index: $currentTabIndex")
        L.d("MoodleFragment", "WebView visibility: ${webView.visibility}")

        if (!currentUrl.contains("login/index.php")) {
            L.d("MoodleFragment", "Not on login page, skipping dialog check")
            return
        }

        L.d("MoodleFragment", "On login page URL, proceeding with dialog check...")

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFragmentActive || !isAdded) {
                L.d("MoodleFragment", "Fragment detached during check delay")
                return@postDelayed
            }

            L.d("MoodleFragment", "Executing dialog detection JavaScript...")

            checkIfSessionTerminationDialogDirect { isTerminationDialog ->
                if (!isFragmentActive || !isAdded) {
                    L.d("MoodleFragment", "Fragment detached during dialog check result")
                    return@checkIfSessionTerminationDialogDirect
                }

                L.d("MoodleFragment", "Dialog check result: $isTerminationDialog")

                if (isTerminationDialog) {
                    L.d("MoodleFragment", "!!! SESSION TERMINATION DIALOG FOUND AFTER TAB SWITCH - Dismissing !!!")

                    activity?.runOnUiThread {
                        if (isFragmentActive && isAdded) {
                            dismissSessionTerminationDialog()
                        }
                    }
                } else {
                    L.d("MoodleFragment", "No session termination dialog found after tab switch")

                    val dontShowDialog = sharedPrefs.getBoolean("moodle_dont_show_login_dialog", false)

                    if (!dontShowDialog && !isLoginDialogShown) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (isFragmentActive && isAdded && !isLoginDialogShown) {
                                L.d("MoodleFragment", "Injecting dialog button on login page")
                                injectDialogButtonOnLoginPage()
                            }
                        }, 500)
                    }
                }
            }
        }, 100)
    }

    private fun checkIfSessionTerminationDialogDirect(callback: (Boolean) -> Unit) {
        val jsCode = """
        (function() {
            try {
                console.log('=== DIRECT Session Termination Dialog Check ===');
                console.log('Current URL: ' + window.location.href);
                console.log('Document ready state: ' + document.readyState);
                console.log('Document title: ' + document.title);
                console.log('Page visibility: ' + document.visibilityState);

                var body = document.body;
                if (body) {
                    console.log('Body exists, innerHTML length: ' + body.innerHTML.length);
                    console.log('Body first 200 chars: ' + body.innerHTML.substring(0, 200));
                } else {
                    console.log('ERROR: Body is null!');
                    return JSON.stringify({ isDialog: false, reason: 'no_body' });
                }

                if (!window.location.pathname.includes('/login/index.php')) {
                    console.log('Not on login page path: ' + window.location.pathname);
                    return JSON.stringify({ isDialog: false, reason: 'wrong_path' });
                }
                
                console.log('On login page path, checking for dialog header...');

                var confirmHeader = document.evaluate(
                    '/html/body/div[2]/div[2]/div/div/div/div/div/div/div/div/div/div[1]/h4',
                    document,
                    null,
                    XPathResult.FIRST_ORDERED_NODE_TYPE,
                    null
                ).singleNodeValue;
                
                if (!confirmHeader) {
                    console.log('Primary XPath failed, trying querySelector...');

                    var possibleHeaders = document.querySelectorAll('h4');
                    console.log('Found ' + possibleHeaders.length + ' h4 elements on page');
                    
                    for (var i = 0; i < possibleHeaders.length; i++) {
                        var headerText = possibleHeaders[i].textContent.trim();
                        console.log('h4[' + i + ']: "' + headerText + '"');
                        
                        if (headerText === 'Bestätigen' || headerText === 'Confirm') {
                            console.log('!!! Found dialog header via querySelector !!!');
                            confirmHeader = possibleHeaders[i];
                            break;
                        }
                    }
                    
                    if (!confirmHeader) {
                        console.log('No dialog header found - checking for login form...');
                        
                        var loginForm = document.querySelector('form[action*="login"]');
                        var usernameField = document.querySelector('input[name="username"]');
                        
                        if (loginForm || usernameField) {
                            console.log('Found login form elements - this is login page');
                            return JSON.stringify({ isDialog: false, reason: 'login_form_found' });
                        }
                        
                        return JSON.stringify({ isDialog: false, reason: 'no_header', h4Count: possibleHeaders.length });
                    }
                }
                
                var headerText = confirmHeader.textContent.trim();
                console.log('Found header with text: "' + headerText + '"');
                console.log('Header parent: ' + (confirmHeader.parentElement ? confirmHeader.parentElement.tagName : 'null'));
                
                if (headerText === 'Bestätigen' || headerText === 'Confirm') {
                    console.log('✓✓✓ THIS IS THE SESSION TERMINATION DIALOG ✓✓✓');

                    var cancelButton = document.evaluate(
                        '/html/body/div[2]/div[2]/div/div/div/div/div/div/div/div/div/div[3]/div/div[1]/form/button',
                        document,
                        null,
                        XPathResult.FIRST_ORDERED_NODE_TYPE,
                        null
                    ).singleNodeValue;
                    
                    if (cancelButton) {
                        console.log('Cancel button found via XPath: "' + cancelButton.textContent.trim() + '"');
                        console.log('Button visible: ' + (cancelButton.offsetParent !== null));
                        console.log('Button disabled: ' + cancelButton.disabled);
                        return JSON.stringify({ 
                            isDialog: true, 
                            reason: 'dialog_detected',
                            buttonText: cancelButton.textContent.trim(),
                            buttonVisible: (cancelButton.offsetParent !== null),
                            buttonDisabled: cancelButton.disabled
                        });
                    } else {
                        console.log('Cancel button not found via XPath, searching all buttons...');
                        
                        var allButtons = document.querySelectorAll('button');
                        console.log('Total buttons on page: ' + allButtons.length);
                        
                        for (var j = 0; j < allButtons.length; j++) {
                            var btn = allButtons[j];
                            var btnText = btn.textContent.trim().toLowerCase();
                            console.log('Button[' + j + ']: "' + btn.textContent.trim() + '" (type: ' + btn.type + ')');
                            
                            if (btnText.includes('abbrechen') || btnText.includes('cancel')) {
                                console.log('!!! Found cancel button via text search !!!');
                                return JSON.stringify({ 
                                    isDialog: true, 
                                    reason: 'dialog_detected_alt',
                                    buttonText: btn.textContent.trim(),
                                    buttonIndex: j
                                });
                            }
                        }
                        
                        return JSON.stringify({ 
                            isDialog: true, 
                            reason: 'dialog_no_button',
                            buttonText: null,
                            totalButtons: allButtons.length
                        });
                    }
                }
                
                console.log('Header text does not match - this is login page');
                console.log('Expected: "Bestätigen" or "Confirm", Got: "' + headerText + '"');
                return JSON.stringify({ 
                    isDialog: false, 
                    reason: 'header_mismatch',
                    foundText: headerText
                });
                
            } catch(e) {
                console.error('!!! EXCEPTION in dialog check !!!');
                console.error('Error: ' + e.message);
                console.error('Stack: ' + e.stack);
                return JSON.stringify({ 
                    isDialog: false, 
                    reason: 'exception',
                    error: e.message,
                    stack: e.stack
                });
            }
        })();
    """.trimIndent()

        L.d("MoodleFragment", "Executing checkIfSessionTerminationDialogDirect JavaScript...")

        webView.evaluateJavascript(jsCode) { result ->
            try {
                val cleanResult = result.replace("\\\"", "\"").trim('"')
                L.d("MoodleFragment", "=== Direct Dialog Check Result ===")
                L.d("MoodleFragment", "Raw result: $cleanResult")

                val jsonResult = JSONObject(cleanResult)
                val isDialog = jsonResult.optBoolean("isDialog", false)
                val reason = jsonResult.optString("reason", "unknown")

                L.d("MoodleFragment", "isDialog: $isDialog")
                L.d("MoodleFragment", "reason: $reason")

                when (reason) {
                    "exception" -> {
                        val error = jsonResult.optString("error", "")
                        val stack = jsonResult.optString("stack", "")
                        L.e("MoodleFragment", "JS Exception: $error")
                        L.e("MoodleFragment", "Stack: $stack")
                    }
                    "dialog_detected", "dialog_detected_alt" -> {
                        val buttonText = jsonResult.optString("buttonText", "")
                        val buttonVisible = jsonResult.optBoolean("buttonVisible", true)
                        val buttonDisabled = jsonResult.optBoolean("buttonDisabled", false)
                        L.d("MoodleFragment", "!!! DIALOG DETECTED !!!")
                        L.d("MoodleFragment", "Button text: '$buttonText'")
                        L.d("MoodleFragment", "Button visible: $buttonVisible")
                        L.d("MoodleFragment", "Button disabled: $buttonDisabled")
                    }
                    "dialog_no_button" -> {
                        val totalButtons = jsonResult.optInt("totalButtons", 0)
                        L.e("MoodleFragment", "Dialog header found but NO BUTTON! Total buttons: $totalButtons")
                    }
                    "no_header" -> {
                        val h4Count = jsonResult.optInt("h4Count", 0)
                        L.d("MoodleFragment", "No dialog header found. Total h4 elements: $h4Count")
                    }
                    "header_mismatch" -> {
                        val foundText = jsonResult.optString("foundText", "")
                        L.d("MoodleFragment", "Header mismatch. Found: '$foundText'")
                    }
                    "login_form_found" -> {
                        L.d("MoodleFragment", "Login form detected - this is login page")
                    }
                }

                L.d("MoodleFragment", "=== End Dialog Check Result ===")

                callback(isDialog)
            } catch (e: Exception) {
                L.e("MoodleFragment", "Error parsing direct dialog check result: $result", e)
                callback(false)
            }
        }
    }

    private fun loadPdfTab(tab: TabInfo) {
        val pdfFilePath = tab.webViewState?.getString("pdf_file_path")
        pdfFileUrl = tab.webViewState?.getString("pdf_url") ?: tab.url

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

                    if (darkMode != (pdfViewerManager?.forceDarkMode ?: false)) {
                        pdfViewerManager?.toggleDarkMode()
                    }

                    pdfViewerManager?.setCurrentPage(savedPage)
                    if (!scrollMode) pdfViewerManager?.toggleScrollMode()

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
        textViewerContainer.visibility = View.GONE
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
            resetWebViewState()

            Handler(Looper.getMainLooper()).postDelayed({
                switchToTab(newCurrentIndex)
            }, 50)
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

    private fun resetDefaultTab() {
        val defaultTabIndex = tabs.indexOfFirst { it.isDefault }

        if (defaultTabIndex != -1) {
            L.d("MoodleFragment", "Resetting default tab at index $defaultTabIndex")

            val defaultTab = tabs[defaultTabIndex]
            tabs[defaultTabIndex] = defaultTab.copy(
                url = loginUrl,
                title = "Moodle",
                thumbnail = null,
                webViewState = null
            )

            if (currentTabIndex == defaultTabIndex) {
                webView.clearHistory()
                webView.clearCache(false)
            }

            L.d("MoodleFragment", "Default tab reset to login page")
        }
    }

    private fun resetDefaultTabToLogin() {
        val defaultTabIndex = tabs.indexOfFirst { it.isDefault }

        if (defaultTabIndex != -1) {
            L.d("MoodleFragment", "Resetting default tab to login page")

            val defaultTab = tabs[defaultTabIndex]
            tabs[defaultTabIndex] = defaultTab.copy(
                url = loginUrl,
                title = "Moodle",
                thumbnail = null,
                webViewState = null
            )

            if (currentTabIndex == defaultTabIndex) {
                webView.clearHistory()
                webView.clearCache(false)
                webView.loadUrl(loginUrl)
            }

            savePinnedTabs()
            L.d("MoodleFragment", "Default tab reset complete")
        }
    }

    override fun onStop() {
        super.onStop()

        if (wasOnLogoutConfirmPage) {
            L.d("MoodleFragment", "App stopped while on logout page - cancelling logout")
            wasOnLogoutConfirmPage = false
            logoutConfirmPageUrl = ""
        }

        if (isAppClosing) {
            resetDefaultTabToLogin()
        }

        savePinnedTabs()
        L.d("MoodleFragment", "onStop" + if (isAppClosing) " - app closing, reset default tab" else " - activity switch")
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

                        Handler(Looper.getMainLooper()).postDelayed({
                            checkForSessionDialogAfterTabSwitch()
                        }, 300)
                    }
                    .start()
            } else {
                (view as? ViewGroup)?.removeView(overlay)
                tabOverlayView = null
                isTabViewVisible = false
                updateTabButtonIcon()

                Handler(Looper.getMainLooper()).postDelayed({
                    checkForSessionDialogAfterTabSwitch()
                }, 100)
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
                if (index in tabs.indices && !tabs[index].isDefault) {
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
                if (isDraggingTab) {
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
                                icon.clearAnimation()
                                icon.animate()
                                    .translationY(-5f * density)
                                    .scaleX(1.1f)
                                    .scaleY(1.1f)
                                    .setDuration(150)
                                    .start()
                            }
                            else -> {
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

    //endregion

    //region **HEADER**
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

    private fun performSearch() {
        val query = searchBarMoodle.text.toString().trim()
        if (query.isEmpty()) return

        val searchType = spinnerSearchType.selectedItemPosition

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
            textViewerContainer.visibility = View.GONE
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

    private fun showMenuPopup() {
        val popup = PopupMenu(requireContext(), btnMenu)

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
                else -> false
            }
        }
        popup.show()
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

    //endregion

    //region **NAVIGATION**

    private fun navigateToUrl(userInput: String) {
        val url = when {
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

        if (url.startsWith(moodleBaseUrl)) {
            loadUrlInBackground(url)
        } else {
            Toast.makeText(requireContext(), getString(R.string.moodle_invalid_url), Toast.LENGTH_SHORT).show()
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
            textViewerContainer.visibility = View.GONE
            webControlsLayout.visibility = View.VISIBLE
            pdfControlsLayout.visibility = View.GONE
            btnBack.visibility = View.VISIBLE
        }

        showLoadingBar()
        webView.loadUrl(url)
    }

    //endregion

    //region **WEBVIEW**

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

    private fun analyzeLinkElementAndHandle(url: String) {
        val jsCode = """
        (function() {
            try {
                console.log('=== Analyzing Link Element ===');
                console.log('URL: $url');

                var links = document.querySelectorAll('a[href="$url"]');
                if (links.length === 0) {
                    console.log('No link element found');
                    return JSON.stringify({ shouldHandle: false, reason: 'no_link' });
                }
                
                var link = links[0];
                console.log('Found link element');

                var firstSpan = link.querySelector('span.instancename');
                if (!firstSpan) {
                    console.log('No instancename span found');
                    return JSON.stringify({ shouldHandle: false, reason: 'no_instancename' });
                }
                
                var accessHideSpan = firstSpan.querySelector('span.accesshide');
                if (!accessHideSpan) {
                    console.log('No accesshide span found');
                    return JSON.stringify({ shouldHandle: false, reason: 'no_accesshide' });
                }
                
                var spanText = accessHideSpan.textContent.trim().toLowerCase();
                console.log('AccessHide span text: "' + spanText + '"');

                if (!spanText.includes('datei') && !spanText.includes('file')) {
                    console.log('Does not contain datei/file - open normally');
                    return JSON.stringify({ shouldHandle: false, reason: 'not_file' });
                }
                
                console.log('Contains datei/file - analyzing icon');

                var currentElement = link;
                for (var i = 0; i < 4; i++) {
                    currentElement = currentElement.parentElement;
                    if (!currentElement || currentElement.tagName !== 'DIV') {
                        console.log('Failed to navigate up ' + (i + 1) + ' divs');
                        return JSON.stringify({ shouldHandle: false, reason: 'navigation_failed' });
                    }
                }
                
                console.log('Navigated up 4 divs successfully');

                var iconContainer = null;
                for (var i = 0; i < currentElement.children.length; i++) {
                    var child = currentElement.children[i];
                    if (child.tagName === 'DIV') {
                        iconContainer = child;
                        break;
                    }
                }
                
                if (!iconContainer) {
                    console.log('Icon container not found');
                    return JSON.stringify({ shouldHandle: false, reason: 'no_icon_container' });
                }
                
                var iconImg = iconContainer.querySelector('img.activityicon, img[data-region="activity-icon"]');
                if (!iconImg) {
                    console.log('Icon img not found');
                    return JSON.stringify({ shouldHandle: false, reason: 'no_icon_img' });
                }
                
                var iconSrc = iconImg.src;
                console.log('Icon src: ' + iconSrc);
                
                var fileType = 'unknown';
                if (iconSrc.includes('/f/pdf')) {
                    fileType = 'pdf';
                } else if (iconSrc.includes('/f/document')) {
                    fileType = 'document';
                } else if (iconSrc.includes('/f/image')) {
                    fileType = 'image';
                } else if (iconSrc.includes('/f/audio')) {
                    fileType = 'audio';
                } else {
                    fileType = 'other';
                }
                
                console.log('Detected file type: ' + fileType);
                
                return JSON.stringify({
                    shouldHandle: true,
                    fileType: fileType,
                    iconSrc: iconSrc
                });
                
            } catch(e) {
                console.error('Error analyzing link: ' + e.message);
                console.error('Stack: ' + e.stack);
                return JSON.stringify({
                    shouldHandle: false,
                    reason: 'exception',
                    error: e.message
                });
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            try {
                val cleanResult = result.replace("\\\"", "\"").trim('"')
                L.d("MoodleFragment", "Link analysis result: $cleanResult")

                val jsonResult = JSONObject(cleanResult)
                val shouldHandle = jsonResult.optBoolean("shouldHandle", false)

                if (!shouldHandle) {
                    val reason = jsonResult.optString("reason", "unknown")
                    L.d("MoodleFragment", "Not handling link, reason: $reason")
                    webView.loadUrl(url)
                    return@evaluateJavascript
                }

                val fileType = jsonResult.optString("fileType", "unknown")
                L.d("MoodleFragment", "File type detected: $fileType")

                val cookies = CookieManager.getInstance().getCookie(url)

                when (fileType) {
                    "pdf" -> {
                        L.d("MoodleFragment", "Handling as PDF")
                        if (url.contains("/mod/resource/view.php")) {
                            resolveDownloadUrl(url, cookies, forceDownload = false)
                        } else {
                            handlePdfForViewerWithCookies(url, cookies)
                        }
                    }
                    "document" -> {
                        L.d("MoodleFragment", "Handling as DOCX")
                        if (url.contains("/mod/resource/view.php")) {
                            resolveDownloadUrl(url, cookies, forceDownload = false)
                        } else {
                            handleDocxFile(url, cookies, forceDownload = false)
                        }
                    }
                    "image", "audio" -> {
                        L.d("MoodleFragment", "Handling as image/audio page - opening normally")
                        webView.loadUrl(url)
                    }
                    "other" -> {
                        L.d("MoodleFragment", "Unknown file type - resolving redirect")
                        resolveDownloadUrl(url, cookies, forceDownload = false)
                    }
                    else -> {
                        L.d("MoodleFragment", "Fallback - opening normally")
                        webView.loadUrl(url)
                    }
                }

            } catch (e: Exception) {
                L.e("MoodleFragment", "Error parsing link analysis result", e)
                webView.loadUrl(url)
            }
        }
    }

    private data class LinkInfo(
        val isDownloadable: Boolean,
        val isCopyable: Boolean,
        val fileType: String? = null,
        val isImage: Boolean = false,
        val isAudio: Boolean = false,
        val directDownloadUrl: String? = null
    )

    private fun analyzeLinkCapabilities(url: String, callback: (LinkInfo) -> Unit) {
        val jsCode = """
        (function() {
            try {
                console.log('=== Analyzing Link Capabilities ===');
                console.log('URL: $url');
                
                var linkInfo = {
                    isDownloadable: false,
                    isCopyable: false,
                    fileType: null,
                    isImage: false,
                    isAudio: false,
                    directDownloadUrl: null
                };

                var links = document.querySelectorAll('a[href="$url"]');
                if (links.length === 0) {
                    console.log('No link found for capabilities analysis');
                    return JSON.stringify(linkInfo);
                }
                
                var link = links[0];

                var firstSpan = link.querySelector('span.instancename');
                if (!firstSpan) {
                    console.log('No instancename span');
                    return JSON.stringify(linkInfo);
                }
                
                var accessHideSpan = firstSpan.querySelector('span.accesshide');
                if (!accessHideSpan) {
                    console.log('No accesshide span');
                    return JSON.stringify(linkInfo);
                }
                
                var spanText = accessHideSpan.textContent.trim().toLowerCase();
                console.log('Span text: "' + spanText + '"');

                if (!spanText.includes('datei') && !spanText.includes('file')) {
                    console.log('Not a file link');
                    return JSON.stringify(linkInfo);
                }

                var currentElement = link;
                for (var i = 0; i < 4; i++) {
                    currentElement = currentElement.parentElement;
                    if (!currentElement || currentElement.tagName !== 'DIV') {
                        console.log('Navigation failed');
                        return JSON.stringify(linkInfo);
                    }
                }

                var iconContainer = null;
                for (var i = 0; i < currentElement.children.length; i++) {
                    var child = currentElement.children[i];
                    if (child.tagName === 'DIV') {
                        iconContainer = child;
                        break;
                    }
                }
                
                if (!iconContainer) {
                    return JSON.stringify(linkInfo);
                }
                
                var iconImg = iconContainer.querySelector('img.activityicon, img[data-region="activity-icon"]');
                if (!iconImg) {
                    return JSON.stringify(linkInfo);
                }
                
                var iconSrc = iconImg.src.toLowerCase();
                console.log('Icon src: ' + iconSrc);

                if (iconSrc.includes('/f/pdf')) {
                    linkInfo.isDownloadable = true;
                    linkInfo.isCopyable = true;
                    linkInfo.fileType = 'pdf';
                } else if (iconSrc.includes('/f/document')) {
                    linkInfo.isDownloadable = true;
                    linkInfo.isCopyable = true;
                    linkInfo.fileType = 'docx';
                } else if (iconSrc.includes('/f/image')) {
                    linkInfo.isImage = true;
                } else if (iconSrc.includes('/f/audio')) {
                    linkInfo.isAudio = true;
                } else {
                    linkInfo.isDownloadable = true;
                    linkInfo.isCopyable = false;
                }
                
                console.log('Link info: ' + JSON.stringify(linkInfo));
                return JSON.stringify(linkInfo);
                
            } catch(e) {
                console.error('Error: ' + e.message);
                return JSON.stringify({
                    isDownloadable: false,
                    isCopyable: false,
                    fileType: null,
                    isImage: false,
                    isAudio: false,
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
                    isImage = jsonObject.optBoolean("isImage", false),
                    isAudio = jsonObject.optBoolean("isAudio", false)
                )

                L.d("MoodleFragment", "Link capabilities - Download: ${linkInfo.isDownloadable}, Copy: ${linkInfo.isCopyable}, Type: ${linkInfo.fileType}, Image: ${linkInfo.isImage}, Audio: ${linkInfo.isAudio}")
                callback(linkInfo)
            } catch (e: Exception) {
                L.e("MoodleFragment", "Error parsing link capabilities", e)
                callback(LinkInfo(
                    isDownloadable = isDownloadableFile(url),
                    isCopyable = url.endsWith(".pdf", ignoreCase = true) || url.endsWith(".docx", ignoreCase = true),
                    isImage = false,
                    isAudio = false
                ))
            }
        }
    }

    private fun showLinkOptionsDialog(url: String, linkInfo: LinkInfo) {
        val options = mutableListOf<Pair<String, Int>>()
        val actions = mutableListOf<() -> Unit>()

        val cookies = CookieManager.getInstance().getCookie(url)
        val isMoodleUrl = url.startsWith("https://moodle.kleyer.eu/")

        // open in new tab (only for moodle urls and non-downloadable files)
        if (isMoodleUrl && (!linkInfo.isDownloadable || linkInfo.isImage || linkInfo.isAudio)) {
            options.add(Pair(getString(R.string.moodle_open_in_new_tab), R.drawable.ic_tab_background))
            actions.add {
                createNewTab(url)
                Toast.makeText(requireContext(), getString(R.string.moodle_tab_opened_in_new_tab), Toast.LENGTH_SHORT).show()
            }
        }

        // download to user device
        if (linkInfo.isDownloadable) {
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

        // download audio
        if (linkInfo.isAudio) {
            options.add(Pair(getString(R.string.moodle_download), R.drawable.ic_download))
            actions.add {
                downloadAudioFile(url, cookies)
            }
        }

        // download images
        if (linkInfo.isImage) {
            options.add(Pair(getString(R.string.moodle_download), R.drawable.ic_download))
            actions.add {
                downloadImageFile(url, cookies)
            }
        }

        // copy text (pdf/docx only)
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

        // open in browser
        options.add(Pair(getString(R.string.moodle_open_browser), R.drawable.ic_globe))
        actions.add { openInExternalBrowser(url) }

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

    private fun resetWebViewState() {
        activity?.runOnUiThread {
            try {
                webView.setInitialScale(0)
                webView.settings.apply {
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                }

                webView.layoutParams = webView.layoutParams
                webView.requestLayout()

                isDarkModeReady = false
                pendingDarkModeUrl = null
                webView.visibility = View.VISIBLE

            } catch (e: Exception) {
                L.e("MoodleFragment", "Error resetting WebView state", e)
            }
        }
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
                    val extractedName = decodedUrl.substringAfterLast("/").substringBefore("?")
                    extractedName.ifBlank { "moodle_file_${System.currentTimeMillis()}" }
                } catch (_: Exception) {
                    "moodle_file_${System.currentTimeMillis()}"
                }
            }

            request.setTitle(fileName)
            request.setDescription(getString(R.string.moodle_downloading))

            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

            request.setAllowedNetworkTypes(
                DownloadManager.Request.NETWORK_WIFI or
                        DownloadManager.Request.NETWORK_MOBILE
            )
            request.setAllowedOverMetered(true)
            request.setAllowedOverRoaming(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                request.setRequiresDeviceIdle(false)
                request.setRequiresCharging(false)
            }

            val mimeType = when {
                fileName.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
                fileName.endsWith(".docx", ignoreCase = true) -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                fileName.endsWith(".doc", ignoreCase = true) -> "application/msword"
                fileName.endsWith(".pptx", ignoreCase = true) -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                fileName.endsWith(".ppt", ignoreCase = true) -> "application/vnd.ms-powerpoint"
                fileName.endsWith(".xlsx", ignoreCase = true) -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                fileName.endsWith(".xls", ignoreCase = true) -> "application/vnd.ms-excel"
                fileName.endsWith(".jpg", ignoreCase = true) || fileName.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
                fileName.endsWith(".png", ignoreCase = true) -> "image/png"
                fileName.endsWith(".gif", ignoreCase = true) -> "image/gif"
                fileName.endsWith(".svg", ignoreCase = true) -> "image/svg+xml"
                fileName.endsWith(".wav", ignoreCase = true) -> "audio/wav"
                fileName.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
                fileName.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
                fileName.endsWith(".zip", ignoreCase = true) -> "application/zip"
                else -> null
            }
            if (mimeType != null) {
                request.setMimeType(mimeType)
            }

            if (!cookies.isNullOrBlank()) {
                request.addRequestHeader("Cookie", cookies)
                L.d("MoodleFragment", "Added cookies to download: ${cookies.take(50)}...")
            } else {
                L.w("MoodleFragment", "WARNING: No cookies available for download!")
            }

            request.addRequestHeader("User-Agent", userAgent)
            request.addRequestHeader("Referer", moodleBaseUrl)
            request.addRequestHeader("Accept", "*/*")
            request.addRequestHeader("Accept-Encoding", "identity")
            request.addRequestHeader("Connection", "keep-alive")
            request.addRequestHeader("Cache-Control", "no-cache")

            val downloadManager = requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = downloadManager.enqueue(request)

            L.d("MoodleFragment", "Download enqueued with ID: $downloadId, file: $fileName")

            backgroundExecutor.execute {
                var previousBytes = 0L
                var stuckCount = 0

                for (i in 1..10) {
                    Thread.sleep(1000)

                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = downloadManager.query(query)

                    if (cursor.moveToFirst()) {
                        val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val bytesDownloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        val totalBytesIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)

                        val status = cursor.getInt(statusIndex)
                        val bytesDownloaded = cursor.getLong(bytesDownloadedIndex)
                        val totalBytes = cursor.getLong(totalBytesIndex)

                        L.d("MoodleFragment", "Download progress: $bytesDownloaded / $totalBytes bytes (${i}s)")

                        when (status) {
                            DownloadManager.STATUS_FAILED -> {
                                val reason = cursor.getInt(reasonIndex)
                                L.e("MoodleFragment", "Download failed with reason: $reason")
                                cursor.close()

                                activity?.runOnUiThread {
                                    val reasonText = when (reason) {
                                        DownloadManager.ERROR_CANNOT_RESUME -> "Cannot resume download"
                                        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "No external storage"
                                        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "File already exists"
                                        DownloadManager.ERROR_FILE_ERROR -> "Storage error"
                                        DownloadManager.ERROR_HTTP_DATA_ERROR -> "HTTP data error"
                                        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Insufficient space"
                                        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Too many redirects"
                                        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "HTTP error"
                                        else -> "Unknown error ($reason)"
                                    }
                                    Toast.makeText(
                                        requireContext(),
                                        getString(R.string.moodle_download_failed, reasonText),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                return@execute
                            }
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                L.d("MoodleFragment", "Download completed successfully in ${i}s")
                                cursor.close()
                                return@execute
                            }
                            DownloadManager.STATUS_RUNNING -> {
                                if (bytesDownloaded == previousBytes && bytesDownloaded > 0) {
                                    stuckCount++
                                    L.w("MoodleFragment", "Download appears stuck (count: $stuckCount)")

                                    if (stuckCount >= 5) {
                                        L.e("MoodleFragment", "Download stuck for 5 seconds, may have stalled")
                                        cursor.close()
                                        return@execute
                                    }
                                } else {
                                    stuckCount = 0
                                    previousBytes = bytesDownloaded
                                }
                            }
                            DownloadManager.STATUS_PAUSED -> {
                                val reason = cursor.getInt(reasonIndex)
                                L.w("MoodleFragment", "Download paused with reason: $reason")
                            }
                            DownloadManager.STATUS_PENDING -> {
                                L.d("MoodleFragment", "Download pending...")
                            }
                        }
                    }
                    cursor.close()
                }
            }

            Toast.makeText(requireContext(), getString(R.string.moodle_download_started, fileName), Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            L.e("MoodleFragment", "Download failed", e)
            Toast.makeText(requireContext(), getString(R.string.moodle_download_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun downloadAudioFile(viewUrl: String, cookies: String?) {
        L.d("MoodleFragment", "=== Downloading Audio File ===")
        L.d("MoodleFragment", "View URL: $viewUrl")

        val progressDialog = AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.moodle_loading))
            .setCancelable(false)
            .show()

        backgroundExecutor.execute {
            try {
                val freshCookies = CookieManager.getInstance().getCookie(moodleBaseUrl)
                val cookiesToUse = freshCookies ?: cookies

                if (cookiesToUse == null) {
                    L.e("MoodleFragment", "No cookies for audio download")
                    activity?.runOnUiThread {
                        progressDialog.dismiss()
                        Toast.makeText(requireContext(), getString(R.string.moodle_no_session_cookies), Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }

                L.d("MoodleFragment", "Fetching audio page: $viewUrl")

                val conn = URL(viewUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Cookie", cookiesToUse)
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty("Referer", moodleBaseUrl)
                conn.connectTimeout = 15000
                conn.readTimeout = 15000

                val responseCode = conn.responseCode
                L.d("MoodleFragment", "Audio page response: $responseCode")

                if (responseCode == 200) {
                    val html = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()

                    val audioSourcePattern = "<source\\s+src=\"([^\"]+)\"[^>]*type=\"audio/[^\"]+\"".toRegex()
                    val match = audioSourcePattern.find(html)

                    if (match != null) {
                        val audioUrl = match.groupValues[1]
                        L.d("MoodleFragment", "Found audio URL: $audioUrl")

                        activity?.runOnUiThread {
                            progressDialog.dismiss()
                            downloadToDeviceWithCookies(audioUrl, cookiesToUse)
                        }
                    } else {
                        L.e("MoodleFragment", "Audio source not found in HTML")
                        activity?.runOnUiThread {
                            progressDialog.dismiss()
                            Toast.makeText(requireContext(), getString(R.string.moodle_couldnt_find_audio_file), Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    L.e("MoodleFragment", "Failed to load audio page: $responseCode")
                    conn.disconnect()

                    activity?.runOnUiThread {
                        progressDialog.dismiss()
                        Toast.makeText(requireContext(), getString(R.string.moodle_failed_to_load_audio_page), Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                L.e("MoodleFragment", "Error downloading audio", e)
                activity?.runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(requireContext(), getString(R.string.moodle_error_details, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun downloadImageFile(viewUrl: String, cookies: String?) {
        L.d("MoodleFragment", "=== Downloading Image File ===")
        L.d("MoodleFragment", "View URL: $viewUrl")

        val progressDialog = AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.moodle_loading))
            .setCancelable(false)
            .show()

        backgroundExecutor.execute {
            try {
                val freshCookies = CookieManager.getInstance().getCookie(moodleBaseUrl)
                val cookiesToUse = freshCookies ?: cookies

                if (cookiesToUse == null) {
                    L.e("MoodleFragment", "No cookies for image download")
                    activity?.runOnUiThread {
                        progressDialog.dismiss()
                        Toast.makeText(requireContext(), getString(R.string.moodle_no_session_cookies), Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }

                L.d("MoodleFragment", "Fetching image page: $viewUrl")

                val conn = URL(viewUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Cookie", cookiesToUse)
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty("Referer", moodleBaseUrl)
                conn.connectTimeout = 15000
                conn.readTimeout = 15000

                val responseCode = conn.responseCode
                L.d("MoodleFragment", "Image page response: $responseCode")

                if (responseCode == 200) {
                    val html = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()

                    val imagePattern = "<img[^>]+class=\"resourceimage\"[^>]+src=\"([^\"]+)\"".toRegex()
                    val match = imagePattern.find(html)

                    if (match != null) {
                        val imageUrl = match.groupValues[1]
                        L.d("MoodleFragment", "Found image URL: $imageUrl")

                        activity?.runOnUiThread {
                            progressDialog.dismiss()
                            downloadToDeviceWithCookies(imageUrl, cookiesToUse)
                        }
                    } else {
                        L.e("MoodleFragment", "Image src not found in HTML")
                        activity?.runOnUiThread {
                            progressDialog.dismiss()
                            Toast.makeText(requireContext(), getString(R.string.moodle_couldnt_find_image_file), Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    L.e("MoodleFragment", "Failed to load image page: $responseCode")
                    conn.disconnect()

                    activity?.runOnUiThread {
                        progressDialog.dismiss()
                        Toast.makeText(requireContext(), getString(R.string.moodle_failed_to_load_image_page), Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                L.e("MoodleFragment", "Error downloading image", e)
                activity?.runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(requireContext(), getString(R.string.moodle_error_details, e.message), Toast.LENGTH_SHORT).show()
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
                            getString(R.string.moodle_no_session_cookies_reload),
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
                                    getString(R.string.moodle_session_expired_reload),
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
                            getString(R.string.moodle_couldnt_resolve_download_url),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

            } catch (e: Exception) {
                L.e("MoodleFragment", "EXCEPTION in resolveDownloadUrl", e)
                activity?.runOnUiThread {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.moodle_error_details, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
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
                            getString(R.string.moodle_no_session_cookies),
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
                                Toast.makeText(requireContext(), getString(R.string.moodle_failed_to_parse_document), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    L.e("MoodleFragment", "Failed to resolve document URL")
                    activity?.runOnUiThread {
                        progressDialog.dismiss()
                        Toast.makeText(
                            requireContext(),
                        getString(R.string.moodle_couldnt_resolve_url_for_parsing),
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
                        getString(R.string.moodle_error_details, e.message),
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

    //endregion

    //region **DATA**

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

    //endregion

    //region **CALENDAR**

    private fun refreshCalendarDataInBackground() {
        try {
            L.d("MoodleFragment", "Starting calendar data refresh...")

            if (!isFragmentActive || !isAdded) {
                L.d("MoodleFragment", "Fragment not active, skipping calendar refresh")
                return
            }

            activity?.runOnUiThread {
                if (!isFragmentActive || !isAdded) {
                    L.d("MoodleFragment", "Fragment detached during calendar refresh")
                    return@runOnUiThread
                }
                refreshCalendarDataViaForm()
            }
        } catch (e: Exception) {
            L.e("MoodleFragment", "Error in background calendar refresh", e)
        }
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
                if (!isFragmentActive || !isAdded) {
                    L.d("MoodleFragment", "Fragment not active, aborting calendar export")
                    return@execute
                }

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
                    if (isFragmentActive && isAdded) {
                        activity?.runOnUiThread {
                            context?.let {
                                Toast.makeText(it, getString(R.string.moodle_calendar_export_page_error), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    return@execute
                }

                // Read the response to extract session key
                val exportPageContent = connection1.inputStream.bufferedReader().use { it.readText() }
                connection1.disconnect()

                // Check if we're authenticated
                if (exportPageContent.contains("login") && !exportPageContent.contains("logout")) {
                    L.e("MoodleFragment", "Not authenticated - redirected to login")
                    if (isFragmentActive && isAdded) {
                        activity?.runOnUiThread {
                            context?.let {
                                Toast.makeText(it, getString(R.string.moodle_calendar_export_login_required), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    return@execute
                }

                // Extract session key using regex
                val sesskeyPattern = """name="sesskey"[^>]*value="([^"]+)"""".toRegex()
                val sesskeyMatch = sesskeyPattern.find(exportPageContent)

                if (sesskeyMatch == null) {
                    L.e("MoodleFragment", "Could not find session key in export page")
                    if (isFragmentActive && isAdded) {
                        activity?.runOnUiThread {
                            context?.let {
                                Toast.makeText(it, getString(R.string.moodle_calendar_export_no_key), Toast.LENGTH_SHORT).show()
                            }
                        }
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
                    instanceFollowRedirects = false
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
                                if (isFragmentActive && isAdded) {
                                    activity?.runOnUiThread {
                                        context?.let {
                                            Toast.makeText(it, getString(R.string.moodle_calendar_invalid_format), Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        } else {
                            L.e("MoodleFragment", "Failed to download calendar: $response3Code")
                            connection3.disconnect()
                            if (isFragmentActive && isAdded) {
                                activity?.runOnUiThread {
                                    context?.let {
                                        Toast.makeText(it, getString(R.string.moodle_calendar_download_failed), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    } else {
                        L.e("MoodleFragment", "Unexpected redirect location: $redirectLocation")
                        if (isFragmentActive && isAdded) {
                            activity?.runOnUiThread {
                                context?.let {
                                    Toast.makeText(it, getString(R.string.moodle_calendar_export_redirect), Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                } else if (response2Code == 200) {
                    val directContent = connection2.inputStream.bufferedReader().use { it.readText() }
                    connection2.disconnect()

                    if (directContent.contains("BEGIN:VCALENDAR")) {
                        L.d("MoodleFragment", "Got calendar data directly from form submission")
                        parseAndSaveCalendarData(directContent)
                    } else {
                        L.e("MoodleFragment", "Form submission returned unexpected content")
                        L.d("MoodleFragment", "Content preview: ${directContent.take(200)}")
                        if (isFragmentActive && isAdded) {
                            activity?.runOnUiThread {
                                context?.let {
                                    Toast.makeText(it, getString(R.string.moodle_calendar_export_unexpected_response), Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                } else {
                    L.e("MoodleFragment", "Form submission failed: $response2Code")
                    connection2.disconnect()
                    if (isFragmentActive && isAdded) {
                        activity?.runOnUiThread {
                            context?.let {
                                Toast.makeText(it, getString(R.string.moodle_calendar_export_form_error), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                L.e("MoodleFragment", "Error in HTTP calendar export", e)
                if (isFragmentActive && isAdded) {
                    activity?.runOnUiThread {
                        context?.let {
                            Toast.makeText(it, getString(R.string.moodle_calendar_export_error_format, e.message), Toast.LENGTH_SHORT).show()
                        }
                    }
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

                if (isFragmentActive && isAdded) {
                    activity?.runOnUiThread {
                        context?.let {
                            //Toast.makeText(it, getString(R.string.moodle_calendar_refresh_success, events.size), Toast.LENGTH_SHORT).show()
                            L.d("MoodleFragment", "TOAST - Calendar data refresh successfully")
                        }
                    }
                }

                L.d("MoodleFragment", "Successfully imported ${events.size} calendar events")
            }
        } catch (e: Exception) {
            L.e("MoodleFragment", "Error parsing calendar data", e)
            if (isFragmentActive && isAdded) {
                activity?.runOnUiThread {
                    context?.let {
                        Toast.makeText(it, getString(R.string.moodle_calendar_refresh_failed), Toast.LENGTH_SHORT).show()
                    }
                }
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

    //endregion

    //region **CAL_SEARCH**

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

    private fun searchForMoodleEntry(category: String, summary: String, entryId: String) {
        L.d("MoodleFragment", "Searching for Moodle entry - Category: $category, Summary: $summary (ID: $entryId)")

        if (!isNetworkAvailable()) {
            Toast.makeText(requireContext(), getString(R.string.moodle_offline_error), Toast.LENGTH_LONG).show()
            return
        }

        val searchQuery = extractSearchableCourseName(category)

        L.d("MoodleFragment", "Extracted search query: '$searchQuery' from category: '$category'")

        searchCancelled = false
        searchProgressDialog = showSearchProgressDialog(searchQuery, summary)

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

            monitorLoginCompletionForSearch(searchQuery, summary, entryId)
        } else {
            continueSearchAfterLogin(searchQuery, summary, entryId)
        }
    }

    private fun extractSearchableCourseName(category: String): String {
        L.d("MoodleFragment", "Attempting to extract searchable name from: $category")

        // Remove common prefixes that don't help with searching
        val cleaned = category
            .replace("Kurs:", "")
            .replace("Aufgabe:", "")
            .trim()

        // Strategy 1: Try to find a class code pattern (e.g., "13BG2G", "12BG", etc.)
        val classCodePattern = """(\d{2}[A-Z]{2,4}\d*[A-Z]*)""".toRegex()
        val classCodeMatch = classCodePattern.find(cleaned)
        if (classCodeMatch != null) {
            val courseCode = classCodeMatch.value
            L.d("MoodleFragment", "Strategy 1 (class code): Found '$courseCode'")
            return courseCode
        }

        // Strategy 2: Check for "—" or ":" separators and take the first meaningful part
        val separators = listOf("—", ":", " - ")
        for (separator in separators) {
            if (cleaned.contains(separator)) {
                val firstPart = cleaned.split(separator).firstOrNull()?.trim()
                if (firstPart != null && firstPart.length >= 3) {
                    L.d("MoodleFragment", "Strategy 2 (separator '$separator'): Found '$firstPart'")
                    return firstPart
                }
            }
        }

        // Strategy 3: Try to extract subject/teacher pattern (e.g., "13BG PoWi Peter")
        // Take everything before brackets, slashes, or "Aufgabe"
        val beforeBracket = cleaned.split("[", "(", "/", "Aufgabe").firstOrNull()?.trim()
        if (beforeBracket != null && beforeBracket.length >= 3 && beforeBracket != cleaned) {
            L.d("MoodleFragment", "Strategy 3 (before bracket): Found '$beforeBracket'")
            return beforeBracket
        }

        // Strategy 4: Take first 3-5 words (likely to contain the course identifier)
        val words = cleaned.split(" ").filter { it.isNotBlank() }
        if (words.size > 3) {
            val firstWords = words.take(3).joinToString(" ")
            L.d("MoodleFragment", "Strategy 4 (first 3 words): Found '$firstWords'")
            return firstWords
        }

        // Strategy 5: If everything else fails, use the cleaned category
        // but limit it to a reasonable length for searching
        val fallback = if (cleaned.length > 50) {
            cleaned.take(50).trim()
        } else {
            cleaned
        }

        L.d("MoodleFragment", "Strategy 5 (fallback): Using '$fallback'")
        return fallback
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

        val initialDelay = 800L

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

        val escapedQuery = searchQuery
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("`", "\\`")
            .replace("$", "\\$")
            .replace("\t", "\\t")

        L.d("MoodleFragment", "Escaped query: $escapedQuery")

        val jsCode = """
        (function() {
            try {
                console.log('=== Moodle Search Debug ===');
                console.log('Search query: "$escapedQuery"');
                
                var existingResults = document.querySelector('[data-region="courses-view"]');
                if (existingResults) {
                    existingResults.innerHTML = '';
                    console.log('Cleared existing results');
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
                    console.error('Search field not found');
                    return false;
                }

                console.log('Search field found, filling with value');
                searchField.disabled = false;
                searchField.readOnly = false;
                searchField.value = '';
                searchField.focus();

                var nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
                nativeInputValueSetter.call(searchField, '$escapedQuery');
                
                console.log('Field value set to: ' + searchField.value);

                var inputEvent = new Event('input', { bubbles: true });
                searchField.dispatchEvent(inputEvent);

                var changeEvent = new Event('change', { bubbles: true });
                searchField.dispatchEvent(changeEvent);
                
                console.log('Events dispatched');
                
                setTimeout(function() {
                    var keyupEvent = new KeyboardEvent('keyup', { 
                        key: 'Enter',
                        keyCode: 13,
                        bubbles: true
                    });
                    searchField.dispatchEvent(keyupEvent);
                    console.log('Keyup event dispatched');
                }, 150);
                
                return true;
            } catch(e) {
                console.error('Error in search field fill: ' + e.message);
                console.error('Stack: ' + e.stack);
                return false;
            }
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

    //endregion

    //region **REFRESH_INDICATOR**

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

            val currentUrl = webView.url ?: ""
            if (currentUrl.contains("/message/index.php")) {
                isAtTop = false
                if (extendedHeaderLayout.isVisible) {
                    hideExtendedHeaderWithAnimation()
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

    //endregion

    //region **PDF_VIEWER**

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
        textViewerContainer.visibility = View.GONE
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
        if (!isAdded || context == null) {
            return
        }

        pdfScrollContainer.viewTreeObserver.addOnScrollChangedListener {
            if (isAdded && context != null) {
                updateCurrentPageFromScroll()
            }
        }
        updatePdfControls()
    }

    private fun updateCurrentPageFromScroll() {
        if (!isAdded || context == null) {
            return
        }

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
            if (!isAdded || context == null || ignoreScrollUpdates) {
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
                if (!isAdded || context == null) {
                    return@Runnable
                }
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
        // zoom state tracking
        var startX = 0f
        var startY = 0f
        var hasMoved = false
        var initialPointerCount = 0
        var lastTapTime = 0L
        var tapCount = 0

        // zoom specific state
        var zoomStartDistance: Float
        var isMultiTouchZoom = false
        var lastZoomDistance = 0f
        var zoomVelocity = 0f
        var lastZoomTime = 0L
        val ZOOM_SMOOTHING_FACTOR = 0.15f // lower -> smoother but slower response
        val MIN_ZOOM_VELOCITY = 0.001f // min velocity to register zoom

        // swipe state
        var isPanning = false
        var lastPanX = 0f
        var lastPanY = 0f

        val swipeThreshold = 100f
        val tapMovementThreshold = 30f
        val doubleTapTimeout = 300L
        val zoomThreshold = 10f

        pdfSinglePageView.setOnTouchListener { view, event ->
            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    pdfSwipeStartX = event.x
                    isPdfSwiping = false
                    isPanning = false
                    hasMoved = false
                    initialPointerCount = 1
                    lastPanX = event.rawX
                    lastPanY = event.rawY
                    isCurrentlyZooming = false
                    isMultiTouchZoom = false
                    zoomVelocity = 0f
                    true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount == 2) {
                        isMultiTouchZoom = true
                        isCurrentlyZooming = true
                        initialPointerCount = 2

                        pdfSinglePageView.animate().cancel()

                        val x0 = event.getX(0)
                        val y0 = event.getY(0)
                        val x1 = event.getX(1)
                        val y1 = event.getY(1)

                        zoomStartDistance = sqrt((x1 - x0).pow(2) + (y1 - y0).pow(2))
                        lastZoomDistance = zoomStartDistance
                        lastZoomTime = System.currentTimeMillis()
                        zoomVelocity = 0f

                        isPdfSwiping = false
                        hasMoved = false
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isMultiTouchZoom && event.pointerCount >= 2) {
                        val x0 = event.getX(0)
                        val y0 = event.getY(0)
                        val x1 = event.getX(1)
                        val y1 = event.getY(1)

                        val currentDistance = sqrt((x1 - x0).pow(2) + (y1 - y0).pow(2))
                        val distanceDelta = currentDistance - lastZoomDistance

                        if (abs(distanceDelta) > zoomThreshold) {
                            val currentTime = System.currentTimeMillis()
                            val timeDelta = (currentTime - lastZoomTime).coerceAtLeast(1)

                            val rawRatio = currentDistance / lastZoomDistance

                            val newVelocity = (rawRatio - 1f) / timeDelta

                            zoomVelocity = if (abs(newVelocity) > MIN_ZOOM_VELOCITY) {
                                zoomVelocity * (1f - ZOOM_SMOOTHING_FACTOR) + newVelocity * ZOOM_SMOOTHING_FACTOR
                            } else {
                                zoomVelocity * 0.8f
                            }

                            val smoothedRatio = 1f + (zoomVelocity * timeDelta).coerceIn(-0.1f, 0.1f)
                            val newScale = (pdfScaleFactor * smoothedRatio).coerceIn(MIN_ZOOM, MAX_ZOOM)

                            if (abs(newScale - pdfScaleFactor) > 0.01f) {
                                pdfScaleFactor = newScale
                                pdfSinglePageView.scaleX = pdfScaleFactor
                                pdfSinglePageView.scaleY = pdfScaleFactor

                                lastZoomDistance = currentDistance
                                lastZoomTime = currentTime

                                val centerX = (x0 + x1) / 2f
                                val centerY = (y0 + y1) / 2f

                                if (pdfScaleFactor > 1.2f) {
                                    val containerWidth = (pdfSinglePageView.parent as View).width.toFloat()
                                    val containerHeight = (pdfSinglePageView.parent as View).height.toFloat()

                                    val panX = (centerX - containerWidth / 2f) * 0.3f
                                    val panY = (centerY - containerHeight / 2f) * 0.3f

                                    pdfSinglePageView.translationX = panX
                                    pdfSinglePageView.translationY = panY

                                    constrainTranslation()
                                }
                            }
                        }
                        return@setOnTouchListener true
                    }

                    if (!isMultiTouchZoom && event.pointerCount == 1 && !isCurrentlyZooming) {
                        val deltaX = event.x - startX
                        val deltaY = event.y - startY
                        val totalMovement = sqrt(deltaX * deltaX + deltaY * deltaY)

                        if (totalMovement > tapMovementThreshold) {
                            tapCount = 0
                        }

                        if (pdfScaleFactor > 1.05f) {
                            if (!isPanning) {
                                isPanning = true
                            }

                            val panDeltaX = event.rawX - lastPanX
                            val panDeltaY = event.rawY - lastPanY

                            lastPanX = event.rawX
                            lastPanY = event.rawY

                            pdfSinglePageView.translationX += panDeltaX
                            pdfSinglePageView.translationY += panDeltaY

                            constrainTranslation()

                            hasMoved = true
                            return@setOnTouchListener true
                        }

                        // swiping (next/previous page)
                        if (!isPanning && pdfScaleFactor <= 1.05f && abs(deltaX) > swipeThreshold && abs(deltaX) > abs(deltaY) * 1.5f) {
                            hasMoved = true
                            isPdfSwiping = true
                            val currentPage = pdfViewerManager?.getCurrentPage() ?: 0
                            val pageCount = pdfViewerManager?.getPageCount() ?: 0

                            val progress = min(abs(deltaX) / 250f, 1f)
                            pdfSwipeProgress = progress

                            if (deltaX > 0 && currentPage > 0) {
                                showPdfSwipeIndicator(currentPage - 1, true, progress)
                            } else if (deltaX < 0 && currentPage < pageCount - 1) {
                                showPdfSwipeIndicator(currentPage + 1, false, progress)
                            }
                        }
                    }
                    true
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.pointerCount == 2) {
                        isMultiTouchZoom = false
                        isCurrentlyZooming = false
                        zoomVelocity = 0f

                        if (pdfScaleFactor in 0.95f..1.05f) {
                            animateToScale(1f)
                        } else {
                            constrainTranslation()
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val upTime = System.currentTimeMillis()
                    val deltaX = event.x - startX
                    val deltaY = event.y - startY
                    val totalMovement = sqrt(deltaX * deltaX + deltaY * deltaY)

                    if (!hasMoved && totalMovement < tapMovementThreshold && initialPointerCount == 1 && !isCurrentlyZooming && !isMultiTouchZoom) {
                        if (upTime - lastTapTime < doubleTapTimeout) {
                            tapCount++
                            if (tapCount >= 2) {
                                if (pdfScaleFactor > 1.05f) {
                                    animateZoomOut()
                                }
                                tapCount = 0
                                lastTapTime = 0
                                hidePdfSwipeIndicator()
                                isPdfSwiping = false
                                isPanning = false
                                return@setOnTouchListener true
                            }
                        } else {
                            tapCount = 1
                        }
                        lastTapTime = upTime
                    }

                    if (isPanning) {
                        constrainTranslation()
                        isPanning = false
                    } else if (!isCurrentlyZooming && !isMultiTouchZoom && pdfScaleFactor <= 1.05f && hasMoved && isPdfSwiping && abs(deltaX) > swipeThreshold) {
                        if (deltaX > 0) {
                            val currentPage = pdfViewerManager?.getCurrentPage() ?: 0
                            if (currentPage > 0) {
                                pdfViewerManager?.setCurrentPage(currentPage - 1)
                                renderSinglePdfPage(currentPage - 1)
                                updatePdfControls()
                            }
                        } else {
                            val currentPage = pdfViewerManager?.getCurrentPage() ?: 0
                            val pageCount = pdfViewerManager?.getPageCount() ?: 0
                            if (currentPage < pageCount - 1) {
                                pdfViewerManager?.setCurrentPage(currentPage + 1)
                                renderSinglePdfPage(currentPage + 1)
                                updatePdfControls()
                            }
                        }
                    }

                    hidePdfSwipeIndicator()
                    isPdfSwiping = false
                    pdfSwipeProgress = 0f
                    hasMoved = false
                    initialPointerCount = 0
                    isMultiTouchZoom = false
                    isCurrentlyZooming = false
                    true
                }

                else -> false
            }
        }
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

        val cleanFileName = fileName.removeSuffix(".pdf")

        val pdfTab = TabInfo(
            id = "$PDF_TAB_PREFIX${System.currentTimeMillis()}",
            url = pdfUrl,
            title = cleanFileName,
            isPinned = false,
            isDefault = false,
            thumbnail = null,
            webViewState = Bundle().apply {
                putString("pdf_file_path", permanentPdfFile.absolutePath)
                putString("pdf_url", pdfUrl)
                putInt("pdf_current_page", 0)
                putBoolean("pdf_scroll_mode", true)
                putBoolean("pdf_dark_mode", isDarkTheme)
            }
        )
        tabs.add(pdfTab)
        currentTabIndex = tabs.size - 1

        pdfFileUrl = pdfUrl

        loadPdfTab(pdfTab)

        isTextViewerMode = false
        textViewerManager = null

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

                setBackgroundColor(Color.LTGRAY)
                minimumHeight = (800 * resources.displayMetrics.density).toInt()
            }

            if (page < 3) {
                val bitmap = pdfViewerManager?.renderPage(page)
                if (bitmap != null && !bitmap.isRecycled) {
                    imageView.setImageBitmap(bitmap)
                    imageView.setBackgroundColor(Color.TRANSPARENT)
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
                                imageView.setBackgroundColor(Color.TRANSPARENT)
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

            val drawable = imageView.drawable as? BitmapDrawable
            val bitmap = drawable?.bitmap

            imageView.setImageDrawable(null)

            if (bitmap != null && !bitmap.isRecycled) {
                try {
                    bitmap.recycle()
                } catch (e: Exception) {
                    L.e("MoodleFragment", "Error recycling bitmap", e)
                }
            }

            imageView.setBackgroundColor(Color.LTGRAY)

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

        pdfScaleFactor = 1f
        lastAppliedScale = 1f
        isCurrentlyZooming = false

        pdfSinglePageView.scaleX = 1f
        pdfSinglePageView.scaleY = 1f
        pdfSinglePageView.translationX = 0f
        pdfSinglePageView.translationY = 0f

        zoomStabilizeRunnable?.let { zoomHandler?.removeCallbacks(it) }
    }

    private fun updatePdfControls() {
        if (!isAdded || context == null) {
            return
        }

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
                        imageView.setBackgroundColor(Color.LTGRAY)

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
                        object : ViewTreeObserver.OnGlobalLayoutListener {
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
                                    imageView.setBackgroundColor(Color.TRANSPARENT)

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
                        imageView.setBackgroundColor(Color.TRANSPARENT)
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
        val pdfState = pdfViewerManager?.getCurrentState()
        val pdfFile = pdfState?.file

        if (pdfFile != null && pdfFile.exists()) {
            try {
                val pdfUrl = tabs.getOrNull(currentTabIndex)?.url ?: ""
                val originalFileName = try {
                    URLDecoder.decode(pdfUrl.substringAfterLast("/").substringBefore("?"), "UTF-8")
                } catch (_: Exception) {
                    "document.pdf"
                }.removeSuffix(".pdf")

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "${originalFileName}_$timestamp.pdf"

                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val destFile = File(downloadsDir, fileName)

                pdfFile.copyTo(destFile, overwrite = true)

                Toast.makeText(requireContext(), getString(R.string.moodle_pdf_downloaded), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                L.e("MoodleFragment", "Error downloading PDF", e)
                Toast.makeText(requireContext(), getString(R.string.moodle_pdf_download_failed), Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(requireContext(), getString(R.string.moodle_pdf_download_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareCurrentPdf() {
        val pdfState = pdfViewerManager?.getCurrentState()
        val pdfFile = pdfState?.file

        if (pdfFile != null && pdfFile.exists()) {
            val pdfUrl = tabs.getOrNull(currentTabIndex)?.url ?: ""
            val originalFileName = try {
                URLDecoder.decode(pdfUrl.substringAfterLast("/").substringBefore("?"), "UTF-8")
            } catch (_: Exception) {
                "document.pdf"
            }.removeSuffix(".pdf")

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "${originalFileName}_$timestamp.pdf"

            val cacheDir = requireContext().cacheDir
            val shareFile = File(cacheDir, fileName)
            pdfFile.copyTo(shareFile, overwrite = true)

            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                shareFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

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

            (imageView.drawable as? BitmapDrawable)?.bitmap?.recycle()

            imageView.setImageDrawable(null)
            imageView.setBackgroundColor(Color.LTGRAY)
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
                    imageView.setBackgroundColor(Color.TRANSPARENT)
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
                        imageView.setBackgroundColor(Color.TRANSPARENT)
                        imageView.minimumHeight = 0
                    } catch (e: Exception) {
                        L.e("MoodleFragment", "Bitmap was recycled while setting on ImageView", e)
                        imageView.setImageDrawable(null)
                        imageView.setBackgroundColor(Color.LTGRAY)
                        imageView.minimumHeight = (800 * resources.displayMetrics.density).toInt()
                    }
                }
            }
        }
    }

    private fun showPdfOptionsMenu() {
        val popup = PopupMenu(requireContext(), pdfKebabMenu)

        if (isTextViewerMode) {
            popup.menu.add(0, 1, 0, getString(R.string.text_viewer_switch_to_pdf)).setIcon(R.drawable.ic_pdf_viewer)
            popup.menu.add(0, 2, 0, getString(R.string.text_viewer_search)).setIcon(R.drawable.ic_search)
            popup.menu.add(0, 3, 0, getString(R.string.text_viewer_preferences)).setIcon(R.drawable.ic_gear)

            val darkModeText = if (textViewerManager?.getBackgroundColor() == Color.rgb(30, 30, 30)) {
                getString(R.string.moodle_pdf_disable_dark_mode)
            } else {
                getString(R.string.moodle_pdf_enable_dark_mode)
            }
            popup.menu.add(0, 4, 0, darkModeText).setIcon(R.drawable.ic_dark_mode)
        } else {
            popup.menu.add(0, 1, 0, getString(R.string.text_viewer_switch_to_text)).setIcon(R.drawable.ic_text_viewer)
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
        }

        try {
            val fieldMPopup = PopupMenu::class.java.getDeclaredField("mPopup")
            fieldMPopup.isAccessible = true
            val mPopup = fieldMPopup.get(popup)
            mPopup.javaClass.getDeclaredMethod("setForceShowIcon", Boolean::class.java).invoke(mPopup, true)
        } catch (_: Exception) { }

        popup.setOnMenuItemClickListener { item ->
            if (isTextViewerMode) {
                when (item.itemId) {
                    1 -> switchToPdfViewer()
                    2 -> showTextSearchDialog()
                    3 -> showTextViewerPreferences()
                    4 -> toggleTextViewerDarkMode()
                }
            } else {
                when (item.itemId) {
                    1 -> switchToTextViewer()
                    2 -> copyAllPdfText()
                    3 -> downloadCurrentPdf()
                    4 -> shareCurrentPdf()
                    5 -> togglePdfDarkMode()
                }
            }
            true
        }
        popup.show()
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

    private fun createPdfSwipeIndicator() {
        pdfSwipeIndicator?.let {
            (it.parent as? ViewGroup)?.removeView(it)
        }

        val indicator = TextView(requireContext()).apply {
            textSize = 16f
            setTextColor(requireContext().getThemeColor(R.attr.settingsColorPrimary))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(32, 16, 32, 16)
            alpha = 0f
            visibility = View.INVISIBLE
            elevation = 30f
            isClickable = false
            isFocusable = false
            id = View.generateViewId()

            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(requireContext().getThemeColor(R.attr.cardBackgroundColor))
                cornerRadius = 28f
                setStroke(3, requireContext().getThemeColor(R.attr.settingsColorPrimary))
            }

            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            )
        }

        pdfSinglePageContainer.addView(indicator)
        pdfSwipeIndicator = indicator
    }

    private fun showPdfSwipeIndicator(targetPage: Int, isRightSwipe: Boolean, progress: Float) {
        if (pdfSwipeIndicator == null) {
            createPdfSwipeIndicator()
        }

        pdfSwipeIndicator?.let { indicator ->
            val pageCount = pdfViewerManager?.getPageCount() ?: 0

            if (targetPage < 0 || targetPage >= pageCount) {
                hidePdfSwipeIndicator()
                return
            }

            val scaledProgress = min(progress, 1f)
            val targetAlpha = min(scaledProgress * 1.2f, 0.95f)

            indicator.text = getString(R.string.moodle_pdf_page_indicator, targetPage + 1, pageCount)
            indicator.alpha = targetAlpha
            indicator.visibility = View.VISIBLE

            indicator.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )

            val containerWidth = pdfSinglePageContainer.width
            val containerHeight = pdfSinglePageContainer.height

            if (containerWidth == 0 || containerHeight == 0) {
                pdfSinglePageContainer.post {
                    showPdfSwipeIndicator(targetPage, isRightSwipe, progress)
                }
                return
            }

            val layoutParams = indicator.layoutParams as ConstraintLayout.LayoutParams

            layoutParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            layoutParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            layoutParams.topMargin = 0
            layoutParams.bottomMargin = 0

            val horizontalMargin = 60

            if (isRightSwipe) {
                layoutParams.leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
                layoutParams.rightToRight = ConstraintLayout.LayoutParams.UNSET
                layoutParams.leftMargin = horizontalMargin
                layoutParams.rightMargin = 0
            } else {
                layoutParams.rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
                layoutParams.leftToLeft = ConstraintLayout.LayoutParams.UNSET
                layoutParams.rightMargin = horizontalMargin
                layoutParams.leftMargin = 0
            }

            indicator.layoutParams = layoutParams

            val scale = 0.7f + (scaledProgress * 0.3f)
            indicator.scaleX = scale
            indicator.scaleY = scale

            val rotation = if (isRightSwipe) -scaledProgress * 3f else scaledProgress * 3f
            indicator.rotation = rotation
        }
    }

    private fun hidePdfSwipeIndicator() {
        pdfSwipeIndicator?.let { indicator ->
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

    private fun constrainTranslation() {
        val imageWidth = pdfSinglePageView.width * pdfScaleFactor
        val imageHeight = pdfSinglePageView.height * pdfScaleFactor
        val containerWidth = (pdfSinglePageView.parent as View).width.toFloat()
        val containerHeight = (pdfSinglePageView.parent as View).height.toFloat()

        val maxTransX = max(0f, (imageWidth - containerWidth) / 2f)
        val maxTransY = max(0f, (imageHeight - containerHeight) / 2f)

        pdfSinglePageView.translationX = pdfSinglePageView.translationX.coerceIn(-maxTransX, maxTransX)
        pdfSinglePageView.translationY = pdfSinglePageView.translationY.coerceIn(-maxTransY, maxTransY)
    }

    private fun animateToScale(targetScale: Float) {
        val startScale = pdfScaleFactor
        val startTransX = pdfSinglePageView.translationX
        val startTransY = pdfSinglePageView.translationY

        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 200
        animator.interpolator = DecelerateInterpolator()

        animator.addUpdateListener { animation ->
            val progress = animation.animatedValue as Float
            val currentScale = startScale + (targetScale - startScale) * progress

            pdfScaleFactor = currentScale
            pdfSinglePageView.scaleX = currentScale
            pdfSinglePageView.scaleY = currentScale

            pdfSinglePageView.translationX = startTransX * (1f - progress)
            pdfSinglePageView.translationY = startTransY * (1f - progress)
        }

        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                pdfScaleFactor = targetScale
                lastAppliedScale = targetScale
                pdfSinglePageView.scaleX = targetScale
                pdfSinglePageView.scaleY = targetScale
                pdfSinglePageView.translationX = 0f
                pdfSinglePageView.translationY = 0f
                isCurrentlyZooming = false
            }
        })

        animator.start()
    }

    private fun animateZoomOut() {
        animateToScale(1f)
    }

    //endregion

    //region **TEXT_VIEWER**

    private fun toggleTextViewerDarkMode() {
        val currentDarkMode = textViewerManager?.getDarkMode() ?: isDarkTheme
        val newDarkMode = !currentDarkMode

        textViewerManager?.setDarkMode(newDarkMode)

        sharedPrefs.edit {
            putBoolean("text_viewer_dark_mode", newDarkMode)
        }

        textViewerManager?.let { manager ->
            applyTextViewerPreferences()
            val displayWidth = textContentScrollView.width
            val formattedText = manager.getFormattedText(displayWidth)
            textViewerContent.text = formattedText
            generateLineNumbers()

            val bgColor = manager.getBackgroundColor()
            textViewerContainer.setBackgroundColor(bgColor)
        }
    }

    private fun switchToTextViewer() {
        val pdfState = pdfViewerManager?.getCurrentState()
        val pdfFile = pdfState?.file

        if (pdfFile == null || !pdfFile.exists()) {
            Toast.makeText(requireContext(), getString(R.string.text_viewer_no_pdf), Toast.LENGTH_SHORT).show()
            return
        }

        val loadingDialog = AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.text_viewer_loading))
            .setCancelable(false)
            .show()

        backgroundExecutor.execute {
            try {
                textViewerManager = TextViewerManager(requireContext())

                val savedDarkMode = sharedPrefs.getBoolean("text_viewer_dark_mode",
                    pdfViewerManager?.forceDarkMode ?: isDarkTheme)

                if (textViewerManager?.parsePdfToText(pdfFile, savedDarkMode) == true) {
                    activity?.runOnUiThread {
                        loadingDialog.dismiss()
                        saveOriginalBackground()
                        displayTextViewer()
                    }
                } else {
                    activity?.runOnUiThread {
                        loadingDialog.dismiss()
                        Toast.makeText(requireContext(), getString(R.string.text_viewer_parse_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                L.e("MoodleFragment", "Error switching to text viewer", e)
                activity?.runOnUiThread {
                    loadingDialog.dismiss()
                    Toast.makeText(requireContext(), getString(R.string.text_viewer_error), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveOriginalBackground() {
        originalBackgroundColor = (view?.findViewById<View>(android.R.id.content)?.background as? ColorDrawable)?.color
            ?: requireContext().getThemeColor(R.attr.homeworkFragmentBackgroundColor)
    }

    private fun displayTextViewer() {
        isTextViewerMode = true

        pdfContainer.visibility = View.GONE
        textViewerContainer.visibility = View.VISIBLE

        applyTextViewerPreferences()

        val displayWidth = textContentScrollView.width
        val formattedText = textViewerManager?.getFormattedText(displayWidth) ?: SpannableStringBuilder("")
        textViewerContent.text = formattedText

        generateLineNumbers()

        textContentScrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            lineNumberScrollView.scrollTo(0, scrollY)
        }

        updatePdfControls()
    }

    private fun generateLineNumbers() {
        lineNumbersContainer.removeAllViews()
        val totalLines = textViewerManager?.getTotalLines() ?: 0
        val fontSize = textViewerManager?.getFontSize() ?: 14
        val lineHeight = (fontSize * 1.2f * resources.displayMetrics.density).toInt()

        for (i in 1..totalLines) {
            val lineNumber = TextView(requireContext()).apply {
                text = i.toString()
                textSize = (fontSize - 2).toFloat()
                setTextColor(requireContext().getThemeColor(R.attr.textSecondaryColor))
                alpha = 0.6f
                gravity = Gravity.END
                setPadding(4, 0, 8, 0)
                minWidth = (40 * resources.displayMetrics.density).toInt()
                height = lineHeight
            }
            lineNumbersContainer.addView(lineNumber)
        }
    }

    private fun applyTextViewerPreferences() {
        textViewerManager?.let { manager ->
            val fontSize = manager.getFontSize()
            val fontColor = manager.getFontColor()
            val bgColor = manager.getBackgroundColor()
            val fontFamily = manager.getFontFamily()

            textViewerContent.textSize = fontSize.toFloat()
            textViewerContent.setTextColor(fontColor)
            textViewerContent.typeface = fontFamily
            textViewerContent.setBackgroundColor(bgColor)

            lineNumbersContainer.setBackgroundColor(bgColor)
            for (i in 0 until lineNumbersContainer.childCount) {
                val lineNumberView = lineNumbersContainer.getChildAt(i) as? TextView
                lineNumberView?.apply {
                    textSize = (fontSize - 2).toFloat()
                    setTextColor(fontColor)
                    alpha = 0.6f
                }
            }

            textViewerContainer.setBackgroundColor(bgColor)
            textContentScrollView.setBackgroundColor(bgColor)
            lineNumberScrollView.setBackgroundColor(bgColor)
        }
    }

    private fun switchToPdfViewer() {
        isTextViewerMode = false
        textViewerContainer.visibility = View.GONE
        pdfContainer.visibility = View.VISIBLE

        val textViewerDarkMode = textViewerManager?.getDarkMode() ?: isDarkTheme
        val pdfDarkMode = pdfViewerManager?.forceDarkMode ?: isDarkTheme

        sharedPrefs.edit {
            putBoolean("text_viewer_dark_mode", textViewerDarkMode)
        }

        textViewerManager = null

        if (pdfDarkMode != textViewerDarkMode) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (pdfViewerManager?.forceDarkMode != textViewerDarkMode) {
                    togglePdfDarkMode()
                }
            }, 100)
        }

        updatePdfControls()
    }

    private fun showTextSearchDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_text_search, null)
        val etSearchQuery = dialogView.findViewById<EditText>(R.id.etSearchQuery)
        val btnClearSearchQuery = dialogView.findViewById<ImageButton>(R.id.btnClearSearchQuery)
        val tvSearchResults = dialogView.findViewById<TextView>(R.id.tvSearchResults)
        val btnPreviousResult = dialogView.findViewById<ImageButton>(R.id.btnPreviousResult)
        val btnNextResult = dialogView.findViewById<ImageButton>(R.id.btnNextResult)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.text_viewer_search))
            .setView(dialogView)
            .setNegativeButton(getString(R.string.moodle_close), null)
            .create()

        btnClearSearchQuery.setOnClickListener {
            etSearchQuery.setText("")
            searchResults = emptyList()
            currentSearchIndex = 0
            tvSearchResults.text = getString(R.string.text_viewer_no_results)
            btnPreviousResult.isEnabled = false
            btnNextResult.isEnabled = false
        }

        etSearchQuery.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString()
                if (query.isNotEmpty()) {
                    searchResults = textViewerManager?.searchText(query) ?: emptyList()
                    currentSearchIndex = if (searchResults.isNotEmpty()) 0 else -1
                    updateSearchResults(tvSearchResults, btnPreviousResult, btnNextResult)
                    if (searchResults.isNotEmpty()) {
                        highlightSearchResult(searchResults[0])
                    }
                } else {
                    searchResults = emptyList()
                    currentSearchIndex = 0
                    tvSearchResults.text = getString(R.string.text_viewer_no_results)
                    btnPreviousResult.isEnabled = false
                    btnNextResult.isEnabled = false
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnPreviousResult.setOnClickListener {
            if (currentSearchIndex > 0) {
                currentSearchIndex--
                updateSearchResults(tvSearchResults, btnPreviousResult, btnNextResult)
                highlightSearchResult(searchResults[currentSearchIndex])
            }
        }

        btnNextResult.setOnClickListener {
            if (currentSearchIndex < searchResults.size - 1) {
                currentSearchIndex++
                updateSearchResults(tvSearchResults, btnPreviousResult, btnNextResult)
                highlightSearchResult(searchResults[currentSearchIndex])
            }
        }

        textSearchDialog = dialog
        dialog.show()

        val buttonColor = requireContext().getThemeColor(R.attr.dialogSectionButtonColor)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
    }

    private fun updateSearchResults(tvResults: TextView, btnPrev: ImageButton, btnNext: ImageButton) {
        if (searchResults.isEmpty()) {
            tvResults.text = getString(R.string.text_viewer_no_results)
            btnPrev.isEnabled = false
            btnNext.isEnabled = false
        } else {
            tvResults.text = getString(R.string.text_viewer_search_results, currentSearchIndex + 1, searchResults.size)
            btnPrev.isEnabled = currentSearchIndex > 0
            btnNext.isEnabled = currentSearchIndex < searchResults.size - 1
        }
    }

    private fun highlightSearchResult(position: Int) {
        textContentScrollView.post {
            val layout = textViewerContent.layout
            if (layout != null) {
                val line = layout.getLineForOffset(position)
                val y = layout.getLineTop(line)
                textContentScrollView.smoothScrollTo(0, y - 100)
            }
        }
    }

    private fun showTextViewerPreferences() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_text_viewer_preferences, null)
        val tvPreviewText = dialogView.findViewById<TextView>(R.id.tvPreviewText)
        val seekBarFontSize = dialogView.findViewById<SeekBar>(R.id.seekBarFontSize)
        val tvFontSizeValue = dialogView.findViewById<TextView>(R.id.tvFontSizeValue)
        val spinnerFontColor = dialogView.findViewById<Spinner>(R.id.spinnerFontColor)
        val spinnerBgColor = dialogView.findViewById<Spinner>(R.id.spinnerBgColor)
        val spinnerFontFamily = dialogView.findViewById<Spinner>(R.id.spinnerFontFamily)
        val checkBoxDisableFormatting = dialogView.findViewById<CheckBox>(R.id.checkBoxDisableFormatting)

        val currentFontSize = sharedPrefs.getInt("text_viewer_font_size", 14)
        val currentDarkMode = textViewerManager?.getDarkMode() ?: isDarkTheme
        val fontColorKey = if (currentDarkMode) "text_viewer_font_color_dark" else "text_viewer_font_color_light"
        val bgColorKey = if (currentDarkMode) "text_viewer_bg_color_dark" else "text_viewer_bg_color_light"
        val currentFontFamily = sharedPrefs.getString("text_viewer_font_family", "monospace") ?: "monospace"
        val disableFormatting = sharedPrefs.getBoolean("text_viewer_disable_formatting", false)

        val currentFontColorIndex = sharedPrefs.getInt("${fontColorKey}_index", 0)
        val currentBgColorIndex = sharedPrefs.getInt("${bgColorKey}_index", 0)

        seekBarFontSize.progress = currentFontSize - 10
        "${currentFontSize}sp".also { tvFontSizeValue.text = it }

        val fontColorOptions = if (currentDarkMode) {
            arrayOf(
                getString(R.string.text_viewer_color_light_gray),
                getString(R.string.text_viewer_color_white),
                getString(R.string.text_viewer_color_light_blue),
                getString(R.string.text_viewer_color_light_green)
            )
        } else {
            arrayOf(
                getString(R.string.text_viewer_color_black),
                getString(R.string.text_viewer_color_dark_gray),
                getString(R.string.text_viewer_color_dark_blue),
                getString(R.string.text_viewer_color_dark_green)
            )
        }

        val bgColorOptions = if (currentDarkMode) {
            arrayOf(
                getString(R.string.text_viewer_bg_dark),
                getString(R.string.text_viewer_bg_darker),
                getString(R.string.text_viewer_bg_black)
            )
        } else {
            arrayOf(
                getString(R.string.text_viewer_bg_white),
                getString(R.string.text_viewer_bg_light_gray),
                getString(R.string.text_viewer_bg_beige)
            )
        }

        val fontColorAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, fontColorOptions)
        fontColorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFontColor.adapter = fontColorAdapter
        spinnerFontColor.setSelection(currentFontColorIndex)

        val bgColorAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, bgColorOptions)
        bgColorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerBgColor.adapter = bgColorAdapter
        spinnerBgColor.setSelection(currentBgColorIndex)

        val fontFamilies = arrayOf("Monospace", "Serif", "Sans-serif")
        val fontAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, fontFamilies)
        fontAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFontFamily.adapter = fontAdapter
        spinnerFontFamily.setSelection(when (currentFontFamily) {
            "serif" -> 1
            "sans-serif" -> 2
            else -> 0
        })

        checkBoxDisableFormatting.isChecked = disableFormatting

        val createPreview: (Boolean) -> SpannableStringBuilder = { disableFormat ->
            val previewSpannable = SpannableStringBuilder()

            val titleText = getString(R.string.text_viewer_preview_title)
            val normalText = getString(R.string.text_viewer_preview_normal)
            val boldText = getString(R.string.text_viewer_preview_bold)
            val italicText = getString(R.string.text_viewer_preview_italic)
            val underlineText = getString(R.string.text_viewer_preview_underline)
            val strikeText = getString(R.string.text_viewer_preview_strike)

            if (!disableFormat) {
                // title
                val titleStart = previewSpannable.length
                previewSpannable.append(titleText).append("\n")
                previewSpannable.setSpan(StyleSpan(Typeface.BOLD), titleStart, titleStart + titleText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                previewSpannable.setSpan(RelativeSizeSpan(1.4f), titleStart, titleStart + titleText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                // normal text
                previewSpannable.append(normalText).append(" ")

                // bold
                val boldStart = previewSpannable.length
                previewSpannable.append(boldText)
                previewSpannable.setSpan(StyleSpan(Typeface.BOLD), boldStart, previewSpannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                previewSpannable.append(" ")

                // italic
                val italicStart = previewSpannable.length
                previewSpannable.append(italicText)
                previewSpannable.setSpan(StyleSpan(Typeface.ITALIC), italicStart, previewSpannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                previewSpannable.append(" ")

                // underline
                val underlineStart = previewSpannable.length
                previewSpannable.append(underlineText)
                previewSpannable.setSpan(UnderlineSpan(), underlineStart, previewSpannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                previewSpannable.append(".\n\n")

                // strikethrough
                val strikeStart = previewSpannable.length
                previewSpannable.append(strikeText)
                previewSpannable.setSpan(StrikethroughSpan(), strikeStart, previewSpannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                previewSpannable.append(titleText).append("\n")
                previewSpannable.append(normalText).append(" ")
                previewSpannable.append(boldText).append(" ")
                previewSpannable.append(italicText).append(" ")
                previewSpannable.append(underlineText).append(".\n\n")
                previewSpannable.append(strikeText)
            }

            previewSpannable
        }

        tvPreviewText.text = createPreview(disableFormatting)

        val updatePreview = {
            val previewFontSize = seekBarFontSize.progress + 10
            tvPreviewText.textSize = previewFontSize.toFloat()

            val selectedFontColor = when (spinnerFontColor.selectedItemPosition) {
                0 -> if (currentDarkMode) Color.LTGRAY else Color.BLACK
                1 -> if (currentDarkMode) Color.WHITE else Color.DKGRAY
                2 -> if (currentDarkMode) Color.rgb(100, 181, 246) else Color.rgb(13, 71, 161)
                3 -> if (currentDarkMode) Color.rgb(129, 199, 132) else Color.rgb(27, 94, 32)
                else -> if (currentDarkMode) Color.LTGRAY else Color.BLACK
            }
            tvPreviewText.setTextColor(selectedFontColor)

            val selectedBgColor = when (spinnerBgColor.selectedItemPosition) {
                0 -> if (currentDarkMode) Color.rgb(30, 30, 30) else Color.WHITE
                1 -> if (currentDarkMode) Color.rgb(20, 20, 20) else Color.rgb(245, 245, 245)
                2 -> if (currentDarkMode) Color.BLACK else Color.rgb(255, 248, 220)
                else -> if (currentDarkMode) Color.rgb(30, 30, 30) else Color.WHITE
            }
            tvPreviewText.setBackgroundColor(selectedBgColor)

            tvPreviewText.typeface = when (spinnerFontFamily.selectedItemPosition) {
                1 -> Typeface.SERIF
                2 -> Typeface.SANS_SERIF
                else -> Typeface.MONOSPACE
            }

            tvPreviewText.text = createPreview(checkBoxDisableFormatting.isChecked)
        }

        seekBarFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = progress + 10
                "${size}sp".also { tvFontSizeValue.text = it }
                updatePreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        spinnerFontColor.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updatePreview()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerBgColor.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updatePreview()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerFontFamily.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updatePreview()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        checkBoxDisableFormatting.setOnCheckedChangeListener { _, _ ->
            updatePreview()
        }

        updatePreview()

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.text_viewer_preferences))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.moodle_save)) { _, _ ->
                val newFontSize = seekBarFontSize.progress + 10
                val newFontFamily = when (spinnerFontFamily.selectedItemPosition) {
                    1 -> "serif"
                    2 -> "sans-serif"
                    else -> "monospace"
                }

                val newFontColor = when (spinnerFontColor.selectedItemPosition) {
                    0 -> if (currentDarkMode) Color.LTGRAY else Color.BLACK
                    1 -> if (currentDarkMode) Color.WHITE else Color.DKGRAY
                    2 -> if (currentDarkMode) Color.rgb(100, 181, 246) else Color.rgb(13, 71, 161)
                    3 -> if (currentDarkMode) Color.rgb(129, 199, 132) else Color.rgb(27, 94, 32)
                    else -> if (currentDarkMode) Color.LTGRAY else Color.BLACK
                }

                val newBgColor = when (spinnerBgColor.selectedItemPosition) {
                    0 -> if (currentDarkMode) Color.rgb(30, 30, 30) else Color.WHITE
                    1 -> if (currentDarkMode) Color.rgb(20, 20, 20) else Color.rgb(245, 245, 245)
                    2 -> if (currentDarkMode) Color.BLACK else Color.rgb(255, 248, 220)
                    else -> if (currentDarkMode) Color.rgb(30, 30, 30) else Color.WHITE
                }

                sharedPrefs.edit {
                    putInt("text_viewer_font_size", newFontSize)
                    putInt(fontColorKey, newFontColor)
                    putInt(bgColorKey, newBgColor)
                    putString("text_viewer_font_family", newFontFamily)
                    putBoolean("text_viewer_disable_formatting", checkBoxDisableFormatting.isChecked)
                    putInt("${fontColorKey}_index", spinnerFontColor.selectedItemPosition)
                    putInt("${bgColorKey}_index", spinnerBgColor.selectedItemPosition)
                    apply()
                }

                textViewerManager?.setDarkMode(currentDarkMode)
                applyTextViewerPreferences()

                val displayWidth = textContentScrollView.width
                val formattedText = textViewerManager?.getFormattedText(displayWidth) ?: SpannableStringBuilder("")
                textViewerContent.text = formattedText
                generateLineNumbers()

                val bgColor = textViewerManager?.getBackgroundColor() ?: Color.WHITE
                textViewerContainer.setBackgroundColor(bgColor)
            }
            .setNegativeButton(getString(R.string.moodle_cancel), null)
            .create()

        dialog.show()

        val buttonColor = requireContext().getThemeColor(R.attr.dialogSectionButtonColor)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
    }

    //endregion

    //region **MOODLE_FETCH**

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
        }, 2500)
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

    //endregion

    //region **DOCX_CONVERT**

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
            val pdfDocument = Document(PageSize.A4, 50f, 100f, 50f, 100f)
            val pdfWriter = PdfWriter.getInstance(pdfDocument, outputStream)

            pdfDocument.open()

            val docxData = parseDocxStructure(docxBytes)

            if (docxData.headers.isNotEmpty() || docxData.footers.isNotEmpty()) {
                pdfWriter.pageEvent = DocxPdfPageEventListener(docxData.headers, docxData.footers
                )
            }

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

    private class DocxPdfPageEventListener(
        val headers: List<DocxParagraph>,
        val footers: List<DocxParagraph>
    ) : PdfPageEventHelper() {
        override fun onStartPage(writer: PdfWriter?, document: Document?) {
            if (writer != null && headers.isNotEmpty()) {
                val pageSize = document?.pageSize ?: PageSize.A4
                val cb = writer.directContent

                cb.beginText()
                cb.setFontAndSize(BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, false), 10f)
                cb.setTextMatrix(50f, pageSize.height - 30f)

                for (header in headers) {
                    for (run in header.runs) {
                        if (run.text.isNotEmpty()) {
                            cb.showText(run.text)
                        }
                    }
                }

                cb.endText()
            }
        }

        override fun onEndPage(writer: PdfWriter?, document: Document?) {
            if (writer != null && footers.isNotEmpty()) {
                document?.pageSize ?: PageSize.A4
                val cb = writer.directContent

                cb.beginText()
                cb.setFontAndSize(BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, false), 10f)
                cb.setTextMatrix(50f, 30f)

                for (footer in footers) {
                    for (run in footer.runs) {
                        if (run.text.isNotEmpty()) {
                            cb.showText(run.text)
                        }
                    }
                }

                cb.endText()
            }
        }
    }

    private data class DocxData(
        val paragraphs: List<DocxParagraph>,
        val images: Map<String, ByteArray>,
        val headers: List<DocxParagraph> = emptyList(),
        val footers: List<DocxParagraph> = emptyList()
    )

    private data class DocxParagraph(
        val runs: List<DocxRun>,
        val alignment: String? = null,
        val spacingBefore: Int = 0,
        val spacingAfter: Int = 0,
        val isTable: Boolean = false,
        val tableData: List<List<String>>? = null,
        val style: String? = null,
        val isHeading: Boolean = false,
        val headingLevel: Int = 0,
        val isList: Boolean = false,
        val listType: String? = null,
        val listLevel: Int = 0
    )

    private data class DocxRun(
        val text: String,
        val isBold: Boolean = false,
        val isItalic: Boolean = false,
        val isUnderline: Boolean = false,
        val isStrikethrough: Boolean = false,
        val fontSize: Float = 11f,
        val color: String? = null,
        val imageRef: String? = null,
        val fontName: String = "Helvetica"
    )

    private fun parseDocxStructure(docxBytes: ByteArray): DocxData {
        val zipInputStream = ZipInputStream(docxBytes.inputStream())
        var entry: ZipEntry?
        val images = mutableMapOf<String, ByteArray>()
        val imageRelations = mutableMapOf<String, String>()
        var documentXml = ""
        var headerXml = ""
        var footerXml = ""
        val styleRelations = mutableMapOf<String, DocxStyleInfo>()

        while (zipInputStream.nextEntry.also { entry = it } != null) {
            when {
                entry?.name == "word/document.xml" -> {
                    documentXml = zipInputStream.bufferedReader().readText()
                }
                entry?.name == "word/styles.xml" -> {
                    val stylesContent = zipInputStream.bufferedReader().readText()
                    parseStyles(stylesContent, styleRelations)
                }
                entry?.name == "word/header1.xml" || entry?.name == "word/header2.xml" -> {
                    headerXml = zipInputStream.bufferedReader().readText()
                }
                entry?.name == "word/footer1.xml" || entry?.name == "word/footer2.xml" -> {
                    footerXml = zipInputStream.bufferedReader().readText()
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

        val paragraphs = parseDocumentXml(documentXml, styleRelations)
        val headers = if (headerXml.isNotEmpty()) parseDocumentXml(headerXml, styleRelations) else emptyList()
        val footers = if (footerXml.isNotEmpty()) parseDocumentXml(footerXml, styleRelations) else emptyList()

        return DocxData(paragraphs, mappedImages, headers, footers)
    }

    private data class DocxStyleInfo(
        val styleId: String,
        val isHeading: Boolean = false,
        val headingLevel: Int = 0,
        val fontSize: Float = 11f,
        val isBold: Boolean = false,
        val fontName: String = "Helvetica"
    )

    private fun parseStyles(stylesXml: String, styles: MutableMap<String, DocxStyleInfo>) {
        val styleRegex = "<w:style[^>]+w:styleId=\"([^\"]+)\"[^>]*>.*?</w:style>".toRegex(RegexOption.DOT_MATCHES_ALL)

        styleRegex.findAll(stylesXml).forEach { match ->
            val styleXml = match.value
            val styleId = match.groupValues[1]

            val nameRegex = "<w:name\\s+w:val=\"([^\"]+)\"".toRegex()
            val styleName = nameRegex.find(styleXml)?.groupValues?.get(1) ?: ""

            val isHeading = styleName.contains("heading", ignoreCase = true)
            val headingLevel = if (isHeading) {
                val level = styleName.firstOrNull { it.isDigit() }?.toString()?.toIntOrNull() ?: 1
                level
            } else 0

            val fontSizeRegex = "<w:sz\\s+w:val=\"(\\d+)\"".toRegex()
            val fontSize = fontSizeRegex.find(styleXml)?.groupValues?.get(1)?.let { (it.toFloat() / 2f).coerceIn(6f, 72f) } ?: 11f

            val isBold = styleXml.contains("<w:b/>") || styleXml.contains("<w:b ")

            styles[styleId] = DocxStyleInfo(
                styleId = styleId,
                isHeading = isHeading,
                headingLevel = headingLevel,
                fontSize = fontSize,
                isBold = isBold
            )
        }
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

    private fun parseDocumentXml(xml: String, styles: Map<String, DocxStyleInfo>): List<DocxParagraph> {
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
                val styleId = extractStyleId(paraXml)
                val styleInfo = styles[styleId]
                val runs = parseRuns(paraXml)

                val isHeading = styleInfo?.isHeading ?: false
                val headingLevel = styleInfo?.headingLevel ?: 0
                val (isList, listType, listLevel) = extractListInfo(paraXml)

                if (runs.isNotEmpty() || !isList) {
                    allElements.add(ElementWithPosition(
                        paraMatch.range.first,
                        DocxParagraph(
                            runs = runs.ifEmpty { listOf(DocxRun(" ")) },
                            alignment = alignment,
                            style = styleId,
                            isHeading = isHeading,
                            headingLevel = headingLevel,
                            isList = isList,
                            listType = listType,
                            listLevel = listLevel
                        )
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

    private fun extractStyleId(paraXml: String): String? {
        val styleRegex = "<w:pStyle\\s+w:val=\"([^\"]+)\"".toRegex()
        return styleRegex.find(paraXml)?.groupValues?.get(1)
    }

    private fun extractListInfo(paraXml: String): Triple<Boolean, String?, Int> {
        val pStyleRegex = "<w:pStyle\\s+w:val=\"([^\"]+)\"".toRegex()
        val styleVal = pStyleRegex.find(paraXml)?.groupValues?.get(1) ?: ""

        val numPrRegex = "<w:numPr[\\s>](.*?)</w:numPr>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val numPrMatch = numPrRegex.find(paraXml)

        if (numPrMatch != null) {
            val numPrXml = numPrMatch.value

            val ilvlRegex = "<w:ilvl\\s+w:val=\"(\\d+)\"".toRegex()
            val level = ilvlRegex.find(numPrXml)?.groupValues?.get(1)?.toIntOrNull() ?: 0

            val listType = when {
                styleVal.contains("List", ignoreCase = true) && styleVal.contains("Bullet", ignoreCase = true) -> "unordered"
                styleVal.contains("List", ignoreCase = true) && styleVal.contains("Number", ignoreCase = true) -> "ordered"
                styleVal.lowercase().contains("bullet") -> "unordered"
                styleVal.lowercase().contains("number") || styleVal.lowercase().contains("paragraph") -> "ordered"
                else -> "unordered"
            }

            return Triple(true, listType, level)
        }

        return Triple(false, null, 0)
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
                val paragraphRegex = "<w:p[\\s>](.*?)</w:p>".toRegex(RegexOption.DOT_MATCHES_ALL)
                val cellText = paragraphRegex.findAll(cellXml)
                    .joinToString("\n") { paraMatch ->
                        extractTextFromXml(paraMatch.value)
                    }
                    .trim()

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
                val isStrikethrough = runXml.contains("<w:strike/>") || runXml.contains("<w:strike ")
                val fontSize = extractFontSize(runXml)
                val color = extractColor(runXml)
                val fontName = extractFontName(runXml)

                runs.add(DocxRun(
                    text = text,
                    isBold = isBold,
                    isItalic = isItalic,
                    isUnderline = isUnderline,
                    isStrikethrough = isStrikethrough,
                    fontSize = fontSize,
                    color = color,
                    fontName = fontName
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

    private fun extractFontName(runXml: String): String {
        val fontRegex = "<w:rFonts\\s+w:ascii=\"([^\"]+)\"".toRegex()
        return fontRegex.find(runXml)?.groupValues?.get(1) ?: "Helvetica"
    }

    private fun extractImageReference(runXml: String): String? {
        var embedRegex = "r:embed=\"([^\"]+)\"".toRegex()
        var match = embedRegex.find(runXml)
        if (match != null) {
            return match.groupValues[1]
        }

        embedRegex = "r:id=\"([^\"]+)\"".toRegex()
        match = embedRegex.find(runXml)
        if (match != null) {
            return match.groupValues[1]
        }

        return null
    }

    private fun renderDocxToPdf(docxData: DocxData, pdfDocument: Document) {
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
        pdfDocument: Document
    ) {
        val pdfPara = Paragraph()

        when (paragraph.alignment) {
            "center" -> pdfPara.alignment = Element.ALIGN_CENTER
            "right" -> pdfPara.alignment = Element.ALIGN_RIGHT
            "both" -> pdfPara.alignment = Element.ALIGN_JUSTIFIED
            else -> pdfPara.alignment = Element.ALIGN_LEFT
        }

        if (paragraph.isHeading) {
            pdfPara.spacingBefore = 12f
            pdfPara.spacingAfter = 6f
        } else {
            pdfPara.spacingBefore = paragraph.spacingBefore * 0.35f
            pdfPara.spacingAfter = if (paragraph.spacingAfter > 0) paragraph.spacingAfter * 0.35f else 3f
        }

        var hasContent = false

        for (run in paragraph.runs) {
            if (run.imageRef != null) {
                val imageData = images[run.imageRef]
                if (imageData != null) {
                    try {
                        val image = Image.getInstance(imageData)

                        val maxWidth = pdfDocument.pageSize.width - pdfDocument.leftMargin() - pdfDocument.rightMargin()
                        val maxHeight = (pdfDocument.pageSize.height - pdfDocument.topMargin() - pdfDocument.bottomMargin()) * 0.4f

                        val widthScale = maxWidth / image.width
                        val heightScale = maxHeight / image.height
                        val scale = minOf(widthScale, heightScale, 1f)

                        if (scale < 1f) {
                            image.scalePercent(scale * 100)
                        } else {
                            image.scalePercent(minOf(scale * 100, 100f))
                        }

                        image.alignment = when (paragraph.alignment) {
                            "center" -> Element.ALIGN_CENTER
                            "right" -> Element.ALIGN_RIGHT
                            else -> Element.ALIGN_LEFT
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
                var fontSize = run.fontSize
                if (paragraph.isHeading) {
                    fontSize = when (paragraph.headingLevel) {
                        1 -> 28f
                        2 -> 24f
                        3 -> 20f
                        else -> 16f
                    }
                }

                val fontStyle = when {
                    run.isBold && run.isItalic -> Font.BOLDITALIC
                    run.isBold -> Font.BOLD
                    run.isItalic -> Font.ITALIC
                    else -> Font.NORMAL
                }

                val font = FontFactory.getFont(
                    run.fontName,
                    fontSize,
                    fontStyle
                )

                if (run.color != null && run.color != "auto") {
                    try {
                        val colorInt = Integer.parseInt(run.color, 16)
                        val r = (colorInt shr 16 and 0xFF) / 255f
                        val g = (colorInt shr 8 and 0xFF) / 255f
                        val b = (colorInt and 0xFF) / 255f
                        font.color = BaseColor(r, g, b)
                    } catch (_: Exception) {
                        // stay on default color
                    }
                }

                val chunk = Chunk(run.text, font)
                if (run.isUnderline) {
                    chunk.setUnderline(0.5f, -2f)
                }
                if (run.isStrikethrough) {
                    chunk.setUnderline(0.5f, -0.5f)  // Line through middle
                }

                pdfPara.add(chunk)
                hasContent = true
            }
        }

        if (paragraph.isList && hasContent) {
            val listMarker = if (paragraph.listType == "ordered") {
                "${paragraph.listLevel + 1}."
            } else {
                "•"
            }
            pdfPara.add(0, Chunk("$listMarker "))
        }

        if (hasContent || paragraph.runs.isEmpty()) {
            pdfDocument.add(pdfPara)
        }
    }

    private fun renderTable(
        tableData: List<List<String>>,
        pdfDocument: Document
    ) {
        if (tableData.isEmpty()) return

        val maxCols = tableData.maxOfOrNull { it.size } ?: return
        val table = PdfPTable(maxCols)
        table.widthPercentage = 100f
        table.spacingBefore = 10f
        table.spacingAfter = 10f

        val font = FontFactory.getFont(
            FontFactory.HELVETICA,
            10f
        )

        for (row in tableData) {
            for (cellText in row) {
                val cell = PdfPCell(Phrase(cellText, font))
                cell.setPadding(5f)
                cell.borderWidth = 1f
                table.addCell(cell)
            }
            repeat(maxCols - row.size) {
                val emptyCell = PdfPCell()
                emptyCell.setPadding(5f)
                table.addCell(emptyCell)
            }
        }

        pdfDocument.add(table)
    }

    //endregion

    //region **MESSAGE_CUSTOM**

    private fun injectMobileOptimizations(url: String) {
        when {
            url.contains("/message/index.php") -> {
                optimizeMessagesPage()
                Handler(Looper.getMainLooper()).postDelayed({
                    setupMessageInputOverride()
                }, 500)
            }
            //url.contains("/my/") && !url.contains("/my/index.php?edit=") -> optimizeDashboardPage()
            // tried (switching dashboard header with recent side bar) but failed to preserve js functionality
        }
    }

    private fun optimizeMessagesPage() {
        messageDialogSuppressedForSession = false
        L.d("MoodleFragment", "Messages page loaded - dialog suppression reset")

        val jsCode = """
        (function() {
            try {
                console.log('Optimizing messages page for mobile...');
    
                if (!window.moodleOptimizationState) {
                    window.moodleOptimizationState = {
                        div2Removed: 0,
                        footerRemoved: 0
                    };
                }
    
                window.moodleAllowSend = false;
    
                function removeElementByXPath(xpath, elementName) {
                    var element = document.evaluate(
                        xpath,
                        document,
                        null,
                        XPathResult.FIRST_ORDERED_NODE_TYPE,
                        null
                    ).singleNodeValue;
                    
                    if (element) {
                        element.remove();
                        console.log('Removed ' + elementName);
                        return true;
                    }
                    return false;
                }
                
                // col-4 to col-5 for conversation list
                var conversationContainer = document.evaluate(
                    '/html/body/div[1]/div[2]/div/div[1]/div/div/div/div/div/div[1]',
                    document,
                    null,
                    XPathResult.FIRST_ORDERED_NODE_TYPE,
                    null
                ).singleNodeValue;
                
                if (conversationContainer) {
                    conversationContainer.className = conversationContainer.className.replace('col-4', 'col-5');
                    console.log('Updated conversation container to col-5');
                }
                
                // col-8 to col-7 for message area
                var messageContainer = document.evaluate(
                    '/html/body/div[1]/div[2]/div/div[1]/div/div/div/div/div/div[2]',
                    document,
                    null,
                    XPathResult.FIRST_ORDERED_NODE_TYPE,
                    null
                ).singleNodeValue;
                
                if (messageContainer) {
                    messageContainer.className = messageContainer.className.replace('col-8', 'col-7');
                    console.log('Updated message container to col-7');
                }
                
                // modify message box rows
                var textarea = document.evaluate(
                    '/html/body/div[1]/div[2]/div/div[1]/div/div/div/div/div/div[2]/div[3]/div/div[1]/div[2]/textarea',
                    document,
                    null,
                    XPathResult.FIRST_ORDERED_NODE_TYPE,
                    null
                ).singleNodeValue;
                
                if (textarea) {
                    textarea.setAttribute('rows', '5');
                    textarea.setAttribute('data-min-rows', '5');
                    textarea.setAttribute('data-max-rows', '12');
                    console.log('Updated textarea rows to 5-12');
                }
                
                // remove div[2] (can reappear once)
                if (window.moodleOptimizationState.div2Removed < 2) {
                    if (removeElementByXPath('/html/body/div[1]/div[2]/div/div[2]', 'div[2]')) {
                        window.moodleOptimizationState.div2Removed++;
                    }
                }
                
                // remove div[3] (only once)
                removeElementByXPath('/html/body/div[1]/div[2]/div/div[3]', 'div[3]');
                
                // remove footer (can also reappear once)
                if (window.moodleOptimizationState.footerRemoved < 2) {
                    if (removeElementByXPath('/html/body/div[1]/footer', 'footer')) {
                        window.moodleOptimizationState.footerRemoved++;
                    }
                }
                
                function setupObserver() {
                    var targetNode = document.body;
                    
                    var observer = new MutationObserver(function(mutations) {
                        mutations.forEach(function(mutation) {
                            if (mutation.addedNodes.length > 0) {
                                if (window.moodleOptimizationState.div2Removed < 2) {
                                    var div2 = document.evaluate(
                                        '/html/body/div[1]/div[2]/div/div[2]',
                                        document,
                                        null,
                                        XPathResult.FIRST_ORDERED_NODE_TYPE,
                                        null
                                    ).singleNodeValue;
                                    
                                    if (div2) {
                                        console.log('div[2] reappeared, removing again');
                                        div2.remove();
                                        window.moodleOptimizationState.div2Removed++;
                                    }
                                }
    
                                if (window.moodleOptimizationState.footerRemoved < 2) {
                                    var footer = document.evaluate(
                                        '/html/body/div[1]/footer',
                                        document,
                                        null,
                                        XPathResult.FIRST_ORDERED_NODE_TYPE,
                                        null
                                    ).singleNodeValue;
                                    
                                    if (footer) {
                                        console.log('Footer reappeared, removing again');
                                        footer.remove();
                                        window.moodleOptimizationState.footerRemoved++;
                                    }
                                }
    
                                if (window.moodleOptimizationState.div2Removed >= 2 && 
                                    window.moodleOptimizationState.footerRemoved >= 2) {
                                    console.log('All removals complete, stopping observer');
                                    observer.disconnect();
                                }
                            }
                        });
                    });
                    
                    observer.observe(targetNode, {
                        childList: true,
                        subtree: true
                    });
                    
                    console.log('MutationObserver set up to watch for reappearing elements');
                }
                
                setupObserver();
                
                return true;
            } catch(e) {
                console.error('Error optimizing messages page: ' + e.message);
                return false;
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            if (result == "true") {
                L.d("MoodleFragment", "Messages page optimized with persistent monitoring")
            } else {
                L.w("MoodleFragment", "Failed to optimize messages page")
            }
        }
    }

    private fun clickMessageDrawerToggle() {
        if (isPdfTab(currentTabIndex)) {
            return
        }

        val jsCode = """
    (function() {
        try {
            var drawerToggle = document.querySelector('[data-toggle="drawer-toggle"]');
            if (drawerToggle) {
                drawerToggle.click();
                return true;
            }
            return false;
        } catch(e) {
            return false;
        }
    })();
""".trimIndent()

        webView.evaluateJavascript(jsCode) { _ ->
            Handler(Looper.getMainLooper()).postDelayed({
                if (isFragmentActive && isAdded) {
                    val currentUrl = webView.url ?: ""
                    if (currentUrl.contains("/message/index.php")) {
                        isAtTop = false
                        hideExtendedHeaderWithAnimation()
                    }
                }
            }, 500)
        }
    }

    private fun setupMessageInputOverride() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastMessageInputSetupTime < MESSAGE_INPUT_SETUP_COOLDOWN) {
            L.d("MoodleFragment", "Message input setup on cooldown")
            return
        }
        lastMessageInputSetupTime = currentTime

        val jsCode = """
        (function() {
            try {
                if (window.moodleMessageInputOverrideSetup) {
                    return 'already_setup';
                }
                
                console.log('Setting up message input override...');
                
                // "main chat" text input
                var mainTextarea = document.evaluate(
                    '/html/body/div[1]/div[2]/div/div[1]/div/div/div/div/div/div[2]/div[3]/div/div[1]/div[2]/textarea',
                    document,
                    null,
                    XPathResult.FIRST_ORDERED_NODE_TYPE,
                    null
                ).singleNodeValue;
                
                // "side bar" chat text input
                var sidebarTextarea = document.evaluate(
                    '/html/body/div[1]/div[3]/div[2]/div[4]/div[1]/div[1]/div[2]/textarea',
                    document,
                    null,
                    XPathResult.FIRST_ORDERED_NODE_TYPE,
                    null
                ).singleNodeValue;
                
                // "main chat" send button
                var mainSendBtn = document.evaluate(
                    '/html/body/div[1]/div[2]/div/div[1]/div/div/div/div/div/div[2]/div[3]/div/div[1]/div[2]/div/button[2]',
                    document,
                    null,
                    XPathResult.FIRST_ORDERED_NODE_TYPE,
                    null
                ).singleNodeValue;
                
                // "side bar chat" send button
                var sidebarSendBtn = document.evaluate(
                    '/html/body/div[1]/div[3]/div[2]/div[4]/div[1]/div[1]/div[2]/div/button[2]',
                    document,
                    null,
                    XPathResult.FIRST_ORDERED_NODE_TYPE,
                    null
                ).singleNodeValue;

                window.moodleTextareaHandlers = {};
                window.moodleSendBtnHandlers = {};
                
                function setupTextareaOverride(textarea, chatType) {
                    if (!textarea) return false;
                    
                    var focusHandler = function(e) {
                        if (window.moodleDialogSuppressed) {
                            console.log('Dialog suppressed - allowing native focus');
                            return;
                        }
                        e.preventDefault();
                        e.stopPropagation();
                        textarea.blur();
                        window.moodleMessageInputFocused = chatType;
                        console.log('Textarea focused: ' + chatType);
                    };
                    
                    var clickHandler = function(e) {
                        if (window.moodleDialogSuppressed) {
                            console.log('Dialog suppressed - allowing native click');
                            return;
                        }
                        e.preventDefault();
                        e.stopPropagation();
                        window.moodleMessageInputFocused = chatType;
                        console.log('Textarea clicked: ' + chatType);
                    };
                    
                    textarea.addEventListener('focus', focusHandler, true);
                    textarea.addEventListener('click', clickHandler, true);
                    
                    window.moodleTextareaHandlers[chatType] = {
                        element: textarea,
                        focusHandler: focusHandler,
                        clickHandler: clickHandler
                    };
                    
                    return true;
                }
                
                function setupSendButtonOverride(button, chatType) {
                    if (!button) return false;
                    
                    var clickHandler = function(e) {
                        if (window.moodleDialogSuppressed) {
                            console.log('Dialog suppressed - allowing native send');
                            return;
                        }
                        e.preventDefault();
                        e.stopPropagation();
                        window.moodleMessageSendClicked = chatType;
                        console.log('Send button clicked: ' + chatType);
                    };
                    
                    button.addEventListener('click', clickHandler, true);
                    
                    window.moodleSendBtnHandlers[chatType] = {
                        element: button,
                        clickHandler: clickHandler
                    };
                    
                    return true;
                }
                
                var mainTextareaSetup = setupTextareaOverride(mainTextarea, 'main');
                var sidebarTextareaSetup = setupTextareaOverride(sidebarTextarea, 'sidebar');
                var mainSendBtnSetup = setupSendButtonOverride(mainSendBtn, 'main');
                var sidebarSendBtnSetup = setupSendButtonOverride(sidebarSendBtn, 'sidebar');
                
                window.moodleMessageInputOverrideSetup = true;
                window.moodleDialogSuppressed = false;
                
                console.log('Message input override setup complete');
                console.log('Main textarea: ' + mainTextareaSetup);
                console.log('Sidebar textarea: ' + sidebarTextareaSetup);
                console.log('Main send button: ' + mainSendBtnSetup);
                console.log('Sidebar send button: ' + sidebarSendBtnSetup);
                
                return 'success';
            } catch(e) {
                console.error('Error setting up message input override: ' + e.message);
                return 'error: ' + e.message;
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            val cleanResult = result.trim('"')
            L.d("MoodleFragment", "Message input override setup result: $cleanResult")

            when (cleanResult) {
                "success" -> {
                    L.d("MoodleFragment", "Starting message input monitoring")
                    startMessageInputMonitoring()
                }
                "already_setup" -> {
                    L.d("MoodleFragment", "Message input override already setup")
                    if (!isMonitoringMessageInput) {
                        startMessageInputMonitoring()
                    }
                }
                else -> {
                    L.e("MoodleFragment", "Failed to setup message input override: $cleanResult")
                }
            }
        }
    }

    private fun startMessageInputMonitoring() {
        if (isMonitoringMessageInput) {
            L.d("MoodleFragment", "Already monitoring message input")
            return
        }

        stopMessageInputMonitoring()

        isMonitoringMessageInput = true
        val checkInterval = 300L
        messageInputMonitorHandler = Handler(Looper.getMainLooper())

        messageInputMonitorRunnable = object : Runnable {
            override fun run() {
                if (!isFragmentActive || !isAdded || !isMonitoringMessageInput) {
                    stopMessageInputMonitoring()
                    return
                }

                val currentUrl = webView.url ?: ""
                if (!currentUrl.contains("/message/index.php")) {
                    L.d("MoodleFragment", "No longer on messages page, stopping monitoring")
                    stopMessageInputMonitoring()
                    return
                }

                val jsCode = """
                (function() {
                    try {
                        if (window.moodleMessageInputFocused) {
                            var chatType = window.moodleMessageInputFocused;
                            window.moodleMessageInputFocused = null;
                            return JSON.stringify({ action: 'input_focused', chatType: chatType });
                        }
                        
                        if (window.moodleMessageSendClicked) {
                            var chatType = window.moodleMessageSendClicked;
                            window.moodleMessageSendClicked = null;
                            return JSON.stringify({ action: 'send_clicked', chatType: chatType });
                        }
                        
                        return JSON.stringify({ action: 'none' });
                    } catch(e) {
                        return JSON.stringify({ action: 'error', error: e.message });
                    }
                })();
            """.trimIndent()

                webView.evaluateJavascript(jsCode) { result ->
                    if (!isFragmentActive || !isAdded || !isMonitoringMessageInput) {
                        stopMessageInputMonitoring()
                        return@evaluateJavascript
                    }

                    try {
                        val cleanResult = result.replace("\\\"", "\"").trim('"')
                        val jsonResult = JSONObject(cleanResult)
                        val action = jsonResult.getString("action")

                        when (action) {
                            "input_focused" -> {
                                val chatType = jsonResult.getString("chatType")
                                L.d("MoodleFragment", "Message input focused: $chatType")
                                currentMessageChatType = if (chatType == "main") MessageChatType.MAIN_CHAT else MessageChatType.SIDEBAR_CHAT
                                activity?.runOnUiThread {
                                    showMessageInputDialog()
                                }
                            }
                            "send_clicked" -> {
                                val chatType = jsonResult.getString("chatType")
                                L.d("MoodleFragment", "Send button clicked: $chatType")
                                currentMessageChatType = if (chatType == "main") MessageChatType.MAIN_CHAT else MessageChatType.SIDEBAR_CHAT
                                activity?.runOnUiThread {
                                    showSendConfirmationDialog()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        L.e("MoodleFragment", "Error parsing message input monitor result", e)
                    }

                    messageInputMonitorHandler?.postDelayed(this, checkInterval)
                }
            }
        }

        messageInputMonitorHandler?.post(messageInputMonitorRunnable!!)
        L.d("MoodleFragment", "Message input monitoring started")
    }

    private fun stopMessageInputMonitoring() {
        isMonitoringMessageInput = false
        messageInputMonitorRunnable?.let {
            messageInputMonitorHandler?.removeCallbacks(it)
        }
        messageInputMonitorHandler = null
        messageInputMonitorRunnable = null
        messageDialogSuppressedForSession = false
        setSuppressedFlagInWebView(false)
        L.d("MoodleFragment", "Message input monitoring stopped - dialog suppression reset")
    }

    private fun getRecipientInfo(callback: (MessageRecipientInfo?) -> Unit) {
        val chatType = currentMessageChatType ?: run {
            callback(null)
            return
        }

        val usernamePath = when (chatType) {
            MessageChatType.MAIN_CHAT -> "/html/body/div[1]/div[2]/div/div[1]/div/div/div/div/div/div[2]/div[1]/div[2]/div[1]/div/div[1]/a/div[2]/div/strong"
            MessageChatType.SIDEBAR_CHAT -> "/html/body/div[1]/div[3]/div[2]/div[2]/div[2]/div[1]/div/div[2]/a/div[2]/div/strong"
        }

        val iconPath = when (chatType) {
            MessageChatType.MAIN_CHAT -> "/html/body/div[1]/div[2]/div/div[1]/div/div/div/div/div/div[2]/div[1]/div[2]/div[1]/div/div[1]/a/div[1]/img"
            MessageChatType.SIDEBAR_CHAT -> "/html/body/div[1]/div[3]/div[2]/div[2]/div[2]/div[1]/div/div[2]/a/div[1]/img"
        }

        val jsCode = """
        (function() {
            try {
                var usernameElement = document.evaluate(
                    '$usernamePath',
                    document,
                    null,
                    XPathResult.FIRST_ORDERED_NODE_TYPE,
                    null
                ).singleNodeValue;
                
                var iconElement = document.evaluate(
                    '$iconPath',
                    document,
                    null,
                    XPathResult.FIRST_ORDERED_NODE_TYPE,
                    null
                ).singleNodeValue;
                
                var username = usernameElement ? usernameElement.textContent.trim() : null;
                var iconUrl = iconElement ? iconElement.src : null;
                var iconAlt = iconElement ? iconElement.alt : null;
                
                console.log('Chat Type: $chatType');
                console.log('Username: ' + username);
                console.log('Icon URL: ' + iconUrl);
                console.log('Icon Alt: [alt="' + iconAlt + '"]');
                
                return JSON.stringify({
                    username: username,
                    iconUrl: iconUrl,
                    iconAlt: iconAlt
                });
            } catch(e) {
                console.error('Error in getRecipientInfo: ' + e.message);
                return JSON.stringify({
                    username: null,
                    iconUrl: null,
                    iconAlt: null,
                    error: e.message
                });
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            try {
                val cleanResult = result.replace("\\\"", "\"").trim('"')
                L.d("MoodleFragment", "getRecipientInfo result: $cleanResult")

                val jsonResult = JSONObject(cleanResult)

                val username: String? = jsonResult.optString("username", "")
                val iconUrl: String? = jsonResult.optString("iconUrl", "")
                val iconAlt: String? = jsonResult.optString("iconAlt", "")

                L.d("MoodleFragment", "Parsed - Username: $username, IconURL: $iconUrl, Alt: [alt=\"$iconAlt\"]")

                if (username != null && username != "" && !username.equals("null", ignoreCase = true)) {
                    callback(MessageRecipientInfo(username, iconUrl))
                } else {
                    L.e("MoodleFragment", "Failed to get recipient info")
                    callback(null)
                }
            } catch (e: Exception) {
                L.e("MoodleFragment", "Error parsing recipient info", e)
                callback(null)
            }
        }
    }

    private fun showMessageInputDialog() {
        if (messageDialogSuppressedForSession) {
            L.d("MoodleFragment", "Message input dialog suppressed - allowing native input")
            allowNativeMessageInput()
            return
        }

        getRecipientInfo { recipientInfo ->
            if (recipientInfo == null) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.moodle_failed_to_get_recipient_info),
                    Toast.LENGTH_SHORT
                ).show()
                return@getRecipientInfo
            }

            activity?.runOnUiThread {
                val dialogView = layoutInflater.inflate(R.layout.dialog_moodle_message_input, null)
                val etMessageInput = dialogView.findViewById<EditText>(R.id.etMessageInput)
                val tvCharCounter = dialogView.findViewById<TextView>(R.id.tvCharCounter)
                val btnCopyText = dialogView.findViewById<ImageButton>(R.id.btnCopyText)
                val btnDeleteMsg = dialogView.findViewById<ImageButton>(R.id.btnDeleteMsg)
                val tvRecipientName = dialogView.findViewById<TextView>(R.id.tvRecipientName)
                val ivUserIcon = dialogView.findViewById<ImageView>(R.id.ivUserIcon)
                val cbDontShowForNow = dialogView.findViewById<CheckBox>(R.id.cbDontShowForNow)

                tvRecipientName.text = recipientInfo.username

                if (recipientInfo.userIconUrl != null) {
                    loadUserIcon(recipientInfo.userIconUrl, ivUserIcon)
                }

                val maxChars = 4096
                tvCharCounter.text = getString(R.string.moodle_char_count, 0, maxChars)

                etMessageInput.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int
                    ) {
                    }

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int
                    ) {
                    }

                    override fun afterTextChanged(s: Editable?) {
                        val length = s?.length ?: 0
                        tvCharCounter.text = getString(R.string.moodle_char_count, length, maxChars)

                        if (length >= maxChars) {
                            tvCharCounter.setTextColor(
                                ContextCompat.getColor(
                                    requireContext(),
                                    android.R.color.holo_red_dark
                                )
                            )
                        } else {
                            tvCharCounter.setTextColor(requireContext().getThemeColor(R.attr.textSecondaryColor))
                        }
                    }
                })

                btnCopyText.setOnClickListener {
                    val text = etMessageInput.text.toString()
                    if (text.isNotEmpty()) {
                        val clipboard =
                            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText(
                            getString(
                                R.string.moodle_message_to,
                                recipientInfo.username
                            ), text
                        )
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.moodle_text_copied_clipboard),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                btnDeleteMsg.setOnClickListener {
                    showDeleteConfirmDialog(etMessageInput)
                }

                val dialog = AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.moodle_message_to, recipientInfo.username))
                    .setView(dialogView)
                    .setPositiveButton(getString(R.string.moodle_insert), null)
                    .setNegativeButton(getString(R.string.moodle_discard), null)
                    .setCancelable(false)
                    .create()

                dialog.setOnShowListener {
                    val buttonColor =
                        requireContext().getThemeColor(R.attr.dialogSectionButtonColor)

                    val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    positiveButton?.setTextColor(buttonColor)
                    positiveButton?.setOnClickListener {
                        val messageText = etMessageInput.text.toString()
                        if (messageText.isEmpty()) {
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.moodle_message_empty),
                                Toast.LENGTH_SHORT
                            ).show()
                            return@setOnClickListener
                        }

                        if (cbDontShowForNow.isChecked) {
                            messageDialogSuppressedForSession = true
                            setSuppressedFlagInWebView(true)
                            L.d("MoodleFragment", "Message dialog suppressed until page refresh")
                        }

                        showInsertConfirmDialog(messageText, dialog)
                    }

                    val negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    negativeButton?.setTextColor(buttonColor)
                    negativeButton?.setOnClickListener {
                        if (cbDontShowForNow.isChecked) {
                            messageDialogSuppressedForSession = true
                            setSuppressedFlagInWebView(true)
                            L.d("MoodleFragment", "Message dialog suppressed until page refresh")
                        }
                        showDiscardConfirmDialog(dialog)
                    }
                }

                messageInputDialog = dialog
                dialog.show()

                etMessageInput.requestFocus()
                val imm =
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(etMessageInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun setSuppressedFlagInWebView(suppressed: Boolean) {
        val jsCode = """
        (function() {
            window.moodleDialogSuppressed = $suppressed;
            console.log('Dialog suppression set to: $suppressed');
            return true;
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            L.d("MoodleFragment", "Set dialog suppression flag: $suppressed, result: $result")
        }
    }

    private fun allowNativeMessageInput() {
        val chatType = currentMessageChatType ?: return

        val textareaPath = when (chatType) {
            MessageChatType.MAIN_CHAT -> "/html/body/div[1]/div[2]/div/div[1]/div/div/div/div/div/div[2]/div[3]/div/div[1]/div[2]/textarea"
            MessageChatType.SIDEBAR_CHAT -> "/html/body/div[1]/div[3]/div[2]/div[4]/div[1]/div[1]/div[2]/textarea"
        }

        val jsCode = """
        (function() {
            try {
                var textarea = document.evaluate(
                    '$textareaPath',
                    document,
                    null,
                    XPathResult.FIRST_ORDERED_NODE_TYPE,
                    null
                ).singleNodeValue;
                
                if (textarea) {
                    textarea.focus();
                    console.log('Native input allowed for suppressed dialog');
                    return true;
                }
                return false;
            } catch(e) {
                console.error('Error allowing native input: ' + e.message);
                return false;
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            if (result == "true") {
                L.d("MoodleFragment", "Native message input enabled")
            } else {
                L.e("MoodleFragment", "Failed to enable native message input")
            }
        }
    }

    private fun showDiscardConfirmDialog(parentDialog: AlertDialog) {
        val confirmDialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.moodle_discard_confirm_title))
            .setMessage(getString(R.string.moodle_discard_confirm_message))
            .setPositiveButton(getString(R.string.moodle_discard)) { _, _ ->
                parentDialog.dismiss()
            }
            .setNegativeButton(getString(R.string.moodle_cancel), null)
            .show()

        val buttonColor = requireContext().getThemeColor(R.attr.dialogSectionButtonColor)
        confirmDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        confirmDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
    }

    private fun loadUserIcon(iconUrl: String, imageView: ImageView) {
        L.d("MoodleFragment", "loadUserIcon called with URL: $iconUrl")

        var userAgent: String? = null
        activity?.runOnUiThread {
            try {
                userAgent = webView.settings.userAgentString
            } catch (e: Exception) {
                L.e("MoodleFragment", "Error getting user agent", e)
            }
        }

        Thread.sleep(50)

        backgroundExecutor.execute {
            try {
                L.d("MoodleFragment", "Starting image download from: $iconUrl")

                val connection = URL(iconUrl).openConnection() as HttpURLConnection
                connection.doInput = true
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val cookieManager = CookieManager.getInstance()
                val cookies = cookieManager.getCookie(iconUrl)
                if (cookies != null) {
                    connection.setRequestProperty("Cookie", cookies)
                    L.d("MoodleFragment", "Added cookies to request: ${cookies.take(50)}...")
                } else {
                    L.d("MoodleFragment", "No cookies found for this URL")
                }

                if (userAgent != null) {
                    connection.setRequestProperty("User-Agent", userAgent)
                    L.d("MoodleFragment", "Added User-Agent to request")
                }

                connection.connect()

                val responseCode = connection.responseCode
                L.d("MoodleFragment", "HTTP Response Code: $responseCode")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val input = connection.inputStream
                    val bitmap = BitmapFactory.decodeStream(input)
                    input.close()

                    if (bitmap != null) {
                        L.d("MoodleFragment", "Bitmap loaded successfully. Size: ${bitmap.width}x${bitmap.height}")

                        activity?.runOnUiThread {
                            imageView.setImageBitmap(bitmap)
                            imageView.visibility = View.VISIBLE
                            L.d("MoodleFragment", "Image set to ImageView and made visible")
                        }
                    } else {
                        L.e("MoodleFragment", "Bitmap is null after decoding")
                        activity?.runOnUiThread {
                            imageView.visibility = View.GONE
                        }
                    }
                } else {
                    L.e("MoodleFragment", "HTTP error: $responseCode")
                    activity?.runOnUiThread {
                        imageView.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                L.e("MoodleFragment", "Error loading user icon from: $iconUrl", e)
                activity?.runOnUiThread {
                    imageView.visibility = View.GONE
                }
            }
        }
    }

    private fun showInsertConfirmDialog(messageText: String, parentDialog: AlertDialog) {
        val confirmDialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.moodle_insert_confirm_title))
            .setMessage(getString(R.string.moodle_insert_confirm_message))
            .setPositiveButton(getString(R.string.moodle_insert)) { _, _ ->
                insertMessageIntoChat(messageText)
                parentDialog.dismiss()
            }
            .setNegativeButton(getString(R.string.moodle_cancel), null)
            .show()

        val buttonColor = requireContext().getThemeColor(R.attr.dialogSectionButtonColor)
        confirmDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        confirmDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
    }

    private fun showDeleteConfirmDialog(editText: EditText) {
        val confirmDialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.moodle_delete_confirm_title))
            .setMessage(getString(R.string.moodle_delete_confirm_message))
            .setPositiveButton(getString(R.string.moodle_delete)) { _, _ ->
                editText.setText("")
            }
            .setNegativeButton(getString(R.string.moodle_cancel), null)
            .show()

        val buttonColor = requireContext().getThemeColor(R.attr.dialogSectionButtonColor)
        confirmDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        confirmDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
    }

    private fun insertMessageIntoChat(messageText: String) {
        val chatType = currentMessageChatType ?: return

        val textareaPath = when (chatType) {
            MessageChatType.MAIN_CHAT -> "/html/body/div[1]/div[2]/div/div[1]/div/div/div/div/div/div[2]/div[3]/div/div[1]/div[2]/textarea"
            MessageChatType.SIDEBAR_CHAT -> "/html/body/div[1]/div[3]/div[2]/div[4]/div[1]/div[1]/div[2]/textarea"
        }

        val escapedMessage = messageText
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("`", "\\`")
            .replace("$", "\\$")
            .replace("\t", "\\t")

        val jsCode = """
        (function() {
            try {
                var textarea = document.evaluate(
                    '$textareaPath',
                    document,
                    null,
                    XPathResult.FIRST_ORDERED_NODE_TYPE,
                    null
                ).singleNodeValue;
                
                if (textarea) {
                    textarea.value = '';
                    
                    var nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value").set;
                    nativeInputValueSetter.call(textarea, '$escapedMessage');
                    
                    textarea.dispatchEvent(new Event('input', { bubbles: true }));
                    textarea.dispatchEvent(new Event('change', { bubbles: true }));
                    
                    console.log('Message inserted into chat');
                    return true;
                }
                return false;
            } catch(e) {
                console.error('Error inserting message: ' + e.message);
                return false;
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            if (result == "true") {
                L.d("MoodleFragment", "Message inserted successfully")
            } else {
                L.e("MoodleFragment", "Failed to insert message")
                Toast.makeText(requireContext(), getString(R.string.moodle_failed_to_insert_message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showSendConfirmationDialog() {
        if (messageDialogSuppressedForSession) {
            L.d("MoodleFragment", "Send dialog suppressed - allowing native send")
            allowNativeSend()
            return
        }

        getRecipientInfo { recipientInfo ->
            if (recipientInfo == null) {
                Toast.makeText(requireContext(), getString(R.string.moodle_failed_to_get_recipient_data), Toast.LENGTH_SHORT).show()
                return@getRecipientInfo
            }

            activity?.runOnUiThread {
                val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_moodle_message_input, null)
                val ivUserIcon = dialogView.findViewById<ImageView>(R.id.ivUserIcon)
                val tvRecipientName = dialogView.findViewById<TextView>(R.id.tvRecipientName)
                val userInfoContainer = dialogView.findViewById<LinearLayout>(R.id.userInfoContainer)

                dialogView.findViewById<View>(R.id.etMessageInput)?.visibility = View.GONE
                dialogView.findViewById<View>(R.id.tvCharCounter)?.visibility = View.GONE
                dialogView.findViewById<View>(R.id.btnCopyText)?.visibility = View.GONE
                dialogView.findViewById<View>(R.id.btnDeleteMsg)?.visibility = View.GONE
                dialogView.findViewById<View>(R.id.cbDontShowForNow)?.visibility = View.GONE

                tvRecipientName.text = recipientInfo.username
                userInfoContainer.gravity = Gravity.CENTER

                if (recipientInfo.userIconUrl != null) {
                    loadUserIcon(recipientInfo.userIconUrl, ivUserIcon)
                }

                val dialog = AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.moodle_send_confirm_title))
                    .setMessage(getString(R.string.moodle_send_confirm_message, recipientInfo.username))
                    .setView(dialogView)
                    .setPositiveButton(getString(R.string.moodle_send)) { _, _ ->
                        triggerSendMessage()
                    }
                    .setNegativeButton(getString(R.string.moodle_cancel), null)
                    .show()

                val buttonColor = requireContext().getThemeColor(R.attr.dialogSectionButtonColor)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
            }
        }
    }

    private fun allowNativeSend() {
        val chatType = currentMessageChatType ?: return

        val sendButtonPath = when (chatType) {
            MessageChatType.MAIN_CHAT -> "/html/body/div[1]/div[2]/div/div[1]/div/div/div/div/div/div[2]/div[3]/div/div[1]/div[2]/div/button[2]"
            MessageChatType.SIDEBAR_CHAT -> "/html/body/div[1]/div[3]/div[2]/div[4]/div[1]/div[1]/div[2]/div/button[2]"
        }

        val jsCode = """
        (function() {
            try {
                var sendButton = document.evaluate(
                    '$sendButtonPath',
                    document,
                    null,
                    XPathResult.FIRST_ORDERED_NODE_TYPE,
                    null
                ).singleNodeValue;
                
                if (sendButton) {
                    window.moodleAllowSend = true;
                    
                    var clickEvent = new MouseEvent('click', {
                        view: window,
                        bubbles: true,
                        cancelable: true
                    });
                    sendButton.dispatchEvent(clickEvent);
                    
                    setTimeout(function() {
                        window.moodleAllowSend = false;
                    }, 100);
                    
                    console.log('Native send allowed');
                    return true;
                }
                return false;
            } catch(e) {
                console.error('Error allowing native send: ' + e.message);
                return false;
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            if (result == "true") {
                L.d("MoodleFragment", "Native send executed")
            } else {
                L.e("MoodleFragment", "Failed to execute native send")
            }
        }
    }

    private fun triggerSendMessage() {
        val chatType = currentMessageChatType ?: return

        val sendButtonPath = when (chatType) {
            MessageChatType.MAIN_CHAT -> "/html/body/div[1]/div[2]/div/div[1]/div/div/div/div/div/div[2]/div[3]/div/div[1]/div[2]/div/button[2]"
            MessageChatType.SIDEBAR_CHAT -> "/html/body/div[1]/div[3]/div[2]/div[4]/div[1]/div[1]/div[2]/div/button[2]"
        }

        val jsCode = """
        (function() {
            try {
                var sendButton = document.evaluate(
                    '$sendButtonPath',
                    document,
                    null,
                    XPathResult.FIRST_ORDERED_NODE_TYPE,
                    null
                ).singleNodeValue;
                
                if (sendButton) {
                    window.moodleAllowSend = true;

                    var clickEvent = new MouseEvent('click', {
                        view: window,
                                                bubbles: true,
                        cancelable: true
                    });
                    sendButton.dispatchEvent(clickEvent);

                    setTimeout(function() {
                        window.moodleAllowSend = false;
                    }, 100);
                    
                    console.log('Message sent');
                    return true;
                }
                return false;
            } catch(e) {
                console.error('Error sending message: ' + e.message);
                return false;
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            if (result == "true") {
                L.d("MoodleFragment", "Message sent successfully")
            } else {
                L.e("MoodleFragment", "Failed to send message")
                Toast.makeText(requireContext(), getString(R.string.moodle_failed_to_send_message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    //endregion

    //region **DARK_THEME_INJECTION**

    private fun getSystemDarkMode(): Boolean {
        val appContext = requireContext().applicationContext
        val uiModeManager = appContext.getSystemService(UiModeManager::class.java)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            uiModeManager?.nightMode == UiModeManager.MODE_NIGHT_YES
        } else {
            val baseConfig = appContext.resources.configuration
            val nightMode = baseConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
            nightMode == Configuration.UI_MODE_NIGHT_YES
        }
    }

    private fun removeMoodleDarkMode() {
        val removeCSS = """
        (function() {
            const style = document.getElementById('darkreader-moodle-override');
            if (style) {
                style.remove();
                window.moodleDarkModeInjected = false;
                console.log('Moodle dark mode removed');
                return true;
            }
            return false;
        })();
    """.trimIndent()

        webView.evaluateJavascript(removeCSS) { result ->
            if (result == "true") {
                isDarkModeInjected = false
                L.d("MoodleFragment", "Dark mode CSS removed successfully")
            }
        }
    }

    fun toggleMoodleDarkMode() {
        moodleDarkModeEnabled = !moodleDarkModeEnabled
        sharedPrefs.edit {
            putBoolean("moodle_dark_mode_enabled", moodleDarkModeEnabled)
        }

        if (moodleDarkModeEnabled) {
            webView.visibility = View.INVISIBLE
            isDarkModeReady = false
            webView.reload()
            Toast.makeText(requireContext(), getString(R.string.moodle_activated_dark_mode), Toast.LENGTH_SHORT).show()
        } else {
            removeMoodleDarkMode()
            webView.visibility = View.VISIBLE
            isDarkModeReady = false
            pendingDarkModeUrl = null
            Toast.makeText(requireContext(), getString(R.string.moodle_deactivated_dark_mode), Toast.LENGTH_SHORT).show()
        }
    }

    private fun injectDarkTheme() {
        darkModeInjectionAttempts++

        val darkModeCSS = """
    (function() {
        if (document.getElementById('darkreader-moodle-override')) {
            return 'already_present';
        }
        
        try {
            const css = `
                :root {
                    --dark-bg-primary: #181818;
                    --dark-bg-secondary: #1e1e1e;
                    --dark-surface: #242424;
                    --dark-surface-elevated: #2d2d2d;
                    --dark-surface-hover: #333333;
                    --dark-text-primary: #e8e6e3;
                    --dark-text-secondary: #b3b1ad;
                    --dark-text-tertiary: #8b8883;
                    --dark-border: #3a3a3a;
                    --dark-border-light: #2d2d2d;
                    --dark-primary: #5094ff;
                    --dark-primary-hover: #6ba4ff;
                    --dark-link: #5094ff;
                    --dark-link-hover: #6ba4ff;
                }
                
                /* === PREVENT LIGHT THEME FLASH === */
                * {
                    background-color: transparent !important;
                }
                
                html {
                    background-color: var(--dark-bg-primary) !important;
                    color-scheme: dark !important;
                }
                
                body {
                    background-color: var(--dark-bg-primary) !important;
                    color: var(--dark-text-primary) !important;
                    margin: 0 !important;
                    padding: 0 !important;
                }
                
                #page, #page-wrapper, #page-content,
                .container, .container-fluid,
                #region-main-box, #region-main,
                .region-main, .region-main-content,
                [role="main"] {
                    background-color: var(--dark-bg-primary) !important;
                    color: var(--dark-text-primary) !important;
                }
                
                /* === User initials and avatars === */
                .userinitials {
                    color: inherit !important;
                    background-color: inherit !important;
                    filter: none !important;
                }
                
                .avatar .userinitials {
                    /* Preserve the original background color of user initials */
                    background-color: inherit !important;
                }
                
                /* Dim profile picture avatars */
                .avatar img:not(.userinitials) {
                    filter: brightness(0.8) contrast(1.05) !important;
                }
                
                /* === Collapse/Expand buttons === */
                .icons-collapse-expand,
                .btn-icon[data-bs-toggle="collapse"],
                a.btn.btn-icon[data-for="sectiontoggler"] {
                    background-color: var(--dark-surface-elevated) !important;
                    color: var(--dark-text-primary) !important;
                    border-color: var(--dark-border) !important;
                }
                
                .icons-collapse-expand:hover,
                .btn-icon[data-bs-toggle="collapse"]:hover {
                    background-color: var(--dark-surface-hover) !important;
                }
                
                .icons-collapse-expand i,
                .btn-icon[data-bs-toggle="collapse"] i {
                    color: var(--dark-text-primary) !important;
                }
                
                /* === Activity header and dates === */
                .activity-header,
                [data-for="page-activity-header"] {
                    background-color: var(--dark-surface) !important;
                    color: var(--dark-text-primary) !important;
                }
                
                .activity-information,
                [data-region="activity-information"] {
                    background-color: transparent !important;
                    color: var(--dark-text-primary) !important;
                }
                
                .activity-dates,
                [data-region="activity-dates"] {
                    background-color: transparent !important;
                    color: var(--dark-text-primary) !important;
                }
                
                .activity-dates > div {
                    background-color: transparent !important;
                    color: var(--dark-text-secondary) !important;
                }
                
                .activity-dates strong {
                    color: var(--dark-text-primary) !important;
                }
                
                .activity-description {
                    background-color: transparent !important;
                    color: var(--dark-text-primary) !important;
                }
                
                .activity-description .box,
                .activity-description .generalbox {
                    background-color: var(--dark-surface) !important;
                    color: var(--dark-text-primary) !important;
                    border-color: var(--dark-border) !important;
                }
                
                /* === Message popover toggle background === */
                .popover-region[data-region="popover-region-messages"] {
                    background-color: transparent !important;
                }
                
                #message-drawer-toggle-68e669b44148568e669b43e32a23,
                a[id*="message-drawer-toggle"] {
                    background-color: transparent !important;
                }
                
                .popover-region-messages .nav-link {
                    background-color: transparent !important;
                }
                
                /* Ensure message icon has correct colors */
                .popover-region[data-region="popover-region-messages"] i.fa-message {
                    color: var(--dark-text-primary) !important;
                }
                
                /* Message count badge */
                .popover-region .count-container,
                .popover-region[data-region="popover-region-messages"] .count-container {
                    background-color: var(--dark-primary) !important;
                    color: #ffffff !important;
                }
                
                /* === LOGIN PAGE SPECIFIC BACKGROUND === */
                .pagelayout-login #page {
                    background-color: var(--dark-bg-primary) !important;
                    background-image: linear-gradient(to right, var(--dark-bg-primary) 0%, var(--dark-bg-secondary) 100%) !important;
                }
                
                /* === LOGIN PAGE SPECIFIC === */
                .login-wrapper, .login-container,
                .loginform, .login-form {
                    background-color: var(--dark-bg-primary) !important;
                    color: var(--dark-text-primary) !important;
                }
                
                .login-heading {
                    color: var(--dark-text-primary) !important;
                }
                
                .login-instructions, .login-divider {
                    background-color: transparent !important;
                    color: var(--dark-text-secondary) !important;
                    border-color: var(--dark-border) !important;
                }
                
                /* === CARDS & BLOCKS === */
                .card {
                    /* Make card background transparent so body-bg shows through */
                    background-color: transparent !important;
                    border-color: var(--dark-border) !important;
                    color: var(--dark-text-primary) !important;
                    /* Preserve rounded corners - using !important on the property itself */
                    border-radius: 0.375rem !important;
                    overflow: hidden !important;
                    /* Override Bootstrap variable */
                    --bs-card-bg: transparent !important;
                }
                
                .card-body {
                    /* Gray background for card bodies */
                    background-color: var(--dark-surface) !important;
                    border-color: var(--dark-border) !important;
                    color: var(--dark-text-primary) !important;
                }
                
                /* Card body padding fix for mobile */
                @media (max-width: 767.98px) {
                    #page-header .card .card-body {
                        padding: 0.5rem !important;
                    }
                }
                
                .card-header, .card-footer {
                    background-color: var(--dark-surface) !important;
                    border-color: var(--dark-border) !important;
                    color: var(--dark-text-primary) !important;
                }
                
                .card-header {
                    background-color: var(--dark-surface-elevated) !important;
                }
                
                /* Block sections - keep gray background */
                [class*="block_"], [id*="block-"],
                .block, .block-region {
                    background-color: var(--dark-surface) !important;
                    color: var(--dark-text-primary) !important;
                    border-color: var(--dark-border) !important;
                    border-radius: 0.375rem !important;
                    overflow: hidden !important;
                }
                
                /* Specific block sections that are cards */
                section.block.card,
                section[class*="block_"].card {
                    background-color: transparent !important;
                    border-radius: 0.375rem !important;
                    overflow: hidden !important;
                    --bs-card-bg: transparent !important;
                }
                
                /* Block card bodies should have gray background */
                section.block.card .card-body,
                section[class*="block_"].card .card-body {
                    background-color: var(--dark-surface) !important;
                }
                
                /* Blocks column */
                [data-region="blocks-column"] {
                    background-color: transparent !important;
                }
                
                /* === NAVIGATION & HEADER === */
                .navbar, nav, .fixed-top {
                    background-color: var(--dark-surface-elevated) !important;
                    border-color: var(--dark-border) !important;
                }
                
                .navbar-nav, .nav-link, .nav-item {
                    color: var(--dark-text-primary) !important;
                }

                nav.navbar-nav.d-md-none {
                    background-color: var(--dark-surface) !important;
                }
                
                #page-header, .page-header-headings {
                    background-color: transparent !important;
                    color: var(--dark-text-primary) !important;
                }

                .page-context-header, .page-header-headings,
                .d-flex.align-items-center {
                    background-color: transparent !important;
                    color: var(--dark-text-primary) !important;
                }
                
                /* === BREADCRUMB === */
                .breadcrumb {
                    background-color: transparent !important;
                }
                
                /* Breadcrumb links should use the link color */
                .breadcrumb-item a {
                    color: var(--dark-link) !important;
                }
                
                .breadcrumb-item a:hover {
                    color: var(--dark-link-hover) !important;
                }
                
                /* Non-link breadcrumb items */
                .breadcrumb-item {
                    color: var(--dark-text-secondary) !important;
                }
                
                .breadcrumb-item + .breadcrumb-item::before {
                    color: var(--dark-text-tertiary) !important;
                }

                nav[aria-label="Navigationsleiste"] {
                    background-color: transparent !important;
                }
                
                /* === COURSE CONTENT === */
                .course-content {
                    background-color: var(--dark-bg-primary) !important;
                    color: var(--dark-text-primary) !important;
                }
                
                /* === FORMS & INPUTS === */
                input, textarea, select {
                    background-color: var(--dark-surface-elevated) !important;
                    color: var(--dark-text-primary) !important;
                    border-color: var(--dark-border) !important;
                }
                
                .form-control, .form-control-lg {
                    background-color: var(--dark-surface-elevated) !important;
                    color: var(--dark-text-primary) !important;
                    border-color: var(--dark-border) !important;
                }
                
                .form-control:focus, input:focus, textarea:focus {
                    background-color: var(--dark-surface-elevated) !important;
                    color: var(--dark-text-primary) !important;
                    border-color: var(--dark-primary) !important;
                    box-shadow: 0 0 0 0.2rem rgba(80, 148, 255, 0.25) !important;
                }
                
                input::placeholder, textarea::placeholder {
                    color: var(--dark-text-tertiary) !important;
                    opacity: 0.7 !important;
                }
                
                .input-group-text {
                    background-color: var(--dark-surface-elevated) !important;
                    color: var(--dark-text-secondary) !important;
                    border-color: var(--dark-border) !important;
                }
                
                /* === BUTTONS === */
                .btn {
                    color: var(--dark-text-primary) !important;
                    border-color: var(--dark-border) !important;
                }
                
                .btn-secondary {
                    background-color: var(--dark-surface-elevated) !important;
                    border-color: var(--dark-border) !important;
                    color: var(--dark-text-primary) !important;
                }
                
                .btn-secondary:hover {
                    background-color: var(--dark-surface-hover) !important;
                    border-color: var(--dark-border) !important;
                }
                
                .btn-primary {
                    background-color: var(--dark-primary) !important;
                    border-color: var(--dark-primary) !important;
                    color: #ffffff !important;
                }
                
                .btn-primary:hover {
                    background-color: var(--dark-primary-hover) !important;
                    border-color: var(--dark-primary-hover) !important;
                }
                
                .btn-link {
                    color: var(--dark-link) !important;
                    background-color: transparent !important;
                }
                
                .btn-link:hover {
                    color: var(--dark-link-hover) !important;
                    background-color: transparent !important;
                }

                .btn-submit.search-icon {
                    background-color: var(--dark-surface-elevated) !important;
                    border-color: var(--dark-border) !important;
                }
                
                .btn-submit.search-icon:hover {
                    background-color: var(--dark-surface-hover) !important;
                }
                
                button, [role="button"] {
                    color: var(--dark-text-primary) !important;
                }
                
                /* === LINKS === */
                a {
                    color: var(--dark-link) !important;
                }
                
                a:hover {
                    color: var(--dark-link-hover) !important;
                }
                
                /* === DROPDOWNS & MENUS === */
                .dropdown-menu {
                    background-color: var(--dark-surface-elevated) !important;
                    border-color: var(--dark-border) !important;
                }
                
                .dropdown-item {
                    background-color: transparent !important;
                    color: var(--dark-text-primary) !important;
                }
                
                .dropdown-item:hover, .dropdown-item:focus {
                    background-color: var(--dark-surface-hover) !important;
                    color: var(--dark-text-primary) !important;
                }
                
                .dropdown-divider {
                    border-color: var(--dark-border) !important;
                }
                
                /* === LISTS === */
                .list-group {
                    background-color: transparent !important;
                    /* Fix border color variable */
                    --bs-list-group-border-color: var(--dark-border) !important;
                }
                
                .list-group-item {
                    background-color: var(--dark-surface) !important;
                    color: var(--dark-text-primary) !important;
                    border-color: var(--dark-border) !important;
                    /* Override Bootstrap's --bs-border-color */
                    --bs-border-color: var(--dark-border) !important;
                }
                
                .list-group-item:hover {
                    background-color: var(--dark-surface-elevated) !important;
                }
                
                /* === TABLES === */
                table, .table {
                    background-color: var(--dark-surface) !important;
                    color: var(--dark-text-primary) !important;
                    border-color: var(--dark-border) !important;
                }
                
                thead, tbody, tfoot, tr, td, th {
                    background-color: transparent !important;
                    color: var(--dark-text-primary) !important;
                    border-color: var(--dark-border-light) !important;
                }
                
                .table-striped tbody tr:nth-of-type(odd) {
                    background-color: var(--dark-surface-elevated) !important;
                }
                
                /* === MODALS & POPOVERS === */
                .modal-content {
                    background-color: var(--dark-surface) !important;
                    color: var(--dark-text-primary) !important;
                    border-color: var(--dark-border) !important;
                }
                
                .modal-header, .modal-footer {
                    background-color: var(--dark-surface-elevated) !important;
                    border-color: var(--dark-border) !important;
                }
                
                .popover {
                    background-color: var(--dark-surface-elevated) !important;
                    border-color: var(--dark-border) !important;
                }
                
                .popover-body, .popover-header {
                    background-color: transparent !important;
                    color: var(--dark-text-primary) !important;
                }
                
                .tooltip-inner {
                    background-color: var(--dark-surface-elevated) !important;
                    color: var(--dark-text-primary) !important;
                }
                
                /* === POPOVER REGIONS === */
                .popover-region-container {
                    background-color: var(--dark-surface-elevated) !important;
                    border-color: var(--dark-border) !important;
                }
                
                .popover-region-header-container {
                    background-color: var(--dark-surface-elevated) !important;
                    border-bottom: 1px solid var(--dark-border) !important;
                }
                
                .popover-region-content-container {
                    background-color: var(--dark-surface) !important;
                }
                
                .popover-region-footer-container {
                    background-color: var(--dark-surface-elevated) !important;
                    border-top: 1px solid var(--dark-border) !important;
                }

                .popover-region-toggle,
                #message-drawer-toggle-68e6603dcdd8368e6603dc094318,
                a[id*="message-drawer-toggle"] {
                    background-color: transparent !important;
                }
                
                [data-region="popover-region-messages"] .popover-region-toggle {
                    background-color: transparent !important;
                }
                
                /* === ALERTS & NOTIFICATIONS === */
                .alert {
                    background-color: var(--dark-surface) !important;
                    color: var(--dark-text-primary) !important;
                    border-color: var(--dark-border) !important;
                }
                
                [data-region*="notification"], [data-region*="message"] {
                    background-color: var(--dark-surface) !important;
                    color: var(--dark-text-primary) !important;
                }
                
                /* === PAGINATION === */
                .pagination {
                    /* Preserve pagination rounded corners */
                    --bs-pagination-border-radius: 0.375rem !important;
                }
                
                /* Paging control container - rounded corners */
                [data-region="paging-control-container"] {
                    border-radius: 0.375rem !important;
                    overflow: hidden !important;
                }
                
                [data-region="paging-control-container"] .pagination {
                    border-radius: 0.375rem !important;
                }
                
                .page-item .page-link {
                    background-color: var(--dark-surface) !important;
                    border-color: var(--dark-border) !important;
                    color: var(--dark-text-primary) !important;
                }
                
                /* First and last pagination */
                .page-item:first-child .page-link {
                    border-top-left-radius: 0.375rem !important;
                    border-bottom-left-radius: 0.375rem !important;
                }
                
                .page-item:last-child .page-link {
                    border-top-right-radius: 0.375rem !important;
                    border-bottom-right-radius: 0.375rem !important;
                }
                
                .page-item.disabled .page-link {
                    background-color: var(--dark-surface-elevated) !important;
                    border-color: var(--dark-border-light) !important;
                    color: var(--dark-text-tertiary) !important;
                }
                
                .page-item:not(.disabled) .page-link:hover {
                    background-color: var(--dark-surface-hover) !important;
                    border-color: var(--dark-border) !important;
                    color: var(--dark-text-primary) !important;
                }
                
                /* === MOODLE SPECIFIC ELEMENTS === */
                .activity, .section, .course-content,
                .activityinstance, .contentwithoutlink {
                    background-color: transparent !important;
                    color: var(--dark-text-primary) !important;
                }
                
                .activity-item, .section-item {
                    background-color: var(--dark-surface) !important;
                    color: var(--dark-text-primary) !important;
                }
                
                .course-info-container {
                    background-color: var(--dark-surface) !important;
                }

                .activitybadge.badge {
                    background-color: transparent !important;
                    border: none !important;
                }

                .expanded-icon, .collapsed-icon {
                    background-color: transparent !important;
                }
                
                .expanded-icon i, .collapsed-icon i {
                    color: var(--dark-text-primary) !important;
                }
                
                /* === CALENDAR === */
                .calendar-month, .calendarmonth, .day {
                    background-color: var(--dark-surface) !important;
                    color: var(--dark-text-primary) !important;
                    border-color: var(--dark-border) !important;
                }
                
                /* === BADGES === */
                .badge {
                    color: var(--dark-text-primary) !important;
                    border: 1px solid var(--dark-border) !important;
                }
                
                .badge-primary, .bg-primary {
                    background-color: var(--dark-primary) !important;
                    color: #ffffff !important;
                }
                
                /* === TEXT UTILITIES === */
                .text-muted {
                    color: var(--dark-text-secondary) !important;
                }
                
                .text-secondary {
                    color: var(--dark-text-secondary) !important;
                }
                
                h1, h2, h3, h4, h5, h6,
                .h1, .h2, .h3, .h4, .h5, .h6 {
                    color: var(--dark-text-primary) !important;
                }
                
                p, span, div, label, legend {
                    color: inherit !important;
                }
                
                /* === BORDERS & DIVIDERS === */
                hr, .border, .border-top, .border-bottom,
                .border-left, .border-right {
                    border-color: var(--dark-border) !important;
                }
                
                .divider {
                    background-color: var(--dark-border) !important;
                    border-color: var(--dark-border) !important;
                }
                
                /* === FOOTER === */
                footer, #page-footer {
                    background-color: var(--dark-surface) !important;
                    color: var(--dark-text-primary) !important;
                }
                
                .footer-dark, .footer-dark-inner {
                    background-color: var(--dark-surface) !important;
                    color: var(--dark-text-primary) !important;
                }
                
                .footer-dark.bg-dark {
                    background-color: var(--dark-surface) !important;
                }
                
                /* === ICONS === */
                img.icon, img.iconsmall, img.iconlarge,
                img[class*="icon"], 
                .icon, i[class*="fa-"],
                [data-region="activity-icon"] {
                    filter: none !important;
                    opacity: 1 !important;
                }
                
                img.activityicon:not([src*=".svg"]) {
                    filter: invert(0.9) hue-rotate(180deg) !important;
                }
                
                img[src*=".svg"], svg {
                    filter: none !important;
                }
                
                /* === IMAGES === */
                img:not(.icon):not([class*="icon"]):not([data-region="activity-icon"]):not(.userinitials) {
                    filter: brightness(0.8) contrast(1.05) !important;
                }
                
                /* === LOADING PLACEHOLDERS === */
                [class*="bg-pulse-grey"] {
                    background-color: var(--dark-surface-elevated) !important;
                }
                
                .bg-light, .bg-white {
                    background-color: var(--dark-surface) !important;
                    color: var(--dark-text-primary) !important;
                }
                
                /* === SCROLLBARS === */
                ::-webkit-scrollbar {
                    width: 12px;
                    height: 12px;
                    background-color: var(--dark-bg-primary);
                }
                
                ::-webkit-scrollbar-track {
                    background-color: var(--dark-bg-secondary);
                }
                
                ::-webkit-scrollbar-thumb {
                    background-color: var(--dark-surface-elevated);
                    border-radius: 6px;
                    border: 2px solid var(--dark-bg-secondary);
                }
                
                ::-webkit-scrollbar-thumb:hover {
                    background-color: var(--dark-surface-hover);
                }
                
                /* === SELECTION === */
                ::selection {
                    background-color: var(--dark-primary) !important;
                    color: #ffffff !important;
                }
                
                ::-moz-selection {
                    background-color: var(--dark-primary) !important;
                    color: #ffffff !important;
                }
            `;
            
            const style = document.createElement('style');
            style.id = 'darkreader-moodle-override';
            style.textContent = css;
            document.head.appendChild(style);

            document.documentElement.style.backgroundColor = '#181818';
            document.body.style.backgroundColor = '#181818';
            document.body.style.color = '#e8e6e3';
            
            window.moodleDarkModeInjected = true;
            console.log('Moodle dark mode CSS injected');
            return 'success';
        } catch(e) {
            console.error('Error injecting dark mode: ' + e.message);
            return 'error: ' + e.message;
        }
    })();
""".trimIndent()

        webView.evaluateJavascript(darkModeCSS) { result ->
            val cleanResult = result?.trim('"') ?: "null"
            L.d(
                "MoodleFragment",
                "Dark mode injection attempt $darkModeInjectionAttempts result: $cleanResult"
            )

            if ((cleanResult.contains("error") || cleanResult == "null") && darkModeInjectionAttempts < MAX_DARK_MODE_RETRIES
            ) {

                val retryDelay = when (darkModeInjectionAttempts) {
                    1 -> 50L
                    2 -> 100L
                    3 -> 200L
                    4 -> 300L
                    else -> 500L
                }

                L.d(
                    "MoodleFragment",
                    "Dark mode injection failed, retrying in ${retryDelay}ms (attempt ${darkModeInjectionAttempts + 1}/$MAX_DARK_MODE_RETRIES)"
                )

                darkModeRetryRunnable = Runnable {
                    if (isFragmentActive && isAdded) {
                        injectDarkTheme()
                    }
                }

                darkModeRetryHandler = Handler(Looper.getMainLooper())
                darkModeRetryHandler?.postDelayed(darkModeRetryRunnable!!, retryDelay)
            } else if (cleanResult == "already_present" || cleanResult == "success") {
                L.d("MoodleFragment", "Dark mode CSS successfully injected")
                darkModeRetryHandler?.removeCallbacks(darkModeRetryRunnable ?: Runnable {})
            }
        }
    }

    private fun verifyAndFixDarkMode() {
        val verifyAndFixCSS = """
        (function() {
            try {
                const styleElement = document.getElementById('darkreader-moodle-override');
                if (styleElement && styleElement.textContent && styleElement.textContent.length > 100) {
                    console.log('Dark mode CSS verified - present in DOM with content');
                    return 'verified';
                }

                if (window.moodleDarkModeInjected === true) {
                    console.log('Dark mode injected flag is set');
                    return 'flag_set';
                }

                console.log('Dark mode CSS NOT found - need to reinject');
                return 'missing';
            } catch(e) {
                console.error('Error verifying dark mode: ' + e.message);
                return 'error';
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(verifyAndFixCSS) { result ->
            val cleanResult = result?.trim('"') ?: "null"
            L.d("MoodleFragment", "Dark mode verification result: $cleanResult")

            when (cleanResult) {
                "verified", "flag_set" -> {
                    activity?.runOnUiThread {
                        webView.visibility = View.VISIBLE
                        hideLoadingBar()
                    }
                }
                "missing" -> {
                    L.w("MoodleFragment", "Dark mode CSS missing on page finish - attempting emergency injection")

                    darkModeInjectionAttempts = 0
                    injectDarkTheme()

                    Handler(Looper.getMainLooper()).postDelayed({
                        activity?.runOnUiThread {
                            webView.visibility = View.VISIBLE
                            hideLoadingBar()
                        }
                    }, 150)
                }
                else -> {
                    activity?.runOnUiThread {
                        webView.visibility = View.VISIBLE
                        hideLoadingBar()
                    }
                }
            }
        }
    }

    //endregion

    //region **COURSE_ENTRY_DOWNLOADS**

    private fun injectCourseDownloadButton() {
        if (!isFragmentActive || !isAdded) return

        val buttonText = if (Locale.getDefault().language == "de") "Herunterladen" else "Download"

        val jsCode = """
        (function() {
            try {
                console.log('=== Injecting Course Download Button ===');

                var existingButton = document.getElementById('course_download');
                if (existingButton) {
                    console.log('Download button already exists');
                    return 'exists';
                }

                var targetDiv = document.evaluate(
                    '/html/body/div[1]/div[2]/header/div/div/div',
                    document,
                    null,
                    XPathResult.FIRST_ORDERED_NODE_TYPE,
                    null
                ).singleNodeValue;
                
                if (!targetDiv) {
                    console.log('Target div not found');
                    return 'not_found';
                }
                
                console.log('Found target div, injecting button');

                var downloadButton = document.createElement('button');
                downloadButton.type = 'submit';
                downloadButton.className = 'btn btn-primary';
                downloadButton.id = 'course_download';
                downloadButton.textContent = '$buttonText';
                downloadButton.style.marginLeft = '8px';

                downloadButton.onclick = function(e) {
                    e.preventDefault();
                    e.stopPropagation();
                    window.courseDownloadClicked = true;
                    console.log('Course download button clicked');
                    return false;
                };

                targetDiv.appendChild(downloadButton);
                console.log('Download button injected successfully');
                
                return 'success';
                
            } catch(e) {
                console.error('Error injecting button: ' + e.message);
                return 'error';
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            val cleanResult = result.trim('"')
            L.d("MoodleFragment", "Download button injection result: $cleanResult")

            if (cleanResult == "success") {
                monitorCourseDownloadButton()
            }
        }
    }

    private fun monitorCourseDownloadButton() {
        if (!isFragmentActive || !isAdded) return

        stopDownloadButtonMonitoring()

        isMonitoringDownloadButton = true
        val checkInterval = 300L
        downloadButtonMonitorHandler = Handler(Looper.getMainLooper())

        downloadButtonMonitorRunnable = object : Runnable {
            override fun run() {
                if (!isFragmentActive || !isAdded || !isMonitoringDownloadButton) {
                    stopDownloadButtonMonitoring()
                    return
                }

                val currentUrl = webView.url ?: ""
                if (!currentUrl.startsWith("https://moodle.kleyer.eu/course/view.php")) {
                    stopDownloadButtonMonitoring()
                    return
                }

                val jsCode = """
                (function() {
                    if (window.courseDownloadClicked === true) {
                        window.courseDownloadClicked = false;
                        return true;
                    }
                    return false;
                })();
            """.trimIndent()

                webView.evaluateJavascript(jsCode) { result ->
                    if (!isFragmentActive || !isAdded || !isMonitoringDownloadButton) {
                        stopDownloadButtonMonitoring()
                        return@evaluateJavascript
                    }

                    if (result == "true") {
                        L.d("MoodleFragment", "Download button clicked - parsing course entries")
                        parseCourseEntries()
                    }

                    downloadButtonMonitorHandler?.postDelayed(this, checkInterval)
                }
            }
        }

        downloadButtonMonitorHandler?.post(downloadButtonMonitorRunnable!!)
    }

    private fun stopDownloadButtonMonitoring() {
        isMonitoringDownloadButton = false
        downloadButtonMonitorRunnable?.let {
            downloadButtonMonitorHandler?.removeCallbacks(it)
        }
        downloadButtonMonitorHandler = null
        downloadButtonMonitorRunnable = null
        L.d("MoodleFragment", "Stopped download button monitoring")
    }

    private fun parseCourseEntries() {
        val jsCode = """
        (function() {
            try {
                console.log('=== Parsing Course Entries ===');
                
                var mainList = document.evaluate(
                    '/html/body/div[1]/div[2]/div/div[1]/div/div/div/div/ul',
                    document,
                    null,
                    XPathResult.FIRST_ORDERED_NODE_TYPE,
                    null
                ).singleNodeValue;
                
                if (!mainList) {
                    console.log('Main course list not found');
                    return JSON.stringify({ success: false, reason: 'no_list' });
                }
                
                console.log('Found main list with ' + mainList.children.length + ' sections');
                
                var entries = [];
                
                function parseEntry(entryLi, sectionName) {
                    try {
                        // li/div/div[2]
                        var entryMainDiv = entryLi.querySelector(':scope > div > div:nth-child(2)');
                        if (!entryMainDiv) {
                            console.log('No main div found in entry');
                            return null;
                        }
                        
                        // 1. entry url (path: div[2]/div/div/a)
                        var linkElement = entryMainDiv.querySelector(':scope > div:nth-child(2) > div > div > a.aalink');
                        if (!linkElement) {
                            console.log('No link element found in entry');
                            return null;
                        }
                        
                        var entryUrl = linkElement.href;
                        
                        // 2. icon url (path: div[1]/img)
                        var iconElement = entryMainDiv.querySelector(':scope > div:nth-child(1) > img.activityicon');
                        var iconUrl = iconElement ? iconElement.src : '';
                        
                        // 3. link type (path: div[2]/div/div/a/span/span)
                        var accessHideSpan = linkElement.querySelector('span.accesshide');
                        var linkType = accessHideSpan ? accessHideSpan.textContent.trim() : '';
                        
                        // 4. entry name (path: div[2]/div/div/a/span)
                        var instanceNameSpan = linkElement.querySelector('span.instancename');
                        var entryName = '';
                        if (instanceNameSpan) {
                            var clone = instanceNameSpan.cloneNode(true);
                            var accessHideInClone = clone.querySelector('span.accesshide');
                            if (accessHideInClone) {
                                accessHideInClone.remove();
                            }
                            entryName = clone.textContent.trim();
                        }
                        
                        if (!entryName) {
                            console.log('Entry has no name, skipping');
                            return null;
                        }
                        
                        // filter out "forum", "aufgabe" and "glossar" entries
                        var linkTypeLower = linkType.toLowerCase();
                        if (linkTypeLower.includes('forum') || linkTypeLower.includes('aufgabe') || linkTypeLower.includes('glossar')) {
                            console.log('Skipping entry (filtered type): ' + linkType);
                            return null;
                        }
                        
                        console.log('Entry found: "' + entryName + '" Type: "' + linkType + '" in section: "' + sectionName + '"');
                        
                        return {
                            url: entryUrl,
                            iconUrl: iconUrl,
                            linkType: linkType,
                            name: entryName,
                            sectionName: sectionName
                        };
                        
                    } catch(entryError) {
                        console.error('Error parsing entry: ' + entryError.message);
                        return null;
                    }
                }
                
                function processNestedSection(nestedLi, parentSectionName) {
                    try {
                        console.log('Processing nested section item under: ' + parentSectionName);
                        
                        // nested structure: li/div/div/div
                        var nestedDivContainer = nestedLi.querySelector(':scope > div > div > div');
                        if (!nestedDivContainer) {
                            console.log('No nested div container found');
                            return [];
                        }
                        
                        // get nested section name from: div/div/a
                        var nestedSectionNameElement = nestedDivContainer.querySelector(':scope > div > a');
                        var nestedSectionName = nestedSectionNameElement ? nestedSectionNameElement.textContent.trim() : '${getString(R.string.moodle_nested)}';
                        
                        console.log('Found nested section: "' + nestedSectionName + '"');
                        
                        var fullSectionName = parentSectionName + ' > ' + nestedSectionName;
                        
                        // entries path: div/ul/li/div/div[2]/ul
                        var nestedUl = nestedDivContainer.querySelector(':scope > ul');
                        if (!nestedUl) {
                            console.log('No UL found in nested section');
                            return [];
                        }
                        
                        var nestedWrapperLi = nestedUl.querySelector(':scope > li');
                        if (!nestedWrapperLi) {
                            console.log('No wrapper LI found in nested section');
                            return [];
                        }
                        
                        var nestedContentDiv = nestedWrapperLi.querySelector(':scope > div > div:nth-child(2)');
                        if (!nestedContentDiv) {
                            console.log('No content div[2] found in nested section');
                            return [];
                        }
                        
                        var nestedEntryList = nestedContentDiv.querySelector(':scope > ul');
                        if (!nestedEntryList) {
                            console.log('No entry list found in nested section');
                            return [];
                        }
                        
                        var nestedEntryItems = nestedEntryList.querySelectorAll(':scope > li');
                        console.log('Found ' + nestedEntryItems.length + ' entries in nested section: ' + nestedSectionName);
                        
                        var nestedEntries = [];
                        for (var k = 0; k < nestedEntryItems.length; k++) {
                            var entry = parseEntry(nestedEntryItems[k], fullSectionName);
                            if (entry) {
                                nestedEntries.push(entry);
                            }
                        }
                        
                        return nestedEntries;
                    } catch(nestedError) {
                        console.error('Error processing nested section: ' + nestedError.message);
                        console.error('Stack: ' + nestedError.stack);
                        return [];
                    }
                }
                
                // iterate through all main sections
                for (var i = 0; i < mainList.children.length; i++) {
                    var sectionLi = mainList.children[i];
                    
                    if (sectionLi.tagName !== 'LI') {
                        continue;
                    }
                    
                    var sectionName = sectionLi.getAttribute('data-sectionname') || 'Section ' + (i + 1);
                    console.log('=== Processing main section [' + i + ']: ' + sectionName + ' ===');

                    // li/div
                    var sectionDiv = sectionLi.querySelector(':scope > div');
                    if (!sectionDiv) {
                        console.log('No direct div child in section');
                        continue;
                    }

                    // li/div/div[2]
                    var contentDiv = sectionDiv.querySelector(':scope > div:nth-child(2)');
                    if (!contentDiv) {
                        console.log('Section "' + sectionName + '" has no content div (div[2])');
                        continue;
                    }

                    // li/div/div[2]/ul
                    var entryList = contentDiv.querySelector(':scope > ul');
                    if (!entryList) {
                        console.log('Section "' + sectionName + '" has no entry list');
                        continue;
                    }

                    if (entryList.children.length === 0) {
                        console.log('Section "' + sectionName + '" has empty entry list');
                        continue;
                    }
                    
                    console.log('Section "' + sectionName + '" has ' + entryList.children.length + ' items');
                    
                    // get all direct <li> children
                    var entryItems = entryList.querySelectorAll(':scope > li');
                    
                    for (var j = 0; j < entryItems.length; j++) {
                        var entryLi = entryItems[j];
                        
                        console.log('Processing item ' + j + ' in section ' + sectionName);

                        var possibleNestedDiv = entryLi.querySelector(':scope > div > div > div');
                        
                        if (possibleNestedDiv) {
                            var hasNestedUl = possibleNestedDiv.querySelector(':scope > ul');
                            if (hasNestedUl) {
                                console.log('Item ' + j + ' is a nested section');
                                var nestedEntries = processNestedSection(entryLi, sectionName);
                                entries = entries.concat(nestedEntries);
                            } else {
                                console.log('Item ' + j + ' is a regular entry (deep structure)');
                                var entry = parseEntry(entryLi, sectionName);
                                if (entry) {
                                    entries.push(entry);
                                }
                            }
                        } else {
                            console.log('Item ' + j + ' is a regular entry');
                            var entry = parseEntry(entryLi, sectionName);
                            if (entry) {
                                entries.push(entry);
                            }
                        }
                    }
                }
                
                console.log('=== Parsing Complete ===');
                console.log('Total entries parsed: ' + entries.length);
                
                return JSON.stringify({
                    success: true,
                    entries: entries
                });
                
            } catch(e) {
                console.error('Critical error parsing course: ' + e.message);
                console.error('Stack: ' + e.stack);
                return JSON.stringify({
                    success: false,
                    reason: 'exception',
                    error: e.message,
                    stack: e.stack
                });
            }
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            try {
                L.d("MoodleFragment", "Raw parse result: $result")

                if (result == null || result == "null" || result.trim('"').isEmpty()) {
                    L.e("MoodleFragment", "JavaScript returned null or empty result")
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.moodle_failed_to_parse_course),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@evaluateJavascript
                }

                val cleanResult = result.replace("\\\"", "\"").trim('"')
                L.d("MoodleFragment", "Cleaned parse result: $cleanResult")

                if (!cleanResult.startsWith("{") || !cleanResult.endsWith("}")) {
                    L.e("MoodleFragment", "Result is not valid JSON: $cleanResult")
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.moodle_error_parsing_entries),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@evaluateJavascript
                }

                val jsonResult = JSONObject(cleanResult)
                val success = jsonResult.optBoolean("success", false)

                if (success) {
                    val entriesArray = jsonResult.getJSONArray("entries")
                    val entries = mutableListOf<CourseEntry>()

                    for (i in 0 until entriesArray.length()) {
                        val entryJson = entriesArray.getJSONObject(i)
                        entries.add(CourseEntry(
                            url = entryJson.getString("url"),
                            iconUrl = entryJson.getString("iconUrl"),
                            linkType = entryJson.getString("linkType"),
                            name = entryJson.getString("name"),
                            sectionName = entryJson.getString("sectionName")
                        ))
                    }

                    L.d("MoodleFragment", "Parsed ${entries.size} course entries")

                    if (entries.isEmpty()) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.moodle_no_downloadable_entries),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        showCourseDownloadDialog(entries)
                    }
                } else {
                    val reason = jsonResult.optString("reason", "unknown")
                    val error = jsonResult.optString("error", "")
                    val stack = jsonResult.optString("stack", "")
                    L.e("MoodleFragment", "Failed to parse: $reason - $error")
                    L.e("MoodleFragment", "Stack: $stack")
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.moodle_failed_to_parse_course),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: JSONException) {
                L.e("MoodleFragment", "JSON parsing error - Raw result: $result", e)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.moodle_error_parsing_entries),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                L.e("MoodleFragment", "Error processing course entries", e)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.moodle_error_parsing_entries),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    class CourseSectionAdapter(
        private var sections: List<CourseSection>,
        private val allSections: List<CourseSection>,
        private val selectedEntries: MutableMap<String, Boolean>,
        private val downloadedEntries: Set<String> = emptySet(),
        private val onSelectionChanged: () -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val VIEW_TYPE_SECTION = 0
            private const val VIEW_TYPE_ENTRY = 1
        }

        private val displayItems = mutableListOf<Any>()

        init {
            rebuildDisplayItems()
        }

        fun updateSections(newSections: List<CourseSection>) {
            sections = newSections
            rebuildDisplayItems()
            notifyDataSetChanged()
        }

        private fun rebuildDisplayItems() {
            displayItems.clear()
            sections.forEach { section ->
                displayItems.add(section)
                if (section.isExpanded) {
                    displayItems.addAll(section.entries)
                }
            }
        }

        override fun getItemViewType(position: Int): Int {
            return when (displayItems[position]) {
                is CourseSection -> VIEW_TYPE_SECTION
                is CourseEntry -> VIEW_TYPE_ENTRY
                else -> throw IllegalArgumentException("Unknown item type")
            }
        }

        class SectionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val checkbox: CheckBox = view.findViewById(R.id.cbSection)
            val name: TextView = view.findViewById(R.id.tvSectionName)
            val expandIcon: ImageView = view.findViewById(R.id.ivExpandIcon)
            val entryCount: TextView = view.findViewById(R.id.tvEntryCount)
        }

        class EntryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val checkbox: CheckBox = view.findViewById(R.id.cbEntry)
            val icon: ImageView = view.findViewById(R.id.ivEntryIcon)
            val name: TextView = view.findViewById(R.id.tvEntryName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                VIEW_TYPE_SECTION -> {
                    val view = LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_course_section, parent, false)
                    SectionViewHolder(view)
                }
                VIEW_TYPE_ENTRY -> {
                    val view = LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_course_entry, parent, false)
                    EntryViewHolder(view)
                }
                else -> throw IllegalArgumentException("Unknown view type")
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is SectionViewHolder -> bindSectionViewHolder(holder, displayItems[position] as CourseSection)
                is EntryViewHolder -> bindEntryViewHolder(holder, displayItems[position] as CourseEntry)
            }
        }

        private fun bindSectionViewHolder(holder: SectionViewHolder, section: CourseSection) {
            holder.name.text = section.sectionName
            holder.entryCount.text = "${section.entries.size}"

            val allEntriesSelected = section.entries.all { entry ->
                selectedEntries["${section.sectionName}_${entry.name}"] == true
            }

            holder.checkbox.setOnCheckedChangeListener(null)
            holder.checkbox.isChecked = allEntriesSelected
            section.isSelected = allEntriesSelected

            holder.expandIcon.rotation = if (section.isExpanded) 90f else 0f

            holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
                section.isSelected = isChecked
                section.entries.forEach { entry ->
                    selectedEntries["${section.sectionName}_${entry.name}"] = isChecked
                }
                onSelectionChanged()
                notifyDataSetChanged()
            }

            holder.itemView.setOnClickListener {
                section.isExpanded = !section.isExpanded
                rebuildDisplayItems()
                notifyDataSetChanged()
            }
        }

        private fun bindEntryViewHolder(holder: EntryViewHolder, entry: CourseEntry) {
            val section = sections.find { it.entries.contains(entry) }
            val entryKey = "${section?.sectionName}_${entry.name}"
            val isSelected = selectedEntries[entryKey] ?: false
            val parentSelected = section?.isSelected ?: false
            val isDownloaded = downloadedEntries.contains(entryKey)

            holder.checkbox.setOnCheckedChangeListener(null)
            holder.checkbox.isChecked = isSelected
            holder.checkbox.isEnabled = !parentSelected && !isDownloaded
            holder.name.text = entry.name
            holder.name.alpha = if (parentSelected || isDownloaded) 0.6f else 1f

            val iconResId = getIconResourceFromUrl(entry.iconUrl, entry.linkType)
            holder.icon.setImageResource(iconResId)
            holder.icon.setColorFilter(
                holder.itemView.context.getThemeColor(R.attr.iconTintColor),
                PorterDuff.Mode.SRC_IN
            )
            holder.icon.visibility = View.VISIBLE

            holder.checkbox.setOnCheckedChangeListener { _, checked ->
                if (!parentSelected && !isDownloaded) {
                    selectedEntries[entryKey] = checked
                    onSelectionChanged()
                }
            }

            holder.itemView.setOnClickListener {
                if (!parentSelected && !isDownloaded) {
                    holder.checkbox.isChecked = !holder.checkbox.isChecked
                }
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

        private fun getIconResourceFromUrl(iconUrl: String, linkType: String): Int {
            val urlLower = iconUrl.lowercase()

            return when {
                urlLower.contains("/f/pdf") -> R.drawable.ic_entry_pdf
                urlLower.contains("/f/image") -> R.drawable.ic_entry_img
                urlLower.contains("/f/document") -> R.drawable.ic_entry_doc
                urlLower.contains("/f/audio") -> R.drawable.ic_entry_audio
                urlLower.contains("/url/") -> R.drawable.ic_entry_url
                urlLower.contains("/folder/") -> R.drawable.ic_entry_folder
                urlLower.contains("/page/") -> R.drawable.ic_entry_page
                else -> R.drawable.ic_entry_unknown
            }
        }

        override fun getItemCount() = displayItems.size
    }

    private fun showCourseDownloadDialog(entries: List<CourseEntry>) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_course_download, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.courseEntriesRecyclerView)
        val selectAllButton = dialogView.findViewById<Button>(R.id.btnSelectAll)
        val searchEditText = dialogView.findViewById<EditText>(R.id.searchEditText)
        val tvSelectionCount = dialogView.findViewById<TextView>(R.id.tvSelectionCount)
        val tvTotalCount = dialogView.findViewById<TextView>(R.id.tvTotalCount)

        val groupedEntries = entries.groupBy { it.sectionName }

        val downloadedFiles = getDownloadedFileNames()

        val allSections = groupedEntries.map { (sectionName, sectionEntries) ->
            CourseSection(sectionName, sectionEntries, isExpanded = false, isSelected = false)
        }

        var filteredSections = allSections.toList()
        val selectedEntries = mutableMapOf<String, Boolean>()
        val downloadedEntries = mutableSetOf<String>()

        allSections.forEach { section ->
            section.entries.forEach { entry ->
                val entryKey = "${section.sectionName}_${entry.name}"
                val isDownloaded = downloadedFiles.contains(entry.name)
                if (isDownloaded) {
                    selectedEntries[entryKey] = true
                    downloadedEntries.add(entryKey)
                }
            }
        }

        fun updateCounters() {
            val selectedCount = selectedEntries.values.count { it }
            val totalCount = filteredSections.sumOf { it.entries.size }
            tvSelectionCount.text = selectedCount.toString()
            tvTotalCount.text = totalCount.toString()

            val allSelected = filteredSections.isNotEmpty() &&
                    filteredSections.all { section ->
                        section.entries.all { entry ->
                            selectedEntries["${section.sectionName}_${entry.name}"] == true
                        }
                    }

            selectAllButton.text = if (allSelected) {
                getString(R.string.moodle_deselect_all)
            } else {
                getString(R.string.moodle_select_all)
            }
        }

        val adapter = CourseSectionAdapter(
            sections = filteredSections,
            allSections = allSections,
            selectedEntries = selectedEntries,
            downloadedEntries = downloadedEntries,
            onSelectionChanged = { updateCounters() }
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        updateCounters()

        selectAllButton.setOnClickListener {
            val shouldSelect = filteredSections.any { section ->
                section.entries.any { entry ->
                    selectedEntries["${section.sectionName}_${entry.name}"] != true
                }
            }

            filteredSections.forEach { section ->
                section.entries.forEach { entry ->
                    selectedEntries["${section.sectionName}_${entry.name}"] = shouldSelect
                }
            }

            updateCounters()
            adapter.notifyDataSetChanged()
        }

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().lowercase().trim()

                filteredSections = if (query.isEmpty()) {
                    allSections.toList()
                } else {
                    allSections.mapNotNull { section ->
                        val matchingEntries = section.entries.filter { entry ->
                            entry.name.lowercase().contains(query) ||
                                    entry.linkType.lowercase().contains(query)
                        }
                        if (matchingEntries.isNotEmpty()) {
                            section.copy(entries = matchingEntries, isExpanded = true)
                        } else if (section.sectionName.lowercase().contains(query)) {
                            section.copy(isExpanded = true)
                        } else {
                            null
                        }
                    }
                }

                adapter.updateSections(filteredSections)
                updateCounters()
            }
        })

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.moodle_select_entries_to_download))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.moodle_download)) { _, _ ->
                val entriesToDownload = selectedEntries.filter { it.value }.keys.mapNotNull { key ->
                    allSections.flatMap { it.entries }.find { entry ->
                        "${allSections.find { section -> section.entries.contains(entry) }?.sectionName}_${entry.name}" == key
                    }
                }

                if (entriesToDownload.isNotEmpty()) {
                    queueCourseEntriesForDownload(entriesToDownload)
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.moodle_no_entries_selected),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(getString(R.string.moodle_cancel), null)
            .show()

        val buttonColor = requireContext().getThemeColor(R.attr.dialogSectionButtonColor)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
    }

    private fun getDownloadedFileNames(): Set<String> {
        val downloadedNames = mutableSetOf<String>()

        if (downloadsDirectory.exists()) {
            downloadsDirectory.listFiles()?.forEach { courseFolder ->
                if (courseFolder.isDirectory) {
                    courseFolder.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            downloadedNames.add(file.nameWithoutExtension)
                        }
                    }
                }
            }
        }

        return downloadedNames
    }

    private fun queueCourseEntriesForDownload(entries: List<CourseEntry>) {
        val downloadQueue = CourseDownloadQueue.getInstance()
        val currentCourse = webView.title ?: "Unknown Course"

        entries.forEach { entry ->
            val fileName = when {
                entry.linkType.contains("PDF", ignoreCase = true) ->
                    "${entry.name}.pdf"
                entry.linkType.contains("document", ignoreCase = true) ||
                        entry.linkType.contains("doc", ignoreCase = true) ->
                    "${entry.name}.docx"
                entry.linkType.contains("image", ignoreCase = true) -> {
                    val extension = getImageExtensionFromUrl(entry.url)
                    "${entry.name}$extension"
                }
                entry.linkType.contains("audio", ignoreCase = true) ->
                    "${entry.name}.mp3"
                else -> {
                    val extension = entry.url.substringAfterLast(".").takeIf { it.length <= 5 } ?: "bin"
                    "${entry.name}.$extension"
                }
            }

            downloadQueue.addToQueue(
                courseName = currentCourse,
                entryName = entry.name,
                url = entry.url,
                fileName = fileName,
                linkType = entry.linkType,
                iconUrl = entry.iconUrl,
                sectionName = entry.sectionName
            )
        }

        Toast.makeText(
            requireContext(),
            getString(R.string.moodle_downloading_entries, entries.size),
            Toast.LENGTH_SHORT
        ).show()

        updateCourseDownloadsCounter()

        Handler(Looper.getMainLooper()).postDelayed({
            startCourseDownloads()
        }, 500)
    }

    private fun startCourseDownloads() {
        val downloadQueue = CourseDownloadQueue.getInstance()
        val pendingEntries = downloadQueue.getPendingEntries()

        if (pendingEntries.isEmpty()) {
            L.d("MoodleFragment", "No pending downloads")
            return
        }

        L.d("MoodleFragment", "Starting ${pendingEntries.size} downloads")

        pendingEntries.take(3).forEach { entry ->
            downloadCourseEntry(entry)
        }
    }

    private fun sanitizeFileName(name: String): String {
        if (name.isEmpty()) return "unnamed"

        // Replace illegal characters with underscore
        var sanitized = name
            .replace(Regex("[\\\\/:*?\"<>|]"), "_") // Windows illegal chars
            .replace("|", "_") // Pipe
            .replace(":", "_") // Colon
            .replace("*", "_") // Asterisk
            .replace("?", "_") // Question mark
            .replace("\"", "_") // Quote
            .replace("<", "_") // Less than
            .replace(">", "_") // Greater than
            .trim() // Remove leading/trailing spaces

        // Remove control characters
        sanitized = sanitized.replace(Regex("[\\x00-\\x1F\\x7F]"), "")

        // Replace multiple underscores with single underscore
        sanitized = sanitized.replace(Regex("_{2,}"), "_")

        // Remove leading/trailing dots and underscores
        sanitized = sanitized.trim('.', '_')

        // Limit length to avoid filesystem issues (most systems support 255 chars)
        if (sanitized.length > 200) {
            sanitized = sanitized.substring(0, 200)
        }

        // If everything was removed, use a default name
        if (sanitized.isEmpty()) {
            sanitized = "unnamed_file"
        }

        // Ensure it doesn't start with a dot (hidden file on Unix)
        if (sanitized.startsWith(".")) {
            sanitized = "_$sanitized"
        }

        return sanitized
    }

    private fun downloadCourseEntry(entry: CourseDownloadQueue.DownloadQueueEntry) {
        backgroundExecutor.execute {
            try {
                L.d("MoodleFragment", "=== Starting Course Entry Download ===")
                L.d("MoodleFragment", "Entry: ${entry.entryName}")
                L.d("MoodleFragment", "URL: ${entry.url}")
                L.d("MoodleFragment", "LinkType: ${entry.linkType}")

                entry.status = "downloading"
                entry.progress = 0
                CourseDownloadQueue.getInstance().updateEntry(entry)

                val freshCookies = CookieManager.getInstance().getCookie(moodleBaseUrl)

                if (freshCookies == null) {
                    L.e("MoodleFragment", "No cookies available for download")
                    entry.status = "failed"
                    entry.errorMessage = getString(R.string.moodle_no_session_cookies)
                    CourseDownloadQueue.getInstance().updateEntry(entry)
                    return@execute
                }

                val iconUrlLower = entry.iconUrl.lowercase()

                when {
                    // pdf files
                    iconUrlLower.contains("/f/pdf") -> {
                        L.d("MoodleFragment", "Detected PDF file")
                        downloadCourseEntryPdf(entry, freshCookies)
                    }
                    // docx files
                    iconUrlLower.contains("/f/document") -> {
                        L.d("MoodleFragment", "Detected DOCX file")
                        downloadCourseEntryDocx(entry, freshCookies)
                    }
                    // image files
                    iconUrlLower.contains("/f/image") -> {
                        L.d("MoodleFragment", "Detected Image file")
                        downloadCourseEntryImage(entry, freshCookies)
                    }
                    // audio files
                    iconUrlLower.contains("/f/audio") -> {
                        L.d("MoodleFragment", "Detected Audio file")
                        downloadCourseEntryAudio(entry, freshCookies)
                    }
                    // text/page files - NEW
                    iconUrlLower.contains("/page/") -> {
                        L.d("MoodleFragment", "Detected Text/Page file")
                        downloadCourseEntryText(entry, freshCookies)
                    }
                    // url links - NEW
                    iconUrlLower.contains("/url/") -> {
                        L.d("MoodleFragment", "Detected URL link")
                        downloadCourseEntryUrl(entry, freshCookies)
                    }
                    // folders
                    iconUrlLower.contains("/folder/") -> {
                        L.d("MoodleFragment", "Detected Folder")
                        downloadCourseEntryFolder(entry, freshCookies)
                    }
                    // any other file
                    else -> {
                        L.d("MoodleFragment", "Detected other file type")
                        downloadCourseEntryGeneric(entry, freshCookies)
                    }
                }

            } catch (e: Exception) {
                L.e("MoodleFragment", "Exception during download", e)
                entry.status = "failed"
                entry.errorMessage = e.message ?: "Unknown error"
                CourseDownloadQueue.getInstance().updateEntry(entry)
            }
        }
    }

    private fun downloadCourseEntryPdf(entry: CourseDownloadQueue.DownloadQueueEntry, cookies: String) {
        if (entry.url.contains("/mod/resource/view.php")) {
            resolveAndDownloadCourseEntry(entry, cookies)
        } else {
            downloadResolvedCourseEntry(entry, entry.url, cookies)
        }
    }

    private fun downloadCourseEntryDocx(entry: CourseDownloadQueue.DownloadQueueEntry, cookies: String) {
        if (entry.url.contains("/mod/resource/view.php")) {
            resolveAndDownloadCourseEntry(entry, cookies)
        } else {
            downloadResolvedCourseEntry(entry, entry.url, cookies)
        }
    }

    private fun downloadCourseEntryImage(entry: CourseDownloadQueue.DownloadQueueEntry, cookies: String) {
        try {
            L.d("MoodleFragment", "Downloading image file")
            L.d("MoodleFragment", "View URL: ${entry.url}")

            L.d("MoodleFragment", "Fetching image page: ${entry.url}")

            val conn = URL(entry.url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Cookie", cookies)
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Referer", moodleBaseUrl)
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val responseCode = conn.responseCode
            L.d("MoodleFragment", "Image page response: $responseCode")

            if (responseCode == 200) {
                val html = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val imagePattern = "<img[^>]+class=\"resourceimage\"[^>]+src=\"([^\"]+)\"".toRegex()
                val match = imagePattern.find(html)

                if (match != null) {
                    val imageUrl = match.groupValues[1]
                    L.d("MoodleFragment", "Found image URL: $imageUrl")
                    downloadResolvedCourseEntry(entry, imageUrl, cookies)
                } else {
                    L.e("MoodleFragment", "Image src not found in HTML")
                    entry.status = "failed"
                    entry.errorMessage = getString(R.string.moodle_couldnt_find_image_file)
                    CourseDownloadQueue.getInstance().updateEntry(entry)
                }
            } else {
                L.e("MoodleFragment", "Failed to load image page: $responseCode")
                conn.disconnect()
                entry.status = "failed"
                entry.errorMessage = "HTTP Error: $responseCode"
                CourseDownloadQueue.getInstance().updateEntry(entry)
            }
        } catch (e: Exception) {
            L.e("MoodleFragment", "Error downloading image", e)
            entry.status = "failed"
            entry.errorMessage = e.message ?: "Unknown error"
            CourseDownloadQueue.getInstance().updateEntry(entry)
        }
    }

    private fun downloadCourseEntryAudio(entry: CourseDownloadQueue.DownloadQueueEntry, cookies: String) {
        try {
            L.d("MoodleFragment", "Downloading audio file")
            L.d("MoodleFragment", "View URL: ${entry.url}")

            L.d("MoodleFragment", "Fetching audio page: ${entry.url}")

            val conn = URL(entry.url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Cookie", cookies)
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Referer", moodleBaseUrl)
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val responseCode = conn.responseCode
            L.d("MoodleFragment", "Audio page response: $responseCode")

            if (responseCode == 200) {
                val html = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val audioSourcePattern = "<source\\s+src=\"([^\"]+)\"[^>]*type=\"audio/[^\"]+\"".toRegex()
                val match = audioSourcePattern.find(html)

                if (match != null) {
                    val audioUrl = match.groupValues[1]
                    L.d("MoodleFragment", "Found audio URL: $audioUrl")
                    downloadResolvedCourseEntry(entry, audioUrl, cookies)
                } else {
                    L.e("MoodleFragment", "Audio source not found in HTML")
                    entry.status = "failed"
                    entry.errorMessage = getString(R.string.moodle_couldnt_find_audio_file)
                    CourseDownloadQueue.getInstance().updateEntry(entry)
                }
            } else {
                L.e("MoodleFragment", "Failed to load audio page: $responseCode")
                conn.disconnect()
                entry.status = "failed"
                entry.errorMessage = "HTTP Error: $responseCode"
                CourseDownloadQueue.getInstance().updateEntry(entry)
            }
        } catch (e: Exception) {
            L.e("MoodleFragment", "Error downloading audio", e)
            entry.status = "failed"
            entry.errorMessage = e.message ?: "Unknown error"
            CourseDownloadQueue.getInstance().updateEntry(entry)
        }
    }

    private fun downloadCourseEntryGeneric(entry: CourseDownloadQueue.DownloadQueueEntry, cookies: String) {
        if (entry.url.contains("/mod/resource/view.php")) {
            resolveAndDownloadCourseEntry(entry, cookies)
        } else {
            downloadResolvedCourseEntry(entry, entry.url, cookies)
        }
    }

    private fun resolveAndDownloadCourseEntry(entry: CourseDownloadQueue.DownloadQueueEntry, cookies: String) {
        try {
            L.d("MoodleFragment", "Resolving download URL for: ${entry.entryName}")

            var currentUrl = entry.url
            var redirectCount = 0
            val maxRedirects = 10
            var finalUrl: String? = null

            while (redirectCount < maxRedirects) {
                L.d("MoodleFragment", "Redirect attempt $redirectCount: $currentUrl")

                if (currentUrl.contains("/login/index.php")) {
                    L.w("MoodleFragment", "Hit login page during resolution")
                    entry.status = "failed"
                    entry.errorMessage = getString(R.string.moodle_session_expired_reload)
                    CourseDownloadQueue.getInstance().updateEntry(entry)
                    return
                }

                val conn = URL(currentUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Cookie", cookies)
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty("Referer", moodleBaseUrl)
                conn.connectTimeout = 15000
                conn.readTimeout = 15000

                val responseCode = conn.responseCode
                L.d("MoodleFragment", "Response code: $responseCode")

                when (responseCode) {
                    in 300..399 -> {
                        val location = conn.getHeaderField("Location")
                        conn.disconnect()

                        if (location.isNullOrBlank()) {
                            L.e("MoodleFragment", "Redirect without location")
                            break
                        }

                        currentUrl = when {
                            location.startsWith("http") -> location
                            location.startsWith("/") -> "$moodleBaseUrl$location"
                            else -> {
                                val baseUrl = currentUrl.substringBeforeLast("/")
                                "$baseUrl/$location"
                            }
                        }
                        redirectCount++
                    }
                    200 -> {
                        finalUrl = currentUrl
                        L.d("MoodleFragment", "Found final download URL")
                        conn.disconnect()
                        break
                    }
                    401, 403 -> {
                        L.e("MoodleFragment", "Authentication failed: $responseCode")
                        conn.disconnect()
                        entry.status = "failed"
                        entry.errorMessage = getString(R.string.moodle_session_expired_reload)
                        CourseDownloadQueue.getInstance().updateEntry(entry)
                        return
                    }
                    else -> {
                        L.e("MoodleFragment", "Unexpected response: $responseCode")
                        conn.disconnect()
                        entry.status = "failed"
                        entry.errorMessage = "HTTP Error: $responseCode"
                        CourseDownloadQueue.getInstance().updateEntry(entry)
                        return
                    }
                }
            }

            if (finalUrl != null) {
                downloadResolvedCourseEntry(entry, finalUrl, cookies)
            } else {
                L.e("MoodleFragment", "Failed to resolve download URL")
                entry.status = "failed"
                entry.errorMessage = getString(R.string.moodle_couldnt_resolve_download_url)
                CourseDownloadQueue.getInstance().updateEntry(entry)
            }
        } catch (e: Exception) {
            L.e("MoodleFragment", "Exception resolving URL", e)
            entry.status = "failed"
            entry.errorMessage = e.message ?: "Unknown error"
            CourseDownloadQueue.getInstance().updateEntry(entry)
        }
    }

    private fun downloadResolvedCourseEntry(entry: CourseDownloadQueue.DownloadQueueEntry, downloadUrl: String, cookies: String) {
        try {
            L.d("MoodleFragment", "Downloading from resolved URL: $downloadUrl")

            val sanitizedCourseName = sanitizeFileName(entry.courseName)
            val sanitizedFileName = sanitizeFileName(entry.fileName)

            val baseDir = downloadsDirectory
            if (!baseDir.exists()) {
                baseDir.mkdirs()
            }

            val courseFolder = File(baseDir, sanitizedCourseName)
            if (!courseFolder.exists()) {
                val created = courseFolder.mkdirs()
                if (!created) {
                    L.e("MoodleFragment", "Failed to create course folder")
                    entry.status = "failed"
                    entry.errorMessage = "Could not create folder"
                    CourseDownloadQueue.getInstance().updateEntry(entry)
                    return
                }
            }

            val conn = URL(downloadUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Cookie", cookies)
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Referer", moodleBaseUrl)
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.setRequestProperty("Connection", "keep-alive")
            conn.setRequestProperty("Cache-Control", "no-cache")
            conn.setRequestProperty("Pragma", "no-cache")
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            conn.readTimeout = 60000

            val responseCode = conn.responseCode
            L.d("MoodleFragment", "Download response: $responseCode")

            if (responseCode == 200) {
                val contentType = conn.getHeaderField("Content-Type") ?: ""
                val contentDisposition = conn.getHeaderField("Content-Disposition") ?: ""

                L.d("MoodleFragment", "Content-Type: $contentType")
                L.d("MoodleFragment", "Content-Disposition: $contentDisposition")

                if (contentType.contains("text/html", ignoreCase = true)) {
                    L.e("MoodleFragment", "Received HTML instead of file - likely permission/auth issue")
                    conn.disconnect()
                    entry.status = "failed"
                    entry.errorMessage = "Server returned HTML instead of file"
                    CourseDownloadQueue.getInstance().updateEntry(entry)
                    return
                }

                val inputStream = conn.inputStream

                var actualFileName = sanitizedFileName
                if (contentDisposition.contains("filename=")) {
                    val match = "filename=\"?([^\"]+)\"?".toRegex().find(contentDisposition)
                    match?.groupValues?.get(1)?.let {
                        actualFileName = sanitizeFileName(it)
                        L.d("MoodleFragment", "Using filename from header: $actualFileName")
                    }
                }

                val file = File(courseFolder, actualFileName)

                val totalBytes = conn.contentLength.toLong()
                var downloadedBytes = 0L

                L.d("MoodleFragment", "Total size: $totalBytes bytes")
                L.d("MoodleFragment", "Saving to: ${file.absolutePath}")

                file.outputStream().use { output ->
                    inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        var bytes = input.read(buffer)

                        while (bytes != -1) {
                            output.write(buffer, 0, bytes)
                            downloadedBytes += bytes

                            if (totalBytes > 0) {
                                val newProgress = (downloadedBytes * 100 / totalBytes).toInt()
                                if (newProgress != entry.progress) {
                                    entry.progress = newProgress
                                    CourseDownloadQueue.getInstance().updateEntry(entry)
                                }
                            }

                            bytes = input.read(buffer)
                        }
                    }
                }

                conn.disconnect()

                if (file.exists() && file.length() > 0) {
                    L.d("MoodleFragment", "File created successfully: ${file.absolutePath}")
                    L.d("MoodleFragment", "File size: ${file.length()} bytes")

                    entry.status = "completed"
                    entry.progress = 100
                    CourseDownloadQueue.getInstance().updateEntry(entry)

                    activity?.runOnUiThread {
                        updateCourseDownloadsCounter()
                    }
                } else {
                    L.e("MoodleFragment", "File was not created or is empty!")
                    entry.status = "failed"
                    entry.errorMessage = "File not created or is empty"
                    CourseDownloadQueue.getInstance().updateEntry(entry)
                }

            } else {
                L.e("MoodleFragment", "Download failed with code: $responseCode")
                conn.disconnect()
                entry.status = "failed"
                entry.errorMessage = "HTTP Error: $responseCode"
                CourseDownloadQueue.getInstance().updateEntry(entry)
            }

        } catch (e: Exception) {
            L.e("MoodleFragment", "Exception during actual download", e)
            entry.status = "failed"
            entry.errorMessage = e.message ?: "Unknown error"
            CourseDownloadQueue.getInstance().updateEntry(entry)
        }
    }

    private fun downloadCourseEntryText(entry: CourseDownloadQueue.DownloadQueueEntry, cookies: String) {
        try {
            L.d("MoodleFragment", "Downloading text page: ${entry.entryName}")
            L.d("MoodleFragment", "Page URL: ${entry.url}")

            val conn = URL(entry.url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Cookie", cookies)
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Referer", moodleBaseUrl)
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val responseCode = conn.responseCode
            L.d("MoodleFragment", "Page response: $responseCode")

            if (responseCode == 200) {
                val html = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                extractTextFromPageHtmlDirect(html) { extractedText ->
                    if (extractedText != null && extractedText.isNotEmpty()) {
                        L.d("MoodleFragment", "Extracted text, saving as file")
                        saveCourseEntryAsTextFile(entry, extractedText)
                    } else {
                        L.e("MoodleFragment", "Failed to extract text from page")
                        entry.status = "failed"
                        entry.errorMessage = "Could not extract text from page"
                        CourseDownloadQueue.getInstance().updateEntry(entry)
                    }
                }
            } else {
                L.e("MoodleFragment", "Failed to load page: $responseCode")
                conn.disconnect()
                entry.status = "failed"
                entry.errorMessage = "HTTP Error: $responseCode"
                CourseDownloadQueue.getInstance().updateEntry(entry)
            }
        } catch (e: Exception) {
            L.e("MoodleFragment", "Error downloading text page", e)
            entry.status = "failed"
            entry.errorMessage = e.message ?: "Unknown error"
            CourseDownloadQueue.getInstance().updateEntry(entry)
        }
    }

    private fun saveCourseEntryAsTextFile(entry: CourseDownloadQueue.DownloadQueueEntry, textContent: String) {
        try {
            L.d("MoodleFragment", "Saving text as file")

            val sanitizedCourseName = sanitizeFileName(entry.courseName)
            val sanitizedEntryName = sanitizeFileName(entry.entryName)

            val baseDir = downloadsDirectory
            if (!baseDir.exists()) {
                baseDir.mkdirs()
            }

            val courseFolder = File(baseDir, sanitizedCourseName)
            if (!courseFolder.exists()) {
                val created = courseFolder.mkdirs()
                if (!created) {
                    L.e("MoodleFragment", "Failed to create course folder")
                    entry.status = "failed"
                    entry.errorMessage = "Could not create folder"
                    CourseDownloadQueue.getInstance().updateEntry(entry)
                    return
                }
            }

            val file = File(courseFolder, "$sanitizedEntryName.txt")

            L.d("MoodleFragment", "Writing text to: ${file.absolutePath}")

            file.writeText(textContent, Charsets.UTF_8)

            if (file.exists() && file.length() > 0) {
                L.d("MoodleFragment", "Text file created successfully")
                L.d("MoodleFragment", "File size: ${file.length()} bytes")

                entry.status = "completed"
                entry.progress = 100
                CourseDownloadQueue.getInstance().updateEntry(entry)

                activity?.runOnUiThread {
                    updateCourseDownloadsCounter()
                }
            } else {
                L.e("MoodleFragment", "Text file was not created!")
                entry.status = "failed"
                entry.errorMessage = "File not created"
                CourseDownloadQueue.getInstance().updateEntry(entry)
            }

        } catch (e: Exception) {
            L.e("MoodleFragment", "Exception saving text file", e)
            entry.status = "failed"
            entry.errorMessage = e.message ?: "Unknown error"
            CourseDownloadQueue.getInstance().updateEntry(entry)
        }
    }

    private fun downloadCourseEntryUrl(entry: CourseDownloadQueue.DownloadQueueEntry, cookies: String) {
        try {
            L.d("MoodleFragment", "Extracting URL from page: ${entry.entryName}")
            L.d("MoodleFragment", "Page URL: ${entry.url}")

            val conn = URL(entry.url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Cookie", cookies)
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Referer", moodleBaseUrl)
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val responseCode = conn.responseCode
            L.d("MoodleFragment", "Page response: $responseCode")

            if (responseCode == 200) {
                val html = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                extractUrlFromPageHtmlDirect(html) { extractedUrl ->
                    if (extractedUrl != null && extractedUrl.isNotEmpty()) {
                        L.d("MoodleFragment", "Extracted URL: $extractedUrl")
                        saveCourseEntryAsUrlLink(entry, extractedUrl)
                    } else {
                        L.e("MoodleFragment", "Failed to extract URL from page")
                        entry.status = "failed"
                        entry.errorMessage = "Could not extract URL from page"
                        CourseDownloadQueue.getInstance().updateEntry(entry)
                    }
                }
            } else {
                L.e("MoodleFragment", "Failed to load page: $responseCode")
                conn.disconnect()
                entry.status = "failed"
                entry.errorMessage = "HTTP Error: $responseCode"
                CourseDownloadQueue.getInstance().updateEntry(entry)
            }
        } catch (e: Exception) {
            L.e("MoodleFragment", "Error extracting URL", e)
            entry.status = "failed"
            entry.errorMessage = e.message ?: "Unknown error"
            CourseDownloadQueue.getInstance().updateEntry(entry)
        }
    }

    private fun extractTextFromPageHtmlDirect(htmlString: String, callback: (String?) -> Unit) {
        try {
            L.d("MoodleFragment", "=== Extracting text from HTML directly ===")

            val doc: org.jsoup.nodes.Document = Jsoup.parse(htmlString)

            val contentDiv = doc.select("div[class=no-overflow]").firstOrNull()

            if (contentDiv == null) {
                L.e("MoodleFragment", "Content div not found")
                callback(null)
                return
            }

            L.d("MoodleFragment", "Found content div")

            val textBuilder = StringBuilder()

            val paragraphs = contentDiv.select("p")
            L.d("MoodleFragment", "Found ${paragraphs.size} paragraphs")

            paragraphs.forEach { p ->
                val text = p.text().trim()
                if (text.isNotEmpty()) {
                    textBuilder.append(text).append("\n")
                }
            }

            val preBlocks = contentDiv.select("pre")
            L.d("MoodleFragment", "Found ${preBlocks.size} pre blocks")

            preBlocks.forEach { pre ->
                val text = pre.text().trim()
                if (text.isNotEmpty()) {
                    if (textBuilder.isNotEmpty()) {
                        textBuilder.append("\n\n")
                    }
                    textBuilder.append(text)
                }
            }

            if (textBuilder.isEmpty()) {
                L.d("MoodleFragment", "No paragraphs or pre blocks found, getting all text")
                val allText = contentDiv.text().trim()
                if (allText.isNotEmpty()) {
                    textBuilder.append(allText)
                }
            }

            val extractedText = textBuilder.toString()
            L.d("MoodleFragment", "Extracted text length: ${extractedText.length}")

            callback(extractedText.ifEmpty { null })

        } catch (e: Exception) {
            L.e("MoodleFragment", "Error parsing HTML for text", e)
            callback(null)
        }
    }

    private fun extractUrlFromPageHtmlDirect(htmlString: String, callback: (String?) -> Unit) {
        try {
            L.d("MoodleFragment", "=== Extracting URL from HTML directly ===")

            val doc: org.jsoup.nodes.Document = Jsoup.parse(htmlString)

            var linkElement = doc.select("div[class=no-overflow] a").firstOrNull()

            if (linkElement == null) {
                L.d("MoodleFragment", "No link in no-overflow div, trying other selectors")
                linkElement = doc.select("div[class*=content] a").firstOrNull()
            }

            if (linkElement == null) {
                L.d("MoodleFragment", "No link found with any selector")
                callback(null)
                return
            }

            L.d("MoodleFragment", "Found link element")

            val url = linkElement.attr("href")
            L.d("MoodleFragment", "Extracted URL: $url")
            L.d("MoodleFragment", "Link text: ${linkElement.text()}")

            callback(url.ifEmpty { null })

        } catch (e: Exception) {
            L.e("MoodleFragment", "Error parsing HTML for URL", e)
            callback(null)
        }
    }

    private fun saveCourseEntryAsUrlLink(entry: CourseDownloadQueue.DownloadQueueEntry, url: String) {
        try {
            L.d("MoodleFragment", "Saving URL as link file")

            val sanitizedCourseName = sanitizeFileName(entry.courseName)

            val baseDir = downloadsDirectory
            if (!baseDir.exists()) {
                baseDir.mkdirs()
            }

            val courseFolder = File(baseDir, sanitizedCourseName)
            if (!courseFolder.exists()) {
                val created = courseFolder.mkdirs()
                if (!created) {
                    L.e("MoodleFragment", "Failed to create course folder")
                    entry.status = "failed"
                    entry.errorMessage = "Could not create folder"
                    CourseDownloadQueue.getInstance().updateEntry(entry)
                    return
                }
            }

            val fileName = sanitizeFileName(entry.entryName) + ".url"
            val file = File(courseFolder, fileName)

            L.d("MoodleFragment", "Writing URL to: ${file.absolutePath}")

            val urlContent = """
            [InternetShortcut]
            URL=$url
        """.trimIndent()

            file.writeText(urlContent, Charsets.UTF_8)

            if (file.exists() && file.length() > 0) {
                L.d("MoodleFragment", "URL file created successfully")
                L.d("MoodleFragment", "File size: ${file.length()} bytes")

                entry.status = "completed"
                entry.progress = 100
                CourseDownloadQueue.getInstance().updateEntry(entry)

                activity?.runOnUiThread {
                    updateCourseDownloadsCounter()
                }
            } else {
                L.e("MoodleFragment", "URL file was not created!")
                entry.status = "failed"
                entry.errorMessage = "File not created"
                CourseDownloadQueue.getInstance().updateEntry(entry)
            }

        } catch (e: Exception) {
            L.e("MoodleFragment", "Exception saving URL file", e)
            entry.status = "failed"
            entry.errorMessage = e.message ?: "Unknown error"
            CourseDownloadQueue.getInstance().updateEntry(entry)
        }
    }

    private fun downloadCourseEntryFolder(entry: CourseDownloadQueue.DownloadQueueEntry, cookies: String) {
        try {
            L.d("MoodleFragment", "=== Downloading Folder Entry ===")
            L.d("MoodleFragment", "Folder: ${entry.entryName}")
            L.d("MoodleFragment", "URL: ${entry.url}")

            entry.status = "downloading"
            entry.progress = 0
            CourseDownloadQueue.getInstance().updateEntry(entry)

            L.d("MoodleFragment", "Fetching folder page: ${entry.url}")

            val conn = URL(entry.url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Cookie", cookies)
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Referer", moodleBaseUrl)
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val responseCode = conn.responseCode
            L.d("MoodleFragment", "Folder page response: $responseCode")

            if (responseCode == 200) {
                val html = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                parseFolderFilesFromHtml(html) { folderFiles ->
                    if (folderFiles != null && folderFiles.isNotEmpty()) {
                        L.d("MoodleFragment", "Found ${folderFiles.size} files in folder")

                        entry.folderFiles.clear()
                        entry.folderFiles.addAll(folderFiles)
                        CourseDownloadQueue.getInstance().updateEntry(entry)

                        downloadFolderFiles(entry, cookies)
                    } else {
                        L.e("MoodleFragment", "No files found in folder")
                        entry.status = "failed"
                        entry.errorMessage = getString(R.string.moodle_no_files_in_folder)
                        CourseDownloadQueue.getInstance().updateEntry(entry)
                    }
                }
            } else {
                L.e("MoodleFragment", "Failed to load folder page: $responseCode")
                conn.disconnect()
                entry.status = "failed"
                entry.errorMessage = "HTTP Error: $responseCode"
                CourseDownloadQueue.getInstance().updateEntry(entry)
            }
        } catch (e: Exception) {
            L.e("MoodleFragment", "Error downloading folder", e)
            entry.status = "failed"
            entry.errorMessage = e.message ?: "Unknown error"
            CourseDownloadQueue.getInstance().updateEntry(entry)
        }
    }

    private fun parseFolderFilesFromHtml(htmlString: String, callback: (List<CourseDownloadQueue.FolderFile>?) -> Unit) {
        try {
            L.d("MoodleFragment", "=== Parsing Folder Files from HTML ===")

            val doc: org.jsoup.nodes.Document = Jsoup.parse(htmlString)

            val fileSpans = doc.select("span.fp-filename")
            L.d("MoodleFragment", "Found ${fileSpans.size} file spans")

            if (fileSpans.isEmpty()) {
                L.e("MoodleFragment", "No file spans found with class 'fp-filename'")
                callback(null)
                return
            }

            val folderFiles = mutableListOf<CourseDownloadQueue.FolderFile>()

            fileSpans.forEach { span ->
                val link = span.selectFirst("a")
                if (link != null) {
                    val fileUrl = link.attr("href")
                    val fileName = link.text().trim()

                    if (fileUrl.isNotEmpty() && fileName.isNotEmpty()) {
                        L.d("MoodleFragment", "Found file: $fileName -> $fileUrl")

                        val iconUrl = determineIconUrlFromFileName(fileName)

                        folderFiles.add(CourseDownloadQueue.FolderFile(
                            name = fileName,
                            url = fileUrl,
                            iconUrl = iconUrl
                        ))
                    }
                }
            }

            L.d("MoodleFragment", "Successfully parsed ${folderFiles.size} files")
            callback(folderFiles)

        } catch (e: Exception) {
            L.e("MoodleFragment", "Error parsing folder files from HTML", e)
            callback(null)
        }
    }

    private fun determineIconUrlFromFileName(fileName: String): String {
        val extension = fileName.substringAfterLast(".", "").lowercase()
        return when (extension) {
            "pdf" -> "https://moodle.kleyer.eu/theme/image.php/boost/core/1/f/pdf"
            "jpg", "jpeg", "png", "gif" -> "https://moodle.kleyer.eu/theme/image.php/boost/core/1/f/image"
            "doc", "docx" -> "https://moodle.kleyer.eu/theme/image.php/boost/core/1/f/document"
            "mp3", "wav" -> "https://moodle.kleyer.eu/theme/image.php/boost/core/1/f/audio"
            "txt" -> "https://moodle.kleyer.eu/theme/image.php/boost/core/1/f/text"
            else -> "https://moodle.kleyer.eu/theme/image.php/boost/core/1/f/unknown"
        }
    }

    private fun downloadFolderFiles(entry: CourseDownloadQueue.DownloadQueueEntry, cookies: String) {
        try {
            L.d("MoodleFragment", "=== Downloading Folder Files ===")
            L.d("MoodleFragment", "Total files: ${entry.folderFiles.size}")

            val sanitizedCourseName = sanitizeFileName(entry.courseName)
            val sanitizedFolderName = sanitizeFileName(entry.entryName)

            val baseDir = downloadsDirectory
            if (!baseDir.exists()) {
                baseDir.mkdirs()
            }

            val courseFolder = File(baseDir, sanitizedCourseName)
            if (!courseFolder.exists()) {
                courseFolder.mkdirs()
            }

            val folderDir = File(courseFolder, sanitizedFolderName)
            if (!folderDir.exists()) {
                val created = folderDir.mkdirs()
                if (!created) {
                    L.e("MoodleFragment", "Failed to create folder directory")
                    entry.status = "failed"
                    entry.errorMessage = "Could not create folder directory"
                    CourseDownloadQueue.getInstance().updateEntry(entry)
                    return
                }
            }

            L.d("MoodleFragment", "Folder directory: ${folderDir.absolutePath}")

            var downloadedCount = 0
            var failedCount = 0
            val totalFiles = entry.folderFiles.size

            entry.folderFiles.forEachIndexed { index, file ->
                try {
                    L.d("MoodleFragment", "Downloading file ${index + 1}/$totalFiles: ${file.name}")

                    val conn = URL(file.url).openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Cookie", cookies)
                    conn.setRequestProperty("User-Agent", userAgent)
                    conn.setRequestProperty("Referer", moodleBaseUrl)
                    conn.setRequestProperty("Accept", "*/*")
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 15000
                    conn.readTimeout = 60000

                    val responseCode = conn.responseCode
                    L.d("MoodleFragment", "File download response: $responseCode")

                    if (responseCode == 200) {
                        val sanitizedFileName = sanitizeFileName(file.name)
                        val outputFile = File(folderDir, sanitizedFileName)

                        val inputStream = conn.inputStream
                        outputFile.outputStream().use { output ->
                            inputStream.use { input ->
                                val buffer = ByteArray(8192)
                                var bytes = input.read(buffer)
                                while (bytes != -1) {
                                    output.write(buffer, 0, bytes)
                                    bytes = input.read(buffer)
                                }
                            }
                        }

                        conn.disconnect()

                        if (outputFile.exists() && outputFile.length() > 0) {
                            L.d("MoodleFragment", "File downloaded: ${file.name} (${outputFile.length()} bytes)")
                            file.downloaded = true
                            downloadedCount++
                        } else {
                            L.e("MoodleFragment", "File not created or empty: ${file.name}")
                            failedCount++
                        }
                    } else {
                        L.e("MoodleFragment", "Failed to download file: $responseCode")
                        conn.disconnect()
                        failedCount++
                    }

                    val progress = ((index + 1) * 100 / totalFiles)
                    entry.progress = progress
                    CourseDownloadQueue.getInstance().updateEntry(entry)

                } catch (e: Exception) {
                    L.e("MoodleFragment", "Exception downloading file: ${file.name}", e)
                    failedCount++
                }
            }

            L.d("MoodleFragment", "Folder download complete: $downloadedCount/$totalFiles successful, $failedCount failed")

            if (downloadedCount > 0) {
                entry.status = "completed"
                entry.progress = 100
            } else {
                entry.status = "failed"
                entry.errorMessage = "All files failed to download"
            }
            CourseDownloadQueue.getInstance().updateEntry(entry)

            activity?.runOnUiThread {
                updateCourseDownloadsCounter()
            }

        } catch (e: Exception) {
            L.e("MoodleFragment", "Exception downloading folder files", e)
            entry.status = "failed"
            entry.errorMessage = e.message ?: "Unknown error"
            CourseDownloadQueue.getInstance().updateEntry(entry)
        }
    }

    private fun getImageExtensionFromUrl(url: String): String {
        return when {
            url.contains(".jpg", ignoreCase = true) -> ".jpg"
            url.contains(".png", ignoreCase = true) -> ".png"
            url.contains(".gif", ignoreCase = true) -> ".gif"
            url.contains(".webp", ignoreCase = true) -> ".webp"
            else -> ".jpg"
        }
    }

    private fun updateCourseDownloadsCounter() {
        val tvCourseDownloads = view?.findViewById<TextView>(R.id.tvCourseDownloads)
        val downloadQueue = CourseDownloadQueue.getInstance()
        val pendingSize = downloadQueue.getPendingSize()

        activity?.runOnUiThread {
            tvCourseDownloads?.apply {
                if (pendingSize > 0) {
                    text = pendingSize.toString()
                    visibility = View.VISIBLE
                } else {
                    visibility = View.GONE
                }
            }
        }
    }

    private fun getDisplayPath(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Android/data/${requireContext().packageName}/files/HKS_Moodle_Downloads"
        } else {
            "Documents/HKS_Moodle_Downloads"
        }
    }

    //endregion
}