// Book.kt
package com.gnimble.typewriter.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val subtitle: String = "",
    val coverPath: String? = null,
    val storyContent: String = "",
    val lastEdited: Date = Date(),
    val createdAt: Date = Date()
)