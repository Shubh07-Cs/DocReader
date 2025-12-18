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

        // 2. Load Content Asynchronously
        loadJob = scope.launch {
            val content = readTextFromUri(context, uri)
            textView?.text = content
        }
    }

    private suspend fun readTextFromUri(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        try {
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
            return@withContext stringBuilder.toString()
        } catch (e: Exception) {
            return@withContext "Error loading document: ${e.message}"
        }
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

    private fun dpToPx(context: Context, dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        )
    }
}