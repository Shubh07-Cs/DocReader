package com.example.docreader.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.example.docreader.data.FileType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PdfReaderEngine : ReaderEngine {

    private var pdfRenderer: PdfRenderer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var currentPage: PdfRenderer.Page? = null
    private var loadJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var containerView: ViewGroup? = null

    override fun load(context: Context, uri: Uri, fileType: FileType, container: ViewGroup) {
        this.containerView = container
        
        val scrollView = ScrollView(context).apply {
             layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val linearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        scrollView.addView(linearLayout)
        container.removeAllViews()
        container.addView(scrollView)

        loadJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    if (pfd != null) {
                        fileDescriptor = pfd
                        pdfRenderer = PdfRenderer(pfd)
                    }
                }

                pdfRenderer?.let { renderer ->
                    val pageCount = renderer.pageCount
                    // Render first 5 pages for stability
                    for (i in 0 until minOf(pageCount, 5)) {
                        renderPage(context, linearLayout, i)
                    }
                }
            } catch (e: Exception) {
                // Handle error
            }
        }
    }
    
    private fun renderPage(context: Context, layout: LinearLayout, index: Int) {
        val renderer = pdfRenderer ?: return
        if (index >= renderer.pageCount) return

        val page = renderer.openPage(index)
        
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val scale = screenWidth.toFloat() / page.width
        val height = (page.height * scale).toInt()
        
        val bitmap = Bitmap.createBitmap(screenWidth, height, Bitmap.Config.ARGB_8888)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        
        val imageView = ImageView(context).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16 
            }
        }
        layout.addView(imageView)
        
        page.close()
    }

    override fun onDestroy() {
        loadJob?.cancel()
        currentPage?.close()
        pdfRenderer?.close()
        fileDescriptor?.close()
    }

    override fun search(query: String) {
        // Native PdfRenderer does not support text search.
    }
}