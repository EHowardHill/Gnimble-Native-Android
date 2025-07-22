// MainViewModel.kt
package com.gnimble.typewriter.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.gnimble.typewriter.data.AppDatabase
import com.gnimble.typewriter.data.Book
import com.gnimble.typewriter.data.BookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: BookRepository
    val allBooks: LiveData<List<Book>>

    init {
        val bookDao = AppDatabase.getDatabase(application).bookDao()
        repository = BookRepository(bookDao)
        allBooks = repository.allBooks
    }

    fun insert(book: Book, onComplete: (Long) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val bookId = repository.insert(book)
            withContext(Dispatchers.Main) {
                onComplete(bookId)
            }
        }
    }

    fun update(book: Book) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.update(book)
        }
    }

    fun delete(book: Book) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.delete(book)
        }
    }

    fun updateLastEdited(bookId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateLastEdited(bookId)
        }
    }

    suspend fun getBook(bookId: Long): Book? {
        return withContext(Dispatchers.IO) {
            repository.getBook(bookId)
        }
    }
}