// UploadActivity.kt
package com.gnimble.typewriter

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gnimble.typewriter.databinding.ActivityUploadBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.BarcodeEncoder
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*

class UploadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUploadBinding
    private var webServer: UploadWebServer? = null
    private val PORT = 8889 // Different port from ShareActivity
    private val PERMISSION_REQUEST_CODE = 1001

    companion object {
        private const val TAG = "UploadActivity"
        private const val MAX_UPLOAD_SIZE = 50 * 1024 * 1024 // 50MB max file size
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityUploadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        startUploadServer()

        binding.stopServerButton.setOnClickListener {
            stopWebServer()
            finish()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startUploadServer()
            } else {
                Toast.makeText(this, "Storage permission required for file uploads", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun startUploadServer() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ipAddress = getLocalIpAddress()
                val serverUrl = "http://$ipAddress:$PORT"

                // Create uploads directory
                val uploadsDir = getUploadsDirectory()
                if (!uploadsDir.exists()) {
                    uploadsDir.mkdirs()
                }

                // Create and start the server
                webServer = UploadWebServer(PORT, uploadsDir, this@UploadActivity)
                webServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

                withContext(Dispatchers.Main) {
                    binding.serverUrlText.text = serverUrl
                    binding.instructionsText.text = "Upload server is active! Others can upload files by:\n" +
                            "1. Scanning the QR code below, or\n" +
                            "2. Entering this URL in their browser: $serverUrl\n\n" +
                            "Files will be saved to: ${uploadsDir.absolutePath}"

                    // Generate QR code
                    generateQRCode(serverUrl)

                    Toast.makeText(this@UploadActivity, "Upload server started on $serverUrl", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting server", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@UploadActivity, "Failed to start server: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun getUploadsDirectory(): File {
        // Use app-specific external storage directory
        val externalDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        return File(externalDir, "Uploads").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    private inner class UploadWebServer(
        port: Int,
        private val uploadsDir: File,
        private val activity: UploadActivity
    ) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            return when {
                session.method == Method.POST && session.uri == "/upload" -> handleFileUpload(session)
                session.method == Method.GET && session.uri == "/" -> serveUploadPage()
                session.method == Method.GET && session.uri == "/status" -> serveStatus()
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
        }

        private fun handleFileUpload(session: IHTTPSession): Response {
            try {
                val files = HashMap<String, String>()
                session.parseBody(files)

                val uploadCount = session.parameters["file"]?.size ?: 0
                val uploadedFiles = mutableListOf<String>()

                // Process each uploaded file
                session.parameters["file"]?.forEachIndexed { index, fileName ->
                    val tempFile = File(files["file"])
                    if (tempFile.exists()) {
                        // Generate unique filename with timestamp
                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        val sanitizedFileName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                        val uniqueFileName = "${timestamp}_$sanitizedFileName"
                        val destFile = File(uploadsDir, uniqueFileName)

                        // Copy temp file to destination
                        tempFile.inputStream().use { input ->
                            FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        }

                        uploadedFiles.add(uniqueFileName)

                        // Delete temp file
                        tempFile.delete()

                        // Notify UI
                        activity.runOnUiThread {
                            Toast.makeText(activity, "Uploaded: $uniqueFileName", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                // Return JSON response
                val jsonResponse = """
                    {
                        "success": true,
                        "message": "Successfully uploaded ${uploadedFiles.size} file(s)",
                        "files": [${uploadedFiles.joinToString(",") { "\"$it\"" }}]
                    }
                """.trimIndent()

                return newFixedLengthResponse(Response.Status.OK, "application/json", jsonResponse)

            } catch (e: Exception) {
                Log.e(TAG, "Error handling upload", e)
                val errorJson = """{"success": false, "message": "Upload failed: ${e.message}"}"""
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", errorJson)
            }
        }

        private fun serveStatus(): Response {
            val freeSpace = uploadsDir.freeSpace / (1024 * 1024) // MB
            val fileCount = uploadsDir.listFiles()?.size ?: 0

            val statusJson = """
                {
                    "active": true,
                    "uploadDirectory": "${uploadsDir.name}",
                    "fileCount": $fileCount,
                    "freeSpaceMB": $freeSpace
                }
            """.trimIndent()

            return newFixedLengthResponse(Response.Status.OK, "application/json", statusJson)
        }

        private fun serveUploadPage(): Response {
            val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Upload Files to Gnimble Typewriter</title>
                <style>
                    * {
                        box-sizing: border-box;
                        margin: 0;
                        padding: 0;
                    }
                    
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Arial, sans-serif;
                        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                        min-height: 100vh;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        padding: 20px;
                    }
                    
                    .container {
                        background: white;
                        border-radius: 20px;
                        box-shadow: 0 20px 40px rgba(0, 0, 0, 0.1);
                        padding: 40px;
                        max-width: 500px;
                        width: 100%;
                    }
                    
                    h1 {
                        color: #333;
                        margin-bottom: 10px;
                        text-align: center;
                        font-size: 28px;
                    }
                    
                    .subtitle {
                        color: #666;
                        text-align: center;
                        margin-bottom: 30px;
                        font-size: 16px;
                    }
                    
                    .upload-area {
                        border: 3px dashed #ddd;
                        border-radius: 15px;
                        padding: 40px;
                        text-align: center;
                        transition: all 0.3s ease;
                        cursor: pointer;
                        background: #fafafa;
                    }
                    
                    .upload-area:hover,
                    .upload-area.drag-over {
                        border-color: #667eea;
                        background: #f0f0ff;
                    }
                    
                    .upload-icon {
                        font-size: 60px;
                        color: #667eea;
                        margin-bottom: 20px;
                    }
                    
                    .upload-text {
                        color: #666;
                        margin-bottom: 20px;
                        font-size: 18px;
                    }
                    
                    .file-input {
                        display: none;
                    }
                    
                    .select-button {
                        background: #667eea;
                        color: white;
                        padding: 12px 30px;
                        border-radius: 8px;
                        border: none;
                        font-size: 16px;
                        cursor: pointer;
                        transition: background 0.3s ease;
                        display: inline-block;
                    }
                    
                    .select-button:hover {
                        background: #5a67d8;
                    }
                    
                    .file-list {
                        margin-top: 30px;
                    }
                    
                    .file-item {
                        background: #f5f5f5;
                        padding: 15px;
                        border-radius: 8px;
                        margin-bottom: 10px;
                        display: flex;
                        align-items: center;
                        justify-content: space-between;
                    }
                    
                    .file-name {
                        color: #333;
                        font-size: 14px;
                        word-break: break-all;
                    }
                    
                    .file-size {
                        color: #888;
                        font-size: 12px;
                        margin-left: 10px;
                        white-space: nowrap;
                    }
                    
                    .upload-button {
                        background: #48bb78;
                        color: white;
                        padding: 15px 30px;
                        border-radius: 8px;
                        border: none;
                        font-size: 18px;
                        cursor: pointer;
                        transition: background 0.3s ease;
                        width: 100%;
                        margin-top: 20px;
                        display: none;
                    }
                    
                    .upload-button:hover {
                        background: #38a169;
                    }
                    
                    .upload-button:disabled {
                        background: #ccc;
                        cursor: not-allowed;
                    }
                    
                    .progress-bar {
                        width: 100%;
                        height: 6px;
                        background: #e0e0e0;
                        border-radius: 3px;
                        overflow: hidden;
                        margin-top: 20px;
                        display: none;
                    }
                    
                    .progress-fill {
                        height: 100%;
                        background: #667eea;
                        width: 0%;
                        transition: width 0.3s ease;
                    }
                    
                    .status-message {
                        text-align: center;
                        margin-top: 20px;
                        padding: 15px;
                        border-radius: 8px;
                        display: none;
                    }
                    
                    .status-success {
                        background: #d4edda;
                        color: #155724;
                        border: 1px solid #c3e6cb;
                    }
                    
                    .status-error {
                        background: #f8d7da;
                        color: #721c24;
                        border: 1px solid #f5c6cb;
                    }
                    
                    .remove-file {
                        color: #dc3545;
                        cursor: pointer;
                        font-size: 20px;
                        padding: 0 5px;
                    }
                    
                    .remove-file:hover {
                        color: #c82333;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <h1>📤 Upload Files</h1>
                    <p class="subtitle">Send files to Gnimble Typewriter</p>
                    
                    <div class="upload-area" id="uploadArea">
                        <div class="upload-icon">📁</div>
                        <p class="upload-text">Drag & drop files here or</p>
                        <label for="fileInput" class="select-button">Choose Files</label>
                        <input type="file" id="fileInput" class="file-input" multiple>
                    </div>
                    
                    <div class="file-list" id="fileList"></div>
                    
                    <button class="upload-button" id="uploadButton">Upload Files</button>
                    
                    <div class="progress-bar" id="progressBar">
                        <div class="progress-fill" id="progressFill"></div>
                    </div>
                    
                    <div class="status-message" id="statusMessage"></div>
                </div>
                
                <script>
                    const uploadArea = document.getElementById('uploadArea');
                    const fileInput = document.getElementById('fileInput');
                    const fileList = document.getElementById('fileList');
                    const uploadButton = document.getElementById('uploadButton');
                    const progressBar = document.getElementById('progressBar');
                    const progressFill = document.getElementById('progressFill');
                    const statusMessage = document.getElementById('statusMessage');
                    
                    let selectedFiles = [];
                    
                    // File input change
                    fileInput.addEventListener('change', (e) => {
                        handleFiles(e.target.files);
                    });
                    
                    // Drag and drop
                    uploadArea.addEventListener('dragover', (e) => {
                        e.preventDefault();
                        uploadArea.classList.add('drag-over');
                    });
                    
                    uploadArea.addEventListener('dragleave', () => {
                        uploadArea.classList.remove('drag-over');
                    });
                    
                    uploadArea.addEventListener('drop', (e) => {
                        e.preventDefault();
                        uploadArea.classList.remove('drag-over');
                        handleFiles(e.dataTransfer.files);
                    });
                    
                    // Click to select
                    uploadArea.addEventListener('click', (e) => {
                        if (e.target.tagName !== 'LABEL') {
                            fileInput.click();
                        }
                    });
                    
                    function handleFiles(files) {
                        selectedFiles = Array.from(files);
                        displayFiles();
                        uploadButton.style.display = selectedFiles.length > 0 ? 'block' : 'none';
                    }
                    
                    function displayFiles() {
                        fileList.innerHTML = '';
                        selectedFiles.forEach((file, index) => {
                            const fileItem = document.createElement('div');
                            fileItem.className = 'file-item';
                            fileItem.innerHTML = `
                                <div>
                                    <span class="file-name">File Name</span>
                                    <span class="file-size">File Size</span>
                                </div>
                                <span class="remove-file" onclick="removeFile(0)">×</span>
                            `;
                            fileList.appendChild(fileItem);
                        });
                    }
                    
                    function removeFile(index) {
                        selectedFiles.splice(index, 1);
                        displayFiles();
                        uploadButton.style.display = selectedFiles.length > 0 ? 'block' : 'none';
                    }
                    
                    function formatFileSize(bytes) {
                        if (bytes === 0) return '0 Bytes';
                        const k = 1024;
                        const sizes = ['Bytes', 'KB', 'MB', 'GB'];
                        const i = Math.floor(Math.log(bytes) / Math.log(k));
                        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
                    }
                    
                    uploadButton.addEventListener('click', async () => {
                        if (selectedFiles.length === 0) return;
                        
                        uploadButton.disabled = true;
                        progressBar.style.display = 'block';
                        statusMessage.style.display = 'none';
                        
                        const formData = new FormData();
                        selectedFiles.forEach(file => {
                            formData.append('file', file);
                        });
                        
                        try {
                            const xhr = new XMLHttpRequest();
                            
                            xhr.upload.addEventListener('progress', (e) => {
                                if (e.lengthComputable) {
                                    const percentComplete = (e.loaded / e.total) * 100;
                                    progressFill.style.width = percentComplete + '%';
                                }
                            });
                            
                            xhr.addEventListener('load', () => {
                                if (xhr.status === 200) {
                                    const response = JSON.parse(xhr.responseText);
                                    showStatus('Files uploaded successfully!', 'success');
                                    selectedFiles = [];
                                    displayFiles();
                                    uploadButton.style.display = 'none';
                                    fileInput.value = '';
                                } else {
                                    showStatus('Upload failed. Please try again.', 'error');
                                }
                                uploadButton.disabled = false;
                                progressBar.style.display = 'none';
                                progressFill.style.width = '0%';
                            });
                            
                            xhr.addEventListener('error', () => {
                                showStatus('Upload failed. Please check your connection.', 'error');
                                uploadButton.disabled = false;
                                progressBar.style.display = 'none';
                                progressFill.style.width = '0%';
                            });
                            
                            xhr.open('POST', '/upload');
                            xhr.send(formData);
                            
                        } catch (error) {
                            showStatus('Upload failed: ' + error.message, 'error');
                            uploadButton.disabled = false;
                            progressBar.style.display = 'none';
                        }
                    });
                    
                    function showStatus(message, type) {
                        statusMessage.textContent = message;
                        statusMessage.className = 'status-message status-' + type;
                        statusMessage.style.display = 'block';
                        
                        setTimeout(() => {
                            statusMessage.style.display = 'none';
                        }, 5000);
                    }
                </script>
            </body>
            </html>
            """.trimIndent()

            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
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
            Log.e(TAG, "Error generating QR code", e)
            Toast.makeText(this, "Failed to generate QR code", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue

                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress
                        val isIPv4 = (sAddr?.indexOf(':') ?: -1) < 0
                        if (isIPv4 && sAddr != null) {
                            if (sAddr.startsWith("192.168.") ||
                                sAddr.startsWith("10.") ||
                                sAddr.startsWith("172.")) {
                                return sAddr
                            }
                        }
                    }
                }
            }

            // Fallback: try to get WiFi IP specifically
            val wifiInterface = NetworkInterface.getByName("wlan0")
            if (wifiInterface != null && wifiInterface.isUp) {
                val addresses = Collections.list(wifiInterface.inetAddresses)
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "localhost"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address", e)
        }

        return "localhost"
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