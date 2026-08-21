package com.example.vocaltrainer.playlists

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.vocaltrainer.audio.TrackDecoder
import com.example.vocaltrainer.audio.VocalSeparator
import com.example.vocaltrainer.data.Playlist
import com.example.vocaltrainer.data.PlaylistRepository
import com.example.vocaltrainer.data.StemsCache
import com.example.vocaltrainer.log.VocaltrainerLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class ReseparateState {
    object Idle : ReseparateState()
    data class InProgress(val current: Int, val total: Int, val currentTitle: String) : ReseparateState()
}

class PlaylistDetailViewModel(
    application: Application,
    private val playlistId: String,
    private val repository: PlaylistRepository,
    private val stemsCache: StemsCache
) : AndroidViewModel(application) {

    private val _playlist = MutableStateFlow<Playlist?>(null)
    val playlist: StateFlow<Playlist?> = _playlist.asStateFlow()

    private val _reseparateState = MutableStateFlow<ReseparateState>(ReseparateState.Idle)
    val reseparateState: StateFlow<ReseparateState> = _reseparateState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { _playlist.value = repository.getPlaylist(playlistId) }
    }

    fun addTrack(uri: Uri, displayName: String) {
        viewModelScope.launch {
            repository.addTrack(playlistId, uri, displayName)
            refresh()
        }
    }

    fun removeTrack(index: Int) {
        viewModelScope.launch {
            repository.removeTrack(playlistId, index)
            refresh()
        }
    }

    /**
     * Trennt jeden Titel der Playlist neu (überschreibt einen eventuell vorhandenen
     * Cache-Eintrag) — nützlich nach einer Trennungs-Qualitätsänderung (Modell- oder
     * Tuning-Wechsel), um nicht auf das erneute Abspielen jedes einzelnen Titels warten zu
     * müssen. Läuft Titel für Titel nacheinander statt parallel, damit die CPU nicht durch
     * mehrere gleichzeitige Trennungen ausgebremst wird (siehe die Concurrent-Load-Lehre aus
     * PlayerViewModel). Ein Fehler bei einem Titel bricht nicht die ganze Liste ab.
     */
    fun reseparateAll() {
        val tracks = playlist.value?.tracks ?: return
        if (_reseparateState.value != ReseparateState.Idle || tracks.isEmpty()) return
        viewModelScope.launch {
            for ((index, track) in tracks.withIndex()) {
                _reseparateState.value = ReseparateState.InProgress(index + 1, tracks.size, track.displayName)
                try {
                    val pcm = TrackDecoder.decode(getApplication(), track.uri)
                    if (pcm.channelCount == 2) {
                        val stems = VocalSeparator.separate(getApplication(), pcm)
                        stemsCache.put(track.uri, pcm.frameCount, pcm.sampleRate, stems)
                        VocaltrainerLogger.i("PlaylistDetailViewModel", "Neu getrennt: ${track.displayName}")
                    }
                } catch (e: Exception) {
                    VocaltrainerLogger.e("PlaylistDetailViewModel", "Neu-Trennung fehlgeschlagen: ${track.displayName}", e)
                }
            }
            _reseparateState.value = ReseparateState.Idle
        }
    }

    class Factory(
        private val application: Application,
        private val playlistId: String,
        private val repository: PlaylistRepository,
        private val stemsCache: StemsCache
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlaylistDetailViewModel(application, playlistId, repository, stemsCache) as T
    }
}
