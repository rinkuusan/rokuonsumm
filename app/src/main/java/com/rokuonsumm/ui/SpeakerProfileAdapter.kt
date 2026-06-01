package com.rokuonsumm.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rokuonsumm.R

class SpeakerProfileAdapter(
    private val onClick: (ProfileRow) -> Unit
) : ListAdapter<ProfileRow, SpeakerProfileAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<ProfileRow>() {
        override fun areItemsTheSame(a: ProfileRow, b: ProfileRow) = a.name == b.name
        override fun areContentsTheSame(a: ProfileRow, b: ProfileRow) = a == b
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val dot: View = v.findViewById(R.id.dot)
        val tvName: TextView = v.findViewById(R.id.tvName)
        val tvStatus: TextView = v.findViewById(R.id.tvStatus)
        val tvAction: TextView = v.findViewById(R.id.tvAction)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_speaker_profile, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = getItem(position)
        holder.tvName.text = r.name
        if (r.registered) {
            holder.tvStatus.text = "登録済み (${r.sampleCount}サンプル)"
            holder.dot.setBackgroundResource(R.drawable.bg_status_dot_rec)
            holder.dot.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF4CAF50.toInt())
            holder.tvAction.text = "管理"
        } else {
            holder.tvStatus.text = "未登録"
            holder.dot.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF555555.toInt())
            holder.tvAction.text = "登録"
        }
        holder.itemView.setOnClickListener { onClick(r) }
    }
}
