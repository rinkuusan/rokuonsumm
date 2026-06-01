package com.rokuonsumm

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 詳細な操作ログ。Logcat（タグ "OpLog"）+ filesDir/op_log.txt に追記。
 * op_log.txt は adb pull で取得して解析可能。サイズは 2MB を超えたらローテート。
 *
 * 使い方: `OpLog.i(this, "tap btnMic", "state=$state")`
 */
object OpLog {
    private const val TAG = "OpLog"
    private const val MAX_SIZE = 2 * 1024 * 1024L // 2MB
    private val executor = Executors.newSingleThreadExecutor()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun i(ctx: Context, where: String, msg: String = "") {
        write(ctx.applicationContext, "I", where, msg)
        Log.i(TAG, if (msg.isEmpty()) where else "$where | $msg")
    }

    fun w(ctx: Context, where: String, msg: String = "") {
        write(ctx.applicationContext, "W", where, msg)
        Log.w(TAG, if (msg.isEmpty()) where else "$where | $msg")
    }

    fun e(ctx: Context, where: String, msg: String = "", t: Throwable? = null) {
        val full = if (t != null) "$msg\n${t.stackTraceToString()}" else msg
        write(ctx.applicationContext, "E", where, full)
        if (t != null) Log.e(TAG, "$where | $msg", t) else Log.e(TAG, "$where | $msg")
    }

    private fun write(ctx: Context, level: String, where: String, msg: String) {
        executor.execute {
            try {
                val f = File(ctx.filesDir, "op_log.txt")
                if (f.exists() && f.length() > MAX_SIZE) {
                    val old = File(ctx.filesDir, "op_log.old.txt")
                    if (old.exists()) old.delete()
                    f.renameTo(old)
                }
                val line = "${fmt.format(Date())} $level ${where.padEnd(28)} | $msg\n"
                f.appendText(line)
            } catch (_: Throwable) {
                // ログ書き込みは失敗してもユーザ影響ゼロにする
            }
        }
    }
}
