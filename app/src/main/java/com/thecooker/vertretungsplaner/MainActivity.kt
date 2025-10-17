package com.thecooker.vertretungsplaner

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import com.google.android.material.navigation.NavigationView
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import androidx.drawerlayout.widget.DrawerLayout
import com.thecooker.vertretungsplaner.databinding.ActivityMainBinding
import androidx.appcompat.app.AppCompatDelegate
import android.os.Build
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.edit
import androidx.navigation.ui.NavigationUI
import androidx.activity.OnBackPressedCallback
import com.thecooker.vertretungsplaner.ui.moodle.MoodleFragment
import androidx.core.content.ContextCompat

open class MainActivity : BaseActivity() {
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private var isProcessingSharedContent = false
    private var hasProcessedSharedContent = false
    private var homeworkArgumentsCleared = false

    private var moodleBackPressedCallback: OnBackPressedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val followSystemTheme = sharedPreferences.getBoolean("follow_system_theme", true)
        val darkModeEnabled = sharedPreferences.getBoolean("dark_mode_enabled", false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AppCompatDelegate.setDefaultNightMode(
                when {
                    followSystemTheme -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    darkModeEnabled -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_NO
                }
            )
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyNavigationBarInsets()

        setStatusBarStyle()

        setSupportActionBar(binding.appBarMain.toolbar)

        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView: NavigationView = binding.navView

        binding.root.post {
            try {
                val navController = findNavController(R.id.nav_host_fragment_content_main)

                appBarConfiguration = AppBarConfiguration(
                    setOf(
                        R.id.nav_home, R.id.nav_gallery, R.id.nav_slideshow,
                        R.id.nav_klausuren, R.id.nav_noten, R.id.nav_moodle
                    ), drawerLayout
                )

                setupActionBarWithNavController(navController, appBarConfiguration)
                navView.setupWithNavController(navController)

                Handler(Looper.getMainLooper()).postDelayed({
                    setupNavigationListener(navView, navController)
                    setupDestinationChangeListener(navController)
                }, 50)

                if (!handleIncomingIntent(intent)) {
                    navigateToStartupPage()
                }
            } catch (e: Exception) {
                L.e("MainActivity", "Error setting up navigation", e)
                handleIncomingIntent(intent)
            }
        }

        if (intent.getBooleanExtra("navigate_to_moodle", false)) {
            val fetchType = intent.getStringExtra("moodle_fetch_type")
            val fetchInProgress = intent.getBooleanExtra("moodle_fetch_in_progress", false)
            val fetchClass = intent.getStringExtra("moodle_fetch_class")  // Get the class

            if (fetchInProgress && fetchType != null) {
                intent.removeExtra("navigate_to_moodle")
                intent.removeExtra("moodle_fetch_type")
                intent.removeExtra("moodle_fetch_in_progress")
                intent.removeExtra("moodle_fetch_class")

                binding.root.postDelayed({
                    try {
                        val navController = findNavController(R.id.nav_host_fragment_content_main)
                        val bundle = Bundle().apply {
                            putString("moodle_fetch_type", fetchType)
                            putBoolean("moodle_fetch_in_progress", true)
                            if (fetchClass != null) {
                                putString("moodle_fetch_class", fetchClass)  // Pass to fragment
                            }
                        }
                        navController.navigate(R.id.nav_moodle, bundle)
                    } catch (e: Exception) {
                        L.e("MainActivity", "Error navigating to Moodle", e)
                    }
                }, 100)
            }
        }

        setupBackPressHandler()
    }

