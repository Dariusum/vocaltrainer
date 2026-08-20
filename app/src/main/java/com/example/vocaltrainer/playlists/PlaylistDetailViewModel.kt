package com.example.vocaltrainer.playlists

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.vocaltrainer.data.Playlist
import com.example.vocaltrainer.data.PlaylistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlaylistDetailViewModel(
    application: Application,
    private val playlistId: String,
    private val repository: PlaylistRepository
) : AndroidViewModel(application) {

    private val _playlist = MutableStateFlow<Playlist?>(null)
    val playlist: StateFlow<Playlist?> = _playlist.asStateFlow()

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

    class Factory(
        private val application: Application,
        private val playlistId: String,
        private val repository: PlaylistRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlaylistDetailViewModel(application, playlistId, repository) as T
    }
}
