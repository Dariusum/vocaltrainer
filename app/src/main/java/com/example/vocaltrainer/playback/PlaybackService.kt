package com.example.vocaltrainer.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.vocaltrainer.MainActivity

/**
 * Hält eine [MediaSession] am Leben, solange Wiedergabe läuft oder laufen könnte — dadurch
 * bleiben Android Auto, Bluetooth-Kopfhörer/Freisprecheinrichtungen im Auto und die
 * Medien-Benachrichtigung mit dem Player verbunden, auch wenn [MainActivity] selbst gerade
 * nicht sichtbar ist (Bildschirm aus, andere App im Vordergrund). Der eigentliche
 * Wiedergabe-Zustand lebt weiterhin im Activity-weit gescopeten `PlayerViewModel` (siehe
 * [PlaybackSourceRegistry]) — dieser Service besitzt nur die Session/Notification/den
 * Foreground-Status, nicht die Audio-Engine selbst.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession = MediaSession.Builder(this, VocalPlayerBridge())
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
