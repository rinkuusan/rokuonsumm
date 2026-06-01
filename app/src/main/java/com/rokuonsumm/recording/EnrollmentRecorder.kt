package com.rokuonsumm.recording

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * 話者登録用の一発録音。本録音と同じ 16kHz mono AAC/m4a。
 * 録音中は RecordingService がマイク奪取を検知して自動 pause → 停止後に自動 resume する。
 */
class EnrollmentRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    var outputFile: File? = null
        private set

    val isRecording: Boolean get() = recorder != null

    fun start(): Boolean {
        if (recorder != null) return false
        val f = File(context.cacheDir, "enroll_${System.nanoTime()}.m4a")
        @Suppress("DEPRECATION")
        val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        return try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioChannels(1)
            mr.setAudioSamplingRate(16_000)
            mr.setAudioEncodingBitRate(32_000)
            mr.setOutputFile(f.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            outputFile = f
            true
        } catch (e: Exception) {
            try { mr.release() } catch (_: Exception) {}
            recorder = null
            false
        }
    }

    /** @return 録音ファイル (失敗時 null) */
    fun stop(): File? {
        val f = outputFile
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        return if (f != null && f.exists() && f.length() > 0) f else null
    }
}
