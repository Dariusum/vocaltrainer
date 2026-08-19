package com.example.vocaltrainer.player

import android.annotation.SuppressLint
import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.vocaltrainer.audio.AudioFocusCoordinator
import com.example.vocaltrainer.audio.LivePlaybackEngine
import com.example.vocaltrainer.audio.PcmAudio
import com.example.vocaltrainer.audio.PlaybackState
import com.example.vocaltrainer.audio.TrackDecoder
import com.example.vocaltrainer.audio.VocalBandFilter
import com.example.vocaltrainer.audio.VocalBandSettings
import com.example.vocaltrainer.audio.VocalRecorder
import com.example.vocaltrainer.data.RecordingRepository
import com.example.vocaltrainer.log.VocaltrainerLogger
import com.example.vocaltrainer.ui.widget.WaveformPeaks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class TrackUiState {
    object Idle : TrackUiState()
    object Loading : TrackUiState()
    data class Loaded(
        val pcm: PcmAudio,
        val peaks: FloatArray,
        val fileName: String,
        val vocalBandMid: FloatArray?
    ) : TrackUiState()
    data class Error(val message: String) : TrackUiState()
}

sealed class PlayerEvent {
    data class PendingSave(val tempFile: File, val suggestedTitle: String) : PlayerEvent()
    data class NavigateToRemix(val projectId: String) : PlayerEvent()
    data class Error(val message: String) : PlayerEvent()
}

