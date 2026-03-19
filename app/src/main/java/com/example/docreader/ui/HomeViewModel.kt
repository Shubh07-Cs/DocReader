package com.example.docreader.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.docreader.data.DocumentEntity
import com.example.docreader.data.DocumentRepository
import com.example.docreader.data.FileType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DocumentRepository(application)
    
    private val allDocuments = mutableListOf<DocumentEntity>()
    private val _uiState = MutableLiveData<List<DocumentItem>>()
    val uiState: LiveData<List<DocumentItem>> = _uiState

    private var currentFilter: FileType? = null
    private var currentSearchQuery: String = ""
    private var showOnlyBookmarked: Boolean = false

    private var loadJob: kotlinx.coroutines.Job? = null

    fun loadDocuments() {
        // Cancel any previous load to prevent race-condition duplicates
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val docs = repository.getAllDocuments()
            allDocuments.clear()
            // Final safety-net dedup by name+size
            val seen = mutableSetOf<String>()
            docs.forEach { doc ->
                val key = "${doc.name}_${doc.size}"
                if (seen.add(key)) {
                    allDocuments.add(doc)
                }
            }
            applyFilters()
        }
    }
    
    fun importDocuments(uris: List<Uri>) {
        viewModelScope.launch {
            uris.forEach { uri ->
                // Check by URI and also by name+size to prevent the same file appearing twice
                val uriStr = uri.toString()
                if (allDocuments.none { it.uri == uriStr }) {
                    repository.addDocument(uri)?.let { newDoc ->
                        // Also check if a doc with same name+size already exists (e.g. from MediaStore)
                        val duplicate = allDocuments.find { it.name == newDoc.name && it.size == newDoc.size }
                        if (duplicate == null) {
                            allDocuments.add(0, newDoc)
                        }
                    }
                }
            }
            applyFilters()
        }
    }

    fun setFilter(filter: FileType?) {
        currentFilter = filter
        applyFilters()
    }

    fun searchDocuments(query: String) {
        currentSearchQuery = query
        applyFilters()
    }
    
    fun toggleBookmarkFilter(showOnly: Boolean) {
        showOnlyBookmarked = showOnly
        applyFilters()
    }
    
    fun toggleBookmarkStatus(uri: String) {
        val doc = allDocuments.find { it.uri == uri }
        doc?.let {
            it.isBookmarked = !it.isBookmarked
            repository.setBookmarkStatus(uri, it.isBookmarked)
            applyFilters() // Refresh list
        }
    }

    // ── Sorting ──────────────────────────────────────────────────────────────

    enum class SortField  { DATE, NAME, SIZE }
    enum class SortDirection { ASC, DESC }

    private var currentSortField: SortField = SortField.DATE
    private var currentSortDirection: SortDirection = SortDirection.DESC

    fun setSortField(field: SortField) {
        currentSortField = field
        applyFilters()
    }

    fun setSortDirection(direction: SortDirection) {
        currentSortDirection = direction
        applyFilters()
    }

    fun getCurrentSortField(): SortField = currentSortField
    fun getCurrentSortDirection(): SortDirection = currentSortDirection

    // ─────────────────────────────────────────────────────────────────────────

    private fun applyFilters() {
        var result = allDocuments.toList()
        
        if (showOnlyBookmarked) {
            result = result.filter { it.isBookmarked }
        }
        
        if (currentFilter != null) {
            result = result.filter { it.type == currentFilter }
        }
        
        if (currentSearchQuery.isNotEmpty()) {
            result = result.filter { 
                it.name.contains(currentSearchQuery, ignoreCase = true) 
            }
        }

        // Apply sort last — combine field + direction for all 6 possible combinations
        result = when (currentSortField) {
            SortField.DATE -> if (currentSortDirection == SortDirection.DESC)
                result.sortedByDescending { it.dateModified }
            else
                result.sortedBy { it.dateModified }
            SortField.NAME -> if (currentSortDirection == SortDirection.ASC)
                result.sortedBy { it.name.lowercase() }
            else
                result.sortedByDescending { it.name.lowercase() }
            SortField.SIZE -> if (currentSortDirection == SortDirection.DESC)
                result.sortedByDescending { it.size }
            else
                result.sortedBy { it.size }
        }

        _uiState.value = result.map { it.toUiItem() }
    }

    private fun DocumentEntity.toUiItem(): DocumentItem {
        return DocumentItem(
            uri = this.uri,
            name = this.name,
            size = formatSize(this.size),
            date = formatDate(this.dateModified),
            mimeType = "", 
            extension = this.type.name,
            isBookmarked = this.isBookmarked
        )
    }

    private fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(Locale.getDefault(), "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    private fun formatDate(timestamp: Long): String {
        return SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}