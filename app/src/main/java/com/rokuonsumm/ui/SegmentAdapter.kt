package com.rokuonsumm.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rokuonsumm.R
import com.rokuonsumm.databinding.ItemDateHeaderBinding
import com.rokuonsumm.databinding.ItemSegmentBinding

class SegmentAdapter(
    private val onLongPress: (SegmentUiItem) -> Unit,
    private val onItemClick: (SegmentUiItem) -> Unit
) : ListAdapter<ListItem, RecyclerView.ViewHolder>(Diff) {

    var selectedPaths: Set<String> = emptySet()
        set(value) { field = value; notifyDataSetChanged() }

    var isSelectionMode: Boolean = false
        set(value) { field = value; notifyDataSetChanged() }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is ListItem.Header  -> TYPE_HEADER
        is ListItem.Segment -> TYPE_SEGMENT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER  -> HeaderViewHolder(ItemDateHeaderBinding.inflate(inflater, parent, false))
            else         -> SegmentViewHolder(ItemSegmentBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ListItem.Header  -> (holder as HeaderViewHolder).bind(item)
            is ListItem.Segment -> (holder as SegmentViewHolder).bind(item.item)
        }
    }

    inner class HeaderViewHolder(private val b: ItemDateHeaderBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(item: ListItem.Header) {
            b.tvDate.text = item.date
            b.timelineView.setData(item.dayStartMs, item.dayEndMs, item.segments)
        }
    }

    inner class SegmentViewHolder(private val b: ItemSegmentBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(item: SegmentUiItem) {
            val ctx: Context = b.root.context
            b.tvTime.text = item.displayTime
            b.tvSize.text = formatSize(item.sizeBytes)
            b.tvStatus.text = when (item.status) {
                TranscriptStatus.PENDING -> ctx.getString(R.string.status_pending)
                TranscriptStatus.DONE    -> ctx.getString(R.string.status_done)
            }
            b.tvStatus.setBackgroundResource(
                if (item.status == TranscriptStatus.DONE) R.drawable.bg_status_done
                else R.drawable.bg_status_badge
            )
            b.cbSelect.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
            b.cbSelect.isChecked  = item.filePath in selectedPaths

            b.root.setOnClickListener  { onItemClick(item) }
            b.root.setOnLongClickListener { onLongPress(item); true }
        }

        private fun formatSize(bytes: Long): String = when {
            bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            else                 -> "%d KB".format(bytes / 1024)
        }
    }

    companion object {
        private const val TYPE_HEADER  = 0
        private const val TYPE_SEGMENT = 1

        private val Diff = object : DiffUtil.ItemCallback<ListItem>() {
            override fun areItemsTheSame(old: ListItem, new: ListItem) = when {
                old is ListItem.Header  && new is ListItem.Header  -> old.date == new.date
                old is ListItem.Segment && new is ListItem.Segment -> old.item.filePath == new.item.filePath
                else -> false
            }
            override fun areContentsTheSame(old: ListItem, new: ListItem) = old == new
        }
    }
}