class PlayerViewModel(
    application: Application,
    private val repository: RecordingRepository
) : AndroidViewModel(application) {

    private val livePlaybackEngine = LivePlaybackEngine()
    private var vocalRecorder: VocalRecorder? = null
    private var recordingObserverJob: Job? = null
    private var bandRecomputeJob: Job? = null
    private val audioFocus = AudioFocusCoordinator(application)
    private val bandSettings = VocalBandSettings(application)

    private val _trackState = MutableStateFlow<TrackUiState>(TrackUiState.Idle)
    val trackState: StateFlow<TrackUiState> = _trackState.asStateFlow()

    val playbackState: StateFlow<PlaybackState> = livePlaybackEngine.state
    val positionFrames: StateFlow<Int> = livePlaybackEngine.positionFrames

    private val _vocalReduction = MutableStateFlow(0f)
    val vocalReduction: StateFlow<Float> = _vocalReduction.asStateFlow()

    private val _loopEnabled = MutableStateFlow(false)
    val loopEnabled: StateFlow<Boolean> = _loopEnabled.asStateFlow()

    private val _bandLowHz = MutableStateFlow(bandSettings.lowHz)
    val bandLowHz: StateFlow<Float> = _bandLowHz.asStateFlow()

    private val _bandHighHz = MutableStateFlow(bandSettings.highHz)
    val bandHighHz: StateFlow<Float> = _bandHighHz.asStateFlow()

    private val _isRecalculatingBand = MutableStateFlow(false)
    val isRecalculatingBand: StateFlow<Boolean> = _isRecalculatingBand.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _elapsedRecordingMs = MutableStateFlow(0L)
    val elapsedRecordingMs: StateFlow<Long> = _elapsedRecordingMs.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>()
    val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()

    init {
        livePlaybackEngine.finished.onEach { finished ->
            if (finished && _isRecording.value) stopRecording()
        }.launchIn(viewModelScope)
    }

    fun pickFile(uri: Uri) {
        VocaltrainerLogger.i("PlayerViewModel", "Datei ausgewählt: $uri")
        livePlaybackEngine.stop()
        _trackState.value = TrackUiState.Loading
        viewModelScope.launch {
            try {
                val t0 = System.currentTimeMillis()
                val pcm = TrackDecoder.decode(getApplication(), uri)
                val t1 = System.currentTimeMillis()
                VocaltrainerLogger.i(
                    "PlayerViewModel",
                    "Dekodiert in ${t1 - t0}ms: ${pcm.sampleRate}Hz, ${pcm.channelCount}ch, " +
                        "${pcm.frameCount} Frames, ${pcm.durationMs}ms Dauer"
                )

                val peaks = withContext(Dispatchers.Default) { WaveformPeaks.compute(pcm) }
                val t2 = System.currentTimeMillis()
                VocaltrainerLogger.i("PlayerViewModel", "Wellenform berechnet in ${t2 - t1}ms")

                val fileName = queryFileName(uri) ?: "Track"
                val vocalBandMid = if (pcm.channelCount == 2) {
                    withContext(Dispatchers.Default) {
                        VocalBandFilter.computeVocalBandMid(pcm, _bandLowHz.value, _bandHighHz.value)
                    }
                } else {
                    null
                }
                val t3 = System.currentTimeMillis()
                VocaltrainerLogger.i("PlayerViewModel", "Gesangsband berechnet in ${t3 - t2}ms (gesamt ${t3 - t0}ms)")

                _trackState.value = TrackUiState.Loaded(pcm, peaks, fileName, vocalBandMid)
                _vocalReduction.value = 0f
            } catch (e: Exception) {
                VocaltrainerLogger.e("PlayerViewModel", "Fehler beim Dekodieren von $uri", e)
                _trackState.value = TrackUiState.Error(e.message ?: "Fehler beim Laden")
            }
        }
    }

    fun togglePlayPause() {
        val state = _trackState.value
        if (state !is TrackUiState.Loaded) return
        when (playbackState.value) {
            PlaybackState.PLAYING -> livePlaybackEngine.pause()
            PlaybackState.PAUSED -> livePlaybackEngine.resume()
            PlaybackState.STOPPED -> {
                audioFocus.requestFocus(
                    onLoss = { livePlaybackEngine.pause() },
                    onGain = { livePlaybackEngine.resume() }
                )
                livePlaybackEngine.setVocalReduction(_vocalReduction.value)
                livePlaybackEngine.start(state.pcm, state.vocalBandMid)
            }
        }
    }

    fun setVocalReduction(value: Float) {
        val state = _trackState.value
        val hasBand = state is TrackUiState.Loaded && state.vocalBandMid != null
        VocaltrainerLogger.d("PlayerViewModel", "setVocalReduction($value), vocalBandMid vorhanden=$hasBand")
        _vocalReduction.value = value
        livePlaybackEngine.setVocalReduction(value)
    }

    fun setLoopEnabled(enabled: Boolean) {
        _loopEnabled.value = enabled
        livePlaybackEngine.setLoopEnabled(enabled)
    }

    /** Bandgrenzen der Gesangsreduzierung ändern — wird persistiert und für den aktuellen Track neu berechnet. */
    fun setBandRange(lowHz: Float, highHz: Float) {
        _bandLowHz.value = lowHz
        _bandHighHz.value = highHz
        bandSettings.lowHz = lowHz
        bandSettings.highHz = highHz

        val state = _trackState.value
        if (state !is TrackUiState.Loaded || state.pcm.channelCount != 2) return

        bandRecomputeJob?.cancel()
        bandRecomputeJob = viewModelScope.launch {
            delay(400)
            _isRecalculatingBand.value = true
            val newBand = withContext(Dispatchers.Default) {
                VocalBandFilter.computeVocalBandMid(state.pcm, lowHz, highHz)
            }
            val current = _trackState.value
            if (current is TrackUiState.Loaded && current.pcm === state.pcm) {
                _trackState.value = current.copy(vocalBandMid = newBand)
            }
            _isRecalculatingBand.value = false
        }
    }

    /** Startet den aktuell geladenen Track von vorne, unabhängig vom aktuellen Wiedergabestatus. */
    fun restart() {
        val state = _trackState.value
        if (state !is TrackUiState.Loaded || _isRecording.value) return
        audioFocus.requestFocus(
            onLoss = { livePlaybackEngine.pause() },
            onGain = { livePlaybackEngine.resume() }
        )
        livePlaybackEngine.setVocalReduction(_vocalReduction.value)
        livePlaybackEngine.start(state.pcm, state.vocalBandMid)
    }

    /**
     * Aufrufer (PlayerFragment) müssen RECORD_AUDIO bereits gewährt haben, bevor diese
     * Methode erreicht wird — entweder über den direkten ContextCompat-Check oder über
     * den Callback von RequestPermission(). Lint erkennt Letzteres nicht statisch,
     * daher die gezielte Unterdrückung statt eines redundanten Laufzeit-Checks hier.
     */
    @SuppressLint("MissingPermission")
    fun startRecording() {
        val state = _trackState.value
        if (state !is TrackUiState.Loaded || _isRecording.value) return
        VocaltrainerLogger.i("PlayerViewModel", "Aufnahme gestartet für ${state.fileName}")

        audioFocus.requestFocus(onLoss = { stopRecording() }, onGain = {})
        livePlaybackEngine.setVocalReduction(_vocalReduction.value)
        livePlaybackEngine.start(state.pcm, state.vocalBandMid, startFrame = 0)

        val recorder = VocalRecorder(state.pcm.sampleRate)
        vocalRecorder = recorder
        val tempFile = File.createTempFile("recording_", ".wav", getApplication<Application>().cacheDir)
        recorder.start(tempFile)
        _isRecording.value = true
        _elapsedRecordingMs.value = 0L

        recordingObserverJob = recorder.recordedFrames.onEach { frames ->
            _elapsedRecordingMs.value = frames * 1000L / state.pcm.sampleRate
        }.launchIn(viewModelScope)
    }

    fun stopRecording() {
        if (!_isRecording.value) return
        _isRecording.value = false
        recordingObserverJob?.cancel()
        recordingObserverJob = null
        livePlaybackEngine.stop()

        val recorder = vocalRecorder ?: return
        vocalRecorder = null
        val file = recorder.stop() ?: return
        VocaltrainerLogger.i("PlayerViewModel", "Aufnahme gestoppt, temporäre Datei: ${file.absolutePath}")
        viewModelScope.launch {
            _events.emit(PlayerEvent.PendingSave(file, defaultTitle()))
        }
    }

    fun saveRecording(tempFile: File, title: String) {
        val state = _trackState.value
        if (state !is TrackUiState.Loaded) return
        viewModelScope.launch {
            try {
                val project = repository.saveProject(title, state.pcm, tempFile)
                VocaltrainerLogger.i("PlayerViewModel", "Aufnahme gespeichert: ${project.id} (\"${project.title}\")")
                _events.emit(PlayerEvent.NavigateToRemix(project.id))
            } catch (e: Exception) {
                VocaltrainerLogger.e("PlayerViewModel", "Speichern fehlgeschlagen", e)
                _events.emit(PlayerEvent.Error(e.message ?: "Speichern fehlgeschlagen"))
            }
        }
    }

    fun discardRecording(tempFile: File) {
        VocaltrainerLogger.i("PlayerViewModel", "Aufnahme verworfen: ${tempFile.absolutePath}")
        tempFile.delete()
    }

    private fun defaultTitle(): String {
        val state = _trackState.value
        return if (state is TrackUiState.Loaded) state.fileName.substringBeforeLast('.') else "Aufnahme"
    }

    private fun queryFileName(uri: Uri): String? {
        val context = getApplication<Application>()
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull()
    }

    override fun onCleared() {
        super.onCleared()
        livePlaybackEngine.stop()
        vocalRecorder?.stop()
        audioFocus.abandonFocus()
    }

    class Factory(
        private val application: Application,
        private val repository: RecordingRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlayerViewModel(application, repository) as T
    }
}
