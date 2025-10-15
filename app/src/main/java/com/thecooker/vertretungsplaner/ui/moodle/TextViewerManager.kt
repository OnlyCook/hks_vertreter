package com.thecooker.vertretungsplaner.ui.moodle

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.*
import androidx.preference.PreferenceManager
import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.parser.*
import com.thecooker.vertretungsplaner.L
import java.io.File
import java.io.FileInputStream
import kotlin.math.abs

class TextViewerManager(private val context: Context, sharedPreferences: SharedPreferences) {

    data class TextElement(
        val text: String,
        val x: Float,
        val y: Float,
        val width: Float,
        val fontSize: Float,
        val isBold: Boolean = false,
        val isItalic: Boolean = false,
        val pageNumber: Int = 1
    )

    data class ParsedPage(
        val pageNumber: Int,
        val elements: List<TextElement>,
        val pageHeight: Float
    )

    private var pages = listOf<ParsedPage>()
    private var sharedPrefs: SharedPreferences = sharedPreferences
    private var isDarkMode = false
    private var averageFontSize = 12f

    fun parsePdfToText(pdfFile: File, darkMode: Boolean): Boolean {
        isDarkMode = darkMode
        return try {
            val fileInputStream = FileInputStream(pdfFile)
            val reader = PdfReader(fileInputStream)
            val pagesList = mutableListOf<ParsedPage>()

            for (pageNum in 1..reader.numberOfPages) {
                val elements = extractPageElements(reader, pageNum)
                val pageSize = reader.getPageSize(pageNum)

                if (pageNum == 1 && elements.isNotEmpty()) {
                    // Calculate average font size from most common size
                    averageFontSize = elements
                        .map { it.fontSize }
                        .groupingBy { it }
                        .eachCount()
                        .maxByOrNull { it.value }?.key ?: 12f
                }

                pagesList.add(ParsedPage(pageNum, elements, pageSize.height))
            }

            reader.close()
            fileInputStream.close()
            pages = pagesList
            true
        } catch (e: Exception) {
            L.e("TextViewerManager", "Error parsing PDF", e)
            false
        }
    }

    private fun extractPageElements(reader: PdfReader, pageNum: Int): List<TextElement> {
        val elements = mutableListOf<TextElement>()

        val strategy = object : SimpleTextExtractionStrategy() {
            val textElements = mutableListOf<TextElement>()

            override fun renderText(renderInfo: TextRenderInfo) {
                val text = renderInfo.text
                if (text.isNullOrBlank()) return

                val baseline = renderInfo.baseline
                val ascentLine = renderInfo.ascentLine

                val x = baseline.startPoint.get(Vector.I1)
                val y = baseline.startPoint.get(Vector.I2)
                val width = baseline.length

                val fontSize = try {
                    ascentLine.startPoint.get(Vector.I2) - baseline.startPoint.get(Vector.I2)
                } catch (_: Exception) {
                    12f
                }

                val font = renderInfo.font
                val fontName = font?.postscriptFontName?.lowercase() ?: ""

                val isBold = fontName.contains("bold") || fontName.contains("black") ||
                        fontName.contains("heavy") || fontName.contains("semibold")
                val isItalic = fontName.contains("italic") || fontName.contains("oblique")

                textElements.add(TextElement(
                    text = text,
                    x = x,
                    y = y,
                    width = width,
                    fontSize = fontSize,
                    isBold = isBold,
                    isItalic = isItalic,
                    pageNumber = pageNum
                ))
            }
        }

        PdfTextExtractor.getTextFromPage(reader, pageNum, strategy)
        elements.addAll(strategy.textElements)

        return elements
    }

    fun getFormattedText(displayWidth: Int): SpannableStringBuilder {
        val fullText = SpannableStringBuilder()
        val disableFormatting = getDisableFormatting()

        for ((index, page) in pages.withIndex()) {
            if (index > 0) {
                addPageDivider(fullText, page.pageNumber)
            }

            val lines = groupElementsIntoLines(page.elements)
            processLines(fullText, lines, disableFormatting)
        }

        return fullText
    }

