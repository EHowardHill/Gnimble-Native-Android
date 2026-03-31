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

        fontItems.add(FontItem("Default", "default", 0, Typeface.DEFAULT))

        val fontFields = R.font::class.java.fields

        for (field in fontFields) {
            try {
                val resourceId = field.getInt(null)
                val resourceEntryName = field.name
                val fontName = formatFontName(field.name)
                val typeface = ResourcesCompat.getFont(context, resourceId)

                if (typeface != null) {
                    fontItems.add(FontItem(fontName, resourceEntryName, resourceId, typeface))
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

    fun spannableToHtml(spannable: Spannable, includeWrapper: Boolean = true): String {
        val sb = StringBuilder()
        if (includeWrapper) {
            sb.append("<html><body>")
        }

        val text = spannable.toString()

        data class SpanInfo(val span: Any, val start: Int, val end: Int)
        val allSpans = mutableListOf<SpanInfo>()

        spannable.getSpans(0, spannable.length, Any::class.java).forEach { span ->
            allSpans.add(SpanInfo(span, spannable.getSpanStart(span), spannable.getSpanEnd(span)))
        }
        allSpans.sortBy { it.start }

        var paragraphStart = 0

        for (i in 0 until text.length) {
            if (i == 0 || (i > 0 && text[i - 1] == '\n')) {
                paragraphStart = i
                val currentParagraphIndented = allSpans.any { spanInfo ->
                    spanInfo.span is TypewriterView.FirstLineIndentSpan &&
                            spanInfo.start <= i && spanInfo.end > i
                }
                if (currentParagraphIndented) {
                    sb.append("<p class=\"indented-paragraph\">")
                } else {
                    sb.append("<p>")
                }
            }

            // Start Spans
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
                        sb.append("<span style=\"font-size: ${span.sizeChange}em;\" data-font-size=\"${span.sizeChange}\">")
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
                    is ImageSpan -> {
                        val drawable = span.drawable
                        val base64Image = drawableToBase64(drawable)
                        sb.append("<img src=\"data:image/png;base64,$base64Image\" />")
                    }
                }
            }

            // Append Text
            when (text[i]) {
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '&' -> sb.append("&amp;")
                '"' -> sb.append("&quot;")
                '\n' -> sb.append("</p>")
                else -> sb.append(text[i])
            }

            // End Spans
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
                }
            }
        }

        if (text.isEmpty() || text.last() != '\n') {
            sb.append("</p>")
        }
        if (includeWrapper) {
            sb.append("</body></html>")
        }
        return sb.toString()
    }

    fun htmlToSpannable(html: String): Spannable {
        val customStyles = parseCustomStylesInfo(html)

        var markerMap = mutableMapOf<String, CustomStyleInfo>()
        var modifiedHtml = html
        var markerIndex = 0

        for (style in customStyles) {
            val startMarker = "\uE000${markerIndex}\uE001"
            val endMarker = "\uE002${markerIndex}\uE003"
            markerIndex++

            val fullOriginal = style.startTag + style.content + style.endTag
            val replacement = startMarker + style.content + endMarker

            val idx = modifiedHtml.indexOf(fullOriginal)
            if (idx != -1) {
                modifiedHtml = modifiedHtml.substring(0, idx) + replacement + modifiedHtml.substring(idx + fullOriginal.length)
                markerMap[markerIndex.toString()] = style
            }
        }

        // BUG FIX #1: Also mark indented paragraphs with markers so we can restore
        // them at exact positions instead of using text-content matching.
        data class IndentMarker(val markerIdx: Int)
        val indentMarkerMap = mutableMapOf<Int, Boolean>()
        val indentPattern = Regex("""<p\s+class="indented-paragraph">""")

        // Replace indented paragraph tags with marked versions
        var indentIdx = markerIndex + 1000 // Offset to avoid collision with style markers
        modifiedHtml = indentPattern.replace(modifiedHtml) { _ ->
            val startMarker = "\uE000INDENT${indentIdx}\uE001"
            indentMarkerMap[indentIdx] = true
            indentIdx++
            "<p>$startMarker"
        }

        modifiedHtml = removeCustomAttributes(modifiedHtml)

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

        val basicSpannable = Html.fromHtml(
            modifiedHtml,
            Html.FROM_HTML_MODE_COMPACT,
            imageGetter,
            null
        )

        val spannableBuilder = SpannableStringBuilder(basicSpannable)

        // Now find markers in the resulting spannable and apply custom styles at exact positions
        val text = spannableBuilder.toString()

        data class MarkerLocation(val markerIdx: String, val startMarkerPos: Int, val startMarkerEnd: Int, val endMarkerPos: Int, val endMarkerEnd: Int)
        val markerLocations = mutableListOf<MarkerLocation>()

        for ((mIdx, _) in markerMap) {
            val actualIdx = mIdx.toInt() - 1
            val startMarker = "\uE000${actualIdx}\uE001"
            val endMarker = "\uE002${actualIdx}\uE003"

            val startPos = text.indexOf(startMarker)
            if (startPos == -1) continue
            val startEnd = startPos + startMarker.length

            val endPos = text.indexOf(endMarker, startEnd)
            if (endPos == -1) continue
            val endEnd = endPos + endMarker.length

            markerLocations.add(MarkerLocation(mIdx, startPos, startEnd, endPos, endEnd))
        }

        // Also collect indent marker locations
        data class IndentLocation(val markerIdx: Int, val markerPos: Int, val markerEnd: Int)
        val indentLocations = mutableListOf<IndentLocation>()

        for ((iIdx, _) in indentMarkerMap) {
            val marker = "\uE000INDENT${iIdx}\uE001"
            val pos = spannableBuilder.toString().indexOf(marker)
            if (pos != -1) {
                indentLocations.add(IndentLocation(iIdx, pos, pos + marker.length))
            }
        }

        // Sort all removals by position descending for safe deletion
        markerLocations.sortByDescending { it.startMarkerPos }
        indentLocations.sortByDescending { it.markerPos }

        // Process style markers
        for (loc in markerLocations) {
            val style = markerMap[loc.markerIdx] ?: continue

            spannableBuilder.delete(loc.endMarkerPos, loc.endMarkerEnd)
            spannableBuilder.delete(loc.startMarkerPos, loc.startMarkerEnd)

            val contentStart = loc.startMarkerPos
            val contentEnd = loc.endMarkerPos - (loc.startMarkerEnd - loc.startMarkerPos)

            if (contentStart >= contentEnd || contentStart < 0 || contentEnd > spannableBuilder.length) continue

            when (style.type) {
                "font-resource" -> {
                    val resourceId = style.value.toIntOrNull()
                    if (resourceId != null && resourceId != 0) {
                        try {
                            val typeface = ResourcesCompat.getFont(context, resourceId)
                            if (typeface != null) {
                                spannableBuilder.setSpan(
                                    TypewriterView.CustomTypefaceSpan(typeface, resourceId),
                                    contentStart, contentEnd,
                                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                                )
                            }
                        } catch (e: Exception) { /* Ignore */ }
                    }
                }
                "alignment" -> {
                    val alignment = when (style.value) {
                        "center" -> Layout.Alignment.ALIGN_CENTER
                        "right" -> Layout.Alignment.ALIGN_OPPOSITE
                        else -> Layout.Alignment.ALIGN_NORMAL
                    }
                    spannableBuilder.setSpan(
                        AlignmentSpan.Standard(alignment),
                        contentStart, contentEnd,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                "font-size" -> {
                    val size = style.value.toFloatOrNull()
                    if (size != null && size != 1.0f) {
                        spannableBuilder.setSpan(
                            RelativeSizeSpan(size),
                            contentStart, contentEnd,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
            }
        }

        // BUG FIX #1: Process indent markers — apply indentation at exact marker positions
        // instead of searching by text content (which matches the wrong paragraph when
        // multiple paragraphs have identical text).
        val tabIndentPixels = (0.25f * context.resources.displayMetrics.densityDpi).toInt()

        for (loc in indentLocations) {
            // Remove the marker characters
            // Re-find marker position since earlier deletions may have shifted positions
            val currentText = spannableBuilder.toString()
            val marker = "\uE000INDENT${loc.markerIdx}\uE001"
            val pos = currentText.indexOf(marker)
            if (pos == -1) continue

            spannableBuilder.delete(pos, pos + marker.length)

            // Find the paragraph boundaries around this position
            val paraStart = pos
            val updatedText = spannableBuilder.toString()
            var paraEnd = updatedText.indexOf('\n', paraStart)
            if (paraEnd == -1) {
                paraEnd = spannableBuilder.length
            } else {
                paraEnd++ // Include the newline
            }

            if (paraEnd > paraStart) {
                spannableBuilder.setSpan(
                    TypewriterView.FirstLineIndentSpan(tabIndentPixels),
                    paraStart,
                    paraEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE or Spannable.SPAN_PARAGRAPH
                )
            }
        }

        return spannableBuilder
    }

    data class CustomStyleInfo(
        val type: String,
        val value: String,
        val content: String,
        val startTag: String,
        val endTag: String
    )

    private fun parseCustomStylesInfo(html: String): List<CustomStyleInfo> {
        val styles = mutableListOf<CustomStyleInfo>()

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

        val fontSizePattern = Regex("""(<span\s+[^>]*?data-font-size="([\d.]+)"[^>]*>)(.*?)(</span>)""", RegexOption.DOT_MATCHES_ALL)
        fontSizePattern.findAll(html).forEach { match ->
            styles.add(CustomStyleInfo(
                type = "font-size",
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
        result = result.replace(Regex("""\s+data-font-resource-id="\d+""""), "")
        result = result.replace(Regex("""\s+data-font-name="[^"]*""""), "")
        result = result.replace(Regex("""\s+data-alignment="\w+""""), "")
        result = result.replace(Regex("""\s+data-font-size="[\d.]*""""), "")
        return result
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