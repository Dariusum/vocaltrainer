package com.example.vocaltrainer.data

data class Playlist(
    val id: String,
    val title: String,
    val createdAt: Long,
    val tracks: List<QueueEntry>
)
