// BookDao.kt
package com.gnimble.typewriter.data

import androidx.lifecycle.LiveData
import androidx.room.*
import androidx.room.Query
import java.util.Date

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastEdited DESC")
    fun getAllBooks(): LiveData<List<Book>>

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getBook(bookId: Long): Book?

    @Insert
    suspend fun insertBook(book: Book): Long

    @Update
    suspend fun updateBook(book: Book)

    @Delete
    suspend fun deleteBook(book: Book)

    @Query("UPDATE books SET lastEdited = :date WHERE id = :bookId")
    suspend fun updateLastEdited(bookId: Long, date: Date)
}