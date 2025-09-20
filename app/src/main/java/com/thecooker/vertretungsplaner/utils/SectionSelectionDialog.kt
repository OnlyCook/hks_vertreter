package com.thecooker.vertretungsplaner.utils

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
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

        titleText.text = if (isExport) context.getString(R.string.sec_sel_select_sections_to_export) else context.getString(R.string.sec_sel_select_sections_to_import)

        setupSectionCheckboxes()
        setupButtons()

        return view
    }

    private fun translateSectionDisplayName(originalDisplayName: String): String {
        return when (originalDisplayName.lowercase()) {
            "stundenplan-daten" -> context.getString(R.string.bac_pro_timetable_data)
            "kalender-daten" -> context.getString(R.string.bac_pro_calendar_data)
            "hausaufgaben" -> context.getString(R.string.bac_pro_homework_data)
            "klausuren" -> context.getString(R.string.bac_pro_exam_data)
            "noten" -> context.getString(R.string.bac_pro_grades_data)
            "app-einstellungen" -> context.getString(R.string.bac_pro_app_settings_data)
            else -> originalDisplayName
        }
    }

    private fun setupSectionCheckboxes() {
        availableSections.forEach { section ->
            val checkboxView = LayoutInflater.from(context).inflate(R.layout.item_section_checkbox, sectionsContainer, false)

            val checkBox = checkboxView.findViewById<CheckBox>(R.id.sectionCheckbox)
            val statusText = checkboxView.findViewById<TextView>(R.id.statusText)

            val translatedName = translateSectionDisplayName(section.displayName)
            checkBox.text = translatedName

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
                    BackupManager.SectionStatus.FAILED -> context.getString(R.string.sec_sel_error_couldnt_import)
                    BackupManager.SectionStatus.EMPTY -> context.getString(R.string.sec_sel_no_data_available)
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
        val buttonColor = context.getThemeColor(R.attr.dialogSectionButtonColor)

        saveButton.setTextColor(buttonColor)
        cancelButton.setTextColor(buttonColor)

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
                Toast.makeText(
                    context,
                    context.getString(R.string.sec_sel_please_select_at_least_one_section),
                    Toast.LENGTH_SHORT
                ).show()
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
}