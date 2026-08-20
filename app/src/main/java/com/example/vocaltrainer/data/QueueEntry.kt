package com.example.vocaltrainer.data

import android.net.Uri

/** Ein abspielbarer Titel innerhalb einer Warteschlange (Playlist oder Schnellauswahl). */
data class QueueEntry(val uri: Uri, val displayName: String)
