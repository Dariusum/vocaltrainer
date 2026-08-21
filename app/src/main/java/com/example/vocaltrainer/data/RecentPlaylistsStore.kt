package com.example.vocaltrainer.data

import android.content.Context

data class RecentPlaylistInfo(val id: String, val title: String)

/**
 * Speichert die zuletzt abgespielten Playlisten, analog zu [RecentTracksStore] — eigener
 * Speicher statt Wiederverwendung derselben Datei, da Playlisten (id+Titel) und Einzeltitel
 * (URI+Anzeigename) strukturell verschieden sind. Die Zusammenführung zu einer gemeinsamen
 * "Zuletzt gespielt"-Anzeige erfolgt erst beim Lesen (siehe `PlayerViewModel`), anhand der
 * jeweiligen Zeitstempel.
 */
class RecentPlaylistsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun recordPlayed(playlistId: String, title: String) {
        val existing = readEntries().filterNot { it.id == playlistId }
        val updated = (listOf(Record(System.currentTimeMillis(), playlistId, title)) + existing).take(MAX_ENTRIES)
        writeEntries(updated)
    }

    fun getRecentTimestamped(): List<Pair<Long, RecentPlaylistInfo>> =
        readEntries().map { it.timestampMs to RecentPlaylistInfo(it.id, it.title) }

    private fun readEntries(): List<Record> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return raw.split("\n").mapNotNull { line -> parseLine(line) }
    }

    private fun writeEntries(entries: List<Record>) {
        val raw = entries.joinToString("\n") { "${it.timestampMs}|${it.id}|${sanitize(it.title)}" }
        prefs.edit().putString(KEY_ENTRIES, raw).apply()
    }

    private fun parseLine(line: String): Record? {
        val parts = line.split("|", limit = 3)
        if (parts.size != 3) return null
        val timestamp = parts[0].toLongOrNull() ?: return null
        return Record(timestamp, parts[1], parts[2])
    }

    private fun sanitize(title: String): String = title.replace("|", " ").replace("\n", " ")

    private data class Record(val timestampMs: Long, val id: String, val title: String)

    companion object {
        private const val PREFS_NAME = "recent_playlists"
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 15
    }
}
