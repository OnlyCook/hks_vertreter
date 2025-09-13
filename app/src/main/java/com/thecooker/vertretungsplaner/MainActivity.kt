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
import android.widget.Toast
import androidx.core.content.edit

class MainActivity : BaseActivity() {
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private var isProcessingSharedContent = false
    private var hasProcessedSharedContent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // apply theme settings before setting content view
        val darkModeEnabled = sharedPreferences.getBoolean("dark_mode_enabled", false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AppCompatDelegate.setDefaultNightMode(
                if (darkModeEnabled) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.appBarMain.toolbar)

        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView: NavigationView = binding.navView
        val navController = findNavController(R.id.nav_host_fragment_content_main)

        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.nav_home, R.id.nav_gallery, R.id.nav_slideshow, R.id.nav_klausuren, R.id.nav_noten
            ), drawerLayout
        )

        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            L.d("MainActivity", "Navigation changed to: ${destination.label}")
        }

        if (!handleIncomingIntent(intent)) {
            navigateToStartupPage()
        }
    }

    override fun onResume() {
        super.onResume()

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
        L.d("MainActivity", "Current destination: ${navController.currentDestination?.label}")

        val navView: NavigationView = binding.navView
        val checkedItem = navView.checkedItem
        L.d("MainActivity", "Navigation drawer checked item: ${checkedItem?.title}")

        if (!sharedPreferences.getBoolean("shared_content_processed", false)) {
            binding.root.postDelayed({
                processStoredSharedHomework()
                processStoredSharedExam()
            }, 500)
        }

        binding.root.postDelayed({
            hasProcessedSharedContent = false
        }, 5000)
    }

    private fun navigateToStartupPage() {
        if (isProcessingSharedContent) {
            L.d("MainActivity", "Skipping startup navigation - processing shared content")
            return
        }

        val startupPageIndex = sharedPreferences.getInt("startup_page_index", 0)

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
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent): Boolean {
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            val uri = intent.data!!

            if (isProcessingSharedContent || hasProcessedSharedContent) {
                L.d("MainActivity", "Already processing or processed shared content - ignoring")
                return true
            }

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

            isProcessingSharedContent = true
            hasProcessedSharedContent = true

            sharedPreferences.edit {
                putString(if (isHomework) "pending_shared_homework_uri" else "pending_shared_exam_uri", uri.toString())
                putBoolean("skip_home_loading", true)
                putBoolean("shared_content_processed", true)
            }

            binding.root.postDelayed({
                try {
                    val navController = findNavController(R.id.nav_host_fragment_content_main)

                    if (isHomework) {
                        val bundle = Bundle().apply { putString("shared_homework_uri", uri.toString()) }
                        navController.navigate(R.id.nav_slideshow, bundle)
                    } else {
                        val bundle = Bundle().apply { putString("shared_exam_uri", uri.toString()) }
                        navController.navigate(R.id.nav_klausuren, bundle)
                    }

                    binding.root.postDelayed({
                        sharedPreferences.edit {
                            remove("skip_home_loading")
                            remove("shared_content_processed")
                        }
                        isProcessingSharedContent = false
                    }, 2000)

                } catch (e: Exception) {
                    L.e("MainActivity", "Error handling shared content", e)
                    Toast.makeText(this, getString(R.string.share_error_generic), Toast.LENGTH_LONG).show()
                    sharedPreferences.edit {
                        remove("skip_home_loading")
                        remove("shared_content_processed")
                    }
                    isProcessingSharedContent = false
                    hasProcessedSharedContent = false
                }
            }, 200)

            return true
        }
        return false
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
            } finally {
                isProcessingSharedContent = false
            }
        }
    }

    private fun processStoredSharedExam() {
        val uriString = sharedPreferences.getString("pending_shared_exam_uri", null)
        if (uriString != null && !isProcessingSharedContent) {
            sharedPreferences.edit { remove("pending_shared_exam_uri") }

            try {
                val navController = findNavController(R.id.nav_host_fragment_content_main)
                val bundle = Bundle().apply {
                    putString("shared_exam_uri", uriString)
                }
                navController.navigate(R.id.nav_klausuren, bundle, androidx.navigation.NavOptions.Builder()
                    .setPopUpTo(R.id.nav_home, false)
                    .build())
            } catch (e: Exception) {
                L.e("MainActivity", "Error processing stored shared exam", e)
                Toast.makeText(this, getString(R.string.share_error_generic), Toast.LENGTH_LONG).show()
            } finally {
                isProcessingSharedContent = false
            }
        }
    }

    override fun onStart() {
        super.onStart()

        if (sharedPreferences.getBoolean("home_fragment_needs_reset", false)) {
            sharedPreferences.edit { remove("home_fragment_needs_reset") }

            val navController = findNavController(R.id.nav_host_fragment_content_main)
            navController.addOnDestinationChangedListener { _, destination, _ ->
                if (destination.id == R.id.nav_home) {
                    val homeFragment = supportFragmentManager.primaryNavigationFragment
                        ?.childFragmentManager
                        ?.fragments
                        ?.find { it.javaClass.simpleName == "HomeFragment" }

                    homeFragment?.arguments = (homeFragment.arguments ?: Bundle()).apply {
                        putBoolean("from_shared_navigation", true)
                    }
                }
            }
        }
    }
}