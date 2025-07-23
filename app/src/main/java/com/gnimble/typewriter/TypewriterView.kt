// TypewriterView.kt
package com.gnimble.typewriter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.Spannable
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.text.LineBreaker
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
        binding.blurBackground.setRenderEffect(
            RenderEffect.createBlurEffect(
                25f, // radiusX
                25f, // radiusY
                Shader.TileMode.CLAMP
            )
        )

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
        val italicSpans = styleSpans.filter { it.style == android.graphics.Typeface.ITALIC }

        if (italicSpans.isNotEmpty()) {
            italicSpans.forEach { spannable.removeSpan(it) }
        } else {
            spannable.setSpan(
                StyleSpan(android.graphics.Typeface.ITALIC),
                selectionStart,
                selectionEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    fun setAlignment(alignment: Alignment) {
        editText.justificationMode = LineBreaker.JUSTIFICATION_MODE_INTER_WORD

        when (alignment) {
            Alignment.LEFT -> {
                editText.gravity = Gravity.TOP or Gravity.START
                editText.justificationMode = LineBreaker.JUSTIFICATION_MODE_NONE
            }
            Alignment.CENTER -> {
                editText.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                editText.justificationMode = LineBreaker.JUSTIFICATION_MODE_NONE
            }
            Alignment.RIGHT -> {
                editText.gravity = Gravity.TOP or Gravity.END
                editText.justificationMode = LineBreaker.JUSTIFICATION_MODE_NONE
            }
            Alignment.JUSTIFY -> {
                editText.gravity = Gravity.TOP or Gravity.START
                editText.justificationMode = LineBreaker.JUSTIFICATION_MODE_INTER_WORD
            }
        }
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
}