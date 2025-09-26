package com.thecooker.vertretungsplaner.data

import android.content.Context
import com.thecooker.vertretungsplaner.L
import com.thecooker.vertretungsplaner.R
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
        if (klasse == context.getString(R.string.sub_repo_not_selected)) { // be cautious about this
            return emptyList()
        }

        return loadCachedSubstitutePlan(context, klasse)
    }

    fun getSubstituteEntriesByDate(context: Context, targetDate: String): List<SubstituteEntry> {
        return getSubstituteEntries(context).filter { it.date == targetDate }
    }

    private fun getSelectedClass(context: Context): String {
        val sharedPreferences = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        return sharedPreferences.getString("selected_klasse", context.getString(R.string.sub_repo_not_selected)) ?: context.getString(R.string.sub_repo_not_selected)
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
                            val stunde = entry.getInt("stunde")
                            val stundeBisRaw = entry.optInt("stundebis", -1)

                            val stundeBis = if (stundeBisRaw == -1 || stundeBisRaw == 0) {
                                null
                            } else {
                                stundeBisRaw
                            }

                            val substituteEntry = SubstituteEntry(
                                date = dateString,
                                stunde = stunde,
                                stundeBis = stundeBis,
                                fach = entry.optString("fach", ""),
                                raum = entry.optString("raum", ""),
                                art = entry.optString("text", ""),
                                originalText = entry.optString("text", "")
                            )

                            entries.add(substituteEntry)

                            L.d(TAG, "Loaded substitute entry: date=$dateString, subject=${substituteEntry.fach}, lesson=${substituteEntry.stunde}-${substituteEntry.stundeBis}, type='${substituteEntry.art}'")
                        }
                    }

                    L.d(TAG, "Total substitute entries loaded: ${entries.size}")
                }
            } else {
                L.w(TAG, "Cache file does not exist: substitute_plan_$klasse.json")
            }
        } catch (e: Exception) {
            L.e(TAG, "Error loading cached substitute plan", e)
        }

        return entries
    }

    fun debugSubstituteEntries(context: Context, targetDate: String) {
        val entries = getSubstituteEntriesByDate(context, targetDate)
        L.d(TAG, "=== SUBSTITUTE REPOSITORY DEBUG for date $targetDate ===")
        L.d(TAG, "Found ${entries.size} entries:")
        entries.forEach { entry ->
            L.d(TAG, "  Subject: ${entry.fach}, Lesson: ${entry.stunde}-${entry.stundeBis}, Type: '${entry.art}', Room: ${entry.raum}")
        }
        L.d(TAG, "=== END SUBSTITUTE REPOSITORY DEBUG ===")
    }
}