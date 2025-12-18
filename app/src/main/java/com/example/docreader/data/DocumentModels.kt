package com.example.docreader.data

import android.net.Uri

enum class FileType {
    PDF, WORD, SLIDES, SHEETS, TEXT, EPUB, UNKNOWN
}

data class DocumentEntity(
    val uri: String,
    val name: String,
    val size: Long,
    val dateModified: Long,
    val type: FileType
)
