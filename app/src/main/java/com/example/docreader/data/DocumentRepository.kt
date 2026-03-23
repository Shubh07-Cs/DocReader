package com.example.docreader.data

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import com.example.docreader.data.room.AppDatabase
import com.example.docreader.data.room.BookmarkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DocumentRepository(private val context: Context) {

    private val bookmarkDao = AppDatabase.getDatabase(context).bookmarkDao()

    suspend fun getAllDocuments(): List<DocumentEntity> = withContext(Dispatchers.IO) {
        val allBookmarks = bookmarkDao.getAllBookmarks()
        val bookmarkedUris = allBookmarks.filter { it.isBookmarked }.map { it.uri }.toSet()
        val importedUris = allBookmarks.filter { it.isManuallyImported }.map { it.uri }.toSet()

        val scannedDocs = scanMediaStore(bookmarkedUris)
        val importedDocs = getManuallyImportedDocuments(importedUris, bookmarkedUris)

        // De-duplicate using a combination of name+size as primary key and URI as secondary.
        val combinedDocs = mutableMapOf<String, DocumentEntity>()
        val seenUris = mutableSetOf<String>()

        // Add all scanned docs first.
        scannedDocs.forEach { doc ->
            val key = "${doc.name}_${doc.size}"
            if (!seenUris.contains(doc.uri)) {
                seenUris.add(doc.uri)
                combinedDocs[key] = doc
            }
        }

        // Add imported docs only if they don't already exist.
        // If a name_size match exists, merge bookmark status (keep true if either is bookmarked).
        importedDocs.forEach { doc ->
            val key = "${doc.name}_${doc.size}"
            if (seenUris.contains(doc.uri)) {
                return@forEach
            }
            seenUris.add(doc.uri)
            val existing = combinedDocs[key]
            if (existing != null) {
                if (doc.isBookmarked && !existing.isBookmarked) {
                    existing.isBookmarked = true
                }
            } else {
                combinedDocs[key] = doc
            }
        }

        return@withContext combinedDocs.values.sortedByDescending { it.dateModified }
    }

    private fun scanMediaStore(bookmarkedUris: Set<String>): List<DocumentEntity> {
        val documents = mutableListOf<DocumentEntity>()
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
            context.contentResolver.query(queryUri, projection, null, null, sortOrder)?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                    val name = it.getString(it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)) ?: "Unknown"
                    val size = it.getLong(it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE))
                    val dateModified = it.getLong(it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)) * 1000L
                    val mimeType = it.getString(it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE))

                    val contentUri = ContentUris.withAppendedId(queryUri, id)
                    val type = determineFileType(name, mimeType)

                    if (type != FileType.UNKNOWN && size > 0) {
                        documents.add(
                            DocumentEntity(
                                uri = contentUri.toString(),
                                name = name,
                                size = size,
                                dateModified = dateModified,
                                type = type,
                                isBookmarked = bookmarkedUris.contains(contentUri.toString())
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

    suspend fun addDocument(uri: Uri): DocumentEntity? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
             Log.e("DocumentRepository", "Failed to take persistable permission: ${e.message}")
        }

        // Pass empty set since bookmark status is queried natively below
        val doc = extractDocumentMetadata(uri, emptySet())
        if (doc != null) {
            val existing = bookmarkDao.getBookmark(uri.toString())
            if (existing != null) {
                bookmarkDao.insertBookmark(existing.copy(isManuallyImported = true))
            } else {
                bookmarkDao.insertBookmark(BookmarkEntity(uri.toString(), isBookmarked = false, isManuallyImported = true))
            }
        }
        return@withContext doc
    }

    private fun getManuallyImportedDocuments(importedUris: Set<String>, bookmarkedUris: Set<String>): List<DocumentEntity> {
        val validDocs = mutableListOf<DocumentEntity>()
        importedUris.forEach { uriString ->
            try {
                val uri = Uri.parse(uriString)
                if (isUriAccessible(uri)) {
                    extractDocumentMetadata(uri, bookmarkedUris)?.let { validDocs.add(it) }
                }
            } catch (e: Exception) { }
        }
        return validDocs
    }

    private fun isUriAccessible(uri: Uri): Boolean {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun extractDocumentMetadata(uri: Uri, bookmarkedUris: Set<String>): DocumentEntity? {
        var name = "Unknown"
        var size = 0L

        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)

                    if (nameIndex != -1) name = cursor.getString(nameIndex)
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
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
            dateModified = System.currentTimeMillis(),
            type = type,
            isBookmarked = bookmarkedUris.contains(uri.toString())
        )
    }

    suspend fun setBookmarkStatus(uri: String, isBookmarked: Boolean) = withContext(Dispatchers.IO) {
        val existing = bookmarkDao.getBookmark(uri)
        if (existing != null) {
            bookmarkDao.insertBookmark(existing.copy(isBookmarked = isBookmarked))
        } else {
            bookmarkDao.insertBookmark(BookmarkEntity(uri, isBookmarked = isBookmarked, isManuallyImported = false))
        }
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