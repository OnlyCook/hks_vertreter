package com.thecooker.vertretungsplaner

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.net.toUri
import androidx.core.content.edit
import com.thecooker.vertretungsplaner.R.string.setup_loading_classes
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray

class SetupActivity : AppCompatActivity() {
    private lateinit var spinnerBildungsgang: Spinner
    private lateinit var spinnerKlasse: Spinner
    private lateinit var cardKlasseSelection: CardView
    private lateinit var btnBestaetigen: Button
    private lateinit var btnHilfe: Button
    private lateinit var sharedPreferences: SharedPreferences

    private var fetchedBildungsgangData: Map<String, List<String>>? = null

    private val verifiedBildungsgang = setOf("BG") // thoroughly tested bgs

	// check which one of these is "Fachschule für Technik" or "Fachhochschulreife" (custom lessons)
    private val bildungsgangData = mapOf(
        "AM" to listOf("10AM", "11AM", "12AM"),
        "AO" to listOf("10AO1", "10AO2", "10AO3", "10AO4", "11AO1", "11AO2", "11AO3", "11AO4", "12AO1", "12AO2", "12AO3", "12AO4"),
        "AOB" to listOf("11AOB", "12AOB"),
        "AOW" to listOf("12AOW"),
        "BFS" to listOf("10BFS1", "10BFS2", "11BFS1", "11BFS2"),
        "BG" to listOf("11BG1", "11BG2", "11BG3", "12BG1", "12BG2", "13BG1", "13BG2"),
        "BzB" to listOf("10BzB1", "10BzB2", "10BzB3"),
        "EL" to listOf("10EL1", "10EL2", "10EL3", "11EL1", "11EL2", "11EL3", "12EL1", "12EL2", "12ELW"),
        "EZ" to listOf("10EZ1", "10EZ2", "11EZ1", "11EZ2", "12EZ1", "12EZ2"),
        "FOS" to listOf("11FOS1", "11FOS2", "12FOS1", "12FOS2"),
        "FS" to listOf("02FS", "03FS", "04FS"),
        "Förd" to listOf("Förd"),
        "IM" to listOf("10IM1", "10IM2", "11IM1", "11IM2", "11IM3", "12IM1", "12IM2", "13IM"),
        "KB" to listOf("10KB1", "10KB3", "11KB2", "11KB3", "12KB1", "12KB2", "13KB"),
        "KM" to listOf("10KM1", "10KM2", "10KM3", "10KM4", "10KM5", "11KM1", "11KM2", "11KM3", "11KM4", "11KM5", "12KM1", "12KM2", "12KM3", "12KM4", "12KM5", "13KM1", "13KM2", "13KM3"),
        "KOM" to listOf("10KOM", "11KOM", "12KOM", "13KOM"),
        "ME" to listOf("10ME1", "10ME2", "10ME3", "10ME4", "11ME1", "11ME2", "11ME3", "11ME4", "12ME1", "12ME2", "12ME3", "12ME4", "13ME1", "13ME2"),
        "ZM" to listOf("11ZM", "12ZM"),
        "ZU" to listOf("12ZU1"),
        "ZW" to listOf("10ZW1", "10ZW2", "10ZW4", "11ZW1", "11ZW2", "11ZW3", "11ZW4", "12ZW1", "12ZW2", "12ZW4", "13ZW1", "13ZW2")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE)

        // check if setup is already completed
        if (sharedPreferences.getBoolean("setup_completed", false)) {
            navigateToMain()
            return
        }

        initializeViews()

        fetchClassesFromAPI() // fetch classes/bildungsgänge

