package com.rokuonsumm.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rokuonsumm.App
import com.rokuonsumm.OpLog
import com.rokuonsumm.data.db.SpeakerProfileEntity
import com.rokuonsumm.recording.SegmentFileManager
import com.rokuonsumm.transcription.PcmDecoder
import com.rokuonsumm.transcription.SileroVad
import com.rokuonsumm.transcription.SpeakerEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ProfileRow(val name: String, val sampleCount: Int, val registered: Boolean)

/** 登録処理の結果 (UIへ通知) */
sealed class EnrollResult {
    data class Success(val name: String, val addedSamples: Int, val totalSamples: Int) : EnrollResult()
    data class Failure(val reason: String) : EnrollResult()
}

class SpeakerRegistrationViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx = app as App
    private val db = appCtx.db
    private val fileManager = SegmentFileManager(app)

    /** 既定の3話者 + 登録済みのカスタム話者をマージして表示 */
    val rows: StateFlow<List<ProfileRow>> = db.speakerProfileDao().observeAll()
        .map { profiles ->
            val byName = profiles.associateBy { it.name }
            val base = DEFAULT_SPEAKERS.map {
                ProfileRow(it, byName[it]?.sampleCount ?: 0, byName.containsKey(it))
            }
            val extra = profiles.filter { it.name !in DEFAULT_SPEAKERS }
                .map { ProfileRow(it.name, it.sampleCount, true) }
            base + extra
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _enrollEvent = MutableStateFlow<EnrollResult?>(null)
    val enrollEvent: StateFlow<EnrollResult?> = _enrollEvent
    fun consumeEvent() { _enrollEvent.value = null }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** 音声ファイル(.m4a/.wav)から話者プロファイルを作る/追記する */
    fun enrollFromFile(name: String, audioFile: File) {
        if (name.isBlank()) { _enrollEvent.value = EnrollResult.Failure("名前が空です"); return }
        viewModelScope.launch {
            _busy.value = true
            val res = withContext(Dispatchers.IO) { computeAndSave(name.trim(), audioFile) }
            _enrollEvent.value = res
            _busy.value = false
        }
    }

    fun delete(name: String) {
        viewModelScope.launch(Dispatchers.IO) { db.speakerProfileDao().delete(name) }
    }

    private suspend fun computeAndSave(name: String, audioFile: File): EnrollResult {
        val pcm = PcmDecoder.decode(audioFile.absolutePath)
            ?: return EnrollResult.Failure("音声をデコードできませんでした")
        if (pcm.samples.isEmpty()) return EnrollResult.Failure("音声が空です")
        val sr = pcm.sampleRate

        val regions = try {
            SileroVad(appCtx).use { it.detectSpeechRegions(pcm.samples, sr) }
        } catch (e: Exception) {
            OpLog.e(appCtx, "enroll.vad_fail", name, e)
            return EnrollResult.Failure("音声解析に失敗しました")
        }
        val embs = ArrayList<FloatArray>()
        try {
            SpeakerEmbedder(appCtx).use { embedder ->
                for (r in regions) {
                    val len = r.endSample - r.startSample
                    if (len < sr / 2) continue        // 0.5秒未満は捨てる
                    val seg = pcm.samples.copyOfRange(r.startSample, r.endSample)
                    embedder.embed(seg, sr)?.let { embs.add(it) }
                }
            }
        } catch (e: Exception) {
            OpLog.e(appCtx, "enroll.embed_fail", name, e)
            return EnrollResult.Failure("声紋計算に失敗しました")
        }
        if (embs.isEmpty()) return EnrollResult.Failure("十分な長さの発話が見つかりません（もっと長く話してください）")

        val newAvg = SpeakerEmbedder.average(embs) ?: return EnrollResult.Failure("声紋計算に失敗しました")

        // 既存プロファイルがあれば重み付き平均で追記
        val existing = db.speakerProfileDao().getAll().firstOrNull { it.name == name }
        val (finalEmb, total) = if (existing != null) {
            val old = existing.embeddingFloats()
            val oc = existing.sampleCount
            val nc = embs.size
            val combined = FloatArray(SpeakerEmbedder.EMBED_DIM) { old[it] * oc + newAvg[it] * nc }
            (SpeakerEmbedder.average(listOf(combined))!! to oc + nc)  // average() が正規化してくれる
        } else newAvg to embs.size

        db.speakerProfileDao().upsert(
            SpeakerProfileEntity(
                name = name,
                embedding = SpeakerProfileEntity.floatsToBytes(finalEmb),
                sampleCount = total
            )
        )
        OpLog.i(appCtx, "enroll.saved", "name=$name added=${embs.size} total=$total")
        return EnrollResult.Success(name, embs.size, total)
    }

    /** 既存セグメント一覧(新しい順, 実体あるもの) */
    suspend fun recentSegments(): List<File> = withContext(Dispatchers.IO) {
        fileManager.allSegmentFiles().filter { it.length() > 100_000 }
            .sortedByDescending { it.name }.take(50)
    }

    companion object {
        val DEFAULT_SPEAKERS = listOf("純", "あやか", "おかん")
    }
}
