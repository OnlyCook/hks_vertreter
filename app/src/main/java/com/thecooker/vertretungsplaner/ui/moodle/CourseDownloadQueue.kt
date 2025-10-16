package com.thecooker.vertretungsplaner.ui.moodle

import android.os.Handler
import android.os.Looper
import com.thecooker.vertretungsplaner.L
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class CourseDownloadQueue private constructor() {

    private val queue = ConcurrentLinkedQueue<DownloadQueueEntry>()
    private val entryMap = ConcurrentHashMap<String, DownloadQueueEntry>()
    private val listeners = mutableListOf<QueueListener>()
    private val activeDownloads = ConcurrentHashMap<String, AtomicBoolean>() // Track active downloads

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
        var errorMessage: String = "",
        var actualFileName: String? = null,
        var downloadedBytes: Long = 0,
        var totalBytes: Long = 0,
        var downloadSpeed: Long = 0,
        var startTime: Long = 0,
        @Volatile var isCancelled: Boolean = false
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
        queue.filter { it.status == "pending" && !it.isCancelled }

    fun getDownloadingEntries(): List<DownloadQueueEntry> =
        queue.filter { it.status == "downloading" && !it.isCancelled && activeDownloads.containsKey(it.id) }

    fun getCompletedEntries(): List<DownloadQueueEntry> =
        queue.filter { it.status == "completed" }

    fun getFailedEntries(): List<DownloadQueueEntry> =
        queue.filter { it.status == "failed" }

    fun removeEntry(entry: DownloadQueueEntry) {
        queue.remove(entry)
        entryMap.remove(entry.id)
        activeDownloads.remove(entry.id)
        L.d("CourseDownloadQueue", "Removed from queue: ${entry.entryName}")
        notifyQueueChanged()
    }

    fun updateEntry(entry: DownloadQueueEntry) {
        entryMap[entry.id]?.let { existing ->
            existing.progress = entry.progress
            existing.status = entry.status
            existing.errorMessage = entry.errorMessage
            existing.downloadedBytes = entry.downloadedBytes
            existing.totalBytes = entry.totalBytes
            existing.downloadSpeed = entry.downloadSpeed
            existing.isCancelled = entry.isCancelled
            notifyEntryChanged(existing)
        }
    }

    fun markDownloadStarted(entry: DownloadQueueEntry) {
        activeDownloads[entry.id] = AtomicBoolean(true)
        entry.status = "downloading"
        updateEntry(entry)
        L.d("CourseDownloadQueue", "Download started: ${entry.entryName}")
    }

    fun markDownloadFinished(entry: DownloadQueueEntry) {
        activeDownloads.remove(entry.id)
        updateEntry(entry)
        L.d("CourseDownloadQueue", "Download finished: ${entry.entryName} - Status: ${entry.status}")
    }

    fun isDownloading(entry: DownloadQueueEntry): Boolean {
        return activeDownloads.containsKey(entry.id)
    }

    fun cancelEntry(entry: DownloadQueueEntry) {
        L.d("CourseDownloadQueue", "Cancelling entry: ${entry.entryName}")
        entry.isCancelled = true

        if (entry.status == "downloading") {
            activeDownloads[entry.id]?.set(false)
        } else {
            entry.status = "cancelled"
            entry.errorMessage = "Cancelled by user"
        }

        updateEntry(entry)
        notifyQueueChanged()
    }

    fun cancelAll() {
        L.d("CourseDownloadQueue", "Cancelling all downloads")
        queue.filter { it.status == "pending" || it.status == "downloading" }.forEach { entry ->
            entry.isCancelled = true

            if (entry.status == "downloading") {
                activeDownloads[entry.id]?.set(false)
            } else {
                entry.status = "cancelled"
                entry.errorMessage = "Cancelled by user"
            }

            notifyEntryChanged(entry)
        }
        notifyQueueChanged()
    }

    fun clearCompleted() {
        val completed = queue.filter { it.status == "completed" }
        completed.forEach {
            queue.remove(it)
            entryMap.remove(it.id)
            activeDownloads.remove(it.id)
        }
        L.d("CourseDownloadQueue", "Cleared ${completed.size} completed entries")
        notifyQueueChanged()
    }

    fun clearFailed() {
        val failed = queue.filter { it.status == "failed" }
        failed.forEach {
            queue.remove(it)
            entryMap.remove(it.id)
            activeDownloads.remove(it.id)
        }
        L.d("CourseDownloadQueue", "Cleared ${failed.size} failed entries")
        notifyQueueChanged()
    }

    fun retryFailed() {
        queue.filter { it.status == "failed" }.forEach { entry ->
            entry.status = "pending"
            entry.progress = 0
            entry.errorMessage = ""
            entry.isCancelled = false
            entry.downloadedBytes = 0
            entry.totalBytes = 0
            entry.downloadSpeed = 0
            notifyEntryChanged(entry)
        }
        notifyQueueChanged()
    }

    fun getQueueSize(): Int = queue.size

    fun getPendingSize(): Int = queue.count {
        (it.status == "pending" || it.status == "downloading") && !it.isCancelled
    }

    fun getQueueStats(): QueueStats {
        val activeEntries = queue.filter { !it.isCancelled }
        val totalBytes = activeEntries.sumOf { it.totalBytes }
        val downloadedBytes = activeEntries.sumOf { it.downloadedBytes }
        val totalSpeed = activeEntries.filter { it.status == "downloading" && activeDownloads.containsKey(it.id) }
            .sumOf { it.downloadSpeed }

        val remainingBytes = totalBytes - downloadedBytes
        val estimatedSeconds = if (totalSpeed > 0) remainingBytes / totalSpeed else 0

        return QueueStats(
            totalEntries = activeEntries.size,
            pendingEntries = activeEntries.count { it.status == "pending" },
            downloadingEntries = activeEntries.count { it.status == "downloading" && activeDownloads.containsKey(it.id) },
            completedEntries = activeEntries.count { it.status == "completed" },
            failedEntries = activeEntries.count { it.status == "failed" },
            totalBytes = totalBytes,
            downloadedBytes = downloadedBytes,
            totalSpeed = totalSpeed,
            estimatedTimeRemaining = estimatedSeconds
        )
    }

    data class QueueStats(
        val totalEntries: Int,
        val pendingEntries: Int,
        val downloadingEntries: Int,
        val completedEntries: Int,
        val failedEntries: Int,
        val totalBytes: Long,
        val downloadedBytes: Long,
        val totalSpeed: Long,
        val estimatedTimeRemaining: Long
    )

    fun clear() {
        queue.clear()
        entryMap.clear()
        activeDownloads.clear()
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