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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.docreader.data.FileType
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.util.FitPolicy
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PdfReaderEngine(private val parentFragment: Fragment) : ReaderEngine {

    private var pdfView: PDFView? = null
    private var selectionOverlay: View? = null
    private var fabCopy: com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton? = null
    private var currentUri: Uri? = null

    // --- Word-level data for the current page ---
    private var pageWords: List<WordWithBounds> = emptyList()
    private var wordsLoadedForPage: Int = -1

    // --- Selection state ---
    private val selectedIndices = mutableSetOf<Int>()
    private var anchorIndex = -1

    // Paint for selection highlight (Translucent Material Blue)
    private val highlightPaint = Paint().apply {
        color = Color.parseColor("#502196F3")
        style = Paint.Style.FILL
    }

    // Paint for the Drag Anchors (Solid Material Blue)
    private val cursorPaint = Paint().apply {
        color = Color.parseColor("#2196F3")
        style = Paint.Style.FILL
        strokeWidth = 4f
        isAntiAlias = true
    }

    // Paint for search highlight
    private val searchHighlightPaint = Paint().apply {
        color = Color.parseColor("#90FF8C00") // Orange for search matches
        style = Paint.Style.FILL
    }

    // --- Search state ---
    private var searchMatchIndices = mutableListOf<Int>()
    private var currentSearchQuery: String = ""
    
    // --- Global search state ---
    data class SearchMatch(val pageIndex: Int, val wordIndex: Int)
    private var isSearching = false
    private var globalSearchMatches: List<SearchMatch> = emptyList()
    private var currentMatchIndex: Int = -1
    private var searchJob: kotlinx.coroutines.Job? = null
    private var searchCallback: ((Int, Int) -> Unit)? = null
    private var loadingCallback: ((Boolean) -> Unit)? = null

    // Paint for active search highlight
    private val activeSearchHighlightPaint = Paint().apply {
        color = Color.parseColor("#D0FFE066") // Bright Yellow for active match
        style = Paint.Style.FILL
    }

    // Cached onDraw page matrix physics for precision touch translation
    private var lastRenderedPageWidth = 0f
    private var lastRenderedPageHeight = 0f
    private var lastRenderedPageScreenX = 0f
    private var lastRenderedPageScreenY = 0f

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

        // Create modern Extended FAB copy button
        val density = context.resources.displayMetrics.density
        val dp16 = (16 * density).toInt()
        val dp32 = (32 * density).toInt()

        fabCopy = com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                // Center the pill at the bottom like a modern iOS-style floating action
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                setMargins(dp16, dp16, dp16, dp32)
            }
            text = "Copy Text"
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            
            // Force the button to match our gorgeous vibrant Material Blue exactly!
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#2196F3"))
            
            contentDescription = "Copy Selected Text"
            visibility = View.GONE
        }
        wrapper.addView(fabCopy)

        // FAB click → copy selected text
        fabCopy?.setOnClickListener {
            if (selectedIndices.isNotEmpty()) {
                val text = formatCopiedText(selectedIndices.toList())
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
        loadingCallback?.invoke(true)
        loadPdfWithPassword(context, uri, null)
    }

    private fun loadPdfWithPassword(context: Context, uri: Uri, password: String?) {
        pdfView?.fromUri(uri)
            ?.defaultPage(0)
            ?.onLoad { nbPages ->
                loadingCallback?.invoke(false)
                Toast.makeText(context, "PDF loaded: $nbPages pages", Toast.LENGTH_SHORT).show()
            }
            ?.onPageChange { page, _ ->
                clearSelection()
                searchMatchIndices.clear()
                preloadWordsForPage(uri, page)
            }
            ?.onDraw { canvas, pageWidth, pageHeight, displayedPage ->
                val currentPage = pdfView?.currentPage ?: -1

                // Lock physical screen coordinates of the active page to bypass Variable Page-Size Drift
                if (displayedPage == currentPage) {
                    lastRenderedPageWidth = pageWidth
                    lastRenderedPageHeight = pageHeight
                    
                    val matrixValues = FloatArray(9)
                    canvas.matrix.getValues(matrixValues)
                    lastRenderedPageScreenX = matrixValues[android.graphics.Matrix.MTRANS_X]
                    lastRenderedPageScreenY = matrixValues[android.graphics.Matrix.MTRANS_Y]
                }

                // Draw search highlights
                if (displayedPage == currentPage && searchMatchIndices.isNotEmpty()) {
                    val activeWordIndex = if (currentMatchIndex in globalSearchMatches.indices && globalSearchMatches[currentMatchIndex].pageIndex == displayedPage) {
                        globalSearchMatches[currentMatchIndex].wordIndex
                    } else -1

                    for (idx in searchMatchIndices) {
                        if (idx < pageWords.size) {
                            val rect = pageWords[idx].bounds
                            val mapped = RectF(
                                rect.left * pageWidth,
                                rect.top * pageHeight,
                                rect.right * pageWidth,
                                rect.bottom * pageHeight
                            )
                            val paintToUse = if (idx == activeWordIndex) activeSearchHighlightPaint else searchHighlightPaint
                            canvas.drawRect(mapped, paintToUse)
                        }
                    }
                }

                // Draw selection highlights (Merged, Smooth, Continuous)
                if (displayedPage == currentPage && selectedIndices.isNotEmpty()) {
                    val sortedIndices = selectedIndices.sorted()
                    var currentLineRect: RectF? = null
                    val yTolerance = 0.01f // Treat words within 1% vertical difference as the same line

                    val density = parentFragment.resources.displayMetrics.density
                    val paddingX = 4f * density
                    val cornerRadius = 6f * density

                    for (idx in sortedIndices) {
                        if (idx < pageWords.size) {
                            val wordRect = pageWords[idx].bounds
                            if (currentLineRect == null) {
                                currentLineRect = RectF(wordRect)
                            } else {
                                // Check if the word is on the same line
                                val isOnSameLine = Math.abs(currentLineRect.top - wordRect.top) < yTolerance
                                if (isOnSameLine) {
                                    // Expand the current line's bounding box horizontally to connect the gap
                                    currentLineRect.right = maxOf(currentLineRect.right, wordRect.right)
                                    // Normalize the height to prevent choppy tops/bottoms
                                    currentLineRect.top = minOf(currentLineRect.top, wordRect.top)
                                    currentLineRect.bottom = maxOf(currentLineRect.bottom, wordRect.bottom)
                                } else {
                                    // Draw the completed line with beautiful vertical padding and rounded corners
                                    val paddingY = (currentLineRect.bottom - currentLineRect.top) * pageHeight * 0.15f
                                    val mapped = RectF(
                                        (currentLineRect.left * pageWidth) - paddingX,
                                        (currentLineRect.top * pageHeight) - paddingY,
                                        (currentLineRect.right * pageWidth) + paddingX,
                                        (currentLineRect.bottom * pageHeight) + paddingY
                                    )
                                    canvas.drawRoundRect(mapped, cornerRadius, cornerRadius, highlightPaint)
                                    
                                    // Start a new line
                                    currentLineRect = RectF(wordRect)
                                }
                            }
                        }
                    }
                    // Draw the final accumulated line
                    currentLineRect?.let { rect ->
                        val paddingY = (rect.bottom - rect.top) * pageHeight * 0.15f
                        val mapped = RectF(
                            (rect.left * pageWidth) - paddingX,
                            (rect.top * pageHeight) - paddingY,
                            (rect.right * pageWidth) + paddingX,
                            (rect.bottom * pageHeight) + paddingY
                        )
                        canvas.drawRoundRect(mapped, cornerRadius, cornerRadius, highlightPaint)
                    }

                    // --- DRAW START & END TEARDROP CURSORS ---
                    val handleRadius = 10f * density

                    val firstIdx = sortedIndices.first()
                    val lastIdx = sortedIndices.last()

                    val firstRect = pageWords[firstIdx].bounds
                    val lastRect = pageWords[lastIdx].bounds

                    val firstMappedLeft = firstRect.left * pageWidth
                    val firstMappedBottom = firstRect.bottom * pageHeight

                    val lastMappedRight = lastRect.right * pageWidth
                    val lastMappedBottom = lastRect.bottom * pageHeight

                    // Left Anchor (Perfect Teardrop pointing Top-Right)
                    val leftPath = android.graphics.Path()
                    leftPath.addRoundRect(
                        RectF(firstMappedLeft - (2 * handleRadius), firstMappedBottom, firstMappedLeft, firstMappedBottom + (2 * handleRadius)),
                        floatArrayOf(handleRadius, handleRadius, 0f, 0f, handleRadius, handleRadius, handleRadius, handleRadius),
                        android.graphics.Path.Direction.CW
                    )
                    canvas.drawPath(leftPath, cursorPaint)

                    // Right Anchor (Perfect Teardrop pointing Top-Left)
                    val rightPath = android.graphics.Path()
                    rightPath.addRoundRect(
                        RectF(lastMappedRight, lastMappedBottom, lastMappedRight + (2 * handleRadius), lastMappedBottom + (2 * handleRadius)),
                        floatArrayOf(0f, 0f, handleRadius, handleRadius, handleRadius, handleRadius, handleRadius, handleRadius),
                        android.graphics.Path.Direction.CW
                    )
                    canvas.drawPath(rightPath, cursorPaint)
                }
            }
            ?.onLongPress { e ->
                onLongPressDetected(context, e)
            }
            ?.onError { throwable ->
                loadingCallback?.invoke(false)
                handlePdfError(context, uri, throwable)
            }
            ?.enableSwipe(true)
            ?.swipeHorizontal(false)
            ?.enableDoubletap(true)
            ?.enableAnnotationRendering(true)
            ?.password(password)
            ?.spacing(10)
            ?.pageFitPolicy(FitPolicy.WIDTH)
            ?.fitEachPage(true)
            ?.nightMode(false)
            ?.scrollHandle(com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle(context))
            ?.load()
    }

    private fun handlePdfError(context: Context, uri: Uri, t: Throwable) {
        val message = t.message?.lowercase() ?: ""

        val isPasswordError = message.contains("password") ||
                message.contains("encrypted") ||
                message.contains("decrypt")

        if (isPasswordError) {
            showPasswordDialog(context, uri)
        } else {
            Toast.makeText(context, "Error: ${t.message}", Toast.LENGTH_LONG).show()
            t.printStackTrace()
        }
    }

    private fun showPasswordDialog(context: Context, uri: Uri) {
        val inputField = EditText(context).apply {
            hint = "Enter PDF password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(48, 32, 48, 32)
        }

        val container = FrameLayout(context).apply {
            val dp24 = (24 * resources.displayMetrics.density).toInt()
            setPadding(dp24, 0, dp24, 0)
            addView(inputField)
        }

        AlertDialog.Builder(context)
            .setTitle("Password Required")
            .setMessage("This PDF is password protected. Please enter the password to open it.")
            .setView(container)
            .setPositiveButton("Open") { _, _ ->
                val enteredPassword = inputField.text.toString()
                if (enteredPassword.isNotBlank()) {
                    loadingCallback?.invoke(true)
                    loadPdfWithPassword(context, uri, enteredPassword)
                } else {
                    Toast.makeText(context, "Password cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
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

            // Trap the touch to start dragging immediately
            selectionOverlay?.visibility = View.VISIBLE
            // Show FAB copy button
            fabCopy?.visibility = View.VISIBLE
        }
    }

    private fun setupSelectionOverlay() {
        selectionOverlay?.setOnTouchListener { _, event ->
            val coord = screenToPdfNormalized(event.x, event.y)
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Smart Touch Passthrough: If the user touches OUTSIDE the active selection bounds
                    if (coord == null) return@setOnTouchListener false
                    
                    if (selectedIndices.isNotEmpty()) {
                        val minIdx = selectedIndices.minOrNull() ?: 0
                        val maxIdx = selectedIndices.maxOrNull() ?: 0
                        
                        val startRect = pageWords[minIdx].bounds
                        val endRect = pageWords[maxIdx].bounds
                        
                        // Calculate physical geometric distance to handles
                        val dxStart = coord.first - startRect.left
                        val dyStart = coord.second - startRect.bottom
                        val distStart = (dxStart * dxStart) + (dyStart * dyStart)
                        
                        val dxEnd = coord.first - endRect.right
                        val dyEnd = coord.second - endRect.bottom
                        val distEnd = (dxEnd * dxEnd) + (dyEnd * dyEnd)

                        // Massive "Magnet Grab" radius. 0.008f represents roughly a generous 9% page width physics orb.
                        // You can comfortably touch slightly outside, slightly below, or slightly next to the teardrops and effortlessly grab them.
                        val toleranceSq = 0.008f
                        
                        if (distStart < toleranceSq || distEnd < toleranceSq) {
                            // If they grabbed the Start Handle, lock the End Index. If they grabbed End, lock Start.
                            anchorIndex = if (distStart < distEnd) maxIdx else minIdx
                            return@setOnTouchListener true
                        }
                    }
                    
                    // UX FLUID FIX: Cancel the active selection fully!
                    clearSelection() 
                    return@setOnTouchListener false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (coord == null) return@setOnTouchListener true
                    val hitIndex = findClosestWordAt(coord.first, coord.second)

                    if (hitIndex >= 0 && anchorIndex >= 0) {
                        val rangeStart = minOf(anchorIndex, hitIndex)
                        val rangeEnd = maxOf(anchorIndex, hitIndex)
                        
                        val previousCount = selectedIndices.size
                        val previousEnd = if (selectedIndices.isNotEmpty()) selectedIndices.maxOrNull() else -1
                        
                        // Prevent UI Thread freezing: ONLY force Canvas to Redraw if the user's finger actually crossed a word boundary
                        if (previousCount != (rangeEnd - rangeStart + 1) || previousEnd != rangeEnd) {
                            selectedIndices.clear()
                            for (i in rangeStart..rangeEnd) {
                                selectedIndices.add(i)
                            }
                            pdfView?.invalidate()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Keep overlay alive to intercept adjustment grabs, but release drag state
                    true
                }
                else -> true
            }
        }
    }

    // ─── Coordinate Translation ───────────────────────────────────

    private fun screenToPdfNormalized(screenX: Float, screenY: Float): Pair<Float, Float>? {
        // FATAL FLAW FIXED: Map directly to the Canvas Geometric Matrix captured in onDraw.
        // This makes touch logic 100% immune to Variable Page Heights, Zoom math bugs, and Spacing variants!
        val pageWidth = lastRenderedPageWidth 
        val pageHeight = lastRenderedPageHeight 

        if (pageWidth <= 0f || pageHeight <= 0f) return null

        val relX = screenX - lastRenderedPageScreenX
        val relY = screenY - lastRenderedPageScreenY

        val normX = (relX / pageWidth)
        val normY = (relY / pageHeight)

        // Protect indexer if dragging way outside bounds
        if (normX < -0.2f || normX > 1.2f || normY < -0.2f || normY > 1.2f) return null
        return Pair(normX.coerceIn(0f, 1f), normY.coerceIn(0f, 1f))
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
                // Re-run highlight logic on newly loaded page if search is active
                if (currentSearchQuery.isNotBlank() || isSearching) {
                    updateHighlightsForCurrentPage()
                }
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

    // ─── Mathematical Copy Formatting ─────────────────────────────────────────
    
    private fun formatCopiedText(indices: List<Int>): String {
        if (indices.isEmpty()) return ""
        val sorted = indices.sorted()
        val builder = java.lang.StringBuilder()
        
        for (i in 0 until sorted.size) {
            val idx = sorted[i]
            val wordData = pageWords.getOrNull(idx) ?: continue
            val word = wordData.word
            
            var appendWord = word
            var appendSpace = true
            var isNewline = false
            
            if (i < sorted.size - 1) {
                val nextWordData = pageWords.getOrNull(sorted[i + 1]) ?: continue
                val currRect = wordData.bounds
                val nextRect = nextWordData.bounds
                
                // 1. Line Break Isolation
                // We use the exact same stable Y-Axis 1% Tolerance algorithm that we built for the Highlighting!
                val yTolerance = 0.01f 
                val isOnSameLine = Math.abs(nextRect.top - currRect.top) < yTolerance
                
                if (!isOnSameLine) {
                    isNewline = true
                    appendSpace = false
                    
                    // Hyphen Cleanup: Detect text straddling two lines naturally
                    if (appendWord.endsWith("-")) {
                        appendWord = appendWord.dropLast(1)
                    }
                }
                
                // 2. Punctuation Kerning Isolation
                // If words reside on the same line but physics dictate negligible space (e.g. "Sentence.")
                if (isOnSameLine) {
                    val xDistance = nextRect.left - currRect.right
                    // If the distance is smaller than a tiny page fraction (or overlapping negatively)
                    if (xDistance < 0.003f) {
                        appendSpace = false
                    }
                }
            } else {
                appendSpace = false // Terminal string execution
            }
            
            builder.append(appendWord)
            if (isNewline) builder.append("\n")
            else if (appendSpace) builder.append(" ")
        }
        return builder.toString()
    }

    override fun copyText(): Boolean {
        // If there's a current selection, copy it
        if (selectedIndices.isNotEmpty()) {
            val selectedText = formatCopiedText(selectedIndices.toList())

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
        currentSearchQuery = query
        searchJob?.cancel()
        
        if (query.isBlank()) {
            isSearching = false
            globalSearchMatches = emptyList()
            currentMatchIndex = -1
            clearSearchHighlights()
            searchCallback?.invoke(0, 0)
            return
        }

        val context = parentFragment.context ?: return
        val uri = currentUri ?: return
        val pageCount = pdfView?.pageCount ?: 0
        if (pageCount == 0) return
        
        isSearching = true
        searchJob = parentFragment.lifecycleScope.launch(Dispatchers.Default) {
             var document: com.tom_roush.pdfbox.pdmodel.PDDocument? = null
             try {
                 val contentResolver = parentFragment.requireContext().contentResolver
                 val inputStream = contentResolver.openInputStream(uri) ?: return@launch
                 
                 // MASSIVE IO CRASH FIX: Parse the PDF physically ONCE for the entire global search jump
                 document = com.tom_roush.pdfbox.pdmodel.PDDocument.load(inputStream)
                 val stripper = PdfBoxBoundingBoxStripper()
                 
                 val matches = mutableListOf<SearchMatch>()
                 val lowerQuery = query.lowercase()
                 
                 for (page in 0 until pageCount) {
                     if (!isActive) break
                     try {
                         // Search memory matrix natively instantly
                         val wordsOnPage = stripper.extractWordsWithBounds(document, page)
                         for (i in wordsOnPage.indices) {
                             if (wordsOnPage[i].word.lowercase().contains(lowerQuery)) {
                                 matches.add(SearchMatch(pageIndex = page, wordIndex = i))
                             }
                         }
                     } catch (e: Exception) {
                         // Corrupted page handler
                     }
                 }
                 
                 if (!isActive) return@launch
                 
                 withContext(Dispatchers.Main) {
                     globalSearchMatches = matches
                     if (matches.isNotEmpty()) {
                         currentMatchIndex = 0
                         updateSearchUiAndJump()
                     } else {
                         currentMatchIndex = -1
                         clearSearchHighlights()
                         searchCallback?.invoke(0, 0)
                         Toast.makeText(context, "No matches found", Toast.LENGTH_SHORT).show()
                     }
                 }
             } catch (e: Exception) {
                 e.printStackTrace()
             } finally {
                 document?.close() // Destroy the mammoth document gracefully
             }
        }
    }

    private fun updateHighlightsForCurrentPage() {
        searchMatchIndices.clear()
        if (currentSearchQuery.isBlank() || pageWords.isEmpty() || !isSearching) {
            pdfView?.invalidate()
            return
        }

        val lowerQuery = currentSearchQuery.lowercase()
        for (i in pageWords.indices) {
            if (pageWords[i].word.lowercase().contains(lowerQuery)) {
                searchMatchIndices.add(i)
            }
        }
        pdfView?.invalidate()
    }
    
    private fun updateSearchUiAndJump() {
        if (globalSearchMatches.isEmpty() || currentMatchIndex !in globalSearchMatches.indices) return
        
        val match = globalSearchMatches[currentMatchIndex]
        searchCallback?.invoke(currentMatchIndex, globalSearchMatches.size)
        
        // Jump to page (triggers onPageChanged -> loadWordsForPage -> updateHighlightsForCurrentPage)
        pdfView?.jumpTo(match.pageIndex)
        
        // If we are already on the page, jumpTo might not trigger onPageChanged
        if (pdfView?.currentPage == match.pageIndex) {
            updateHighlightsForCurrentPage()
        }
    }

    override fun findNext() {
        if (globalSearchMatches.isEmpty()) return
        currentMatchIndex = (currentMatchIndex + 1) % globalSearchMatches.size
        updateSearchUiAndJump()
    }

    override fun findPrevious() {
        if (globalSearchMatches.isEmpty()) return
        currentMatchIndex = if (currentMatchIndex - 1 < 0) globalSearchMatches.size - 1 else currentMatchIndex - 1
        updateSearchUiAndJump()
    }

    override fun setSearchCallback(callback: (current: Int, total: Int) -> Unit) {
        this.searchCallback = callback
    }

    override fun setOnLoadingStateListener(callback: (Boolean) -> Unit) {
        this.loadingCallback = callback
    }

    private fun clearSearchHighlights() {
        searchMatchIndices.clear()
        pdfView?.invalidate()
    }

    override fun setDarkMode(isDarkMode: Boolean) {
        if (isDarkMode) {
            pdfView?.setBackgroundColor(Color.parseColor("#121212"))
        } else {
            pdfView?.setBackgroundColor(Color.parseColor("#E0E0E0"))
        }
    }

    override fun onDestroy() {
        pdfView?.recycle()
        pdfView = null
        selectionOverlay = null
        fabCopy = null
    }
}