package com.example.vocaltrainer.recordings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.vocaltrainer.R
import com.example.vocaltrainer.data.RecordingProject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingAdapter(
    private val onClick: (RecordingProject) -> Unit,
    private val onLongClick: (RecordingProject) -> Unit
) : ListAdapter<RecordingProject, RecordingAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tvSubtitle)

        fun bind(project: RecordingProject) {
            tvTitle.text = project.title
            val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(project.createdAt))
            tvSubtitle.text = "$date · ${formatDuration(project.durationMs)}"
            itemView.setOnClickListener { onClick(project) }
            itemView.setOnLongClickListener { onLongClick(project); true }
        }

        private fun formatDuration(ms: Long): String {
            val totalSeconds = ms / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<RecordingProject>() {
            override fun areItemsTheSame(oldItem: RecordingProject, newItem: RecordingProject) =
                oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: RecordingProject, newItem: RecordingProject) =
                oldItem == newItem
        }
    }
}
