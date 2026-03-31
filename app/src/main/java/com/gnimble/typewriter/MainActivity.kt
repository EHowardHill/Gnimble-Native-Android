package com.gnimble.typewriter

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.gnimble.typewriter.adapter.BookAdapter
import com.gnimble.typewriter.data.Book
import com.gnimble.typewriter.databinding.ActivityMainBinding
import com.gnimble.typewriter.viewmodel.MainViewModel
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var bookAdapter: BookAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        observeBooks()
        setupFabs()
    }

    private fun setupRecyclerView() {
        bookAdapter = BookAdapter(
            onBookClick = { book ->
                openBook(book)
            },
            onRenameBook = { book, newTitle ->
                renameBook(book, newTitle)
            },
            onDeleteBook = { book ->
                deleteBook(book)
            }
        )

        binding.booksRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = bookAdapter
        }
    }

    private fun observeBooks() {
        viewModel.allBooks.observe(this) { books ->
            bookAdapter.submitList(books)

            if (books.isEmpty()) {
                binding.emptyView.visibility = View.VISIBLE
                binding.booksRecyclerView.visibility = View.GONE
            } else {
                binding.emptyView.visibility = View.GONE
                binding.booksRecyclerView.visibility = View.VISIBLE
            }
        }
    }

    private fun setupFabs() {
        // 1. Upload Button (Left)
        binding.fabUpload.setOnClickListener {
            val intent = Intent(this, UploadActivity::class.java)
            startActivity(intent)
        }

        // 2. Add Book Button (Center)
        binding.fabAddBook.setOnClickListener {
            showNewBookDialog()
        }

        // Opens Android system settings — intentional since Gnimble is used as a launcher.
        binding.fabSettings.setOnClickListener {
            val intent = Intent(Settings.ACTION_SETTINGS)
            startActivity(intent)
        }

        // 4. About page
        binding.mainLogo.setOnClickListener {
            val intent = Intent(this, AboutActivity::class.java)
            startActivity(intent)
        }
    }

    private fun showNewBookDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_new_book, null)
        val titleInput = dialogView.findViewById<TextInputEditText>(R.id.title_input)
        val titleLayout = dialogView.findViewById<TextInputLayout>(R.id.title_layout)

        AlertDialog.Builder(this)
            .setTitle("Create New Story")
            .setView(dialogView)
            .setPositiveButton("Create") { dialog, _ ->
                val title = titleInput.text?.toString()?.trim()
                if (!title.isNullOrEmpty()) {
                    createNewBook(title)
                    dialog.dismiss()
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .apply {
                show()

                getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false

                titleInput.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        val text = s?.toString()?.trim()
                        val isValid = !text.isNullOrEmpty()
                        getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = isValid
                        titleLayout.error = if (isValid) null else "Title is required"
                    }
                })
            }
    }

    private fun createNewBook(title: String) {
        val newBook = Book(
            title = title,
            subtitle = "",
            storyContent = ""
        )

        viewModel.insert(newBook) { bookId ->
            runOnUiThread {
                val intent = Intent(this, EditorActivity::class.java).apply {
                    putExtra("book_id", bookId)
                    putExtra("book_title", title)
                }
                startActivity(intent)
            }
        }
    }

    private fun openBook(book: Book) {
        val intent = Intent(this, EditorActivity::class.java).apply {
            putExtra("book_id", book.id)
            putExtra("book_title", book.title)
        }
        startActivity(intent)
    }

    private fun renameBook(book: Book, newTitle: String) {
        val updatedBook = book.copy(title = newTitle)
        viewModel.update(updatedBook)
    }

    private fun deleteBook(book: Book) {
        viewModel.delete(book)
    }
}