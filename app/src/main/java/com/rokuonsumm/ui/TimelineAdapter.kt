package com.rokuonsumm.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.rokuonsumm.R
import com.rokuonsumm.transcription.VadGate

/** 話者ラベル → (背景色, 文字色)。タイムラインバッジとフィルタチップで共用 */
object SpeakerBadge {
    private val PALETTE = intArrayOf(
        0xFF4CAF50.toInt(), // green
        0xFFEC407A.toInt(), // pink
        0xFFFFB300.toInt(), // amber
        0xFF42A5F5.toInt(), // blue
        0xFFAB47BC.toInt(), // purple
        0xFF26C6DA.toInt()  // cyan
    )
    fun colors(label: String): Pair<Int, Int> {
        if (label == VadGate.UNKNOWN) return 0xFF4B5563.toInt() to 0xFFD1D5DB.toInt()
        val idx = (label.hashCode() and 0x7FFFFFFF) % PALETTE.size
        return PALETTE[idx] to 0xFF0A0A0A.toInt()
    }
}

class TimelineAdapter(
    private val onParagraphClick: (Long) -> Unit,
    private val onSummarizeClick: () -> Unit
) : ListAdapter<TimelineUiItem, RecyclerView.ViewHolder>(Diff) {

    object Diff : DiffUtil.ItemCallback<TimelineUiItem>() {
        override fun areItemsTheSame(a: TimelineUiItem, b: TimelineUiItem): Boolean {
            return when {
                a is TimelineUiItem.Paragraph && b is TimelineUiItem.Paragraph -> a.transcriptId == b.transcriptId
                a is TimelineUiItem.TimeHeader && b is TimelineUiItem.TimeHeader -> a.timeText == b.timeText
                a is TimelineUiItem.PendingBlock && b is TimelineUiItem.PendingBlock -> a.timeRangeText == b.timeRangeText
                a is TimelineUiItem.SummaryCard && b is TimelineUiItem.SummaryCard -> true
                a is TimelineUiItem.NoSummary && b is TimelineUiItem.NoSummary -> true
                a is TimelineUiItem.EmptyDay && b is TimelineUiItem.EmptyDay -> true
                else -> false
            }
        }
        override fun areContentsTheSame(a: TimelineUiItem, b: TimelineUiItem) = a == b
    }

    companion object {
        private const val TYPE_SUMMARY = 0
        private const val TYPE_NO_SUMMARY = 1
        private const val TYPE_TIME_HEADER = 2
        private const val TYPE_PARAGRAPH = 3
        private const val TYPE_PENDING = 4
        private const val TYPE_EMPTY = 5
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is TimelineUiItem.SummaryCard -> TYPE_SUMMARY
        is TimelineUiItem.NoSummary -> TYPE_NO_SUMMARY
        is TimelineUiItem.TimeHeader -> TYPE_TIME_HEADER
        is TimelineUiItem.Paragraph -> TYPE_PARAGRAPH
        is TimelineUiItem.PendingBlock -> TYPE_PENDING
        is TimelineUiItem.EmptyDay -> TYPE_EMPTY
    }

    class SummaryVH(v: View) : RecyclerView.ViewHolder(v) {
        val tvSummary: TextView = v.findViewById(R.id.tvSummary)
    }
    class NoSummaryVH(v: View) : RecyclerView.ViewHolder(v) {
        val btn: MaterialButton = v.findViewById(R.id.btnSummarize)
        val label: TextView = v.findViewById(R.id.tvNoSummaryLabel)
        val progress: CircularProgressIndicator = v.findViewById(R.id.pbSummarizing)
    }
    class TimeHeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val tvTime: TextView = v.findViewById(R.id.tvTime)
    }
    class ParagraphVH(v: View) : RecyclerView.ViewHolder(v) {
        val tvTime: TextView = v.findViewById(R.id.tvParaTime)
        val tvText: TextView = v.findViewById(R.id.tvParaText)
        val tvSpeaker: TextView = v.findViewById(R.id.tvParaSpeaker)
    }
    class PendingVH(v: View) : RecyclerView.ViewHolder(v) {
        val tvRange: TextView = v.findViewById(R.id.tvPendingRange)
        val tvLabel: TextView = v.findViewById(R.id.tvPendingLabel)
    }
    class EmptyVH(v: View) : RecyclerView.ViewHolder(v)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SUMMARY -> SummaryVH(inflater.inflate(R.layout.item_timeline_summary, parent, false))
            TYPE_NO_SUMMARY -> NoSummaryVH(inflater.inflate(R.layout.item_timeline_no_summary, parent, false))
            TYPE_TIME_HEADER -> TimeHeaderVH(inflater.inflate(R.layout.item_timeline_time_header, parent, false))
            TYPE_PARAGRAPH -> ParagraphVH(inflater.inflate(R.layout.item_timeline_paragraph, parent, false))
            TYPE_PENDING -> PendingVH(inflater.inflate(R.layout.item_timeline_pending, parent, false))
            TYPE_EMPTY -> EmptyVH(inflater.inflate(R.layout.item_timeline_empty, parent, false))
            else -> error("Unknown viewType $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is TimelineUiItem.SummaryCard -> {
                (holder as SummaryVH).tvSummary.text = item.summary
            }
            is TimelineUiItem.NoSummary -> {
                holder as NoSummaryVH
                if (item.isLoading) {
                    holder.label.text = "要約を生成中…"
                    holder.progress.visibility = View.VISIBLE
                    holder.btn.isEnabled = false
                    holder.btn.alpha = 0.4f
                } else {
                    holder.label.text = "要約はまだ生成されていません"
                    holder.progress.visibility = View.GONE
                    holder.btn.isEnabled = true
                    holder.btn.alpha = 1f
                }
                holder.btn.setOnClickListener { if (!item.isLoading) onSummarizeClick() }
            }
            is TimelineUiItem.TimeHeader -> {
                (holder as TimeHeaderVH).tvTime.text = item.timeText
            }
            is TimelineUiItem.Paragraph -> {
                holder as ParagraphVH
                // 品質ゲートで要確認の行は時刻に ⚠ を付けてオレンジ表示(本文は残す=非破壊)
                holder.tvTime.text = if (item.flagged) "⚠ ${item.timeText}" else item.timeText
                holder.tvTime.setTextColor(if (item.flagged) 0xFFFFA726.toInt() else 0xFF6B7280.toInt())
                holder.tvText.text = item.text
                val label = item.speakerLabel
                if (label.isNullOrBlank()) {
                    holder.tvSpeaker.visibility = View.GONE
                } else {
                    holder.tvSpeaker.visibility = View.VISIBLE
                    holder.tvSpeaker.text = label
                    val (bg, fg) = SpeakerBadge.colors(label)
                    holder.tvSpeaker.backgroundTintList = android.content.res.ColorStateList.valueOf(bg)
                    holder.tvSpeaker.setTextColor(fg)
                }
                holder.itemView.setOnClickListener { onParagraphClick(item.transcriptId) }
            }
            is TimelineUiItem.PendingBlock -> {
                holder as PendingVH
                holder.tvRange.text = item.timeRangeText
                holder.tvLabel.text = if (item.count > 1) "文字起こし待ち (${item.count}件)" else "文字起こし待ち"
            }
            is TimelineUiItem.EmptyDay -> {
                // 何もしない（レイアウトに静的テキスト）
            }
        }
    }
}
