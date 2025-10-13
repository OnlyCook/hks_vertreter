package com.thecooker.vertretungsplaner.ui.moodle

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.thecooker.vertretungsplaner.R

class DownloadQueueAdapter(
    private val entries: MutableList<CourseDownloadQueue.DownloadQueueEntry>
) : RecyclerView.Adapter<DownloadQueueAdapter.QueueViewHolder>() {

    class QueueViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val courseName: TextView = itemView.findViewById(R.id.tvQueueCourseName)
        val entryName: TextView = itemView.findViewById(R.id.tvQueueEntryName)
        val statusIcon: ImageView = itemView.findViewById(R.id.ivQueueStatus)
        val progressBar: ProgressBar = itemView.findViewById(R.id.progressBarQueue)
        val progressText: TextView = itemView.findViewById(R.id.tvQueueProgress)
        val statusText: TextView = itemView.findViewById(R.id.tvQueueStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download_queue, parent, false)
        return QueueViewHolder(view)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        val entry = entries[position]

        holder.courseName.text = entry.courseName
        holder.entryName.text = entry.entryName
        holder.progressBar.progress = entry.progress
        "${entry.progress}%".also { holder.progressText.text = it }

        when (entry.status) {
            "pending" -> {
                holder.statusIcon.setImageResource(R.drawable.ic_pending)
                holder.statusText.text = holder.itemView.context.getString(R.string.moodle_status_pending)
                holder.progressBar.isIndeterminate = false
            }
            "downloading" -> {
                holder.statusIcon.setImageResource(R.drawable.ic_download)
                holder.statusText.text = holder.itemView.context.getString(R.string.moodle_status_downloading)
                holder.progressBar.isIndeterminate = entry.progress == 0
            }
            "completed" -> {
                holder.statusIcon.setImageResource(R.drawable.ic_check)
                holder.statusText.text = holder.itemView.context.getString(R.string.moodle_status_completed)
                holder.progressBar.isIndeterminate = false
                holder.progressBar.progress = 100
            }
            "failed" -> {
                holder.statusIcon.setImageResource(R.drawable.ic_error)
                holder.statusText.text = holder.itemView.context.getString(R.string.moodle_status_failed)
                if (entry.errorMessage.isNotEmpty()) {
                    "${holder.statusText.text}: ${entry.errorMessage}".also { holder.statusText.text = it }
                }
                holder.progressBar.isIndeterminate = false
            }
        }
    }

    override fun getItemCount() = entries.size

    fun updateEntries(newEntries: List<CourseDownloadQueue.DownloadQueueEntry>) {
        entries.clear()
        entries.addAll(newEntries)
        notifyDataSetChanged()
    }
}