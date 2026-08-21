package com.example.vocaltrainer.playback

import kotlinx.coroutines.flow.StateFlow

/** Immutable Momentaufnahme dessen, was [VocalPlayerBridge] für MediaSession/Auto/Bluetooth braucht. */
data class PlaybackSourceState(
    val isPlaying: Boolean = false,
    /** Anzeigenamen der aktuellen Warteschlange, in Reihenfolge. Leer, wenn nichts geladen ist. */
    val queueTitles: List<String> = emptyList(),
    val queueIndex: Int = 0
)

/**
 * Von [com.example.vocaltrainer.player.PlayerViewModel] implementiert, damit
 * [VocalPlayerBridge] grundlegende Wiedergabe-Befehle (Play/Pause/Stop/Vor/Zurück) von
 * MediaSession/Auto/Bluetooth an die aktuell laufende Wiedergabe durchreichen kann, ohne
 * selbst etwas über Stems/Decoding/Trennung wissen zu müssen.
 */
interface PlaybackSource {
    val state: StateFlow<PlaybackSourceState>

    fun play()
    fun pause()
    fun stop()

    /** Springt zur Warteschlangen-Position [index] (Vor/Zurück-Hardware-Tasten). */
    fun seekToQueueIndex(index: Int)
}
