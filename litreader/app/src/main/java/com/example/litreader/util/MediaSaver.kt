package com.example.litreader.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** 画廊图片落盘：保存到系统相册 / 写缓存目录供分享。 */
object MediaSaver {
    private const val DIR_NAME = "纸墨"

    fun mimeType(url: String): String = when {
        url.endsWith(".png", true) -> "image/png"
        url.endsWith(".gif", true) -> "image/gif"
        url.endsWith(".webp", true) -> "image/webp"
        else -> "image/jpeg"
    }

    private fun fileName(url: String): String {
        val ext = url.substringAfterLast('.', "jpg").take(5).ifEmpty { "jpg" }
        return "${DIR_NAME}_${System.currentTimeMillis()}.$ext"
    }

    /** API 29+ 走 MediaStore；旧版本写公共 Pictures 目录（调用方确保已授权）。 */
    fun saveToGallery(ctx: Context, bytes: ByteArray, sourceUrl: String): Boolean {
        val name = fileName(sourceUrl)
        val mime = mimeType(sourceUrl)
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + DIR_NAME)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            ctx.contentResolver.update(uri, done, null, null)
            true
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), DIR_NAME)
            if (!dir.exists() && !dir.mkdirs()) return false
            val file = File(dir, name)
            file.writeBytes(bytes)
            MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf(mime), null)
            true
        }
    }

    /** 写入缓存目录并返回 FileProvider uri，用于系统分享。 */
    fun cacheForShare(ctx: Context, bytes: ByteArray, sourceUrl: String): Uri {
        val ext = sourceUrl.substringAfterLast('.', "jpg").take(5).ifEmpty { "jpg" }
        val file = File(ctx.cacheDir, "share_${System.currentTimeMillis()}.$ext")
        file.writeBytes(bytes)
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", file)
    }
}
