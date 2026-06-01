package com.rokuonsumm.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rokuonsumm.R

class DayRowAdapter(
    private val onClick: (DayRow) -> Unit
) : ListAdapter<DayRow, DayRowAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<DayRow>() {
        override fun areItemsTheSame(a: DayRow, b: DayRow) = a.dateKey == b.dateKey
        override fun areContentsTheSame(a: DayRow, b: DayRow) = a == b
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvDate: TextView = itemView.findViewById(R.id.tvDate)
        val tvPreview: TextView = itemView.findViewById(R.id.tvPreview)
        val tvTodayBadge: TextView = itemView.findViewById(R.id.tvTodayBadge)
        val tvPendingBadge: TextView = itemView.findViewById(R.id.tvPendingBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_day_row, parent, false)
        return VH(v)
    }

    /**
     * 件数を脳に優しい粒度で表記。
     * - 1-99: 「N件待ち」
     * - 100-499: 「100+件」(100単位で切り捨て)
     * - 500-999: 「多め」
     * - 1000+: 「大量」
     */
    private fun formatPendingCount(n: Int): String = when {
        n < 100 -> "$n 件待ち"
        n < 500 -> "${(n / 100) * 100}+ 件"
        n < 1000 -> "多め"
        else -> "大量"
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = getItem(position)
        holder.tvDate.text = row.displayDate
        if (row.summaryPreview != null) {
            holder.tvPreview.text = row.summaryPreview
            holder.tvPreview.setTextColor(0xFFCFCFCF.toInt())
        } else {
            holder.tvPreview.text = "未要約"
            holder.tvPreview.setTextColor(0xFF6B7280.toInt())
        }
        holder.tvTodayBadge.visibility = if (row.isToday) View.VISIBLE else View.GONE
        if (row.pendingCount > 0) {
            holder.tvPendingBadge.text = formatPendingCount(row.pendingCount)
            holder.tvPendingBadge.visibility = View.VISIBLE
        } else {
            holder.tvPendingBadge.visibility = View.GONE
        }
        holder.itemView.setOnClickListener { onClick(row) }
    }
}
