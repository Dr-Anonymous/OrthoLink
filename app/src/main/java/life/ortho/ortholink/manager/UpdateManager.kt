package life.ortho.ortholink.manager

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import life.ortho.ortholink.BuildConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

object UpdateManager {

    private const val GITHUB_REPO = "Dr-Anonymous/OrthoView"

    data class GitHubRelease(
        @SerializedName("tag_name") val tagName: String,
        @SerializedName("assets") val assets: List<GitHubAsset>,
        @SerializedName("body") val body: String?
    )

    data class GitHubAsset(
        @SerializedName("browser_download_url") val downloadUrl: String,
        @SerializedName("content_type") val contentType: String
    )

    fun checkForUpdate(activity: Activity) {
        // Run network operation on background thread
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) return

                val responseBody = response.body?.string() ?: return
                try {
                    val release = Gson().fromJson(responseBody, GitHubRelease::class.java)
                    val latestTag = release.tagName.replace("v", "") // e.g., "1.0.123"
                    val currentVersion = BuildConfig.VERSION_NAME

                    if (isUpdateAvailable(currentVersion, latestTag)) {
                        activity.runOnUiThread {
                            showUpdateDialog(activity, release)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        })
    }

    private fun isUpdateAvailable(current: String, latest: String): Boolean {
        try {
            // Compare version names component by component, or just check if different for now
            // Simpler approach: Extract the verify build number (last component) if semantic versioning
            // Assuming 1.0.X format
            val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
            val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }

            val length = maxOf(currentParts.size, latestParts.size)
            for (i in 0 until length) {
                val v1 = if (i < currentParts.size) currentParts[i] else 0
                val v2 = if (i < latestParts.size) latestParts[i] else 0
                if (v2 > v1) return true
                if (v1 > v2) return false
            }
            return false
        } catch (e: Exception) {
            return false
        }
    }

    private fun showUpdateDialog(activity: Activity, release: GitHubRelease) {
        if (activity.isFinishing || activity.isDestroyed) return
        
        val downloadUrl = release.assets.firstOrNull { it.downloadUrl.endsWith(".apk") }?.downloadUrl ?: return

        AlertDialog.Builder(activity)
            .setTitle("New Update Available")
            .setMessage("Version ${release.tagName} includes new features and fixes.\n\n${release.body ?: ""}\n\nUpdate now?")
            .setPositiveButton("Update") { _, _ ->
                downloadAndInstall(activity, downloadUrl)
            }
            .setNegativeButton("Ignore", null)
            .show()
    }

    private fun downloadAndInstall(context: Context, url: String) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadFileName = "OrthoLink_Update_${System.currentTimeMillis()}.apk"

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("OrthoLink Update")
            .setDescription("Downloading version update...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, downloadFileName)
            .setMimeType("application/vnd.android.package-archive")

        val downloadId = downloadManager.enqueue(request)

        // Register receiver for when download is complete
        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(ctxt: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == downloadId) {
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = downloadManager.query(query)
                    if (cursor.moveToFirst()) {
                        val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        if (statusIndex != -1) {
                            val status = cursor.getInt(statusIndex)
                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                installDownloadedApk(ctxt, downloadManager, downloadId)
                            } else {
                                android.util.Log.e("UpdateManager", "Download failed with status: $status")
                            }
                        }
                    }
                    cursor.close()
                    try {
                        ctxt.unregisterReceiver(this)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
        
        ContextCompat.registerReceiver(
            context,
            onComplete,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
        
        Toast.makeText(context, "Update downloading in background...", Toast.LENGTH_SHORT).show()
    }

    private fun installDownloadedApk(context: Context, downloadManager: DownloadManager, downloadId: Long) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                intent.data = Uri.parse("package:${context.packageName}")
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                Toast.makeText(context, "Please enable install permission and try again", Toast.LENGTH_LONG).show()
                return
            }
        }

        val uri = downloadManager.getUriForDownloadedFile(downloadId)
        if (uri == null) {
            Toast.makeText(context, "Downloaded update not found", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_VIEW)
        intent.addCategory(Intent.CATEGORY_DEFAULT)
        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                       Intent.FLAG_GRANT_READ_URI_PERMISSION or
                       Intent.FLAG_ACTIVITY_CLEAR_TOP
        
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Update installation error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
