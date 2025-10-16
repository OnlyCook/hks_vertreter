package com.thecooker.vertretungsplaner.ui.moodle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import com.thecooker.vertretungsplaner.L
import java.io.File
import java.io.FileInputStream
import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.parser.LocationTextExtractionStrategy
import com.itextpdf.text.pdf.parser.PdfTextExtractor

class PdfViewerManager(private val context: Context) {

    private var pdfRenderer: PdfRenderer? = null
    private var currentPdfFile: File? = null
    private var currentPage = 0
    var scrollModeEnabled = true
        private set
    var forceDarkMode = false
        private set

    private val pageCache = mutableMapOf<Int, Bitmap>()
    private val MAX_CACHE_SIZE = 9
    internal val currentlyDisplayedPages = mutableSetOf<Int>()

    data class PdfState(
        val file: File,
        val currentPage: Int,
        val scrollMode: Boolean,
        val darkMode: Boolean
    )

    fun openPdf(pdfFile: File): Boolean {
        return try {
            closePdf()

            val fileDescriptor = ParcelFileDescriptor.open(
                pdfFile,
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            pdfRenderer = PdfRenderer(fileDescriptor)
            currentPdfFile = pdfFile
            currentPage = 0
            pageCache.clear()
            true
        } catch (e: Exception) {
            L.e("PdfViewerManager", "Error opening PDF", e)
            false
        }
    }

    @Synchronized
    fun getPageCount(): Int = pdfRenderer?.pageCount ?: 0

    fun getCurrentPage(): Int = currentPage

    fun setCurrentPage(page: Int): Boolean {
        if (page < 0 || page >= getPageCount()) return false
        currentPage = page
        return true
    }

    @Synchronized
    fun getCachedPage(page: Int): Bitmap? {
        return pageCache[page]
    }

    fun removeCachedPage(pageNumber: Int) {
        val bitmap = pageCache.remove(pageNumber)
        if (bitmap != null && !bitmap.isRecycled) {
            bitmap.recycle()
        }
    }

    @Synchronized
    fun renderPage(page: Int, forceRender: Boolean = false): Bitmap? {
        if (!forceRender && pageCache.containsKey(page)) {
            return pageCache[page]
        }

        if (forceRender && pageCache.containsKey(page)) {
            val oldBitmap = pageCache.remove(page)
            if (page !in currentlyDisplayedPages && oldBitmap != null && !oldBitmap.isRecycled) {
                oldBitmap.recycle()
            }
        }

        return try {
            pdfRenderer?.let { renderer ->
                if (page >= 0 && page < renderer.pageCount) {
                    val pdfPage = renderer.openPage(page)
                    val bitmap = createBitmap(pdfPage.width * 2, pdfPage.height * 2, Bitmap.Config.ARGB_8888)

                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    pdfPage.close()

                    val finalBitmap = if (forceDarkMode) {
                        applyDarkModeFilter(bitmap)
                    } else {
                        bitmap
                    }

                    addToCache(page, finalBitmap)
                    finalBitmap
                } else null
            }
        } catch (e: Exception) {
            L.e("PdfViewerManager", "Error rendering page", e)
            null
        }
    }

    private fun addToCache(page: Int, bitmap: Bitmap) {
        if (pageCache.size >= MAX_CACHE_SIZE) {
            val pagesToRemove = pageCache.keys
                .filter { it !in currentlyDisplayedPages }
                .sortedByDescending { kotlin.math.abs(it - currentPage) }
                .take(pageCache.size - MAX_CACHE_SIZE + 1)

            pagesToRemove.forEach { pageNum ->
                val oldBitmap = pageCache[pageNum]
                if (oldBitmap != null && !oldBitmap.isRecycled) {
                    oldBitmap.recycle()
                }
                pageCache.remove(pageNum)
            }

            L.d("PdfViewerManager", "Cache cleanup: removed ${pagesToRemove.size} pages")
        }

        pageCache[page] = bitmap
    }

    fun updateDisplayedPages(pages: Set<Int>) {
        currentlyDisplayedPages.clear()
        currentlyDisplayedPages.addAll(pages)

        val pagesToRemove = pageCache.keys.filter { pageNum ->
            pageNum !in currentlyDisplayedPages &&
                    kotlin.math.abs(pageNum - currentPage) > 8
        }

        if (pagesToRemove.isNotEmpty()) {
            L.d("PdfViewerManager", "Removing ${pagesToRemove.size} pages from cache")
            pagesToRemove.forEach { pageNum ->
                val bitmap = pageCache[pageNum]
                if (bitmap != null && !bitmap.isRecycled) {
                    bitmap.recycle()
                }
                pageCache.remove(pageNum)
            }
        }
    }

    fun clearAllCache() {
        pageCache.values.forEach { it.recycle() }
        pageCache.clear()
        currentlyDisplayedPages.clear()
    }

    private fun applyDarkModeFilter(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xff
            val g = (pixel shr 8) and 0xff
            val b = pixel and 0xff

            val newR = 255 - r
            val newG = 255 - g
            val newB = 255 - b

            pixels[i] = (0xFF shl 24) or (newR shl 16) or (newG shl 8) or newB
        }

        val newBitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

        bitmap.recycle()

        return newBitmap
    }

    fun extractCurrentPageText(): String? {
        return currentPdfFile?.let { file ->
            try {
                val fileInputStream = FileInputStream(file)
                val reader = PdfReader(fileInputStream)

                val strategy = LocationTextExtractionStrategy()
                val text = PdfTextExtractor.getTextFromPage(
                    reader,
                    currentPage + 1,
                    strategy
                )

                reader.close()
                fileInputStream.close()
                text
            } catch (e: Exception) {
                L.e("PdfViewerManager", "Error extracting page text", e)
                null
            }
        }
    }

    fun extractAllText(): String? {
        return currentPdfFile?.let { file ->
            try {
                val fileInputStream = FileInputStream(file)
                val reader = PdfReader(fileInputStream)
                val textBuilder = StringBuilder()

                for (page in 1..reader.numberOfPages) {
                    val strategy = LocationTextExtractionStrategy()
                    val pageText = PdfTextExtractor.getTextFromPage(reader, page, strategy)
                    textBuilder.append(pageText)
                    if (page < reader.numberOfPages) {
                        textBuilder.append("\n\n--- Page ${page + 1} ---\n\n")
                    }
                }

                reader.close()
                fileInputStream.close()
                textBuilder.toString()
            } catch (e: Exception) {
                L.e("PdfViewerManager", "Error extracting all text", e)
                null
            }
        }
    }

    fun toggleScrollMode() {
        scrollModeEnabled = !scrollModeEnabled
    }

    fun toggleDarkMode() {
        forceDarkMode = !forceDarkMode
        pageCache.values.forEach { it.recycle() }
        pageCache.clear()
        currentlyDisplayedPages.clear()
    }

    fun getCurrentState(): PdfState? {
        return currentPdfFile?.let { file ->
            PdfState(file, currentPage, scrollModeEnabled, forceDarkMode)
        }
    }

    fun closePdf() {
        clearAllCache()
        pdfRenderer?.close()
        pdfRenderer = null
        currentPdfFile = null
        currentPage = 0
    }

    @Synchronized
    fun clearAllCacheForJump() {
        L.d("PdfViewerManager", "Clearing all cache for jump navigation")

        pageCache.values.forEach { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }

        pageCache.clear()
        currentlyDisplayedPages.clear()

        L.d("PdfViewerManager", "Cache cleared completely")
    }
}