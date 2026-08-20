package com.example.vocaltrainer.playlists

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.vocaltrainer.R
import com.example.vocaltrainer.data.QueueEntry

class PlaylistTrackAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit
) : ListAdapter<QueueEntry, PlaylistTrackAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist_track, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)

        fun bind(entry: QueueEntry, position: Int) {
            tvTitle.text = entry.displayName
            itemView.setOnClickListener { onClick(position) }
            itemView.setOnLongClickListener { onLongClick(position); true }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<QueueEntry>() {
            override fun areItemsTheSame(oldItem: QueueEntry, newItem: QueueEntry) = oldItem.uri == newItem.uri
            override fun areContentsTheSame(oldItem: QueueEntry, newItem: QueueEntry) = oldItem == newItem
        }
    }
}
