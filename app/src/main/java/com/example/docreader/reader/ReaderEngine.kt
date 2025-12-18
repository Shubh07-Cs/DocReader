package com.example.docreader.reader

import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import com.example.docreader.data.FileType

interface ReaderEngine {
    /**
     * Loads the document content into the provided container.
     * @param context Context for resource access
     * @param uri URI of the document to load
     * @param fileType The type of the file being loaded
     * @param container The ViewGroup where the document content should be rendered
     */
    fun load(context: Context, uri: Uri, fileType: FileType, container: ViewGroup)

    /**
     * Cleans up resources when the reader is destroyed.
     */
    fun onDestroy()

    /**
     * Searches for text within the loaded document.
     * @param query The text to search for
     */
    fun search(query: String)
}