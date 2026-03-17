package com.example.docreader.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.docreader.data.FileType
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.util.FitPolicy
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PdfReaderEngine(private val parentFragment: Fragment) : ReaderEngine {

    private var pdfView: PDFView? = null
    private var selectionOverlay: View? = null
    private var fabCopy: FloatingActionButton? = null
    private var currentUri: Uri? = null

    // --- Word-level data for the current page ---
    private var pageWords: List<WordWithBounds> = emptyList()
    private var wordsLoadedForPage: Int = -1

    // --- Selection state ---
    private val selectedIndices = mutableSetOf<Int>()
    private var anchorIndex = -1

    // Paint for highlight
    private val highlightPaint = Paint().apply {
        color = Color.parseColor("#80FFFF00")
        style = Paint.Style.FILL
    }

    // Cached onDraw page dimensions for coordinate translation
    private var lastRenderedPageWidth = 0f
    private var lastRenderedPageHeight = 0f

    override fun load(context: Context, uri: Uri, fileType: FileType, container: ViewGroup) {
        currentUri = uri

        // Create a FrameLayout to hold PDFView + overlay + FAB
        val wrapper = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Create PDFView
        pdfView = PDFView(context, null).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        wrapper.addView(pdfView)

        // Create transparent selection overlay (GONE by default)
        selectionOverlay = View(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
        }
        wrapper.addView(selectionOverlay)

        // Create FAB copy button (GONE by default, same as PdfReader)
        val dp16 = (16 * context.resources.displayMetrics.density).toInt()
        val dp56 = (56 * context.resources.displayMetrics.density).toInt()
        fabCopy = FloatingActionButton(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(dp16, dp16, dp16, dp16)
            }
            setImageResource(android.R.drawable.ic_menu_edit)
            contentDescription = "Copy Selected Text"
            size = FloatingActionButton.SIZE_MINI
            visibility = View.GONE
        }
        wrapper.addView(fabCopy)

        // FAB click → copy selected text
        fabCopy?.setOnClickListener {
            if (selectedIndices.isNotEmpty()) {
                val text = selectedIndices.sorted().mapNotNull { idx ->
                    pageWords.getOrNull(idx)?.word
                }.joinToString(" ")

                if (text.isNotBlank()) {
                    copyToClipboard(context, text)
                }
            }
            clearSelection()
        }

        // Add wrapper to the container
        container.removeAllViews()
        container.addView(wrapper)

        // Setup drag-to-select on the overlay
        setupSelectionOverlay()

        // Initialize PdfBox font resources
        try {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Load PDF
        pdfView?.fromUri(uri)
            ?.defaultPage(0)
            ?.onLoad { nbPages ->
                Toast.makeText(context, "PDF loaded: $nbPages pages", Toast.LENGTH_SHORT).show()
            }
            ?.onPageChange { page, _ ->
                clearSelection()
                preloadWordsForPage(uri, page)
            }
            ?.onDraw { canvas, pageWidth, pageHeight, displayedPage ->
                lastRenderedPageWidth = pageWidth
                lastRenderedPageHeight = pageHeight

                // Draw yellow highlights for selected words
                val currentPage = pdfView?.currentPage ?: -1
                if (displayedPage == currentPage && selectedIndices.isNotEmpty()) {
                    for (idx in selectedIndices) {
                        if (idx < pageWords.size) {
                            val rect = pageWords[idx].bounds
                            val mapped = RectF(
                                rect.left * pageWidth,
                                rect.top * pageHeight,
                                rect.right * pageWidth,
                                rect.bottom * pageHeight
                            )
                            canvas.drawRect(mapped, highlightPaint)
                        }
                    }
                }
            }
            ?.onLongPress { e ->
                onLongPressDetected(context, e)
            }
            ?.onError { throwable ->
                Toast.makeText(context, "Error loading PDF: ${throwable.message}", Toast.LENGTH_LONG).show()
                throwable.printStackTrace()
            }
            ?.enableSwipe(true)
            ?.swipeHorizontal(false)
            ?.enableDoubletap(true)
            ?.enableAnnotationRendering(true)
            ?.password(null)
            ?.spacing(10)
            ?.pageFitPolicy(FitPolicy.WIDTH)
            ?.fitEachPage(true)
            ?.nightMode(false)
            ?.load()
    }

    // ─── Long Press + Drag Selection ──────────────────────────────

    private fun onLongPressDetected(context: Context, e: MotionEvent) {
        if (pageWords.isEmpty()) {
            Toast.makeText(context, "No text found on this page", Toast.LENGTH_SHORT).show()
            return
        }

        val coord = screenToPdfNormalized(e.x, e.y) ?: return
        val hitIndex = findWordAt(coord.first, coord.second)

        if (hitIndex >= 0) {
            anchorIndex = hitIndex
            selectedIndices.clear()
            selectedIndices.add(hitIndex)
            pdfView?.invalidate()

            // Show the overlay so it captures drag events
            selectionOverlay?.visibility = View.VISIBLE
            // Show FAB copy button
            fabCopy?.visibility = View.VISIBLE
        }
    }

    private fun setupSelectionOverlay() {
        selectionOverlay?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_MOVE -> {
                    val coord = screenToPdfNormalized(event.x, event.y)
                        ?: return@setOnTouchListener true
                    val hitIndex = findClosestWordAt(coord.first, coord.second)

                    if (hitIndex >= 0 && anchorIndex >= 0) {
                        val rangeStart = minOf(anchorIndex, hitIndex)
                        val rangeEnd = maxOf(anchorIndex, hitIndex)
                        selectedIndices.clear()
                        for (i in rangeStart..rangeEnd) {
                            selectedIndices.add(i)
                        }
                        pdfView?.invalidate()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Hide overlay so PDFView gets touches again,
                    // but keep the highlights & FAB visible so user can tap Copy
                    selectionOverlay?.visibility = View.GONE
                    true
                }
                else -> true
            }
        }
    }

    // ─── Coordinate Translation ───────────────────────────────────

    private fun screenToPdfNormalized(screenX: Float, screenY: Float): Pair<Float, Float>? {
        val pv = pdfView ?: return null
        val zoom = pv.zoom
        val currentPage = pv.currentPage

        val pageWidth = lastRenderedPageWidth * zoom
        val pageHeight = lastRenderedPageHeight * zoom

        if (pageWidth <= 0f || pageHeight <= 0f) return null

        val offsetX = pv.currentXOffset
        val offsetY = pv.currentYOffset

        val density = parentFragment.resources.displayMetrics.density
        val spacingPx = 10f * density * zoom
        val pageTopY = offsetY + currentPage.toFloat() * (pageHeight + spacingPx)

        val relX = screenX - offsetX
        val relY = screenY - pageTopY

        val normX = (relX / pageWidth).coerceIn(0f, 1f)
        val normY = (relY / pageHeight).coerceIn(0f, 1f)

        return Pair(normX, normY)
    }

    private fun findWordAt(normX: Float, normY: Float): Int {
        val tolerance = 0.02f
        for (i in pageWords.indices) {
            val r = pageWords[i].bounds
            if (normX >= r.left - tolerance && normX <= r.right + tolerance &&
                normY >= r.top - tolerance && normY <= r.bottom + tolerance) {
                return i
            }
        }
        return -1
    }

    private fun findClosestWordAt(normX: Float, normY: Float): Int {
        val exact = findWordAt(normX, normY)
        if (exact >= 0) return exact

        var bestIndex = -1
        var bestDist = Float.MAX_VALUE
        for (i in pageWords.indices) {
            val r = pageWords[i].bounds
            val cx = (r.left + r.right) / 2f
            val cy = (r.top + r.bottom) / 2f
            val dx = normX - cx
            val dy = normY - cy
            val dist = dx * dx + dy * dy
            if (dist < bestDist) {
                bestDist = dist
                bestIndex = i
            }
        }
        return if (bestDist < 0.01f) bestIndex else -1
    }

    // ─── Word Pre-loading ─────────────────────────────────────────

    private fun preloadWordsForPage(uri: Uri, pageIndex: Int) {
        if (wordsLoadedForPage == pageIndex) return

        parentFragment.lifecycleScope.launch {
            try {
                val words = extractWordsFromUri(uri, pageIndex)
                pageWords = words ?: emptyList()
                wordsLoadedForPage = pageIndex
            } catch (e: Exception) {
                e.printStackTrace()
                pageWords = emptyList()
            }
        }
    }

    private suspend fun extractWordsFromUri(uri: Uri, pageIndex: Int): List<WordWithBounds>? = withContext(Dispatchers.IO) {
        var document: com.tom_roush.pdfbox.pdmodel.PDDocument? = null
        try {
            val inputStream = parentFragment.requireContext().contentResolver.openInputStream(uri)
                ?: return@withContext null
            document = com.tom_roush.pdfbox.pdmodel.PDDocument.load(inputStream)
            val stripper = PdfBoxBoundingBoxStripper()
            stripper.extractWordsWithBounds(document, pageIndex)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            document?.close()
        }
    }

    // ─── Copy & Selection ─────────────────────────────────────────

    private fun clearSelection() {
        selectedIndices.clear()
        anchorIndex = -1
        selectionOverlay?.visibility = View.GONE
        fabCopy?.visibility = View.GONE
        pdfView?.invalidate()
    }

    private fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("PDF Text", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Text copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    override fun copyText(): Boolean {
        // If there's a current selection, copy it
        if (selectedIndices.isNotEmpty()) {
            val selectedText = selectedIndices.sorted().mapNotNull { idx ->
                pageWords.getOrNull(idx)?.word
            }.joinToString(" ")

            if (selectedText.isNotBlank()) {
                val ctx = parentFragment.context ?: return false
                copyToClipboard(ctx, selectedText)
                clearSelection()
                return true
            }
        }

        // If no selection, extract entire page text and show dialog
        val context = parentFragment.context ?: return false
        val uri = currentUri ?: return false
        val currentPage = pdfView?.currentPage ?: return false

        parentFragment.lifecycleScope.launch {
            try {
                val words = extractWordsFromUri(uri, currentPage)
                val fullText = words?.joinToString(" ") { it.word } ?: ""
                if (fullText.isNotBlank()) {
                    withContext(Dispatchers.Main) {
                        showTextDialog(context, fullText)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "No text found on this page", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error extracting text: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        return true
    }

    private fun showTextDialog(context: Context, text: String) {
        val builder = androidx.appcompat.app.AlertDialog.Builder(context)
        builder.setTitle("Extracted Text")

        val scrollView = android.widget.ScrollView(context)
        val textView = android.widget.TextView(context)
        textView.text = text
        textView.setPadding(32, 32, 32, 32)
        textView.setTextIsSelectable(true)
        scrollView.addView(textView)
        builder.setView(scrollView)

        builder.setPositiveButton("Copy All") { _, _ ->
            copyToClipboard(context, text)
        }
        builder.setNegativeButton("Close", null)
        builder.show()
    }

    override fun search(query: String) {
        if (query.isNotBlank()) {
            Toast.makeText(
                parentFragment.requireContext(),
                "Search is not yet supported for PDF",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroy() {
        pdfView?.recycle()
        pdfView = null
        selectionOverlay = null
        fabCopy = null
    }
}