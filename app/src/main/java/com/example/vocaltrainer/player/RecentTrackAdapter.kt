package com.example.vocaltrainer.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.vocaltrainer.R
import com.example.vocaltrainer.data.QueueEntry

class RecentTrackAdapter(
    private val onClick: (Int) -> Unit
) : ListAdapter<QueueEntry, RecentTrackAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recent_track, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvRecentTrackName)

        fun bind(entry: QueueEntry, position: Int) {
            tvName.text = entry.displayName
            tvName.setOnClickListener { onClick(position) }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<QueueEntry>() {
            override fun areItemsTheSame(oldItem: QueueEntry, newItem: QueueEntry) = oldItem.uri == newItem.uri
            override fun areContentsTheSame(oldItem: QueueEntry, newItem: QueueEntry) = oldItem == newItem
        }
    }
}
