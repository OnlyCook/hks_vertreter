package com.thecooker.vertretungsplaner.ui.moodle

import android.os.Handler
import android.os.Looper
import com.thecooker.vertretungsplaner.L
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class CourseDownloadQueue private constructor() {

    private val queue = ConcurrentLinkedQueue<DownloadQueueEntry>()
    private val entryMap = ConcurrentHashMap<String, DownloadQueueEntry>()
    private val listeners = mutableListOf<QueueListener>()

    data class DownloadQueueEntry(
        val id: String = java.util.UUID.randomUUID().toString(),
        val courseName: String,
        val entryName: String,
        val url: String,
        val fileName: String,
        val linkType: String = "",
        val iconUrl: String = "",
        val sectionName: String = "",
        val isFolder: Boolean = false,
        val folderFiles: MutableList<FolderFile> = mutableListOf(),
        var progress: Int = 0,
        var status: String = "pending",
        var errorMessage: String = ""
    )

    data class FolderFile(
        val name: String,
        val url: String,
        val iconUrl: String = "",
        var downloaded: Boolean = false
    )

    interface QueueListener {
        fun onQueueChanged()
        fun onEntryStatusChanged(entry: DownloadQueueEntry)
    }

    fun addListener(listener: QueueListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: QueueListener) {
        listeners.remove(listener)
    }

    private fun notifyQueueChanged() {
        Handler(Looper.getMainLooper()).post {
            listeners.forEach { it.onQueueChanged() }
        }
    }

    private fun notifyEntryChanged(entry: DownloadQueueEntry) {
        Handler(Looper.getMainLooper()).post {
            listeners.forEach { it.onEntryStatusChanged(entry) }
        }
    }

    fun addToQueue(
        courseName: String,
        entryName: String,
        url: String,
        fileName: String,
        linkType: String = "",
        iconUrl: String = "",
        sectionName: String = ""
    ): DownloadQueueEntry {
        val entry = DownloadQueueEntry(
            courseName = courseName,
            entryName = entryName,
            url = url,
            fileName = fileName,
            linkType = linkType,
            iconUrl = iconUrl,
            sectionName = sectionName
        )

        queue.add(entry)
        entryMap[entry.id] = entry

        L.d("CourseDownloadQueue", "Added to queue: $entryName (Total: ${queue.size})")
        notifyQueueChanged()

        return entry
    }

    fun getAllEntries(): List<DownloadQueueEntry> = queue.toList()

    fun getPendingEntries(): List<DownloadQueueEntry> =
        queue.filter { it.status == "pending" }

    fun getDownloadingEntries(): List<DownloadQueueEntry> =
        queue.filter { it.status == "downloading" }

    fun getCompletedEntries(): List<DownloadQueueEntry> =
        queue.filter { it.status == "completed" }

    fun getFailedEntries(): List<DownloadQueueEntry> =
        queue.filter { it.status == "failed" }

    fun removeEntry(entry: DownloadQueueEntry) {
        queue.remove(entry)
        entryMap.remove(entry.id)
        L.d("CourseDownloadQueue", "Removed from queue: ${entry.entryName}")
        notifyQueueChanged()
    }

    fun updateEntry(entry: DownloadQueueEntry) {
        entryMap[entry.id]?.let { existing ->
            existing.progress = entry.progress
            existing.status = entry.status
            existing.errorMessage = entry.errorMessage
            notifyEntryChanged(existing)
        }
    }

    fun clearCompleted() {
        val completed = queue.filter { it.status == "completed" }
        completed.forEach {
            queue.remove(it)
            entryMap.remove(it.id)
        }
        L.d("CourseDownloadQueue", "Cleared ${completed.size} completed entries")
        notifyQueueChanged()
    }

    fun clearFailed() {
        val failed = queue.filter { it.status == "failed" }
        failed.forEach {
            queue.remove(it)
            entryMap.remove(it.id)
        }
        L.d("CourseDownloadQueue", "Cleared ${failed.size} failed entries")
        notifyQueueChanged()
    }

    fun retryFailed() {
        queue.filter { it.status == "failed" }.forEach { entry ->
            entry.status = "pending"
            entry.progress = 0
            entry.errorMessage = ""
            notifyEntryChanged(entry)
        }
        notifyQueueChanged()
    }

    fun getQueueSize(): Int = queue.size

    fun getPendingSize(): Int = queue.count { it.status == "pending" || it.status == "downloading" }

    fun clear() {
        queue.clear()
        entryMap.clear()
        L.d("CourseDownloadQueue", "Queue cleared")
        notifyQueueChanged()
    }

    companion object {
        @Volatile
        private var instance: CourseDownloadQueue? = null

        fun getInstance(): CourseDownloadQueue {
            return instance ?: synchronized(this) {
                instance ?: CourseDownloadQueue().also { instance = it }
            }
        }
    }
}