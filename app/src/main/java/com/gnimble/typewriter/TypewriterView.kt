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
    class JustifySpan : AlignmentSpan {
        override fun getAlignment(): Layout.Alignment {
            return Layout.Alignment.ALIGN_NORMAL
        }
    }

    // Text watcher to handle paragraph indentation
    private inner class ParagraphIndentWatcher : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: android.text.Editable?) {
            if (s == null) return

            editText.removeTextChangedListener(this)
            applyParagraphIndents(s)
            editText.addTextChangedListener(this)
        }
    }

    private fun applyParagraphIndents(spannable: android.text.Editable) {
        val existingIndents = spannable.getSpans(0, spannable.length, FirstLineIndentSpan::class.java)
        existingIndents.forEach { spannable.removeSpan(it) }

        var paragraphStart = 0
        while (paragraphStart < spannable.length) {
            var paragraphEnd = spannable.indexOf('\n', paragraphStart)
            if (paragraphEnd == -1) {
                paragraphEnd = spannable.length
            } else {
                paragraphEnd++
            }

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

    // BUG FIX #12: Refactored toggleBold and toggleItalic to handle partial overlap.
    // When a style span only partially overlaps the selection, the old code removed the
    // entire span (un-styling text outside the selection). The new code splits partially
    // overlapping spans so that text outside the selection retains its styling.

    fun toggleBold() {
        toggleStyle(Typeface.BOLD)
    }

    fun toggleItalic() {
        toggleStyle(Typeface.ITALIC)
    }

    private fun toggleStyle(style: Int) {
        val spannable = editText.text as Spannable
        val selectionStart = editText.selectionStart
        val selectionEnd = editText.selectionEnd

        if (selectionStart == selectionEnd) return

        val styleSpans = spannable.getSpans(selectionStart, selectionEnd, StyleSpan::class.java)
        val matchingSpans = styleSpans.filter { it.style == style }

        if (matchingSpans.isNotEmpty()) {
            // Remove the style from the selection, but preserve it outside the selection
            for (span in matchingSpans) {
                val spanStart = spannable.getSpanStart(span)
                val spanEnd = spannable.getSpanEnd(span)
                val spanFlags = spannable.getSpanFlags(span)

                // Remove the original span
                spannable.removeSpan(span)

                // Re-apply to the portion before the selection (if any)
                if (spanStart < selectionStart) {
                    spannable.setSpan(
                        StyleSpan(style),
                        spanStart,
                        selectionStart,
                        spanFlags
                    )
                }

                // Re-apply to the portion after the selection (if any)
                if (spanEnd > selectionEnd) {
                    spannable.setSpan(
                        StyleSpan(style),
                        selectionEnd,
                        spanEnd,
                        spanFlags
                    )
                }
            }
        } else {
            spannable.setSpan(
                StyleSpan(style),
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

        val paragraphBounds = findParagraphBounds(spannable, selectionStart, selectionEnd)

        for ((paraStart, paraEnd) in paragraphBounds) {
            val existingAlignmentSpans = spannable.getSpans(paraStart, paraEnd, AlignmentSpan::class.java)
            existingAlignmentSpans.forEach { spannable.removeSpan(it) }

            val existingJustifySpans = spannable.getSpans(paraStart, paraEnd, JustifySpan::class.java)
            existingJustifySpans.forEach { spannable.removeSpan(it) }

            when (alignment) {
                Alignment.LEFT -> {
                    // LEFT is default, removing existing spans is enough
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
                    spannable.setSpan(
                        JustifySpan(),
                        paraStart,
                        paraEnd,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE or Spannable.SPAN_PARAGRAPH
                    )
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

    private fun findParagraphBounds(text: CharSequence, selectionStart: Int, selectionEnd: Int): List<Pair<Int, Int>> {
        val paragraphs = mutableListOf<Pair<Int, Int>>()

        var paraStart = selectionStart
        while (paraStart > 0 && text[paraStart - 1] != '\n') {
            paraStart--
        }

        var currentStart = paraStart
        while (currentStart <= selectionEnd && currentStart < text.length) {
            var paraEnd = currentStart
            while (paraEnd < text.length && text[paraEnd] != '\n') {
                paraEnd++
            }

            if (paraEnd < text.length) {
                paraEnd++
            }

            if (currentStart < selectionEnd || selectionStart == selectionEnd) {
                paragraphs.add(Pair(currentStart, paraEnd))
            }

            currentStart = paraEnd

            if (currentStart > selectionEnd && selectionStart != selectionEnd) {
                break
            }
        }

        return paragraphs
    }

    // Custom TypefaceSpan to support custom fonts
    class CustomTypefaceSpan(
        val customTypeface: Typeface,
        val resourceId: Int
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

    fun setGlobalFont(fontItem: FontItem) {
        currentFont = fontItem

        if (fontItem.resourceId == 0) {
            editText.typeface = Typeface.DEFAULT
        } else {
            editText.typeface = fontItem.typeface
        }

        // CLEANUP: Remove any legacy CustomTypefaceSpans
        val spannable = editText.text as Spannable
        val spans = spannable.getSpans(0, spannable.length, CustomTypefaceSpan::class.java)
        spans.forEach { spannable.removeSpan(it) }
    }

    fun applyHeadingStyle(headingStyle: HeadingStyle) {
        val spannable = editText.text as Spannable
        val start = editText.selectionStart
        val end = editText.selectionEnd

        if (start != end) {
            val existingSpans = spannable.getSpans(start, end, RelativeSizeSpan::class.java)
            existingSpans.forEach { spannable.removeSpan(it) }

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
                val maxWidth = editText.width - editText.paddingLeft - editText.paddingRight - tabIndentPixels

                if (intrinsicWidth > maxWidth) {
                    val scale = maxWidth.toFloat() / intrinsicWidth.toFloat()
                    val scaledHeight = (intrinsicHeight * scale).toInt()
                    it.setBounds(0, 0, maxWidth, scaledHeight)
                } else {
                    it.setBounds(0, 0, intrinsicWidth, intrinsicHeight)
                }

                val cursorPosition = editText.selectionStart
                val editable = editText.text

                editable.insert(cursorPosition, "\n \n")

                editable.setSpan(
                    android.text.style.ImageSpan(it, android.text.style.ImageSpan.ALIGN_BASELINE),
                    cursorPosition + 1,
                    cursorPosition + 2,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                editText.setSelection(cursorPosition + 3)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setContent(content: CharSequence) {
        editText.setText(content, TextView.BufferType.SPANNABLE)
        applyParagraphIndents(editText.text)
    }

    fun getCurrentAlignment(): Alignment {
        val spannable = editText.text as Spannable
        val cursorPos = editText.selectionStart

        var paraStart = cursorPos
        while (paraStart > 0 && spannable[paraStart - 1] != '\n') {
            paraStart--
        }

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