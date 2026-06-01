package com.rokuonsumm.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.rokuonsumm.App
import com.rokuonsumm.data.db.SummaryEntity
import com.rokuonsumm.data.db.TranscriptEntity
import com.rokuonsumm.recording.SegmentFileManager
import com.rokuonsumm.transcription.TranscriptionWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class DayRow(
    val dateKey: String,         // "2026-05-26"
    val displayDate: String,     // "5月26日(火)"
    val isToday: Boolean,
    val summaryPreview: String?, // null = まだ要約されてない
    val pendingCount: Int        // 文字起こし待ちのセグメント数
)

/** 文字起こしの進行状況 (ホーム上部チップ用) */
data class TranscriptionStatus(
    val pending: Int,    // 未文字起こしのセグメントファイル数 (= 残り)
    val running: Int,    // WorkManager で実行中
    val enqueued: Int    // WorkManager で待機中
) {
    val total get() = pending
    val isActive get() = running > 0 || enqueued > 0
    val allDone get() = pending == 0
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx = app as App
    private val db = appCtx.db
    private val prefs = appCtx.prefs
    private val fileManager = SegmentFileManager(app)
    private val refreshSignal = MutableStateFlow(0)

    val dayRows: StateFlow<List<DayRow>> = combine(
        db.transcriptDao().observeAll(),
        db.summaryDao().observeAll(),
        prefs.dayEndHourFlow,
        prefs.dayEndMinuteFlow,
        refreshSignal
    ) { transcripts, summaries, dayEndH, dayEndM, _ ->
        buildDayRows(transcripts, summaries, dayEndH, dayEndM)
    }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun refresh() { refreshSignal.value++ }

    // ── 文字起こし状況 (自動進行の可視化 + 手動キック) ────────────────

    val transcriptionStatus: StateFlow<TranscriptionStatus> = combine(
        db.transcriptDao().observeAll(),
        WorkManager.getInstance(appCtx).getWorkInfosByTagFlow(TranscriptionWorker.TAG_TRANSCRIPTION),
        refreshSignal
    ) { transcripts, workInfos, _ ->
        val transcribedPaths = transcripts.mapTo(HashSet()) { it.segmentPath }
        val pending = fileManager.allSegmentFiles().count { it.absolutePath !in transcribedPaths }
        val running = workInfos.count { it.state == WorkInfo.State.RUNNING }
        val enqueued = workInfos.count { it.state == WorkInfo.State.ENQUEUED }
        TranscriptionStatus(pending, running, enqueued)
    }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TranscriptionStatus(0, 0, 0))

    /** 未文字起こしのセグメントを全部 WorkManager に手動投入する */
    fun requeuePending() {
        viewModelScope.launch(Dispatchers.IO) {
            val transcribedPaths = db.transcriptDao().observeAll().first()
                .mapTo(HashSet()) { it.segmentPath }
            val pending = fileManager.allSegmentFiles().filter { it.absolutePath !in transcribedPaths }
            pending.forEach { TranscriptionWorker.enqueue(appCtx, it.absolutePath) }
            refreshSignal.value++
        }
    }

    private fun buildDayRows(
        transcripts: List<TranscriptEntity>,
        summaries: List<SummaryEntity>,
        dayEndH: Int,
        dayEndM: Int
    ): List<DayRow> {
        val files = fileManager.allSegmentFiles()
        val summaryByDate = summaries.associateBy { it.date }
        val todayKey = dayKeyFor(System.currentTimeMillis(), dayEndH, dayEndM)

        val nameParser = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val keyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val displayFmt = SimpleDateFormat("M月d日(E)", Locale.JAPANESE)

        // dayKey -> displayDate
        val displayByKey = mutableMapOf<String, String>()
        // dayKey -> pending count
        val pendingByKey = mutableMapOf<String, Int>()
        // dayKey -> transcript count (any transcript for this day)
        val transcriptDayKeys = mutableSetOf<String>()

        // 1. Walk files: anything without transcript adds to pending count for that day
        val transcriptPaths = transcripts.mapTo(HashSet()) { it.segmentPath }
        files.forEach { f ->
            val namePart = f.nameWithoutExtension.removePrefix("seg_")
            val parsed = try { nameParser.parse(namePart) } catch (_: Exception) { null } ?: return@forEach
            val key = dayKeyFor(parsed.time, dayEndH, dayEndM)
            displayByKey.getOrPut(key) {
                val cal = Calendar.getInstance().apply { time = parsed }
                normalizeToDayStart(cal, dayEndH, dayEndM)
                displayFmt.format(cal.time)
            }
            if (f.absolutePath !in transcriptPaths) {
                pendingByKey.merge(key, 1, Int::plus)
            }
        }

        // 2. Walk transcripts: mark dayKeys that have at least one transcript
        transcripts.forEach { t ->
            val key = dayKeyFor(t.startTimeMs, dayEndH, dayEndM)
            transcriptDayKeys += key
            displayByKey.getOrPut(key) {
                val cal = Calendar.getInstance().apply { timeInMillis = t.startTimeMs }
                normalizeToDayStart(cal, dayEndH, dayEndM)
                displayFmt.format(cal.time)
            }
        }

        // 3. Build rows for all dayKeys we know about
        val allKeys = (displayByKey.keys + summaryByDate.keys).toSortedSet(reverseOrder())
        return allKeys.map { key ->
            val summary = summaryByDate[key]?.summary
            val preview = summary?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.let { if (it.length > 80) it.take(78) + "…" else it }
            val display = displayByKey[key] ?: run {
                // summary exists but no transcripts/files: parse the key
                val parts = key.split("-").mapNotNull { it.toIntOrNull() }
                if (parts.size == 3) {
                    val cal = Calendar.getInstance().apply {
                        set(parts[0], parts[1] - 1, parts[2], 12, 0, 0)
                    }
                    displayFmt.format(cal.time)
                } else key
            }
            DayRow(
                dateKey = key,
                displayDate = display,
                isToday = key == todayKey,
                summaryPreview = preview,
                pendingCount = pendingByKey[key] ?: 0
            )
        }
    }

    companion object {
        fun dayKeyFor(timeMs: Long, dayEndH: Int, dayEndM: Int): String {
            val cal = Calendar.getInstance().apply { timeInMillis = timeMs }
            normalizeToDayStart(cal, dayEndH, dayEndM)
            return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        }

        /** Roll back to the previous day if hour/min is before the day-end cutoff. */
        private fun normalizeToDayStart(cal: Calendar, dayEndH: Int, dayEndM: Int) {
            val h = cal.get(Calendar.HOUR_OF_DAY)
            val m = cal.get(Calendar.MINUTE)
            if (h < dayEndH || (h == dayEndH && m < dayEndM)) {
                cal.add(Calendar.DAY_OF_YEAR, -1)
            }
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
        }
    }
}
