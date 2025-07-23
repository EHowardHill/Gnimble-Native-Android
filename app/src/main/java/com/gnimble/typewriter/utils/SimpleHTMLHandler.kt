// SimpleHtmlHandler.kt
package com.gnimble.typewriter.utils

import android.text.style.*
import android.graphics.drawable.Drawable
import android.util.Base64
import java.io.ByteArrayOutputStream
import android.content.Context
import android.graphics.Typeface
import android.text.Html
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import androidx.core.content.res.ResourcesCompat
import com.gnimble.typewriter.R
import com.gnimble.typewriter.TypewriterView
import com.gnimble.typewriter.data.FontItem
import androidx.core.graphics.createBitmap

class SimpleHtmlHandler(private val context: Context) {

    // Store font items for lookup
    private val fontItems: List<FontItem> by lazy {
        initializeFontList()
    }

    private fun initializeFontList(): List<FontItem> {
        val fontItems = mutableListOf<FontItem>()

        // Add default font first
        fontItems.add(FontItem("Default", 0, Typeface.DEFAULT))

        // Get all font resources dynamically
        val fontFields = R.font::class.java.fields

        for (field in fontFields) {
            try {
                val resourceId = field.getInt(null)
                val fontName = formatFontName(field.name)
                val typeface = ResourcesCompat.getFont(context, resourceId)

                if (typeface != null) {
                    fontItems.add(FontItem(fontName, resourceId, typeface))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return fontItems
    }

    private fun formatFontName(resourceName: String): String {
        return resourceName
            .replace("_", " ")
            .split(" ")
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
    }

    fun spannableToHtml(spannable: Spannable): String {
        val sb = StringBuilder()
        sb.append("<html><body>")

        var lastPosition = 0
        val text = spannable.toString()

        // Group spans by their positions
        data class SpanInfo(val span: Any, val start: Int, val end: Int)
        val allSpans = mutableListOf<SpanInfo>()

        spannable.getSpans(0, spannable.length, Any::class.java).forEach { span ->
            allSpans.add(SpanInfo(span, spannable.getSpanStart(span), spannable.getSpanEnd(span)))
        }

        // Sort by start position
        allSpans.sortBy { it.start }

        // Track if we're in a paragraph with indentation
        var currentParagraphIndented = false
        var paragraphStart = 0

        // Process text with spans
        for (i in 0 until text.length) {
            // Check if this is the start of a new paragraph
            if (i == 0 || (i > 0 && text[i - 1] == '\n')) {
                paragraphStart = i
                // Check if this paragraph has FirstLineIndentSpan
                currentParagraphIndented = allSpans.any { spanInfo ->
                    spanInfo.span is TypewriterView.FirstLineIndentSpan &&
                            spanInfo.start <= i && spanInfo.end > i
                }

                if (currentParagraphIndented) {
                    sb.append("<p class=\"indented-paragraph\">")
                } else {
                    sb.append("<p>")
                }
            }

            // Check for spans starting at this position
            val startingSpans = allSpans.filter { it.start == i }
            startingSpans.forEach { spanInfo ->
                when (val span = spanInfo.span) {
                    is StyleSpan -> {
                        when (span.style) {
                            Typeface.BOLD -> sb.append("<b>")
                            Typeface.ITALIC -> sb.append("<i>")
                            Typeface.BOLD_ITALIC -> sb.append("<b><i>")
                        }
                    }
                    is AlignmentSpan.Standard -> {
                        val align = when (span.alignment) {
                            Layout.Alignment.ALIGN_CENTER -> "center"
                            Layout.Alignment.ALIGN_OPPOSITE -> "right"
                            else -> "left"
                        }
                        sb.append("<span data-alignment=\"$align\">")
                    }
                    is RelativeSizeSpan -> {
                        sb.append("<span style=\"font-size: ${span.sizeChange}em;\">")
                    }
                    is TypewriterView.CustomTypefaceSpan -> {
                        if (span.resourceId != 0) {
                            val fontItem = fontItems.find { it.resourceId == span.resourceId }
                            val fontName = fontItem?.name ?: ""
                            sb.append("<span data-font-resource-id=\"${span.resourceId}\" data-font-name=\"$fontName\">")
                        } else {
                            sb.append("<span>")
                        }
                    }
                    is TypefaceSpan -> {
                        // This will catch regular TypefaceSpan that aren't CustomTypefaceSpan
                        sb.append("<span style=\"font-family: '${span.family}';\">")
                    }
                    is ImageSpan -> {
                        val drawable = span.drawable
                        val base64Image = drawableToBase64(drawable)
                        sb.append("<img src=\"data:image/png;base64,$base64Image\" />")
                    }
                    is TypewriterView.FirstLineIndentSpan -> {
                        // Already handled at paragraph level
                    }
                }
            }

            // Add the character
            when (text[i]) {
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '&' -> sb.append("&amp;")
                '"' -> sb.append("&quot;")
                '\n' -> {
                    sb.append("</p>")
                }
                else -> sb.append(text[i])
            }

            // Check for spans ending at this position + 1
            val endingSpans = allSpans.filter { it.end == i + 1 }
            endingSpans.forEach { spanInfo ->
                when (val span = spanInfo.span) {
                    is StyleSpan -> {
                        when (span.style) {
                            Typeface.BOLD -> sb.append("</b>")
                            Typeface.ITALIC -> sb.append("</i>")
                            Typeface.BOLD_ITALIC -> sb.append("</i></b>")
                        }
                    }
                    is AlignmentSpan -> sb.append("</span>")
                    is RelativeSizeSpan -> sb.append("</span>")
                    is TypewriterView.CustomTypefaceSpan -> sb.append("</span>")
                    is TypefaceSpan -> sb.append("</span>")
                }
            }
        }

        // Close any open paragraph
        if (text.isEmpty() || text.last() != '\n') {
            sb.append("</p>")
        }

        sb.append("</body></html>")
        return sb.toString()
    }

    fun htmlToSpannable(html: String): Spannable {
        // First, parse the HTML to extract custom style information
        val customStyles = parseCustomStylesInfo(html)

        val spannableBuilder = SpannableStringBuilder()

        // Parse HTML with custom handling for style attributes
        val imageGetter = Html.ImageGetter { source ->
            if (source.startsWith("data:image/")) {
                try {
                    val base64Data = source.substring(source.indexOf(",") + 1)
                    val imageBytes = Base64.decode(base64Data, Base64.DEFAULT)
                    val drawable = Drawable.createFromStream(
                        imageBytes.inputStream(),
                        "image"
                    )
                    drawable?.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
                    drawable
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
        }

        // Create a modified HTML that preserves structure but removes custom attributes
        val modifiedHtml = removeCustomAttributes(html)

        // Basic HTML parsing
        val basicSpannable = Html.fromHtml(
            modifiedHtml,
            Html.FROM_HTML_MODE_COMPACT,
            imageGetter,
            null
        )

        spannableBuilder.append(basicSpannable)

        // Apply custom styles based on the extracted information
        applyCustomStyles(spannableBuilder, customStyles)

        // Restore paragraph indentation
        restoreParagraphIndentation(html, spannableBuilder)

        return spannableBuilder
    }

    private data class CustomStyleInfo(
        val type: String,
        val value: String,
        val content: String,
        val startTag: String,
        val endTag: String
    )

    private fun parseCustomStylesInfo(html: String): List<CustomStyleInfo> {
        val styles = mutableListOf<CustomStyleInfo>()

        // Parse font resource IDs
        val fontPattern = Regex("""(<span\s+data-font-resource-id="(\d+)"[^>]*>)(.*?)(</span>)""", RegexOption.DOT_MATCHES_ALL)
        fontPattern.findAll(html).forEach { match ->
            styles.add(CustomStyleInfo(
                type = "font-resource",
                value = match.groupValues[2],
                content = match.groupValues[3],
                startTag = match.groupValues[1],
                endTag = match.groupValues[4]
            ))
        }

        // Parse alignments
        val alignmentPattern = Regex("""(<span\s+data-alignment="(\w+)"[^>]*>)(.*?)(</span>)""", RegexOption.DOT_MATCHES_ALL)
        alignmentPattern.findAll(html).forEach { match ->
            styles.add(CustomStyleInfo(
                type = "alignment",
                value = match.groupValues[2],
                content = match.groupValues[3],
                startTag = match.groupValues[1],
                endTag = match.groupValues[4]
            ))
        }

        return styles
    }

    private fun removeCustomAttributes(html: String): String {
        var result = html
        // Remove custom data attributes but keep the span tags
        result = result.replace(Regex("""\s+data-font-resource-id="\d+""""), "")
        result = result.replace(Regex("""\s+data-font-name="[^"]*""""), "")
        result = result.replace(Regex("""\s+data-alignment="\w+""""), "")
        return result
    }

    private fun applyCustomStyles(spannable: SpannableStringBuilder, styles: List<CustomStyleInfo>) {
        for (style in styles) {
            // Get the plain text content (strip HTML tags)
            val plainContent = style.content
                .replace(Regex("<[^>]+>"), "")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .trim()

            if (plainContent.isEmpty()) continue

            // Find all occurrences of this content in the spannable
            var searchStart = 0
            while (searchStart < spannable.length) {
                val startIndex = spannable.toString().indexOf(plainContent, searchStart)
                if (startIndex == -1) break

                val endIndex = startIndex + plainContent.length

                when (style.type) {
                    "font-resource" -> {
                        val resourceId = style.value.toIntOrNull()
                        if (resourceId != null && resourceId != 0) {
                            // Check if this span already has a font applied
                            val existingFontSpans = spannable.getSpans(
                                startIndex, endIndex,
                                TypewriterView.CustomTypefaceSpan::class.java
                            )

                            if (existingFontSpans.isEmpty()) {
                                val typeface = try {
                                    ResourcesCompat.getFont(context, resourceId)
                                } catch (e: Exception) {
                                    null
                                }

                                if (typeface != null) {
                                    spannable.setSpan(
                                        TypewriterView.CustomTypefaceSpan(typeface, resourceId),
                                        startIndex,
                                        endIndex,
                                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                                    )
                                }
                            }
                        }
                    }
                    "alignment" -> {
                        val alignment = when (style.value) {
                            "center" -> Layout.Alignment.ALIGN_CENTER
                            "right" -> Layout.Alignment.ALIGN_OPPOSITE
                            else -> Layout.Alignment.ALIGN_NORMAL
                        }

                        spannable.setSpan(
                            AlignmentSpan.Standard(alignment),
                            startIndex,
                            endIndex,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }

                searchStart = startIndex + 1
            }
        }
    }

    private fun restoreParagraphIndentation(html: String, spannable: SpannableStringBuilder) {
        // Calculate tab indent (1/4 inch in pixels)
        val tabIndentPixels = (0.25f * context.resources.displayMetrics.densityDpi).toInt()

        // Find all indented paragraphs
        val indentedParagraphPattern = Regex("""<p\s+class="indented-paragraph">(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)

        indentedParagraphPattern.findAll(html).forEach { matchResult ->
            val content = matchResult.groupValues[1]
            // Remove any nested HTML tags to get plain text
            val plainContent = content.replace(Regex("<[^>]+>"), "")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")

            // Find this paragraph in the spannable
            var searchStart = 0
            while (searchStart < spannable.length) {
                val startIndex = spannable.toString().indexOf(plainContent, searchStart)
                if (startIndex == -1) break

                // Find the end of this paragraph (next newline or end of text)
                var endIndex = spannable.toString().indexOf('\n', startIndex)
                if (endIndex == -1) {
                    endIndex = spannable.length
                } else {
                    endIndex++ // Include the newline
                }

                // Apply FirstLineIndentSpan
                spannable.setSpan(
                    TypewriterView.FirstLineIndentSpan(tabIndentPixels),
                    startIndex,
                    endIndex,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE or Spannable.SPAN_PARAGRAPH
                )
                break
            }
        }
    }

    private fun drawableToBase64(drawable: Drawable): String {
        val bitmap = createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)

        val baos = ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
        val imageBytes = baos.toByteArray()
        return Base64.encodeToString(imageBytes, Base64.NO_WRAP)
    }
}