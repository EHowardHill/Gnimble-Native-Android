package com.gnimble.typewriter.utils

import android.content.Context
import android.graphics.Typeface
import android.text.Html
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.AlignmentSpan
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Xml
import com.gnimble.typewriter.FontItem
import com.gnimble.typewriter.TypewriterView
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlSerializer
import java.io.StringWriter

/**
 * Helper class to serialize and deserialize formatted text content
 */
class FormattedContentHelper(private val context: Context) {

    companion object {
        private const val CUSTOM_FONT_TAG = "font"
        private const val CUSTOM_SIZE_TAG = "size"
        private const val CUSTOM_IMAGE_TAG = "img"

        // Custom attributes
        private const val ATTR_FONT_RES = "data-font-res"
        private const val ATTR_FONT_NAME = "data-font-name"
        private const val ATTR_SIZE_FACTOR = "data-size-factor"
        private const val ATTR_IMAGE_URI = "data-image-uri"
    }

    /**
     * Convert Spannable text to HTML string with custom attributes
     */
    fun spannableToHtml(text: Spannable): String {
        val serializer = Xml.newSerializer()
        val writer = StringWriter()

        try {
            serializer.setOutput(writer)
            serializer.startDocument("UTF-8", true)
            serializer.startTag("", "content")

            // Process the spannable text
            processSpans(serializer, text, 0, text.length)

            serializer.endTag("", "content")
            serializer.endDocument()

            return writer.toString()
        } catch (e: Exception) {
            // Fallback to basic HTML
            return Html.toHtml(text, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE)
        }
    }

