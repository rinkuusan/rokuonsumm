package com.rokuonsumm.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rokuonsumm.App
import com.rokuonsumm.data.db.TranscriptEntity
import com.rokuonsumm.recording.SegmentFileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale


enum class TranscriptStatus { PENDING, DONE }

data class SegmentUiItem(
    val filePath: String,
    val displayTime: String,
    val dateKey: String,
    val startMs: Long,
    val endMs: Long,
    val sizeBytes: Long,
    val status: TranscriptStatus
)

sealed class ListItem {
    data class Header(
        val date: String,
        val dayStartMs: Long,
        val dayEndMs: Long,
        val segments: List<Pair<Long, Long>>
    ) : ListItem()
    data class Segment(val item: SegmentUiItem) : ListItem()
}

class AudioListViewModel(app: Application) : AndroidViewModel(app) {

    private val fileManager = SegmentFileManager(app)
    private val db = (app as App).db

    private val refreshSignal = MutableStateFlow(0)

    val listItems: StateFlow<List<ListItem>> = combine(
        db.transcriptDao().observeAll(),
        refreshSignal
    ) { transcripts, _ ->
        buildListItems(transcripts)
    }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedPaths = MutableStateFlow<Set<String>>(emptySet())
    val selectedPaths: StateFlow<Set<String>> = _selectedPaths

    fun toggleSelection(filePath: String) {
        _selectedPaths.update { if (filePath in it) it - filePath else it + filePath }
    }

    fun clearSelection() {
        _selectedPaths.value = emptySet()
    }

    fun deleteFile(filePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.transcriptDao().markAudioDeleted(filePath)
            File(filePath).delete()
            refreshSignal.value++
        }
    }

    fun deleteSelected() {
        val toDelete = _selectedPaths.value.toList()
        viewModelScope.launch(Dispatchers.IO) {
            toDelete.forEach { path ->
                db.transcriptDao().markAudioDeleted(path)
                File(path).delete()
            }
            _selectedPaths.value = emptySet()
            refreshSignal.value++
        }
    }

    private fun buildListItems(transcripts: List<TranscriptEntity>): List<ListItem> = try {
        buildListItemsInternal(transcripts)
    } catch (e: Exception) {
        emptyList()
    }

    private fun buildListItemsInternal(transcripts: List<TranscriptEntity>): List<ListItem> {
        val files = fileManager.allSegmentFiles()
        if (files.isEmpty()) return emptyList()

        val transcriptsByPath = transcripts.associateBy { it.segmentPath }
        val fmtParse = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val fmtDate  = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
        val fmtTime  = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val now = System.currentTimeMillis()

        val items = files.mapNotNull { file ->
            val namePart = file.nameWithoutExtension.removePrefix("seg_")
            val date = try { fmtParse.parse(namePart) } catch (e: Exception) { return@mapNotNull null }
            val transcript = transcriptsByPath[file.absolutePath]
            val startMs = date.time
            // use lastModified as end; fall back to startMs + 5min if it looks wrong
            val endMs = file.lastModified().let { lm ->
                if (lm > startMs) lm else startMs + SEGMENT_APPROX_MS
            }
            SegmentUiItem(
                filePath    = file.absolutePath,
                displayTime = fmtTime.format(date),
                dateKey     = fmtDate.format(date),
                startMs     = startMs,
                endMs       = endMs,
                sizeBytes   = file.length(),
                status      = if (transcript != null) TranscriptStatus.DONE else TranscriptStatus.PENDING
            )
        }

        val cal = Calendar.getInstance()
        val result = mutableListOf<ListItem>()
        items.groupBy { it.dateKey }
            .entries.sortedByDescending { it.key }
            .forEach { (dateKey, group) ->
                cal.timeInMillis = group.first().startMs
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val dayStart = cal.timeInMillis
                val dayEnd = minOf(dayStart + 86_400_000L, now)
                val segPairs = group.map { Pair(it.startMs, it.endMs) }
                result.add(ListItem.Header(dateKey, dayStart, dayEnd, segPairs))
                group.sortedByDescending { it.startMs }.forEach { result.add(ListItem.Segment(it)) }
            }
        return result
    }

    companion object {
        private const val SEGMENT_APPROX_MS = 5 * 60_000L
    }
}
