// Book.kt
package com.gnimble.typewriter.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import java.util.Date

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "subtitle")
    val subtitle: String = "",

    @ColumnInfo(name = "story_content")
    val storyContent: String = "",  // Keep for backward compatibility

    @ColumnInfo(name = "formatted_content")
    val formattedContent: String? = null,  // New field for HTML/JSON formatted content

    @ColumnInfo(name = "content_format")
    val contentFormat: ContentFormat = ContentFormat.PLAIN_TEXT,  // Track format type

    @ColumnInfo(name = "cover_path")
    val coverPath: String? = null,

    @ColumnInfo(name = "last_edited")
    val lastEdited: Date = Date(),

    @ColumnInfo(name = "created_date")
    val createdDate: Date = Date()
)

enum class ContentFormat {
    PLAIN_TEXT,
    HTML,
    JSON
}

// Extension functions for Book
fun Book.getDisplayContent(): String {
    return when (contentFormat) {
        ContentFormat.PLAIN_TEXT -> storyContent
        ContentFormat.HTML, ContentFormat.JSON -> formattedContent ?: storyContent
    }
}

fun Book.hasFormatting(): Boolean {
    return contentFormat != ContentFormat.PLAIN_TEXT && !formattedContent.isNullOrEmpty()
}