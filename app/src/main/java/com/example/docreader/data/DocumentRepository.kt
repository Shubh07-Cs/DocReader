package com.example.docreader.data

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DocumentRepository(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("doc_reader_prefs", Context.MODE_PRIVATE)
    private val KEY_SAVED_URIS = "saved_uris"

    suspend fun getAllDocuments(): List<DocumentEntity> = withContext(Dispatchers.IO) {
        val scannedDocs = scanMediaStore()
        val importedDocs = getManuallyImportedDocuments()
        
        // Merge and remove duplicates based on URI string
        val allDocs = (scannedDocs + importedDocs).distinctBy { it.uri }
        
        // Sort by date modified desc
        return@withContext allDocs.sortedByDescending { it.dateModified }
    }

    // --- MediaStore Logic (Auto-Scan) ---
    private fun scanMediaStore(): List<DocumentEntity> {
        val documents = mutableListOf<DocumentEntity>()
        
        // On Android 13+, this query often returns nothing for non-media files 
        // without MANAGE_EXTERNAL_STORAGE, but we keep it for older devices/media files.
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE
        )

        val queryUri = MediaStore.Files.getContentUri("external")
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

        try {
            val cursor = context.contentResolver.query(
                queryUri,
                projection,
                null,
                null,
                sortOrder
            )

            cursor?.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val mimeColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)

                while (it.moveToNext()) {
                    val id = it.getLong(idColumn)
                    val name = it.getString(nameColumn) ?: "Unknown"
                    val size = it.getLong(sizeColumn)
                    val dateModified = it.getLong(dateColumn) * 1000L
                    val mimeType = it.getString(mimeColumn)

                    val contentUri = ContentUris.withAppendedId(queryUri, id)
                    val type = determineFileType(name, mimeType)

                    if (type != FileType.UNKNOWN && size > 0) {
                        documents.add(
                            DocumentEntity(
                                uri = contentUri.toString(),
                                name = name,
                                size = size,
                                dateModified = dateModified,
                                type = type
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DocumentRepository", "Error querying MediaStore", e)
        }
        return documents
    }

    // --- SAF Logic (Manual Import) ---
    suspend fun addDocument(uri: Uri): DocumentEntity? = withContext(Dispatchers.IO) {
        // 1. Persist Permission
        try {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: SecurityException) {
            Log.e("DocumentRepository", "Failed to take persistable permission: ${e.message}")
            // Proceed anyway; might be a temp permission valid for this session
        }

        // 2. Extract & Return
        val doc = extractDocumentMetadata(uri)
        if (doc != null) {
            saveUriToPrefs(uri.toString())
        }
        return@withContext doc
    }

    private fun getManuallyImportedDocuments(): List<DocumentEntity> {
        val savedUris = prefs.getStringSet(KEY_SAVED_URIS, emptySet()) ?: emptySet()
        val validDocs = mutableListOf<DocumentEntity>()
        val invalidUris = mutableListOf<String>()

        for (uriString in savedUris) {
            try {
                val uri = Uri.parse(uriString)
                if (isUriAccessible(uri)) {
                    val doc = extractDocumentMetadata(uri)
                    if (doc != null) {
                        validDocs.add(doc)
                    } else {
                        invalidUris.add(uriString)
                    }
                } else {
                    // Check if we have permission logic could act here, 
                    // but isUriAccessible usually covers it
                    invalidUris.add(uriString)
                }
            } catch (e: Exception) {
                invalidUris.add(uriString)
            }
        }
        
        // Cleanup invalid
        if (invalidUris.isNotEmpty()) {
            val updatedSet = savedUris.toMutableSet()
            updatedSet.removeAll(invalidUris)
            prefs.edit().putStringSet(KEY_SAVED_URIS, updatedSet).apply()
        }
        return validDocs
    }

    private fun isUriAccessible(uri: Uri): Boolean {
        return try {
            // Try to open a stream or query lightly
            context.contentResolver.query(uri, null, null, null, null)?.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun extractDocumentMetadata(uri: Uri): DocumentEntity? {
        var name = "Unknown"
        var size = 0L
        var lastModified = System.currentTimeMillis()

        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)

                    if (nameIndex != -1) name = cursor.getString(nameIndex)
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                    // Note: OpenableColumns doesn't give date usually
                }
            }
        } catch (e: Exception) {
            return null
        }

        val type = determineFileType(name, context.contentResolver.getType(uri))
        if (type == FileType.UNKNOWN) return null

        return DocumentEntity(
            uri = uri.toString(),
            name = name,
            size = size,
            dateModified = lastModified,
            type = type
        )
    }

    private fun saveUriToPrefs(uriString: String) {
        val currentSet = prefs.getStringSet(KEY_SAVED_URIS, emptySet())?.toMutableSet() ?: mutableSetOf()
        currentSet.add(uriString)
        prefs.edit().putStringSet(KEY_SAVED_URIS, currentSet).apply()
    }

    private fun determineFileType(fileName: String, mimeType: String?): FileType {
        val lowerName = fileName.lowercase()
        return when {
            lowerName.endsWith(".pdf") -> FileType.PDF
            lowerName.endsWith(".docx") || lowerName.endsWith(".doc") -> FileType.WORD
            lowerName.endsWith(".pptx") || lowerName.endsWith(".ppt") -> FileType.SLIDES
            lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls") -> FileType.SHEETS
            lowerName.endsWith(".txt") -> FileType.TEXT
            lowerName.endsWith(".epub") -> FileType.EPUB
            else -> FileType.UNKNOWN
        }
    }
}