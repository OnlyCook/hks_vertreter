package com.thecooker.vertretungsplaner.ui.exams

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.thecooker.vertretungsplaner.R

class ExamAdapter(
    private val examList: MutableList<ExamFragment.ExamEntry>,
    private val onExamDeleted: (ExamFragment.ExamEntry) -> Unit,
    private val onExamEdited: (ExamFragment.ExamEntry) -> Unit
) : RecyclerView.Adapter<ExamAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textExamInfo: TextView = view.findViewById(R.id.textExamInfo)
        val textExamNote: TextView = view.findViewById(R.id.textExamNote)
        val btnEdit: Button = view.findViewById(R.id.btnEditExam)
        val btnDelete: Button = view.findViewById(R.id.btnDeleteExam)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_exam, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val exam = examList[position]

        holder.textExamInfo.text = "${exam.subject} | ${exam.getDisplayDateString()}"

        if (exam.note.isNotBlank()) {
            holder.textExamNote.text = exam.note
            holder.textExamNote.visibility = View.VISIBLE
        } else {
            holder.textExamNote.visibility = View.GONE
        }

        // bg color based on exam state
        val backgroundColor = exam.getBackgroundColor()
        if (backgroundColor != android.R.color.transparent) {
            holder.itemView.setBackgroundColor(
                ContextCompat.getColor(holder.itemView.context, backgroundColor)
            )
        } else {
            holder.itemView.setBackgroundColor(
                ContextCompat.getColor(holder.itemView.context, android.R.color.transparent)
            )
        }

        // text appearance for completed/overdue exams
        if (exam.isCompleted) {
            holder.textExamInfo.alpha = 0.6f
            holder.textExamNote.alpha = 0.6f
        } else {
            holder.textExamInfo.alpha = 1.0f
            holder.textExamNote.alpha = 1.0f
        }

        holder.btnEdit.setOnClickListener {
            onExamEdited(exam)
        }

        holder.btnDelete.setOnClickListener {
            onExamDeleted(exam)
        }
    }

    override fun getItemCount(): Int = examList.size
}