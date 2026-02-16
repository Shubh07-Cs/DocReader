package com.example.docreader.reader

import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.docreader.data.FileType
import com.alamin5g.pdf.PDFView
import com.alamin5g.pdf.listener.OnErrorListener
import com.alamin5g.pdf.listener.OnLoadCompleteListener
import com.alamin5g.pdf.listener.OnPageChangeListener


import java.io.File

class PdfReaderEngine(private val parentFragment: Fragment) : ReaderEngine {

    private var pdfView: PDFView? = null

    private var currentSearchQuery: String = ""
    private var currentUri: Uri? = null

    override fun load(context: Context, uri: Uri, fileType: FileType, container: ViewGroup) {
        // Create PDFView instance
        pdfView = PDFView(context, null).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Add PDFView to container
        container.removeAllViews()
        container.addView(pdfView)




        // Store URI for text extraction
        currentUri = uri

        // Load PDF from URI
        pdfView?.fromUri(uri)
            ?.enableSwipe(true)
            ?.swipeHorizontal(false)
            ?.enableDoubletap(true)
            ?.defaultPage(0)
            ?.enableAnnotationRendering(false)
            ?.password(null)
            //.scrollHandle(DefaultScrollHandle(context)) // DefaultScrollHandle not found in com.alamin5g.pdf.scroll
            ?.enableAntialiasing(true)
            ?.spacing(10) // spacing between pages in dp
            ?.autoSpacing(false)
            ?.pageFitPolicy(PDFView.FitPolicy.WIDTH)
            ?.pageSnap(true)
            ?.pageFling(true)
            ?.setNightMode(false)
            ?.onLoad(OnLoadCompleteListener { nbPages ->
                // PDF loaded successfully
                Toast.makeText(context, "PDF loaded: $nbPages pages", Toast.LENGTH_SHORT).show()
            })
            ?.onPageChange(OnPageChangeListener { page, pageCount ->
                // Page changed - can be used for tracking current page
            })
            ?.onError(OnErrorListener { throwable ->
                // Handle error
                Toast.makeText(context, "Error loading PDF: ${throwable.message}", Toast.LENGTH_LONG).show()
                throwable.printStackTrace()
            })
            ?.load()
    }

    override fun search(query: String) {
        // Store search query
        currentSearchQuery = query
        
        // Note: Alamin5G PDF Viewer doesn't have built-in search UI
        // Search functionality would need to be implemented separately
        // For now, we'll just show a message
        if (query.isNotBlank()) {
            Toast.makeText(
                parentFragment.requireContext(),
                "Search functionality is being updated for the new PDF viewer",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun copyText(): Boolean {
        val context = parentFragment.context ?: return false
        val pdfView = pdfView ?: return false
        val uri = currentUri ?: return false

        // Show loading indicator
        Toast.makeText(context, "Extracting text...", Toast.LENGTH_SHORT).show()

        try {
            // Get current page
            val currentPage = pdfView.currentPage
            
            // Open PdfRenderer to render the page to a bitmap
            val fileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
            if (fileDescriptor != null) {
                val pdfRenderer = android.graphics.pdf.PdfRenderer(fileDescriptor)
                val page = pdfRenderer.openPage(currentPage)
                
                // create bitmap
                val width = page.width * 2 // Higher resolution for better OCR
                val height = page.height * 2
                val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                
                // render
                page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                
                // Close page and renderer
                page.close()
                pdfRenderer.close()
                fileDescriptor.close()
                
                // Process with ML Kit
                processImageForText(context, bitmap)
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Error extracting text: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        return false
    }

    private fun processImageForText(context: Context, bitmap: android.graphics.Bitmap) {
        val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)
        val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
            com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS
        )

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                showTextDialog(context, visionText.text)
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "Text recognition failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showTextDialog(context: Context, text: String) {
        val builder = androidx.appcompat.app.AlertDialog.Builder(context)
        builder.setTitle("Extracted Text")
        
        // Scrollable text view
        val scrollView = android.widget.ScrollView(context)
        val textView = android.widget.TextView(context)
        textView.text = text
        textView.setPadding(32, 32, 32, 32)
        textView.setTextIsSelectable(true) // Enable system selection
        scrollView.addView(textView)
        builder.setView(scrollView)

        builder.setPositiveButton("Copy All") { _, _ ->
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("PDF Text", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Text copied to clipboard", Toast.LENGTH_SHORT).show()
        }
        builder.setNegativeButton("Close", null)
        builder.show()
    }

    override fun onDestroy() {
        pdfView?.recycle()
        pdfView = null
    }
}