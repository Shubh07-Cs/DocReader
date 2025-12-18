package com.example.docreader.reader

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
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
    private var loadJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun load(context: Context, uri: Uri, fileType: FileType, container: ViewGroup) {
        val scrollView = ScrollView(context).apply {
             layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val linearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        scrollView.addView(linearLayout)
        container.removeAllViews()
        container.addView(scrollView)

        loadJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    fileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
                    fileDescriptor?.let { 
                        pdfRenderer = PdfRenderer(it)
                    }
                }

                pdfRenderer?.let { renderer ->
                    // Render first 5 pages for stability
                    for (i in 0 until minOf(renderer.pageCount, 5)) {
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

        renderer.openPage(index).use { page ->
            val displayMetrics = context.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val scale = screenWidth.toFloat() / page.width
            val height = (page.height * scale).toInt()
            
            val bitmap = Bitmap.createBitmap(screenWidth, height, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            // Check for dark mode and invert colors if needed
            val nightModeFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES) {
                invertBitmapColors(bitmap)
            }
            
            val imageView = ImageView(context).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 16 }
            }
            layout.addView(imageView)
        }
    }

    private fun invertBitmapColors(bitmap: Bitmap) {
        val canvas = Canvas(bitmap)
        val paint = Paint()
        val matrix = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
    }

    override fun onDestroy() {
        loadJob?.cancel()
        pdfRenderer?.close()
        fileDescriptor?.close()
    }

    override fun search(query: String) {
        // Native PdfRenderer does not support text search.
    }
}