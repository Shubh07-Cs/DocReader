package com.example.docreader.reader

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.example.docreader.data.FileType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class TextReaderEngine : ReaderEngine {

    private var textView: TextView? = null
    private var loadJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var loadingCallback: ((Boolean) -> Unit)? = null

    override fun load(context: Context, uri: Uri, fileType: FileType, container: ViewGroup) {
        // 1. Create Layout
        val scrollView = NestedScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
        }

        // Create local variable to ensure non-null usage
        val tv = TextView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val padding = dpToPx(context, 16f).toInt()
            setPadding(padding, padding, padding, padding)
            setTextIsSelectable(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            
            // Set text color to theme's onSurface color
            val typedValue = TypedValue()
            context.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, typedValue, true)
            setTextColor(typedValue.data)
        }
        
        // Assign to class property
        this.textView = tv

        scrollView.addView(tv)
        container.removeAllViews()
        container.addView(scrollView)

        loadingCallback?.invoke(true)

        // 2. Load Content Asynchronously
        loadJob = scope.launch {
            val content = readTextFromUri(context, uri, fileType)
            
            if (fileType == FileType.MARKDOWN) {
                try {
                    val markwon = io.noties.markwon.Markwon.create(context)
                    markwon.setMarkdown(textView!!, content)
                } catch (e: Exception) {
                    textView?.text = content
                }
            } else if (fileType == FileType.JSON) {
                textView?.typeface = android.graphics.Typeface.MONOSPACE
                try {
                    if (content.trim().startsWith("[")) {
                        textView?.text = org.json.JSONArray(content).toString(4)
                    } else {
                        textView?.text = org.json.JSONObject(content).toString(4)
                    }
                } catch (e: Exception) {
                    textView?.text = content
                }
            } else if (fileType == FileType.XML) {
                textView?.typeface = android.graphics.Typeface.MONOSPACE
                textView?.text = content
            } else {
                textView?.text = content
            }
            loadingCallback?.invoke(false)
        }
    }

    private suspend fun readTextFromUri(context: Context, uri: Uri, fileType: FileType): String = withContext(Dispatchers.IO) {
        try {
            if (fileType == FileType.ODT || fileType == FileType.ODP) {
                return@withContext extractOpenDocumentText(context, uri)
            }
            
            val stringBuilder = StringBuilder()
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) {
                        stringBuilder.append(line).append("\n")
                        line = reader.readLine()
                    }
                }
            }
            var result = stringBuilder.toString()
            if (fileType == FileType.RTF) {
                result = stripRtf(result)
            }
            return@withContext result
        } catch (e: Exception) {
            return@withContext "Error loading document: ${e.message}"
        }
    }

    private fun stripRtf(rtf: String): String {
        var text = rtf
        // Basic RTF control word stripping
        text = text.replace(Regex("\\\\[a-zA-Z]+(-?\\d+)? ?"), "")
        text = text.replace("{", "").replace("}", "")
        text = text.replace("\\\\", "\\")
        return text.trim()
    }

    private fun extractOpenDocumentText(context: Context, uri: Uri): String {
        val tempFile = java.io.File(context.cacheDir, "temp_odt_${System.currentTimeMillis()}")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        val sb = StringBuilder()
        try {
            java.util.zip.ZipFile(tempFile).use { zip ->
                val entry = zip.getEntry("content.xml")
                if (entry != null) {
                    zip.getInputStream(entry).use { input ->
                        val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance()
                        val parser = factory.newPullParser()
                        parser.setInput(input, "UTF-8")
                        var eventType = parser.eventType
                        var inTextElement = false
                        while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                            if (eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                                if (parser.name == "text:p" || parser.name == "text:h") {
                                    inTextElement = true
                                }
                            } else if (eventType == org.xmlpull.v1.XmlPullParser.TEXT) {
                                if (inTextElement) {
                                    sb.append(parser.text)
                                }
                            } else if (eventType == org.xmlpull.v1.XmlPullParser.END_TAG) {
                                if (parser.name == "text:p" || parser.name == "text:h") {
                                    sb.append("\n\n")
                                    inTextElement = false
                                }
                            }
                            eventType = parser.next()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            sb.append("Error parsing OpenDocument: ${e.message}")
        }
        tempFile.delete()
        return sb.toString()
    }

    override fun onDestroy() {
        loadJob?.cancel()
        textView = null
    }

    override fun search(query: String) {
        val view = textView ?: return
        val fullText = view.text.toString()
        
        if (query.isEmpty()) {
            // Clear highlights if query is empty
            view.text = fullText 
            return
        }

        val spannable = SpannableString(fullText)
        var index = fullText.indexOf(query, ignoreCase = true)
        
        // Basic highlighting logic
        val highlightColor = Color.YELLOW // Could be themed
        
        while (index >= 0) {
            spannable.setSpan(
                BackgroundColorSpan(highlightColor),
                index,
                index + query.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            index = fullText.indexOf(query, index + query.length, ignoreCase = true)
        }
        
        view.text = spannable
    }

    override fun setOnLoadingStateListener(callback: (Boolean) -> Unit) {
        this.loadingCallback = callback
    }

    private fun dpToPx(context: Context, dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        )
    }
}