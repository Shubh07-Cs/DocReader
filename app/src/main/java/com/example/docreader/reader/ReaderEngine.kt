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

    /**
     * Extracts text from the current page/document.
     * @return true if extraction started, false if not supported
     */
    fun copyText(): Boolean {
        return false
    }

    /**
     * Toggles dark mode for the reader engine.
     * @param isDarkMode True for dark mode, false for light mode.
     */
    fun setDarkMode(isDarkMode: Boolean) {
        // Optional implementation
    }

    /**
     * Finds the next match in a global search.
     */
    fun findNext() {}

    /**
     * Finds the previous match in a global search.
     */
    fun findPrevious() {}

    /**
     * Sets a callback to receive search progress/results.
     * @param callback A function called with (currentIndex, totalMatches).
     */
    fun setSearchCallback(callback: (current: Int, total: Int) -> Unit) {}

    /**
     * Sets a callback to receive loading state updates.
     * @param callback A function called with (isLoading).
     */
    fun setOnLoadingStateListener(callback: (isLoading: Boolean) -> Unit) {}
}