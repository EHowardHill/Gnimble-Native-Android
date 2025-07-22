// TypewriterView.kt
package com.gnimble.typewriter

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import android.graphics.RenderEffect
import android.graphics.Shader
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
    }

    enum class Alignment {
        LEFT, CENTER, RIGHT
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
        when (alignment) {
            Alignment.LEFT -> editText.gravity = Gravity.TOP or Gravity.START
            Alignment.CENTER -> editText.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            Alignment.RIGHT -> editText.gravity = Gravity.TOP or Gravity.END
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

    fun setTextSize(relativeSizeFactor: Float) {
        val spannable = editText.text as Spannable
        val selectionStart = editText.selectionStart
        val selectionEnd = editText.selectionEnd

        if (selectionStart != selectionEnd) {
            // Remove existing size spans
            val existingSpans = spannable.getSpans(selectionStart, selectionEnd, android.text.style.RelativeSizeSpan::class.java)
            existingSpans.forEach { spannable.removeSpan(it) }

            // Apply new size if not normal
            if (relativeSizeFactor != 1.0f) {
                spannable.setSpan(
                    android.text.style.RelativeSizeSpan(relativeSizeFactor),
                    selectionStart,
                    selectionEnd,
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
                val maxWidth = editText.width - editText.paddingLeft - editText.paddingRight

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
}