        setupListeners()
        setupLanguageButton()
    }

    private fun initializeViews() {
        spinnerBildungsgang = findViewById(R.id.spinnerBildungsgang)
        spinnerKlasse = findViewById(R.id.spinnerKlasse)
        cardKlasseSelection = findViewById(R.id.cardKlasseSelection)
        btnBestaetigen = findViewById(R.id.btnBestaetigen)
        btnHilfe = findViewById(R.id.btnHilfe)
    }

    private fun setupBildungsgangSpinner() {
        val bildungsgangOptions = mutableListOf(getString(R.string.act_set_program_select))
        bildungsgangOptions.addAll(getCurrentBildungsgangData().keys.sorted())

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, bildungsgangOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerBildungsgang.adapter = adapter
    }

    private fun setupListeners() {
        spinnerBildungsgang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedBildungsgang = parent?.getItemAtPosition(position).toString()

                if (position == 0) {
                    hideKlasseElements()
                } else {
                    if (!verifiedBildungsgang.contains(selectedBildungsgang)) {
                        showUnverifiedBildungsgangDialog(selectedBildungsgang)
                    }

                    showKlasseElements()
                    populateKlasseSpinner(selectedBildungsgang)
                }

                btnBestaetigen.visibility = View.GONE
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                hideKlasseElements()
            }
        }

        spinnerKlasse.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0) {
                    btnBestaetigen.visibility = View.VISIBLE
                } else {
                    btnBestaetigen.visibility = View.GONE
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                btnBestaetigen.visibility = View.GONE
            }
        }

        btnBestaetigen.setOnClickListener {
            saveUserSelection()
            navigateToMain()
        }

        btnHilfe.setOnClickListener {
            showHelpDialog()
        }
    }

    private fun hideKlasseElements() {
        cardKlasseSelection.visibility = View.GONE
        btnBestaetigen.visibility = View.GONE
    }

    private fun showKlasseElements() {
        cardKlasseSelection.visibility = View.VISIBLE
    }

    private fun populateKlasseSpinner(bildungsgang: String) {
        val klasseOptions = mutableListOf(getString(R.string.act_set_class_select))
        getCurrentBildungsgangData()[bildungsgang]?.let { classes ->
            klasseOptions.addAll(classes)
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, klasseOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerKlasse.adapter = adapter
    }

    private fun showUnverifiedBildungsgangDialog(bildungsgang: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.setup_unverified_program_title))
            .setIcon(android.R.drawable.ic_dialog_info)
            .setMessage(getString(R.string.setup_unverified_program_message, bildungsgang))
            .setPositiveButton(getString(R.string.setup_understood)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.setup_need_help))
            .setMessage(getString(R.string.setup_missing_program_message))
            .setPositiveButton(getString(R.string.setup_send_email)) { dialog, _ ->
                openEmailClient()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun openEmailClient() {
        val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = getString(R.string.set_act_hks_contact_email).toUri()
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.setup_email_subject))
            putExtra(Intent.EXTRA_TEXT, getString(R.string.setup_email_body))
        }

        try {
            startActivity(Intent.createChooser(emailIntent, getString(R.string.set_act_choose_email_app)))
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.setup_no_email_app_found), Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveUserSelection() {
        val selectedBildungsgang = spinnerBildungsgang.selectedItem.toString()
        val selectedKlasse = spinnerKlasse.selectedItem.toString()

        sharedPreferences.edit {
            putBoolean("setup_completed", true)
            putString("selected_bildungsgang", selectedBildungsgang)
            putString("selected_klasse", selectedKlasse)
        }

        Toast.makeText(this, getString(R.string.setup_selection_saved, selectedBildungsgang, selectedKlasse), Toast.LENGTH_SHORT).show()
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun setupLanguageButton() {
        val btnLanguageSelection = findViewById<ImageButton>(R.id.btnLanguageSelection)

        btnLanguageSelection.setOnClickListener {
            showLanguageSelectionDialog()
        }
    }

    private fun showLanguageSelectionDialog() {
        val sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val isAutoDetect = sharedPreferences.getBoolean("language_auto_detect", true)

        val currentLanguage = if (isAutoDetect) {
            val systemLanguage = java.util.Locale.getDefault().language
            if (systemLanguage in arrayOf("de", "en")) systemLanguage else "de"
        } else {
            sharedPreferences.getString("selected_language", "de") ?: "de"
        }

        val languages = arrayOf("de", "en")
        val languageNames = arrayOf(
            "🇩🇪 ${getString(R.string.german)}",
            "🇺🇸 ${getString(R.string.english)}"
        )

        val currentIndex = languages.indexOf(currentLanguage).let { if (it == -1) 0 else it }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_language))
            .setSingleChoiceItems(languageNames, currentIndex) { dialog, which ->
                val selectedLanguageCode = languages[which]

                sharedPreferences.edit {
                    putString("selected_language", selectedLanguageCode)
                    putBoolean("language_auto_detect", false)
                }

                showRestartDialog()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showRestartDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.restart_required))
            .setMessage(getString(R.string.restart_required_message))
            .setPositiveButton(getString(R.string.restart_now)) { _, _ ->
                restartApp()
            }
            .setNegativeButton(getString(R.string.restart_later), null)
            .show()
    }

    private fun restartApp() {
        val savedLanguage = LanguageUtil.getSavedLanguage(this)
        LanguageUtil.setLanguage(this, savedLanguage)

        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
    }

    private fun fetchClassesFromAPI() {
        showLoadingDialog()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("https://www.heinrich-kleyer-schule.de/kontakt/nachricht-klassenlehrer/api/classes")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val jsonArray = JSONArray(response)

                    val classes = mutableListOf<String>()
                    for (i in 0 until jsonArray.length()) {
                        classes.add(jsonArray.getString(i))
                    }

                    val organizedData = organizeClassesByBildungsgang(classes)

                    withContext(Dispatchers.Main) {
                        fetchedBildungsgangData = organizedData
                        hideLoadingDialog()
                        setupBildungsgangSpinner()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        hideLoadingDialog()
                        showFetchErrorDialog()
                    }
                }
                connection.disconnect()

            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    hideLoadingDialog()
                    showFetchErrorDialog()
                }
            }
        }
    }

    private fun organizeClassesByBildungsgang(classes: List<String>): Map<String, List<String>> {
        val organized = mutableMapOf<String, MutableList<String>>()

        for (className in classes) {
            val bildungsgang = extractBildungsgang(className)
            if (organized[bildungsgang] == null) {
                organized[bildungsgang] = mutableListOf()
            }
            organized[bildungsgang]!!.add(className)
        }

        organized.forEach { (_, classList) ->
            classList.sort()
        }

        return organized.toMap()
    }

    private fun extractBildungsgang(className: String): String {
        // remove all digits from the class name
        return className.replace(Regex("\\d"), "")
    }

    private var loadingDialog: AlertDialog? = null

    private fun showLoadingDialog() {
        loadingDialog = AlertDialog.Builder(this)
            .setTitle(getString(setup_loading_classes))
            .setMessage(getString(R.string.setup_loading_classes_message))
            .setCancelable(false)
            .create()
        loadingDialog?.show()
    }

    private fun hideLoadingDialog() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }

    private fun showFetchErrorDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.setup_fetch_error_title))
            .setMessage(getString(R.string.setup_fetch_error_message))
            .setNegativeButton(getString(R.string.setup_use_offline_data)) { dialog, _ ->
                dialog.dismiss()
                fetchedBildungsgangData = null
                setupBildungsgangSpinner()
            }
            .setPositiveButton(getString(R.string.setup_retry)) { dialog, _ ->
                dialog.dismiss()
                fetchClassesFromAPI()
            }
            .show()
    }

    private fun getCurrentBildungsgangData(): Map<String, List<String>> {
        return fetchedBildungsgangData ?: bildungsgangData
    }
}