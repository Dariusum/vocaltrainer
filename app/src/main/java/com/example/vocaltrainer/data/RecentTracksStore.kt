package com.example.vocaltrainer.data

import android.content.Context
import android.net.Uri

/**
 * Speichert die zuletzt abgespielten Titel für die Schnellauswahl auf dem Wiedergabe-Screen.
 * SharedPreferences-basiert (kein Room/keine Datenbank für eine so kleine, einfache Liste) —
 * ein Eintrag pro Zeile als `Zeitstempel|URI|Anzeigename`, neuester zuerst. Ein erneutes
 * Abspielen eines bereits enthaltenen Titels verschiebt ihn nach vorne statt ihn zu duplizieren.
 */
class RecentTracksStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun recordPlayed(uri: Uri, displayName: String) {
        val existing = readEntries().filterNot { it.uri == uri }
        val updated = (listOf(RecentTrack(System.currentTimeMillis(), uri, displayName)) + existing)
            .take(MAX_ENTRIES)
        writeEntries(updated)
    }

    fun getRecent(): List<QueueEntry> = readEntries().map { QueueEntry(it.uri, it.displayName) }

    /** Wie [getRecent], aber mit Zeitstempel — nötig, um mit [RecentPlaylistsStore]s Einträgen
     * zu einer gemeinsamen, chronologisch sortierten "Zuletzt gespielt"-Liste zu verschmelzen. */
    fun getRecentTimestamped(): List<Pair<Long, QueueEntry>> =
        readEntries().map { it.timestampMs to QueueEntry(it.uri, it.displayName) }

    private fun readEntries(): List<RecentTrack> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return raw.split("\n").mapNotNull { line -> parseLine(line) }
    }

    private fun writeEntries(entries: List<RecentTrack>) {
        val raw = entries.joinToString("\n") { entry ->
            "${entry.timestampMs}|${entry.uri}|${sanitize(entry.displayName)}"
        }
        prefs.edit().putString(KEY_ENTRIES, raw).apply()
    }

    private fun parseLine(line: String): RecentTrack? {
        val parts = line.split("|", limit = 3)
        if (parts.size != 3) return null
        val timestamp = parts[0].toLongOrNull() ?: return null
        val uri = runCatching { Uri.parse(parts[1]) }.getOrNull() ?: return null
        return RecentTrack(timestamp, uri, parts[2])
    }

    /** Trennzeichen dürfen im Anzeigenamen nicht vorkommen, da das Zeilenformat sonst bricht. */
    private fun sanitize(displayName: String): String = displayName.replace("|", " ").replace("\n", " ")

    private data class RecentTrack(val timestampMs: Long, val uri: Uri, val displayName: String)

    companion object {
        private const val PREFS_NAME = "recent_tracks"
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 15
    }
}
