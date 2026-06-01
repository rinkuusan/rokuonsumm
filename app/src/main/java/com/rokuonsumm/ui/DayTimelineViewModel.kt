package com.rokuonsumm.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rokuonsumm.App
import com.rokuonsumm.OpLog
import com.rokuonsumm.data.db.SummaryEntity
import com.rokuonsumm.recording.SegmentFileManager
import com.rokuonsumm.transcription.Summarizer
import com.rokuonsumm.transcription.SummaryProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

sealed class TimelineUiItem {
    data class SummaryCard(val summary: String) : TimelineUiItem()
    data class NoSummary(val isLoading: Boolean = false) : TimelineUiItem()
    data class TimeHeader(val timeText: String) : TimelineUiItem()
    data class Paragraph(
        val transcriptId: Long,
        val timeText: String,
        val text: String,
        val speakerLabel: String? = null
    ) : TimelineUiItem()
    data class PendingBlock(val timeRangeText: String, val count: Int) : TimelineUiItem()
    object EmptyDay : TimelineUiItem()
}

sealed class SummarizeState {
    object Idle : SummarizeState()
    object Loading : SummarizeState()
    data class Error(val message: String) : SummarizeState()
}

data class TimelineData(
    val displayDate: String,
    val items: List<TimelineUiItem>
)

class DayTimelineViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx = app as App
    private val db = appCtx.db
    private val prefs = appCtx.prefs
    private val fileManager = SegmentFileManager(app)

    private val _dateKey = MutableStateFlow<String?>(null)
    private val _summarizeState = MutableStateFlow<SummarizeState>(SummarizeState.Idle)
    val summarizeState: StateFlow<SummarizeState> = _summarizeState

    /** null = 全話者表示。それ以外はその話者ラベルのみ表示 */
    private val _speakerFilter = MutableStateFlow<String?>(null)
    val speakerFilter: StateFlow<String?> = _speakerFilter

    fun setDateKey(dateKey: String) { _dateKey.value = dateKey }
    fun setSpeakerFilter(label: String?) { _speakerFilter.value = label }

    private val rawTimeline = combine(
        _dateKey,
        db.transcriptDao().observeAll(),
        db.summaryDao().observeAll(),
        prefs.dayEndHourFlow,
        prefs.dayEndMinuteFlow
    ) { dateKey, transcripts, summaries, dayEndH, dayEndM ->
        if (dateKey == null) null else buildTimeline(dateKey, transcripts.toList(), summaries.toList(), dayEndH, dayEndM)
    }

    /** その日のタイムラインに登場する話者ラベル一覧（フィルタUI用） */
    val availableSpeakers: StateFlow<List<String>> = rawTimeline.map { data ->
        data?.items.orEmpty()
            .filterIsInstance<TimelineUiItem.Paragraph>()
            .mapNotNull { it.speakerLabel }
            .distinct()
    }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** ローディング状態を NoSummary アイテムに射影 + 話者フィルタ適用 */
    val timeline: StateFlow<TimelineData?> = combine(
        rawTimeline,
        _summarizeState,
        _speakerFilter
    ) { data, state, filter ->
        if (data == null) return@combine null
        val withLoading = if (state !is SummarizeState.Loading) data
            else data.copy(items = data.items.map {
                if (it is TimelineUiItem.NoSummary) it.copy(isLoading = true) else it
            })
        if (filter == null) withLoading
        else withLoading.copy(items = applySpeakerFilter(withLoading.items, filter))
    }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 指定話者の Paragraph だけ残し、孤立した TimeHeader を除去する */
    private fun applySpeakerFilter(items: List<TimelineUiItem>, label: String): List<TimelineUiItem> {
        val kept = items.filter {
            it !is TimelineUiItem.Paragraph || it.speakerLabel == label
        }
        // 直後に Paragraph が無い TimeHeader（孤立ヘッダ）を落とす
        val result = mutableListOf<TimelineUiItem>()
        kept.forEachIndexed { i, item ->
            if (item is TimelineUiItem.TimeHeader) {
                val hasFollowingParagraph = kept.drop(i + 1)
                    .takeWhile { it !is TimelineUiItem.TimeHeader }
                    .any { it is TimelineUiItem.Paragraph }
                if (hasFollowingParagraph) result += item
            } else {
                result += item
            }
        }
        return result
    }

    fun summarize() {
        val dateKey = _dateKey.value ?: return
        if (_summarizeState.value is SummarizeState.Loading) return

        viewModelScope.launch {
            _summarizeState.value = SummarizeState.Loading
            OpLog.i(appCtx, "summarize.start", "date=$dateKey")
            try {
                val dayEndH = prefs.dayEndHourFlow.first()
                val dayEndM = prefs.dayEndMinuteFlow.first()
                val keyParts = dateKey.split("-").mapNotNull { it.toIntOrNull() }
                if (keyParts.size != 3) {
                    _summarizeState.value = SummarizeState.Error("日付の解析に失敗")
                    return@launch
                }
                val cal = Calendar.getInstance().apply {
                    set(keyParts[0], keyParts[1] - 1, keyParts[2], dayEndH, dayEndM, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val fromMs = cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, 1)
                val toMs = cal.timeInMillis

                val dayTranscripts = excludeUnknownIfEnabled(db.transcriptDao().getForDateRange(fromMs, toMs))
                OpLog.i(appCtx, "summarize.fetched", "n=${dayTranscripts.size}")
                if (dayTranscripts.isEmpty()) {
                    _summarizeState.value = SummarizeState.Error("文字起こしがまだありません")
                    return@launch
                }

                val provider = SummaryProvider.fromKey(prefs.summaryProviderFlow.first())
                if (provider == SummaryProvider.NONE) {
                    _summarizeState.value = SummarizeState.Error(
                        "要約エンジンは「使わない」設定や。メニューの「用途別にコピー」でLLMに貼って要約してな"
                    )
                    return@launch
                }
                val summaryKey = prefs.summaryApiKeyFlow.first()
                val groqKey = prefs.groqApiKeyFlow.first()
                // Groq は要約専用キー未設定なら文字起こし用 Groq キーを fallback
                val apiKey = when {
                    summaryKey.isNotBlank() -> summaryKey
                    provider == SummaryProvider.GROQ && groqKey.isNotBlank() -> groqKey
                    else -> ""
                }
                if (apiKey.isBlank()) {
                    _summarizeState.value = SummarizeState.Error(
                        "${provider.displayName} の API キーが未設定です（設定から入力）"
                    )
                    return@launch
                }

                val summary = Summarizer.summarize(dayTranscripts, provider, apiKey)
                OpLog.i(appCtx, "summarize.api_ok", "len=${summary.length}")
                db.summaryDao().insert(
                    SummaryEntity(
                        date = dateKey,
                        summary = summary,
                        highlightsJson = "[]",
                        todosJson = "[]"
                    )
                )
                OpLog.i(appCtx, "summarize.saved", "date=$dateKey")
                _summarizeState.value = SummarizeState.Idle
            } catch (e: Exception) {
                OpLog.e(appCtx, "summarize.failed", e.message ?: "", e)
                _summarizeState.value = SummarizeState.Error(e.message ?: "要約に失敗しました")
            }
        }
    }

    fun clearError() {
        if (_summarizeState.value is SummarizeState.Error) {
            _summarizeState.value = SummarizeState.Idle
        }
    }

    /**
     * 当日のトランスクリプトを `[H:mm] 本文` 形式で結合して返す。
     * 「全文コピー」用 - API使わず別AIに貼り付けるテキスト。
     */
    suspend fun buildFullTranscriptText(): String? {
        val dateKey = _dateKey.value ?: return null
        val dayEndH = prefs.dayEndHourFlow.first()
        val dayEndM = prefs.dayEndMinuteFlow.first()
        val keyParts = dateKey.split("-").mapNotNull { it.toIntOrNull() }
        if (keyParts.size != 3) return null
        val cal = Calendar.getInstance().apply {
            set(keyParts[0], keyParts[1] - 1, keyParts[2], dayEndH, dayEndM, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val fromMs = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val toMs = cal.timeInMillis
        val transcripts = excludeUnknownIfEnabled(db.transcriptDao().getForDateRange(fromMs, toMs))
        if (transcripts.isEmpty()) return null
        return Summarizer.buildRecords(transcripts)
    }

    /** 設定で「不明話者を除外」がONなら、speakerLabel=="不明" を要約入力から落とす */
    private suspend fun excludeUnknownIfEnabled(
        transcripts: List<com.rokuonsumm.data.db.TranscriptEntity>
    ): List<com.rokuonsumm.data.db.TranscriptEntity> {
        return if (prefs.summaryExcludeUnknownFlow.first())
            transcripts.filter { it.speakerLabel != com.rokuonsumm.transcription.VadGate.UNKNOWN }
        else transcripts
    }

    private fun buildTimeline(
        dateKey: String,
        transcripts: List<com.rokuonsumm.data.db.TranscriptEntity>,
        summaries: List<com.rokuonsumm.data.db.SummaryEntity>,
        dayEndH: Int,
        dayEndM: Int
    ): TimelineData {
        val keyParts = dateKey.split("-").mapNotNull { it.toIntOrNull() }
        val (dayStart, dayEnd) = if (keyParts.size == 3) {
            val cal = Calendar.getInstance().apply {
                set(keyParts[0], keyParts[1] - 1, keyParts[2], dayEndH, dayEndM, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val start = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
            start to cal.timeInMillis
        } else {
            0L to Long.MAX_VALUE
        }

        val displayFmt = SimpleDateFormat("yyyy年M月d日(E)", Locale.JAPANESE)
        val displayDate = if (keyParts.size == 3) {
            val cal = Calendar.getInstance().apply { set(keyParts[0], keyParts[1] - 1, keyParts[2], 12, 0, 0) }
            displayFmt.format(cal.time)
        } else dateKey

        // Filter to this day's transcripts
        val dayTranscripts = transcripts
            .filter { it.startTimeMs in dayStart until dayEnd }
            .filter { it.text.trim().isNotEmpty() }
            .sortedBy { it.startTimeMs }

        // Find pending files (segments without transcripts) for this day
        val transcriptPaths = transcripts.mapTo(HashSet()) { it.segmentPath }
        val nameParser = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val pendingFiles = fileManager.allSegmentFiles()
            .mapNotNull { f ->
                val namePart = f.nameWithoutExtension.removePrefix("seg_")
                val parsed = try { nameParser.parse(namePart) } catch (_: Exception) { null }
                    ?: return@mapNotNull null
                val startMs = parsed.time
                if (startMs !in dayStart until dayEnd) return@mapNotNull null
                if (f.absolutePath in transcriptPaths) return@mapNotNull null
                Triple(f.absolutePath, startMs, startMs + SEGMENT_APPROX_MS)
            }
            .sortedBy { it.second }

        // Build combined timeline: each item has a start time, either Paragraph or PendingFile
        data class Entry(val startMs: Long, val endMs: Long, val transcriptId: Long?, val text: String?, val speaker: String?)
        val entries = mutableListOf<Entry>()
        dayTranscripts.forEach {
            entries += Entry(it.startTimeMs, it.endTimeMs, it.id, it.text.trim(), it.speakerLabel)
        }
        pendingFiles.forEach {
            entries += Entry(it.second, it.third, null, null, null)
        }
        entries.sortBy { it.startMs }

        val items = mutableListOf<TimelineUiItem>()
        val summaryEntry = summaries.firstOrNull { it.date == dateKey }
        if (summaryEntry != null && summaryEntry.summary.isNotBlank()) {
            items += TimelineUiItem.SummaryCard(summaryEntry.summary.trim())
        } else if (entries.isNotEmpty()) {
            items += TimelineUiItem.NoSummary()
        }

        if (entries.isEmpty()) {
            if (summaryEntry == null) items += TimelineUiItem.EmptyDay
            return TimelineData(displayDate, items)
        }

        val timeFmt = SimpleDateFormat("H:mm", Locale.getDefault())
        var prevEndMs = -1L
        // Pending merge state
        var pendingStart = -1L
        var pendingEnd = -1L
        var pendingCount = 0

        fun flushPending() {
            if (pendingCount > 0) {
                val range = if (pendingEnd - pendingStart < 60_000)
                    timeFmt.format(java.util.Date(pendingStart))
                else
                    "${timeFmt.format(java.util.Date(pendingStart))}〜${timeFmt.format(java.util.Date(pendingEnd))}"
                items += TimelineUiItem.PendingBlock(range, pendingCount)
                pendingStart = -1L; pendingEnd = -1L; pendingCount = 0
            }
        }

        entries.forEach { e ->
            val isNewSection = prevEndMs < 0 || (e.startMs - prevEndMs) > SESSION_GAP_MS
            if (isNewSection) {
                flushPending()
                items += TimelineUiItem.TimeHeader(timeFmt.format(java.util.Date(e.startMs)))
            }
            if (e.transcriptId != null && e.text != null) {
                flushPending()
                items += TimelineUiItem.Paragraph(
                    transcriptId = e.transcriptId,
                    timeText = timeFmt.format(java.util.Date(e.startMs)),
                    text = e.text,
                    speakerLabel = e.speaker
                )
            } else {
                // pending: extend or start
                if (pendingCount == 0) pendingStart = e.startMs
                pendingEnd = e.endMs
                pendingCount++
            }
            prevEndMs = e.endMs
        }
        flushPending()

        return TimelineData(displayDate, items)
    }

    companion object {
        private const val SESSION_GAP_MS = 10 * 60_000L
        private const val SEGMENT_APPROX_MS = 5 * 60_000L
    }
}
