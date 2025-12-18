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
import java.io.File

class OfficeReaderEngine : ReaderEngine {

    private var webView: WebView? = null
    private var loadJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var unzippedDir: File? = null

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
            
            // Allow access to local files (needed for images extracted to cache)
            settings.allowFileAccess = true 
        }
        
        container.removeAllViews()
        container.addView(webView)

        // Load Content Asynchronously
        loadJob = scope.launch {
            try {
                // 1. Unzip to cache first (Robust way)
                unzippedDir = withContext(Dispatchers.IO) {
                    OoxmlParser.unzip(context, uri)
                }

                // 2. Parse content from the unzipped directory
                val rootDir = unzippedDir
                if (rootDir != null) {
                    val htmlContent = withContext(Dispatchers.IO) {
                        when (fileType) {
                            FileType.SHEETS -> OoxmlParser.parseXlsx(rootDir)
                            FileType.SLIDES -> OoxmlParser.parsePptx(rootDir)
                            else -> OoxmlParser.parseDocx(rootDir)
                        }
                    }
                    
                    // 3. Load with Base URL pointing to the unzipped directory
                    val baseUrl = "file://${rootDir.absolutePath}/"
                    webView?.loadDataWithBaseURL(baseUrl, htmlContent, "text/html", "UTF-8", null)
                } else {
                    webView?.loadData("<html><body>Failed to read document structure.</body></html>", "text/html", "UTF-8")
                }
            } catch (e: Exception) {
                webView?.loadData("<html><body>Error: ${e.message}</body></html>", "text/html", "UTF-8")
            }
        }
    }

    override fun onDestroy() {
        loadJob?.cancel()
        webView?.destroy()
        webView = null
        
        // Cleanup cache on destroy to save space
        scope.launch(Dispatchers.IO) {
            unzippedDir?.deleteRecursively()
        }
    }

    override fun search(query: String) {
        webView?.findAllAsync(query)
    }
}