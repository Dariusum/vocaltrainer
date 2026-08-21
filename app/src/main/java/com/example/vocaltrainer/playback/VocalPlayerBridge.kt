package com.example.vocaltrainer.playback

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Verbindet [PlaybackSourceRegistry]s aktive Quelle (das laufende `PlayerViewModel`) mit
 * MediaSession — dadurch können Android Auto, Bluetooth-Kopfhörer/Freisprecheinrichtungen im
 * Auto und die Medien-Benachrichtigung Play/Pause/Stop/Vor/Zurück auslösen.
 *
 * Bewusst schlank gehalten: nur die für [PlaybackSource] relevanten Befehle werden unterstützt
 * (kein Sitzplatz-Seeking innerhalb eines Titels, kein Shuffle/Repeat) — [LivePlaybackEngine]
 * unterstützt ohnehin nur Play/Pause/Stop/Von-vorne/Loop, kein beliebiges Positionsseeking.
 *
 * Vor/Zurück nutzt bewusst die *echte* Warteschlange als [SimpleBasePlayer]-Playlist (nicht nur
 * zwei Boolean-Flags): SimpleBasePlayer berechnet den Ziel-Index für
 * `COMMAND_SEEK_TO_NEXT`/`_PREVIOUS` intern selbst anhand der über [State.Builder.setPlaylist]
 * gemeldeten Titel-Liste, bevor es [handleSeek] aufruft — ohne echte Playlist würden
 * Hardware-Tasten für Vor/Zurück schlicht nichts auslösen.
 */
@OptIn(UnstableApi::class)
class VocalPlayerBridge : SimpleBasePlayer(Looper.getMainLooper()) {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + Job())

    init {
        // Manuelles "flatMapLatest": bei jedem Wechsel der aktiven Quelle (z.B. Fragment-
        // Neuerzeugung erzeugt ein neues PlayerViewModel) wird die bisherige state-Beobachtung
        // abgebrochen und eine neue für die neue Quelle gestartet. Bewusst ohne
        // flow.flatMapLatest/collectLatest (in der hier verwendeten Coroutines-Version nur
        // experimentell verfügbar) — die Logik ist einfach genug, um sie direkt nachzubauen.
        scope.launch {
            var sourceJob: Job? = null
            PlaybackSourceRegistry.active.collect { source ->
                sourceJob?.cancel()
                sourceJob = source?.let { s ->
                    launch { s.state.collect { invalidateState() } }
                }
                invalidateState()
            }
        }
    }

    override fun getState(): State {
        val s = PlaybackSourceRegistry.active.value?.state?.value ?: PlaybackSourceState()

        val commands = Player.Commands.Builder()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_STOP)
            .add(Player.COMMAND_PREPARE)
            .add(Player.COMMAND_RELEASE)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .apply {
                if (s.queueIndex > 0) {
                    add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                }
                if (s.queueIndex < s.queueTitles.size - 1) {
                    add(Player.COMMAND_SEEK_TO_NEXT)
                    add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                }
            }
            .build()

        val playlist = s.queueTitles.mapIndexed { index, title ->
            MediaItemData.Builder(index)
                .setMediaItem(
                    MediaItem.Builder()
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                        .build()
                )
                .build()
        }

        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaybackState(if (playlist.isEmpty()) Player.STATE_IDLE else Player.STATE_READY)
            .setPlayWhenReady(s.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(s.queueIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0)))
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val source = PlaybackSourceRegistry.active.value ?: return Futures.immediateVoidFuture()
        if (playWhenReady) source.play() else source.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        PlaybackSourceRegistry.active.value?.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        PlaybackSourceRegistry.active.value?.seekToQueueIndex(mediaItemIndex)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateVoidFuture()
    }
}
