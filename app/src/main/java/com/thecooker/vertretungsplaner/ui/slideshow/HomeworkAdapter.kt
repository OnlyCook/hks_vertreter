package com.thecooker.vertretungsplaner.ui.slideshow

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.thecooker.vertretungsplaner.R
import android.text.SpannableString
import android.text.style.StyleSpan
import android.graphics.Typeface

class HomeworkAdapter(
    private val homeworkList: MutableList<SlideshowFragment.HomeworkEntry>,
    private val onHomeworkToggled: (SlideshowFragment.HomeworkEntry, Boolean) -> Unit,
    private val onHomeworkDeleted: (SlideshowFragment.HomeworkEntry) -> Unit,
    private val onHomeworkEdited: (SlideshowFragment.HomeworkEntry) -> Unit,
    private val onHomeworkViewed: (SlideshowFragment.HomeworkEntry) -> Unit,
    private val onChecklistItemToggled: (SlideshowFragment.HomeworkEntry, SlideshowFragment.ChecklistItem, Boolean) -> Unit
) : RecyclerView.Adapter<HomeworkAdapter.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_NORMAL = 0
        private const val VIEW_TYPE_OVERDUE = 1
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkBox: CheckBox = view.findViewById(R.id.checkBoxHomework)
        val btnView: Button = view.findViewById(R.id.btnView)
        val btnEdit: Button = view.findViewById(R.id.btnEdit)
        val btnDelete: Button = view.findViewById(R.id.btnDelete)
    }

    override fun getItemViewType(position: Int): Int {
        val homework = homeworkList[position]
        return if (homework.isOverdueForUI() && !homework.isCompleted) {
            VIEW_TYPE_OVERDUE
        } else {
            VIEW_TYPE_NORMAL
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_homework, parent, false)

        val holder = ViewHolder(view)

        if (viewType == VIEW_TYPE_OVERDUE) {
            holder.checkBox.setButtonDrawable(R.drawable.ic_homework_overdue)
        }

        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val homework = homeworkList[position]

        holder.checkBox.setOnCheckedChangeListener(null)

        val checkboxText = "${homework.subject} | ${homework.getDueDateString()}"
        val spannableString = SpannableString(checkboxText)
        spannableString.setSpan(
            StyleSpan(Typeface.BOLD),
            0,
            homework.subject.length,
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        holder.checkBox.text = spannableString

        if (getItemViewType(position) == VIEW_TYPE_OVERDUE) {
            holder.checkBox.isEnabled = false
            holder.checkBox.alpha = 0.6f
            holder.checkBox.isChecked = false

            holder.itemView.setBackgroundColor(
                ContextCompat.getColor(holder.itemView.context, android.R.color.darker_gray)
            )

            holder.checkBox.setOnCheckedChangeListener(null)
            holder.itemView.setOnClickListener(null)
        } else {
            // normal
            holder.checkBox.isEnabled = true
            holder.checkBox.alpha = 1.0f
            holder.checkBox.isChecked = homework.isCompleted

            val backgroundColor = homework.getBackgroundColor()
            if (backgroundColor != android.R.color.transparent) {
                holder.itemView.setBackgroundColor(
                    ContextCompat.getColor(holder.itemView.context, backgroundColor)
                )
            } else {
                holder.itemView.setBackgroundColor(
                    ContextCompat.getColor(holder.itemView.context, android.R.color.transparent)
                )
            }

            holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                onHomeworkToggled(homework, isChecked)
            }

            holder.itemView.setOnClickListener {
                holder.checkBox.toggle()
            }
        }

        holder.btnView.setOnClickListener {
            onHomeworkViewed(homework)
        }

        holder.btnEdit.setOnClickListener {
            onHomeworkEdited(homework)
        }

        holder.btnDelete.setOnClickListener {
            onHomeworkDeleted(homework)
        }
    }

    override fun getItemCount(): Int = homeworkList.size
}