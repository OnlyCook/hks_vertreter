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

class TextViewerManager(private val context: Context) {

    data class TextPage(
        val pageNumber: Int,
        val textElements: List<TextElement>,
        val hasHeader: Boolean = false,
        val hasFooter: Boolean = false
    )

    data class TextElement(
        val text: String,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val fontSize: Float,
        val isBold: Boolean = false,
        val isItalic: Boolean = false,
        val isUnderlined: Boolean = false,
        val isStrikethrough: Boolean = false,
        val isImage: Boolean = false,
        val elementType: ElementType = ElementType.NORMAL
    )

    enum class ElementType {
        NORMAL, HEADER, FOOTER, TITLE, TABLE_CELL, LIST_ITEM, IMAGE
    }

    private var pages = listOf<TextPage>()
    private var sharedPrefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private var isDarkMode = false
    private var baseFontSize = 12f
    private val pageHeight = mutableMapOf<Int, Float>()

    fun parsePdfToText(pdfFile: File, darkMode: Boolean): Boolean {
        isDarkMode = darkMode
        return try {
            val fileInputStream = FileInputStream(pdfFile)
            val reader = PdfReader(fileInputStream)
            val pagesList = mutableListOf<TextPage>()

            for (pageNum in 1..reader.numberOfPages) {
                val elements = extractPageElements(reader, pageNum)

                if (pageNum == 1 && elements.isNotEmpty()) {
                    baseFontSize = elements.filter { !it.isImage }
                        .map { it.fontSize }
                        .groupingBy { it }
                        .eachCount()
                        .maxByOrNull { it.value }?.key ?: 12f
                }

                val sortedElements = sortAndGroupElements(elements, pageNum)
                val (hasHeader, hasFooter) = detectHeaderFooter(sortedElements, pageNum)

                pagesList.add(TextPage(pageNum, sortedElements, hasHeader, hasFooter))
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

    private fun extractPageElements(reader: PdfReader, pageNum: Int): List<TextElement> {
        val elements = mutableListOf<TextElement>()

        try {
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
                            fontName.contains("heavy") || fontName.contains("semibold") ||
                            fontName.contains("demi")
                    val isItalic = fontName.contains("italic") || fontName.contains("oblique") ||
                            fontName.contains("slanted")

                    textElements.add(TextElement(
                        text = text,
                        x = x,
                        y = y,
                        width = width,
                        height = fontSize,
                        fontSize = fontSize,
                        isBold = isBold,
                        isItalic = isItalic
                    ))
                }

                fun getElements(): List<TextElement> = textElements
            }

            PdfTextExtractor.getTextFromPage(reader, pageNum, strategy)
            elements.addAll(strategy.getElements())

            val page = reader.getPageSize(pageNum)
            pageHeight[pageNum] = page.height

        } catch (e: Exception) {
            L.e("TextViewerManager", "Error extracting page elements", e)
        }

        return elements
    }

    private fun sortAndGroupElements(elements: List<TextElement>, pageNum: Int): List<TextElement> {
        if (elements.isEmpty()) return emptyList()

        val sorted = elements.sortedWith(compareBy(
            { -it.y },
            { it.x }
        ))

        val grouped = mutableListOf<TextElement>()
        var currentLineElements = mutableListOf<TextElement>()
        var currentLineY = sorted.firstOrNull()?.y ?: 0f
        val lineThreshold = 4f

        for (element in sorted) {
            if (abs(element.y - currentLineY) <= lineThreshold) {
                currentLineElements.add(element)
            } else {
                if (currentLineElements.isNotEmpty()) {
                    grouped.addAll(mergeLineElements(currentLineElements, pageNum))
                }
                currentLineElements = mutableListOf(element)
                currentLineY = element.y
            }
        }

        if (currentLineElements.isNotEmpty()) {
            grouped.addAll(mergeLineElements(currentLineElements, pageNum))
        }

        return grouped
    }

    private fun mergeLineElements(lineElements: List<TextElement>, pageNum: Int = 0): List<TextElement> {
        if (lineElements.isEmpty()) return emptyList()

        val sorted = lineElements.sortedBy { it.x }
        val merged = mutableListOf<TextElement>()

        var currentElement: TextElement? = null

        val nonImageElements = sorted.filter { !it.isImage }
        if (nonImageElements.isEmpty()) return sorted

        val avgFontSize = nonImageElements.map { it.fontSize }.average().toFloat()
        val charWidth = avgFontSize * 0.5f

        for (element in sorted) {
            if (element.isImage) {
                currentElement?.let { merged.add(it) }
                merged.add(element)
                currentElement = null
            } else if (currentElement == null) {
                currentElement = element
            } else {
                val gap = element.x - (currentElement.x + currentElement.width)
                val formattingMatches = currentElement.fontSize == element.fontSize &&
                        currentElement.isBold == element.isBold &&
                        currentElement.isItalic == element.isItalic

                when {
                    // elements are very close or overlapping slightly -> always merge
                    gap < 0.2f && formattingMatches -> {
                        currentElement = currentElement.copy(
                            text = currentElement.text + element.text,
                            width = element.x + element.width - currentElement.x
                        )
                    }
                    // small gap (0.2-1.5) with matching formatting -> likely same word: merge with space
                    gap in 0.2f..1.5f && formattingMatches -> {
                        currentElement = currentElement.copy(
                            text = currentElement.text + " " + element.text,
                            width = element.x + element.width - currentElement.x
                        )
                    }
                    // normal word gap (1.5-6) with matching formatting -> merge with space
                    gap in 1.5f..6f && formattingMatches -> {
                        currentElement = currentElement.copy(
                            text = currentElement.text + " " + element.text,
                            width = element.x + element.width - currentElement.x
                        )
                    }
                    else -> {
                        merged.add(currentElement)
                        currentElement = element
                    }
                }
            }
        }

        currentElement?.let { merged.add(it) }
        return merged
    }

    private fun detectHeaderFooter(elements: List<TextElement>, pageNum: Int): Pair<Boolean, Boolean> {
        if (elements.isEmpty()) return Pair(false, false)

        val height = pageHeight[pageNum] ?: 800f
        val headerThreshold = height * 0.90f // top 10%
        val footerThreshold = height * 0.10f // bottom 10%

        var hasHeader = false
        var hasFooter = false

        for (element in elements) {
            if (!element.isImage) {
                if (element.y > headerThreshold) {
                    hasHeader = true
                }
                if (element.y < footerThreshold) {
                    hasFooter = true
                }
            }
        }

        return Pair(hasHeader, hasFooter)
    }

    fun getFormattedText(maxLineWidth: Int): SpannableStringBuilder {
        val fullText = SpannableStringBuilder()
        val disableFormatting = sharedPrefs.getBoolean("text_viewer_disable_formatting", false)

        for ((pageIndex, page) in pages.withIndex()) {
            if (pageIndex > 0) {
                addPageSeparator(fullText, page.pageNumber)
            }

            val nonHeaderFooterElements = page.textElements.filter { element ->
                when (element.elementType) {
                    ElementType.HEADER -> pageIndex == 0
                    ElementType.FOOTER -> false
                    else -> true
                }
            }

            for ((elementIndex, element) in nonHeaderFooterElements.withIndex()) {
                val elementText = element.text.trim()

                val isLineNumberMarker = elementText.matches(Regex("\\[\\d+.*?\\]"))

                if (isLineNumberMarker) {
                    addFormattedElement(fullText, element, disableFormatting)
                } else {
                    addFormattedElement(fullText, element, disableFormatting)

                    if (elementIndex < nonHeaderFooterElements.size - 1) {
                        fullText.append("\n")
                    }
                }
            }

            if (pageIndex < pages.size - 1) {
                fullText.append("\n")
            }
        }

        return fullText
    }

    private fun addPageSeparator(text: SpannableStringBuilder, pageNumber: Int) {
        text.append("\n")
        val separator = "━".repeat(50)
        val sepStart = text.length
        text.append(separator).append("\n")
        text.setSpan(ForegroundColorSpan(Color.GRAY), sepStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val pageMarker = "Page $pageNumber"
        val pageStart = text.length
        text.append(pageMarker).append("\n")
        text.setSpan(StyleSpan(Typeface.ITALIC), pageStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(ForegroundColorSpan(Color.GRAY), pageStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val sepStart2 = text.length
        text.append(separator).append("\n\n")
        text.setSpan(ForegroundColorSpan(Color.GRAY), sepStart2, sepStart2 + separator.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun addFormattedElement(text: SpannableStringBuilder, element: TextElement, disableFormatting: Boolean) {
        val start = text.length

        if (element.isImage) {
            text.append("📷 [Image]")
            if (!disableFormatting) {
                text.setSpan(ForegroundColorSpan(Color.GRAY), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(StyleSpan(Typeface.ITALIC), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return
        }

        text.append(element.text)

        if (!disableFormatting) {
            if (element.isBold) {
                text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (element.isItalic) {
                text.setSpan(StyleSpan(Typeface.ITALIC), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (element.isUnderlined) {
                text.setSpan(UnderlineSpan(), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (element.isStrikethrough) {
                text.setSpan(StrikethroughSpan(), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            if (element.elementType == ElementType.TITLE || element.fontSize > baseFontSize * 1.3f) {
                val sizeRatio = (element.fontSize / baseFontSize).coerceIn(1f, 2f)
                text.setSpan(RelativeSizeSpan(sizeRatio), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    fun getTotalLines(): Int {
        var total = 0
        for (page in pages) {
            total += page.textElements.filter { !it.isImage }.size
        }
        return total.coerceAtLeast(1)
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

    fun setDarkMode(darkMode: Boolean) {
        isDarkMode = darkMode
    }

    fun getDarkMode(): Boolean {
        return isDarkMode
    }
}