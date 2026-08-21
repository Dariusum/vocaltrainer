package com.example.vocaltrainer.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hält eine Referenz auf die aktuell aktive [PlaybackSource] (derzeit immer das
 * Activity-weit gescopete `PlayerViewModel`), damit [VocalPlayerBridge] Befehle von
 * MediaSession/Auto/Bluetooth dorthin durchreichen kann, ohne eine harte Abhängigkeit
 * zwischen [PlaybackService] und dem ViewModel zu benötigen (das ViewModel kennt den
 * Service nicht, der Service kennt das ViewModel nicht — nur dieses gemeinsame Objekt).
 */
object PlaybackSourceRegistry {
    private val _active = MutableStateFlow<PlaybackSource?>(null)
    val active: StateFlow<PlaybackSource?> = _active.asStateFlow()

    fun register(source: PlaybackSource) {
        _active.value = source
    }

    fun unregister(source: PlaybackSource) {
        if (_active.value === source) _active.value = null
    }
}
