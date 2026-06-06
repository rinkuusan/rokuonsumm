package com.rokuonsumm.transcription

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.rokuonsumm.App
import com.rokuonsumm.OpLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 既存の transcripts 全件を HallucinationFilter で再フィルタする Worker。
 * 設定画面の「過去の文字起こしを再フィルタ」から起動される。
 *
 * 出力データ:
 *   "deleted": 削除件数
 *   "scanned": 走査件数
 */
class RefilterWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as App
        val userPatterns = app.prefs.fillerPatternsUserFlow.first()
        val dao = app.db.transcriptDao()

        val rows = dao.getAllIdsAndTexts()
        OpLog.i(applicationContext, "refilter.start", "scanned=${rows.size}")

        var deleted = 0
        var cleaned = 0
        rows.forEach { row ->
            val scrubbed = HallucinationFilter.scrub(row.text, userPatterns)
            when {
                scrubbed == row.text -> { /* 変化なし */ }
                HallucinationFilter.meaningfulLength(scrubbed) < 2 -> {
                    dao.deleteById(row.id); deleted++           // 丸ごと幻覚/雑音 → 行削除
                }
                else -> {
                    dao.updateText(row.id, scrubbed); cleaned++ // 幻覚だけ抜いて本文は温存
                }
            }
        }
        OpLog.i(applicationContext, "refilter.done", "deleted=$deleted cleaned=$cleaned scanned=${rows.size}")

        // ── Point 5: 品質フラグ計測 (反復/非音声/低信頼) を全体+日別に集計してログ出力 ──
        val thr = TranscriptQuality.Thresholds(
            app.prefs.qNoSpeechMaxFlow.first(),
            app.prefs.qCompressionMaxFlow.first(),
            app.prefs.qAvgLogprobMinFlow.first(),
            app.prefs.qRepMinCountFlow.first()
        )
        val all = dao.getAllFull()
        val totals = TranscriptQuality.tally(all, thr)
        OpLog.i(applicationContext, "quality.total",
            "scanned=${totals.scanned} hasMeta=${totals.hasMeta} rep=${totals.repetition} " +
                "repTextOnly=${totals.repByTextOnly} nonSpeech=${totals.nonSpeech} lowConf=${totals.lowConf}")
        val dayFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        all.groupBy { dayFmt.format(java.util.Date(it.startTimeMs)) }
            .entries.sortedByDescending { it.key }
            .forEach { (day, dayRows) ->
                val t = TranscriptQuality.tally(dayRows, thr)
                if (t.repetition > 0 || t.nonSpeech > 0 || t.lowConf > 0)
                    OpLog.i(applicationContext, "quality.day",
                        "day=$day n=${t.scanned} rep=${t.repetition} nonSpeech=${t.nonSpeech} lowConf=${t.lowConf}")
            }

        Result.success(workDataOf(
            "deleted" to deleted, "cleaned" to cleaned, "scanned" to rows.size,
            "repFlagged" to totals.repetition
        ))
    }

    companion object {
        const val TAG_REFILTER = "refilter"

        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<RefilterWorker>()
                .addTag(TAG_REFILTER)
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }
    }
}