    private fun setupNavigationListener(navView: NavigationView, navController: androidx.navigation.NavController) {
        navView.setNavigationItemSelectedListener { menuItem ->
            val args = navController.currentBackStackEntry?.arguments

            val needsCleanNavigation = args != null && (
                    args.containsKey("highlight_homework_id") ||
                            args.containsKey("highlight_exam_id") ||
                            args.containsKey("highlight_home_item_id") ||
                            args.containsKey("moodle_search_category")
                    )

            if (needsCleanNavigation) {
                L.d("MainActivity", "Detected persistent arguments - forcing clean navigation")
                when (menuItem.itemId) {
                    R.id.nav_gallery -> {
                        forceCleanNavigation(R.id.nav_gallery)
                        binding.drawerLayout.closeDrawers()
                        return@setNavigationItemSelectedListener true
                    }
                    R.id.nav_home -> {
                        forceCleanNavigation(R.id.nav_home)
                        binding.drawerLayout.closeDrawers()
                        return@setNavigationItemSelectedListener true
                    }
                    R.id.nav_slideshow -> {
                        forceCleanNavigation(R.id.nav_slideshow)
                        binding.drawerLayout.closeDrawers()
                        return@setNavigationItemSelectedListener true
                    }
                    R.id.nav_klausuren -> {
                        forceCleanNavigation(R.id.nav_klausuren)
                        binding.drawerLayout.closeDrawers()
                        return@setNavigationItemSelectedListener true
                    }
                    R.id.nav_noten -> {
                        forceCleanNavigation(R.id.nav_noten)
                        binding.drawerLayout.closeDrawers()
                        return@setNavigationItemSelectedListener true
                    }
                    R.id.nav_moodle -> {
                        forceCleanNavigation(R.id.nav_moodle)
                        binding.drawerLayout.closeDrawers()
                        return@setNavigationItemSelectedListener true
                    }
                }
            }

            if (menuItem.itemId == R.id.nav_home && navController.currentDestination?.id != R.id.nav_home) {
                val bundle = Bundle().apply {
                    putBoolean("from_navigation_drawer", true)
                }
                navController.navigate(R.id.nav_home, bundle)
                binding.drawerLayout.closeDrawers()
                return@setNavigationItemSelectedListener true
            }

            val result = NavigationUI.onNavDestinationSelected(menuItem, navController)
            binding.drawerLayout.closeDrawers()
            result
        }
    }

    private fun updateNavigationIcons(destinationId: Int) {
        val navView: NavigationView = binding.navView
        val menu = navView.menu

        val iconMap = mapOf(
            R.id.nav_gallery to Pair(R.drawable.ic_menu_gallery, R.drawable.ic_menu_gallery_filled),
            R.id.nav_home to Pair(R.drawable.ic_menu_camera, R.drawable.ic_menu_camera_filled),
            R.id.nav_slideshow to Pair(R.drawable.ic_menu_slideshow, R.drawable.ic_menu_slideshow_filled),
            R.id.nav_klausuren to Pair(R.drawable.ic_menu_klausuren, R.drawable.ic_menu_klausuren_filled),
            R.id.nav_noten to Pair(R.drawable.ic_star, R.drawable.ic_star_filled),
            R.id.nav_moodle to Pair(R.drawable.ic_moodle, R.drawable.ic_moodle) // moodle doesnt have a filled version
        )

        iconMap.forEach { (itemId, icons) ->
            val menuItem = menu.findItem(itemId)
            menuItem?.icon = ContextCompat.getDrawable(
                this,
                if (itemId == destinationId) icons.second else icons.first
            )
        }
    }

