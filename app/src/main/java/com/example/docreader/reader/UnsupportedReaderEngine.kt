package com.example.docreader.reader

import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import android.widget.TextView
import com.example.docreader.data.FileType

class UnsupportedReaderEngine : ReaderEngine {
    override fun load(context: Context, uri: Uri, fileType: FileType, container: ViewGroup) {
        val textView = TextView(context).apply {
            text = "Unsupported file format."
            textSize = 16f
            setPadding(32, 32, 32, 32)
        }
        container.removeAllViews()
        container.addView(textView)
    }

    override fun onDestroy() { }

    override fun search(query: String) { }
}