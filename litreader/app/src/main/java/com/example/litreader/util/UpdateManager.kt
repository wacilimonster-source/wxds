package com.example.litreader.util

import androidx.activity.ComponentActivity
import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 应用内更新。raw.githubusercontent.com 在国内时通时断，
 * 因此检查与下载都按 jsDelivr CDN（多个节点）→ raw 的顺序回退；
 * APK 下载用 OkHttp 直下（可逐源重试），安装经 FileProvider 走 content://。
 */
object UpdateManager {
    private const val REPO = "wacilimonster-source/wxds"

    private val VERSION_URLS = listOf(
        "https://cdn.jsdelivr.net/gh/$REPO@master/latest.json",
        "https://fastly.jsdelivr.net/gh/$REPO@master/latest.json",
        "https://gcore.jsdelivr.net/gh/$REPO@master/latest.json",
        "https://raw.githubusercontent.com/$REPO/master/latest.json"
    )
    private val APK_BASES = listOf(
        "https://cdn.jsdelivr.net/gh/$REPO@master/",
        "https://fastly.jsdelivr.net/gh/$REPO@master/",
        "https://gcore.jsdelivr.net/gh/$REPO@master/",
        "https://raw.githubusercontent.com/$REPO/master/"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val UA = "LitReader-UpdateChecker"

    data class ReleaseInfo(
        val versionName: String,
        val versionCode: Int,
        val apkName: String
    )

    @Suppress("UNUSED_PARAMETER")
    fun checkAndPromptUpdate(activity: ComponentActivity, scope: CoroutineScope, silent: Boolean = false) {
        scope.launch {
            val info = fetchLatestApk()
            if (info == null) {
                showToast(activity, "检查更新失败")
                return@launch
            }
            if (info.versionCode > parseVersionCode(Constants.VERSION_NAME)) {
                showUpdateDialog(activity, info)
            } else if (!silent) {
                showToast(activity, "已是最新版")
            }
        }
    }

    /** 逐源尝试读 latest.json（jsDelivr 优先，raw 兜底）。 */
    private suspend fun fetchLatestApk(): ReleaseInfo? = withContext(Dispatchers.IO) {
        for (url in VERSION_URLS) {
            try {
                val req = Request.Builder().url(url).header("User-Agent", UA).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val json = org.json.JSONObject(resp.body?.string() ?: "")
                    val versionName = json.optString("versionName")
                    val apk = json.optString("apk")
                    if (versionName.isNotEmpty() && apk.isNotEmpty()) {
                        return@withContext ReleaseInfo(versionName, parseVersionCode(versionName), apk)
                    }
                }
            } catch (_: Exception) {
                // 换下一个源
            }
        }
        null
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
                .setPositiveButton("立即更新") { _, _ -> downloadAndInstall(activity, info, activity.lifecycleScope) }
                .setNegativeButton("稍后", null)
                .setCancelable(false)
                .show()
        }
    }

    private fun downloadAndInstall(activity: ComponentActivity, info: ReleaseInfo, scope: CoroutineScope) {
        scope.launch {
            showToast(activity, "开始下载更新…")
            val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return@launch
            val file = File(dir, "litreader-update.apk")
            for (base in APK_BASES) {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { downloadTo(base + info.apkName, file) }.getOrDefault(false)
                }
                if (ok) {
                    installApk(activity, file)
                    return@launch
                }
            }
            showToast(activity, "下载失败，请检查网络后重试")
        }
    }

    /** 流式下载到 .part 临时文件，成功后原子改名，避免装进半截包。 */
    private fun downloadTo(url: String, file: File): Boolean {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return false
            val body = resp.body ?: return false
            val tmp = File(file.parentFile, file.name + ".part")
            tmp.outputStream().use { out -> body.byteStream().copyTo(out, 64 * 1024) }
            if (tmp.length() == 0L) return false
            if (file.exists()) file.delete()
            return tmp.renameTo(file)
        }
    }

    private fun installApk(activity: ComponentActivity, file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, activity.packageName)
        }
        activity.startActivity(intent)
    }

    private fun showToast(activity: ComponentActivity, msg: String) {
        activity.runOnUiThread { android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show() }
    }
}
