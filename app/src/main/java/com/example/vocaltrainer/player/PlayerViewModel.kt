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
import com.example.vocaltrainer.audio.SeparatedStems
import com.example.vocaltrainer.audio.TrackDecoder
import com.example.vocaltrainer.audio.VocalRecorder
import com.example.vocaltrainer.audio.VocalSeparator
import com.example.vocaltrainer.data.QueueEntry
import com.example.vocaltrainer.data.RecentTracksStore
import com.example.vocaltrainer.data.RecordingRepository
import com.example.vocaltrainer.data.StemsCache
import com.example.vocaltrainer.log.VocaltrainerLogger
import com.example.vocaltrainer.ui.widget.WaveformPeaks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
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
        val stems: SeparatedStems?
    ) : TrackUiState() {
        /** Zum Abspielen: das Instrumental, falls getrennt, sonst das unveränderte Original. */
        val instrumental: PcmAudio get() = stems?.instrumental ?: pcm
    }
    data class Error(val message: String) : TrackUiState()
}

sealed class PlayerEvent {
    data class PendingSave(val tempFile: File, val suggestedTitle: String) : PlayerEvent()
    data class NavigateToRemix(val projectId: String) : PlayerEvent()
    data class Error(val message: String) : PlayerEvent()
}

class PlayerViewModel(
    application: Application,
    private val repository: RecordingRepository,
    private val recentTracksStore: RecentTracksStore,
    private val stemsCache: StemsCache
) : AndroidViewModel(application) {

    private val livePlaybackEngine = LivePlaybackEngine()
    private var vocalRecorder: VocalRecorder? = null
    private var recordingObserverJob: Job? = null
    private var loadJob: Job? = null
    private val audioFocus = AudioFocusCoordinator(application)

    private val _trackState = MutableStateFlow<TrackUiState>(TrackUiState.Idle)
    val trackState: StateFlow<TrackUiState> = _trackState.asStateFlow()

    val playbackState: StateFlow<PlaybackState> = livePlaybackEngine.state
    val positionFrames: StateFlow<Int> = livePlaybackEngine.positionFrames

    private val _recentTracks = MutableStateFlow(recentTracksStore.getRecent())
    val recentTracks: StateFlow<List<QueueEntry>> = _recentTracks.asStateFlow()

    // Nur gesetzt, wenn die Wiedergabe aus einer Playlist oder der Schnellauswahl gestartet
    // wurde — direktes Wählen über den Datei-Picker (pickFile) bleibt bewusst ein Einzeltitel
    // ohne Vor/Zurück, wie bisher.
    private val _currentQueue = MutableStateFlow<List<QueueEntry>?>(null)
    private val _currentQueueIndex = MutableStateFlow(0)
    val hasPrevious: StateFlow<Boolean> = combine(_currentQueue, _currentQueueIndex) { queue, index ->
        queue != null && index > 0
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val hasNext: StateFlow<Boolean> = combine(_currentQueue, _currentQueueIndex) { queue, index ->
        queue != null && index < queue.size - 1
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _vocalReduction = MutableStateFlow(0f)
    val vocalReduction: StateFlow<Float> = _vocalReduction.asStateFlow()

    private val _loopEnabled = MutableStateFlow(false)
    val loopEnabled: StateFlow<Boolean> = _loopEnabled.asStateFlow()

    private val _isSeparatingVocals = MutableStateFlow(false)
    val isSeparatingVocals: StateFlow<Boolean> = _isSeparatingVocals.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _elapsedRecordingMs = MutableStateFlow(0L)
    val elapsedRecordingMs: StateFlow<Long> = _elapsedRecordingMs.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>()
    val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()

    init {
        livePlaybackEngine.finished.onEach { finished ->
            if (!finished) return@onEach
            if (_isRecording.value) {
                stopRecording()
                return@onEach
            }
            // Kein-Op, falls keine Warteschlange (oder deren Ende) erreicht ist — Wiedergabe
            // bleibt dann wie bisher einfach gestoppt.
            playNext()
        }.launchIn(viewModelScope)
    }

    fun pickFile(uri: Uri) {
        VocaltrainerLogger.i("PlayerViewModel", "Datei ausgewählt: $uri")
        _currentQueue.value = null
        _currentQueueIndex.value = 0
        loadTrack(uri, fallbackName = "Track", autoPlay = false)
    }

    /** Startet Wiedergabe einer Playlist/Schnellauswahl ab [startIndex]; aktiviert Vor/Zurück. */
    fun playFromQueue(queue: List<QueueEntry>, startIndex: Int) {
        if (startIndex !in queue.indices) return
        _currentQueue.value = queue
        _currentQueueIndex.value = startIndex
        val entry = queue[startIndex]
        loadTrack(entry.uri, fallbackName = entry.displayName, autoPlay = true)
    }

    fun playNext() {
        val queue = _currentQueue.value ?: return
        val nextIndex = _currentQueueIndex.value + 1
        if (nextIndex !in queue.indices) return
        _currentQueueIndex.value = nextIndex
        val entry = queue[nextIndex]
        loadTrack(entry.uri, fallbackName = entry.displayName, autoPlay = true)
    }

    fun playPrevious() {
        val queue = _currentQueue.value ?: return
        val prevIndex = _currentQueueIndex.value - 1
        if (prevIndex !in queue.indices) return
        _currentQueueIndex.value = prevIndex
        val entry = queue[prevIndex]
        loadTrack(entry.uri, fallbackName = entry.displayName, autoPlay = true)
    }

    private fun loadTrack(uri: Uri, fallbackName: String, autoPlay: Boolean) {
        // Die KI-Gesangstrennung dauert jetzt teils über eine Minute. Ohne Abbruch des
        // vorherigen Ladevorgangs liefen bei erneutem Antippen von "Datei wählen" während
        // des Ladens zwei Trennungen gleichzeitig — das würgt die CPU ab (Chunks wurden
        // dadurch beobachtet bis zu 6x langsamer) und der zuerst fertige Track wurde durch
        // den zweiten Durchlauf sofort wieder überschrieben ("Lied verschwindet").
        loadJob?.cancel()
        livePlaybackEngine.stop()
        _trackState.value = TrackUiState.Loading
        loadJob = viewModelScope.launch {
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

                val fileName = queryFileName(uri) ?: fallbackName
                recentTracksStore.recordPlayed(uri, fileName)
                _recentTracks.value = recentTracksStore.getRecent()

                val cached = if (pcm.channelCount == 2) stemsCache.get(uri, pcm.frameCount, pcm.sampleRate) else null
                val stems = if (cached != null) {
                    VocaltrainerLogger.i("PlayerViewModel", "Stems aus Cache geladen (Trennung übersprungen)")
                    cached
                } else if (pcm.channelCount == 2) {
                    _isSeparatingVocals.value = true
                    try {
                        VocalSeparator.separate(getApplication(), pcm).also {
                            stemsCache.put(uri, pcm.frameCount, pcm.sampleRate, it)
                        }
                    } finally {
                        _isSeparatingVocals.value = false
                    }
                } else {
                    null
                }
                val t3 = System.currentTimeMillis()
                VocaltrainerLogger.i("PlayerViewModel", "Gesangstrennung fertig in ${t3 - t2}ms (gesamt ${t3 - t0}ms)")

                _trackState.value = TrackUiState.Loaded(pcm, peaks, fileName, stems)
                _vocalReduction.value = 0f
                if (autoPlay) {
                    livePlaybackEngine.setVocalReduction(0f)
                    audioFocus.requestFocus(
                        onLoss = { livePlaybackEngine.pause() },
                        onGain = { livePlaybackEngine.resume() }
                    )
                    livePlaybackEngine.start(stems?.instrumental ?: pcm, stems?.vocal)
                }
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
                livePlaybackEngine.start(state.instrumental, state.stems?.vocal)
            }
        }
    }

    fun setVocalReduction(value: Float) {
        val state = _trackState.value
        val hasStems = state is TrackUiState.Loaded && state.stems != null
        VocaltrainerLogger.d("PlayerViewModel", "setVocalReduction($value), Stems vorhanden=$hasStems")
        _vocalReduction.value = value
        livePlaybackEngine.setVocalReduction(value)
    }

    fun setLoopEnabled(enabled: Boolean) {
        _loopEnabled.value = enabled
        livePlaybackEngine.setLoopEnabled(enabled)
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
        livePlaybackEngine.start(state.instrumental, state.stems?.vocal)
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
        livePlaybackEngine.start(state.instrumental, state.stems?.vocal, startFrame = 0)

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
        private val repository: RecordingRepository,
        private val recentTracksStore: RecentTracksStore,
        private val stemsCache: StemsCache
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlayerViewModel(application, repository, recentTracksStore, stemsCache) as T
    }
}
