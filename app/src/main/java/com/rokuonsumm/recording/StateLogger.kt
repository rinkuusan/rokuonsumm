package com.rokuonsumm.recording

import android.content.Context
import java.io.File

/**
 * 状態遷移を state_log.csv に追記する。
 * 検証スクリプトが pause 区間の除外に使う。
 *
 * フォーマット (CSV, append):
 *   epoch_ms,EVENT[,arg1][,arg2]
 *
 * イベント一覧:
 *   SERVICE_START
 *   SEGMENT_START,  filename
 *   SEGMENT_SEALED, filename, bytes     ← gapless ロール完了
 *   SEGMENT_STOP,   filename, bytes     ← ハード停止 (pause/onDestroy)
 *   PAUSED,         reason              ← MIC_STOLEN | MEDIA_PLAYING
 *   RESUMED                             ← 500ms 待機後、実際に start() する直前
 *   SERVICE_STOP                        ← 通常停止
 *   SERVICE_DESTROY                     ← onDestroy
 *
 * ファイルは都度 open/close するためクラッシュ耐性あり。
 * メインスレッドのみから呼ばれる前提（RecordingService）。
 */
class StateLogger(context: Context) {

    val logFile = File(context.filesDir, "state_log.csv")

    fun log(event: String, vararg args: String) {
        val line = buildString {
            append(System.currentTimeMillis())
            append(',')
            append(event)
            for (a in args) { append(','); append(a) }
            append('\n')
        }
        logFile.appendText(line)
    }
}
