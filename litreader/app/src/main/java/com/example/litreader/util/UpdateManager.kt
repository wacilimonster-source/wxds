package com.example.litreader.util

import android.app.Activity
import androidx.activity.ComponentActivity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object UpdateManager {
    private const val GITHUB_API = "https://api.github.com/repos/wacilimonster-source/wxds/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class ReleaseInfo(
        val tagName: String,
        val versionName: String,
        val versionCode: Int,
        val downloadUrl: String,
        val body: String
    )

    @Suppress("UNUSED_PARAMETER")
    fun checkAndPromptUpdate(activity: ComponentActivity, scope: CoroutineScope, silent: Boolean = false) {
        scope.launch {
            val currentCode = Constants.VERSION_CODE
            val info = fetchLatestRelease()
            if (info == null) {
                if (!silent) showToast(activity, "检查更新失败")
                return@launch
            }
            if (info.versionCode > currentCode) {
                showUpdateDialog(activity, info)
            } else if (!silent) {
                showToast(activity, "已是最新版")
            }
        }
    }

    private suspend fun fetchLatestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(GITHUB_API)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "LitReader-UpdateChecker")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val json = JSONObject(resp.body?.string() ?: "")
                val tagName = json.getString("tag_name")
                val body = json.getString("body")
                val assets = json.getJSONArray("assets")
                var apkUrl = ""
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    if (name.endsWith(".apk")) {
                        apkUrl = asset.getString("browser_download_url")
                        break
                    }
                }
                if (apkUrl.isEmpty()) return@withContext null
                val versionName = tagName.removePrefix("v")
                val versionCode = parseVersionCode(versionName)
                ReleaseInfo(tagName, versionName, versionCode, apkUrl, body)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseVersionCode(versionName: String): Int {
        try {
            val parts = versionName.split(".").map { it.toInt() }
            return (parts.getOrNull(0) ?: 1) * 10000 + (parts.getOrNull(1) ?: 0) * 100 + (parts.getOrNull(2) ?: 0)
        } catch (e: Exception) {
            return 1
        }
    }

    private fun showUpdateDialog(activity: ComponentActivity, info: ReleaseInfo) {
        activity.runOnUiThread {
            val msg = "发现新版本 ${info.versionName}\n${info.body}"
            androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle("有可用更新")
                .setMessage(msg)
                .setPositiveButton("立即更新") { _, _ -> downloadAndInstall(activity, info.downloadUrl) }
                .setNegativeButton("稍后", null)
                .setCancelable(false)
                .show()
        }
    }

    private fun downloadAndInstall(activity: ComponentActivity, url: String) {
        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("LitReader 更新")
            .setDescription("正在下载...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, "litreader-update.apk")
            .setMimeType("application/vnd.android.package-archive")
        val downloadId = dm.enqueue(request)
        activity.runOnUiThread {
            val observer = object : LifecycleObserver {
                @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
                fun checkStatus() {
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = dm.query(query)
                    if (cursor.moveToFirst()) {
                        val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            val uri = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI))
                            installApk(activity, Uri.parse(uri))
                            activity.lifecycle.removeObserver(this)
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            showToast(activity, "下载失败")
                            activity.lifecycle.removeObserver(this)
                        }
                    }
                    cursor.close()
                }
            }
            activity.lifecycle.addObserver(observer)
        }
    }

    private fun installApk(activity: ComponentActivity, fileUri: Uri) {
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = fileUri
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, activity.packageName)
        }
        activity.startActivity(intent)
    }

    private fun showToast(activity: ComponentActivity, msg: String) {
        activity.runOnUiThread { android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show() }
    }
}