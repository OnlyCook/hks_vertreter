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

class MainActivity : BaseActivity() {
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding

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

        navigateToStartupPage()
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
    }

    private fun navigateToStartupPage() {
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
}