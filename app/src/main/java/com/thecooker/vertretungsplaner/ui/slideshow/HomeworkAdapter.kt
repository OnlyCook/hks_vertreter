package com.thecooker.vertretungsplaner.ui.slideshow

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.thecooker.vertretungsplaner.R
import android.text.SpannableString
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView

class HomeworkAdapter(
    private val homeworkList: MutableList<SlideshowFragment.HomeworkEntry>,
    private val onHomeworkToggled: (SlideshowFragment.HomeworkEntry, Boolean) -> Unit,
    private val onHomeworkDeleted: (SlideshowFragment.HomeworkEntry) -> Unit,
    private val onHomeworkEdited: (SlideshowFragment.HomeworkEntry) -> Unit,
    private val onHomeworkViewed: (SlideshowFragment.HomeworkEntry) -> Unit
) : RecyclerView.Adapter<HomeworkAdapter.ViewHolder>() {

    private var filteredHomeworkList: List<SlideshowFragment.HomeworkEntry> = homeworkList
    private var currentFilter: String = ""

    companion object {
        private const val VIEW_TYPE_NORMAL = 0
        private const val VIEW_TYPE_OVERDUE = 1
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkBox: CheckBox = view.findViewById(R.id.checkBoxHomework)
        val checkboxArea: FrameLayout = view.findViewById(R.id.checkboxArea)
        val textHomeworkDetails: TextView = view.findViewById(R.id.textHomeworkDetails)
        val btnEdit: ImageButton = view.findViewById(R.id.btnEdit)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
    }

    override fun getItemViewType(position: Int): Int {
        val homework = filteredHomeworkList[position]
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
        val homework = filteredHomeworkList[position]

        holder.checkBox.setOnCheckedChangeListener(null)
        holder.checkboxArea.setOnClickListener(null)
        holder.textHomeworkDetails.setOnClickListener(null)

        val checkboxText = "${homework.subject} | ${homework.getDueDateString()}"
        val spannableString = SpannableString(checkboxText)
        spannableString.setSpan(
            StyleSpan(Typeface.BOLD),
            0,
            homework.subject.length,
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        holder.textHomeworkDetails.text = spannableString

        val isOverdueAndCompleted = homework.isCompleted && homework.isOverdueForUI()

        if (getItemViewType(position) == VIEW_TYPE_OVERDUE && !homework.isCompleted) {
            // normal overdue (not completed)
            holder.checkBox.isEnabled = false
            holder.checkBox.alpha = 0.6f
            holder.checkBox.isChecked = false

            holder.itemView.setBackgroundColor(
                ContextCompat.getColor(holder.itemView.context, android.R.color.darker_gray)
            )

            holder.textHomeworkDetails.setOnClickListener {
                onHomeworkViewed(homework)
            }

        } else if (isOverdueAndCompleted) {
            // completed but overdue
            holder.checkBox.isEnabled = false
            holder.checkBox.alpha = 1.0f
            holder.checkBox.isChecked = true

            holder.itemView.setBackgroundColor(
                ContextCompat.getColor(holder.itemView.context, android.R.color.holo_green_light)
            )

            holder.textHomeworkDetails.setOnClickListener {
                onHomeworkViewed(homework)
            }

        } else {
            // normal homework (not overdue or completed but not overdue🗣️)
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

            // checkbox area (left side) for checking/unchecking
            holder.checkboxArea.setOnClickListener {
                val newState = !holder.checkBox.isChecked
                holder.checkBox.isChecked = newState
                onHomeworkToggled(homework, newState)
            }

            // text area (middle area) for viewing details
            holder.textHomeworkDetails.setOnClickListener {
                onHomeworkViewed(homework)
            }
        }

        holder.btnEdit.setOnClickListener {
            onHomeworkEdited(homework)
        }

        holder.btnDelete.setOnClickListener {
            onHomeworkDeleted(homework)
        }
    }

    fun filter(query: String) {
        currentFilter = query
        filteredHomeworkList = if (query.isEmpty()) {
            homeworkList
        } else {
            homeworkList.filter { homework ->
                homework.subject.contains(query, ignoreCase = true)
            }
        }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = filteredHomeworkList.size
}