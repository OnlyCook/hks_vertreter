package com.thecooker.vertretungsplaner.utils

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import com.thecooker.vertretungsplaner.R
import androidx.core.graphics.drawable.toDrawable

interface DialogDismissCallback {
    fun onDialogDismissed(isExport: Boolean, wasSuccessful: Boolean)
    fun onEditRequested(isExport: Boolean, currentSections: List<BackupManager.BackupSection>)
}

class BackupProgressDialog(
    private val context: Context,
    private val isExport: Boolean,
    private val backupManager: BackupManager,
    private val enabledSections: Set<String>? = null
) : Dialog(context), BackupManager.BackupProgressCallback {

    private lateinit var titleText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var sectionsContainer: LinearLayout
    private lateinit var cancelButton: Button
    private lateinit var errorButton: Button
    private lateinit var editButton: Button
    private lateinit var scrollView: ScrollView

    private var isCancelled = false
    private var isOperationCompleted = false
    private var dismissCallback: DialogDismissCallback? = null
    private var wasOperationSuccessful = false
    private val sectionViews = mutableMapOf<String, SectionProgressView>()
    private val processedSections = mutableListOf<BackupManager.BackupSection>()

    val progressCallback: BackupManager.BackupProgressCallback = this

    init {
        setupDialog()
    }

    fun setDismissCallback(callback: DialogDismissCallback) {
        this.dismissCallback = callback
    }

    override fun dismiss() {
        dismissCallback?.onDialogDismissed(isExport, wasOperationSuccessful && isOperationCompleted)
        super.dismiss()
    }

    private fun setupDialog() {
        setContentView(createDialogView())
        setCancelable(false)
        window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.9).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun createDialogView(): View {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_backup_progress, null)

        titleText = view.findViewById(R.id.titleText)
        progressBar = view.findViewById(R.id.progressBar)
        sectionsContainer = view.findViewById(R.id.sectionsContainer)
        cancelButton = view.findViewById(R.id.cancelButton)
        errorButton = view.findViewById(R.id.errorButton)
        editButton = view.findViewById(R.id.editButton)
        scrollView = view.findViewById(R.id.scrollView)

        val buttonColor = context.getThemeColor(R.attr.dialogSectionButtonColor)
        cancelButton.setTextColor(buttonColor)
        errorButton.setTextColor(buttonColor)
        editButton.setTextColor(buttonColor)

        titleText.text = if (isExport)
            context.getString(R.string.bac_pro_backup_is_being_created)
        else
            context.getString(R.string.bac_pro_backup_is_being_restored)

        errorButton.visibility = View.GONE
        errorButton.setOnClickListener {
            val errorReport = backupManager.generateErrorReport(processedSections, if (isExport) "Export" else "Import")
            showErrorDetails(errorReport)
        }

        editButton.visibility = View.GONE
        editButton.setOnClickListener {
            dismissCallback?.onEditRequested(isExport, processedSections.toList())
            dismiss()
        }

        cancelButton.setOnClickListener {
            if (isOperationCompleted) {
                dismiss()
            } else {
                isCancelled = true
                dismiss()
            }
        }

        setupSectionViews()
        return view
    }


    private fun setupSectionViews() {
        val allSections = listOf(
            BackupManager.BackupSection("TIMETABLE_DATA", context.getString(R.string.bac_pro_timetable_data)),
            BackupManager.BackupSection("CALENDAR_DATA", context.getString(R.string.bac_pro_calendar_data)),
            BackupManager.BackupSection("HOMEWORK_DATA", context.getString(R.string.bac_pro_homework_data)),
            BackupManager.BackupSection("EXAM_DATA", context.getString(R.string.bac_pro_exam_data)),
            BackupManager.BackupSection("GRADE_DATA", context.getString(R.string.bac_pro_grades_data)),
            BackupManager.BackupSection("APP_SETTINGS", context.getString(R.string.bac_pro_app_settings_data))
        )

        val sectionsToShow = if (enabledSections != null) {
            allSections.filter { enabledSections.contains(it.name) }
        } else {
            allSections
        }

        progressBar.max = sectionsToShow.size

        val excludedSections = if (enabledSections != null) {
            allSections.filter { !enabledSections.contains(it.name) }
        } else {
            emptyList()
        }

        sectionsToShow.forEach { section ->
            val sectionView = SectionProgressView(context, section.displayName)
            sectionsContainer.addView(sectionView.view)
            sectionViews[section.name] = sectionView
        }

        excludedSections.forEach { section ->
            val sectionView = SectionProgressView(context, section.displayName)
            sectionView.setStatus(BackupManager.SectionStatus.EXCLUDED)
            sectionsContainer.addView(sectionView.view)
        }
    }

    override fun onSectionStarted(section: BackupManager.BackupSection) {
        if (isCancelled) return
        if (enabledSections != null && !enabledSections.contains(section.name)) return

        sectionViews[section.name]?.setStatus(BackupManager.SectionStatus.IN_PROGRESS)
    }

    override fun onSectionCompleted(section: BackupManager.BackupSection) {
        if (isCancelled) return
        if (enabledSections != null && !enabledSections.contains(section.name)) return

        processedSections.add(section)
        sectionViews[section.name]?.setStatus(section.status, section.errorMessage)
        progressBar.progress = processedSections.size
    }

    override fun onBackupCompleted(success: Boolean, sections: List<BackupManager.BackupSection>) {
        if (isCancelled) return

        isOperationCompleted = true
        wasOperationSuccessful = success

        val hasErrors = sections.any { it.status == BackupManager.SectionStatus.FAILED }
        val hasEmptySections = sections.any { it.status == BackupManager.SectionStatus.EMPTY }

        titleText.text = when {
            success && !hasEmptySections -> if (isExport) context.getString(R.string.bac_pro_backup_created_successfully) else context.getString(R.string.bac_pro_backup_restored_successfully)
            hasErrors -> if (isExport) context.getString(R.string.bac_pro_backup_created_with_errors) else context.getString(R.string.bac_pro_backup_restored_with_errors)
            else -> if (isExport) context.getString(R.string.bac_pro_backup_created_with_empty_sectoins) else context.getString(R.string.bac_pro_backup_restored_with_empty_sections)
        }

        context.getString(R.string.bac_pro_continue).also { cancelButton.text = it }

        if (hasErrors || hasEmptySections) {
            errorButton.visibility = View.VISIBLE
        }

        if (enabledSections == null) {
            editButton.visibility = View.VISIBLE
        }
    }

    private fun showErrorDetails(errorReport: String) {
        val dialog = Dialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_error_details, null)
        dialog.setContentView(view)

        val errorText = view.findViewById<TextView>(R.id.errorText)
        val copyButton = view.findViewById<ImageButton>(R.id.copyButton)
        val closeButton = view.findViewById<Button>(R.id.closeButton)

        val buttonColor = context.getThemeColor(R.attr.dialogSectionButtonColor)
        closeButton.setTextColor(buttonColor)

        errorText.text = errorReport

        copyButton.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("HKS Error Report", errorReport)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, context.getString(R.string.bac_pro_error_log_copied), Toast.LENGTH_SHORT).show()
        }

        closeButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.9).toInt(),
            (context.resources.displayMetrics.heightPixels * 0.8).toInt()
        )

        dialog.show()
    }

    private class SectionProgressView(private val context: Context, private val sectionName: String) {
        val view: View = LayoutInflater.from(context).inflate(R.layout.item_section_progress, null)

        private val nameText: TextView = view.findViewById(R.id.sectionName)
        private val statusIcon: ImageView = view.findViewById(R.id.statusIcon)
        private val progressSpinner: ProgressBar = view.findViewById(R.id.progressSpinner)
        private val errorText: TextView = view.findViewById(R.id.errorText)

        init {
            nameText.text = sectionName
            setStatus(BackupManager.SectionStatus.PENDING)
        }

        fun setStatus(status: BackupManager.SectionStatus, errorMessage: String? = null) {
            progressSpinner.visibility = View.GONE
            statusIcon.visibility = View.VISIBLE
            errorText.visibility = View.GONE

            when (status) {
                BackupManager.SectionStatus.PENDING -> {
                    statusIcon.setImageResource(R.drawable.ic_pending)
                    statusIcon.setColorFilter(ContextCompat.getColor(context, android.R.color.darker_gray))
                }
                BackupManager.SectionStatus.IN_PROGRESS -> {
                    statusIcon.visibility = View.GONE
                    progressSpinner.visibility = View.VISIBLE
                }
                BackupManager.SectionStatus.SUCCESS -> {
                    statusIcon.setImageResource(R.drawable.ic_check)
                    statusIcon.setColorFilter(ContextCompat.getColor(context, android.R.color.holo_green_dark))
                }
                BackupManager.SectionStatus.FAILED -> {
                    statusIcon.setImageResource(R.drawable.ic_error)
                    statusIcon.setColorFilter(ContextCompat.getColor(context, android.R.color.holo_red_dark))
                    if (errorMessage != null) {
                        errorText.text = errorMessage
                        errorText.visibility = View.VISIBLE
                    }
                }
                BackupManager.SectionStatus.EMPTY -> {
                    statusIcon.setImageResource(R.drawable.ic_info)
                    statusIcon.setColorFilter(ContextCompat.getColor(context, android.R.color.holo_orange_dark))
                    errorText.text = context.getString(R.string.bac_pro_no_data)
                    errorText.visibility = View.VISIBLE
                }
                BackupManager.SectionStatus.EXCLUDED -> {
                    statusIcon.setImageResource(R.drawable.ic_remove)
                    statusIcon.setColorFilter(ContextCompat.getColor(context, android.R.color.darker_gray))
                    errorText.text = context.getString(R.string.bac_pro_excluded)
                    errorText.visibility = View.VISIBLE
                    nameText.alpha = 0.6f
                }
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
}