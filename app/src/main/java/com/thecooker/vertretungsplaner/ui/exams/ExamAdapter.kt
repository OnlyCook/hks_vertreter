package com.thecooker.vertretungsplaner.ui.exams

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.thecooker.vertretungsplaner.R
import android.text.SpannableString
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.ImageButton

class ExamAdapter(
    private val examList: MutableList<ExamFragment.ExamEntry>,
    private val onExamDeleted: (ExamFragment.ExamEntry) -> Unit,
    private val onExamEdited: (ExamFragment.ExamEntry) -> Unit,
    private val onExamDetailsRequested: ((ExamFragment.ExamEntry) -> Unit)? = null
) : RecyclerView.Adapter<ExamAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textExamInfo: TextView = view.findViewById(R.id.textExamInfo)
        val textExamNote: TextView = view.findViewById(R.id.textExamNote)
        val btnEdit: ImageButton = view.findViewById(R.id.btnEditExam)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteExam)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_exam, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val exam = examList[position]

        val fullText = "${exam.subject} | ${exam.getDisplayDateString(holder.itemView.context)}"
        val spannableString = SpannableString(fullText)
        val subjectLength = exam.subject.length

        spannableString.setSpan(
            StyleSpan(Typeface.BOLD),
            0,
            subjectLength,
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        holder.textExamInfo.text = spannableString

        if (exam.note.isNotBlank()) {
            val lines = exam.note.split("\n")
            val displayText = if (lines.size > 5) {
                lines.take(5).joinToString("\n") + "\n... (Zum Anzeigen tippen)"
            } else {
                exam.note
            }

            holder.textExamNote.text = displayText
            holder.textExamNote.visibility = View.VISIBLE
        } else {
            holder.textExamNote.visibility = View.GONE
        }

        holder.itemView.setOnClickListener {
            showExamDetails(exam)
        }

        val backgroundColorAttr = exam.getBackgroundColorAttribute()
        if (backgroundColorAttr != 0) {
            val typedValue = TypedValue()
            holder.itemView.context.theme.resolveAttribute(backgroundColorAttr, typedValue, true)
            holder.itemView.setBackgroundColor(
                ContextCompat.getColor(holder.itemView.context, typedValue.resourceId)
            )
        } else {
            holder.itemView.setBackgroundColor(
                ContextCompat.getColor(holder.itemView.context, android.R.color.transparent)
            )
        }

        val primaryTextColor = getThemeColor(holder.itemView.context, R.attr.examTextPrimary)
        val secondaryTextColor = getThemeColor(holder.itemView.context, R.attr.examTextSecondary)

        holder.textExamInfo.setTextColor(primaryTextColor)
        holder.textExamNote.setTextColor(secondaryTextColor)

        if (exam.isCompleted) {
            holder.textExamInfo.alpha = 0.6f
            holder.textExamNote.alpha = 0.6f
        } else {
            holder.textExamInfo.alpha = 1.0f
            holder.textExamNote.alpha = 1.0f
        }

        holder.btnEdit.setOnClickListener { onExamEdited(exam) }
        holder.btnDelete.setOnClickListener { onExamDeleted(exam) }
    }

    private fun getThemeColor(context: Context, attrRes: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attrRes, typedValue, true)
        return ContextCompat.getColor(context, typedValue.resourceId)
    }

    private fun showExamDetails(exam: ExamFragment.ExamEntry) {
        onExamDetailsRequested?.invoke(exam)
    }

    override fun getItemCount(): Int = examList.size
}