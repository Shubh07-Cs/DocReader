package com.example.docreader.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.docreader.data.FileType
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PdfReaderEngine(private val parentFragment: Fragment) : ReaderEngine {

    private var document: PDDocument? = null
    private var loadJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var textContentByPage = mutableMapOf<Int, String>()
    private var renderedViews = mutableMapOf<Int, TextView>()
    private var scrollView: ScrollView? = null

    override fun load(context: Context, uri: Uri, fileType: FileType, container: ViewGroup) {
        PDFBoxResourceLoader.init(context)
        
        scrollView = ScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val linearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        scrollView?.addView(linearLayout)
        container.removeAllViews()
        container.addView(scrollView)

        loadJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    document = PDDocument.load(inputStream)
                }

                document?.let { doc ->
                    val textStripper = PDFTextStripper()
                    for (i in 0 until doc.numberOfPages) {
                        textStripper.startPage = i + 1
                        textStripper.endPage = i + 1
                        val text = textStripper.getText(doc)
                        textContentByPage[i] = text
                        
                        // Render page as a TextView
                        withContext(Dispatchers.Main) {
                            val textView = createTextViewForPage(context, text, i)
                            renderedViews[i] = textView
                            linearLayout.addView(textView)
                        }
                    }
                }
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    private fun createTextViewForPage(context: Context, text: String, pageNum: Int): TextView {
        return TextView(context).apply {
            this.text = text
            setTextIsSelectable(true)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply{
                val margin = (16 * context.resources.displayMetrics.density).toInt()
                setMargins(margin, margin / 2, margin, margin / 2)
            }
        }
    }
    
    override fun search(query: String) {
        if (query.isEmpty()) {
            // Clear highlights
            renderedViews.forEach { (pageNum, textView) ->
                textView.text = textContentByPage[pageNum]
            }
            return
        }

        var firstMatchView: View? = null
        renderedViews.forEach { (pageNum, textView) ->
            val originalText = textContentByPage[pageNum] ?: ""
            val spannable = SpannableString(originalText)
            
            var index = originalText.indexOf(query, ignoreCase = true)
            while (index >= 0) {
                spannable.setSpan(
                    BackgroundColorSpan(0xFFFFFF00.toInt()), // Yellow
                    index,
                    index + query.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                if (firstMatchView == null) {
                    firstMatchView = textView
                }
                index = originalText.indexOf(query, index + query.length, ignoreCase = true)
            }
            textView.text = spannable
        }

        // Scroll to the first match
        firstMatchView?.let { view ->
            scrollView?.post {
                scrollView?.smoothScrollTo(0, view.top)
            }
        }
    }

    override fun onDestroy() {
        loadJob?.cancel()
        document?.close()
    }
}