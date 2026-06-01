package com.rokuonsumm.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rokuonsumm.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 検索結果の1件 */
data class SearchResult(
    val isSummary: Boolean,
    val transcriptId: Long,        // transcript の時のみ有効
    val dateKey: String,           // summary の遷移 + 表示用 ("yyyy-MM-dd")
    val displayDateTime: String,   // "5月26日(火) 14:03" / "5月26日(火) 要約"
    val fullText: String,          // 元テキスト
    val matchStart: Int,           // スニペット内のハイライト開始 (snippet 基準)
    val matchEnd: Int,
    val snippet: String,           // 表示用スニペット
    val sortMs: Long
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val db = (app as App).db
    private val query = MutableStateFlow("")

    fun setQuery(q: String) { query.value = q }

    val results: StateFlow<List<SearchResult>> = query
        .debounce(300)
        .flatMapLatest { q -> flow { emit(runSearch(q.trim())) } }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private suspend fun runSearch(q: String): List<SearchResult> {
        if (q.isEmpty()) return emptyList()
        val dtFmt = SimpleDateFormat("M月d日(E) H:mm", Locale.JAPANESE)
        val dayFmt = SimpleDateFormat("M月d日(E)", Locale.JAPANESE)
        val keyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        val out = ArrayList<SearchResult>()

        db.transcriptDao().search(q).forEach { t ->
            val (snippet, ms, me) = makeSnippet(t.text, q)
            out.add(
                SearchResult(
                    isSummary = false,
                    transcriptId = t.id,
                    dateKey = keyFmt.format(Date(t.startTimeMs)),
                    displayDateTime = dtFmt.format(Date(t.startTimeMs)),
                    fullText = t.text,
                    matchStart = ms, matchEnd = me,
                    snippet = snippet,
                    sortMs = t.startTimeMs
                )
            )
        }
        db.summaryDao().search(q).forEach { s ->
            val (snippet, ms, me) = makeSnippet(s.summary, q)
            // date ("yyyy-MM-dd") → 表示用 + ソート用ms
            val sortMs = runCatching { keyFmt.parse(s.date)?.time ?: 0L }.getOrDefault(0L)
            val disp = runCatching { dayFmt.format(keyFmt.parse(s.date)!!) }.getOrDefault(s.date)
            out.add(
                SearchResult(
                    isSummary = true,
                    transcriptId = -1,
                    dateKey = s.date,
                    displayDateTime = "$disp 要約",
                    fullText = s.summary,
                    matchStart = ms, matchEnd = me,
                    snippet = snippet,
                    sortMs = sortMs
                )
            )
        }
        return out.sortedByDescending { it.sortMs }
    }

    /**
     * マッチ箇所周辺のスニペットを作る。
     * @return Triple(snippet, ハイライト開始, ハイライト終了) — 後者2つは snippet 内のindex。
     */
    private fun makeSnippet(text: String, q: String): Triple<String, Int, Int> {
        val idx = text.indexOf(q, ignoreCase = true)
        if (idx < 0) return Triple(text.take(80), 0, 0)
        val before = 16
        val start = (idx - before).coerceAtLeast(0)
        val end = (idx + q.length + 50).coerceAtMost(text.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        val snippet = prefix + text.substring(start, end) + suffix
        val hlStart = prefix.length + (idx - start)
        val hlEnd = hlStart + q.length
        return Triple(snippet, hlStart, hlEnd)
    }
}
