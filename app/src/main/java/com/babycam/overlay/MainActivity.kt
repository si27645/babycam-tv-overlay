@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.babycam.overlay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Control panel for the overlay: the saved camera list (add/edit/delete, each with its own
 * ONVIF-discovery and test-connection flow), overlay appearance, automation and permissions.
 * Fields are plain vertically-stacked Views so D-pad focus traversal on a TV remote works
 * without extra wiring.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore

    private lateinit var overlayStatusText: TextView
    private lateinit var cameraListContainer: LinearLayout
    private lateinit var groupLayoutMode: RadioGroup
    private lateinit var groupRotationInterval: RadioGroup
    private lateinit var groupPosition: RadioGroup
    private lateinit var groupSize: RadioGroup
    private lateinit var groupOpacity: RadioGroup
    private lateinit var switchMute: SwitchCompat
    private lateinit var switchAutostart: SwitchCompat
    private lateinit var overlayPermissionStatus: TextView
    private lateinit var notificationPermissionStatus: TextView
    private lateinit var btnGrantNotificationPermission: Button

    /** True once the camera list, layout mode, or rotation interval changed since the overlay was last (re)started. */
    private var camerasDirty = false

    private var testPlayer: ExoPlayer? = null
    private val testHandler = Handler(Looper.getMainLooper())

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshPermissionStatus()
        }
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissionStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = SettingsStore(this)

        bindViews()
        populateOptionGroups()
        loadSettingsIntoForm()
        wireActions()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
        refreshCameraList()
        overlayStatusText.text =
            if (settings.overlayEnabled) getString(R.string.status_overlay_running)
            else getString(R.string.status_overlay_stopped)
    }

    private fun bindViews() {
        overlayStatusText = findViewById(R.id.overlay_status_text)
        cameraListContainer = findViewById(R.id.camera_list_container)
        groupLayoutMode = findViewById(R.id.group_layout_mode)
        groupRotationInterval = findViewById(R.id.group_rotation_interval)
        groupPosition = findViewById(R.id.group_position)
        groupSize = findViewById(R.id.group_size)
        groupOpacity = findViewById(R.id.group_opacity)
        switchMute = findViewById(R.id.switch_mute)
        switchAutostart = findViewById(R.id.switch_autostart)
        overlayPermissionStatus = findViewById(R.id.overlay_permission_status)
        notificationPermissionStatus = findViewById(R.id.notification_permission_status)
        btnGrantNotificationPermission = findViewById(R.id.btn_grant_notification_permission)
    }

    /** Builds the option RadioGroups from enums/constants so those stay the single source of truth. */
    private fun populateOptionGroups() {
        LayoutMode.entries.forEach { mode -> groupLayoutMode.addView(radioButtonFor(mode.label, mode.ordinal)) }
        ROTATION_INTERVAL_OPTIONS.forEachIndexed { index, seconds ->
            groupRotationInterval.addView(radioButtonFor(getString(R.string.rotation_interval_option, seconds), index))
        }
        OverlayPosition.entries.forEach { position -> groupPosition.addView(radioButtonFor(position.label, position.ordinal)) }
        OverlaySize.entries.forEach { size -> groupSize.addView(radioButtonFor(size.label, size.ordinal)) }
        OverlayOpacity.entries.forEach { opacity -> groupOpacity.addView(radioButtonFor(opacity.label, opacity.ordinal)) }
    }

    private fun radioButtonFor(label: String, id: Int): RadioButton = RadioButton(this).apply {
        this.id = id
        text = label
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun loadSettingsIntoForm() {
        groupLayoutMode.check(settings.layoutMode.ordinal)
        groupRotationInterval.check(ROTATION_INTERVAL_OPTIONS.indexOf(settings.rotationIntervalSeconds).coerceAtLeast(0))
        groupPosition.check(settings.position.ordinal)
        groupSize.check(settings.size.ordinal)
        groupOpacity.check(settings.opacity.ordinal)
        switchMute.isChecked = !settings.muted
        switchAutostart.isChecked = settings.autoStartOnBoot
        refreshCameraList()
        camerasDirty = false
    }

    private fun wireActions() {
        findViewById<Button>(R.id.btn_add_camera).setOnClickListener { showCameraDialog(null) }
        findViewById<Button>(R.id.btn_grant_overlay_permission).setOnClickListener { requestOverlayPermission() }
        btnGrantNotificationPermission.setOnClickListener { requestNotificationPermission() }
        findViewById<Button>(R.id.btn_save_start).setOnClickListener { saveAndStart() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener { stopOverlay() }
    }

    // ---- Camera list ---------------------------------------------------------------------

    private fun refreshCameraList() {
        cameraListContainer.removeAllViews()
        val cameras = settings.cameras
        if (cameras.isEmpty()) {
            cameraListContainer.addView(TextView(this).apply {
                text = getString(R.string.no_cameras_yet)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 13f
            })
            return
        }
        cameras.forEach { profile -> cameraListContainer.addView(buildCameraRow(profile)) }
    }

    private fun buildCameraRow(profile: CameraProfile): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
        }

        val label = profile.name.ifBlank { getString(R.string.unnamed_camera) }
        val nameText = TextView(this).apply {
            text = if (profile.enabled) label else getString(R.string.camera_disabled_label, label)
            setTextColor(ContextCompat.getColor(this@MainActivity, if (profile.enabled) R.color.text_primary else R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        row.addView(nameText)
        row.addView(smallButton(getString(R.string.btn_edit)) { showCameraDialog(profile) })
        row.addView(smallButton(getString(R.string.btn_delete)) { confirmDeleteCamera(profile) })
        return row
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
        setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.surface_dark))
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(12), dp(6), dp(12), dp(6))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8)
        }
        setOnClickListener { onClick() }
    }

    private fun confirmDeleteCamera(profile: CameraProfile) {
        val label = profile.name.ifBlank { getString(R.string.unnamed_camera) }
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_delete_title)
            .setMessage(getString(R.string.confirm_delete_message, label))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                settings.cameras = settings.cameras.filterNot { it.id == profile.id }
                camerasDirty = true
                refreshCameraList()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showCameraDialog(existing: CameraProfile?) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_camera_editor, null)
        val inputName = dialogView.findViewById<EditText>(R.id.dialog_input_name)
        val inputUrl = dialogView.findViewById<EditText>(R.id.dialog_input_url)
        val inputUsername = dialogView.findViewById<EditText>(R.id.dialog_input_username)
        val inputPassword = dialogView.findViewById<EditText>(R.id.dialog_input_password)
        val checkEnabled = dialogView.findViewById<CheckBox>(R.id.dialog_check_enabled)
        val btnDiscover = dialogView.findViewById<Button>(R.id.dialog_btn_discover)
        val discoverStatus = dialogView.findViewById<TextView>(R.id.dialog_discover_status)
        val discoverResults = dialogView.findViewById<LinearLayout>(R.id.dialog_discover_results)
        val btnTest = dialogView.findViewById<Button>(R.id.dialog_btn_test)
        val testStatus = dialogView.findViewById<TextView>(R.id.dialog_test_status)

        if (existing != null) {
            inputName.setText(existing.name)
            inputUrl.setText(existing.url)
            inputUsername.setText(existing.username)
            inputPassword.setText(existing.password)
            checkEnabled.isChecked = existing.enabled
        } else {
            checkEnabled.isChecked = true
        }

        btnDiscover.setOnClickListener {
            discoverStatus.text = getString(R.string.discovering_cameras)
            discoverResults.removeAllViews()
            Thread {
                val found = OnvifDiscovery.probe(this)
                runOnUiThread {
                    if (found.isEmpty()) {
                        discoverStatus.text = getString(R.string.no_cameras_found)
                    } else {
                        discoverStatus.text = getString(R.string.cameras_found, found.size)
                        found.forEach { device ->
                            discoverResults.addView(TextView(this).apply {
                                text = "${device.displayName}  (${device.ipAddress})"
                                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                                setPadding(0, dp(6), 0, dp(6))
                                setOnClickListener { onDiscoveredCameraPicked(device, inputName, inputUrl, inputUsername, inputPassword, discoverStatus) }
                            })
                        }
                    }
                }
            }.start()
        }

        btnTest.setOnClickListener {
            val url = inputUrl.text.toString().trim()
            if (url.isBlank()) {
                testStatus.text = getString(R.string.validation_missing_url)
                return@setOnClickListener
            }
            val authenticatedUrl = CameraProfile(
                name = "", url = url,
                username = inputUsername.text.toString(),
                password = inputPassword.text.toString()
            ).authenticatedUrl()
            runConnectionTest(authenticatedUrl, testStatus)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.dialog_title_add_camera else R.string.dialog_title_edit_camera)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_save, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()

        dialog.setOnDismissListener { releaseTestPlayer() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = inputUrl.text.toString().trim()
                if (url.isBlank()) {
                    testStatus.text = getString(R.string.validation_missing_url)
                    return@setOnClickListener
                }
                val profile = CameraProfile(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    name = inputName.text.toString().trim().ifBlank { getString(R.string.unnamed_camera) },
                    url = url,
                    username = inputUsername.text.toString(),
                    password = inputPassword.text.toString(),
                    enabled = checkEnabled.isChecked
                )
                val updated = settings.cameras.toMutableList()
                val index = updated.indexOfFirst { it.id == profile.id }
                if (index >= 0) updated[index] = profile else updated.add(profile)
                settings.cameras = updated
                camerasDirty = true
                refreshCameraList()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun onDiscoveredCameraPicked(
        device: OnvifDiscovery.DiscoveredCamera,
        inputName: EditText,
        inputUrl: EditText,
        inputUsername: EditText,
        inputPassword: EditText,
        discoverStatus: TextView
    ) {
        if (inputName.text.isNullOrBlank()) inputName.setText(device.displayName)
        discoverStatus.text = getString(R.string.resolving_stream_uri)
        val username = inputUsername.text.toString()
        val password = inputPassword.text.toString()
        Thread {
            val uri = OnvifDiscovery.fetchStreamUri(device.xAddr, username, password)
            runOnUiThread {
                if (uri != null) {
                    inputUrl.setText(uri)
                    discoverStatus.text = getString(R.string.stream_uri_resolved)
                } else {
                    inputUrl.setText(getString(R.string.rtsp_fallback_url, device.ipAddress))
                    discoverStatus.text = getString(R.string.stream_uri_not_resolved)
                }
            }
        }.start()
    }

    // ---- Permissions -------------------------------------------------------------------

    private fun refreshPermissionStatus() {
        val overlayGranted = canDrawOverlaysCompat(this)
        overlayPermissionStatus.text =
            if (overlayGranted) getString(R.string.status_granted) else getString(R.string.status_not_granted)
        overlayPermissionStatus.setTextColor(
            ContextCompat.getColor(this, if (overlayGranted) R.color.success else R.color.danger)
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            notificationPermissionStatus.text =
                if (granted) getString(R.string.status_granted) else getString(R.string.status_not_granted)
            notificationPermissionStatus.setTextColor(
                ContextCompat.getColor(this, if (granted) R.color.success else R.color.danger)
            )
            btnGrantNotificationPermission.isEnabled = !granted
        } else {
            notificationPermissionStatus.text = getString(R.string.status_not_required)
            notificationPermissionStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnGrantNotificationPermission.isEnabled = false
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        overlayPermissionLauncher.launch(intent)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ---- Connection test (shared by the camera dialog) -----------------------------------

    private fun runConnectionTest(url: String, statusView: TextView) {
        releaseTestPlayer()
        statusView.text = getString(R.string.test_connecting)

        val player = RtspPlayerFactory.createPlayer(this)
        testPlayer = player

        val timeoutRunnable = Runnable {
            statusView.text = getString(R.string.test_timeout)
            releaseTestPlayer()
        }

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    testHandler.removeCallbacks(timeoutRunnable)
                    statusView.text = getString(R.string.test_success)
                    testHandler.postDelayed({ releaseTestPlayer() }, 300)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                testHandler.removeCallbacks(timeoutRunnable)
                statusView.text = getString(R.string.test_failed, error.errorCodeName)
                releaseTestPlayer()
            }
        })

        player.setMediaSource(RtspPlayerFactory.createMediaSource(url))
        player.prepare()
        testHandler.postDelayed(timeoutRunnable, TEST_TIMEOUT_MS)
    }

    private fun releaseTestPlayer() {
        testPlayer?.release()
        testPlayer = null
    }

    // ---- Save / start / stop --------------------------------------------------------------

    private fun saveAndStart() {
        if (settings.enabledCameras().isEmpty()) {
            overlayStatusText.text = getString(R.string.status_no_cameras)
            return
        }

        val newLayoutMode = LayoutMode.entries[groupLayoutMode.checkedRadioButtonId.coerceAtLeast(0)]
        val newRotationInterval = ROTATION_INTERVAL_OPTIONS[groupRotationInterval.checkedRadioButtonId.coerceAtLeast(0)]
        val streamRelatedChanged = camerasDirty ||
            newLayoutMode != settings.layoutMode ||
            newRotationInterval != settings.rotationIntervalSeconds

        settings.layoutMode = newLayoutMode
        settings.rotationIntervalSeconds = newRotationInterval

        if (!canDrawOverlaysCompat(this)) {
            requestOverlayPermission()
            return
        }

        val wasRunning = settings.overlayEnabled

        settings.position = OverlayPosition.entries[groupPosition.checkedRadioButtonId.coerceAtLeast(0)]
        settings.size = OverlaySize.entries[groupSize.checkedRadioButtonId.coerceAtLeast(0)]
        settings.opacity = OverlayOpacity.entries[groupOpacity.checkedRadioButtonId.coerceAtLeast(0)]
        settings.muted = !switchMute.isChecked
        settings.autoStartOnBoot = switchAutostart.isChecked

        val intent = Intent(this, OverlayService::class.java).apply {
            action = when {
                streamRelatedChanged -> OverlayService.ACTION_RESTART_STREAM
                wasRunning -> OverlayService.ACTION_REFRESH_SETTINGS
                else -> null
            }
        }
        ContextCompat.startForegroundService(this, intent)
        camerasDirty = false

        overlayStatusText.text = getString(R.string.status_overlay_running)
    }

    private fun stopOverlay() {
        startService(Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        })
        overlayStatusText.text = getString(R.string.status_overlay_stopped)
    }

    override fun onDestroy() {
        releaseTestPlayer()
        testHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val TEST_TIMEOUT_MS = 8_000L
        private val ROTATION_INTERVAL_OPTIONS = listOf(15, 30, 60)
    }
}
