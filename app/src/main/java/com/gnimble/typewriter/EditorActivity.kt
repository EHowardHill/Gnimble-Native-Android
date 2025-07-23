// EditorActivity.kt
package com.gnimble.typewriter

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Html
import android.text.Spannable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText // Added import
import android.widget.Toast // Added import
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import com.gnimble.typewriter.data.AppDatabase
import com.gnimble.typewriter.data.Book
import com.gnimble.typewriter.data.ContentFormat
import com.gnimble.typewriter.data.FontItem
import com.gnimble.typewriter.databinding.ActivityEditorBinding
import com.gnimble.typewriter.utils.SimpleHtmlHandler
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.math.floor

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

    // New property to track find/replace state
    private var lastFoundIndex: Int = 0

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

    fun setupHeadingDropdown() {
        val headingNames = headingStyles.map { it.name }
        val headingAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, headingNames)
        headingAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.actionHeadingSelection.adapter = headingAdapter

        binding.actionHeadingSelection.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedStyle = headingStyles[position]
                binding.typewriter.applyHeadingStyle(selectedStyle)
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
                binding.typewriter.applyFont(selectedFont)
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
            val intent = Intent(Intent.ACTION_PICK)
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

    private fun saveBook() {
        lifecycleScope.launch {
            currentBook?.let { book ->
                val htmlHandler = SimpleHtmlHandler(this@EditorActivity)
                // Export without HTML wrapper for embedding
                val htmlContent = htmlHandler.spannableToHtml(
                    binding.typewriter.editText.text as Spannable,
                    includeWrapper = false  // Don't include <html><body> tags
                )

                val updatedBook = book.copy(
                    storyContent = binding.typewriter.editText.text.toString(), // Plain text version
                    formattedContent = htmlContent, // HTML formatted version (without wrapper)
                    contentFormat = ContentFormat.HTML, // Mark as HTML format
                    lastEdited = Date()
                )
                database.bookDao().updateBook(updatedBook)
                currentBook = updatedBook
            }
        }
    }

    private fun loadBook() {
        lifecycleScope.launch {
            currentBook = database.bookDao().getBook(bookId)
            currentBook?.let { book ->
                try {
                    val htmlHandler = SimpleHtmlHandler(this@EditorActivity)

                    // Use formatted content if available, otherwise use story content
                    val contentToLoad = if (book.contentFormat == ContentFormat.HTML && !book.formattedContent.isNullOrEmpty()) {
                        // If the content doesn't have HTML wrapper, add it for parsing
                        val content = book.formattedContent
                        if (!content.startsWith("<html>") && !content.startsWith("<!DOCTYPE")) {
                            "<html><body>$content</body></html>"
                        } else {
                            content
                        }
                    } else {
                        // Convert plain text to HTML
                        "<html><body><p>${book.storyContent.replace("\n", "</p><p>")}</p></body></html>"
                    }

                    val spannable = htmlHandler.htmlToSpannable(contentToLoad)
                    // Use setContent to ensure paragraph indents are applied
                    binding.typewriter.setContent(spannable)
                    supportActionBar?.title = book.title
                } catch (e: Exception) {
                    // Fallback to simple HTML parsing
                    try {
                        val spannable = Html.fromHtml(
                            book.storyContent,
                            Html.FROM_HTML_MODE_COMPACT
                        )
                        binding.typewriter.setContent(spannable)
                    } catch (e2: Exception) {
                        // Last resort: plain text
                        binding.typewriter.setContent(book.storyContent)
                    }
                }
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

            R.id.action_statistics -> {
                // Get the word and page counts
                val wordCount = getWordCount()
                val pageCount = getPageCount()

                // Build the message string
                val message = "Word Count: $wordCount\nPage Count: $pageCount"

                // Create and show the AlertDialog
                AlertDialog.Builder(this)
                    .setTitle("Statistics:")
                    .setMessage(message)
                    .setPositiveButton("OK") { dialog, _ ->
                        dialog.dismiss()
                    }
                    .show()

                true
            }

            R.id.action_find_replace -> {
                // ** MODIFICATION START **
                showFindReplaceDialog()
                // ** MODIFICATION END **
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    // ** NEW METHOD START **
    private fun showFindReplaceDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_find_replace, null)
        val findEditText = dialogView.findViewById<EditText>(R.id.et_find)
        val replaceEditText = dialogView.findViewById<EditText>(R.id.et_replace)
        val mainEditText = binding.typewriter.editText

        // Reset search index when the dialog is opened
        lastFoundIndex = 0
        mainEditText.clearFocus()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Find & Replace")
            .setView(dialogView)
            .setPositiveButton("Find") { _, _ -> /* Overridden below */ }
            .setNeutralButton("Replace") { _, _ -> /* Overridden below */ }
            .setNegativeButton("Replace All") { _, _ ->
                val findText = findEditText.text.toString()
                val replaceText = replaceEditText.text.toString()

                if (findText.isNotEmpty()) {
                    val originalContent = mainEditText.text.toString()
                    val newContent = originalContent.replace(findText, replaceText, ignoreCase = true)
                    mainEditText.setText(newContent)
                    Toast.makeText(this, "All occurrences replaced.", Toast.LENGTH_SHORT).show()
                }
            }
            .create()

        dialog.show()

        // We override some button listeners to prevent the dialog from closing on click.
        val findButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val replaceButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)

        findButton.setOnClickListener {
            val findText = findEditText.text.toString()
            val content = mainEditText.text.toString()

            if (findText.isEmpty()) return@setOnClickListener

            // If we've searched to the end, wrap around
            if (lastFoundIndex >= content.length) {
                lastFoundIndex = 0
                Toast.makeText(this, "Searching from top...", Toast.LENGTH_SHORT).show()
            }

            val startIndex = content.indexOf(findText, startIndex = lastFoundIndex, ignoreCase = true)

            if (startIndex != -1) {
                mainEditText.requestFocus()
                mainEditText.setSelection(startIndex, startIndex + findText.length)
                lastFoundIndex = startIndex + 1 // Prepare for next search
            } else {
                Toast.makeText(this, "Text not found.", Toast.LENGTH_SHORT).show()
                lastFoundIndex = 0 // Reset for next time
            }
        }

        replaceButton.setOnClickListener {
            val findText = findEditText.text.toString()
            val replaceText = replaceEditText.text.toString()

            if (findText.isEmpty()) return@setOnClickListener

            if (mainEditText.hasSelection()) {
                val selectionStart = mainEditText.selectionStart
                val selectionEnd = mainEditText.selectionEnd
                val selectedText = mainEditText.text.subSequence(selectionStart, selectionEnd).toString()

                // Check if the current selection matches the text to find
                if (selectedText.equals(findText, ignoreCase = true)) {
                    mainEditText.text.replace(selectionStart, selectionEnd, replaceText)
                    // Automatically find the next occurrence
                    lastFoundIndex = selectionStart + replaceText.length
                    findButton.performClick()
                } else {
                    // Selection doesn't match, just find the next occurrence
                    findButton.performClick()
                }
            } else {
                // Nothing is selected, just find the first occurrence
                findButton.performClick()
            }
        }
    }
    // ** NEW METHOD END **

    override fun onPause() {
        super.onPause()
        saveBook()
    }

    fun getWordCount(): Int {
        val text = binding.typewriter.editText.text.toString()
        if (text.isBlank()) return 0
        return text.trim().split("\\s+".toRegex()).size
    }

    fun getPageCount(): Int {
        val layout = binding.typewriter.editText.layout ?: return 1
        val textHeightInPixels = layout.height
        val ydpi = resources.displayMetrics.ydpi
        if (ydpi <= 0) return 1
        // Assuming a standard page height of ~7 inches for calculation
        val heightInInches = textHeightInPixels / ydpi
        val pageCount = floor(heightInInches / 7.0).toInt() + 1
        return pageCount.coerceAtLeast(1) // Ensure at least 1 page
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