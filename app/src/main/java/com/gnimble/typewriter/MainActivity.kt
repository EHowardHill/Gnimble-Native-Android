// MainActivity.kt
package com.gnimble.typewriter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.gnimble.typewriter.adapter.BookAdapter
import com.gnimble.typewriter.data.Book
import com.gnimble.typewriter.databinding.ActivityMainBinding
import com.gnimble.typewriter.viewmodel.MainViewModel
import com.gnimble.typewriter.utils.UpdateManager
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var bookAdapter: BookAdapter
    private var bookToUpdateCover: Book? = null

    // Activity result launcher for picking images from gallery
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { imageUri ->
            bookToUpdateCover?.let { book ->
                updateBookCover(book, imageUri)
            }
        }
        bookToUpdateCover = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomAppBar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_upload -> {
                    // Handle upload action
                    val intent = Intent(this, UploadActivity::class.java)
                    startActivity(intent)
                    true
                }
                R.id.action_settings -> {
                    // Handle settings action
                    val intent = Intent(Settings.ACTION_SETTINGS)
                    startActivity(intent)
                    true
                }
                R.id.action_update -> {
                    // Handle Gnimble system update action
                    checkForUpdates()
                    true
                }
                else -> false
            }
        }

        setupRecyclerView()
        observeBooks()
        setupFab()
    }

    private fun checkForUpdates() {
        val updateManager = UpdateManager(this)
        updateManager.checkForUpdates { updateAvailable ->
            if (updateAvailable == false) {
                Toast.makeText(this, "Update not found", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Update found", Toast.LENGTH_SHORT).show()
            }
        }
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
            },
            onChangeCover = { book ->
                openImagePicker(book)
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

            // Show/hide empty view
            if (books.isEmpty()) {
                binding.emptyView.visibility = View.VISIBLE
                binding.booksRecyclerView.visibility = View.GONE
            } else {
                binding.emptyView.visibility = View.GONE
                binding.booksRecyclerView.visibility = View.VISIBLE
            }
        }
    }

    private fun setupFab() {
        binding.fabAddBook.setOnClickListener {
            showNewBookDialog()
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

                // Validate input on the fly
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
            coverPath = null,
            storyContent = ""
        )

        viewModel.insert(newBook) { bookId ->
            // Open the newly created book
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
        // Create a copy of the book with the new title
        val updatedBook = book.copy(title = newTitle)

        // Update the book in the database
        viewModel.update(updatedBook)
    }

    private fun deleteBook(book: Book) {
        // Delete the book from the database
        viewModel.delete(book)
    }

    private fun openImagePicker(book: Book) {
        bookToUpdateCover = book
        pickImageLauncher.launch("image/*")
    }

    private fun updateBookCover(book: Book, imageUri: Uri) {
        // Request persistent permission for the URI
        try {
            contentResolver.takePersistableUriPermission(
                imageUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // Some URIs don't support persistent permissions, which is fine
        }

        // Save the image URI as string
        val updatedBook = book.copy(coverPath = imageUri.toString())

        // Update the book in the database
        viewModel.update(updatedBook)
    }
}