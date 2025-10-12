package com.thecooker.vertretungsplaner.ui.moodle

data class DownloadedCourse(
    val name: String,
    val entries: List<DownloadedEntry>,
    val path: String,
    var isSelected: Boolean = false,
    var isExpanded: Boolean = false
)

data class DownloadedEntry(
    val name: String,
    val fileName: String,
    val path: String,
    val size: Long,
    val dateModified: Long,
    val mimeType: String,
    var isSelected: Boolean = false
)