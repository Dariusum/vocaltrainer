package com.example.vocaltrainer.data

/** Ein Eintrag der gemeinsamen "Zuletzt gespielt"-Schnellauswahl — Einzeltitel oder Playlist. */
sealed class RecentEntry {
    data class Track(val entry: QueueEntry) : RecentEntry()
    data class PlaylistEntry(val id: String, val title: String) : RecentEntry()
}
