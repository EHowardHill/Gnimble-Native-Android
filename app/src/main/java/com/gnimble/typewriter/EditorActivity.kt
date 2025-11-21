package com.gnimble.typewriter

import android.graphics.Typeface
import android.os.Bundle
import android.text.Html
import android.text.Spannable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private var bookId: Long = -1
    private var currentBook: Book? = null
    private val database by lazy { AppDatabase.getDatabase(this) }

    // Font list
    private lateinit var fontList: List<FontItem>

    // Heading styles
    private val headingStyles = listOf(
        HeadingStyle("Body", 1.0f),
        HeadingStyle("Title", 2.0f),
        HeadingStyle("Subtitle", 1.5f),
        HeadingStyle("Chapter", 1.75f)
    )

    // Property to track find/replace state
    private var lastFoundIndex: Int = 0

    private var pages = mutableListOf<String>()
    private var currentPageIndex = 0
    private val CHARS_PER_PAGE = 10000 // Split approx every 10k chars (adjustable)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        bookId = intent.getLongExtra("book_id", -1)
        val bookTitle = intent.getStringExtra("book_title") ?: "Untitled"

        supportActionBar?.title = bookTitle

        // Initialize fonts BEFORE loading the book so the list is ready
        initializeFontList()

        if (bookId != -1L) {
            loadBook()
        }

        setupHeadingDropdown()
        setupFontDropdown()
        setupFormatButtons()
        setupAutoSave()
        setupPaginationControls()
        setupPageOverflowWatcher()
    }

    private fun setupPaginationControls() {
        binding.btnPrevPage.setOnClickListener {
            if (currentPageIndex > 0) {
                changePage(currentPageIndex - 1)
            }
        }

        binding.btnNextPage.setOnClickListener {
            // Allow creating a new page if we are at the end
            if (currentPageIndex < pages.size - 1) {
                changePage(currentPageIndex + 1)
            } else {
                // Optional: Ask user if they want to add a new page?
                // For now, let's just create one if the current one is full
                if (binding.typewriter.editText.text.length > 100) {
                    pages.add("") // Add empty page
                    changePage(currentPageIndex + 1)
                }
            }
        }
    }

    private fun changePage(newIndex: Int) {
        // 1. Save current edits to the memory buffer
        saveCurrentPageToBuffer()

        // 2. Clear the EditText to free memory immediately
        binding.typewriter.editText.text = null

        // 3. Update index
        currentPageIndex = newIndex

        // 4. Load the new page
        loadPageToEditor(newIndex)

        // 5. Update UI
        updatePaginationUI()
    }

    private fun loadPageToEditor(index: Int) {
        binding.loadingProgress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val pageContent = pages[index]

            val (spannableContent, fontToSelect) = withContext(Dispatchers.IO) {
                val htmlHandler = SimpleHtmlHandler(this@EditorActivity)

                // We wrap the chunk in body tags so the handler processes it correctly
                val contentToParse = "<html><body>$pageContent</body></html>"

                val spannable = htmlHandler.htmlToSpannable(contentToParse)

                // Get font (we assume global font for the book, not per page)
                val currentFontName = currentBook?.fontName ?: "default"
                val font = fontList.find { it.resourceEntryName == currentFontName } ?: fontList[0]

                Pair(spannable, font)
            }

            binding.typewriter.setContent(spannableContent)
            binding.typewriter.setGlobalFont(fontToSelect)
            binding.loadingProgress.visibility = View.GONE
        }
    }

    private fun updatePaginationUI() {
        binding.textPageIndicator.text = "Section ${currentPageIndex + 1} / ${pages.size}"
        binding.btnPrevPage.isEnabled = currentPageIndex > 0
        // Next is always enabled to allow adding new pages, or you can limit it
        binding.btnNextPage.isEnabled = true
    }

    // Capture what is currently in the EditText and store it in our pages list
    private fun saveCurrentPageToBuffer() {
        if (currentPageIndex >= 0 && currentPageIndex < pages.size) {
            val htmlHandler = SimpleHtmlHandler(this)
            // Convert current spannable back to HTML
            // Note: includeWrapper=false because we just want the body content for the chunk
            val htmlContent = htmlHandler.spannableToHtml(
                binding.typewriter.editText.text as Spannable,
                includeWrapper = false
            )
            pages[currentPageIndex] = htmlContent
        }
    }

    private fun splitContentIntoPages(fullHtml: String): MutableList<String> {
        val result = mutableListOf<String>()

        // Remove <html><body> wrappers for splitting logic
        val cleanContent = fullHtml
            .replace("<html><body>", "")
            .replace("</body></html>", "")

        // Split by paragraphs to avoid breaking HTML tags mid-stream
        // This is a naive split; for very long paragraphs, it might still be large
        val paragraphs = cleanContent.split("</p>")

        var currentChunk = StringBuilder()

        for (p in paragraphs) {
            if (p.isBlank()) continue

            val paragraphWithTag = "$p</p>"

            // If adding this paragraph exceeds limit, push currentChunk to pages
            if (currentChunk.length + paragraphWithTag.length > CHARS_PER_PAGE && currentChunk.isNotEmpty()) {
                result.add(currentChunk.toString())
                currentChunk = StringBuilder()
            }
            currentChunk.append(paragraphWithTag)
        }

        if (currentChunk.isNotEmpty()) {
            result.add(currentChunk.toString())
        }

        if (result.isEmpty()) result.add("") // Ensure at least one page

        return result
    }

    private fun initializeFontList() {
        val fontItems = mutableListOf<FontItem>()

        // Add default font
        fontItems.add(FontItem("Default", "default", 0, Typeface.DEFAULT))

        val fontFields = R.font::class.java.fields

        for (field in fontFields) {
            try {
                val resourceId = field.getInt(null)
                val resourceEntryName = field.name // e.g. "crimson_text"
                val fontName = formatFontName(field.name)

                // This calls the helper method defined below
                val typeface = loadFont(resourceId)

                if (typeface != null) {
                    fontItems.add(FontItem(fontName, resourceEntryName, resourceId, typeface))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        fontList = listOf(fontItems[0]) + fontItems.drop(1).sortedBy { it.name }
    }

    // Helper method to format font names (e.g. "crimson_text" -> "Crimson Text")
    private fun formatFontName(resourceName: String): String {
        return resourceName
            .replace("_", " ")
            .split(" ")
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
    }

    // Helper method to load Typeface from resource ID
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
                // Apply globally instead of to selection
                binding.typewriter.setGlobalFont(selectedFont)
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
        // 1. Ensure the currently active page is saved to the list
        saveCurrentPageToBuffer()

        lifecycleScope.launch {
            currentBook?.let { book ->

                // 2. Stitch all pages back together
                val stitchedHtml = StringBuilder()
                stitchedHtml.append("<html><body>")
                pages.forEach { pageHtml ->
                    // Clean pageHtml to ensure we don't duplicate body tags if they snuck in
                    val cleanPage = pageHtml
                        .replace("<html><body>", "")
                        .replace("</body></html>", "")
                    stitchedHtml.append(cleanPage)
                }
                stitchedHtml.append("</body></html>")

                val finalHtml = stitchedHtml.toString()

                // Generate plain text preview (rough approximation for the list view)
                val plainText = finalHtml.replace(Regex("<[^>]+>"), " ").trim()

                // Get current font
                val selectedPosition = binding.actionFontSelection.selectedItemPosition
                val currentFontName = if (selectedPosition >= 0 && selectedPosition < fontList.size) {
                    fontList[selectedPosition].resourceEntryName
                } else {
                    "default"
                }

                val updatedBook = book.copy(
                    storyContent = plainText.take(500), // Only store a preview in storyContent to save DB space
                    formattedContent = finalHtml,
                    contentFormat = ContentFormat.HTML,
                    lastEdited = Date(),
                    subtitle = "Last edited: ${SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date())}",
                    fontName = currentFontName
                )
                database.bookDao().updateBook(updatedBook)
                currentBook = updatedBook
            }
        }
    }

    private fun loadBook() {
        binding.loadingProgress.visibility = View.VISIBLE
        binding.typewriter.visibility = View.INVISIBLE

        lifecycleScope.launch {
            currentBook = database.bookDao().getBook(bookId)
            currentBook?.let { book ->
                try {
                    withContext(Dispatchers.IO) {
                        // Prepare content string
                        val contentToLoad = if (book.contentFormat == ContentFormat.HTML && !book.formattedContent.isNullOrEmpty()) {
                            book.formattedContent!!
                        } else {
                            "<html><body><p>${book.storyContent.replace("\n", "</p><p>")}</p></body></html>"
                        }

                        // HEAVY OPERATION: Split content into pages
                        pages = splitContentIntoPages(contentToLoad)
                    }

                    // Reset to page 0
                    currentPageIndex = 0
                    updatePaginationUI()
                    loadPageToEditor(0)

                    supportActionBar?.title = book.title

                } catch (e: Exception) {
                    e.printStackTrace()
                    // Fallback
                    pages = mutableListOf(book.storyContent)
                    loadPageToEditor(0)
                }
            }
            // Visibility is handled in loadPageToEditor
            binding.typewriter.visibility = View.VISIBLE
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
                Toast.makeText(this, "Document Saved", Toast.LENGTH_SHORT).show()
                true
            }

            R.id.action_statistics -> {
                val wordCount = getWordCount()
                val pageCount = getPageCount()
                val message = "Word Count: $wordCount\nPage Count: $pageCount"

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
                showFindReplaceDialog()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

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

        val findButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val replaceButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)

        findButton.setOnClickListener {
            val findText = findEditText.text.toString()
            val content = mainEditText.text.toString()

            if (findText.isEmpty()) return@setOnClickListener

            if (lastFoundIndex >= content.length) {
                lastFoundIndex = 0
                Toast.makeText(this, "Searching from top...", Toast.LENGTH_SHORT).show()
            }

            val startIndex = content.indexOf(findText, startIndex = lastFoundIndex, ignoreCase = true)

            if (startIndex != -1) {
                mainEditText.requestFocus()
                mainEditText.setSelection(startIndex, startIndex + findText.length)
                lastFoundIndex = startIndex + 1
            } else {
                Toast.makeText(this, "Text not found.", Toast.LENGTH_SHORT).show()
                lastFoundIndex = 0
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

                if (selectedText.equals(findText, ignoreCase = true)) {
                    mainEditText.text.replace(selectionStart, selectionEnd, replaceText)
                    lastFoundIndex = selectionStart + replaceText.length
                    findButton.performClick()
                } else {
                    findButton.performClick()
                }
            } else {
                findButton.performClick()
            }
        }
    }

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
        val heightInInches = textHeightInPixels / ydpi
        val pageCount = floor(heightInInches / 7.0).toInt() + 1
        return pageCount.coerceAtLeast(1)
    }

    // Constants for pagination limits
    private val PREFERRED_PAGE_SIZE = 10000
    private val MAX_PAGE_SIZE = 12000 // Buffer to allow finishing a paragraph

    private fun setupPageOverflowWatcher() {
        binding.typewriter.editText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: android.text.Editable?) {
                if (s == null) return

                // Check if we exceeded the hard limit
                if (s.length > MAX_PAGE_SIZE) {
                    performAutoPagination(s)
                }
            }
        })
    }

    private fun performAutoPagination(content: android.text.Editable) {
        // 1. Find the best place to split (the last paragraph break)
        // We look for the last newline character before the preferred limit
        // to avoid splitting the user mid-sentence.
        var splitIndex = content.lastIndexOf('\n')

        // If the user wrote one massive paragraph (rare), just split at the limit
        if (splitIndex == -1 || splitIndex < 100) {
            splitIndex = PREFERRED_PAGE_SIZE
        }

        // 2. Extract the text that needs to move to the next page
        val overflowSpannable = content.subSequence(splitIndex + 1, content.length)

        // 3. Keep the text that stays on this page
        val keepSpannable = content.subSequence(0, splitIndex)

        // 4. Convert both to HTML for storage
        val htmlHandler = SimpleHtmlHandler(this)
        val currentHtml = htmlHandler.spannableToHtml(keepSpannable as Spannable, false)
        val overflowHtml = htmlHandler.spannableToHtml(overflowSpannable as Spannable, false)

        // 5. Update current page in memory
        pages[currentPageIndex] = currentHtml

        // 6. Insert new page AFTER current page
        // We add it to index + 1
        pages.add(currentPageIndex + 1, overflowHtml)

        // 7. Remove the TextWatcher temporarily to avoid infinite loops while we modify UI
        // (Note: clearFocus or simple text setting usually triggers watchers)

        // 8. Move user to the new page automatically
        changePage(currentPageIndex + 1)

        // 9. Notify user
        Toast.makeText(this, "Section full! Created new section.", Toast.LENGTH_SHORT).show()
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