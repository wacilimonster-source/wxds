package com.example.litreader.util

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
import org.json.JSONArray
import java.util.concurrent.TimeUnit

object UpdateManager {
    private const val REPO = "wacilimonster-source/wxds"
    private const val CONTENTS_API = "https://api.github.com/repos/$REPO/contents/"
    private val APK_NAME = Regex("""^litreader-v(\d+)\.(\d+)(?:\.(\d+))?\.apk$""")

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class ReleaseInfo(
        val versionName: String,
        val versionCode: Int,
        val downloadUrl: String
    )

    @Suppress("UNUSED_PARAMETER")
    fun checkAndPromptUpdate(activity: ComponentActivity, scope: CoroutineScope, silent: Boolean = false) {
        scope.launch {
            val info = fetchLatestApk()
            if (info == null) {
                if (!silent) showToast(activity, "检查更新失败")
                return@launch
            }
            if (info.versionCode > parseVersionCode(Constants.VERSION_NAME)) {
                showUpdateDialog(activity, info)
            } else if (!silent) {
                showToast(activity, "已是最新版")
            }
        }
    }

    /** 扫描仓库根目录的 litreader-vX.Y[.Z].apk，取版本号最高的一个（不依赖 GitHub Releases）。 */
    private suspend fun fetchLatestApk(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(CONTENTS_API)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "LitReader-UpdateChecker")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val arr = JSONArray(resp.body?.string() ?: "[]")
                var best: ReleaseInfo? = null
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val name = obj.optString("name")
                    val m = APK_NAME.find(name) ?: continue
                    val major = m.groupValues[1].toInt()
                    val minor = m.groupValues[2].toInt()
                    val patch = m.groupValues[3].ifEmpty { "0" }.toInt()
                    val code = major * 10000 + minor * 100 + patch
                    val url = obj.optString("download_url")
                    if (url.isNotEmpty() && (best == null || code > best!!.versionCode)) {
                        best = ReleaseInfo("$major.$minor.$patch", code, url)
                    }
                }
                best
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseVersionCode(versionName: String): Int {
        return try {
            val parts = versionName.split(".").map { it.toInt() }
            (parts.getOrNull(0) ?: 0) * 10000 + (parts.getOrNull(1) ?: 0) * 100 + (parts.getOrNull(2) ?: 0)
        } catch (e: Exception) {
            0
        }
    }

    private fun showUpdateDialog(activity: ComponentActivity, info: ReleaseInfo) {
        activity.runOnUiThread {
            val msg = "发现新版本 ${info.versionName}，是否下载安装？"
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
