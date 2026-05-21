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
    private var loadingCallback: ((Boolean) -> Unit)? = null

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
            settings.javaScriptEnabled = true
            settings.textZoom = 100 // Prevent Android user text scaling from breaking absolute positioning
        }
        
        container.removeAllViews()
        container.addView(webView)

        loadingCallback?.invoke(true)

        loadJob = scope.launch {
            val htmlContent = withContext(Dispatchers.IO) {
                val fileName = getFileName(context, uri).lowercase()
                val isCsv = fileName.endsWith(".csv")
                val isDoc = fileName.endsWith(".doc")
                val isDocx = fileName.endsWith(".docx")
                val isXls = fileName.endsWith(".xls")
                val isXlsx = fileName.endsWith(".xlsx")
                val isPpt = fileName.endsWith(".ppt") && !fileName.endsWith(".pptx")
                val isPptx = fileName.endsWith(".pptx")
                val isHtml = fileName.endsWith(".html") || fileName.endsWith(".htm")
                val isOds = fileName.endsWith(".ods")
                val isEpub = fileName.endsWith(".epub")
                val isMobi = fileName.endsWith(".mobi") || fileName.endsWith(".azw3") || fileName.endsWith(".prc")
                
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
                } else if (isPpt) {
                    try {
                        val inputStream = context.contentResolver.openInputStream(uri)
                        if (inputStream != null) {
                            PptToHtmlConverter.convertPpt(context, inputStream)
                        } else {
                            "<html><body>Could not open file stream.</body></html>"
                        }
                    } catch (e: Exception) {
                        "<html><body>Error parsing PPT: ${e.message}</body></html>"
                    }
                } else if (isPptx) {
                    // Unzip PPTX so images can be referenced by file:// baseUrl
                    unzippedDir = OoxmlParser.unzip(context, uri)
                    val rootDir = unzippedDir
                    if (rootDir != null) {
                        PptToHtmlConverter.convertPptx(rootDir)
                    } else {
                        "<html><body>Failed to read presentation. File might be corrupted.</body></html>"
                    }
                } else if (isHtml) {
                    try {
                        val inputStream = context.contentResolver.openInputStream(uri)
                        if (inputStream != null) {
                            java.io.BufferedReader(java.io.InputStreamReader(inputStream)).use { it.readText() }
                        } else {
                            "<html><body>Could not open HTML file.</body></html>"
                        }
                    } catch (e: Exception) {
                        "<html><body>Error loading HTML: ${e.message}</body></html>"
                    }
                } else if (isEpub) {
                    try {
                        unzippedDir = OoxmlParser.unzip(context, uri)
                        val rootDir = unzippedDir
                        if (rootDir != null) {
                            val sb = StringBuilder()
                            sb.append("<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"><style>body { font-family: sans-serif; padding: 16px; line-height: 1.6; } img { max-width: 100%; height: auto; }</style></head><body>")
                            val htmlFiles = mutableListOf<File>()
                            rootDir.walkTopDown().forEach { file ->
                                if (file.isFile && (file.name.endsWith(".html") || file.name.endsWith(".xhtml"))) {
                                    htmlFiles.add(file)
                                }
                            }
                            htmlFiles.sortedBy { it.name }.forEach { file ->
                                val content = file.readText()
                                val bodyRegex = Regex("<body[^>]*>(.*?)</body>", RegexOption.DOT_MATCHES_ALL)
                                val match = bodyRegex.find(content)
                                if (match != null) {
                                    sb.append(match.groupValues[1])
                                } else {
                                    sb.append(content)
                                }
                                sb.append("<hr/>")
                            }
                            sb.append("</body></html>")
                            sb.toString()
                        } else {
                            "<html><body>Failed to read EPUB.</body></html>"
                        }
                    } catch (e: Exception) {
                        "<html><body>Error parsing EPUB: ${e.message}</body></html>"
                    }
                } else if (isOds) {
                    try {
                        unzippedDir = OoxmlParser.unzip(context, uri)
                        val rootDir = unzippedDir
                        if (rootDir != null) {
                            val contentXml = File(rootDir, "content.xml")
                            if (contentXml.exists()) {
                                val sb = StringBuilder()
                                sb.append("<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"><style>body { font-family: sans-serif; padding: 16px; } table { border-collapse: collapse; width: 100%; } th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }</style></head><body>")
                                
                                val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance()
                                val parser = factory.newPullParser()
                                parser.setInput(java.io.FileInputStream(contentXml), "UTF-8")
                                var eventType = parser.eventType
                                var inTable = false
                                var inRow = false
                                var inCell = false
                                
                                while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                                    when (eventType) {
                                        org.xmlpull.v1.XmlPullParser.START_TAG -> {
                                            if (parser.name == "table:table") { sb.append("<table>"); inTable = true }
                                            if (parser.name == "table:table-row") { sb.append("<tr>"); inRow = true }
                                            if (parser.name == "table:table-cell") { sb.append("<td>"); inCell = true }
                                        }
                                        org.xmlpull.v1.XmlPullParser.TEXT -> {
                                            if (inCell) sb.append(parser.text)
                                        }
                                        org.xmlpull.v1.XmlPullParser.END_TAG -> {
                                            if (parser.name == "table:table") { sb.append("</table><br/>"); inTable = false }
                                            if (parser.name == "table:table-row") { sb.append("</tr>"); inRow = false }
                                            if (parser.name == "table:table-cell") { sb.append("</td>"); inCell = false }
                                        }
                                    }
                                    eventType = parser.next()
                                }
                                sb.append("</body></html>")
                                sb.toString()
                            } else {
                                "<html><body>No content.xml found in ODS.</body></html>"
                            }
                        } else {
                            "<html><body>Failed to read ODS.</body></html>"
                        }
                    } catch (e: Exception) {
                        "<html><body>Error parsing ODS: ${e.message}</body></html>"
                    }
                } else if (isMobi) {
                    "<html><body style='padding:24px;font-family:sans-serif;'><h3>MOBI / Kindle Format</h3><p>This format requires specialized reading layers. Native support will be fully expanded in future updates.</p></body></html>"
                } else {
                    "<html><body>Unsupported file format.</body></html>"
                }
            }
            
            val baseUrl = if (unzippedDir != null) "file://${unzippedDir!!.absolutePath}/" else null
            
            webView?.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    loadingCallback?.invoke(false)
                }
            }
            
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

    override fun setOnLoadingStateListener(callback: (Boolean) -> Unit) {
        this.loadingCallback = callback
    }
}