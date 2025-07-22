// BookRepository.kt
package com.gnimble.typewriter.data

import androidx.lifecycle.LiveData
import java.util.Date

class BookRepository(private val bookDao: BookDao) {
    val allBooks: LiveData<List<Book>> = bookDao.getAllBooks()

    suspend fun insert(book: Book): Long {
        return bookDao.insertBook(book)
    }

    suspend fun update(book: Book) {
        bookDao.updateBook(book)
    }

    suspend fun delete(book: Book) {
        bookDao.deleteBook(book)
    }

    suspend fun getBook(bookId: Long): Book? {
        return bookDao.getBook(bookId)
    }

    suspend fun updateLastEdited(bookId: Long) {
        bookDao.updateLastEdited(bookId, Date())
    }
}