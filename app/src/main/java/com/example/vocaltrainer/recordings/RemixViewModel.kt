package com.example.vocaltrainer.recordings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.vocaltrainer.audio.AudioFocusCoordinator
import com.example.vocaltrainer.audio.MixGains
import com.example.vocaltrainer.audio.PcmAudio
import com.example.vocaltrainer.audio.PlaybackState
import com.example.vocaltrainer.audio.RemixPlaybackEngine
import com.example.vocaltrainer.audio.SeparatedStems
import com.example.vocaltrainer.audio.VocalSeparator
import com.example.vocaltrainer.audio.WavExporter
import com.example.vocaltrainer.data.RecordingRepository
import com.example.vocaltrainer.log.VocaltrainerLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.OutputStream

sealed class RemixUiState {
    object Loading : RemixUiState()
    data class Ready(val title: String) : RemixUiState()
    data class Error(val message: String) : RemixUiState()
}

sealed class RemixEvent {
    object ExportSuccess : RemixEvent()
    data class ExportError(val message: String) : RemixEvent()
}

class RemixViewModel(
    application: Application,
    private val projectId: String,
    private val repository: RecordingRepository
) : AndroidViewModel(application) {

    private val remixPlaybackEngine = RemixPlaybackEngine()
    private val audioFocus = AudioFocusCoordinator(application)

    private var stems: SeparatedStems? = null
    private var userVocal: PcmAudio? = null
    private var persistJob: Job? = null

    private val _uiState = MutableStateFlow<RemixUiState>(RemixUiState.Loading)
    val uiState: StateFlow<RemixUiState> = _uiState.asStateFlow()

    private val _gains = MutableStateFlow(MixGains.FULL)
    val gains: StateFlow<MixGains> = _gains.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _loopEnabled = MutableStateFlow(false)
    val loopEnabled: StateFlow<Boolean> = _loopEnabled.asStateFlow()

    val playbackState: StateFlow<PlaybackState> = remixPlaybackEngine.state
    val positionFrames: StateFlow<Int> = remixPlaybackEngine.positionFrames

    private val _events = MutableSharedFlow<RemixEvent>()
    val events: SharedFlow<RemixEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            try {
                val project = repository.getProject(projectId)
                    ?: throw IllegalStateException("Aufnahme nicht gefunden")
                val (originalPcm, vocalPcm) = repository.loadProjectAudio(projectId)
                userVocal = vocalPcm
                stems = VocalSeparator.separate(getApplication(), originalPcm)
                _gains.value = MixGains(
                    master = project.lastFaderMaster,
                    userVocal = project.lastFaderUserVocal,
                    originalVocal = project.lastFaderOriginalVocal
                )
                _uiState.value = RemixUiState.Ready(project.title)
                VocaltrainerLogger.i("RemixViewModel", "Projekt geladen: $projectId (\"${project.title}\")")
            } catch (e: Exception) {
                VocaltrainerLogger.e("RemixViewModel", "Projekt konnte nicht geladen werden: $projectId", e)
                _uiState.value = RemixUiState.Error(e.message ?: "Aufnahme konnte nicht geladen werden")
            }
        }
    }

    fun togglePlayPause() {
        val s = stems ?: return
        val v = userVocal ?: return
        when (playbackState.value) {
            PlaybackState.PLAYING -> remixPlaybackEngine.pause()
            PlaybackState.PAUSED -> remixPlaybackEngine.resume()
            PlaybackState.STOPPED -> {
                audioFocus.requestFocus(
                    onLoss = { remixPlaybackEngine.pause() },
                    onGain = { remixPlaybackEngine.resume() }
                )
                remixPlaybackEngine.start(s.instrumental, s.vocal, v, _gains.value)
            }
        }
    }

    fun setLoopEnabled(enabled: Boolean) {
        _loopEnabled.value = enabled
        remixPlaybackEngine.setLoopEnabled(enabled)
    }

    /** Startet die Mix-Vorschau von vorne, unabhängig vom aktuellen Wiedergabestatus. */
    fun restart() {
        val s = stems ?: return
        val v = userVocal ?: return
        audioFocus.requestFocus(
            onLoss = { remixPlaybackEngine.pause() },
            onGain = { remixPlaybackEngine.resume() }
        )
        remixPlaybackEngine.start(s.instrumental, s.vocal, v, _gains.value)
    }

    fun setGains(newGains: MixGains) {
        _gains.value = newGains
        remixPlaybackEngine.setGains(newGains)

        // Debounced statt bei jedem Slider-Tick, um nicht bei jedem Pixel eine Datei zu schreiben.
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(500)
            repository.updateFaders(projectId, newGains)
        }
    }

    fun export(out: OutputStream) {
        val s = stems ?: return
        val v = userVocal ?: return
        VocaltrainerLogger.i("RemixViewModel", "Export gestartet für $projectId, gains=${_gains.value}")
        _isExporting.value = true
        viewModelScope.launch {
            try {
                WavExporter.export(s.instrumental, s.vocal, v, _gains.value, out)
                VocaltrainerLogger.i("RemixViewModel", "Export erfolgreich für $projectId")
                _events.emit(RemixEvent.ExportSuccess)
            } catch (e: Exception) {
                VocaltrainerLogger.e("RemixViewModel", "Export fehlgeschlagen für $projectId", e)
                _events.emit(RemixEvent.ExportError(e.message ?: "Export fehlgeschlagen"))
            } finally {
                _isExporting.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        remixPlaybackEngine.stop()
        audioFocus.abandonFocus()
    }

    class Factory(
        private val application: Application,
        private val projectId: String,
        private val repository: RecordingRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RemixViewModel(application, projectId, repository) as T
    }
}
