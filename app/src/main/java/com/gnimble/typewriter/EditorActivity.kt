// EditorActivity.kt
package com.gnimble.typewriter

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import com.gnimble.typewriter.data.AppDatabase
import com.gnimble.typewriter.data.Book
import com.gnimble.typewriter.databinding.ActivityEditorBinding
import kotlinx.coroutines.launch
import java.util.Date

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private var bookId: Long = -1
    private var currentBook: Book? = null
    private val database by lazy { AppDatabase.getDatabase(this) }

    // Font list - you'll need to add your actual font resource IDs here
    private lateinit var fontList: List<FontItem>

    // Heading styles
    private val headingStyles = listOf(
        HeadingStyle("Body", 1.0f),
        HeadingStyle("Title", 2.0f),
        HeadingStyle("Subtitle", 1.5f),
        HeadingStyle("Chapter", 1.75f)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        bookId = intent.getLongExtra("book_id", -1)
        val bookTitle = intent.getStringExtra("book_title") ?: "Untitled"

        supportActionBar?.title = bookTitle

        if (bookId != -1L) {
            loadBook()
        }

        initializeFontList()
        setupHeadingDropdown()
        setupFontDropdown()
        setupFormatButtons()
        setupAutoSave()
    }

    private fun initializeFontList() {
        val fontItems = mutableListOf<FontItem>()

        // Add default font first
        fontItems.add(FontItem("Default", 0, Typeface.DEFAULT))

        // Get all font resources dynamically
        val fontFields = R.font::class.java.fields

        for (field in fontFields) {
            try {
                val resourceId = field.getInt(null)
                val fontName = formatFontName(field.name)
                val typeface = loadFont(resourceId)

                if (typeface != null) {
                    fontItems.add(FontItem(fontName, resourceId, typeface))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Sort fonts alphabetically (keeping Default at the top)
        fontList = listOf(fontItems[0]) + fontItems.drop(1).sortedBy { it.name }
    }

    private fun formatFontName(resourceName: String): String {
        // Convert resource name to readable format
        // e.g., "roboto_regular" -> "Roboto Regular"
        // e.g., "open_sans_bold" -> "Open Sans Bold"
        return resourceName
            .replace("_", " ")
            .split(" ")
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
    }

    private fun loadFont(resourceId: Int): Typeface? {
        return try {
            ResourcesCompat.getFont(this, resourceId)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun setupHeadingDropdown() {
        val headingNames = headingStyles.map { it.name }
        val headingAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, headingNames)
        headingAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.actionHeadingSelection.adapter = headingAdapter

        binding.actionHeadingSelection.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedStyle = headingStyles[position]
                binding.typewriter.setTextSize(selectedStyle.sizeFactor)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupFontDropdown() {
        val fontAdapter = FontAdapter(this, fontList)
        binding.actionFontSelection.adapter = fontAdapter

        binding.actionFontSelection.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedFont = fontList[position]
                binding.typewriter.setFont(selectedFont)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupFormatButtons() {
        binding.actionBold.setOnClickListener {
            binding.typewriter.toggleBold()
        }

        binding.actionItalic.setOnClickListener {
            binding.typewriter.toggleItalic()
        }

        binding.actionAlignLeft.setOnClickListener {
            binding.typewriter.setAlignment(TypewriterView.Alignment.LEFT)
        }

        binding.actionAlignCenter.setOnClickListener {
            binding.typewriter.setAlignment(TypewriterView.Alignment.CENTER)
        }

        binding.actionAlignRight.setOnClickListener {
            binding.typewriter.setAlignment(TypewriterView.Alignment.RIGHT)
        }

        binding.actionInsertImage.setOnClickListener {
            // Launch image picker
            val intent = android.content.Intent(android.content.Intent.ACTION_PICK)
            intent.type = "image/*"
            startActivityForResult(intent, REQUEST_CODE_PICK_IMAGE)
        }
    }

    private fun setupAutoSave() {
        binding.typewriter.editText.addTextChangedListener(object : android.text.TextWatcher {
            private var saveRunnable: Runnable? = null

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                // Cancel previous save
                saveRunnable?.let { binding.typewriter.editText.removeCallbacks(it) }

                // Schedule new save after 1 second of inactivity
                saveRunnable = Runnable {
                    saveBook()
                }
                binding.typewriter.editText.postDelayed(saveRunnable!!, 1000)
            }
        })
    }

    private fun loadBook() {
        lifecycleScope.launch {
            currentBook = database.bookDao().getBook(bookId)
            currentBook?.let { book ->
                binding.typewriter.editText.setText(book.storyContent)
                supportActionBar?.title = book.title
            }
        }
    }

    private fun saveBook() {
        lifecycleScope.launch {
            currentBook?.let { book ->
                val updatedBook = book.copy(
                    storyContent = binding.typewriter.editText.text.toString(),
                    lastEdited = Date()
                )
                database.bookDao().updateBook(updatedBook)
                currentBook = updatedBook
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PICK_IMAGE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                binding.typewriter.insertImage(uri)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_editor, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                saveBook()
                finish()
                true
            }
            R.id.action_save -> {
                saveBook()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onPause() {
        super.onPause()
        saveBook()
    }

    companion object {
        private const val REQUEST_CODE_PICK_IMAGE = 1001
    }
}

// Data class for heading styles
data class HeadingStyle(
    val name: String,
    val sizeFactor: Float
)