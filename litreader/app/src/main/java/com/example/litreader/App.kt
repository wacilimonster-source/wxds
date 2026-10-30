package com.example.litreader

import android.app.Application
import com.example.litreader.data.db.AppDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {
    val database by lazy { AppDatabase.build(this) }

    override fun onCreate() {
        super.onCreate()
        setupCrashLog()
    }

    /**
     * 未捕获异常落盘（保留最近 5 份），随后仍交给系统处理。
     * 个人分发没有后台，本地日志是排查线上崩溃的唯一手段。
     */
    private fun setupCrashLog() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val dir = getExternalFilesDir("crash") ?: return@runCatching
                dir.listFiles()?.sortedByDescending { it.name }?.drop(4)?.forEach { it.delete() }
                val time = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                File(dir, "crash-$time.txt").writeText(
                    buildString {
                        appendLine("version: ${com.example.litreader.util.Constants.VERSION_NAME}")
                        appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
                        appendLine("thread: ${thread.name}")
                        appendLine()
                        append(android.util.Log.getStackTraceString(e))
                    }
                )
            }
            prev?.uncaughtException(thread, e)
        }
    }
}
