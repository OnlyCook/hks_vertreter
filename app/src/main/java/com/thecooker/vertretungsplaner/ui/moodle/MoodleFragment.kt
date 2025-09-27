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

    private var searchBarVisible = false
    private var isAtTop = true
    private var scrollThreshold = 50 // pixels to scroll before hiding header
    private var isUserScrolling = false
    private var lastScrollCheckTime = 0L
    private val scrollDebounceDelay = 100L

    // file picker
    private lateinit var btnOpenInBrowser: ImageButton
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        fileUploadCallback?.onReceiveValue(uris.toTypedArray())
        fileUploadCallback = null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_moodle, container, false)

        initViews(root)
        setupSharedPreferences()
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

                if (url == loginUrl || url?.contains("login/index.php") == true) {
                    waitForPageReady { pageReady ->
                        if (pageReady) {
                            isConfirmDialogPage { isConfirmDialog ->
                                if (isConfirmDialog) {
                                    checkConfirmDialog()
                                } else {
                                    checkLoginFailure { loginFailed ->
                                        hasLoginFailed = loginFailed

                                        if (!isLoginDialogShown) {
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
        val jsCode = """
        (function() {
            var errorElements = document.querySelectorAll('.alert-danger, .error, .loginerror');
            for (var i = 0; i < errorElements.length; i++) {
                if (errorElements[i].textContent.toLowerCase().includes('invalid') || 
                    errorElements[i].textContent.toLowerCase().includes('incorrect') ||
                    errorElements[i].textContent.toLowerCase().includes('wrong') ||
                    errorElements[i].textContent.toLowerCase().includes('fehler') ||
                    errorElements[i].textContent.toLowerCase().includes('falsch')) {
                    return true;
                }
            }
            return false;
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            callback(result == "true")
        }
    }

    private fun handleLogout() {
        isLoginDialogShown = false
        hasLoginFailed = false

        encryptedPrefs.edit { clear() }

        sharedPrefs.edit {
            remove("moodle_dont_show_login_dialog")
        }

        Toast.makeText(requireContext(), getString(R.string.moodle_logged_out), Toast.LENGTH_SHORT).show()
    }

    private fun checkExtendedHeaderVisibility() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastScrollCheckTime < scrollDebounceDelay) return
        lastScrollCheckTime = currentTime

        val scrollY = webView.scrollY
        val newIsAtTop = scrollY < scrollThreshold

        if (newIsAtTop != isAtTop) {
            isAtTop = newIsAtTop
            updateExtendedHeaderVisibility()
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
            navigateToUrl(url)
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
            searchLayout.visibility = if (searchBarVisible) View.VISIBLE else View.GONE
            val layoutParams = searchLayout.layoutParams
            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            searchLayout.layoutParams = layoutParams

            if (searchBarVisible) {
                searchBarMoodle.requestFocus()
            } else {
                searchBarMoodle.clearFocus()
                searchBarMoodle.setText("")
            }
        }

        Handler(Looper.getMainLooper()).postDelayed({
            updateExtendedHeaderVisibility()
        }, if (animationsEnabled) 50 else 0)
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
        val url = if (userInput.startsWith("https://moodle.kleyer.eu/")) {
            userInput
        } else if (userInput.startsWith(moodleBaseUrl)) {
            userInput
        } else {
            "$moodleBaseUrl/$userInput"
        }

        if (url.startsWith(moodleBaseUrl)) {
            loadUrlInBackground(url)
        } else {
            Toast.makeText(requireContext(), getString(R.string.moodle_invalid_url), Toast.LENGTH_SHORT).show()
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
            var userMenu = document.querySelector('.usermenu, .user-menu, [data-userid]');
            var loginForm = document.querySelector('form[action*="login"]');

            if ((logoutLink || userMenu) && !loginForm) {
                return true;
            }

            if (window.location.pathname.includes('/my/') && !loginForm) {
                return true;
            }
            
            return false;
        })();
    """.trimIndent()

        webView.evaluateJavascript(jsCode) { result ->
            callback(result == "true")
        }
    }

    private fun checkAutoLogin() {
        isConfirmDialogPage { isConfirmDialog ->
            if (isConfirmDialog) {
                return@isConfirmDialogPage
            }

            isUserLoggedIn { isLoggedIn ->
                if (isLoggedIn) {
                    isLoginDialogShown = true
                    return@isUserLoggedIn
                }

                val dontShowDialog = sharedPrefs.getBoolean("moodle_dont_show_login_dialog", false)
                val hasCredentials = encryptedPrefs.contains("moodle_username") &&
                        encryptedPrefs.contains("moodle_password")

                if (hasCredentials && !dontShowDialog) {
                    performAutoLogin()
                } else if (!hasCredentials && !dontShowDialog && !isLoginDialogShown) {
                    showLoginDialog()
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

    private fun showLoginDialog() {
        if (isLoginDialogShown) return

        val dontShowDialog = sharedPrefs.getBoolean("moodle_dont_show_login_dialog", false)
        if (dontShowDialog) {
            isLoginDialogShown = true
            return
        }

        isLoginDialogShown = true

        val dialogView = layoutInflater.inflate(R.layout.dialog_moodle_login, null)
        val etUsername = dialogView.findViewById<EditText>(R.id.etUsername)
        val etPassword = dialogView.findViewById<EditText>(R.id.etPassword)
        val cbSaveCredentials = dialogView.findViewById<CheckBox>(R.id.cbSaveCredentials)
        val cbDontShowAgain = dialogView.findViewById<CheckBox>(R.id.cbDontShowAgain)
        val tvErrorMessage = dialogView.findViewById<TextView>(R.id.tvErrorMessage)

        if (hasLoginFailed) {
            tvErrorMessage.visibility = View.VISIBLE
            tvErrorMessage.text = getString(R.string.moodle_login_failed)
        } else {
            tvErrorMessage.visibility = View.GONE
        }

        val savedUsername = encryptedPrefs.getString("moodle_username", "") ?: ""
        if (savedUsername.isNotEmpty()) {
            etUsername.setText(savedUsername)
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
            }
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

    fun canGoBack(): Boolean = webView.canGoBack()

    fun goBack() {
        if (webView.canGoBack()) {
            webView.goBack()
        }
    }
}