package com.example.vocaltrainer.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.vocaltrainer.MainActivity
import com.example.vocaltrainer.R

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

        // PlayerViewModel startet diesen Service über startForegroundService(), das dem
        // System verspricht, innerhalb von 5s startForeground() aufzurufen — sonst killt das
        // System den Prozess mit einer RemoteServiceException (auf einem echten Gerät
        // reproduziert). MediaSessionServices eigene automatische Benachrichtigung reagiert
        // auf Player-Zustandsänderungen und war dafür nicht zuverlässig schnell genug (sie
        // läuft über mehrere Coroutine-Hops von PlayerViewModel bis hierher) — deshalb hier
        // sofort eine minimale eigene Benachrichtigung, unabhängig vom eigentlichen
        // Wiedergabe-Zustand. Sobald media3s eigene Erkennung nachzieht, ersetzt sie diese
        // i.d.R. durch die "echte" Medien-Benachrichtigung mit Titel/Steuerelementen.
        startForegroundImmediately()
    }

    private fun startForegroundImmediately() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.playback_notification_channel),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
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

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 4201
    }
}
