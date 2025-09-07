package com.thecooker.vertretungsplaner.data

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

object SubstituteRepository {
    private const val TAG = "SubstituteRepository"

    data class SubstituteEntry(
        val date: String,
        val stunde: Int,
        val stundeBis: Int?,
        val fach: String,
        val raum: String,
        val art: String,
        val originalText: String
    )

    fun getSubstituteEntries(context: Context): List<SubstituteEntry> {
        val klasse = getSelectedClass(context)
        if (klasse == "Nicht ausgewählt") {
            return emptyList()
        }

        return loadCachedSubstitutePlan(context, klasse)
    }

    fun getSubstituteEntriesByDate(context: Context, targetDate: String): List<SubstituteEntry> {
        return getSubstituteEntries(context).filter { it.date == targetDate }
    }

    private fun getSelectedClass(context: Context): String {
        val sharedPreferences = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        return sharedPreferences.getString("selected_klasse", "Nicht ausgewählt") ?: "Nicht ausgewählt"
    }

    private fun loadCachedSubstitutePlan(context: Context, klasse: String): List<SubstituteEntry> {
        val entries = mutableListOf<SubstituteEntry>()

        try {
            val cacheFile = File(context.cacheDir, "substitute_plan_$klasse.json")

            if (cacheFile.exists()) {
                val cachedData = cacheFile.readText()
                val jsonData = JSONObject(cachedData)
                val dates = jsonData.optJSONArray("dates")

                if (dates != null) {
                    for (i in 0 until dates.length()) {
                        val dateObj = dates.getJSONObject(i)
                        val dateString = dateObj.getString("date")
                        val dateEntries = dateObj.getJSONArray("entries")

                        for (j in 0 until dateEntries.length()) {
                            val entry = dateEntries.getJSONObject(j)
                            entries.add(
                                SubstituteEntry(
                                    date = dateString,
                                    stunde = entry.getInt("stunde"),
                                    stundeBis = entry.optInt("stundebis", -1).takeIf { it != -1 },
                                    fach = entry.optString("fach", ""),
                                    raum = entry.optString("raum", ""),
                                    art = entry.optString("text", ""),
                                    originalText = entry.optString("text", "")
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading cached substitute plan", e)
        }

        return entries
    }
}