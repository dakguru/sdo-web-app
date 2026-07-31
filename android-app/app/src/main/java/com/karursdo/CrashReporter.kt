package com.karursdo

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-resort crash logger for a sideloaded internal app with no crash backend:
 * writes the stack trace of any uncaught exception to the phone's Downloads
 * folder (visible in any file manager) before letting the system kill the app.
 * Contains NO staff data — only the exception trace.
 */
object CrashReporter {

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeReport(context, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeReport(context: Context, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val body = buildString {
            appendLine("Karur SDO crash report · $stamp")
            appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            append(sw.toString())
        }
        val fileName = "karursdo-crash-${System.currentTimeMillis()}.txt"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(body.toByteArray())
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            runCatching { File(dir, fileName).writeText(body) }
                .onFailure {
                    File(context.getExternalFilesDir(null), fileName).writeText(body)
                }
        }
    }
}
