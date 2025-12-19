package com.example.docreader.reader

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
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
        webView = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.allowFileAccess = true 
        }
        
        container.removeAllViews()
        container.addView(webView)

        loadJob = scope.launch {
            val htmlContent = withContext(Dispatchers.IO) {
                val fileName = getFileName(context, uri).lowercase()
                val isLegacy = fileName.endsWith(".doc") || fileName.endsWith(".xls") || fileName.endsWith(".ppt")
                
                if (isLegacy) {
                    try {
                        val inputStream = context.contentResolver.openInputStream(uri)
                        if (inputStream != null) {
                            when {
                                fileName.endsWith(".doc") -> LegacyOoxmlParser.parseDoc(inputStream)
                                fileName.endsWith(".xls") -> LegacyOoxmlParser.parseXls(inputStream)
                                else -> LegacyOoxmlParser.parsePpt(inputStream)
                            }
                        } else {
                            "<html><body>Could not open file stream.</body></html>"
                        }
                    } catch (e: Exception) {
                        "<html><body>Error parsing legacy file: ${e.message}</body></html>"
                    }
                } else {
                    unzippedDir = OoxmlParser.unzip(context, uri)
                    val rootDir = unzippedDir
                    if (rootDir != null) {
                        when (fileType) {
                            FileType.SHEETS -> OoxmlParser.parseXlsx(rootDir)
                            FileType.SLIDES -> OoxmlParser.parsePptx(rootDir)
                            else -> OoxmlParser.parseDocx(rootDir)
                        }
                    } else {
                         "<html><body>Failed to read document structure. File might be corrupted.</body></html>"
                    }
                }
            }
            
            val baseUrl = if (unzippedDir != null) "file://${unzippedDir!!.absolutePath}/" else null
            webView?.loadDataWithBaseURL(baseUrl, htmlContent, "text/html", "UTF-8", null)
        }
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var name = ""
        val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    name = it.getString(index)
                }
            }
        }
        if (name.isEmpty()) {
            name = uri.path ?: ""
        }
        return name
    }

    override fun onDestroy() {
        loadJob?.cancel()
        webView?.destroy()
        webView = null
        
        scope.launch(Dispatchers.IO) {
            unzippedDir?.deleteRecursively()
        }
    }

    override fun search(query: String) {
        webView?.findAllAsync(query)
    }
}