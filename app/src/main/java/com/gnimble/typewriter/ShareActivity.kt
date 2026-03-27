// ShareActivity.kt
package com.gnimble.typewriter

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gnimble.typewriter.data.AppDatabase
import com.gnimble.typewriter.data.Book
import com.gnimble.typewriter.data.ContentFormat
import com.gnimble.typewriter.databinding.ActivityShareBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.BarcodeEncoder
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*

class ShareActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShareBinding
    private var webServer: BookWebServer? = null
    private val PORT = 8888
    private var bookId: Long = -1

    private val database by lazy { AppDatabase.getDatabase(this) }

    companion object {
        private const val TAG = "ShareActivity"

        // Font mapping between local resources and Google Fonts
        val FONT_MAPPINGS = mapOf(
            "cardo" to FontMapping("Cardo", "https://fonts.googleapis.com/css2?family=Cardo:ital,wght@0,400;0,700;1,400&display=swap"),
            "crimson_text" to FontMapping("Crimson Text", "https://fonts.googleapis.com/css2?family=Crimson+Text:ital,wght@0,400;0,600;0,700;1,400;1,600;1,700&display=swap", "700"),
            "eb_garamond" to FontMapping("EB Garamond", "https://fonts.googleapis.com/css2?family=EB+Garamond:ital,wght@0,400..800;1,400..800&display=swap", "400", "italic"),
            "young_serif" to FontMapping("Young Serif", "https://fonts.googleapis.com/css2?family=Young+Serif&display=swap"),
            "noto_sans_mono" to FontMapping("Noto Sans Mono", "https://fonts.googleapis.com/css2?family=Noto+Sans+Mono:wght@100..900&display=swap")
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

        bookId = intent.getLongExtra("book_id", -1)

        if (bookId == -1L) {
            Toast.makeText(this, "Error: No book ID provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        startServer()

        binding.stopServerButton.setOnClickListener {
            stopWebServer()
            finish()
        }
    }

    private fun startServer() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val book = database.bookDao().getBook(bookId)
                if (book == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ShareActivity, "Book not found", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return@launch
                }

                val ipAddress = getLocalIpAddress()
                val serverUrl = "http://$ipAddress:$PORT"

                webServer = BookWebServer(PORT, bookId, database, this@ShareActivity)
                webServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

                withContext(Dispatchers.Main) {
                    binding.serverUrlText.text = serverUrl
                    binding.instructionsText.text =
                        "Share & Edit is active!\n\n" +
                                "Others can view and edit \"${book.title}\" by:\n" +
                                "1. Scanning the QR code below, or\n" +
                                "2. Entering this URL in their browser:\n    $serverUrl\n\n" +
                                "Make sure devices are on the same Wi-Fi network.\n" +
                                "Changes made in the browser are saved back to this device."

                    generateQRCode(serverUrl)

                    Toast.makeText(this@ShareActivity, "Server started on $serverUrl", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting server", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ShareActivity, "Failed to start server: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Web Server — serves the WYSIWYG editor and handles save API
    // ─────────────────────────────────────────────────────────────────────

    private class BookWebServer(
        port: Int,
        private val bookId: Long,
        private val database: AppDatabase,
        private val activity: ShareActivity
    ) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            return try {
                when {
                    session.method == Method.GET && session.uri == "/" -> serveEditorPage()
                    session.method == Method.GET && session.uri == "/api/content" -> serveBookContent()
                    session.method == Method.POST && session.uri == "/api/save" -> handleSave(session)
                    session.method == Method.GET && session.uri == "/readonly" -> serveReadOnlyPage()
                    else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error on ${session.uri}", e)
                val errorJson = """{"success":false,"message":"Server error: ${e.message?.replace("\"", "'")}"}"""
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", errorJson)
            }
        }

        // ── GET /api/content ────────────────────────────────────────────

        private fun serveBookContent(): Response {
            val book = runBlocking { database.bookDao().getBook(bookId) }
                ?: return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "application/json",
                    """{"success":false,"message":"Book not found"}"""
                )

            val bodyContent = when (book.contentFormat) {
                ContentFormat.HTML -> {
                    val raw = book.formattedContent ?: book.storyContent
                    processFormattedHtml(raw)
                }
                else -> convertPlainTextToHtml(book.storyContent)
            }

            val json = JSONObject().apply {
                put("success", true)
                put("title", book.title)
                put("subtitle", book.subtitle)
                put("fontName", book.fontName)
                put("bodyHtml", bodyContent)
            }

            return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
        }

        // ── POST /api/save ──────────────────────────────────────────────

        private fun handleSave(session: IHTTPSession): Response {
            val bodyFiles = HashMap<String, String>()
            session.parseBody(bodyFiles)
            val postBody = bodyFiles["postData"] ?: ""

            if (postBody.isEmpty()) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    """{"success":false,"message":"Empty request body"}"""
                )
            }

            val json = JSONObject(postBody)
            val newTitle = json.optString("title", "").trim()
            val newBodyHtml = json.optString("bodyHtml", "")
            val newFontName = json.optString("fontName", "default")

            if (newBodyHtml.isEmpty()) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    """{"success":false,"message":"No content provided"}"""
                )
            }

            val fullHtml = "<html><body>$newBodyHtml</body></html>"

            val plainText = newBodyHtml
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            try {
                runBlocking {
                    val book = database.bookDao().getBook(bookId) ?: return@runBlocking

                    val updatedBook = book.copy(
                        title = if (newTitle.isNotEmpty()) newTitle else book.title,
                        formattedContent = fullHtml,
                        contentFormat = ContentFormat.HTML,
                        storyContent = plainText.take(500),
                        lastEdited = Date(),
                        subtitle = "Last edited: ${SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date())}",
                        fontName = newFontName
                    )
                    database.bookDao().updateBook(updatedBook)
                }

                activity.runOnUiThread {
                    Toast.makeText(activity, "Document saved from browser", Toast.LENGTH_SHORT).show()
                }

                return newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"success":true,"message":"Saved successfully"}"""
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error saving book", e)
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    """{"success":false,"message":"Save failed: ${e.message?.replace("\"", "'")}"}"""
                )
            }
        }

        // ── GET / — the WYSIWYG editor page ─────────────────────────────

        private fun serveEditorPage(): Response {
            val book = runBlocking { database.bookDao().getBook(bookId) }
                ?: return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/html",
                    "<h1>Book not found</h1>"
                )

            // Build all Google Font <link> tags
            val allFontLinks = FONT_MAPPINGS.values
                .map { """<link href="${it.googleFontUrl}" rel="stylesheet">""" }
                .joinToString("\n    ")

            // Build the font option list for the dropdown
            val fontOptionsHtml = buildString {
                append("""<option value="default">Calibri</option>""")
                FONT_MAPPINGS.forEach { (key, mapping) ->
                    val selected = if (key == book.fontName) " selected" else ""
                    append("""<option value="$key" style="font-family:'${mapping.familyName}',serif"$selected>${mapping.familyName}</option>""")
                }
            }

            // CSS font-family rules for content classes
            val fontFamilyCssRules = FONT_MAPPINGS.map { (key, mapping) ->
                """.font-${key.replace("_", "-")} { font-family: '${mapping.familyName}', serif !important; }"""
            }.joinToString("\n        ")

            // Determine initial font family for the editor
            val initialFontFamily = if (book.fontName != "default" && FONT_MAPPINGS.containsKey(book.fontName)) {
                "'${FONT_MAPPINGS[book.fontName]!!.familyName}', serif"
            } else {
                "'Calibri', 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif"
            }

            val html = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Editing: ${escapeHtml(book.title)}</title>
    <link href="https://fonts.googleapis.com/css2?family=Calibri:wght@400;700&display=swap" rel="stylesheet">
    $allFontLinks
    <style>
        *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

        /* ── LIGHT THEME (Word 2016 default) ─────────────── */
        :root, [data-theme="light"] {
            --title-bar-bg: #2b579a;
            --title-bar-text: #ffffff;
            --ribbon-bg: #f1f1f1;
            --ribbon-border: #d4d4d4;
            --ribbon-text: #444444;
            --ribbon-hover: #c8daf3;
            --ribbon-active: #2b579a;
            --ribbon-active-text: #ffffff;
            --ribbon-group-border: #d4d4d4;
            --editor-bg: #ffffff;
            --editor-text: #000000;
            --page-bg: #e8e8e8;
            --page-shadow: rgba(0,0,0,0.15);
            --accent: #2b579a;
            --accent-hover: #1e3f70;
            --success: #217346;
            --status-bg: #2b579a;
            --status-text: #ffffff;
            --select-bg: #ffffff;
            --select-border: #c0c0c0;
            --select-text: #333333;
            --btn-save-bg: #2b579a;
            --btn-save-hover: #1e3f70;
            --btn-save-text: #ffffff;
            --tooltip-bg: #333333;
            --tooltip-text: #ffffff;
            --separator-color: #c8c8c8;
            --file-tab-bg: #2b579a;
            --file-tab-text: #ffffff;
            --file-tab-hover: #1e3f70;
            --theme-toggle-bg: transparent;
            --theme-toggle-text: #ffffff;
            --theme-toggle-hover: rgba(255,255,255,0.15);
        }

        /* ── DARK THEME (Word 2016 Dark Gray) ────────────── */
        [data-theme="dark"] {
            --title-bar-bg: #1f1f1f;
            --title-bar-text: #d4d4d4;
            --ribbon-bg: #2d2d30;
            --ribbon-border: #3f3f46;
            --ribbon-text: #cccccc;
            --ribbon-hover: #3e3e42;
            --ribbon-active: #007acc;
            --ribbon-active-text: #ffffff;
            --ribbon-group-border: #3f3f46;
            --editor-bg: #1e1e1e;
            --editor-text: #d4d4d4;
            --page-bg: #171717;
            --page-shadow: rgba(0,0,0,0.4);
            --accent: #007acc;
            --accent-hover: #005a9e;
            --success: #388e3c;
            --status-bg: #007acc;
            --status-text: #ffffff;
            --select-bg: #333337;
            --select-border: #3f3f46;
            --select-text: #cccccc;
            --btn-save-bg: #0e639c;
            --btn-save-hover: #007acc;
            --btn-save-text: #ffffff;
            --tooltip-bg: #252526;
            --tooltip-text: #cccccc;
            --separator-color: #3f3f46;
            --file-tab-bg: #0e639c;
            --file-tab-text: #ffffff;
            --file-tab-hover: #007acc;
            --theme-toggle-bg: transparent;
            --theme-toggle-text: #d4d4d4;
            --theme-toggle-hover: rgba(255,255,255,0.1);
        }

        html, body {
            height: 100%;
            font-family: 'Segoe UI', 'Calibri', Tahoma, Geneva, Verdana, sans-serif;
            background: var(--page-bg);
            color: var(--editor-text);
            font-size: 14px;
        }

        /* ── LAYOUT ─────────────────────────────────────────── */
        .app-shell { display: flex; flex-direction: column; height: 100vh; height: 100dvh; }

        /* ── TITLE BAR (Word 2016 blue top) ──────────────── */
        .title-bar {
            display: flex; align-items: center; gap: 8px;
            padding: 4px 12px; background: var(--title-bar-bg); color: var(--title-bar-text);
            flex-shrink: 0; z-index: 30; min-height: 32px;
        }
        .title-bar .file-tab {
            padding: 4px 16px; background: var(--file-tab-bg); color: var(--file-tab-text);
            border: none; border-radius: 0; font-size: 12px; font-weight: 400;
            cursor: pointer; font-family: inherit; letter-spacing: 0.2px;
            transition: background 0.1s;
        }
        .title-bar .file-tab:hover { background: var(--file-tab-hover); }

        .title-bar .doc-title {
            flex: 1; font-size: 12px; font-weight: 400;
            background: none; border: 1px solid transparent; border-radius: 0;
            color: var(--title-bar-text); padding: 2px 6px; outline: none;
            min-width: 0; text-align: center; font-family: inherit;
        }
        .title-bar .doc-title:hover { border-color: rgba(255,255,255,0.3); }
        .title-bar .doc-title:focus { border-color: rgba(255,255,255,0.6); background: rgba(255,255,255,0.1); }

        .title-bar-actions { display: flex; align-items: center; gap: 2px; }

        .btn-theme {
            display: inline-flex; align-items: center; justify-content: center;
            width: 28px; height: 28px; border: none; border-radius: 2px;
            background: var(--theme-toggle-bg); color: var(--theme-toggle-text);
            cursor: pointer; font-size: 14px; transition: background 0.1s;
        }
        .btn-theme:hover { background: var(--theme-toggle-hover); }

        /* ── QUICK ACCESS TOOLBAR ────────────────────────── */
        .quick-access {
            display: flex; align-items: center; gap: 1px;
            padding: 2px 8px; background: var(--title-bar-bg);
            border-bottom: none; flex-shrink: 0;
        }
        .quick-access .qa-btn {
            display: inline-flex; align-items: center; justify-content: center;
            width: 22px; height: 22px; border: none; border-radius: 2px;
            background: transparent; color: var(--title-bar-text);
            cursor: pointer; font-size: 12px; transition: background 0.1s; opacity: 0.8;
        }
        .quick-access .qa-btn:hover { background: rgba(255,255,255,0.15); opacity: 1; }
        .quick-access .qa-btn svg { width: 13px; height: 13px; fill: none; stroke: currentColor; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }

        /* ── RIBBON TOOLBAR (Word 2016 style) ────────────── */
        .ribbon {
            display: flex; align-items: stretch;
            padding: 4px 8px 18px; background: var(--ribbon-bg);
            border-bottom: 1px solid var(--ribbon-border);
            flex-shrink: 0; z-index: 10;
            gap: 0;
        }
        .ribbon-group {
            display: flex; align-items: center; gap: 2px;
            padding: 2px 8px;
            border-right: 1px solid var(--ribbon-group-border);
            position: relative;
        }
        .ribbon-group:last-child { border-right: none; }
        .ribbon-group-label {
            position: absolute; bottom: -14px; left: 0; right: 0;
            text-align: center; font-size: 9px; color: var(--ribbon-text);
            opacity: 0.6; pointer-events: none; letter-spacing: 0.3px;
            text-transform: uppercase; font-family: inherit;
        }

        .tb {
            display: inline-flex; align-items: center; justify-content: center;
            width: 28px; height: 28px; border: 1px solid transparent; border-radius: 2px;
            background: transparent; color: var(--ribbon-text);
            font-size: 14px; cursor: pointer; transition: all 0.1s; flex-shrink: 0;
        }
        .tb:hover { background: var(--ribbon-hover); border-color: var(--ribbon-border); }
        .tb.active { background: var(--ribbon-active); color: var(--ribbon-active-text); border-color: var(--ribbon-active); }
        .tb svg { width: 15px; height: 15px; fill: none; stroke: currentColor; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }

        .ribbon select {
            height: 26px; padding: 0 6px; background: var(--select-bg); color: var(--select-text);
            border: 1px solid var(--select-border); border-radius: 2px; font-size: 12px;
            cursor: pointer; outline: none; font-family: inherit;
        }
        .ribbon select:focus { border-color: var(--accent); }
        .ribbon select:hover { border-color: #999; }

        /* ── EDITOR AREA ────────────────────────────────────── */
        .editor-wrap {
            flex: 1; overflow-y: auto; padding: 24px 16px 60px;
            display: flex; justify-content: center; background: var(--page-bg);
        }
        .editor-page {
            width: 100%; max-width: 816px; min-height: 1056px;
            background: var(--editor-bg); border-radius: 0;
            box-shadow: 0 1px 3px var(--page-shadow), 0 4px 12px var(--page-shadow);
            padding: 96px 96px 96px 96px; outline: none;
            font-size: 11pt; line-height: 1.15;
            font-family: ${initialFontFamily};
            color: var(--editor-text);
        }
        .editor-page:focus { box-shadow: 0 1px 3px var(--page-shadow), 0 4px 12px var(--page-shadow); }

        /* Content formatting inside editor */
        .editor-page p { margin-bottom: 8pt; }
        .editor-page p.indented-paragraph { text-indent: 0.5in; }
        .editor-page .align-center { text-align: center; }
        .editor-page .align-right { text-align: right; }
        .editor-page .large-text { font-size: 1.4em; }
        .editor-page .small-text { font-size: 0.85em; }
        $fontFamilyCssRules

        /* ── STATUS BAR (Word 2016 blue bottom bar) ──────── */
        .status-bar {
            display: flex; align-items: center; justify-content: space-between;
            padding: 2px 12px; background: var(--status-bg); color: var(--status-text);
            font-size: 11px; flex-shrink: 0; min-height: 24px; font-family: inherit;
        }
        .status-bar-left { display: flex; align-items: center; gap: 16px; }
        .status-bar-right { display: flex; align-items: center; gap: 8px; }

        /* ── SAVE BUTTON (in title bar) ──────────────────── */
        .btn-save {
            display: inline-flex; align-items: center; gap: 4px;
            padding: 3px 12px; background: var(--btn-save-bg); color: var(--btn-save-text);
            border: 1px solid rgba(255,255,255,0.15); border-radius: 2px;
            font-size: 11px; font-weight: 400; cursor: pointer;
            white-space: nowrap; transition: background 0.15s; font-family: inherit;
        }
        .btn-save:hover { background: var(--btn-save-hover); }
        .btn-save:active { transform: scale(0.98); }
        .btn-save.saving { opacity: 0.6; pointer-events: none; }
        .btn-save.saved { background: var(--success); border-color: var(--success); }

        .btn-readonly {
            padding: 3px 10px; background: transparent; color: var(--title-bar-text);
            border: 1px solid rgba(255,255,255,0.2); border-radius: 2px;
            font-size: 11px; cursor: pointer; white-space: nowrap; font-family: inherit;
            transition: background 0.1s;
        }
        .btn-readonly:hover { background: rgba(255,255,255,0.1); }

        /* ── TOAST ───────────────────────────────────────────── */
        .toast {
            position: fixed; bottom: 36px; left: 50%;
            transform: translateX(-50%) translateY(60px);
            background: var(--tooltip-bg); color: var(--tooltip-text);
            padding: 8px 20px; border-radius: 2px; font-size: 12px;
            font-weight: 400; opacity: 0; transition: transform 0.25s, opacity 0.25s;
            z-index: 999; pointer-events: none; font-family: inherit;
            box-shadow: 0 2px 8px rgba(0,0,0,0.3);
        }
        .toast.show { transform: translateX(-50%) translateY(0); opacity: 1; }

        /* ── RESPONSIVE ──────────────────────────────────────── */
        @media (max-width: 900px) {
            .editor-page { padding: 48px 48px; max-width: 100%; min-height: 400px; }
        }
        @media (max-width: 600px) {
            .editor-page { padding: 24px 20px; }
            .title-bar { padding: 4px 8px; gap: 4px; }
            .ribbon { padding: 4px 4px 6px; gap: 0; }
            .ribbon-group { padding: 2px 4px; }
            .ribbon-group-label { display: none; }
            .btn-readonly { display: none; }
            .quick-access { display: none; }
        }

        /* ── PRINT ───────────────────────────────────────────── */
        @media print {
            .title-bar, .quick-access, .ribbon, .status-bar, .toast { display: none !important; }
            .editor-wrap { padding: 0; background: #fff; }
            .editor-page { box-shadow: none; border-radius: 0; max-width: 100%; padding: 0; background: #fff; color: #000; }
        }
    </style>
</head>
<body>
<div class="app-shell" id="appShell">

    <!-- TITLE BAR (Word 2016 blue bar) -->
    <div class="title-bar">
        <button class="btn-save" id="btnSave" onclick="saveDocument()" title="Save (Ctrl+S)">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M19 21H5a2 2 0 01-2-2V5a2 2 0 012-2h11l5 5v11a2 2 0 01-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/></svg>
            Save
        </button>
        <input class="doc-title" id="docTitle" type="text" value="${escapeAttr(book.title)}" spellcheck="false" />
        <div class="title-bar-actions">
            <a class="btn-readonly" href="/readonly" target="_blank" title="Open print-friendly read-only view">Print View</a>
            <button class="btn-theme" id="btnTheme" onclick="toggleTheme()" title="Toggle dark/light mode">
                <svg id="themeIcon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12.79A9 9 0 1111.21 3 7 7 0 0021 12.79z"/></svg>
            </button>
        </div>
    </div>

    <!-- QUICK ACCESS TOOLBAR -->
    <div class="quick-access">
        <button class="qa-btn" onclick="document.execCommand('undo')" title="Undo (Ctrl+Z)">
            <svg viewBox="0 0 24 24"><polyline points="1 4 1 10 7 10"/><path d="M3.51 15a9 9 0 102.13-9.36L1 10"/></svg>
        </button>
        <button class="qa-btn" onclick="document.execCommand('redo')" title="Redo (Ctrl+Y)">
            <svg viewBox="0 0 24 24"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 11-2.13-9.36L23 10"/></svg>
        </button>
    </div>

    <!-- RIBBON TOOLBAR (Word 2016 style) -->
    <div class="ribbon" id="ribbon">
        <!-- Clipboard group -->
        <div class="ribbon-group">
            <select id="headingSelect" onchange="applyHeading(this.value)" title="Styles">
                <option value="p">Normal</option>
                <option value="title">Title</option>
                <option value="subtitle">Subtitle</option>
                <option value="chapter">Heading 1</option>
            </select>
            <span class="ribbon-group-label">Styles</span>
        </div>

        <!-- Font group -->
        <div class="ribbon-group">
            <select id="fontSelect" onchange="applyFont(this.value)" title="Font">
                $fontOptionsHtml
            </select>
            <button class="tb" id="btnBold" onclick="fmt('bold')" title="Bold (Ctrl+B)">
                <svg viewBox="0 0 24 24"><path d="M6 4h8a4 4 0 014 4 4 4 0 01-4 4H6z"/><path d="M6 12h9a4 4 0 014 4 4 4 0 01-4 4H6z"/></svg>
            </button>
            <button class="tb" id="btnItalic" onclick="fmt('italic')" title="Italic (Ctrl+I)">
                <svg viewBox="0 0 24 24"><line x1="19" y1="4" x2="10" y2="4"/><line x1="14" y1="20" x2="5" y2="20"/><line x1="15" y1="4" x2="9" y2="20"/></svg>
            </button>
            <span class="ribbon-group-label">Font</span>
        </div>

        <!-- Paragraph group -->
        <div class="ribbon-group">
            <button class="tb" id="btnAlignLeft" onclick="align('left')" title="Align Left">
                <svg viewBox="0 0 24 24"><line x1="17" y1="10" x2="3" y2="10"/><line x1="21" y1="6" x2="3" y2="6"/><line x1="21" y1="14" x2="3" y2="14"/><line x1="17" y1="18" x2="3" y2="18"/></svg>
            </button>
            <button class="tb" id="btnAlignCenter" onclick="align('center')" title="Center">
                <svg viewBox="0 0 24 24"><line x1="18" y1="10" x2="6" y2="10"/><line x1="21" y1="6" x2="3" y2="6"/><line x1="21" y1="14" x2="3" y2="14"/><line x1="18" y1="18" x2="6" y2="18"/></svg>
            </button>
            <button class="tb" id="btnAlignRight" onclick="align('right')" title="Align Right">
                <svg viewBox="0 0 24 24"><line x1="21" y1="10" x2="7" y2="10"/><line x1="21" y1="6" x2="3" y2="6"/><line x1="21" y1="14" x2="3" y2="14"/><line x1="21" y1="18" x2="7" y2="18"/></svg>
            </button>
            <span class="ribbon-group-label">Paragraph</span>
        </div>
    </div>

    <!-- EDITOR -->
    <div class="editor-wrap">
        <div class="editor-page" id="editor" contenteditable="true" spellcheck="true">
            <p>Loading…</p>
        </div>
    </div>

    <!-- STATUS BAR (Word 2016 blue bottom) -->
    <div class="status-bar">
        <div class="status-bar-left">
            <span id="wordCount">Words: 0</span>
            <span id="saveStatus">Ready</span>
        </div>
        <div class="status-bar-right">
            <span>100%</span>
        </div>
    </div>
</div>

<div class="toast" id="toast"></div>

<script>
(function() {
    'use strict';

    var editor        = document.getElementById('editor');
    var docTitle      = document.getElementById('docTitle');
    var fontSelect    = document.getElementById('fontSelect');
    var headingSelect = document.getElementById('headingSelect');
    var wordCountEl   = document.getElementById('wordCount');
    var saveStatusEl  = document.getElementById('saveStatus');
    var btnSave       = document.getElementById('btnSave');
    var appShell      = document.getElementById('appShell');

    var FONTS = {
        'default':        "'Calibri', 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif",
        'cardo':          "'Cardo', serif",
        'crimson_text':   "'Crimson Text', serif",
        'eb_garamond':    "'EB Garamond', serif",
        'young_serif':    "'Young Serif', serif",
        'noto_sans_mono': "'Noto Sans Mono', monospace"
    };

    var SAVE_ICON = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M19 21H5a2 2 0 01-2-2V5a2 2 0 012-2h11l5 5v11a2 2 0 01-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/></svg> Save';

    var dirty = false;
    var autoSaveTimer = null;

    // ── Theme management ───────────────────────────────────────────
    function getStoredTheme() {
        try { return localStorage.getItem('gnimble-editor-theme') || 'light'; }
        catch(e) { return 'light'; }
    }

    function setStoredTheme(theme) {
        try { localStorage.setItem('gnimble-editor-theme', theme); } catch(e) {}
    }

    function applyTheme(theme) {
        document.documentElement.setAttribute('data-theme', theme);
        appShell.setAttribute('data-theme', theme);
        var icon = document.getElementById('themeIcon');
        if (theme === 'dark') {
            icon.innerHTML = '<circle cx="12" cy="12" r="5"/><line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/><line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/>';
        } else {
            icon.innerHTML = '<path d="M21 12.79A9 9 0 1111.21 3 7 7 0 0021 12.79z"/>';
        }
    }

    window.toggleTheme = function() {
        var current = getStoredTheme();
        var next = current === 'dark' ? 'light' : 'dark';
        setStoredTheme(next);
        applyTheme(next);
    };

    // Apply saved theme on load
    applyTheme(getStoredTheme());

    // ── Load content ───────────────────────────────────────────────
    function loadContent() {
        fetch('/api/content')
            .then(function(r) { return r.json(); })
            .then(function(data) {
                if (!data.success) throw new Error(data.message);
                editor.innerHTML = data.bodyHtml || '<p><br></p>';
                docTitle.value   = data.title || 'Untitled';
                if (data.fontName && fontSelect) {
                    fontSelect.value = data.fontName;
                    applyFontToEditor(data.fontName);
                }
                updateWordCount();
                saveStatusEl.textContent = 'Loaded';
            })
            .catch(function(err) {
                console.error('Load failed:', err);
                editor.innerHTML = '<p>Failed to load content. Please refresh the page.</p>';
            });
    }
    loadContent();

    // ── Save content ───────────────────────────────────────────────
    window.saveDocument = function() {
        if (btnSave.classList.contains('saving')) return;

        btnSave.classList.add('saving');
        btnSave.textContent = 'Saving…';
        saveStatusEl.textContent = 'Saving…';

        var bodyHtml = editor.innerHTML;
        var title    = docTitle.value.trim();
        var fontName = fontSelect.value;

        fetch('/api/save', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ title: title, bodyHtml: bodyHtml, fontName: fontName })
        })
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (data.success) {
                dirty = false;
                btnSave.classList.remove('saving');
                btnSave.classList.add('saved');
                btnSave.textContent = '✓ Saved';
                saveStatusEl.textContent = 'Saved just now';
                showToast('Document saved');
                setTimeout(function() {
                    btnSave.classList.remove('saved');
                    btnSave.innerHTML = SAVE_ICON;
                }, 2000);
            } else {
                throw new Error(data.message);
            }
        })
        .catch(function(err) {
            console.error('Save failed:', err);
            btnSave.classList.remove('saving');
            btnSave.textContent = 'Save failed';
            saveStatusEl.textContent = 'Save failed';
            showToast('Save failed: ' + err.message);
            setTimeout(function() { btnSave.innerHTML = SAVE_ICON; }, 3000);
        });
    };

    // ── Auto-save after 5s of inactivity ───────────────────────────
    function scheduleSave() {
        if (autoSaveTimer) clearTimeout(autoSaveTimer);
        autoSaveTimer = setTimeout(function() {
            if (dirty) saveDocument();
        }, 5000);
    }

    editor.addEventListener('input', function() {
        dirty = true;
        saveStatusEl.textContent = 'Unsaved changes';
        updateWordCount();
        scheduleSave();
    });

    docTitle.addEventListener('input', function() {
        dirty = true;
        saveStatusEl.textContent = 'Unsaved changes';
        scheduleSave();
    });

    // ── Formatting commands ────────────────────────────────────────
    window.fmt = function(command) {
        document.execCommand(command, false, null);
        editor.focus();
        updateToolbarState();
    };

    window.align = function(direction) {
        var block = getSelectedBlock();
        if (!block) return;
        block.classList.remove('align-center', 'align-right');
        block.style.textAlign = '';
        if (direction === 'center') block.classList.add('align-center');
        else if (direction === 'right') block.classList.add('align-right');
        updateToolbarState();
    };

    window.applyHeading = function(value) {
        var block = getSelectedBlock();
        if (!block) return;

        block.classList.remove('large-text');
        var sizeSpans = block.querySelectorAll('.large-text, span[data-font-size]');
        for (var i = 0; i < sizeSpans.length; i++) {
            var s = sizeSpans[i];
            var parent = s.parentNode;
            while (s.firstChild) parent.insertBefore(s.firstChild, s);
            parent.removeChild(s);
        }

        var factor = 0;
        if (value === 'title')    factor = 2.0;
        if (value === 'subtitle') factor = 1.5;
        if (value === 'chapter')  factor = 1.75;

        if (factor > 0) {
            var span = document.createElement('span');
            span.style.fontSize = factor + 'em';
            span.setAttribute('data-font-size', factor);
            span.classList.add('large-text');
            while (block.firstChild) span.appendChild(block.firstChild);
            block.appendChild(span);
        }
        editor.focus();
    };

    window.applyFont = function(fontKey) {
        applyFontToEditor(fontKey);
        dirty = true;
        saveStatusEl.textContent = 'Unsaved changes';
        scheduleSave();
    };

    function applyFontToEditor(fontKey) {
        editor.style.fontFamily = FONTS[fontKey] || FONTS['default'];
    }

    // ── Helpers ────────────────────────────────────────────────────
    function getSelectedBlock() {
        var sel = window.getSelection();
        if (!sel || sel.rangeCount === 0) return null;
        var node = sel.anchorNode;
        var blockTags = ['P','DIV','H1','H2','H3','H4','BLOCKQUOTE'];
        while (node && node !== editor) {
            if (node.nodeType === 1 && blockTags.indexOf(node.tagName) !== -1) return node;
            node = node.parentNode;
        }
        return null;
    }

    function updateToolbarState() {
        document.getElementById('btnBold').classList.toggle('active', document.queryCommandState('bold'));
        document.getElementById('btnItalic').classList.toggle('active', document.queryCommandState('italic'));
        var block = getSelectedBlock();
        var isCenter = block && block.classList.contains('align-center');
        var isRight  = block && block.classList.contains('align-right');
        document.getElementById('btnAlignLeft').classList.toggle('active', !isCenter && !isRight);
        document.getElementById('btnAlignCenter').classList.toggle('active', !!isCenter);
        document.getElementById('btnAlignRight').classList.toggle('active', !!isRight);
        if (block) {
            var sizeSpan = block.querySelector('.large-text, span[data-font-size]');
            if (sizeSpan) {
                var size = parseFloat(sizeSpan.getAttribute('data-font-size') || sizeSpan.style.fontSize);
                if (size >= 1.9)      headingSelect.value = 'title';
                else if (size >= 1.6) headingSelect.value = 'chapter';
                else if (size >= 1.4) headingSelect.value = 'subtitle';
                else                  headingSelect.value = 'p';
            } else {
                headingSelect.value = 'p';
            }
        }
    }

    document.addEventListener('selectionchange', updateToolbarState);

    function updateWordCount() {
        var text = editor.innerText || '';
        var words = text.trim() === '' ? 0 : text.trim().split(/\s+/).length;
        wordCountEl.textContent = 'Words: ' + words;
    }

    // ── Keyboard shortcuts ─────────────────────────────────────────
    document.addEventListener('keydown', function(e) {
        if ((e.ctrlKey || e.metaKey) && e.key === 's') {
            e.preventDefault();
            saveDocument();
        }
    });

    editor.addEventListener('keydown', function(e) {
        if (e.key === 'Enter' && !e.shiftKey) {
            document.execCommand('defaultParagraphSeparator', false, 'p');
        }
    });
    document.execCommand('defaultParagraphSeparator', false, 'p');

    window.addEventListener('beforeunload', function(e) {
        if (dirty) { e.preventDefault(); e.returnValue = ''; }
    });

    function showToast(msg) {
        var toast = document.getElementById('toast');
        toast.textContent = msg;
        toast.classList.add('show');
        setTimeout(function() { toast.classList.remove('show'); }, 2500);
    }
    window.showToast = showToast;

})();
</script>
</body>
</html>
""".trimIndent()

            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }

        // ── GET /readonly — the original read-only print view ────────

        private fun serveReadOnlyPage(): Response {
            val book = runBlocking { database.bookDao().getBook(bookId) }
                ?: return newFixedLengthResponse(
                    Response.Status.NOT_FOUND, "text/html", "<h1>Book not found</h1>"
                )

            val fontName = book.fontName
            var googleFontUrl = ""
            var cssFontFamily = "'Calibri', 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif"

            if (fontName != "default" && FONT_MAPPINGS.containsKey(fontName)) {
                val mapping = FONT_MAPPINGS[fontName]!!
                googleFontUrl = """<link href="${mapping.googleFontUrl}" rel="stylesheet">"""
                cssFontFamily = "'${mapping.familyName}', serif"
            }

            val bodyContent = when (book.contentFormat) {
                ContentFormat.HTML -> processFormattedHtml(book.formattedContent ?: book.storyContent)
                else -> convertPlainTextToHtml(book.storyContent)
            }

            val usedFonts = extractUsedFonts(bodyContent)
            val dynamicFontStyles = generateFontStyles(usedFonts)
            val dynamicFontLinks = generateFontLinks(usedFonts)

            return newFixedLengthResponse(Response.Status.OK, "text/html", """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${escapeHtml(book.title)}</title>
    $googleFontUrl
    $dynamicFontLinks
    <style>
        @page { margin: 1in; size: auto; }
        body { font-family: $cssFontFamily; line-height: 1.5; color: #000; background: #fff; margin: 0; padding: 0; }
        .container { max-width: 100%; padding: 0; }
        .content { text-align: justify; margin-top: 0; }
        .content p { margin-bottom: 1em; orphans: 3; widows: 3; }
        .content p.indented-paragraph { text-indent: 2em; }
        .content b, .content strong { font-weight: bold; }
        .content i, .content em { font-style: italic; }
        .content .large-text { font-size: 14pt; }
        .content .small-text { font-size: 9pt; }
        .content .align-center { text-align: center !important; text-indent: 0 !important; }
        .content .align-right { text-align: right !important; text-indent: 0 !important; }
        $dynamicFontStyles
        .nav-bar { display: block; padding: 16px; text-align: center; background: #f5f5f5; border-bottom: 1px solid #ddd; }
        .nav-bar a { color: #2b579a; text-decoration: none; font-size: 14px; font-weight: 500; }
        .nav-bar a:hover { text-decoration: underline; }
        @media print { .nav-bar { display: none; } body { -webkit-print-color-adjust: exact; } }
    </style>
</head>
<body>
    <div class="nav-bar"><a href="/">← Back to Editor</a></div>
    <div class="container">
        <div class="content">$bodyContent</div>
    </div>
</body>
</html>
""".trimIndent())
        }

        // ── HTML processing helpers ──────────────────────────────────

        private fun processFormattedHtml(html: String): String {
            var processed = html
            val bodyPattern = Regex("""<body[^>]*>(.*?)</body>""", RegexOption.DOT_MATCHES_ALL)
            val bodyMatch = bodyPattern.find(processed)
            if (bodyMatch != null) processed = bodyMatch.groupValues[1]

            processed = processed
                .replace(Regex("""</?html[^>]*>"""), "")
                .replace(Regex("""</?head[^>]*>"""), "")
                .replace(Regex("""</?body[^>]*>"""), "")
                .replace(Regex("""<meta[^>]*>"""), "")
                .replace(Regex("""<title[^>]*>.*?</title>"""), "")
                .replace(Regex("""<script[^>]*>.*?</script>""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""<img[^>]*>"""), "")

            processed = convertFontReferences(processed)

            processed = processed
                .replace(Regex("""<span\s+data-alignment="center"[^>]*>"""), """<span class="align-center">""")
                .replace(Regex("""<span\s+data-alignment="right"[^>]*>"""), """<span class="align-right">""")
                .replace(Regex("""<span\s+data-alignment="left"[^>]*>"""), """<span>""")
                .replace(Regex("""<span\s+style="font-size:\s*([\d.]+)em;"[^>]*>""")) { match ->
                    val size = match.groupValues[1].toFloatOrNull() ?: 1.0f
                    when {
                        size >= 1.5f -> """<span class="large-text" data-font-size="$size" style="font-size: ${size}em;">"""
                        size <= 0.8f -> """<span class="small-text">"""
                        else -> "<span>"
                    }
                }
                .replace(Regex("""<p\s+class="indented-paragraph">"""), """<p class="indented-paragraph">""")
                .trim()

            return processed
        }

        private fun convertPlainTextToHtml(text: String): String {
            return text.split("\n\n").filter { it.isNotBlank() }
                .joinToString("") { "<p>${escapeHtml(it.trim())}</p>" }
        }

        private fun convertFontReferences(html: String): String {
            var processed = html
            processed = processed.replace(Regex("""<span([^>]*?)data-font-resource-id="(\d+)"([^>]*?)>""")) { match ->
                val beforeAttr = match.groupValues[1]
                val resourceId = match.groupValues[2].toIntOrNull()
                val afterAttr = match.groupValues[3]
                if (resourceId != null) {
                    val name = getFontFieldNameFromResourceId(resourceId)
                    if (name != null) {
                        val other = (beforeAttr + afterAttr).trim()
                        if (other.isNotEmpty()) """<span class="font-${name.replace("_", "-")}" $other>"""
                        else """<span class="font-${name.replace("_", "-")}">"""
                    } else "<span${beforeAttr}${afterAttr}>"
                } else "<span${beforeAttr}${afterAttr}>"
            }
            processed = processed.replace(Regex("""<span([^>]*?)data-font-name="([^"]+)"([^>]*?)>""")) { match ->
                val beforeAttr = match.groupValues[1]; val fontName = match.groupValues[2]; val afterAttr = match.groupValues[3]
                val cleaned = (beforeAttr + afterAttr).replace(Regex("""data-font-resource-id="\d+""""), "").trim()
                val cls = "font-${fontName.replace(" ", "-")}"
                if (cleaned.isNotEmpty()) """<span class="$cls" $cleaned>""" else """<span class="$cls">"""
            }
            return processed
        }

        private fun extractUsedFonts(html: String): Set<String> {
            val used = mutableSetOf<String>()
            Regex("""data-font-resource-id="(\d+)"""").findAll(html).forEach {
                val id = it.groupValues[1].toIntOrNull()
                if (id != null) getFontFieldNameFromResourceId(id)?.let { n -> used.add(n) }
            }
            Regex("""data-font-name="([^"]+)"""").findAll(html).forEach {
                val field = it.groupValues[1].lowercase().replace(" ", "_")
                if (FONT_MAPPINGS.containsKey(field)) used.add(field)
            }
            return used
        }

        private fun getFontFieldNameFromResourceId(resourceId: Int): String? {
            try {
                for (field in R.font::class.java.fields) {
                    if (field.getInt(null) == resourceId) return field.name
                }
            } catch (e: Exception) { Log.e(TAG, "Error finding font field name", e) }
            return null
        }

        private fun generateFontLinks(usedFonts: Set<String>): String {
            return usedFonts.mapNotNull { FONT_MAPPINGS[it]?.googleFontUrl }.toSet()
                .joinToString("\n") { """<link href="$it" rel="stylesheet">""" }
        }

        private fun generateFontStyles(usedFonts: Set<String>): String {
            return usedFonts.mapNotNull { key ->
                FONT_MAPPINGS[key]?.let { m ->
                    """.font-${key.replace("_", "-")} { font-family: '${m.familyName}', serif !important; }"""
                }
            }.joinToString("\n")
        }

        private fun escapeHtml(text: String): String {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;")
        }

        private fun escapeAttr(text: String): String {
            return text.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Activity helpers
    // ─────────────────────────────────────────────────────────────────────

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

    private fun isPrivateIpAddress(addr: String): Boolean {
        return addr.startsWith("192.168.") || addr.startsWith("10.") || isPrivate172Address(addr)
    }

    private fun isPrivate172Address(addr: String): Boolean {
        if (!addr.startsWith("172.")) return false
        val secondOctet = addr.removePrefix("172.").substringBefore(".").toIntOrNull() ?: return false
        return secondOctet in 16..31
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val sAddr = addr.hostAddress ?: continue
                        if (isPrivateIpAddress(sAddr)) return sAddr
                    }
                }
            }
            getWifiIpAddress()?.let { return it }
        } catch (e: Exception) { Log.e(TAG, "Error getting IP address", e) }

        try {
            for (intf in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) return addr.hostAddress ?: "localhost"
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Error in fallback IP detection", e) }

        return "localhost"
    }

    private fun getWifiIpAddress(): String? {
        try {
            val wifiInterface = NetworkInterface.getByName("wlan0")
            if (wifiInterface != null && wifiInterface.isUp) {
                for (addr in Collections.list(wifiInterface.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) return addr.hostAddress
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Error getting WiFi IP", e) }
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