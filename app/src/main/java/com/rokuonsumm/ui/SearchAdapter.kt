package com.rokuonsumm.ui

import android.graphics.Color
import android.text.SpannableString
import android.text.Spannable
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rokuonsumm.R

class SearchAdapter(
    private val onClick: (SearchResult) -> Unit
) : ListAdapter<SearchResult, SearchAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<SearchResult>() {
        override fun areItemsTheSame(a: SearchResult, b: SearchResult) =
            a.isSummary == b.isSummary && a.transcriptId == b.transcriptId && a.dateKey == b.dateKey
        override fun areContentsTheSame(a: SearchResult, b: SearchResult) = a == b
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvWhen: TextView = v.findViewById(R.id.tvWhen)
        val tvSnippet: TextView = v.findViewById(R.id.tvSnippet)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = getItem(position)
        holder.tvWhen.text = r.displayDateTime
        // スニペットのマッチ箇所をハイライト
        val sp = SpannableString(r.snippet)
        if (r.matchEnd > r.matchStart && r.matchEnd <= r.snippet.length) {
            sp.setSpan(BackgroundColorSpan(0x554CAF50), r.matchStart, r.matchEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(ForegroundColorSpan(Color.WHITE), r.matchStart, r.matchEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        holder.tvSnippet.text = sp
        holder.itemView.setOnClickListener { onClick(r) }
    }
}
