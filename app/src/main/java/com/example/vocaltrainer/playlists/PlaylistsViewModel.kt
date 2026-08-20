package com.example.vocaltrainer.playlists

import android.app.Application
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

class PlaylistsViewModel(
    application: Application,
    private val repository: PlaylistRepository
) : AndroidViewModel(application) {

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { _playlists.value = repository.listPlaylists() }
    }

    fun createPlaylist(title: String) {
        viewModelScope.launch {
            repository.createPlaylist(title)
            refresh()
        }
    }

    fun deletePlaylist(id: String) {
        viewModelScope.launch {
            repository.deletePlaylist(id)
            refresh()
        }
    }

    class Factory(
        private val application: Application,
        private val repository: PlaylistRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlaylistsViewModel(application, repository) as T
    }
}
