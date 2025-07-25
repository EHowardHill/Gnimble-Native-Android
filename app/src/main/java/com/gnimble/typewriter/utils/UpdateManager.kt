// UpdateManager.kt
package com.gnimble.typewriter.utils

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateManager(private val context: Context) {

    companion object {
        private const val UPDATE_CHECK_URL = "https://cinemint.online/static/typewriter/version.json"
        private const val APK_FILENAME = "typewriter-update.apk"
        private const val REQUEST_INSTALL_PACKAGES = 1001
    }

    private var downloadId: Long = -1
    private var progressDialog: AlertDialog? = null

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val releaseNotes: String = "",
        val minSdkVersion: Int = 21,
        val fileSizeBytes: Long = 0
    )

    fun checkForUpdates(onComplete: (Boolean) -> Unit = {}) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val updateInfo = fetchUpdateInfo()
                val currentVersionCode = getCurrentVersionCode()

                withContext(Dispatchers.Main) {
                    when {
                        updateInfo == null -> {
                            showMessage("Unable to check for updates. Please try again later.")
                            onComplete(false)
                        }
                        updateInfo.versionCode > currentVersionCode -> {
                            showUpdateDialog(updateInfo)
                            onComplete(true)
                        }
                        else -> {
                            showMessage("You're running the latest version!")
                            onComplete(false)
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showMessage("Update check failed: ${e.message}")
                    onComplete(false)
                }
            }
        }
    }

    private suspend fun fetchUpdateInfo(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL(UPDATE_CHECK_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)

                UpdateInfo(
                    versionCode = json.getInt("versionCode"),
                    versionName = json.getString("versionName"),
                    apkUrl = json.getString("apkUrl"),
                    releaseNotes = json.optString("releaseNotes", ""),
                    minSdkVersion = json.optInt("minSdkVersion", 21),
                    fileSizeBytes = json.optLong("fileSizeBytes", 0)
                )
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun getCurrentVersionCode(): Int {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }

            packageInfo.longVersionCode.toInt()
        } catch (e: Exception) {
            0
        }
    }

    private fun showUpdateDialog(updateInfo: UpdateInfo) {
        val message = buildString {
            append("A new version is available!\n\n")
            append("Current version: ${getCurrentVersionName()}\n")
            append("New version: ${updateInfo.versionName}\n")
            if (updateInfo.fileSizeBytes > 0) {
                append("Download size: ${formatFileSize(updateInfo.fileSizeBytes)}\n")
            }
            if (updateInfo.releaseNotes.isNotEmpty()) {
                append("\nWhat's new:\n${updateInfo.releaseNotes}")
            }
        }

        AlertDialog.Builder(context)
            .setTitle("Update Available")
            .setMessage(message)
            .setPositiveButton("Update") { dialog, _ ->
                dialog.dismiss()
                checkAndRequestInstallPermission(updateInfo)
            }
            .setNegativeButton("Later") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .show()
    }

    private fun checkAndRequestInstallPermission(updateInfo: UpdateInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                AlertDialog.Builder(context)
                    .setTitle("Permission Required")
                    .setMessage("To install updates, please enable 'Install unknown apps' permission for this app.")
                    .setPositiveButton("Open Settings") { dialog, _ ->
                        dialog.dismiss()
                        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    }
                    .setNegativeButton("Cancel") { dialog, _ ->
                        dialog.dismiss()
                    }
                    .show()
                return
            }
        }

        downloadUpdate(updateInfo)
    }

    private fun downloadUpdate(updateInfo: UpdateInfo) {
        showProgressDialog()

        try {
            // Delete any existing update file
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_FILENAME)
            if (file.exists()) {
                file.delete()
            }

            // Create download request
            val request = DownloadManager.Request(Uri.parse(updateInfo.apkUrl))
                .setTitle("Downloading Typewriter Update")
                .setDescription("Version ${updateInfo.versionName}")
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_FILENAME)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadId = downloadManager.enqueue(request)

            // Register receiver for download completion
            context.registerReceiver(
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED
            )

        } catch (e: Exception) {
            dismissProgressDialog()
            showMessage("Download failed: ${e.message}")
        }
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == downloadId) {
                context.unregisterReceiver(this)
                dismissProgressDialog()

                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)

                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            val localUri = cursor.getString(
                                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
                            )
                            installApk(Uri.parse(localUri))
                        }
                        DownloadManager.STATUS_FAILED -> {
                            val reason = cursor.getInt(
                                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                            )
                            showMessage("Download failed. Error code: $reason")
                        }
                    }
                }
                cursor.close()
            }
        }
    }

    private fun installApk(apkUri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    // For Android N and above, use FileProvider
                    val file = File(apkUri.path ?: return)
                    val contentUri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    setDataAndType(contentUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    // For older versions
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            showMessage("Installation failed: ${e.message}")
        }
    }

    private fun showProgressDialog() {
        progressDialog = AlertDialog.Builder(context)
            .setTitle("Downloading Update")
            .setMessage("Please wait...")
            .setCancelable(false)
            .setNegativeButton("Cancel") { dialog, _ ->
                cancelDownload()
                dialog.dismiss()
            }
            .create()
        progressDialog?.show()
    }

    private fun dismissProgressDialog() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    private fun cancelDownload() {
        if (downloadId != -1L) {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.remove(downloadId)
            downloadId = -1
            try {
                context.unregisterReceiver(downloadReceiver)
            } catch (e: Exception) {
                // Receiver might not be registered
            }
        }
    }

    private fun getCurrentVersionName(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName
        } catch (e: Exception) {
            "Unknown"
        }.toString()
    }

    @SuppressLint("DefaultLocale")
    private fun formatFileSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1 -> String.format("%.1f MB", mb)
            kb >= 1 -> String.format("%.1f KB", kb)
            else -> "$bytes bytes"
        }
    }

    private fun showMessage(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}