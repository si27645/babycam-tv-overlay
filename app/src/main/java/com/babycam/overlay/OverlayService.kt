package com.babycam.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Foreground service that owns the always-on-top camera window.
 *
 * Design note: the overlay is deliberately non-touchable/non-focusable at all times
 * (FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE). Most Android TV boxes are driven by a D-pad
 * remote with no pointer, so a floating window that could "steal" touch/focus from
 * whatever's underneath (the launcher, Netflix, YouTube...) would break normal TV usage.
 * All positioning/sizing/opacity/mute controls instead live in MainActivity and are pushed
 * into this service via intents - see ACTION_REFRESH_SETTINGS / ACTION_RESTART_STREAM.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var settings: SettingsStore
    private lateinit var overlayView: View
    private lateinit var playerView: PlayerView
    private lateinit var reconnectBadge: View
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var overlayAttached = false

    private var player: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var retryAttempt = 0
    private var isDestroyed = false

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_REFRESH_SETTINGS -> {
                if (overlayAttached) {
                    applyLayout()
                    player?.volume = if (settings.muted) 0f else 1f
                    return START_STICKY
                }
                // Overlay not showing yet - fall through and do a full start instead.
            }

            ACTION_RESTART_STREAM -> {
                ensureForegroundAndOverlay()
                startPlayback()
                return START_STICKY
            }
        }

        ensureForegroundAndOverlay()
        if (player == null) {
            startPlayback()
        }
        settings.overlayEnabled = true
        return START_STICKY
    }

    private fun ensureForegroundAndOverlay() {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!overlayAttached) {
            showOverlay()
        }
    }

    private fun showOverlay() {
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_camera, null)
        playerView = overlayView.findViewById(R.id.overlay_player_view)
        reconnectBadge = overlayView.findViewById(R.id.overlay_reconnecting)
        playerView.useController = false

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        layoutParams = WindowManager.LayoutParams(
            0,
            0,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        applyLayout()
        windowManager.addView(overlayView, layoutParams)
        overlayAttached = true
    }

    private fun applyLayout() {
        val metrics = resources.displayMetrics
        val width = (metrics.widthPixels * settings.size.widthPercent).toInt()
        val height = (width * 9.0 / 16.0).toInt() // 16:9 box; PlayerView letterboxes the real feed inside it

        layoutParams.width = width
        layoutParams.height = height
        layoutParams.gravity = gravityFor(settings.position)
        val margin = (16 * metrics.density).toInt()
        layoutParams.x = margin
        layoutParams.y = margin

        overlayView.alpha = settings.opacity.alpha

        if (overlayAttached && overlayView.isAttachedToWindow) {
            windowManager.updateViewLayout(overlayView, layoutParams)
        }
    }

    private fun gravityFor(position: OverlayPosition): Int = when (position) {
        OverlayPosition.TOP_START -> Gravity.TOP or Gravity.START
        OverlayPosition.TOP_END -> Gravity.TOP or Gravity.END
        OverlayPosition.BOTTOM_START -> Gravity.BOTTOM or Gravity.START
        OverlayPosition.BOTTOM_END -> Gravity.BOTTOM or Gravity.END
        OverlayPosition.CENTER -> Gravity.CENTER
    }

    private fun startPlayback() {
        releasePlayer()
        val url = settings.buildAuthenticatedUrl()
        if (url.isBlank()) {
            stopSelf()
            return
        }

        val exoPlayer = RtspPlayerFactory.createPlayer(this)
        player = exoPlayer
        playerView.player = exoPlayer
        exoPlayer.volume = if (settings.muted) 0f else 1f

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    retryAttempt = 0
                    reconnectBadge.visibility = View.GONE
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                scheduleReconnect()
            }
        })

        exoPlayer.setMediaSource(RtspPlayerFactory.createMediaSource(url))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    private fun scheduleReconnect() {
        if (isDestroyed) return
        if (::reconnectBadge.isInitialized) {
            reconnectBadge.visibility = View.VISIBLE
        }
        val delayMs = minOf(5_000L * (retryAttempt + 1), 30_000L)
        retryAttempt++
        mainHandler.postDelayed({
            if (!isDestroyed) startPlayback()
        }, delayMs)
    }

    private fun releasePlayer() {
        mainHandler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_camera_notification)
            .setContentIntent(openPendingIntent)
            .addAction(0, getString(R.string.notification_action_stop), stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        isDestroyed = true
        releasePlayer()
        if (overlayAttached && overlayView.isAttachedToWindow) {
            windowManager.removeView(overlayView)
        }
        overlayAttached = false
        settings.overlayEnabled = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** Stop the overlay and this service entirely. */
        const val ACTION_STOP = "com.babycam.overlay.action.STOP"

        /** Re-apply position/size/opacity/mute to an already-running overlay without touching playback. */
        const val ACTION_REFRESH_SETTINGS = "com.babycam.overlay.action.REFRESH_SETTINGS"

        /** The RTSP URL/credentials changed - tear down and rebuild playback. */
        const val ACTION_RESTART_STREAM = "com.babycam.overlay.action.RESTART_STREAM"

        private const val CHANNEL_ID = "babycam_overlay_channel"
        private const val NOTIFICATION_ID = 42
    }
}
