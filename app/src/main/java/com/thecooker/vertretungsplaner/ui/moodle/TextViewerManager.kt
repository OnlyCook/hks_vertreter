package com.thecooker.vertretungsplaner.ui.moodle

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ForegroundColorSpan
import androidx.preference.PreferenceManager
import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.parser.LocationTextExtractionStrategy
import com.itextpdf.text.pdf.parser.PdfTextExtractor
import com.thecooker.vertretungsplaner.L
import java.io.File
import java.io.FileInputStream

class TextViewerManager(private val context: Context) {

    data class TextPage(
        val pageNumber: Int,
        val lines: List<TextLine>,
        val rawText: String
    )

    data class TextLine(
        val lineNumber: Int,
        val text: SpannableStringBuilder,
        val isWrapped: Boolean = false,
        val originalLineEndsWithHyphen: Boolean = false
    )

    private var pages = listOf<TextPage>()
    private var sharedPrefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private var isDarkMode = false

    fun parsePdfToText(pdfFile: File, darkMode: Boolean): Boolean {
        isDarkMode = darkMode
        return try {
            val fileInputStream = FileInputStream(pdfFile)
            val reader = PdfReader(fileInputStream)
            val pagesList = mutableListOf<TextPage>()

            for (pageNum in 1..reader.numberOfPages) {
                val strategy = LocationTextExtractionStrategy()
                val pageText = PdfTextExtractor.getTextFromPage(reader, pageNum, strategy)

                val lines = parsePageLines(pageText, pageNum)
                pagesList.add(TextPage(pageNum, lines, pageText))
            }

            reader.close()
            fileInputStream.close()
            pages = pagesList
            true
        } catch (e: Exception) {
            L.e("TextViewerManager", "Error parsing PDF to text", e)
            false
        }
    }

    private fun parsePageLines(pageText: String, pageNum: Int): List<TextLine> {
        val lines = mutableListOf<TextLine>()
        val rawLines = pageText.split("\n")
        var lineNumber = 1

        for (i in rawLines.indices) {
            val rawLine = rawLines[i]

            if (rawLine.isBlank()) {
                lines.add(TextLine(lineNumber, SpannableStringBuilder(" ")))
                lineNumber++
                continue
            }

            val endsWithHyphen = rawLine.trimEnd().endsWith("-")
            val processedLine = processLineContent(rawLine)
            lines.add(TextLine(lineNumber, processedLine, originalLineEndsWithHyphen = endsWithHyphen))
            lineNumber++
        }

        return lines
    }

    private fun processLineContent(text: String): SpannableStringBuilder {
        val builder = SpannableStringBuilder()

        var processedText = text
            .replace("\ufb01", "fi") // 'fi' ligature
            .replace("\ufb02", "fl") // 'fl' ligature
            .replace("\u00ad", "") // soft hyphens
            .replace("�", "f") // question marks that should be 'f'

        if (processedText.contains("<img") || processedText.contains("[image]") || processedText.contains("□")) {
            processedText = processedText.replace(Regex("<img[^>]*>"), "[IMAGE]")
            processedText = processedText.replace("[image]", "[IMAGE]")
            processedText = processedText.replace("□", "[IMAGE]")
        }

        if (processedText.contains("|") && processedText.count { it == '|' } >= 2) {
            processedText = "[TABLE]"
        }

        builder.append(processedText)

        applyFormattingHeuristics(builder)

        return builder
    }

    private fun applyFormattingHeuristics(builder: SpannableStringBuilder) {
        val text = builder.toString()

        if (text.length < 50 && text.uppercase() == text && text.any { it.isLetter() }) {
            builder.setSpan(
                StyleSpan(Typeface.BOLD),
                0,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            builder.setSpan(
                RelativeSizeSpan(1.3f),
                0,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        val listPattern = Regex("^\\s*[•\\-*]\\s+|^\\s*\\d+[.):]\\s+")
        if (listPattern.containsMatchIn(text)) {
            builder.setSpan(
                StyleSpan(Typeface.BOLD),
                0,
                listPattern.find(text)?.value?.length ?: 0,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    fun getFormattedText(maxLineWidth: Int): SpannableStringBuilder {
        val fullText = SpannableStringBuilder()
        val baseFontSize = sharedPrefs.getInt("text_viewer_font_size", 14)
        val disableFormatting = sharedPrefs.getBoolean("text_viewer_disable_formatting", false)

        for ((pageIndex, page) in pages.withIndex()) {
            if (pageIndex > 0) {
                fullText.append("\n\n━━━ Page ${page.pageNumber} ━━━\n\n")
            }

            var i = 0
            while (i < page.lines.size) {
                val line = page.lines[i]

                if (line.originalLineEndsWithHyphen && i + 1 < page.lines.size) {
                    val currentText = line.text.toString().trimEnd('-')
                    val nextLine = page.lines[i + 1]
                    val combinedText = SpannableStringBuilder()
                    combinedText.append(currentText)
                    combinedText.append(nextLine.text)

                    fullText.append(combinedText)
                    fullText.append("\n")
                    i += 2
                } else {
                    fullText.append(line.text)
                    fullText.append("\n")
                    i++
                }
            }
        }

        if (!disableFormatting) {
            applyUserPreferences(fullText)
        }

        return fullText
    }

    private fun applyUserPreferences(text: SpannableStringBuilder) {
        val fontColor = getFontColor()

        text.setSpan(
            ForegroundColorSpan(fontColor),
            0,
            text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    fun getTotalLines(): Int {
        var total = 0
        for (page in pages) {
            var i = 0
            while (i < page.lines.size) {
                total++
                // Skip merged lines
                if (page.lines[i].originalLineEndsWithHyphen && i + 1 < page.lines.size) {
                    i += 2
                } else {
                    i++
                }
            }
        }
        return total
    }

    fun searchText(query: String): List<Int> {
        if (query.isBlank()) return emptyList()

        val results = mutableListOf<Int>()
        val fullText = getFormattedText(0).toString()
        var index = 0

        while (index < fullText.length) {
            index = fullText.indexOf(query, index, ignoreCase = true)
            if (index == -1) break

            results.add(index)
            index += query.length
        }

        return results
    }

    fun getFontSize(): Int {
        return sharedPrefs.getInt("text_viewer_font_size", 14)
    }

    fun getFontColor(): Int {
        val key = if (isDarkMode) "text_viewer_font_color_dark" else "text_viewer_font_color_light"
        val default = if (isDarkMode) android.graphics.Color.LTGRAY else android.graphics.Color.BLACK
        return sharedPrefs.getInt(key, default)
    }

    fun getBackgroundColor(): Int {
        val key = if (isDarkMode) "text_viewer_bg_color_dark" else "text_viewer_bg_color_light"
        val default = if (isDarkMode) android.graphics.Color.rgb(30, 30, 30) else android.graphics.Color.WHITE
        return sharedPrefs.getInt(key, default)
    }

    fun getFontFamily(): Typeface {
        val family = sharedPrefs.getString("text_viewer_font_family", "monospace") ?: "monospace"
        return when (family) {
            "serif" -> Typeface.SERIF
            "sans-serif" -> Typeface.SANS_SERIF
            else -> Typeface.MONOSPACE
        }
    }

    fun setDarkMode(darkMode: Boolean) {
        isDarkMode = darkMode
    }
}