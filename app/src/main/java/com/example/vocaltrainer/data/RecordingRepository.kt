package com.example.vocaltrainer.data

import android.content.Context
import com.example.vocaltrainer.audio.MixGains
import com.example.vocaltrainer.audio.PcmAudio
import com.example.vocaltrainer.audio.wav.WavFileReader
import com.example.vocaltrainer.audio.wav.WavFileWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties
import java.util.UUID

/**
 * Dateisystem-basierte Verwaltung gespeicherter Aufnahme-Projekte — bewusst kein Room
 * (die Daten sind naturgemäß dateibasiert: original.wav + vocal.wav + Metadaten),
 * analog zur Entscheidung in der Vault-App gegen eine Datenbank für dateibasierte Inhalte.
 *
 * Layout: filesDir/recordings/<uuid>/{original.wav, vocal.wav, metadata.properties}
 */
class RecordingRepository(private val context: Context) {

    private val rootDir: File
        get() = File(context.filesDir, "recordings").apply { mkdirs() }

    suspend fun listProjects(): List<RecordingProject> = withContext(Dispatchers.IO) {
        rootDir.listFiles { file -> file.isDirectory }
            ?.mapNotNull { dir -> loadMetadata(dir) }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    suspend fun getProject(id: String): RecordingProject? = withContext(Dispatchers.IO) {
        loadMetadata(File(rootDir, id))
    }

    suspend fun saveProject(title: String, originalPcm: PcmAudio, tempVocalFile: File): RecordingProject =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val dir = File(rootDir, id).apply { mkdirs() }

            val originalFile = File(dir, ORIGINAL_FILE)
            val writer = WavFileWriter(originalFile, originalPcm.sampleRate, originalPcm.channelCount)
            writer.writeFrames(originalPcm.samples, 0, originalPcm.samples.size)
            writer.close()

            val vocalFile = File(dir, VOCAL_FILE)
            if (!tempVocalFile.renameTo(vocalFile)) {
                tempVocalFile.copyTo(vocalFile, overwrite = true)
                tempVocalFile.delete()
            }

            val project = RecordingProject(
                id = id,
                title = title,
                createdAt = System.currentTimeMillis(),
                durationMs = originalPcm.durationMs,
                sampleRate = originalPcm.sampleRate,
                lastFaderMaster = 1f,
                lastFaderUserVocal = 1f,
                lastFaderOriginalVocal = 1f
            )
            writeMetadata(dir, project)
            project
        }

    suspend fun loadProjectAudio(id: String): Pair<PcmAudio, PcmAudio> = withContext(Dispatchers.IO) {
        val dir = File(rootDir, id)
        val original = WavFileReader.read(File(dir, ORIGINAL_FILE))
        val vocal = WavFileReader.read(File(dir, VOCAL_FILE))
        original to vocal
    }

    suspend fun updateFaders(id: String, gains: MixGains) = withContext(Dispatchers.IO) {
        val dir = File(rootDir, id)
        val project = loadMetadata(dir) ?: return@withContext
        writeMetadata(
            dir,
            project.copy(
                lastFaderMaster = gains.master,
                lastFaderUserVocal = gains.userVocal,
                lastFaderOriginalVocal = gains.originalVocal
            )
        )
    }

    suspend fun deleteProject(id: String) = withContext(Dispatchers.IO) {
        File(rootDir, id).deleteRecursively()
        Unit
    }

    private fun loadMetadata(dir: File): RecordingProject? {
        if (!dir.isDirectory) return null
        val metaFile = File(dir, METADATA_FILE)
        if (!metaFile.exists()) return null
        return runCatching {
            val props = Properties()
            metaFile.inputStream().use { props.load(it) }
            RecordingProject(
                id = dir.name,
                title = props.getProperty("title", dir.name),
                createdAt = props.getProperty("createdAt", "0").toLong(),
                durationMs = props.getProperty("durationMs", "0").toLong(),
                sampleRate = props.getProperty("sampleRate", "44100").toInt(),
                lastFaderMaster = props.getProperty("lastFaderMaster", "1.0").toFloat(),
                lastFaderUserVocal = props.getProperty("lastFaderUserVocal", "1.0").toFloat(),
                lastFaderOriginalVocal = props.getProperty("lastFaderOriginalVocal", "1.0").toFloat()
            )
        }.getOrNull()
    }

    private fun writeMetadata(dir: File, project: RecordingProject) {
        val props = Properties()
        props.setProperty("title", project.title)
        props.setProperty("createdAt", project.createdAt.toString())
        props.setProperty("durationMs", project.durationMs.toString())
        props.setProperty("sampleRate", project.sampleRate.toString())
        props.setProperty("lastFaderMaster", project.lastFaderMaster.toString())
        props.setProperty("lastFaderUserVocal", project.lastFaderUserVocal.toString())
        props.setProperty("lastFaderOriginalVocal", project.lastFaderOriginalVocal.toString())
        File(dir, METADATA_FILE).outputStream().use { props.store(it, "Vocaltrainer recording metadata") }
    }

    companion object {
        private const val ORIGINAL_FILE = "original.wav"
        private const val VOCAL_FILE = "vocal.wav"
        private const val METADATA_FILE = "metadata.properties"
    }
}
