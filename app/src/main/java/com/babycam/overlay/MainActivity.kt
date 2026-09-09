@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.babycam.overlay

import android.Manifest
import android.content.ActivityNotFoundException
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
import android.widget.Toast
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

    private lateinit var switchMqttEnabled: SwitchCompat
    private lateinit var inputMqttHost: EditText
    private lateinit var inputMqttPort: EditText
    private lateinit var switchMqttTls: SwitchCompat
    private lateinit var inputMqttUsername: EditText
    private lateinit var inputMqttPassword: EditText
    private lateinit var inputMqttTopic: EditText
    private lateinit var mqttTestStatus: TextView
    private lateinit var doorbellCameraContainer: LinearLayout
    private lateinit var groupDoorbellDuration: RadioGroup
    /** One checkbox per saved camera, multi-select: pick one for a static feed, several to rotate through them. */
    private var doorbellCameraCheckboxes: List<Pair<CameraProfile, CheckBox>> = emptyList()

    /** True once the camera list, layout mode, or rotation interval changed since the overlay was last (re)started. */
    private var camerasDirty = false

    private var testPlayer: ExoPlayer? = null
    private var testMqttClient: MqttDoorbellClient? = null
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

        switchMqttEnabled = findViewById(R.id.switch_mqtt_enabled)
        inputMqttHost = findViewById(R.id.input_mqtt_host)
        inputMqttPort = findViewById(R.id.input_mqtt_port)
        switchMqttTls = findViewById(R.id.switch_mqtt_tls)
        inputMqttUsername = findViewById(R.id.input_mqtt_username)
        inputMqttPassword = findViewById(R.id.input_mqtt_password)
        inputMqttTopic = findViewById(R.id.input_mqtt_topic)
        mqttTestStatus = findViewById(R.id.mqtt_test_status)
        doorbellCameraContainer = findViewById(R.id.group_doorbell_camera)
        groupDoorbellDuration = findViewById(R.id.group_doorbell_duration)
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
        DOORBELL_DURATION_OPTIONS.forEachIndexed { index, seconds ->
            groupDoorbellDuration.addView(radioButtonFor(getString(R.string.rotation_interval_option, seconds), index))
        }
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

        switchMqttEnabled.isChecked = settings.mqttEnabled
        inputMqttHost.setText(settings.mqttHost)
        inputMqttPort.setText(settings.mqttPort.toString())
        switchMqttTls.isChecked = settings.mqttUseTls
        inputMqttUsername.setText(settings.mqttUsername)
        inputMqttPassword.setText(settings.mqttPassword)
        inputMqttTopic.setText(settings.mqttTopic)
        groupDoorbellDuration.check(DOORBELL_DURATION_OPTIONS.indexOf(settings.doorbellDurationSeconds).coerceAtLeast(0))

        refreshCameraList()
        camerasDirty = false
    }

    /**
     * Rebuilds the doorbell-camera checkbox list from the current camera list and restores the
     * saved selection. Multi-select: one checked -> shown statically when triggered, several ->
     * rotated through for the trigger duration.
     */
    private fun refreshDoorbellCameraPicker() {
        doorbellCameraContainer.removeAllViews()
        val cameras = settings.cameras
        if (cameras.isEmpty()) {
            doorbellCameraContainer.addView(TextView(this).apply {
                text = getString(R.string.no_cameras_for_doorbell)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 13f
            })
            doorbellCameraCheckboxes = emptyList()
            return
        }
        val savedIds = settings.doorbellCameraIds
        doorbellCameraCheckboxes = cameras.map { profile ->
            val checkbox = CheckBox(this).apply {
                text = profile.name.ifBlank { getString(R.string.unnamed_camera) }
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                isChecked = profile.id in savedIds
            }
            doorbellCameraContainer.addView(checkbox)
            profile to checkbox
        }
    }

    private fun selectedDoorbellCameraIds(): Set<String> =
        doorbellCameraCheckboxes.filter { (_, checkbox) -> checkbox.isChecked }
            .mapTo(LinkedHashSet()) { (profile, _) -> profile.id }

    private fun wireActions() {
        findViewById<Button>(R.id.btn_add_camera).setOnClickListener { showCameraDialog(null) }
        findViewById<Button>(R.id.btn_grant_overlay_permission).setOnClickListener { requestOverlayPermission() }
        btnGrantNotificationPermission.setOnClickListener { requestNotificationPermission() }
        findViewById<Button>(R.id.btn_test_mqtt).setOnClickListener { testMqttConnection() }
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
        } else {
            cameras.forEach { profile -> cameraListContainer.addView(buildCameraRow(profile)) }
        }
        refreshDoorbellCameraPicker()
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
        val enabledCheckbox = CheckBox(this).apply {
            isChecked = profile.enabled
            setOnClickListener { setCameraEnabled(profile, isChecked) }
        }
        val nameText = TextView(this).apply {
            text = label
            setTextColor(ContextCompat.getColor(this@MainActivity, if (profile.enabled) R.color.text_primary else R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4)
            }
        }

        row.addView(enabledCheckbox)
        row.addView(nameText)
        row.addView(smallButton(getString(R.string.btn_edit)) { showCameraDialog(profile) })
        row.addView(smallButton(getString(R.string.btn_delete)) { confirmDeleteCamera(profile) })
        return row
    }

    /** Quick on/off toggle for whether a camera shows in the normal overlay (single-feed/grid), without opening its edit dialog. */
    private fun setCameraEnabled(profile: CameraProfile, enabled: Boolean) {
        settings.cameras = settings.cameras.map { if (it.id == profile.id) it.copy(enabled = enabled) else it }
        camerasDirty = true
        refreshCameraList()
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
            // Default a brand-new camera to enabled only if it'd be the first one; once at
            // least one camera already shows normally, further additions default to off so
            // adding a 2nd/3rd camera doesn't silently start rotating/gridding them all -
            // the user opts each one in via this checkbox or the list's inline toggle.
            checkEnabled.isChecked = settings.cameras.none { it.enabled }
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
        try {
            overlayPermissionLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            // Some Android TV builds (stock Mi Box included) don't ship a screen for this
            // intent at all. Fall back to the generic app-details screen, where the
            // permission is sometimes reachable under Permissions/Advanced instead.
            openAppDetailsAsOverlayPermissionFallback()
        }
    }

    private fun openAppDetailsAsOverlayPermissionFallback() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        try {
            overlayPermissionLauncher.launch(intent)
            Toast.makeText(this, R.string.overlay_permission_fallback_hint, Toast.LENGTH_LONG).show()
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.overlay_permission_unavailable, Toast.LENGTH_LONG).show()
        }
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

    // ---- MQTT connection test (doorbell trigger) ------------------------------------------

    private fun testMqttConnection() {
        val host = inputMqttHost.text.toString().trim()
        if (host.isBlank()) {
            mqttTestStatus.text = getString(R.string.validation_missing_url)
            return
        }
        releaseTestMqttClient()
        mqttTestStatus.text = getString(R.string.mqtt_test_connecting)

        val timeoutRunnable = Runnable {
            mqttTestStatus.text = getString(R.string.mqtt_test_timeout)
            releaseTestMqttClient()
        }

        val client = MqttDoorbellClient(
            host = host,
            port = inputMqttPort.text.toString().toIntOrNull() ?: 1883,
            useTls = switchMqttTls.isChecked,
            username = inputMqttUsername.text.toString(),
            password = inputMqttPassword.text.toString(),
            topic = inputMqttTopic.text.toString().trim().ifBlank { "babycam/doorbell" },
            onTriggered = {}
        )
        testMqttClient = client
        client.start(
            onConnected = {
                testHandler.post {
                    testHandler.removeCallbacks(timeoutRunnable)
                    mqttTestStatus.text = getString(R.string.mqtt_test_success)
                    testHandler.postDelayed({ releaseTestMqttClient() }, 300)
                }
            },
            onConnectFailed = { message ->
                testHandler.post {
                    testHandler.removeCallbacks(timeoutRunnable)
                    mqttTestStatus.text = getString(R.string.mqtt_test_failed, message)
                    releaseTestMqttClient()
                }
            }
        )
        testHandler.postDelayed(timeoutRunnable, TEST_TIMEOUT_MS)
    }

    private fun releaseTestMqttClient() {
        testMqttClient?.stop()
        testMqttClient = null
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

        val newMqttEnabled = switchMqttEnabled.isChecked
        val newMqttHost = inputMqttHost.text.toString().trim()
        val newMqttPort = inputMqttPort.text.toString().toIntOrNull() ?: 1883
        val newMqttTls = switchMqttTls.isChecked
        val newMqttUsername = inputMqttUsername.text.toString()
        val newMqttPassword = inputMqttPassword.text.toString()
        val newMqttTopic = inputMqttTopic.text.toString().trim().ifBlank { "babycam/doorbell" }
        val newDoorbellCameraIds = selectedDoorbellCameraIds()
        val newDoorbellDuration = DOORBELL_DURATION_OPTIONS[groupDoorbellDuration.checkedRadioButtonId.coerceAtLeast(0)]

        if (newMqttEnabled && (newMqttHost.isBlank() || newDoorbellCameraIds.isEmpty())) {
            overlayStatusText.text = getString(R.string.status_doorbell_incomplete)
            return
        }

        val doorbellRelatedChanged = newMqttEnabled != settings.mqttEnabled ||
            newMqttHost != settings.mqttHost ||
            newMqttPort != settings.mqttPort ||
            newMqttTls != settings.mqttUseTls ||
            newMqttUsername != settings.mqttUsername ||
            newMqttPassword != settings.mqttPassword ||
            newMqttTopic != settings.mqttTopic ||
            newDoorbellCameraIds != settings.doorbellCameraIds

        settings.layoutMode = newLayoutMode
        settings.rotationIntervalSeconds = newRotationInterval
        settings.mqttEnabled = newMqttEnabled
        settings.mqttHost = newMqttHost
        settings.mqttPort = newMqttPort
        settings.mqttUseTls = newMqttTls
        settings.mqttUsername = newMqttUsername
        settings.mqttPassword = newMqttPassword
        settings.mqttTopic = newMqttTopic
        settings.doorbellCameraIds = newDoorbellCameraIds
        settings.doorbellDurationSeconds = newDoorbellDuration

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

        if (doorbellRelatedChanged && wasRunning) {
            ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_REFRESH_DOORBELL
            })
        }

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
        releaseTestMqttClient()
        testHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val TEST_TIMEOUT_MS = 8_000L
        private val ROTATION_INTERVAL_OPTIONS = listOf(15, 30, 60)
        private val DOORBELL_DURATION_OPTIONS = listOf(10, 20, 30, 60)
    }
}