    /**
     * Convert HTML string back to Spannable with custom formatting
     */
    fun htmlToSpannable(html: String, fontLoader: (Int, String) -> FontItem?): Spannable {
        return try {
            // Parse custom HTML
            parseCustomHtml(html, fontLoader)
        } catch (e: Exception) {
            // Fallback to basic HTML parsing
            SpannableStringBuilder(Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT))
        }
    }

    private fun processSpans(serializer: XmlSerializer, text: Spannable, start: Int, end: Int) {
        // Get all spans in the range
        val spans = text.getSpans(start, end, Any::class.java)

        // Sort spans by start position
        val sortedSpans = spans.sortedBy { text.getSpanStart(it) }

        var currentPos = start

        for (span in sortedSpans) {
            val spanStart = text.getSpanStart(span)
            val spanEnd = text.getSpanEnd(span)

            // Write text before span
            if (currentPos < spanStart) {
                serializer.text(text.substring(currentPos, spanStart))
            }

            // Write span with appropriate tag
            when (span) {
                is StyleSpan -> {
                    when (span.style) {
                        Typeface.BOLD -> {
                            serializer.startTag("", "b")
                            serializer.text(text.substring(spanStart, spanEnd))
                            serializer.endTag("", "b")
                        }
                        Typeface.ITALIC -> {
                            serializer.startTag("", "i")
                            serializer.text(text.substring(spanStart, spanEnd))
                            serializer.endTag("", "i")
                        }
                    }
                }
                is TypewriterView.CustomTypefaceSpan -> {
                    serializer.startTag("", CUSTOM_FONT_TAG)
                    // Store font information as attributes
                    // Note: You'll need to enhance CustomTypefaceSpan to store font resource ID
                    serializer.attribute("", ATTR_FONT_NAME, "custom_font")
                    serializer.text(text.substring(spanStart, spanEnd))
                    serializer.endTag("", CUSTOM_FONT_TAG)
                }
                is RelativeSizeSpan -> {
                    serializer.startTag("", CUSTOM_SIZE_TAG)
                    serializer.attribute("", ATTR_SIZE_FACTOR, span.sizeChange.toString())
                    serializer.text(text.substring(spanStart, spanEnd))
                    serializer.endTag("", CUSTOM_SIZE_TAG)
                }
                is ImageSpan -> {
                    serializer.startTag("", CUSTOM_IMAGE_TAG)
                    // Store image URI or path
                    serializer.attribute("", ATTR_IMAGE_URI, span.source ?: "")
                    serializer.endTag("", CUSTOM_IMAGE_TAG)
                }
                is AlignmentSpan -> {
                    // Handle alignment spans
                    /*
                    val alignment = when ((span as AlignmentSpan.Standard).alignment) {
                        Layout.Alignment.ALIGN_CENTER -> "center"
                        Layout.Alignment.ALIGN_OPPOSITE -> "right"
                        else -> "left"
                    }
                    */
                    serializer.startTag("", "p")
                    //serializer.attribute("", "align", alignment)
                    serializer.text(text.substring(spanStart, spanEnd))
                    serializer.endTag("", "p")
                }
            }

            currentPos = spanEnd
        }

        // Write remaining text
        if (currentPos < end) {
            serializer.text(text.substring(currentPos, end))
        }
    }

    private fun parseCustomHtml(html: String, fontLoader: (Int, String) -> FontItem?): Spannable {
        // This is a simplified version - you'd need a proper XML parser
        // For production, consider using a library like jsoup for Android
        val spannable = SpannableStringBuilder()

        // Basic implementation - enhance with proper XML parsing
        val processed = html
            .replace("<b>", "<<BOLD>>")
            .replace("</b>", "<</BOLD>>")
            .replace("<i>", "<<ITALIC>>")
            .replace("</i>", "<</ITALIC>>")

        // Parse and apply spans
        // This is a placeholder - implement proper parsing logic

        return spannable
    }

    /**
     * Alternative: Store as JSON for more flexibility
     */
    fun spannableToJson(text: Spannable): String {
        val json = JSONObject()
        val content = text.toString()
        json.put("text", content)

        val spans = JSONArray()
        val allSpans = text.getSpans(0, text.length, Any::class.java)

        for (span in allSpans) {
            val spanJson = JSONObject()
            spanJson.put("start", text.getSpanStart(span))
            spanJson.put("end", text.getSpanEnd(span))
            spanJson.put("flags", text.getSpanFlags(span))

            when (span) {
                is StyleSpan -> {
                    spanJson.put("type", "style")
                    spanJson.put("style", span.style)
                }
                is TypewriterView.CustomTypefaceSpan -> {
                    spanJson.put("type", "font")
                    // Add font resource ID or name
                }
                is RelativeSizeSpan -> {
                    spanJson.put("type", "size")
                    spanJson.put("factor", span.sizeChange)
                }
                is ImageSpan -> {
                    spanJson.put("type", "image")
                    spanJson.put("source", span.source ?: "")
                }
                is AlignmentSpan.Standard -> {
                    spanJson.put("type", "alignment")
                    spanJson.put("alignment", span.alignment.toString())
                }
            }

            spans.put(spanJson)
        }

        json.put("spans", spans)
        return json.toString()
    }

    fun jsonToSpannable(jsonString: String, fontLoader: (Int, String) -> FontItem?): Spannable {
        val json = JSONObject(jsonString)
        val text = json.getString("text")
        val spannable = SpannableStringBuilder(text)

        val spans = json.getJSONArray("spans")
        for (i in 0 until spans.length()) {
            val spanJson = spans.getJSONObject(i)
            val start = spanJson.getInt("start")
            val end = spanJson.getInt("end")
            val flags = spanJson.getInt("flags")

            val span = when (spanJson.getString("type")) {
                "style" -> StyleSpan(spanJson.getInt("style"))
                "size" -> RelativeSizeSpan(spanJson.getDouble("factor").toFloat())
                /*
                "alignment" -> {
                    val alignment = when (spanJson.getString("alignment")) {
                        "ALIGN_CENTER" -> Layout.Alignment.ALIGN_CENTER
                        "ALIGN_OPPOSITE" -> Layout.Alignment.ALIGN_OPPOSITE
                        else -> Layout.Alignment.ALIGN_NORMAL
                    }
                    AlignmentSpan.Standard(alignment)
                }
                */
                // Handle other span types
                else -> null
            }

            span?.let {
                spannable.setSpan(it, start, end, flags)
            }
        }

        return spannable
    }
}