    private fun groupElementsIntoLines(elements: List<TextElement>): List<List<TextElement>> {
        if (elements.isEmpty()) return emptyList()

        val sorted = elements.sortedWith(compareBy({ -it.y }, { it.x }))
        val lines = mutableListOf<List<TextElement>>()
        var currentLine = mutableListOf<TextElement>()
        var currentY = sorted.first().y
        val lineThreshold = 3f

        for (element in sorted) {
            if (abs(element.y - currentY) <= lineThreshold) {
                currentLine.add(element)
            } else {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine.sortedBy { it.x })
                }
                currentLine = mutableListOf(element)
                currentY = element.y
            }
        }

        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.sortedBy { it.x })
        }

        return lines
    }

    private fun processLines(text: SpannableStringBuilder, lines: List<List<TextElement>>, disableFormatting: Boolean) {
        for ((lineIndex, line) in lines.withIndex()) {
            // Detect line type
            val lineType = detectLineType(line)

            // Merge elements in line
            val mergedText = mergeLineElements(line)

            // Add content based on type
            when (lineType) {
                LineType.HEADING -> addHeading(text, mergedText, disableFormatting)
                LineType.LIST_ITEM -> addListItem(text, mergedText, disableFormatting)
                LineType.IMAGE -> addImagePlaceholder(text)
                LineType.TABLE_ROW -> addTableRow(text, line, disableFormatting)
                LineType.NORMAL -> addNormalText(text, mergedText, disableFormatting)
            }

            // Add newline if not last line
            if (lineIndex < lines.size - 1) {
                text.append("\n")
            }
        }
    }

    private enum class LineType {
        NORMAL, HEADING, LIST_ITEM, IMAGE, TABLE_ROW
    }

    private fun detectLineType(line: List<TextElement>): LineType {
        if (line.isEmpty()) return LineType.NORMAL

        val firstElement = line.first()
        val lineText = line.joinToString(" ") { it.text }.trim()

        // Check for image markers (if your PDF has them)
        if (lineText.contains("Image") && lineText.length < 20) {
            return LineType.IMAGE
        }

        // Check for headings (larger font)
        if (firstElement.fontSize > averageFontSize * 1.3f) {
            return LineType.HEADING
        }

        // Check for list items
        val listPattern = Regex("^\\s*([•\\-*○►]|\\d+[.)]|[a-z][.)]|[A-Z][.)])\\s+")
        if (listPattern.containsMatchIn(lineText)) {
            return LineType.LIST_ITEM
        }

        // Check for table rows (multiple elements with spacing)
        if (line.size >= 3 && hasTableStructure(line)) {
            return LineType.TABLE_ROW
        }

        return LineType.NORMAL
    }

    private fun hasTableStructure(line: List<TextElement>): Boolean {
        if (line.size < 3) return false

        // Check if elements are evenly spaced (table columns)
        val gaps = mutableListOf<Float>()
        for (i in 0 until line.size - 1) {
            val gap = line[i + 1].x - (line[i].x + line[i].width)
            gaps.add(gap)
        }

        // If gaps are relatively consistent, it might be a table
        val avgGap = gaps.average()
        val variance = gaps.map { abs(it - avgGap) }.average()

        return variance < avgGap * 0.5 && avgGap > 10f
    }

    private fun mergeLineElements(line: List<TextElement>): List<TextElement> {
        if (line.isEmpty()) return emptyList()

        val merged = mutableListOf<TextElement>()
        var current: TextElement? = null

        for (element in line) {
            if (current == null) {
                current = element
            } else {
                val gap = element.x - (current.x + current.width)
                val sameFormatting = current.fontSize == element.fontSize &&
                        current.isBold == element.isBold &&
                        current.isItalic == element.isItalic

                when {
                    // Very close - merge without space
                    gap < 1f && sameFormatting -> {
                        current = current.copy(
                            text = current.text + element.text,
                            width = element.x + element.width - current.x
                        )
                    }
                    // Normal word spacing - merge with space
                    gap < 8f && sameFormatting -> {
                        current = current.copy(
                            text = current.text + " " + element.text,
                            width = element.x + element.width - current.x
                        )
                    }
                    // Large gap or different formatting - separate elements
                    else -> {
                        merged.add(current)
                        current = element
                    }
                }
            }
        }

        current?.let { merged.add(it) }
        return merged
    }

    private fun addHeading(text: SpannableStringBuilder, elements: List<TextElement>, disableFormatting: Boolean) {
        val start = text.length
        elements.forEach { text.append(it.text) }

        if (!disableFormatting) {
            text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val sizeRatio = (elements.firstOrNull()?.fontSize ?: averageFontSize) / averageFontSize
            text.setSpan(RelativeSizeSpan(sizeRatio), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            // Add some spacing after headings
            text.append("\n")
        }
    }

    private fun addListItem(text: SpannableStringBuilder, elements: List<TextElement>, disableFormatting: Boolean) {
        val start = text.length
        val fullText = elements.joinToString("") { it.text }

        // Add indentation for nested lists
        val indentLevel = countLeadingSpaces(fullText) / 4
        text.append("  ".repeat(indentLevel))

        val contentStart = text.length
        text.append(fullText.trim())

        if (!disableFormatting) {
            // Apply bullet span
            text.setSpan(BulletSpan(20), contentStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            // Apply formatting to elements
            var offset = contentStart
            for (element in elements) {
                val elementText = element.text
                if (element.isBold) {
                    text.setSpan(StyleSpan(Typeface.BOLD), offset, offset + elementText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (element.isItalic) {
                    text.setSpan(StyleSpan(Typeface.ITALIC), offset, offset + elementText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                offset += elementText.length
            }
        }
    }

    private fun countLeadingSpaces(text: String): Int {
        return text.takeWhile { it == ' ' }.length
    }

    private fun addImagePlaceholder(text: SpannableStringBuilder) {
        val start = text.length
        text.append("📷 [Image]")
        text.setSpan(ForegroundColorSpan(Color.GRAY), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(StyleSpan(Typeface.ITALIC), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun addTableRow(text: SpannableStringBuilder, line: List<TextElement>, disableFormatting: Boolean) {
        val start = text.length

        // For simple tables, preserve spacing
        var lastX = 0f
        for (element in line) {
            val gap = element.x - lastX
            val spaces = (gap / 10f).toInt().coerceIn(0, 20)
            text.append(" ".repeat(spaces))
            text.append(element.text)
            lastX = element.x + element.width

            if (!disableFormatting) {
                val elementStart = text.length - element.text.length
                if (element.isBold) {
                    text.setSpan(StyleSpan(Typeface.BOLD), elementStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (element.isItalic) {
                    text.setSpan(StyleSpan(Typeface.ITALIC), elementStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }

        // Add subtle background for table rows
        if (!disableFormatting) {
            val bgColor = if (isDarkMode) {
                Color.argb(20, 255, 255, 255)
            } else {
                Color.argb(20, 0, 0, 0)
            }
            text.setSpan(BackgroundColorSpan(bgColor), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun addNormalText(text: SpannableStringBuilder, elements: List<TextElement>, disableFormatting: Boolean) {
        for (element in elements) {
            val start = text.length
            text.append(element.text)

            if (!disableFormatting) {
                if (element.isBold) {
                    text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (element.isItalic) {
                    text.setSpan(StyleSpan(Typeface.ITALIC), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }

    private fun addPageDivider(text: SpannableStringBuilder, pageNumber: Int) {
        text.append("\n")
        val divider = "━".repeat(40)
        val dividerStart = text.length
        text.append(divider).append("\n")
        text.setSpan(ForegroundColorSpan(Color.GRAY), dividerStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(RelativeSizeSpan(0.8f), dividerStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val pageMarker = "Page $pageNumber"
        val pageStart = text.length
        text.append(pageMarker).append("\n")
        text.setSpan(StyleSpan(Typeface.ITALIC), pageStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(ForegroundColorSpan(Color.GRAY), pageStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(RelativeSizeSpan(0.9f), pageStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val dividerStart2 = text.length
        text.append(divider).append("\n\n")
        text.setSpan(ForegroundColorSpan(Color.GRAY), dividerStart2, dividerStart2 + divider.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(RelativeSizeSpan(0.8f), dividerStart2, dividerStart2 + divider.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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

    fun getTotalPages(): Int = pages.size

    fun getFontSize(): Int {
        return sharedPrefs.getInt("text_viewer_font_size", 14)
    }

    fun getFontColor(): Int {
        val key = if (isDarkMode) "text_viewer_font_color_dark" else "text_viewer_font_color_light"
        val default = if (isDarkMode) Color.LTGRAY else Color.BLACK
        return sharedPrefs.getInt(key, default)
    }

    fun getBackgroundColor(): Int {
        val key = if (isDarkMode) "text_viewer_bg_color_dark" else "text_viewer_bg_color_light"
        val default = if (isDarkMode) Color.rgb(30, 30, 30) else Color.WHITE
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

    fun getDisableFormatting(): Boolean {
        return sharedPrefs.getBoolean("text_viewer_disable_formatting", false)
    }

    fun setDarkMode(darkMode: Boolean) {
        isDarkMode = darkMode
    }

    fun getDarkMode(): Boolean {
        return isDarkMode
    }
}