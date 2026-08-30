package com.example.litreader.util

import androidx.activity.ComponentActivity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
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
 * 因此检查与下载都按 raw → jsDelivr（多个节点）的顺序回退；
 * 版本检查带 ?ts= 防止 CDN 返回陈旧缓存；APK 下载带百分比进度对话框。
 */
object UpdateManager {
    private const val REPO = "wacilimonster-source/wxds"

    // 版本检查 raw 优先（缓存仅 5 分钟、永远新鲜），jsDelivr 各节点作被墙回退
    private val VERSION_URLS = listOf(
        "https://raw.githubusercontent.com/$REPO/master/latest.json",
        "https://cdn.jsdelivr.net/gh/$REPO@master/latest.json",
        "https://fastly.jsdelivr.net/gh/$REPO@master/latest.json",
        "https://gcore.jsdelivr.net/gh/$REPO@master/latest.json"
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
        val apkName: String,
        val notes: String = ""
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

    /** 逐源尝试读 latest.json（raw 优先，jsDelivr 兜底）；?ts= 防 CDN 陈旧缓存。 */
    private suspend fun fetchLatestApk(): ReleaseInfo? = withContext(Dispatchers.IO) {
        for (url in VERSION_URLS) {
            try {
                val req = Request.Builder()
                    .url("$url?ts=${System.currentTimeMillis()}")
                    .header("User-Agent", UA)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val json = org.json.JSONObject(resp.body?.string() ?: "")
                    val versionName = json.optString("versionName")
                    val apk = json.optString("apk")
                    if (versionName.isNotEmpty() && apk.isNotEmpty()) {
                        return@withContext ReleaseInfo(
                            versionName, parseVersionCode(versionName), apk, json.optString("notes")
                        )
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
            val msg = buildString {
                append("发现新版本 ${info.versionName}，是否下载安装？")
                if (info.notes.isNotBlank()) {
                    append("\n\n")
                    append(info.notes)
                }
            }
            AlertDialog.Builder(activity)
                .setTitle("有可用更新")
                .setMessage(msg)
                .setPositiveButton("立即更新") { _, _ ->
                    downloadAndInstall(activity, info, activity.lifecycleScope)
                }
                .setNegativeButton("稍后", null)
                .setCancelable(false)
                .show()
        }
    }

    private fun downloadAndInstall(activity: ComponentActivity, info: ReleaseInfo, scope: CoroutineScope) {
        scope.launch {
            val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return@launch
            val file = File(dir, "litreader-update.apk")

            // 进度对话框
            val pad = (16 * activity.resources.displayMetrics.density).toInt()
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
            }
            val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
            val tv = TextView(activity).apply {
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, pad / 2, 0, 0)
            }
            box.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            box.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            val dialog = AlertDialog.Builder(activity)
                .setTitle("下载更新")
                .setView(box)
                .setCancelable(false)
                .create()
            dialog.show()
            fun onProgress(fraction: Float) {
                activity.runOnUiThread {
                    if (fraction >= 0f) {
                        bar.isIndeterminate = false
                        bar.max = 100
                        bar.progress = (fraction * 100).toInt()
                        tv.text = "${(fraction * 100).toInt()}%"
                    } else {
                        bar.isIndeterminate = true
                        tv.text = "准备中…"
                    }
                }
            }

            var ok = false
            try {
                for (base in APK_BASES) {
                    ok = withContext(Dispatchers.IO) {
                        runCatching { downloadTo(base + info.apkName, file, ::onProgress) }.getOrDefault(false)
                    }
                    if (ok) break
                }
            } finally {
                dialog.dismiss()
            }
            if (ok) {
                installApk(activity, file)
            } else {
                showToast(activity, "下载失败，请检查网络后重试")
            }
        }
    }

    /** 流式下载到 .part 临时文件，成功后原子改名；fraction<0 表示总长未知。 */
    private fun downloadTo(url: String, file: File, onProgress: (Float) -> Unit): Boolean {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return false
            val body = resp.body ?: return false
            val total = body.contentLength()
            val tmp = File(file.parentFile, file.name + ".part")
            tmp.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(if (total > 0) done.toFloat() / total else -1f)
                    }
                }
            }
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
