// UploadActivity.kt
package com.gnimble.typewriter

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.gnimble.typewriter.data.Book
import com.gnimble.typewriter.data.ContentFormat
import com.gnimble.typewriter.databinding.ActivityUploadBinding
import com.gnimble.typewriter.viewmodel.MainViewModel
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
    private lateinit var mainViewModel: MainViewModel
    private var webServer: UploadWebServer? = null
    private val PORT = 8889 // Different port from ShareActivity
    private val PERMISSION_REQUEST_CODE = 1001

    companion object {
        private const val TAG = "UploadActivity"
        private const val MAX_UPLOAD_SIZE = 50 * 1024 * 1024 // 50MB max file size

        // Supported document extensions
        private val DOCUMENT_EXTENSIONS = setOf("txt", "rtf", "html", "htm")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityUploadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize ViewModel
        mainViewModel = ViewModelProvider(this)[MainViewModel::class.java]

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
                            "Supported files:\n" +
                            "• Images: JPG, PNG, GIF, etc. (saved to Pictures)\n" +
                            "• Documents: TXT, RTF, HTML (converted to books)"

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

    private fun getFileExtension(fileName: String): String {
        return fileName.substringAfterLast('.', "").lowercase()
    }

    private fun isDocumentFile(fileName: String): Boolean {
        return DOCUMENT_EXTENSIONS.contains(getFileExtension(fileName))
    }

    private fun isImageFile(fileName: String): Boolean {
        return IMAGE_EXTENSIONS.contains(getFileExtension(fileName))
    }

    // Convert RTF to plain text (basic implementation)
    private fun convertRtfToPlainText(rtfContent: String): String {
        // Basic RTF to plain text conversion
        // This is a simplified version - for production, consider using a proper RTF parser
        var text = rtfContent

        // Remove RTF header and footer
        val startIndex = text.indexOf("{\\rtf")
        val endIndex = text.lastIndexOf("}")
        if (startIndex >= 0 && endIndex > startIndex) {
            text = text.substring(startIndex, endIndex + 1)
        }

        // Remove RTF control words and groups
        text = text.replace(Regex("\\\\[a-z]+(-?\\d+)?[ ]?"), "")
        text = text.replace(Regex("[{}]"), "")

        // Convert special characters
        text = text.replace("\\'92", "'")
        text = text.replace("\\'93", """)
        text = text.replace("\\'94", """)
        text = text.replace("\\'96", "–")
        text = text.replace("\\'97", "—")
        text = text.replace("\\line", "\n")
        text = text.replace("\\par", "\n\n")

        // Clean up extra whitespace
        text = text.replace(Regex("\\s+"), " ")
        text = text.replace(Regex("\n{3,}"), "\n\n")

        return text.trim()
    }

    // Extract title from HTML
    private fun extractTitleFromHtml(htmlContent: String): String? {
        val titleRegex = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE)
        val match = titleRegex.find(htmlContent)
        return match?.groupValues?.get(1)?.trim()
    }

    // Create a book from document content
    private suspend fun createBookFromDocument(
        fileName: String,
        content: String,
        extension: String
    ): Book {
        var title = fileName.removeSuffix(".$extension")
        var bookContent = content
        var format = ContentFormat.PLAIN_TEXT
        var formattedContent: String? = null

        when (extension) {
            "txt" -> {
                // For TXT files, try to extract title from first line
                val lines = content.lines()
                if (lines.isNotEmpty() && lines[0].length < 100) {
                    // Use first line as title if it's not too long
                    title = lines[0].trim()
                    bookContent = lines.drop(1).joinToString("\n").trim()
                }
            }
            "rtf" -> {
                // Convert RTF to plain text
                bookContent = convertRtfToPlainText(content)

                // Try to extract title from first line
                val lines = bookContent.lines()
                if (lines.isNotEmpty() && lines[0].length < 100) {
                    title = lines[0].trim()
                    bookContent = lines.drop(1).joinToString("\n").trim()
                }
            }
            "html", "htm" -> {
                // For HTML files, preserve the formatting
                format = ContentFormat.HTML
                formattedContent = content

                // Extract plain text for storyContent (backward compatibility)
                bookContent = content
                    .replace(Regex("<[^>]+>"), "") // Remove HTML tags
                    .replace(Regex("\\s+"), " ") // Normalize whitespace
                    .trim()

                // Try to extract title from HTML
                extractTitleFromHtml(content)?.let {
                    title = it
                }
            }
        }

        // Clean up the title
        title = title.replace(Regex("[^a-zA-Z0-9\\s-]"), "").trim()
        if (title.isEmpty()) {
            title = "Untitled Book"
        }

        return Book(
            title = title,
            subtitle = "Imported from $fileName",
            storyContent = bookContent,
            formattedContent = formattedContent,
            contentFormat = format,
            createdDate = Date(),
            lastEdited = Date()
        )
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

        private fun saveImageToMediaStore(tempFile: File, fileName: String): String? {
            val resolver = activity.contentResolver
            val extension = getFileExtension(fileName)
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "image/*"

            val imageCollection = MediaStore.Images.Media.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY
            )

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val sanitizedFileName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val uniqueFileName = "${timestamp}_$sanitizedFileName"

            val newImageDetails = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, uniqueFileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Upload")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val imageUri = resolver.insert(imageCollection, newImageDetails)

            imageUri?.let { uri ->
                try {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        tempFile.inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    newImageDetails.clear()
                    newImageDetails.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, newImageDetails, null, null)
                    return uniqueFileName
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save image to MediaStore", e)
                    resolver.delete(uri, null, null)
                    return null
                }
            }
            return null
        }

        private fun handleFileUpload(session: IHTTPSession): Response {
            try {
                val files = HashMap<String, String>()
                session.parseBody(files)

                val uploadedFileNames = mutableListOf<String>()
                val createdBooks = mutableListOf<String>()

                session.parameters["file"]?.forEachIndexed { index, fileName ->
                    val tempFilePath = files["file"]
                    if (tempFilePath != null) {
                        val tempFile = File(tempFilePath)
                        if (tempFile.exists()) {
                            when {
                                isImageFile(fileName) -> {
                                    // Handle image files as before
                                    val savedFileName = saveImageToMediaStore(tempFile, fileName)
                                    if (savedFileName != null) {
                                        uploadedFileNames.add(savedFileName)
                                        activity.runOnUiThread {
                                            Toast.makeText(activity, "Saved to Pictures: $savedFileName", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                                isDocumentFile(fileName) -> {
                                    // Handle document files
                                    try {
                                        // Read the file content immediately before it gets deleted
                                        val content = tempFile.readText()
                                        val extension = getFileExtension(fileName)

                                        // Now process in a coroutine
                                        lifecycleScope.launch {
                                            try {
                                                val book = createBookFromDocument(fileName, content, extension)

                                                // Insert book into database
                                                mainViewModel.insert(book) { bookId ->
                                                    createdBooks.add(book.title)
                                                    activity.runOnUiThread {
                                                        Toast.makeText(
                                                            activity,
                                                            "Created book: ${book.title}",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Error creating book from document", e)
                                                activity.runOnUiThread {
                                                    Toast.makeText(
                                                        activity,
                                                        "Failed to create book from $fileName",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error reading document file", e)
                                        activity.runOnUiThread {
                                            Toast.makeText(
                                                activity,
                                                "Failed to read file: $fileName",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                                else -> {
                                    Log.w(TAG, "Unsupported file type: $fileName")
                                }
                            }

                            // Delete the temp file
                            tempFile.delete()
                        }
                    }
                }

                // Wait a bit for books to be created (not ideal, but simple)
                Thread.sleep(500)

                val totalFiles = uploadedFileNames.size + createdBooks.size
                if (totalFiles == 0) {
                    throw Exception("No files were processed successfully.")
                }

                // Return JSON response
                val jsonResponse = """
                    {
                        "success": true,
                        "message": "Successfully processed $totalFiles file(s)",
                        "images": [${uploadedFileNames.joinToString(",") { "\"$it\"" }}],
                        "books": [${createdBooks.joinToString(",") { "\"$it\"" }}]
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
                        margin-bottom: 20px;
                        font-size: 16px;
                    }
                    
                    .file-types {
                        background: #f5f7fa;
                        border-radius: 10px;
                        padding: 15px;
                        margin-bottom: 25px;
                        font-size: 14px;
                        color: #555;
                    }
                    
                    .file-types h3 {
                        font-size: 14px;
                        margin-bottom: 8px;
                        color: #333;
                    }
                    
                    .file-types-grid {
                        display: grid;
                        grid-template-columns: 1fr 1fr;
                        gap: 10px;
                    }
                    
                    .file-type-group {
                        display: flex;
                        align-items: center;
                        gap: 8px;
                    }
                    
                    .file-type-icon {
                        font-size: 20px;
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
                    
                    .file-info {
                        display: flex;
                        align-items: center;
                        gap: 10px;
                        flex: 1;
                    }
                    
                    .file-type-badge {
                        background: #667eea;
                        color: white;
                        padding: 2px 8px;
                        border-radius: 4px;
                        font-size: 11px;
                        font-weight: bold;
                        text-transform: uppercase;
                    }
                    
                    .file-type-badge.image {
                        background: #48bb78;
                    }
                    
                    .file-type-badge.document {
                        background: #4299e1;
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
                    
                    <div class="file-types">
                        <h3>Supported file types:</h3>
                        <div class="file-types-grid">
                            <div class="file-type-group">
                                <span class="file-type-icon">🖼️</span>
                                <div>
                                    <strong>Images</strong><br>
                                    <small>JPG, PNG, GIF → Pictures</small>
                                </div>
                            </div>
                            <div class="file-type-group">
                                <span class="file-type-icon">📄</span>
                                <div>
                                    <strong>Documents</strong><br>
                                    <small>TXT, RTF, HTML → Books</small>
                                </div>
                            </div>
                        </div>
                    </div>
                    
                    <div class="upload-area" id="uploadArea">
                        <div class="upload-icon">📁</div>
                        <p class="upload-text">Drag & drop files here or</p>
                        <label for="fileInput" class="select-button">Choose Files</label>
                        <input type="file" id="fileInput" class="file-input" multiple accept=".txt,.rtf,.html,.htm,.jpg,.jpeg,.png,.gif,.bmp,.webp">
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
                    
                    const documentExtensions = ['txt', 'rtf', 'html', 'htm'];
                    const imageExtensions = ['jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp'];
                    
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
                    
                    function getFileExtension(filename) {
                        return filename.split('.').pop().toLowerCase();
                    }
                    
                    function getFileType(filename) {
                        const ext = getFileExtension(filename);
                        if (imageExtensions.includes(ext)) return 'image';
                        if (documentExtensions.includes(ext)) return 'document';
                        return 'unknown';
                    }
                    
                    function handleFiles(files) {
                        selectedFiles = Array.from(files);
                        displayFiles();
                        uploadButton.style.display = selectedFiles.length > 0 ? 'block' : 'none';
                    }
                    
                    function displayFiles() {
                        fileList.innerHTML = '';
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
                                    const message = response.message || 'Files uploaded successfully!';
                                    showStatus(message, 'success');
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