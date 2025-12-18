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

    fun loadDocuments() {
        viewModelScope.launch {
            allDocuments.clear()
            allDocuments.addAll(repository.getAllDocuments())
            applyFilters()
        }
    }
    
    fun importDocuments(uris: List<Uri>) {
        viewModelScope.launch {
            uris.forEach { uri ->
                if (allDocuments.none { it.uri == uri.toString() }) {
                    repository.addDocument(uri)?.let { allDocuments.add(0, it) }
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