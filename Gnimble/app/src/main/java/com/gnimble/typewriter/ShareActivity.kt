// ShareActivity.kt - Enhanced with formatted content support
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

class ShareActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShareBinding
    private var webServer: BookWebServer? = null
    private val PORT = 8888

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
                val coverUri = Uri.parse(coverPath)
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
            val bodyContent = when (contentFormat) {
                ContentFormat.HTML -> {
                    // Use the formatted HTML content directly
                    processFormattedHtml(formattedContent ?: convertPlainTextToHtml(content))
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
                        text-indent: 2em;
                    }
                    .content p:first-child {
                        text-indent: 0;
                    }
                    /* Formatting styles */
                    .content b, .content strong {
                        font-weight: bold;
                        color: #2c3e50;
                    }
                    .content i, .content em {
                        font-style: italic;
                    }
                    .content .large-text {
                        font-size: 1.2em;
                    }
                    .content .small-text {
                        font-size: 0.9em;
                    }
                    .content .align-center {
                        text-align: center;
                        text-indent: 0 !important;
                    }
                    .content .align-right {
                        text-align: right;
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
                    /* Custom font styles */
                    .font-serif { font-family: Georgia, serif; }
                    .font-mono { font-family: 'Courier New', monospace; }
                    .font-cursive { font-family: cursive; }
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

        private fun convertPlainTextToHtml(text: String): String {
            return text
                .split("\n\n")
                .filter { it.isNotBlank() }
                .joinToString("") { "<p>${escapeHtml(it.trim())}</p>" }
        }

        private fun processFormattedHtml(html: String): String {
            // Process the HTML to ensure it's safe and properly formatted
            // This is a simplified version - in production, use a proper HTML sanitizer

            return html
                // Remove any script tags for security
                .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
                // Convert custom font tags to CSS classes
                .replace(Regex("<font[^>]*data-font-name=\"([^\"]+)\"[^>]*>"), "<span class=\"font-$1\">")
                .replace("</font>", "</span>")
                // Convert custom size tags
                .replace(Regex("<size[^>]*data-size-factor=\"([0-9.]+)\"[^>]*>")) { match ->
                    val factor = match.groupValues[1].toFloatOrNull() ?: 1.0f
                    when {
                        factor > 1.2f -> "<span class=\"large-text\">"
                        factor < 0.9f -> "<span class=\"small-text\">"
                        else -> "<span>"
                    }
                }
                .replace("</size>", "</span>")
                // Handle alignment
                .replace(Regex("<p[^>]*align=\"center\"[^>]*>"), "<p class=\"align-center\">")
                .replace(Regex("<p[^>]*align=\"right\"[^>]*>"), "<p class=\"align-right\">")
                // Process images - convert local URIs to server endpoints
                .replace(Regex("<img[^>]*data-image-uri=\"([^\"]+)\"[^>]*>")) { match ->
                    val imageUri = match.groupValues[1]
                    // In production, you'd properly handle image serving
                    "<img src=\"/image/${imageUri.hashCode()}\" alt=\"Embedded image\">"
                }
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
                        val isIPv4 = sAddr?.indexOf(':') ?: -1 < 0
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