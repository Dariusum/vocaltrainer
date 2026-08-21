package com.example.vocaltrainer.data

import android.content.Context
import android.net.Uri
import com.example.vocaltrainer.audio.PcmAudio
import com.example.vocaltrainer.audio.SeparatedStems
import com.example.vocaltrainer.audio.VocalSeparator
import com.example.vocaltrainer.audio.wav.WavFileReader
import com.example.vocaltrainer.audio.wav.WavFileWriter
import com.example.vocaltrainer.log.VocaltrainerLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Properties

/**
 * Persistiert einmal getrennte Stems (Instrumental + Original-Gesang) pro Quelldatei, damit
 * dieselbe Datei (z.B. ein Playlist-Titel) beim erneuten Abspielen nicht erneut die
 * mehrminütige KI-Trennung durchlaufen muss.
 *
 * Layout: filesDir/stems_cache/<sha256(uri)>/{instrumental.wav, vocal.wav, meta.properties}.
 * Ein Treffer wird nur verwendet, wenn `frameCount`/`sampleRate` zur frisch dekodierten PCM
 * passen (die URI allein beweist nicht, dass die dahinterliegende Datei unverändert ist) und
 * das beim Trennen verwendete Modell (`modelAsset`) noch mit [VocalSeparator]s aktuellem
 * Modell übereinstimmt — sonst würde ein künftiger Modellwechsel sonst still veraltete Stems
 * weiterverwenden.
 */
class StemsCache(private val context: Context) {

    private val rootDir: File
        get() = File(context.filesDir, "stems_cache").apply { mkdirs() }

    suspend fun get(uri: Uri, frameCount: Int, sampleRate: Int): SeparatedStems? = withContext(Dispatchers.IO) {
        val dir = File(rootDir, keyFor(uri))
        val metaFile = File(dir, METADATA_FILE)
        val instrumentalFile = File(dir, INSTRUMENTAL_FILE)
        val vocalFile = File(dir, VOCAL_FILE)
        if (!metaFile.exists() || !instrumentalFile.exists() || !vocalFile.exists()) return@withContext null

        val props = Properties()
        val matches = runCatching {
            metaFile.inputStream().use { props.load(it) }
            props.getProperty("frameCount")?.toIntOrNull() == frameCount &&
                props.getProperty("sampleRate")?.toIntOrNull() == sampleRate &&
                props.getProperty("modelAsset") == VocalSeparator.MODEL_ASSET
        }.getOrDefault(false)
        if (!matches) return@withContext null

        val stems = runCatching {
            SeparatedStems(vocal = WavFileReader.read(vocalFile), instrumental = WavFileReader.read(instrumentalFile))
        }.getOrNull() ?: return@withContext null

        // Für die simple LRU-Räumung in put(): "zuletzt verwendet" statt nur "erstellt".
        dir.setLastModified(System.currentTimeMillis())
        VocaltrainerLogger.i("StemsCache", "Treffer für $uri")
        stems
    }

    suspend fun put(uri: Uri, frameCount: Int, sampleRate: Int, stems: SeparatedStems) = withContext(Dispatchers.IO) {
        val dir = File(rootDir, keyFor(uri)).apply { mkdirs() }
        writeWav(File(dir, INSTRUMENTAL_FILE), stems.instrumental)
        writeWav(File(dir, VOCAL_FILE), stems.vocal)

        val props = Properties()
        props.setProperty("sourceUri", uri.toString())
        props.setProperty("frameCount", frameCount.toString())
        props.setProperty("sampleRate", sampleRate.toString())
        props.setProperty("modelAsset", VocalSeparator.MODEL_ASSET)
        File(dir, METADATA_FILE).outputStream().use { props.store(it, "Vocaltrainer stems cache metadata") }

        evictIfOverLimit()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        rootDir.listFiles()?.forEach { it.deleteRecursively() }
        Unit
    }

    suspend fun currentSizeBytes(): Long = withContext(Dispatchers.IO) { dirSizeBytes(rootDir) }

    private fun writeWav(file: File, pcm: PcmAudio) {
        val writer = WavFileWriter(file, pcm.sampleRate, pcm.channelCount)
        writer.writeFrames(pcm.samples, 0, pcm.samples.size)
        writer.close()
    }

    /** Löscht die am längsten nicht mehr verwendeten Einträge, bis [MAX_CACHE_BYTES] wieder eingehalten ist. */
    private fun evictIfOverLimit() {
        val entries = rootDir.listFiles { file -> file.isDirectory } ?: return
        var totalSize = entries.sumOf { dirSizeBytes(it) }
        if (totalSize <= MAX_CACHE_BYTES) return

        val oldestFirst = entries.sortedBy { it.lastModified() }
        for (dir in oldestFirst) {
            if (totalSize <= MAX_CACHE_BYTES) break
            totalSize -= dirSizeBytes(dir)
            dir.deleteRecursively()
        }
    }

    private fun dirSizeBytes(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun keyFor(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    companion object {
        private const val INSTRUMENTAL_FILE = "instrumental.wav"
        private const val VOCAL_FILE = "vocal.wav"
        private const val METADATA_FILE = "meta.properties"
        private const val MAX_CACHE_BYTES = 800L * 1024 * 1024
    }
}
