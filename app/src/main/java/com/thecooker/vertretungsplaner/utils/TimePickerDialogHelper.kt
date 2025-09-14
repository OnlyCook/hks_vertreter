package com.thecooker.vertretungsplaner.utils

import android.app.TimePickerDialog
import android.content.Context
import android.widget.TimePicker
import com.thecooker.vertretungsplaner.R

object TimePickerDialogHelper {

    fun showTimePicker(
        context: Context,
        currentTime: String,
        onTimeSelected: (String) -> Unit
    ) {
        val (hour, minute) = parseTime(currentTime)

        val timePickerDialog = TimePickerDialog(
            context,
            { _: TimePicker, selectedHour: Int, selectedMinute: Int ->
                val formattedTime = String.format("%02d:%02d", selectedHour, selectedMinute)
                onTimeSelected(formattedTime)
            },
            hour,
            minute,
            true
        )

        timePickerDialog.setTitle(context.getString(R.string.time_select_update_time))
        timePickerDialog.show()
    }

    fun showIntervalPicker(
        context: Context,
        currentInterval: Int,
        onIntervalSelected: (Int) -> Unit
    ) {
        val intervals = arrayOf(5, 10, 15, 30, 60, 120, 180) // minutes
        val intervalTexts = intervals.map {
            when {
                it < 60 -> context.getString(R.string.time_minutes_number, it)
                it == 60 -> context.getString(R.string.time_hour_single)
                else -> context.getString(R.string.time_hour_multiple, it / 60)
            }
        }.toTypedArray()

        val currentIndex = intervals.indexOf(currentInterval).let {
            if (it >= 0) it else 2 // fallback 15 mins
        }

        android.app.AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.time_check_interval_select))
            .setSingleChoiceItems(intervalTexts, currentIndex) { dialog, which ->
                onIntervalSelected(intervals[which])
                dialog.dismiss()
            }
            .setNegativeButton(context.getString(R.string.cancel), null)
            .show()
    }

    private fun parseTime(timeString: String): Pair<Int, Int> {
        return try {
            val parts = timeString.split(":")
            Pair(parts[0].toInt(), parts[1].toInt())
        } catch (_: Exception) {
            Pair(6, 0) // default to 6:00
        }
    }
}