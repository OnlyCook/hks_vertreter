package com.thecooker.vertretungsplaner.utils

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import com.thecooker.vertretungsplaner.R
import androidx.core.graphics.drawable.toDrawable

interface DialogDismissCallback {
    fun onDialogDismissed(isExport: Boolean, wasSuccessful: Boolean)
}

class BackupProgressDialog(
    private val context: Context,
    private val isExport: Boolean,
    private val backupManager: BackupManager
) : Dialog(context), BackupManager.BackupProgressCallback {

    private lateinit var titleText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var sectionsContainer: LinearLayout
    private lateinit var cancelButton: Button
    private lateinit var errorButton: Button
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
        scrollView = view.findViewById(R.id.scrollView)

        titleText.text = if (isExport) "Sicherung wird erstellt..." else "Sicherung wird wiederhergestellt..."

        errorButton.visibility = View.GONE
        errorButton.setOnClickListener {
            val errorReport = backupManager.generateErrorReport(processedSections, if (isExport) "Export" else "Import")
            showErrorDetails(errorReport)
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
        val sections = listOf(
            BackupManager.BackupSection("TIMETABLE_DATA", "Stundenplan-Daten"),
            BackupManager.BackupSection("CALENDAR_DATA", "Kalender-Daten"),
            BackupManager.BackupSection("HOMEWORK_DATA", "Hausaufgaben"),
            BackupManager.BackupSection("EXAM_DATA", "Klausuren"),
            BackupManager.BackupSection("GRADE_DATA", "Noten"),
            BackupManager.BackupSection("APP_SETTINGS", "App-Einstellungen")
        )

        progressBar.max = sections.size

        sections.forEach { section ->
            val sectionView = SectionProgressView(context, section.displayName)
            sectionsContainer.addView(sectionView.view)
            sectionViews[section.name] = sectionView
        }
    }

    override fun onSectionStarted(section: BackupManager.BackupSection) {
        if (isCancelled) return

        sectionViews[section.name]?.setStatus(BackupManager.SectionStatus.IN_PROGRESS)
    }

    override fun onSectionCompleted(section: BackupManager.BackupSection) {
        if (isCancelled) return

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
            success && !hasEmptySections -> if (isExport) "Sicherung erfolgreich erstellt!" else "Sicherung erfolgreich wiederhergestellt!"
            hasErrors -> if (isExport) "Sicherung mit Fehlern erstellt" else "Wiederherstellung mit Fehlern abgeschlossen"
            else -> if (isExport) "Sicherung erstellt (einige Bereiche leer)" else "Wiederherstellung abgeschlossen (einige Bereiche leer)"
        }

        "Fortfahren".also { cancelButton.text = it }

        if (hasErrors || hasEmptySections) {
            errorButton.visibility = View.VISIBLE
        }
    }

    private fun showErrorDetails(errorReport: String) {
        val dialog = Dialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_error_details, null)
        dialog.setContentView(view)

        val errorText = view.findViewById<TextView>(R.id.errorText)
        val copyButton = view.findViewById<ImageButton>(R.id.copyButton)
        val closeButton = view.findViewById<Button>(R.id.closeButton)

        errorText.text = errorReport

        copyButton.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("HKS Error Report", errorReport)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Fehlerprotokoll kopiert", Toast.LENGTH_SHORT).show()
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
                    "Keine Daten".also { errorText.text = it }
                    errorText.visibility = View.VISIBLE
                }
            }
        }
    }
}