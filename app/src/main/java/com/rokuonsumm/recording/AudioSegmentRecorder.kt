package com.rokuonsumm.recording

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * MediaRecorder の薄いラッパー。
 *
 * セグメントロール: rollTo(nextFile) で現セグメントを stop して seal し、即座に
 * 新セグメントを start する。setNextOutputFile は setMaxDuration/setMaxFileSize が
 * 未設定だと発火しないため使わない。stop→start 間の数十ms のギャップは許容。
 *
 * ハード停止: stop() は pause/resume/onDestroy で使う。
 */
class AudioSegmentRecorder(
    private val context: Context,
    private val onSegmentSealed: (File) -> Unit,
    private val onError: (Exception) -> Unit
) {
    private var recorder: MediaRecorder? = null
    private var activeFile: File? = null
    private var pendingFile: File? = null

    val isRecording: Boolean get() = recorder != null

    fun start(file: File, bitrateBps: Int) {
        check(recorder == null) { "already recording" }
        activeFile = file
        @Suppress("DEPRECATION")
        val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        mr.setAudioSource(MediaRecorder.AudioSource.MIC)
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mr.setAudioChannels(1)
        mr.setAudioSamplingRate(16_000)
        mr.setAudioEncodingBitRate(bitrateBps.coerceIn(16_000, 64_000))
        mr.setOutputFile(file.absolutePath)
        mr.setOnInfoListener { _, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED) {
                val sealed = activeFile
                activeFile = pendingFile
                pendingFile = null
                if (sealed != null && sealed.exists() && sealed.length() > 0) {
                    onSegmentSealed(sealed)
                }
            }
        }
        mr.setOnErrorListener { _, what, extra ->
            val msg = "MediaRecorder async error what=${mrErrorName(what)} extra=$extra"
            Log.e(TAG, msg)
            onError(RuntimeException(msg))
        }
        try {
            mr.prepare()
            mr.start()
            recorder = mr   // ← 成功時のみ代入
            Log.i(TAG, "started: ${file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.javaClass.simpleName}: ${e.message}")
            mr.release()
            activeFile = null
            // recorder は null のまま
            onError(e)
        }
    }

    /**
     * セグメントロール: 現セグメントを停止して seal し、即座に新セグメントを開始する。
     *
     * 注: setNextOutputFile() は setMaxDuration()/setMaxFileSize() が未設定だと
     * 絶対に発火しない(Androidの仕様)。そのため確実な stop→start でロールする。
     * stop と start の間に数十ms のギャップが生じるが、pause/resume 同様 仕様として許容。
     */
    fun rollTo(nextFile: File, bitrateBps: Int) {
        val sealed = stop()              // 現セグメントを確定 (recorder=null になる)
        start(nextFile, bitrateBps)      // 新セグメントを即開始
        sealed?.let { onSegmentSealed(it) }  // 旧セグメントを enqueue + 次ロール予約
    }

    fun stop(): File? {
        val file = activeFile
        pendingFile?.let { if (it.exists() && it.length() == 0L) it.delete() }
        try { recorder?.stop() } catch (e: Exception) {
            Log.w(TAG, "stop() threw (empty segment?): ${e.javaClass.simpleName}: ${e.message}")
        }
        recorder?.release()
        recorder = null
        activeFile = null
        pendingFile = null
        return if (file != null && file.exists() && file.length() > 0) file else null
    }

    companion object {
        private const val TAG = "AudioSegmentRecorder"

        private fun mrErrorName(what: Int) = when (what) {
            MediaRecorder.MEDIA_RECORDER_ERROR_UNKNOWN -> "ERROR_UNKNOWN"
            MediaRecorder.MEDIA_ERROR_SERVER_DIED      -> "SERVER_DIED"
            else                                       -> "what=$what"
        }
    }
}
