// ShareActivity.kt - Enhanced with font support
package com.gnimble.typewriter

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.Html
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gnimble.typewriter.data.ContentFormat
import com.gnimble.typewriter.databinding.ActivityShareBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.BarcodeEncoder
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.*
import androidx.core.net.toUri

class ShareActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShareBinding
    private var webServer: BookWebServer? = null
    private val PORT = 8888

    // Font mapping between local resources and Google Fonts
    companion object {
        // Map of resource field names to Google Fonts URLs and family names
        // Update these mappings based on your actual fonts in /res/font/
        private val FONT_MAPPINGS = mapOf(
            "cardo" to FontMapping("Cardo", "https://fonts.googleapis.com/css2?family=Cardo:ital,wght@0,400;0,700;1,400&display=swap"),
            "crimson_text" to FontMapping("Crimson Text", "https://fonts.googleapis.com/css2?family=Crimson+Text:ital,wght@0,400;0,600;0,700;1,400;1,600;1,700&display=swap", "700"),
            "eb_garamond" to FontMapping("EB Garamond", "https://fonts.googleapis.com/css2?family=EB+Garamond:ital,wght@0,400..800;1,400..800&display=swap", "400", "italic"),
            "young_serif" to FontMapping("Young Serif", "https://fonts.googleapis.com/css2?family=Young+Serif&display=swap"),
        )

        data class FontMapping(
            val familyName: String,
            val googleFontUrl: String,
            val weight: String = "400",
            val style: String = "normal"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityShareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get book data from intent - including formatted content
        val bookTitle = intent.getStringExtra("book_title") ?: "Unknown Title"
        val bookSubtitle = intent.getStringExtra("book_subtitle") ?: ""
        val bookContent = intent.getStringExtra("book_content") ?: ""
        val formattedContent = intent.getStringExtra("book_formatted_content")
        val contentFormat = intent.getStringExtra("book_content_format")?.let {
            ContentFormat.valueOf(it)
        } ?: ContentFormat.PLAIN_TEXT
        val bookCoverPath = intent.getStringExtra("book_cover_path") ?: ""

        // Start the web server with formatted content support
        startWebServer(bookTitle, bookSubtitle, bookContent, formattedContent, contentFormat, bookCoverPath)

        binding.stopServerButton.setOnClickListener {
            stopWebServer()
            finish()
        }
    }

    private fun startWebServer(
        title: String,
        subtitle: String,
        content: String,
        formattedContent: String?,
        contentFormat: ContentFormat,
        coverPath: String
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ipAddress = getLocalIpAddress()
                val serverUrl = "http://$ipAddress:$PORT"

                // Create and start the server with formatted content
                webServer = BookWebServer(PORT, title, subtitle, content, formattedContent, contentFormat, coverPath, this@ShareActivity)
                webServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

                withContext(Dispatchers.Main) {
                    binding.serverUrlText.text = serverUrl
                    binding.instructionsText.text = "Share is active! Others can connect to your book by:\n" +
                            "1. Scanning the QR code below, or\n" +
                            "2. Entering this URL in their browser: $serverUrl\n\n" +
                            "Make sure devices are on the same Wi-Fi network."

                    // Generate QR code
                    generateQRCode(serverUrl)

                    Toast.makeText(this@ShareActivity, "Server started on $serverUrl", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("ShareActivity", "Error starting server", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ShareActivity, "Failed to start server: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private inner class BookWebServer(
        port: Int,
        private val title: String,
        private val subtitle: String,
        private val content: String,
        private val formattedContent: String?,
        private val contentFormat: ContentFormat,
        private val coverPath: String,
        private val context: android.content.Context
    ) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri

            // Handle image requests
            if (uri.startsWith("/image/")) {
                return serveImage(uri)
            }

            // Handle cover image requests
            if (uri == "/cover" && coverPath.isNotEmpty()) {
                return serveCoverImage()
            }

            // Serve the main HTML content
            return newFixedLengthResponse(Response.Status.OK, "text/html", generateHtmlContent())
        }

        private fun serveImage(uri: String): Response {
            try {
                // Extract image identifier from URI
                val imageId = uri.substring("/image/".length)
                // In a real implementation, you'd map this to actual image files
                // For now, return a placeholder or 404
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Image not found")
            } catch (e: Exception) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error serving image")
            }
        }

        private fun serveCoverImage(): Response {
            try {
                val coverUri = coverPath.toUri()
                val inputStream = context.contentResolver.openInputStream(coverUri)
                if (inputStream != null) {
                    val bytes = inputStream.readBytes()
                    inputStream.close()

                    // Determine MIME type
                    val mimeType = context.contentResolver.getType(coverUri) ?: "image/jpeg"

                    return newFixedLengthResponse(Response.Status.OK, mimeType, bytes.inputStream(), bytes.size.toLong())
                }
            } catch (e: Exception) {
                Log.e("BookWebServer", "Error serving cover image", e)
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Cover image not found")
        }

        private fun generateHtmlContent(): String {
            // Extract used fonts from the formatted content
            val usedFonts = extractUsedFonts(formattedContent ?: "")

            // Generate Google Fonts links
            val fontLinks = generateFontLinks(usedFonts)

            // Generate CSS for font classes
            val fontStyles = generateFontStyles(usedFonts)

            val bodyContent = when (contentFormat) {
                ContentFormat.HTML -> {
                    // Use the formatted HTML content directly - DO NOT escape it
                    processFormattedHtml(formattedContent ?: content)
                }
                ContentFormat.JSON -> {
                    // Convert JSON formatted content to HTML
                    // This would require parsing the JSON and converting to HTML
                    convertPlainTextToHtml(content) // Fallback for now
                }
                ContentFormat.PLAIN_TEXT -> {
                    // Convert plain text to HTML with paragraph formatting
                    convertPlainTextToHtml(content)
                }
            }

            val coverImageHtml = if (coverPath.isNotEmpty()) {
                """
                <div class="cover-image">
                    <img src="/cover" alt="Book cover">
                </div>
                """
            } else ""

            return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>$title</title>
                $fontLinks
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Arial, sans-serif;
                        line-height: 1.6;
                        color: #333;
                        max-width: 800px;
                        margin: 0 auto;
                        padding: 20px;
                        background-color: #f5f5f5;
                    }
                    .container {
                        background-color: white;
                        padding: 30px;
                        border-radius: 10px;
                        box-shadow: 0 2px 10px rgba(0,0,0,0.1);
                    }
                    h1 {
                        color: #2c3e50;
                        margin-bottom: 10px;
                    }
                    h2 {
                        color: #7f8c8d;
                        font-weight: normal;
                        margin-top: 0;
                        margin-bottom: 30px;
                    }
                    .cover-image {
                        text-align: center;
                        margin-bottom: 30px;
                    }
                    .cover-image img {
                        max-width: 300px;
                        max-height: 400px;
                        border-radius: 5px;
                        box-shadow: 0 2px 8px rgba(0,0,0,0.1);
                    }
                    .content {
                        text-align: justify;
                        margin-top: 30px;
                    }
                    .content p {
                        margin-bottom: 1.5em;
                    }
                    .content p.indented-paragraph {
                        text-indent: 2em;
                    }
                    /* Text formatting styles */
                    .content b, .content strong {
                        font-weight: bold;
                    }
                    .content i, .content em {
                        font-style: italic;
                    }
                    .content .large-text, .content span[style*="font-size: 2"], .content span[style*="font-size: 1.7"], .content span[style*="font-size: 1.5"] {
                        font-size: 1.5em;
                    }
                    .content .small-text, .content span[style*="font-size: 0."] {
                        font-size: 0.85em;
                    }
                    /* Alignment styles */
                    .content .align-center, .content p.align-center {
                        text-align: center !important;
                        text-indent: 0 !important;
                    }
                    .content .align-right, .content p.align-right {
                        text-align: right !important;
                        text-indent: 0 !important;
                    }
                    .content img {
                        max-width: 100%;
                        height: auto;
                        display: block;
                        margin: 20px auto;
                        border-radius: 5px;
                    }
                    .metadata {
                        background-color: #ecf0f1;
                        padding: 15px;
                        border-radius: 5px;
                        margin-bottom: 20px;
                        font-size: 0.9em;
                    }
                    .footer {
                        margin-top: 40px;
                        padding-top: 20px;
                        border-top: 1px solid #e0e0e0;
                        text-align: center;
                        color: #7f8c8d;
                        font-size: 0.9em;
                    }
                    /* Generated font styles */
                    $fontStyles
                </style>
            </head>
            <body>
                <div class="container">
                    <h1>${escapeHtml(title)}</h1>
                    ${if (subtitle.isNotEmpty()) "<h2>${escapeHtml(subtitle)}</h2>" else ""}
                    
                    $coverImageHtml
                    
                    <div class="content">
                        $bodyContent
                    </div>
                    
                    <div class="footer">
                        <p>Shared with 💙 via Typewriter</p>
                    </div>
                </div>
            </body>
            </html>
            """.trimIndent()
        }

        private fun extractUsedFonts(html: String): Set<String> {
            val usedFonts = mutableSetOf<String>()

            Log.d("BookWebServer", "Extracting fonts from HTML: ${html.take(200)}...")

            // Extract font resource IDs from the HTML
            val fontPattern = Regex("""data-font-resource-id="(\d+)"""")
            fontPattern.findAll(html).forEach { match ->
                val resourceId = match.groupValues[1].toIntOrNull()
                Log.d("BookWebServer", "Found font resource ID: $resourceId")
                if (resourceId != null) {
                    // Find the corresponding font name from resource ID
                    val fontFieldName = getFontFieldNameFromResourceId(resourceId)
                    Log.d("BookWebServer", "Font field name for ID $resourceId: $fontFieldName")
                    if (fontFieldName != null) {
                        usedFonts.add(fontFieldName)
                    }
                }
            }

            // Also extract font names directly if present
            val fontNamePattern = Regex("""data-font-name="([^"]+)"""")
            fontNamePattern.findAll(html).forEach { match ->
                val fontName = match.groupValues[1]
                Log.d("BookWebServer", "Found font name: $fontName")
                // Convert display name back to field name
                val fieldName = fontName.lowercase().replace(" ", "_")
                if (FONT_MAPPINGS.containsKey(fieldName)) {
                    usedFonts.add(fieldName)
                }
            }

            Log.d("BookWebServer", "Total fonts found: ${usedFonts.size} - $usedFonts")

            return usedFonts
        }

        private fun getFontFieldNameFromResourceId(resourceId: Int): String? {
            // Use reflection to find the field name from resource ID
            try {
                val fontFields = R.font::class.java.fields
                for (field in fontFields) {
                    if (field.getInt(null) == resourceId) {
                        return field.name
                    }
                }
            } catch (e: Exception) {
                Log.e("BookWebServer", "Error finding font field name", e)
            }
            return null
        }

        private fun generateFontLinks(usedFonts: Set<String>): String {
            val uniqueUrls = mutableSetOf<String>()

            for (fontField in usedFonts) {
                FONT_MAPPINGS[fontField]?.let { mapping ->
                    uniqueUrls.add(mapping.googleFontUrl)
                }
            }

            return uniqueUrls.joinToString("\n") { url ->
                """<link href="$url" rel="stylesheet">"""
            }
        }

        private fun generateFontStyles(usedFonts: Set<String>): String {
            val styles = mutableListOf<String>()

            Log.d("BookWebServer", "Generating font styles for: $usedFonts")

            // Always include a default font style
            styles.add("""
                .content span {
                    /* Default span style to ensure inheritance works */
                }
            """.trimIndent())

            for (fontField in usedFonts) {
                FONT_MAPPINGS[fontField]?.let { mapping ->
                    val className = ".font-${fontField.replace("_", "-")}"
                    val fontWeight = if (mapping.weight != "400") "font-weight: ${mapping.weight} !important;" else ""
                    val fontStyle = if (mapping.style != "normal") "font-style: ${mapping.style} !important;" else ""

                    styles.add("""
                        .content $className {
                            font-family: '${mapping.familyName}', serif !important;
                            $fontWeight
                            $fontStyle
                        }
                    """.trimIndent())

                    Log.d("BookWebServer", "Generated style for $className with family '${mapping.familyName}'")
                }
            }

            // Also add styles for font names (for backward compatibility)
            for (fontField in usedFonts) {
                FONT_MAPPINGS[fontField]?.let { mapping ->
                    val displayName = fontField.replace("_", " ").split(" ")
                        .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase() } }
                    val className = ".font-${displayName.replace(" ", "-")}"

                    styles.add("""
                        .content $className {
                            font-family: '${mapping.familyName}', serif !important;
                        }
                    """.trimIndent())
                }
            }

            val finalStyles = styles.joinToString("\n")
            Log.d("BookWebServer", "Generated CSS:\n$finalStyles")

            return finalStyles
        }

        private fun convertPlainTextToHtml(text: String): String {
            return text
                .split("\n\n")
                .filter { it.isNotBlank() }
                .joinToString("") { "<p>${escapeHtml(it.trim())}</p>" }
        }

        private fun processFormattedHtml(html: String): String {
            // First, extract just the body content if it's wrapped in html/body tags
            var processed = html

            // Extract content between <body> tags if present
            val bodyPattern = Regex("""<body[^>]*>(.*?)</body>""", RegexOption.DOT_MATCHES_ALL)
            val bodyMatch = bodyPattern.find(processed)
            if (bodyMatch != null) {
                processed = bodyMatch.groupValues[1]
            }

            // Remove any remaining html, head, or body tags
            processed = processed
                .replace(Regex("""</?html[^>]*>"""), "")
                .replace(Regex("""</?head[^>]*>"""), "")
                .replace(Regex("""</?body[^>]*>"""), "")
                .replace(Regex("""<meta[^>]*>"""), "")
                .replace(Regex("""<title[^>]*>.*?</title>"""), "")
                // Remove any script tags for security
                .replace(Regex("""<script[^>]*>.*?</script>""", RegexOption.DOT_MATCHES_ALL), "")

            // Convert font references BEFORE other processing
            processed = convertFontReferences(processed)

            // Handle alignment - make sure to catch all variations
            processed = processed
                .replace(Regex("""<span\s+data-alignment="center"[^>]*>"""), """<span class="align-center">""")
                .replace(Regex("""<span\s+data-alignment="right"[^>]*>"""), """<span class="align-right">""")
                .replace(Regex("""<span\s+data-alignment="left"[^>]*>"""), """<span>""")

            // Handle size spans - both data attributes and inline styles
            processed = processed
                .replace(Regex("""<span\s+style="font-size:\s*([\d.]+)em;"[^>]*>""")) { match ->
                    val size = match.groupValues[1].toFloatOrNull() ?: 1.0f
                    when {
                        size >= 1.5f -> """<span class="large-text">"""
                        size <= 0.8f -> """<span class="small-text">"""
                        else -> "<span>"
                    }
                }

            // Process images - convert both base64 and URI references
            processed = processed
                .replace(Regex("""<img[^>]*src="data:image/[^"]*"[^>]*>""")) { match ->
                    // Keep base64 images as-is
                    match.value
                }
                .replace(Regex("""<img[^>]*data-image-uri="([^"]+)"[^>]*>""")) { match ->
                    val imageUri = match.groupValues[1]
                    """<img src="/image/${imageUri.hashCode()}" alt="Embedded image">"""
                }

            // Handle paragraph classes
            processed = processed
                .replace(Regex("""<p\s+class="indented-paragraph">"""), """<p class="indented-paragraph">""")
                .trim()

            // Debug log to see what we're actually serving
            Log.d("BookWebServer", "Processed HTML: $processed")

            return processed
        }

        private fun convertFontReferences(html: String): String {
            var processed = html

            // Convert data-font-resource-id spans - handle multiple attributes
            processed = processed.replace(Regex("""<span([^>]*?)data-font-resource-id="(\d+)"([^>]*?)>""")) { match ->
                val beforeAttr = match.groupValues[1]
                val resourceId = match.groupValues[2].toIntOrNull()
                val afterAttr = match.groupValues[3]

                if (resourceId != null) {
                    val fontFieldName = getFontFieldNameFromResourceId(resourceId)
                    if (fontFieldName != null) {
                        // Check if there are other attributes to preserve
                        val otherAttrs = (beforeAttr + afterAttr).trim()
                        if (otherAttrs.isNotEmpty()) {
                            """<span class="font-${fontFieldName.replace("_", "-")}" $otherAttrs>"""
                        } else {
                            """<span class="font-${fontFieldName.replace("_", "-")}">"""
                        }
                    } else {
                        "<span${beforeAttr}${afterAttr}>"
                    }
                } else {
                    "<span${beforeAttr}${afterAttr}>"
                }
            }

            // Convert data-font-name spans
            processed = processed.replace(Regex("""<span([^>]*?)data-font-name="([^"]+)"([^>]*?)>""")) { match ->
                val beforeAttr = match.groupValues[1]
                val fontName = match.groupValues[2]
                val afterAttr = match.groupValues[3]

                // Clean up the other attributes - remove the data-font-resource-id if present
                var cleanedAttrs = (beforeAttr + afterAttr)
                    .replace(Regex("""data-font-resource-id="\d+""""), "")
                    .trim()

                val className = "font-${fontName.replace(" ", "-")}"
                if (cleanedAttrs.isNotEmpty()) {
                    """<span class="$className" $cleanedAttrs>"""
                } else {
                    """<span class="$className">"""
                }
            }

            return processed
        }

        private fun escapeHtml(text: String): String {
            return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
        }
    }

    private fun generateQRCode(url: String) {
        try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(url, BarcodeFormat.QR_CODE, 512, 512)
            val barcodeEncoder = BarcodeEncoder()
            val bitmap: Bitmap = barcodeEncoder.createBitmap(bitMatrix)
            binding.qrCodeImage.setImageBitmap(bitmap)
        } catch (e: WriterException) {
            Log.e("ShareActivity", "Error generating QR code", e)
            Toast.makeText(this, "Failed to generate QR code", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                // Skip loopback and non-active interfaces
                if (intf.isLoopback || !intf.isUp) continue

                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress
                        // Check if it's IPv4
                        val isIPv4 = (sAddr?.indexOf(':') ?: -1) < 0
                        if (isIPv4 && sAddr != null) {
                            // Accept common private IP ranges
                            if (sAddr.startsWith("192.168.") ||
                                sAddr.startsWith("10.") ||
                                sAddr.startsWith("172.")) {
                                Log.d("ShareActivity", "Found IP: $sAddr on interface: ${intf.displayName}")
                                return sAddr
                            }
                        }
                    }
                }
            }

            // Fallback: try to get WiFi IP specifically
            val wifiIp = getWifiIpAddress()
            if (wifiIp != null) {
                return wifiIp
            }
        } catch (e: Exception) {
            Log.e("ShareActivity", "Error getting IP address", e)
        }

        // Last resort - return first non-loopback IPv4 address
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "localhost"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ShareActivity", "Error in fallback IP detection", e)
        }

        return "localhost"
    }

    private fun getWifiIpAddress(): String? {
        try {
            // Try to specifically get the wlan0 interface (common WiFi interface name)
            val wifiInterface = NetworkInterface.getByName("wlan0")
            if (wifiInterface != null && wifiInterface.isUp) {
                val addresses = Collections.list(wifiInterface.inetAddresses)
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ShareActivity", "Error getting WiFi IP", e)
        }
        return null
    }

    private fun stopWebServer() {
        webServer?.stop()
        webServer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopWebServer()
    }
}

// Extension function to safely read bytes from InputStream
fun java.io.InputStream.readBytes(): ByteArray {
    val buffer = ByteArrayOutputStream()
    val data = ByteArray(16384)
    var nRead: Int
    while (this.read(data, 0, data.size).also { nRead = it } != -1) {
        buffer.write(data, 0, nRead)
    }
    buffer.flush()
    return buffer.toByteArray()
}