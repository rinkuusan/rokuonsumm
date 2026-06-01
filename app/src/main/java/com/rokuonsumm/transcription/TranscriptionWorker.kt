package com.rokuonsumm.transcription

import android.content.Context
import android.util.Log
import androidx.work.*
import com.rokuonsumm.App
import com.rokuonsumm.OpLog
import com.rokuonsumm.data.db.TranscriptEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class TranscriptionWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val path = inputData.getString(KEY_SEGMENT_PATH) ?: return Result.failure()
        val file = File(path)
        if (!file.exists()) {
            Log.w(TAG, "segment not found: $path")
            return Result.failure()
        }

        val app = applicationContext as App

        // 0バイト/極小ファイルはGroqが400("file is empty"/"too short")で弾く。
        // 何度送ってもムダなので削除してスキップ(retryループ防止)。
        if (file.length() < MIN_AUDIO_BYTES) {
            OpLog.w(applicationContext, "transcription.skip_tiny", "file=${file.name} size=${file.length()}")
            file.delete()
            return Result.success()
        }

        val apiKey   = app.prefs.groqApiKeyFlow.first()
        val model    = app.prefs.transcriptionModelFlow.first()
        val autoDelete = app.prefs.autoDeleteAfterTranscriptionFlow.first()
        val segMin   = app.prefs.segmentDurationMinFlow.first()

        if (apiKey.isBlank()) {
            Log.w(TAG, "Groq API key not set (attempt ${runAttemptCount + 1})")
            return if (runAttemptCount < MAX_KEY_RETRIES) {
                Result.retry()
            } else {
                // 3回retryしたら諦めて通知。永遠ループ防止。
                TranscriptionNotifier.notifyApiKeyMissing(applicationContext)
                OpLog.w(applicationContext, "transcription.api_key_missing", "file=${file.name} gave up after $runAttemptCount retries")
                Result.failure()
            }
        }

        // 重い処理(デコード/VAD/声紋/Groq)は端末を守るため必ず1件ずつ直列化する。
        // 一括再キューで何十件も並列に走るとMediaCodec枯渇+OOMで全滅するため。
        return transcribeMutex.withLock {
            runTranscription(app, file, path, apiKey, model, autoDelete, segMin)
        }
    }

    private suspend fun runTranscription(
        app: App,
        file: File,
        path: String,
        apiKey: String,
        model: String,
        autoDelete: Boolean,
        segMin: Int
    ): Result {
        // ── VAD ゲート: 声が無いセグメントは Groq に送らない + 話者識別 ──
        val vadEnabled = app.prefs.vadEnabledFlow.first()
        var sendFile = file
        var sendMime = "audio/mp4"
        var tempWav: File? = null
        var speakerLabel: String? = null
        var speakerEmbBytes: ByteArray? = null
        val speakerIdEnabled = app.prefs.speakerIdEnabledFlow.first()
        if (vadEnabled) {
            // 登録済み(named)話者のみ VadGate に渡す。自動クラスタは後段で処理。
            // 識別ON時は登録ゼロでも空リストを渡す → 声紋を取得して自動クラスタリングに回す。
            val speakers = if (speakerIdEnabled) {
                app.db.speakerProfileDao().getAll().filter { !it.isAuto }.map {
                    VadGate.SpeakerRef(it.name, it.embeddingFloats())
                }
            } else null
            val threshold = app.prefs.speakerThresholdFlow.first()

            val result = VadGate.evaluate(applicationContext, file, applicationContext.cacheDir, speakers, threshold)
            // 自動話者クラスタリング: named未マッチ&声紋ありなら 人物N に振り分け
            val emb = result.speakerEmbedding
            speakerLabel = if (speakerIdEnabled && emb != null) {
                speakerEmbBytes = com.rokuonsumm.data.db.SpeakerProfileEntity.floatsToBytes(emb)
                SpeakerClusterer.resolve(
                    applicationContext, app.db.speakerProfileDao(),
                    result.speakerLabel, emb, threshold
                )
            } else result.speakerLabel
            when (val d = result.decision) {
                is VadGate.Decision.Skip -> {
                    // 無音/環境音のみ → transcript 作らず、音声は autoDelete 通り
                    if (autoDelete) {
                        file.delete()
                        app.db.transcriptDao().markAudioDeleted(path)
                    }
                    Log.i(TAG, "VAD skip (no speech): ${file.name}")
                    return Result.success()
                }
                is VadGate.Decision.SendOriginal -> { /* file/mime そのまま */ }
                is VadGate.Decision.SendExtracted -> {
                    sendFile = d.wav
                    sendMime = "audio/wav"
                    tempWav = d.wav
                }
            }
        }

        // 固有名詞 prompt (改行 → 読点区切りに正規化)
        val prompt = app.prefs.properNounsFlow.first()
            .split("\n", "、", ",").map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString("、")

        return try {
            val (text, startMs, endMs) = callGroqApi(sendFile, sendMime, file, apiKey, model, segMin, prompt)
            tempWav?.delete()  // 送信済みの一時WAVを掃除

            // フィラー幻覚チェック (DB保存前)
            val filterEnabled = app.prefs.fillerFilterEnabledFlow.first()
            val userPatterns = app.prefs.fillerPatternsUserFlow.first()
            val filterResult = if (filterEnabled) {
                HallucinationFilter.isHallucination(text, userPatterns)
            } else null

            if (filterResult?.isJunk == true) {
                OpLog.i(applicationContext, "transcription.filtered",
                    "file=${file.name} reason=${filterResult.reason} pat=${filterResult.matchedPattern} text=${text.take(60)}")
                // 音声ファイルは autoDelete 設定通り処理
                if (autoDelete) {
                    file.delete()
                    Log.i(TAG, "junk-filtered and deleted: ${file.name}")
                } else {
                    Log.i(TAG, "junk-filtered (kept audio): ${file.name}")
                }
                return Result.success()
            }

            // 実会話に混ざった幻覚フレーズはその場で除去して保存(本文は温存)
            val cleanText = if (filterEnabled) HallucinationFilter.scrub(text, userPatterns) else text

            app.db.transcriptDao().insert(
                TranscriptEntity(
                    segmentPath = path,
                    startTimeMs = startMs,
                    endTimeMs   = endMs,
                    text        = cleanText,
                    speakerLabel = speakerLabel,
                    speakerEmbedding = speakerEmbBytes
                )
            )
            OpLog.i(applicationContext, "transcription.saved", "file=${file.name} len=${text.length} spk=$speakerLabel")
            // 自動クラスタ(人物N)の音声は「後で耳で確認して命名する」ため残す。
            // 既知話者/不明(一見さん)は autoDelete 設定通り。
            val keepForNaming = speakerLabel?.startsWith("人物") == true
            if (autoDelete && !keepForNaming) {
                file.delete()
                app.db.transcriptDao().markAudioDeleted(path)
                Log.i(TAG, "transcribed and deleted: ${file.name}")
            } else {
                Log.i(TAG, "transcribed (kept audio${if (keepForNaming) ", for naming" else ""}): ${file.name}")
            }
            Result.success()
        } catch (e: Exception) {
            tempWav?.delete()
            OpLog.e(applicationContext, "transcription.failed", "file=${file.name} msg=${e.message}", e)
            Log.e(TAG, "transcription failed (attempt ${runAttemptCount + 1})", e)
            // 401/403は認証失敗。retryせず即failure+通知
            val msg = e.message.orEmpty()
            if (msg.contains("401") || msg.contains("403") || msg.contains("invalid_api_key")) {
                TranscriptionNotifier.notifyApiKeyInvalid(applicationContext)
                return Result.failure()
            }
            // 短すぎ/空/大きすぎファイルは何度送ってもムダ。削除してスキップ(retryループ防止)
            if (msg.contains("too short") || msg.contains("file is empty") ||
                msg.contains("request_too_large") || msg.contains("413")) {
                file.delete()
                OpLog.w(applicationContext, "transcription.skip_bad", "file=${file.name} ${msg.take(50)}")
                return Result.success()
            }
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    /**
     * @param sendFile 実際にGroqへ送るファイル (原本m4a or 抽出WAV)
     * @param sendMime 送信ファイルのMIME (audio/mp4 or audio/wav)
     * @param timeRefFile 開始時刻の基準ファイル名 (常に元の seg_*.m4a)
     * @param prompt 固有名詞などの認識バイアス
     */
    private suspend fun callGroqApi(
        sendFile: File,
        sendMime: String,
        timeRefFile: File,
        apiKey: String,
        model: String,
        segMin: Int,
        prompt: String
    ): Triple<String, Long, Long> = withContext(Dispatchers.IO) {
        val startMs = parseSegmentStartMs(timeRefFile)
        val endMs   = startMs + segMin * 60_000L

        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", sendFile.name, sendFile.asRequestBody(sendMime.toMediaType()))
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("language", "ja")
            .addFormDataPart("temperature", "0")  // 決定論的デコード (VADで無音除去済みなので幻覚ループ無し)
        if (prompt.isNotBlank()) builder.addFormDataPart("prompt", prompt)

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(builder.build())
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "(no body)"
            throw IOException("Groq API ${response.code}: $errorBody")
        }

        val text = JSONObject(response.body!!.string()).getString("text")
        Triple(text, startMs, endMs)
    }

    private fun parseSegmentStartMs(file: File): Long {
        val namePart = file.nameWithoutExtension.removePrefix("seg_")
        return try {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).parse(namePart)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    companion object {
        private const val KEY_SEGMENT_PATH = "segment_path"
        private const val MAX_RETRIES      = 3
        private const val MAX_KEY_RETRIES  = 2  // APIキー未設定時の追加猶予
        private const val MIN_AUDIO_BYTES  = 2_000L  // これ未満は実音声なしとみなしスキップ
        private const val TAG              = "TranscriptionWorker"
        const val TAG_TRANSCRIPTION        = "transcription"  // WorkManager タグ (進捗監視用)

        /** 重い文字起こし処理(デコード/VAD/声紋/Groq)を端末全体で1件ずつに直列化するロック */
        private val transcribeMutex = Mutex()

        private val httpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build()
        }

        fun enqueue(context: Context, segmentPath: String) {
            // ネットワーク制約は付けない: API呼び出し時に IOException → retry でリカバリ。
            // CONNECTED制約を付けると Wi-Fi/モバイル切断時に永遠 ENQUEUED 状態のまま待機してしまう。
            val req = OneTimeWorkRequestBuilder<TranscriptionWorker>()
                .setInputData(workDataOf(KEY_SEGMENT_PATH to segmentPath))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG_TRANSCRIPTION)
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }
    }
}
