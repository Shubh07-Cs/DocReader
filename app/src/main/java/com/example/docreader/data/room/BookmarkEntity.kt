package com.example.docreader.data.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val uri: String,
    val isBookmarked: Boolean,
    val isManuallyImported: Boolean
)
