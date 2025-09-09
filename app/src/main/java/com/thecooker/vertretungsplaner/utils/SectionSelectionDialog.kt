package com.thecooker.vertretungsplaner.utils

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.graphics.drawable.toDrawable
import com.thecooker.vertretungsplaner.R

interface SectionSelectionCallback {
    fun onSectionsSelected(selectedSections: Set<String>)
    fun onSelectionCancelled()
}

class SectionSelectionDialog(
    private val context: Context,
    private val isExport: Boolean,
    private val availableSections: List<BackupManager.BackupSection>,
    private val currentlySelectedSections: Set<String>? = null
) : Dialog(context) {

    private lateinit var titleText: TextView
    private lateinit var sectionsContainer: LinearLayout
    private lateinit var selectAllButton: Button
    private lateinit var deselectAllButton: Button
    private lateinit var saveButton: Button
    private lateinit var cancelButton: Button

    private var callback: SectionSelectionCallback? = null
    private val sectionCheckBoxes = mutableMapOf<String, CheckBox>()

    init {
        setupDialog()
    }

    fun setCallback(callback: SectionSelectionCallback) {
        this.callback = callback
    }

    private fun setupDialog() {
        setContentView(createDialogView())
        setCancelable(true)
        window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.9).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        setOnCancelListener {
            callback?.onSelectionCancelled()
        }
    }

    private fun createDialogView(): View {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_section_selection, null)

        titleText = view.findViewById(R.id.titleText)
        sectionsContainer = view.findViewById(R.id.sectionsContainer)
        selectAllButton = view.findViewById(R.id.selectAllButton)
        deselectAllButton = view.findViewById(R.id.deselectAllButton)
        saveButton = view.findViewById(R.id.saveButton)
        cancelButton = view.findViewById(R.id.cancelButton)

        titleText.text = if (isExport) "Zu exportierende Bereiche auswählen" else "Zu importierende Bereiche auswählen"

        setupSectionCheckboxes()
        setupButtons()

        return view
    }

    private fun setupSectionCheckboxes() {
        availableSections.forEach { section ->
            val checkboxView = LayoutInflater.from(context).inflate(R.layout.item_section_checkbox, sectionsContainer, false)

            val checkBox = checkboxView.findViewById<CheckBox>(R.id.sectionCheckbox)
            val statusText = checkboxView.findViewById<TextView>(R.id.statusText)

            checkBox.text = section.displayName

            val canBeToggled = when {
                isExport -> true
                !isExport && section.status == BackupManager.SectionStatus.FAILED -> false
                !isExport && section.status == BackupManager.SectionStatus.EMPTY -> false
                else -> true
            }

            checkBox.isEnabled = canBeToggled

            checkBox.isChecked = when {
                !canBeToggled -> false
                currentlySelectedSections != null -> currentlySelectedSections.contains(section.name)
                else -> true
            }

            if (!canBeToggled) {
                statusText.visibility = View.VISIBLE
                statusText.text = when (section.status) {
                    BackupManager.SectionStatus.FAILED -> "Fehler - kann nicht importiert werden"
                    BackupManager.SectionStatus.EMPTY -> "Keine Daten verfügbar"
                    else -> ""
                }
                checkBox.alpha = 0.5f
            } else {
                statusText.visibility = View.GONE
                checkBox.alpha = 1.0f
            }

            sectionCheckBoxes[section.name] = checkBox
            sectionsContainer.addView(checkboxView)
        }
    }

    private fun setupButtons() {
        selectAllButton.setOnClickListener {
            sectionCheckBoxes.values.forEach { checkBox ->
                if (checkBox.isEnabled) {
                    checkBox.isChecked = true
                }
            }
        }

        deselectAllButton.setOnClickListener {
            sectionCheckBoxes.values.forEach { checkBox ->
                if (checkBox.isEnabled) {
                    checkBox.isChecked = false
                }
            }
        }

        saveButton.setOnClickListener {
            val selectedSections = sectionCheckBoxes
                .filter { it.value.isChecked && it.value.isEnabled }
                .keys
                .toSet()

            if (selectedSections.isEmpty()) {
                Toast.makeText(context, "Bitte wählen Sie mindestens einen Bereich aus", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            callback?.onSectionsSelected(selectedSections)
            dismiss()
        }

        cancelButton.setOnClickListener {
            callback?.onSelectionCancelled()
            dismiss()
        }
    }
}