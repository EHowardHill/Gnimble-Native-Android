// TypewriterView.kt
package com.gnimble.typewriter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.Spannable
import android.text.style.AlignmentSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import android.text.style.RelativeSizeSpan
import androidx.constraintlayout.widget.ConstraintLayout
import com.gnimble.typewriter.databinding.ViewTypewriterBinding
import com.gnimble.typewriter.data.FontItem

class TypewriterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private val binding: ViewTypewriterBinding =
        ViewTypewriterBinding.inflate(LayoutInflater.from(context), this)

    private var currentFont: FontItem? = null

    // Calculate 1/4 inch in pixels (1/4 inch = 0.25 * DPI)
    private val tabIndentPixels = (0.25f * resources.displayMetrics.densityDpi).toInt()

    val editText: EditText
        get() = binding.editText

    init {
        // Ensure EditText uses SpannableStringBuilder
        editText.setText("", TextView.BufferType.SPANNABLE)

        // Configure EditText for proper word wrapping
        editText.apply {
            // Ensure no horizontal scrolling
            isHorizontalScrollBarEnabled = false

            // Set 1.5x line spacing for readability
            setLineSpacing(0f, 1.5f)

            // Add some padding to prevent text from touching edges
            val horizontalPadding = (8 * resources.displayMetrics.density).toInt()
            setPadding(horizontalPadding, paddingTop, horizontalPadding, paddingBottom)

            // Set default gravity to TOP | START and never change it
            gravity = Gravity.TOP or Gravity.START
        }

        // Add text change listener to handle paragraph indentation
        editText.addTextChangedListener(ParagraphIndentWatcher())
    }

    // Custom span for first line indentation
    class FirstLineIndentSpan(private val indent: Int) : LeadingMarginSpan {
        override fun getLeadingMargin(first: Boolean): Int {
            return if (first) indent else 0
        }

        override fun drawLeadingMargin(
            canvas: Canvas, paint: Paint, x: Int, dir: Int,
            top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int,
            first: Boolean, layout: Layout
        ) {
            // No drawing needed, just spacing
        }
    }

    // Custom span for justified text (workaround since there's no JustificationSpan)
    // This uses a standard alignment span but we'll track it differently
    class JustifySpan : AlignmentSpan {
        override fun getAlignment(): Layout.Alignment {
            // Return normal alignment, we'll handle justification differently
            return Layout.Alignment.ALIGN_NORMAL
        }
    }

    // Text watcher to handle paragraph indentation
    private inner class ParagraphIndentWatcher : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: android.text.Editable?) {
            if (s == null) return

            // Remove the watcher temporarily to avoid infinite loop
            editText.removeTextChangedListener(this)

            // Apply indentation to all paragraphs
            applyParagraphIndents(s)

            // Re-add the watcher
            editText.addTextChangedListener(this)
        }
    }

    private fun applyParagraphIndents(spannable: android.text.Editable) {
        // Remove all existing FirstLineIndentSpan
        val existingIndents = spannable.getSpans(0, spannable.length, FirstLineIndentSpan::class.java)
        existingIndents.forEach { spannable.removeSpan(it) }

        // Find all paragraph starts
        var paragraphStart = 0
        while (paragraphStart < spannable.length) {
            // Find the end of the current paragraph
            var paragraphEnd = spannable.indexOf('\n', paragraphStart)
            if (paragraphEnd == -1) {
                paragraphEnd = spannable.length
            } else {
                paragraphEnd++ // Include the newline character
            }

            // Apply indent only if the paragraph has content
            if (paragraphEnd > paragraphStart &&
                (paragraphEnd == paragraphStart + 1 || spannable[paragraphStart] != '\n')) {
                spannable.setSpan(
                    FirstLineIndentSpan(tabIndentPixels),
                    paragraphStart,
                    paragraphEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE or Spannable.SPAN_PARAGRAPH
                )
            }

            paragraphStart = paragraphEnd
        }
    }

    enum class Alignment {
        LEFT, CENTER, RIGHT, JUSTIFY
    }

    fun toggleBold() {
        val spannable = editText.text as Spannable
        val selectionStart = editText.selectionStart
        val selectionEnd = editText.selectionEnd

        if (selectionStart == selectionEnd) return

        val styleSpans = spannable.getSpans(selectionStart, selectionEnd, StyleSpan::class.java)
        val boldSpans = styleSpans.filter { it.style == android.graphics.Typeface.BOLD }

        if (boldSpans.isNotEmpty()) {
            boldSpans.forEach { spannable.removeSpan(it) }
        } else {
            spannable.setSpan(
                StyleSpan(android.graphics.Typeface.BOLD),
                selectionStart,
                selectionEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    fun toggleItalic() {
        val spannable = editText.text as Spannable
        val selectionStart = editText.selectionStart
        val selectionEnd = editText.selectionEnd

        if (selectionStart == selectionEnd) return

        val styleSpans = spannable.getSpans(selectionStart, selectionEnd, StyleSpan::class.java)
        val italicSpans = styleSpans.filter { it.style == Typeface.ITALIC }

        if (italicSpans.isNotEmpty()) {
            italicSpans.forEach { spannable.removeSpan(it) }
        } else {
            spannable.setSpan(
                StyleSpan(Typeface.ITALIC),
                selectionStart,
                selectionEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    fun setAlignment(alignment: Alignment) {
        val spannable = editText.text as Spannable
        val selectionStart = editText.selectionStart
        val selectionEnd = editText.selectionEnd

        // Find paragraph boundaries for the selection
        val paragraphBounds = findParagraphBounds(spannable, selectionStart, selectionEnd)

        for ((paraStart, paraEnd) in paragraphBounds) {
            // Remove any existing alignment spans in this paragraph
            val existingAlignmentSpans = spannable.getSpans(paraStart, paraEnd, AlignmentSpan::class.java)
            existingAlignmentSpans.forEach { spannable.removeSpan(it) }

            // Remove any JustifySpan markers
            val existingJustifySpans = spannable.getSpans(paraStart, paraEnd, JustifySpan::class.java)
            existingJustifySpans.forEach { spannable.removeSpan(it) }

            // Apply new alignment span
            when (alignment) {
                Alignment.LEFT -> {
                    // LEFT is default, so just removing existing spans is enough
                }
                Alignment.CENTER -> {
                    spannable.setSpan(
                        AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                        paraStart,
                        paraEnd,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE or Spannable.SPAN_PARAGRAPH
                    )
                }
                Alignment.RIGHT -> {
                    spannable.setSpan(
                        AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE),
                        paraStart,
                        paraEnd,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE or Spannable.SPAN_PARAGRAPH
                    )
                }
                Alignment.JUSTIFY -> {
                    // Since Android doesn't have a JustificationSpan, we'll use a marker
                    // and handle justification in a custom TextView if needed
                    spannable.setSpan(
                        JustifySpan(),
                        paraStart,
                        paraEnd,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE or Spannable.SPAN_PARAGRAPH
                    )
                    // You could also apply both start alignment and a custom attribute
                    spannable.setSpan(
                        AlignmentSpan.Standard(Layout.Alignment.ALIGN_NORMAL),
                        paraStart,
                        paraEnd,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE or Spannable.SPAN_PARAGRAPH
                    )
                }
            }
        }
    }

    // Helper function to find paragraph boundaries
    private fun findParagraphBounds(text: CharSequence, selectionStart: Int, selectionEnd: Int): List<Pair<Int, Int>> {
        val paragraphs = mutableListOf<Pair<Int, Int>>()

        // Find the paragraph containing the selection start
        var paraStart = selectionStart
        while (paraStart > 0 && text[paraStart - 1] != '\n') {
            paraStart--
        }

        // Find all paragraphs in the selection
        var currentStart = paraStart
        while (currentStart <= selectionEnd && currentStart < text.length) {
            // Find the end of the current paragraph
            var paraEnd = currentStart
            while (paraEnd < text.length && text[paraEnd] != '\n') {
                paraEnd++
            }

            // Include the newline character in the paragraph if it exists
            if (paraEnd < text.length) {
                paraEnd++
            }

            // Add this paragraph if it's within our selection
            if (currentStart < selectionEnd || selectionStart == selectionEnd) {
                paragraphs.add(Pair(currentStart, paraEnd))
            }

            // Move to the next paragraph
            currentStart = paraEnd

            // If we've passed the selection end, we're done
            if (currentStart > selectionEnd && selectionStart != selectionEnd) {
                break
            }
        }

        return paragraphs
    }

    // Custom TypefaceSpan to support custom fonts
    class CustomTypefaceSpan(
        val customTypeface: Typeface,
        val resourceId: Int  // Add this to store the font resource ID
    ) : TypefaceSpan("") {

        override fun updateDrawState(textPaint: android.text.TextPaint) {
            applyCustomTypeface(textPaint)
        }

        override fun updateMeasureState(textPaint: android.text.TextPaint) {
            applyCustomTypeface(textPaint)
        }

        private fun applyCustomTypeface(paint: android.text.TextPaint) {
            val oldTypeface = paint.typeface
            val oldStyle = oldTypeface?.style ?: 0

            val fake = oldStyle and customTypeface.style.inv()
            if (fake and Typeface.BOLD != 0) {
                paint.isFakeBoldText = true
            }

            if (fake and Typeface.ITALIC != 0) {
                paint.textSkewX = -0.25f
            }

            paint.typeface = customTypeface
        }
    }

    fun applyFont(fontItem: FontItem) {
        val spannable = editText.text as Spannable
        val start = editText.selectionStart
        val end = editText.selectionEnd

        if (start != end) {
            // Remove existing CustomTypefaceSpan and TypefaceSpan in selection
            val existingCustomSpans = spannable.getSpans(start, end, CustomTypefaceSpan::class.java)
            existingCustomSpans.forEach { spannable.removeSpan(it) }

            val existingTypefaceSpans = spannable.getSpans(start, end, TypefaceSpan::class.java)
            existingTypefaceSpans.forEach { spannable.removeSpan(it) }

            // Apply new font using CustomTypefaceSpan with resource ID
            if (fontItem.resourceId != 0 && fontItem.typeface != null) {
                spannable.setSpan(
                    CustomTypefaceSpan(fontItem.typeface, fontItem.resourceId), // Pass resourceId
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        } else {
            // For new text when nothing is selected: Set the current font
            currentFont = fontItem
            if (fontItem.resourceId == 0) {
                editText.typeface = Typeface.DEFAULT
            } else {
                editText.typeface = fontItem.typeface
            }
        }
    }

    fun applyHeadingStyle(headingStyle: HeadingStyle) {
        val spannable = editText.text as Spannable
        val start = editText.selectionStart
        val end = editText.selectionEnd

        if (start != end) {
            // Remove existing RelativeSizeSpan in selection
            val existingSpans = spannable.getSpans(start, end, RelativeSizeSpan::class.java)
            existingSpans.forEach { spannable.removeSpan(it) }

            // Apply new size
            if (headingStyle.sizeFactor != 1.0f) {
                spannable.setSpan(
                    RelativeSizeSpan(headingStyle.sizeFactor),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    fun insertImage(imageUri: android.net.Uri) {
        try {
            val inputStream = context.contentResolver.openInputStream(imageUri)
            val drawable = android.graphics.drawable.Drawable.createFromStream(inputStream, imageUri.toString())
            inputStream?.close()

            drawable?.let {
                val intrinsicWidth = it.intrinsicWidth
                val intrinsicHeight = it.intrinsicHeight
                // Account for padding and indentation when calculating max width
                val maxWidth = editText.width - editText.paddingLeft - editText.paddingRight - tabIndentPixels

                // Scale image if needed
                if (intrinsicWidth > maxWidth) {
                    val scale = maxWidth.toFloat() / intrinsicWidth.toFloat()
                    val scaledHeight = (intrinsicHeight * scale).toInt()
                    it.setBounds(0, 0, maxWidth, scaledHeight)
                } else {
                    it.setBounds(0, 0, intrinsicWidth, intrinsicHeight)
                }

                val cursorPosition = editText.selectionStart
                val editable = editText.text

                // Insert newline, image placeholder, and another newline
                editable.insert(cursorPosition, "\n \n")

                // Apply image span to the space character
                editable.setSpan(
                    android.text.style.ImageSpan(it, android.text.style.ImageSpan.ALIGN_BASELINE),
                    cursorPosition + 1,
                    cursorPosition + 2,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                // Move cursor after the image
                editText.setSelection(cursorPosition + 3)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Call this when loading content to ensure indents are applied
    fun setContent(content: CharSequence) {
        editText.setText(content, TextView.BufferType.SPANNABLE)
        // Force apply indents after setting content
        applyParagraphIndents(editText.text)
    }

    // Optional: Get the current alignment of the paragraph at cursor position
    fun getCurrentAlignment(): Alignment {
        val spannable = editText.text as Spannable
        val cursorPos = editText.selectionStart

        // Find paragraph bounds
        var paraStart = cursorPos
        while (paraStart > 0 && spannable[paraStart - 1] != '\n') {
            paraStart--
        }

        // Check for alignment spans
        val alignmentSpans = spannable.getSpans(paraStart, cursorPos, AlignmentSpan::class.java)
        if (alignmentSpans.isNotEmpty()) {
            val span = alignmentSpans[0]
            return when {
                span is JustifySpan -> Alignment.JUSTIFY
                span is AlignmentSpan.Standard -> {
                    when (span.alignment) {
                        Layout.Alignment.ALIGN_CENTER -> Alignment.CENTER
                        Layout.Alignment.ALIGN_OPPOSITE -> Alignment.RIGHT
                        else -> Alignment.LEFT
                    }
                }
                else -> Alignment.LEFT
            }
        }

        return Alignment.LEFT
    }
}