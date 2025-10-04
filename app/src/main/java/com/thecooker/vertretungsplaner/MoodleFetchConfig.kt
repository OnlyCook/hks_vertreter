package com.thecooker.vertretungsplaner

data class MoodleFetchConfig(
    val fetchType: FetchType,
    val preserveNotes: Boolean = false,
    val programCourseName: String? = null,
    val timetableEntryName: String? = null
)

enum class FetchType {
    EXAM_SCHEDULE,
    TIMETABLE
}