package com.rokuonsumm.transcription

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.rokuonsumm.R
import com.rokuonsumm.ui.SettingsActivity

/**
 * 文字起こし関連のエラー通知。同じ種類のエラーは 30分に1回しか出さない (うるさくならないため)。
 */
object TranscriptionNotifier {

    private const val CHANNEL_ID = "transcription_error"
    private const val NOTIF_ID_API_KEY = 1001
    private const val NOTIF_ID_AUTH_FAIL = 1002
    private const val NOTIF_ID_REPEATED_FAIL = 1003
    private const val PREF_NAME = "transcription_notif_state"
    private const val SUPPRESS_MS = 30 * 60 * 1000L // 30分

    fun notifyApiKeyMissing(ctx: Context) =
        notifyOnce(ctx, NOTIF_ID_API_KEY, "api_key_missing",
            title = "APIキーが未設定です",
            text = "Groq APIキーを設定すると文字起こしが始まります。タップして設定を開く。")

    fun notifyApiKeyInvalid(ctx: Context) =
        notifyOnce(ctx, NOTIF_ID_AUTH_FAIL, "api_key_invalid",
            title = "APIキーが無効です",
            text = "Groqからの認証エラー。タップしてキーを確認。")

    fun notifyRepeatedFailures(ctx: Context, count: Int) =
        notifyOnce(ctx, NOTIF_ID_REPEATED_FAIL, "repeated_fail",
            title = "文字起こしが繰り返し失敗しています",
            text = "$count 件連続で失敗。ネットワークまたはGroq APIの問題かも。")

    private fun notifyOnce(
        ctx: Context, notifId: Int, suppressKey: String,
        title: String, text: String
    ) {
        val prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val lastShown = prefs.getLong(suppressKey, 0L)
        if (System.currentTimeMillis() - lastShown < SUPPRESS_MS) return

        ensureChannel(ctx)
        val intent = Intent(ctx, SettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.notify(notifId, notif)
            prefs.edit().putLong(suppressKey, System.currentTimeMillis()).apply()
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS未許可: 無視
        }
    }

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "文字起こしエラー",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "APIキー未設定や認証エラーを通知"
            }
        )
    }

    /** 設定画面で正常状態に戻ったら抑制状態をリセット (次のエラーで即通知できるよう) */
    fun resetSuppression(ctx: Context) {
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
