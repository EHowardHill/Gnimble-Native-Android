package com.gnimble.typewriter

import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private var bookId: Long = -1
    private var currentBook: Book? = null
    private val database by lazy { AppDatabase.getDatabase(this) }

    // Font list
    private lateinit var fontList: List<FontItem>

    // BUG FIX #6: Track whether the font dropdown has been fully initialized.
    // This prevents saveBook() from overwriting the book's saved font with "default"
    // if it fires before the spinner is ready.
    private var fontDropdownInitialized = false

    // Heading styles
    private val headingStyles = listOf(
        HeadingStyle("Body", 1.0f),
        HeadingStyle("Title", 2.0f),
        HeadingStyle("Subtitle", 1.5f),
        HeadingStyle("Chapter", 1.75f)
    )

    // Property to track find/replace state
    private var lastFoundIndex: Int = 0
    // BUG FIX #10: Track which page find/replace is currently searching
    private var lastFoundPageIndex: Int = 0

    private var pages = mutableListOf<String>()
    private var currentPageIndex = 0
    private val CHARS_PER_PAGE = 10000

    private var suppressTextWatchers = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        bookId = intent.getLongExtra("book_id", -1)
        val bookTitle = intent.getStringExtra("book_title") ?: "Untitled"

        supportActionBar?.title = bookTitle

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
            if (currentPageIndex < pages.size - 1) {
                changePage(currentPageIndex + 1)
            } else {
                if (binding.typewriter.editText.text.length > 100) {
                    pages.add("")
                    changePage(currentPageIndex + 1)
                }
            }
        }
    }

    private fun changePage(newIndex: Int) {
        suppressTextWatchers = true
        try {
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
        } finally {
            suppressTextWatchers = false
        }
    }

    private fun loadPageToEditor(index: Int) {
        if (index < 0 || index >= pages.size) {
            binding.loadingProgress.visibility = View.GONE
            return
        }

        binding.loadingProgress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val pageContent = pages[index]

            val (spannableContent, fontToSelect) = withContext(Dispatchers.IO) {
                val htmlHandler = SimpleHtmlHandler(this@EditorActivity)

                val contentToParse = "<html><body>$pageContent</body></html>"

                val spannable = htmlHandler.htmlToSpannable(contentToParse)

                val currentFontName = currentBook?.fontName ?: "default"
                val font = fontList.find { it.resourceEntryName == currentFontName } ?: fontList[0]

                Pair(spannable, font)
            }

            suppressTextWatchers = true
            try {
                binding.typewriter.setContent(spannableContent)
                binding.typewriter.setGlobalFont(fontToSelect)

                // BUG FIX #3: Reset cursor to position 0 after loading a new page.
                // Without this, a stale selection from the previous page could cause
                // IndexOutOfBoundsException if it exceeds the new page's length.
                binding.typewriter.editText.setSelection(0)
            } finally {
                suppressTextWatchers = false
            }
            binding.loadingProgress.visibility = View.GONE
        }
    }

    private fun updatePaginationUI() {
        binding.textPageIndicator.text = "Section ${currentPageIndex + 1} / ${pages.size}"
        binding.btnPrevPage.isEnabled = currentPageIndex > 0
        binding.btnNextPage.isEnabled = true
    }

    // BUG FIX #8: Guard against saving empty/null content to the page buffer.
    // If the EditText was just cleared (e.g. during changePage), we skip the save
    // to avoid overwriting valid page content with an empty string.
    private fun saveCurrentPageToBuffer() {
        if (currentPageIndex < 0 || currentPageIndex >= pages.size) return

        val editableText = binding.typewriter.editText.text
        if (editableText == null || editableText.isEmpty()) return
        if (editableText !is Spannable) return

        val htmlHandler = SimpleHtmlHandler(this)
        val htmlContent = htmlHandler.spannableToHtml(
            editableText,
            includeWrapper = false
        )
        pages[currentPageIndex] = htmlContent
    }

    // BUG FIX #7: Improved HTML splitting that handles content not wrapped in <p> tags.
    // The old approach split on "</p>" which dropped <div>, <span>, <br>, and bare text.
    // The new approach uses a tag-aware chunker that respects HTML structure.
    private fun splitContentIntoPages(fullHtml: String): MutableList<String> {
        val result = mutableListOf<String>()

        // Remove <html><body> wrappers for splitting logic
        val cleanContent = fullHtml
            .replace("<html><body>", "")
            .replace("</body></html>", "")

        // Split by block-level boundaries: </p>, </div>, <br>, or newlines
        // This regex captures each block element as a complete unit
        val blockPattern = Regex("""(<p[^>]*>.*?</p>|<div[^>]*>.*?</div>|<br\s*/?>|[^<]+)""", RegexOption.DOT_MATCHES_ALL)
        val blocks = blockPattern.findAll(cleanContent).map { it.value }.toList()

        if (blocks.isEmpty()) {
            result.add(cleanContent.ifBlank { "" })
            return result
        }

        var currentChunk = StringBuilder()

        for (block in blocks) {
            if (block.isBlank()) continue

            // If adding this block exceeds the limit, push current chunk and start new
            if (currentChunk.length + block.length > CHARS_PER_PAGE && currentChunk.isNotEmpty()) {
                result.add(currentChunk.toString())
                currentChunk = StringBuilder()
            }
            currentChunk.append(block)
        }

        if (currentChunk.isNotEmpty()) {
            result.add(currentChunk.toString())
        }

        if (result.isEmpty()) result.add("")

        return result
    }

    private fun initializeFontList() {
        val fontItems = mutableListOf<FontItem>()

        fontItems.add(FontItem("Default", "default", 0, Typeface.DEFAULT))

        val fontFields = R.font::class.java.fields

        for (field in fontFields) {
            try {
                val resourceId = field.getInt(null)
                val resourceEntryName = field.name
                val fontName = formatFontName(field.name)

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

    private fun formatFontName(resourceName: String): String {
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
                // BUG FIX #6: Mark dropdown as initialized on first selection callback.
                fontDropdownInitialized = true

                val selectedFont = fontList[position]
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
                if (suppressTextWatchers) return

                saveRunnable?.let { binding.typewriter.editText.removeCallbacks(it) }

                saveRunnable = Runnable {
                    saveBook()
                }
                binding.typewriter.editText.postDelayed(saveRunnable!!, 1000)
            }
        })
    }

    private fun saveBook() {
        // BUG FIX #8: Don't save if we're in the middle of a page transition
        if (suppressTextWatchers) return

        // 1. Ensure the currently active page is saved to the list
        saveCurrentPageToBuffer()

        lifecycleScope.launch {
            currentBook?.let { book ->

                // 2. Stitch all pages back together
                val stitchedHtml = StringBuilder()
                stitchedHtml.append("<html><body>")
                pages.forEach { pageHtml ->
                    val cleanPage = pageHtml
                        .replace("<html><body>", "")
                        .replace("</body></html>", "")
                    stitchedHtml.append(cleanPage)
                }
                stitchedHtml.append("</body></html>")

                val finalHtml = stitchedHtml.toString()

                val plainText = finalHtml.replace(Regex("<[^>]+>"), " ").trim()

                // BUG FIX #6: Only read font from spinner if it has been initialized.
                // Before initialization, the spinner position may be -1 or default,
                // which would overwrite the book's actual saved font with "default".
                val currentFontName = if (fontDropdownInitialized) {
                    val selectedPosition = binding.actionFontSelection.selectedItemPosition
                    if (selectedPosition >= 0 && selectedPosition < fontList.size) {
                        fontList[selectedPosition].resourceEntryName
                    } else {
                        book.fontName // Preserve existing font
                    }
                } else {
                    book.fontName // Preserve existing font until spinner is ready
                }

                val updatedBook = book.copy(
                    storyContent = plainText.take(500),
                    formattedContent = finalHtml,
                    contentFormat = ContentFormat.HTML,
                    lastEdited = Date(),
                    subtitle = "Last edited: ${SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date())}",
                    fontName = currentFontName
                )
                // BUG FIX #5: Acquire shared mutex to prevent race condition
                // with ShareActivity's web server writing to the same book.
                ShareActivity.dbWriteMutex.withLock {
                    database.bookDao().updateBook(updatedBook)
                }
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
                        val contentToLoad = if (book.contentFormat == ContentFormat.HTML && !book.formattedContent.isNullOrEmpty()) {
                            book.formattedContent!!
                        } else {
                            "<html><body><p>${book.storyContent.replace("\n", "</p><p>")}</p></body></html>"
                        }

                        pages = splitContentIntoPages(contentToLoad)
                    }

                    currentPageIndex = 0
                    updatePaginationUI()
                    loadPageToEditor(0)

                    // BUG FIX #6: Set the font spinner to the book's saved font AFTER loading
                    val savedFontIndex = fontList.indexOfFirst { it.resourceEntryName == book.fontName }
                    if (savedFontIndex >= 0) {
                        binding.actionFontSelection.setSelection(savedFontIndex)
                    }

                    supportActionBar?.title = book.title

                } catch (e: Exception) {
                    e.printStackTrace()
                    pages = mutableListOf(book.storyContent)
                    loadPageToEditor(0)
                }
            }
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
                saveCurrentPageToBuffer()
                val wordCount = getTotalWordCount()
                val pageCount = getTotalPageCount()
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

    // BUG FIX #10: Completely rewritten Find & Replace to search across ALL pages,
    // not just the currently visible one. The dialog now navigates to the correct page
    // when a match is found on a different section.
    private fun showFindReplaceDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_find_replace, null)
        val findEditText = dialogView.findViewById<EditText>(R.id.et_find)
        val replaceEditText = dialogView.findViewById<EditText>(R.id.et_replace)
        val mainEditText = binding.typewriter.editText

        // Reset search state
        lastFoundIndex = 0
        lastFoundPageIndex = currentPageIndex
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
                    saveCurrentPageToBuffer()

                    // Replace in all non-current pages (HTML level)
                    for (i in pages.indices) {
                        if (i != currentPageIndex) {
                            pages[i] = pages[i].replace(findText, replaceText, ignoreCase = true)
                        }
                    }

                    // Replace in the current page using Editable to preserve spans
                    val editable = mainEditText.text
                    var searchFrom = 0
                    while (searchFrom < editable.length) {
                        val idx = editable.toString().indexOf(findText, searchFrom, ignoreCase = true)
                        if (idx == -1) break
                        editable.replace(idx, idx + findText.length, replaceText)
                        searchFrom = idx + replaceText.length
                    }

                    Toast.makeText(this, "All occurrences replaced across all sections.", Toast.LENGTH_SHORT).show()
                }
            }
            .create()

        dialog.show()

        val findButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val replaceButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)

        findButton.setOnClickListener {
            val findText = findEditText.text.toString()
            if (findText.isEmpty()) return@setOnClickListener

            // Save current page so we can search its latest content
            saveCurrentPageToBuffer()

            // Search starting from current position on current search page
            val pagesCount = pages.size
            var searched = 0

            while (searched < pagesCount) {
                val pageIdx = lastFoundPageIndex % pagesCount
                val pageContent: String

                if (pageIdx == currentPageIndex) {
                    // Search in the live EditText for the current page
                    pageContent = mainEditText.text.toString()
                } else {
                    // Search in the stored HTML (strip tags for plain-text search)
                    pageContent = pages[pageIdx].replace(Regex("<[^>]+>"), "")
                        .replace("&lt;", "<").replace("&gt;", ">")
                        .replace("&amp;", "&").replace("&quot;", "\"")
                }

                val startIndex = pageContent.indexOf(findText, startIndex = lastFoundIndex, ignoreCase = true)

                if (startIndex != -1) {
                    // Found a match
                    if (pageIdx != currentPageIndex) {
                        // Navigate to that page first, then highlight
                        changePage(pageIdx)
                        // After page change, find in the now-loaded EditText
                        val liveContent = mainEditText.text.toString()
                        val liveIndex = liveContent.indexOf(findText, ignoreCase = true)
                        if (liveIndex != -1) {
                            mainEditText.requestFocus()
                            mainEditText.setSelection(liveIndex, liveIndex + findText.length)
                            lastFoundIndex = liveIndex + 1
                        }
                    } else {
                        mainEditText.requestFocus()
                        mainEditText.setSelection(startIndex, startIndex + findText.length)
                        lastFoundIndex = startIndex + 1
                    }
                    lastFoundPageIndex = pageIdx
                    return@setOnClickListener
                }

                // Not found on this page, move to next
                lastFoundIndex = 0
                lastFoundPageIndex = (pageIdx + 1) % pagesCount
                searched++
            }

            // Wrapped all pages without finding
            Toast.makeText(this, "Text not found in any section.", Toast.LENGTH_SHORT).show()
            lastFoundIndex = 0
            lastFoundPageIndex = currentPageIndex
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

    fun getTotalWordCount(): Int {
        saveCurrentPageToBuffer()
        var totalWords = 0
        for (pageHtml in pages) {
            val plainText = pageHtml.replace(Regex("<[^>]+>"), " ").trim()
            if (plainText.isNotBlank()) {
                totalWords += plainText.trim().split("\\s+".toRegex()).size
            }
        }
        return totalWords
    }

    fun getTotalPageCount(): Int {
        return pages.size.coerceAtLeast(1)
    }

    // Constants for pagination limits
    private val PREFERRED_PAGE_SIZE = 10000
    private val MAX_PAGE_SIZE = 12000

    private fun setupPageOverflowWatcher() {
        binding.typewriter.editText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: android.text.Editable?) {
                if (suppressTextWatchers) return
                if (s == null) return

                if (s.length > MAX_PAGE_SIZE) {
                    performAutoPagination(s)
                }
            }
        })
    }

    // BUG FIX #2: Fixed off-by-one and empty-overflow-page issues in auto-pagination.
    // The old code could create empty overflow pages when splitIndex == content.length - 1,
    // and could produce an IndexOutOfBoundsException if splitIndex was out of bounds.
    private fun performAutoPagination(content: android.text.Editable) {
        val searchLimit = min(PREFERRED_PAGE_SIZE, content.length)
        var splitIndex = -1

        // Search backwards from the preferred limit for a newline
        for (i in searchLimit - 1 downTo 0) {
            if (content[i] == '\n') {
                splitIndex = i
                break
            }
        }

        // If no newline found, split at the preferred size
        if (splitIndex == -1 || splitIndex < 100) {
            splitIndex = min(PREFERRED_PAGE_SIZE, content.length)
        }

        // Safety check: ensure there's actually content to overflow
        if (splitIndex <= 0 || splitIndex >= content.length) {
            return // Nothing to split off
        }

        // Ensure there's meaningful overflow content (not just whitespace)
        val overflowStart = splitIndex
        if (overflowStart >= content.length) {
            return // No overflow content
        }

        val keepSpannable = SpannableStringBuilder(content, 0, splitIndex)
        val overflowSpannable = SpannableStringBuilder(content, overflowStart, content.length)

        // Don't create a new page if the overflow is empty/whitespace-only
        if (overflowSpannable.toString().isBlank()) {
            return
        }

        val htmlHandler = SimpleHtmlHandler(this)
        val currentHtml = htmlHandler.spannableToHtml(keepSpannable, false)
        val overflowHtml = htmlHandler.spannableToHtml(overflowSpannable, false)

        // Update current page in memory
        pages[currentPageIndex] = currentHtml

        // Insert new page AFTER current page
        pages.add(currentPageIndex + 1, overflowHtml)

        // Move user to the new page
        changePage(currentPageIndex + 1)

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