    private fun setupDestinationChangeListener(navController: androidx.navigation.NavController) {
        navController.addOnDestinationChangedListener { _, destination, arguments ->
            L.d("MainActivity", "Navigation changed to: ${destination.label}")
            L.d("MainActivity", "Navigation arguments: $arguments")

            updateNavigationIcons(destination.id)

            moodleBackPressedCallback?.isEnabled = (destination.id == R.id.nav_moodle)

            // clear any highlight args when on wrong destination
            if (arguments != null) {
                val shouldClearArgs = when (destination.id) {
                    R.id.nav_slideshow -> arguments.containsKey("highlight_exam_id") ||
                            arguments.containsKey("highlight_home_item_id") ||
                            arguments.containsKey("highlight_substitute_subject") ||
                            arguments.containsKey("moodle_search_category") ||
                            arguments.containsKey("moodle_fetch_in_progress") ||
                            arguments.containsKey("moodle_fetched_pdf")
                    R.id.nav_klausuren -> arguments.containsKey("highlight_homework_id") ||
                            arguments.containsKey("highlight_home_item_id") ||
                            arguments.containsKey("highlight_substitute_subject") ||
                            arguments.containsKey("moodle_search_category") ||
                            arguments.containsKey("moodle_fetch_in_progress")
                    R.id.nav_home -> arguments.containsKey("highlight_homework_id") ||
                            arguments.containsKey("highlight_exam_id") ||
                            arguments.containsKey("moodle_search_category") ||
                            arguments.containsKey("moodle_fetch_in_progress") ||
                            arguments.containsKey("moodle_fetched_pdf")
                    R.id.nav_gallery -> arguments.containsKey("highlight_homework_id") ||
                            arguments.containsKey("highlight_exam_id") ||
                            arguments.containsKey("highlight_home_item_id") ||
                            arguments.containsKey("highlight_substitute_subject") ||
                            arguments.containsKey("moodle_fetch_in_progress") ||
                            arguments.containsKey("moodle_fetched_pdf")
                    R.id.nav_noten -> arguments.containsKey("highlight_homework_id") ||
                            arguments.containsKey("highlight_exam_id") ||
                            arguments.containsKey("highlight_home_item_id") ||
                            arguments.containsKey("highlight_substitute_subject") ||
                            arguments.containsKey("moodle_search_category") ||
                            arguments.containsKey("moodle_fetch_in_progress") ||
                            arguments.containsKey("moodle_fetched_pdf")
                    R.id.nav_moodle -> arguments.containsKey("highlight_homework_id") ||
                            arguments.containsKey("highlight_exam_id") ||
                            arguments.containsKey("highlight_home_item_id") ||
                            arguments.containsKey("highlight_substitute_subject") ||
                            arguments.containsKey("moodle_fetched_pdf")
                    else -> arguments.containsKey("highlight_homework_id") ||
                            arguments.containsKey("highlight_exam_id") ||
                            arguments.containsKey("highlight_home_item_id") ||
                            arguments.containsKey("highlight_substitute_subject") ||
                            arguments.containsKey("moodle_search_category") ||
                            arguments.containsKey("moodle_fetch_in_progress") ||
                            arguments.containsKey("moodle_fetched_pdf")
                }

                if (shouldClearArgs) {
                    L.d("MainActivity", "Clearing mismatched arguments")
                    arguments.clear()
                }
            }

            // schedule cleanup for correctly placed arguments
            if (arguments != null && !homeworkArgumentsCleared) {
                val hasValidArgs = when (destination.id) {
                    R.id.nav_slideshow -> arguments.containsKey("highlight_homework_id")
                    R.id.nav_klausuren -> arguments.containsKey("highlight_exam_id") ||
                            arguments.containsKey("moodle_fetched_pdf")
                    R.id.nav_home -> arguments.containsKey("highlight_home_item_id")
                    R.id.nav_moodle -> arguments.containsKey("moodle_search_category") ||
                            arguments.containsKey("moodle_fetch_in_progress")
                    else -> false
                }

                if (hasValidArgs) {
                    L.d("MainActivity", "Scheduling argument cleanup for ${destination.label}")
                    homeworkArgumentsCleared = true

                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            arguments.remove("highlight_homework_id")
                            arguments.remove("highlight_exam_id")
                            arguments.remove("highlight_home_item_id")
                            arguments.remove("highlight_homework_subject")
                            arguments.remove("highlight_homework_date")
                            arguments.remove("highlight_exam_subject")
                            arguments.remove("highlight_exam_date")
                            arguments.remove("highlight_substitute_subject")
                            arguments.remove("highlight_substitute_lesson")
                            arguments.remove("highlight_substitute_lesson_end")
                            arguments.remove("highlight_substitute_date")
                            arguments.remove("highlight_substitute_type")
                            arguments.remove("highlight_substitute_room")
                            arguments.remove("moodle_search_category")
                            arguments.remove("moodle_search_summary")
                            arguments.remove("moodle_entry_id")
                            arguments.remove("moodle_fetch_in_progress")
                            arguments.remove("moodle_fetch_type")
                            arguments.remove("moodle_fetch_preserve_notes")
                            arguments.remove("moodle_fetch_program_name")
                            arguments.remove("moodle_fetched_pdf")
                            L.d("MainActivity", "Arguments cleared successfully")
                        } catch (e: Exception) {
                            L.w("MainActivity", "Error clearing arguments", e)
                        }
                    }, 2000)
                }
            }

            if (destination.id != R.id.nav_slideshow && destination.id != R.id.nav_klausuren &&
                destination.id != R.id.nav_home && destination.id != R.id.nav_moodle) {
                homeworkArgumentsCleared = false
            }
        }
    }

    override fun onResume() {
        super.onResume()

        L.d("MainActivity", "onResume called")
        L.d("MainActivity", "Intent extras: ${intent.extras}")
        L.d("MainActivity", "Intent data: ${intent.data}")

        // clear intent extras
        if (intent.hasExtra("highlight_homework_id")) {
            L.d("MainActivity", "Clearing lingering homework intent data")
            intent.removeExtra("highlight_homework_id")
            intent.removeExtra("highlight_homework_subject")
            intent.removeExtra("highlight_homework_date")
        }
        if (intent.hasExtra("highlight_exam_id")) {
            L.d("MainActivity", "Clearing lingering exam intent data")
            intent.removeExtra("highlight_exam_id")
            intent.removeExtra("highlight_exam_subject")
            intent.removeExtra("highlight_exam_date")
        }
        if (intent.hasExtra("highlight_substitute_subject")) {
            L.d("MainActivity", "Clearing lingering substitute intent data")
            intent.removeExtra("highlight_substitute_subject")
            intent.removeExtra("highlight_substitute_lesson")
            intent.removeExtra("highlight_substitute_lesson_end")
            intent.removeExtra("highlight_substitute_date")
            intent.removeExtra("highlight_substitute_type")
            intent.removeExtra("highlight_substitute_room")
        }
        if (intent.hasExtra("moodle_search_category")) {
            L.d("MainActivity", "Clearing lingering moodle intent data")
            intent.removeExtra("moodle_search_category")
            intent.removeExtra("moodle_search_summary")
            intent.removeExtra("moodle_entry_id")
        }

        val landscapeEnabled = sharedPreferences.getBoolean("landscape_mode_enabled", true)
        val currentOrientation = if (landscapeEnabled) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        if (requestedOrientation != currentOrientation) {
            requestedOrientation = currentOrientation
        }

        val navController = findNavController(R.id.nav_host_fragment_content_main)
        val currentDestination = navController.currentDestination

        if (currentDestination?.id == R.id.nav_slideshow) {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    navController.currentBackStackEntry?.arguments?.let { args ->
                        if (args.containsKey("highlight_homework_id")) {
                            args.remove("highlight_homework_id")
                            args.remove("highlight_homework_subject")
                            args.remove("highlight_homework_date")
                            L.d("MainActivity", "Force cleared homework arguments in onResume")
                        }
                    }
                } catch (e: Exception) {
                    L.w("MainActivity", "Error force clearing arguments in onResume", e)
                }
            }, 4000)
        }

        if (currentDestination?.id == R.id.nav_klausuren) {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    navController.currentBackStackEntry?.arguments?.let { args ->
                        if (args.containsKey("highlight_exam_id")) {
                            args.remove("highlight_exam_id")
                            args.remove("highlight_exam_subject")
                            args.remove("highlight_exam_date")
                            L.d("MainActivity", "Force cleared exam arguments in onResume")
                        }
                    }
                } catch (e: Exception) {
                    L.w("MainActivity", "Error force clearing exam arguments in onResume", e)
                }
            }, 4000)
        }

        if (currentDestination?.id == R.id.nav_moodle) {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    navController.currentBackStackEntry?.arguments?.let { args ->
                        if (args.containsKey("moodle_search_category")) {
                            args.remove("moodle_search_category")
                            args.remove("moodle_search_summary")
                            args.remove("moodle_entry_id")
                            L.d("MainActivity", "Force cleared moodle arguments in onResume")
                        }
                    }
                } catch (e: Exception) {
                    L.w("MainActivity", "Error force clearing moodle arguments in onResume", e)
                }
            }, 4000)
        }

        if (intent.hasExtra("moodle_fetch_in_progress")) {
            L.d("MainActivity", "Clearing fetch intent data")
            intent.removeExtra("moodle_fetch_in_progress")
            intent.removeExtra("moodle_fetch_type")
            intent.removeExtra("moodle_fetch_class")
            intent.removeExtra("moodle_fetch_preserve_notes")
            intent.removeExtra("moodle_fetch_program_name")
        }

        val navView: NavigationView = binding.navView
        val checkedItem = navView.checkedItem
        L.d("MainActivity", "Navigation drawer checked item: ${checkedItem?.title}")

        if (sharedPreferences.getBoolean("has_pending_shared_content", false) &&
            !sharedPreferences.getBoolean("shared_content_processed", false)) {

            sharedPreferences.edit {
                putBoolean("shared_content_processed", true)
            }

            binding.root.postDelayed({
                processStoredSharedHomework()
                processStoredSharedExam()
            }, 500)
        }

        binding.root.postDelayed({
            hasProcessedSharedContent = false
            sharedPreferences.edit {
                putBoolean("shared_content_processed", false)
                putBoolean("has_pending_shared_content", false)
            }
        }, 10000)
    }

    private fun navigateToStartupPage() {
        if (isProcessingSharedContent) {
            L.d("MainActivity", "Skipping startup navigation - processing shared content")
            return
        }

        val startupPageIndex = sharedPreferences.getInt("startup_page_index", 1) // default = home page

        binding.root.postDelayed({
            try {
                val navController = findNavController(R.id.nav_host_fragment_content_main)

                when (startupPageIndex) {
                    0 -> {
                        // Kalender (Gallery)
                        navController.navigate(R.id.nav_gallery, null, androidx.navigation.NavOptions.Builder()
                            .setPopUpTo(R.id.nav_home, true)
                            .build())
                    }
                    1 -> {
                        // Vertretungsplan (Home)
                        navController.navigate(R.id.nav_home, null, androidx.navigation.NavOptions.Builder()
                            .build())
                    }
                    2 -> {
                        // Hausaufgaben (Slideshow)
                        navController.navigate(R.id.nav_slideshow, null, androidx.navigation.NavOptions.Builder()
                            .setPopUpTo(R.id.nav_home, true)
                            .build())
                    }
                    3 -> {
                        // Klausuren
                        navController.navigate(R.id.nav_klausuren, null, androidx.navigation.NavOptions.Builder()
                            .setPopUpTo(R.id.nav_home, true)
                            .build())
                    }
                    4 -> {
                        // Noten
                        navController.navigate(R.id.nav_noten, null, androidx.navigation.NavOptions.Builder()
                            .setPopUpTo(R.id.nav_home, true)
                            .build())
                    }
                    5 -> {
                        // Moodle
                        navController.navigate(R.id.nav_moodle, null, androidx.navigation.NavOptions.Builder()
                            .setPopUpTo(R.id.nav_home, true)
                            .build())
                    }
                    else -> {
                        // default to Kalender
                        navController.navigate(R.id.nav_gallery, null, androidx.navigation.NavOptions.Builder()
                            .setPopUpTo(R.id.nav_home, true)
                            .build())
                    }
                }
            } catch (e: Exception) {
                L.e("MainActivity", "Navigation failed in navigateToStartupPage", e)
            }
        }, 100)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        menu.findItem(R.id.action_settings)?.setIcon(R.drawable.ic_gear)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                navigateToSettings()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    private fun navigateToSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        if (intent.getBooleanExtra("navigate_to_moodle", false)) {
            val fetchType = intent.getStringExtra("moodle_fetch_type")
            val fetchInProgress = intent.getBooleanExtra("moodle_fetch_in_progress", false)
            val fetchClass = intent.getStringExtra("moodle_fetch_class")

            if (fetchInProgress && fetchType != null) {
                intent.removeExtra("navigate_to_moodle")
                intent.removeExtra("moodle_fetch_type")
                intent.removeExtra("moodle_fetch_in_progress")
                intent.removeExtra("moodle_fetch_class")

                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        val navController = findNavController(R.id.nav_host_fragment_content_main)
                        val bundle = Bundle().apply {
                            putString("moodle_fetch_type", fetchType)
                            putBoolean("moodle_fetch_in_progress", true)
                            if (fetchClass != null) {
                                putString("moodle_fetch_class", fetchClass)
                            }
                        }
                        navController.navigate(R.id.nav_moodle, bundle)
                    } catch (e: Exception) {
                        L.e("MainActivity", "Error navigating to Moodle from onNewIntent", e)
                    }
                }, 100)
                return
            }
        }

        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent): Boolean {
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            val uri = intent.data!!

            L.d("MainActivity", "Processing shared content URI: $uri")

            val isHomework = isHomeworkFile(uri)
            val isExam = isExamFile(uri)

            if (!isHomework && !isExam) {
                return false
            }

            val hasCompletedSetup = sharedPreferences.getBoolean("setup_completed", false)
            val selectedKlasse = sharedPreferences.getString("selected_klasse", "")
            val selectedBildungsgang = sharedPreferences.getString("selected_bildungsgang", "")

            if (!hasCompletedSetup || selectedKlasse.isNullOrEmpty() || selectedBildungsgang.isNullOrEmpty()) {
                Toast.makeText(this, getString(R.string.share_setup_required), Toast.LENGTH_LONG).show()
                return false
            }

            sharedPreferences.edit {
                putString(if (isHomework) "pending_shared_homework_uri" else "pending_shared_exam_uri", uri.toString())
                putBoolean("has_pending_shared_content", true)
            }

            binding.root.postDelayed({
                try {
                    val navController = findNavController(R.id.nav_host_fragment_content_main)

                    if (isHomework) {
                        val bundle = Bundle().apply { putString("shared_homework_uri", uri.toString()) }
                        navController.navigate(R.id.nav_slideshow, bundle, androidx.navigation.NavOptions.Builder()
                            .setPopUpTo(R.id.nav_home, false)
                            .build())
                    } else {
                        val bundle = Bundle().apply { putString("shared_exam_uri", uri.toString()) }
                        navController.navigate(R.id.nav_klausuren, bundle, androidx.navigation.NavOptions.Builder()
                            .setPopUpTo(R.id.nav_home, false)
                            .build())
                    }
                } catch (e: Exception) {
                    L.e("MainActivity", "Error handling shared content", e)
                    Toast.makeText(this, getString(R.string.share_error_generic), Toast.LENGTH_LONG).show()
                    cleanupSharedContentState()
                }
            }, 100)

            return true
        }
        return false
    }

    private fun cleanupSharedContentState() {
        isProcessingSharedContent = false
        hasProcessedSharedContent = false
        sharedPreferences.edit {
            putBoolean("shared_content_processed", false)
            putBoolean("has_pending_shared_content", false)
            remove("pending_shared_homework_uri")
            remove("pending_shared_exam_uri")
        }
    }

    private fun isHomeworkFile(uri: Uri): Boolean {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            val content = inputStream?.bufferedReader()?.use { it.readText() }
            inputStream?.close()

            content?.contains("\"type\":\"homework\"") == true &&
                    content.contains("\"subject\"") &&
                    content.contains("\"dueDate\"")
        } catch (_: Exception) {
            false
        }
    }

    private fun isExamFile(uri: Uri): Boolean {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            val content = inputStream?.bufferedReader()?.use { it.readText() }
            inputStream?.close()

            content?.contains("\"type\":\"exam\"") == true &&
                    content.contains("\"subject\"") &&
                    content.contains("\"date\"")
        } catch (_: Exception) {
            false
        }
    }

    private fun processStoredSharedHomework() {
        val uriString = sharedPreferences.getString("pending_shared_homework_uri", null)
        if (uriString != null && !isProcessingSharedContent) {
            sharedPreferences.edit { remove("pending_shared_homework_uri") }

            try {
                val navController = findNavController(R.id.nav_host_fragment_content_main)
                val bundle = Bundle().apply {
                    putString("shared_homework_uri", uriString)
                }
                navController.navigate(R.id.nav_slideshow, bundle, androidx.navigation.NavOptions.Builder()
                    .setPopUpTo(R.id.nav_home, false)
                    .build())
            } catch (e: Exception) {
                L.e("MainActivity", "Error processing stored shared homework", e)
                Toast.makeText(this, getString(R.string.share_error_generic), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun processStoredSharedExam() {
        val uriString = sharedPreferences.getString("pending_shared_exam_uri", null)
        if (uriString != null && !isProcessingSharedContent) {
            L.d("MainActivity", "Processing stored shared exam: $uriString")

            isProcessingSharedContent = true

            try {
                val navController = findNavController(R.id.nav_host_fragment_content_main)
                val bundle = Bundle().apply {
                    putString("shared_exam_uri", uriString)
                }

                if (navController.currentDestination?.id != R.id.nav_klausuren) {
                    navController.navigate(R.id.nav_klausuren, bundle, androidx.navigation.NavOptions.Builder()
                        .setPopUpTo(R.id.nav_home, false)
                        .build())
                } else {
                    val currentFragment = supportFragmentManager.primaryNavigationFragment
                        ?.childFragmentManager?.fragments?.firstOrNull()

                    if (currentFragment is com.thecooker.vertretungsplaner.ui.exams.ExamFragment) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            currentFragment.arguments?.putString("shared_exam_uri", uriString)
                            currentFragment.handleSharedExam()
                        }, 50)
                    }
                }

                sharedPreferences.edit {
                    remove("pending_shared_exam_uri")
                }

            } catch (e: Exception) {
                L.e("MainActivity", "Error processing stored shared exam", e)
                Toast.makeText(this, getString(R.string.share_error_generic), Toast.LENGTH_LONG).show()

                sharedPreferences.edit {
                    remove("pending_shared_exam_uri")
                    putBoolean("shared_content_processed", true)
                }
            } finally {
                Handler(Looper.getMainLooper()).postDelayed({
                    isProcessingSharedContent = false
                }, 500)
            }
        }
    }

    override fun onStart() {
        super.onStart()

        if (sharedPreferences.getBoolean("home_fragment_needs_reset", false)) {
            sharedPreferences.edit { remove("home_fragment_needs_reset") }
        }
    }

    private fun forceCleanNavigation(destinationId: Int) {
        try {
            val navController = findNavController(R.id.nav_host_fragment_content_main)

            L.d("MainActivity", "Forcing clean navigation to destination: $destinationId")

            // clear ALL arguments first
            navController.currentBackStackEntry?.arguments?.clear()
            navController.previousBackStackEntry?.arguments?.clear()

            // navigate to home first to reset navigation state
            navController.navigate(R.id.nav_home, null, androidx.navigation.NavOptions.Builder()
                .setPopUpTo(R.id.nav_home, true)
                .build())

            // then and only then navigate to the intended destination (after slight delay)
            if (destinationId != R.id.nav_home) {
                Handler(Looper.getMainLooper()).postDelayed({
                    navController.navigate(destinationId, null, androidx.navigation.NavOptions.Builder()
                        .setPopUpTo(R.id.nav_home, false)
                        .build())
                }, 50)
            }

        } catch (e: Exception) {
            L.e("MainActivity", "Error in forceCleanNavigation", e)
        }
    }

    private fun setupBackPressHandler() {
        moodleBackPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                val navController = findNavController(R.id.nav_host_fragment_content_main)
                val currentFragment = supportFragmentManager.primaryNavigationFragment
                    ?.childFragmentManager?.fragments?.firstOrNull()

                if (currentFragment is MoodleFragment && navController.currentDestination?.id == R.id.nav_moodle) {
                    if (!currentFragment.onBackPressed()) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, moodleBackPressedCallback!!)
    }

    protected fun setStatusBarStyle() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)

        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION.inv() and
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN.inv()

        val isNightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES

        val barColor = if (isNightMode) {
            ContextCompat.getColor(this, R.color.homework_fragment_bg_dark)
        } else {
            ContextCompat.getColor(this, R.color.homework_fragment_bg_light)
        }

        window.statusBarColor = barColor
        window.navigationBarColor = barColor

        var flags = window.decorView.systemUiVisibility

        if (isNightMode) {
            flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
        } else {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }

        window.decorView.systemUiVisibility = flags
    }

    private fun applyNavigationBarInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            binding.root.setOnApplyWindowInsetsListener { view, insets ->
                val systemWindowInsets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(android.view.WindowInsets.Type.systemBars())
                } else {
                    @Suppress("DEPRECATION")
                    android.graphics.Insets.of(
                        insets.systemWindowInsetLeft,
                        insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight,
                        insets.systemWindowInsetBottom
                    )
                }

                binding.appBarMain.root.setPadding(
                    0,
                    0,
                    0,
                    systemWindowInsets.bottom
                )

                L.d("MainActivity", "Applied navigation bar padding: ${systemWindowInsets.bottom}px")

                insets
            }
        }
    }
}