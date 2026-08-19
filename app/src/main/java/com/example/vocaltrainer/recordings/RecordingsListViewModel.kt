package com.example.vocaltrainer.recordings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.vocaltrainer.data.RecordingProject
import com.example.vocaltrainer.data.RecordingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RecordingsListViewModel(
    application: Application,
    private val repository: RecordingRepository
) : AndroidViewModel(application) {

    private val _projects = MutableStateFlow<List<RecordingProject>>(emptyList())
    val projects: StateFlow<List<RecordingProject>> = _projects.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _projects.value = repository.listProjects()
        }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch {
            repository.deleteProject(id)
            refresh()
        }
    }

    class Factory(
        private val application: Application,
        private val repository: RecordingRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RecordingsListViewModel(application, repository) as T
    }
}
