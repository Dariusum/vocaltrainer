package com.example.vocaltrainer.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.vocaltrainer.R
import com.example.vocaltrainer.data.RecentEntry

class RecentTrackAdapter(
    private val onClick: (Int) -> Unit
) : ListAdapter<RecentEntry, RecentTrackAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recent_track, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvRecentTrackName)

        fun bind(item: RecentEntry, position: Int) {
            tvName.text = when (item) {
                is RecentEntry.Track -> item.entry.displayName
                is RecentEntry.PlaylistEntry -> "📁 ${item.title}"
            }
            tvName.setOnClickListener { onClick(position) }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<RecentEntry>() {
            override fun areItemsTheSame(oldItem: RecentEntry, newItem: RecentEntry): Boolean = when {
                oldItem is RecentEntry.Track && newItem is RecentEntry.Track -> oldItem.entry.uri == newItem.entry.uri
                oldItem is RecentEntry.PlaylistEntry && newItem is RecentEntry.PlaylistEntry -> oldItem.id == newItem.id
                else -> false
            }

            override fun areContentsTheSame(oldItem: RecentEntry, newItem: RecentEntry) = oldItem == newItem
        }
    }
}
