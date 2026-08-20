package com.example.vocaltrainer.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties
import java.util.UUID

/**
 * Dateisystem-basierte Playlist-Verwaltung, analog zu [RecordingRepository] — kein Room für
 * diese einfache, geordnete Listenstruktur.
 *
 * Layout: filesDir/playlists/<uuid>/{metadata.properties, tracks.properties}. Titel werden in
 * tracks.properties als indizierte Einträge `track.<i>.uri`/`track.<i>.name` abgelegt, um die
 * Reihenfolge ohne JSON-Abhängigkeit verlustfrei zu erhalten.
 */
class PlaylistRepository(private val context: Context) {

    private val rootDir: File
        get() = File(context.filesDir, "playlists").apply { mkdirs() }

    suspend fun listPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        rootDir.listFiles { file -> file.isDirectory }
            ?.mapNotNull { dir -> loadPlaylist(dir) }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    suspend fun getPlaylist(id: String): Playlist? = withContext(Dispatchers.IO) {
        loadPlaylist(File(rootDir, id))
    }

    suspend fun createPlaylist(title: String): Playlist = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val dir = File(rootDir, id).apply { mkdirs() }
        val playlist = Playlist(id = id, title = title, createdAt = System.currentTimeMillis(), tracks = emptyList())
        writeMetadata(dir, playlist)
        writeTracks(dir, playlist.tracks)
        playlist
    }

    suspend fun addTrack(playlistId: String, uri: Uri, displayName: String) = withContext(Dispatchers.IO) {
        val dir = File(rootDir, playlistId)
        val playlist = loadPlaylist(dir) ?: return@withContext
        writeTracks(dir, playlist.tracks + QueueEntry(uri, displayName))
    }

    suspend fun removeTrack(playlistId: String, index: Int) = withContext(Dispatchers.IO) {
        val dir = File(rootDir, playlistId)
        val playlist = loadPlaylist(dir) ?: return@withContext
        if (index !in playlist.tracks.indices) return@withContext
        writeTracks(dir, playlist.tracks.filterIndexed { i, _ -> i != index })
    }

    suspend fun deletePlaylist(id: String) = withContext(Dispatchers.IO) {
        File(rootDir, id).deleteRecursively()
        Unit
    }

    private fun loadPlaylist(dir: File): Playlist? {
        if (!dir.isDirectory) return null
        val metaFile = File(dir, METADATA_FILE)
        if (!metaFile.exists()) return null
        return runCatching {
            val props = Properties()
            metaFile.inputStream().use { props.load(it) }
            Playlist(
                id = dir.name,
                title = props.getProperty("title", dir.name),
                createdAt = props.getProperty("createdAt", "0").toLong(),
                tracks = loadTracks(dir)
            )
        }.getOrNull()
    }

    private fun loadTracks(dir: File): List<QueueEntry> {
        val tracksFile = File(dir, TRACKS_FILE)
        if (!tracksFile.exists()) return emptyList()
        val props = Properties()
        tracksFile.inputStream().use { props.load(it) }
        val count = props.getProperty("count", "0").toIntOrNull() ?: 0
        return (0 until count).mapNotNull { i ->
            val uriStr = props.getProperty("track.$i.uri") ?: return@mapNotNull null
            val name = props.getProperty("track.$i.name", uriStr)
            runCatching { QueueEntry(Uri.parse(uriStr), name) }.getOrNull()
        }
    }

    private fun writeMetadata(dir: File, playlist: Playlist) {
        val props = Properties()
        props.setProperty("title", playlist.title)
        props.setProperty("createdAt", playlist.createdAt.toString())
        File(dir, METADATA_FILE).outputStream().use { props.store(it, "Vocaltrainer playlist metadata") }
    }

    private fun writeTracks(dir: File, tracks: List<QueueEntry>) {
        val props = Properties()
        props.setProperty("count", tracks.size.toString())
        tracks.forEachIndexed { i, track ->
            props.setProperty("track.$i.uri", track.uri.toString())
            props.setProperty("track.$i.name", track.displayName)
        }
        File(dir, TRACKS_FILE).outputStream().use { props.store(it, "Vocaltrainer playlist tracks") }
    }

    companion object {
        private const val METADATA_FILE = "metadata.properties"
        private const val TRACKS_FILE = "tracks.properties"
    }
}
