// ShareActivity.kt - Using NanoHTTPD
package com.gnimble.typewriter

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gnimble.typewriter.databinding.ActivityShareBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.BarcodeEncoder
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.*

class ShareActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShareBinding
    private var webServer: BookWebServer? = null
    private val PORT = 8888 // Using a different port

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityShareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get book data from intent
        val bookTitle = intent.getStringExtra("book_title") ?: "Unknown Title"
        val bookSubtitle = intent.getStringExtra("book_subtitle") ?: ""
        val bookContent = intent.getStringExtra("book_content") ?: ""
        val bookCoverPath = intent.getStringExtra("book_cover_path") ?: ""

        // Start the web server
        startWebServer(bookTitle, bookSubtitle, bookContent, bookCoverPath)

        binding.stopServerButton.setOnClickListener {
            stopWebServer()
            finish()
        }
    }

    private fun startWebServer(title: String, subtitle: String, content: String, coverPath: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ipAddress = getLocalIpAddress()
                val serverUrl = "http://$ipAddress:$PORT"

                // Create and start the server
                webServer = BookWebServer(PORT, title, subtitle, content, coverPath)
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
        private val coverPath: String
    ) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            return newFixedLengthResponse(Response.Status.OK, "text/html", generateHtmlContent())
        }

        private fun generateHtmlContent(): String {
            // Convert content with proper paragraph formatting
            val formattedContent = content
                .split("\n\n")
                .filter { it.isNotBlank() }
                .joinToString("") { "<p>${it.trim()}</p>" }

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
                </style>
            </head>
            <body>
                <div class="container">
                    <h1>$title</h1>
                    ${if (subtitle.isNotEmpty()) "<h2>$subtitle</h2>" else ""}
                                        
                    <div class="content">
                        $formattedContent
                    </div>
                    
                    <div class="footer">
                        <p>Shared with 💙 with Gnimble</p>
                    </div>
                </div>
            </body>
            </html>
            """.trimIndent()
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

    private fun getAllIpAddresses(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isUp) {
                    val addrs = Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            ips.add("${intf.displayName}: ${addr.hostAddress}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ShareActivity", "Error getting all IPs", e)
        }
        return ips
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