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
import android.util.Log
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

    private class CameraSlot(var cell: CameraCell) {
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

    // ---- Doorbell trigger (MQTT) state -----------------------------------------------------
    // Two selectable display modes (SettingsStore.doorbellDisplayMode) since which one actually
    // renders smoothly depends entirely on the TV box's hardware - see README "Doorbell trigger".
    //
    // TAKEOVER: doorbellSlot aliases an existing main-overlay slot (slots[0] in SINGLE_ROTATE,
    // or slots.last() when GRID is already full) - only ever one concurrent video pipeline.
    // GRID with room to spare instead adds a genuinely extra tile (doorbellIsExtraTile=true).
    //
    // SEPARATE_WINDOW: doorbellSlot belongs to its own independent WindowManager window
    // (doorbellOverlayView/doorbellWindowParams), never touching the main overlay at all - two
    // concurrent video pipelines, which not all hardware can actually composite.
    //
    // A dedicated doorbellHandler (not the main mainHandler) owns both modes' revert/rotation
    // timers, so releaseAllSlots() rebuilding the *main* overlay can't accidentally cancel an
    // in-progress trigger; activeDoorbellMode remembers which mode a trigger actually started in,
    // in case the setting is changed while it's still showing.
    private var mqttClient: MqttDoorbellClient? = null
    private val doorbellHandler = Handler(Looper.getMainLooper())
    private var activeDoorbellMode: DoorbellDisplayMode? = null
    private var doorbellSlot: CameraSlot? = null
    private var doorbellIsExtraTile = false
    private var replacedSlotProfile: CameraProfile? = null
    private var doorbellOverlayView: View? = null
    private var doorbellWindowParams: WindowManager.LayoutParams? = null
    /** The (possibly single-camera) set being shown for the current trigger, and where in it we are. */
    private var doorbellRotationQueue: List<CameraProfile> = emptyList()
    private var doorbellRotationIndex = 0

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startMqttClient()
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
                    val volume = if (settings.muted) 0f else 1f
                    slots.forEach { it.player?.volume = volume }
                    doorbellSlot?.player?.volume = volume
                    return START_STICKY
                }
                // Overlay not showing yet - fall through and do a full start instead.
            }

            ACTION_RESTART_STREAM -> {
                ensureForegroundAndOverlay()
                rebuildSlotsAndStart()
                return START_STICKY
            }

            ACTION_REFRESH_DOORBELL -> {
                startMqttClient()
                applyDoorbellLayout()
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
        // Only create the main overlay window if there's actually something to show in it - a
        // doorbell-only setup (no main cameras enabled) has no use for an empty bordered window
        // sitting on screen. TAKEOVER-mode doorbell triggers correctly no-op without one (there's
        // nothing to take over); SEPARATE_WINDOW-mode triggers open their own window regardless.
        if (!overlayAttached && settings.enabledCameras().isNotEmpty()) {
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

        val cameraList = settings.cameras
        val enabled = settings.enabledCameras(cameraList)
        if (enabled.isEmpty()) {
            // Nothing to show in the main overlay - tear its window down if it's still up from
            // before (e.g. the user just disabled their last main camera), but keep the service
            // itself (and MQTT) alive if doorbell listening is still configured.
            if (overlayAttached && overlayView.isAttachedToWindow) {
                windowManager.removeView(overlayView)
            }
            overlayAttached = false
            if (!settings.doorbellConfigured(cameraList)) {
                stopSelf()
            }
            return
        }

        if (!overlayAttached) {
            // Shouldn't happen: every call site calls ensureForegroundAndOverlay() immediately
            // beforehand, which already creates the window under this same "enabled is
            // non-empty" condition. Real fallback, not the normal path - if this ever fires it
            // means that contract broke, so it's worth knowing about rather than silently
            // relying on it.
            Log.w(TAG, "rebuildSlotsAndStart(): overlay wasn't attached - ensureForegroundAndOverlay() should have created it")
            showOverlay()
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
        // Inflated from XML (rather than `PlayerView(this)`) specifically to pick up
        // surface_type="texture_view" - see overlay_player_cell.xml for why.
        val playerView = LayoutInflater.from(this)
            .inflate(R.layout.overlay_player_cell, container, false) as PlayerView
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
        // A TAKEOVER-mode doorbell trigger aliases a slot just released above (or is a genuine
        // extra grid tile never added to `slots`) - either way it's now invalid and must be torn
        // down too. A SEPARATE_WINDOW-mode trigger is fully independent of the main overlay and
        // is deliberately left alone here even while the main overlay rebuilds.
        if (activeDoorbellMode == DoorbellDisplayMode.TAKEOVER) {
            if (doorbellIsExtraTile) {
                doorbellSlot?.player?.release()
            }
            doorbellSlot = null
            doorbellIsExtraTile = false
            replacedSlotProfile = null
            activeDoorbellMode = null
            doorbellHandler.removeCallbacksAndMessages(null)
            doorbellRotationQueue = emptyList()
            doorbellRotationIndex = 0
        }
    }

    // ---- Doorbell trigger (MQTT) ------------------------------------------------------------

    private fun startMqttClient() {
        stopMqttClient()
        if (!settings.mqttEnabled) return
        val newClient = MqttDoorbellClient(
            host = settings.mqttHost,
            port = settings.mqttPort,
            useTls = settings.mqttUseTls,
            username = settings.mqttUsername,
            password = settings.mqttPassword,
            topic = settings.mqttTopic,
            onTriggered = { onDoorbellMessageReceived() }
        )
        mqttClient = newClient
        newClient.start()
    }

    private fun stopMqttClient() {
        mqttClient?.stop()
        mqttClient = null
    }

    /** Called on Paho's own callback thread - hop to the main thread before touching any Views. */
    private fun onDoorbellMessageReceived() {
        doorbellHandler.post { handleDoorbellTrigger() }
    }

    private fun handleDoorbellTrigger() {
        if (isDestroyed) return
        val profiles = settings.doorbellCameras()
        if (profiles.isEmpty()) return

        doorbellHandler.removeCallbacksAndMessages(null)

        val mode = settings.doorbellDisplayMode
        if (activeDoorbellMode != null && activeDoorbellMode != mode) {
            // The mode setting changed while a trigger from the *old* mode was still showing -
            // tear that one down properly before starting fresh in the new mode.
            endActiveDoorbellTrigger(activeDoorbellMode!!)
        }
        activeDoorbellMode = mode

        doorbellRotationQueue = profiles
        doorbellRotationIndex = 0

        when (mode) {
            DoorbellDisplayMode.TAKEOVER -> {
                if (!overlayAttached) {
                    activeDoorbellMode = null
                    return
                }
                if (doorbellSlot == null) claimTakeoverSlot()
            }
            DoorbellDisplayMode.SEPARATE_WINDOW -> {
                if (doorbellOverlayView == null) showDoorbellOverlay()
            }
        }
        // Re-trigger while already active just restarts on the first camera and resets the
        // countdown/rotation below, rather than stacking a second overlay/window.

        doorbellSlot?.let { playCamera(it, profiles[0]) }
        if (profiles.size > 1) scheduleDoorbellRotation()

        doorbellHandler.postDelayed({ revertDoorbell() }, settings.doorbellDurationSeconds * 1000L)
    }

    /** TAKEOVER mode: claim a slot the same way regardless of layout mode - see the class-level doc comment. */
    private fun claimTakeoverSlot() {
        when (settings.layoutMode) {
            LayoutMode.SINGLE_ROTATE -> {
                val slot = slots.getOrNull(0) ?: return
                replacedSlotProfile = slot.profile
                mainHandler.removeCallbacks(rotationRunnable)
                doorbellSlot = slot
                doorbellIsExtraTile = false
            }

            LayoutMode.GRID -> {
                if (slots.size < MAX_GRID_CAMERAS) {
                    val cells = buildGridRows(slots.size + 1)
                    slots.forEachIndexed { index, slot -> reattachSlot(slot, cells[index]) }
                    doorbellSlot = CameraSlot(cells.last())
                    doorbellIsExtraTile = true
                } else {
                    val lastSlot = slots.last()
                    replacedSlotProfile = lastSlot.profile
                    doorbellSlot = lastSlot
                    doorbellIsExtraTile = false
                }
            }
        }
    }

    /** SEPARATE_WINDOW mode: opens its own floating window - separate from, and never touching, the main overlay. */
    private fun showDoorbellOverlay() {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_camera, null)
        val container = view.findViewById<LinearLayout>(R.id.overlay_grid_container)
        val cell = buildCell()
        container.addView(
            cell.container,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        doorbellSlot = CameraSlot(cell)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
        val params = WindowManager.LayoutParams(
            0, 0, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        doorbellOverlayView = view
        doorbellWindowParams = params
        applyDoorbellLayout()
        windowManager.addView(view, params)
    }

    private fun applyDoorbellLayout() {
        val view = doorbellOverlayView ?: return
        val params = doorbellWindowParams ?: return
        val metrics = resources.displayMetrics
        val width = (metrics.widthPixels * settings.doorbellSize.widthPercent).toInt()
        val height = (width * 9.0 / 16.0).toInt()

        params.width = width
        params.height = height
        params.gravity = gravityFor(settings.doorbellPosition)
        val margin = dp(16)
        params.x = margin
        params.y = margin
        view.alpha = settings.opacity.alpha

        if (view.isAttachedToWindow) {
            windowManager.updateViewLayout(view, params)
        }
    }

    private fun hideDoorbellOverlay() {
        val view = doorbellOverlayView ?: return
        doorbellSlot?.player?.release()
        doorbellSlot = null
        runCatching {
            if (view.isAttachedToWindow) windowManager.removeView(view)
        }
        doorbellOverlayView = null
        doorbellWindowParams = null
    }

    /** Splits the trigger duration evenly across the selected doorbell cameras and cycles through them. */
    private fun scheduleDoorbellRotation() {
        val perCameraMs = (settings.doorbellDurationSeconds * 1000L / doorbellRotationQueue.size)
            .coerceAtLeast(3_000L)
        doorbellHandler.postDelayed({ advanceDoorbellRotation() }, perCameraMs)
    }

    private fun advanceDoorbellRotation() {
        if (isDestroyed || doorbellRotationQueue.size <= 1) return
        val slot = doorbellSlot ?: return
        doorbellRotationIndex = (doorbellRotationIndex + 1) % doorbellRotationQueue.size
        playCamera(slot, doorbellRotationQueue[doorbellRotationIndex])
        scheduleDoorbellRotation()
    }

    private fun revertDoorbell() {
        doorbellRotationQueue = emptyList()
        val mode = activeDoorbellMode ?: return
        activeDoorbellMode = null
        endActiveDoorbellTrigger(mode)
    }

    private fun endActiveDoorbellTrigger(mode: DoorbellDisplayMode) {
        when (mode) {
            DoorbellDisplayMode.SEPARATE_WINDOW -> hideDoorbellOverlay()
            DoorbellDisplayMode.TAKEOVER -> {
                val slot = doorbellSlot ?: return
                doorbellSlot = null
                if (doorbellIsExtraTile) {
                    doorbellIsExtraTile = false
                    slot.player?.release()
                    val cells = buildGridRows(slots.size)
                    slots.forEachIndexed { index, s -> reattachSlot(s, cells[index]) }
                } else {
                    val prior = replacedSlotProfile
                    replacedSlotProfile = null
                    if (prior != null) playCamera(slot, prior)
                    if (settings.layoutMode == LayoutMode.SINGLE_ROTATE && rotationQueue.size > 1) {
                        scheduleRotation()
                    }
                }
            }
        }
    }

    /** Moves a slot's live player over to a freshly-built cell after a grid reflow, with no reconnect. */
    private fun reattachSlot(slot: CameraSlot, newCell: CameraCell) {
        val badgeVisible = slot.cell.badge.visibility
        slot.cell.playerView.player = null
        slot.cell = newCell
        newCell.playerView.player = slot.player
        newCell.badge.visibility = badgeVisible
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
        stopMqttClient()
        doorbellHandler.removeCallbacksAndMessages(null)
        hideDoorbellOverlay() // no-op unless a SEPARATE_WINDOW trigger is currently active
        releaseAllSlots() // also tears down a TAKEOVER-mode trigger, if one is currently active
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

        /** MQTT broker/doorbell settings changed - reconnect the MQTT client without touching playback. */
        const val ACTION_REFRESH_DOORBELL = "com.babycam.overlay.action.REFRESH_DOORBELL"

        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "babycam_overlay_channel"
        private const val NOTIFICATION_ID = 42
        private const val MAX_GRID_CAMERAS = 4
    }
}
