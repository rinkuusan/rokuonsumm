package com.rokuonsumm

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.rokuonsumm.data.db.AppDatabase
import com.rokuonsumm.data.prefs.AppPreferences

class App : Application() {

    val db by lazy { AppDatabase.getInstance(this) }
    val prefs by lazy { AppPreferences(this) }

    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val logFile = java.io.File(filesDir, "crash_log.txt")
                val entry = buildString {
                    append("=== CRASH ${java.util.Date()} thread=${thread.name} ===\n")
                    append(throwable.stackTraceToString())
                    append("\n")
                }
                logFile.appendText(entry)
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RECORDING,
                getString(R.string.channel_recording_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.channel_recording_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                getString(R.string.channel_alerts_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = getString(R.string.channel_alerts_desc) }
        )
    }

    companion object {
        const val CHANNEL_RECORDING = "ch_recording"
        const val CHANNEL_ALERTS = "ch_alerts"
    }
}
