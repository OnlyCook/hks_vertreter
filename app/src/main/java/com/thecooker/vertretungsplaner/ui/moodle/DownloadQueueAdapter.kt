package com.thecooker.vertretungsplaner.ui.moodle

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.thecooker.vertretungsplaner.R

class DownloadQueueAdapter(
    private val entries: MutableList<CourseDownloadQueue.DownloadQueueEntry>,
    private val onCancelClick: (CourseDownloadQueue.DownloadQueueEntry) -> Unit
) : RecyclerView.Adapter<DownloadQueueAdapter.QueueViewHolder>() {

    class QueueViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val courseName: TextView = itemView.findViewById(R.id.tvQueueCourseName)
        val entryName: TextView = itemView.findViewById(R.id.tvQueueEntryName)
        val statusIcon: ImageView = itemView.findViewById(R.id.ivQueueStatus)
        val progressBar: ProgressBar = itemView.findViewById(R.id.progressBarQueue)
        val progressText: TextView = itemView.findViewById(R.id.tvQueueProgress)
        val statusText: TextView = itemView.findViewById(R.id.tvQueueStatus)
        val downloadSpeed: TextView = itemView.findViewById(R.id.tvDownloadSpeed)
        val downloadSize: TextView = itemView.findViewById(R.id.tvDownloadSize)
        val btnCancel: ImageButton = itemView.findViewById(R.id.btnCancelDownload)
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

        if (entry.status == "downloading" && entry.downloadSpeed > 0) {
            holder.downloadSpeed.visibility = View.VISIBLE
            holder.downloadSpeed.text = formatSpeed(entry.downloadSpeed)
        } else {
            holder.downloadSpeed.visibility = View.GONE
        }

        if (entry.totalBytes > 0) {
            holder.downloadSize.visibility = View.VISIBLE
            val downloadedMB = entry.downloadedBytes / (1024.0 * 1024.0)
            val totalMB = entry.totalBytes / (1024.0 * 1024.0)
            "%.2f MB / %.2f MB".format(downloadedMB, totalMB).also { holder.downloadSize.text = it }
        } else {
            holder.downloadSize.visibility = View.GONE
        }

        when (entry.status) {
            "pending" -> {
                holder.statusIcon.setImageResource(R.drawable.ic_pending)
                holder.statusText.text = holder.itemView.context.getString(R.string.moodle_status_pending)
                holder.progressBar.isIndeterminate = false
                holder.btnCancel.visibility = View.VISIBLE
            }
            "downloading" -> {
                holder.statusIcon.setImageResource(R.drawable.ic_download)
                holder.statusText.text = holder.itemView.context.getString(R.string.moodle_status_downloading)
                holder.progressBar.isIndeterminate = entry.progress == 0
                holder.btnCancel.visibility = View.VISIBLE
            }
            "completed" -> {
                holder.statusIcon.setImageResource(R.drawable.ic_check)
                holder.statusText.text = holder.itemView.context.getString(R.string.moodle_status_completed)
                holder.progressBar.isIndeterminate = false
                holder.progressBar.progress = 100
                holder.btnCancel.visibility = View.GONE
            }
            "failed" -> {
                holder.statusIcon.setImageResource(R.drawable.ic_error)
                holder.statusText.text = holder.itemView.context.getString(R.string.moodle_status_failed)
                if (entry.errorMessage.isNotEmpty()) {
                    "${holder.statusText.text}: ${entry.errorMessage}".also { holder.statusText.text = it }
                }
                holder.progressBar.isIndeterminate = false
                holder.btnCancel.visibility = View.GONE
            }
            "cancelled" -> {
                holder.statusIcon.setImageResource(R.drawable.ic_error)
                holder.statusText.text = holder.itemView.context.getString(R.string.moodle_status_cancelled)
                holder.progressBar.isIndeterminate = false
                holder.btnCancel.visibility = View.GONE
            }
        }

        holder.btnCancel.setOnClickListener {
            onCancelClick(entry)
        }
    }

    override fun getItemCount() = entries.size

    fun updateEntries(newEntries: List<CourseDownloadQueue.DownloadQueueEntry>) {
        entries.clear()
        entries.addAll(newEntries)
        notifyDataSetChanged()
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        return when {
            bytesPerSecond >= 1024 * 1024 -> "%.2f MB/s".format(bytesPerSecond / (1024.0 * 1024.0))
            bytesPerSecond >= 1024 -> "%.2f KB/s".format(bytesPerSecond / 1024.0)
            else -> "$bytesPerSecond B/s"
        }
    }
}