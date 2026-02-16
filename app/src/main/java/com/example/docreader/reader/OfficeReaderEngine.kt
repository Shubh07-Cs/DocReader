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
                val isCsv = fileName.endsWith(".csv")
                val isDoc = fileName.endsWith(".doc")
                val isDocx = fileName.endsWith(".docx")
                val isXls = fileName.endsWith(".xls")
                val isXlsx = fileName.endsWith(".xlsx")
                val isLegacy = fileName.endsWith(".ppt") // Only PPT left in generic legacy check
                
                if (isCsv) {
                   try {
                       val inputStream = context.contentResolver.openInputStream(uri)
                       if (inputStream != null) {
                           CsvParser.parse(inputStream)
                       } else {
                           "<html><body>Could not open file stream.</body></html>"
                       }
                   } catch (e: Exception) {
                       "<html><body>Error parsing CSV: ${e.message}</body></html>"
                   }
                } else if (isDoc) {
                    try {
                        val inputStream = context.contentResolver.openInputStream(uri)
                        if (inputStream != null) {
                            WordToHtmlConverter.convertDoc(context, inputStream)
                        } else {
                            "<html><body>Could not open file stream.</body></html>"
                        }
                    } catch (e: Exception) {
                        "<html><body>Error parsing DOC: ${e.message}</body></html>"
                    }
                } else if (isDocx) {
                     try {
                        val inputStream = context.contentResolver.openInputStream(uri)
                        if (inputStream != null) {
                            WordToHtmlConverter.convertDocx(context, inputStream)
                        } else {
                            "<html><body>Could not open file stream.</body></html>"
                        }
                    } catch (e: Exception) {
                        "<html><body>Error parsing DOCX: ${e.message}</body></html>"
                    }
                } else if (isXls) {
                    try {
                        val inputStream = context.contentResolver.openInputStream(uri)
                        if (inputStream != null) {
                            ExcelToHtmlConverter.convertXls(context, inputStream)
                        } else {
                            "<html><body>Could not open file stream.</body></html>"
                        }
                    } catch (e: Exception) {
                        "<html><body>Error parsing XLS: ${e.message}</body></html>"
                    }
                } else if (isXlsx) {
                    try {
                        val inputStream = context.contentResolver.openInputStream(uri)
                        if (inputStream != null) {
                            ExcelToHtmlConverter.convertXlsx(context, inputStream)
                        } else {
                            "<html><body>Could not open file stream.</body></html>"
                        }
                    } catch (e: Exception) {
                        "<html><body>Error parsing XLSX: ${e.message}</body></html>"
                    }
                } else if (isLegacy) {
                    try {
                        val inputStream = context.contentResolver.openInputStream(uri)
                        if (inputStream != null) {
                            LegacyOoxmlParser.parsePpt(inputStream)
                        } else {
                            "<html><body>Could not open file stream.</body></html>"
                        }
                    } catch (e: Exception) {
                        "<html><body>Error parsing legacy PPT: ${e.message}</body></html>"
                    }
                } else {
                    unzippedDir = OoxmlParser.unzip(context, uri)
                    val rootDir = unzippedDir
                    if (rootDir != null) {
                        when (fileType) {
                            com.example.docreader.data.FileType.SLIDES -> OoxmlParser.parsePptx(rootDir)
                            else -> "<html><body>Unsupported file format or corrupted file.</body></html>"
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