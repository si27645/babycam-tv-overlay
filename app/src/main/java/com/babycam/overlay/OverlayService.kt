@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.babycam.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Foreground service that owns the always-on-top camera window. Shows either one camera at a
 * time (rotating through the enabled list on a timer) or up to four at once in a grid,
 * depending on [SettingsStore.layoutMode].
 *
 * Design note: the overlay is deliberately non-touchable/non-focusable at all times
 * (FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE). Most Android TV boxes are driven by a D-pad
 * remote with no pointer, so a floating window that could "steal" touch/focus from
 * whatever's underneath (the launcher, Netflix, YouTube...) would break normal TV usage.
 * All positioning/sizing/opacity/mute/camera-list controls instead live in MainActivity and
 * are pushed into this service via intents - see ACTION_REFRESH_SETTINGS / ACTION_RESTART_STREAM.
 */
class OverlayService : Service() {

    private data class CameraCell(
        val container: FrameLayout,
        val playerView: PlayerView,
        val badge: TextView
    )

    private class CameraSlot(val cell: CameraCell) {
        var player: ExoPlayer? = null
        var retryAttempt = 0
        var profile: CameraProfile? = null
    }

    private lateinit var windowManager: WindowManager
    private lateinit var settings: SettingsStore
    private lateinit var overlayView: View
    private lateinit var gridContainer: LinearLayout
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var overlayAttached = false

    private val slots = mutableListOf<CameraSlot>()
    private var rotationQueue: List<CameraProfile> = emptyList()
    private var rotationIndex = 0
    private val rotationRunnable = Runnable { advanceRotation() }

    private val mainHandler = Handler(Looper.getMainLooper())
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
                    slots.forEach { it.player?.volume = if (settings.muted) 0f else 1f }
                    return START_STICKY
                }
                // Overlay not showing yet - fall through and do a full start instead.
            }

            ACTION_RESTART_STREAM -> {
                ensureForegroundAndOverlay()
                rebuildSlotsAndStart()
                return START_STICKY
            }
        }

        ensureForegroundAndOverlay()
        if (slots.isEmpty()) {
            rebuildSlotsAndStart()
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
        gridContainer = overlayView.findViewById(R.id.overlay_grid_container)

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
        val height = (width * 9.0 / 16.0).toInt() // 16:9 box; each PlayerView letterboxes its real feed inside its cell

        layoutParams.width = width
        layoutParams.height = height
        layoutParams.gravity = gravityFor(settings.position)
        val margin = dp(16)
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

    // ---- Camera slots (single-rotate or grid) --------------------------------------------

    private fun rebuildSlotsAndStart() {
        releaseAllSlots()

        val enabled = settings.enabledCameras()
        if (enabled.isEmpty()) {
            stopSelf()
            return
        }

        when (settings.layoutMode) {
            LayoutMode.SINGLE_ROTATE -> {
                val cells = buildGridRows(1)
                val slot = CameraSlot(cells[0])
                slots.add(slot)
                rotationQueue = enabled
                rotationIndex = 0
                playCamera(slot, rotationQueue[0])
                if (rotationQueue.size > 1) scheduleRotation()
            }

            LayoutMode.GRID -> {
                val capped = enabled.take(MAX_GRID_CAMERAS)
                val cells = buildGridRows(capped.size)
                capped.forEachIndexed { index, profile ->
                    val slot = CameraSlot(cells[index])
                    slots.add(slot)
                    playCamera(slot, profile)
                }
            }
        }
    }

    /** Lays out [count] (1-4) equally-sized cells as rows of a vertical LinearLayout and returns them in order. */
    private fun buildGridRows(count: Int): List<CameraCell> {
        gridContainer.removeAllViews()
        val cells = mutableListOf<CameraCell>()

        fun addRow(cellsInRow: Int) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            repeat(cellsInRow) {
                val cell = buildCell()
                cell.container.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(dp(1), dp(1), dp(1), dp(1))
                }
                row.addView(cell.container)
                cells.add(cell)
            }
            gridContainer.addView(row)
        }

        when (count.coerceAtLeast(1)) {
            1 -> addRow(1)
            2 -> addRow(2)
            3 -> { addRow(2); addRow(1) }
            else -> { addRow(2); addRow(2) }
        }
        return cells
    }

    private fun buildCell(): CameraCell {
        val container = FrameLayout(this)
        val playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val badge = TextView(this).apply {
            text = getString(R.string.overlay_reconnecting)
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.reconnect_badge_background)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            textSize = 12f
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        }
        container.addView(playerView)
        container.addView(badge)
        return CameraCell(container, playerView, badge)
    }

    private fun playCamera(slot: CameraSlot, profile: CameraProfile) {
        slot.player?.release()
        slot.profile = profile
        slot.retryAttempt = 0
        slot.cell.badge.visibility = View.GONE

        val url = profile.authenticatedUrl()
        if (url.isBlank()) return

        val exoPlayer = RtspPlayerFactory.createPlayer(this)
        slot.player = exoPlayer
        slot.cell.playerView.player = exoPlayer
        exoPlayer.volume = if (settings.muted) 0f else 1f

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    slot.retryAttempt = 0
                    slot.cell.badge.visibility = View.GONE
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                scheduleReconnect(slot)
            }
        })

        exoPlayer.setMediaSource(RtspPlayerFactory.createMediaSource(url))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    private fun scheduleReconnect(slot: CameraSlot) {
        if (isDestroyed) return
        slot.cell.badge.visibility = View.VISIBLE
        val delayMs = minOf(5_000L * (slot.retryAttempt + 1), 30_000L)
        slot.retryAttempt++
        val profile = slot.profile ?: return
        mainHandler.postDelayed({
            if (!isDestroyed) playCamera(slot, profile)
        }, delayMs)
    }

    private fun scheduleRotation() {
        mainHandler.postDelayed(rotationRunnable, settings.rotationIntervalSeconds * 1000L)
    }

    private fun advanceRotation() {
        if (isDestroyed || slots.isEmpty() || rotationQueue.size <= 1) return
        rotationIndex = (rotationIndex + 1) % rotationQueue.size
        playCamera(slots[0], rotationQueue[rotationIndex])
        scheduleRotation()
    }

    private fun releaseAllSlots() {
        mainHandler.removeCallbacksAndMessages(null)
        slots.forEach { it.player?.release() }
        slots.clear()
    }

    // ---- Notification ----------------------------------------------------------------------

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
        releaseAllSlots()
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

        /** The camera list, layout mode, or rotation interval changed - tear down and rebuild playback. */
        const val ACTION_RESTART_STREAM = "com.babycam.overlay.action.RESTART_STREAM"

        private const val CHANNEL_ID = "babycam_overlay_channel"
        private const val NOTIFICATION_ID = 42
        private const val MAX_GRID_CAMERAS = 4
    }
}
