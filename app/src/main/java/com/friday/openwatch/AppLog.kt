package com.friday.openwatch

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight persistent log so connection/protocol issues can be diagnosed
 * from inside the app itself, without needing adb/logcat access.
 */
object AppLog {
    private const val FILE_NAME = "openwatch_log.txt"
    private const val MAX_BYTES = 200_000
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    @Synchronized
    fun log(ctx: Context, tag: String, msg: String) {
        Log.d(tag, msg)
        val line = "${fmt.format(Date())} [$tag] $msg\n"
        try {
            val f = file(ctx)
            if (f.exists() && f.length() > MAX_BYTES) {
                val tail = f.readText().takeLast(MAX_BYTES / 2)
                f.writeText(tail)
            }
            f.appendText(line)
        } catch (e: Exception) {
            Log.e("AppLog", "failed to write log", e)
        }
    }

    @Synchronized
    fun readAll(ctx: Context): String =
        try {
            val f = file(ctx)
            if (f.exists()) f.readText() else "(no log yet)"
        } catch (e: Exception) {
            "error reading log: ${e.message}"
        }

    @Synchronized
    fun clear(ctx: Context) {
        try { file(ctx).writeText("") } catch (_: Exception) {}
    }
}
