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
    
    // Master list of all loaded documents
    private val allDocuments = mutableListOf<DocumentEntity>()
    
    // LiveData for UI
    private val _uiState = MutableLiveData<List<DocumentItem>>()
    val uiState: LiveData<List<DocumentItem>> = _uiState

    private var currentFilter: FileType? = null

    // We don't auto-load in init block anymore because we need to wait for permission
    
    fun loadDocuments() {
        viewModelScope.launch {
            val docs = repository.getAllDocuments()
            allDocuments.clear()
            allDocuments.addAll(docs)
            applyFilter()
        }
    }
    
    // Updated import function to support manual addition
    fun importDocuments(uris: List<Uri>) {
        viewModelScope.launch {
            val newDocs = mutableListOf<DocumentEntity>()
            for (uri in uris) {
                // Check if already exists in memory to avoid duplicates (Repository also handles persistence)
                if (allDocuments.none { it.uri == uri.toString() }) {
                    repository.addDocument(uri)?.let {
                        newDocs.add(it)
                    }
                }
            }
            if (newDocs.isNotEmpty()) {
                allDocuments.addAll(0, newDocs) // Add to top
                applyFilter()
            }
        }
    }

    fun setFilter(filter: FileType?) {
        if (currentFilter != filter) {
            currentFilter = filter
            applyFilter()
        }
    }

    private fun applyFilter() {
        val filteredList = if (currentFilter == null) {
            allDocuments
        } else {
            allDocuments.filter { it.type == currentFilter }
        }
        _uiState.value = filteredList.map { it.toUiItem() }
    }

    private fun DocumentEntity.toUiItem(): DocumentItem {
        return DocumentItem(
            uri = this.uri,
            name = this.name,
            size = formatSize(this.size),
            date = formatDate(this.dateModified),
            mimeType = "", 
            extension = this.type.name
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