package com.example.docreader.reader

import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebView
import com.example.docreader.data.FileType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OfficeReaderEngine : ReaderEngine {

    private var webView: WebView? = null
    private var loadJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun load(context: Context, uri: Uri, fileType: FileType, container: ViewGroup) {
        // Setup WebView
        webView = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
        }
        
        container.removeAllViews()
        container.addView(webView)

        // Load Content Asynchronously
        loadJob = scope.launch {
            val htmlContent = withContext(Dispatchers.IO) {
                // Use the passed fileType
                when (fileType) {
                    FileType.SHEETS -> OoxmlParser.parseXlsx(context, uri)
                    FileType.SLIDES -> OoxmlParser.parsePptx(context, uri)
                    else -> OoxmlParser.parseDocx(context, uri) // Default to Docx
                }
            }
            webView?.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
        }
    }

    override fun onDestroy() {
        loadJob?.cancel()
        webView?.destroy()
        webView = null
    }

    override fun search(query: String) {
        webView?.findAllAsync(query)
    }
}