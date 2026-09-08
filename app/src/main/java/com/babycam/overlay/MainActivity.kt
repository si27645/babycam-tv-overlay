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
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Control panel for the overlay: stream credentials, appearance, automation and the
 * permissions the overlay needs. All fields are plain vertically-stacked Views so D-pad
 * focus traversal on an Android TV remote "just works" without extra wiring.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore

    private lateinit var overlayStatusText: TextView
    private lateinit var inputRtspUrl: EditText
    private lateinit var inputUsername: EditText
    private lateinit var inputPassword: EditText
    private lateinit var testStatusText: TextView
    private lateinit var groupPosition: RadioGroup
    private lateinit var groupSize: RadioGroup
    private lateinit var groupOpacity: RadioGroup
    private lateinit var switchMute: Switch
    private lateinit var switchAutostart: Switch
    private lateinit var overlayPermissionStatus: TextView
    private lateinit var notificationPermissionStatus: TextView
    private lateinit var btnGrantNotificationPermission: Button

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
        overlayStatusText.text =
            if (settings.overlayEnabled) getString(R.string.status_overlay_running)
            else getString(R.string.status_overlay_stopped)
    }

    private fun bindViews() {
        overlayStatusText = findViewById(R.id.overlay_status_text)
        inputRtspUrl = findViewById(R.id.input_rtsp_url)
        inputUsername = findViewById(R.id.input_username)
        inputPassword = findViewById(R.id.input_password)
        testStatusText = findViewById(R.id.test_status_text)
        groupPosition = findViewById(R.id.group_position)
        groupSize = findViewById(R.id.group_size)
        groupOpacity = findViewById(R.id.group_opacity)
        switchMute = findViewById(R.id.switch_mute)
        switchAutostart = findViewById(R.id.switch_autostart)
        overlayPermissionStatus = findViewById(R.id.overlay_permission_status)
        notificationPermissionStatus = findViewById(R.id.notification_permission_status)
        btnGrantNotificationPermission = findViewById(R.id.btn_grant_notification_permission)
    }

    /** Builds the position/size/opacity RadioGroups from the enums so the enum stays the single source of truth. */
    private fun populateOptionGroups() {
        OverlayPosition.entries.forEach { position ->
            groupPosition.addView(radioButtonFor(position.label, position.ordinal))
        }
        OverlaySize.entries.forEach { size ->
            groupSize.addView(radioButtonFor(size.label, size.ordinal))
        }
        OverlayOpacity.entries.forEach { opacity ->
            groupOpacity.addView(radioButtonFor(opacity.label, opacity.ordinal))
        }
    }

    private fun radioButtonFor(label: String, id: Int): RadioButton = RadioButton(this).apply {
        this.id = id
        text = label
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun loadSettingsIntoForm() {
        inputRtspUrl.setText(settings.rtspUrl)
        inputUsername.setText(settings.username)
        inputPassword.setText(settings.password)
        groupPosition.check(settings.position.ordinal)
        groupSize.check(settings.size.ordinal)
        groupOpacity.check(settings.opacity.ordinal)
        switchMute.isChecked = !settings.muted
        switchAutostart.isChecked = settings.autoStartOnBoot
    }

    private fun wireActions() {
        findViewById<Button>(R.id.btn_test_connection).setOnClickListener { testConnection() }
        findViewById<Button>(R.id.btn_grant_overlay_permission).setOnClickListener { requestOverlayPermission() }
        btnGrantNotificationPermission.setOnClickListener { requestNotificationPermission() }
        findViewById<Button>(R.id.btn_save_start).setOnClickListener { saveAndStart() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener { stopOverlay() }
    }

    // ---- Permissions -------------------------------------------------------------------

    private fun refreshPermissionStatus() {
        val overlayGranted = Settings.canDrawOverlays(this)
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
            notificationPermissionStatus.setTextColor(
                ContextCompat.getColor(this, R.color.text_secondary)
            )
            btnGrantNotificationPermission.isEnabled = false
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ---- Test connection -----------------------------------------------------------------

    private fun testConnection() {
        val url = injectCredentials(inputRtspUrl.text.toString().trim(), inputUsername.text.toString(), inputPassword.text.toString())
        if (url.isBlank()) {
            testStatusText.text = getString(R.string.validation_missing_url)
            return
        }

        releaseTestPlayer()
        testStatusText.text = getString(R.string.test_connecting)

        val player = RtspPlayerFactory.createPlayer(this)
        testPlayer = player

        val timeoutRunnable = Runnable {
            testStatusText.text = getString(R.string.test_timeout)
            releaseTestPlayer()
        }

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    testHandler.removeCallbacks(timeoutRunnable)
                    testStatusText.text = getString(R.string.test_success)
                    testHandler.postDelayed({ releaseTestPlayer() }, 300)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                testHandler.removeCallbacks(timeoutRunnable)
                testStatusText.text = getString(R.string.test_failed, error.errorCodeName)
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

    private fun injectCredentials(url: String, username: String, password: String): String {
        if (username.isBlank() || url.isBlank()) return url
        return runCatching {
            val schemeIdx = url.indexOf("://")
            if (schemeIdx == -1) return url
            val scheme = url.substring(0, schemeIdx + 3)
            val rest = url.substring(schemeIdx + 3)
            if (rest.contains("@")) return url
            val encodedUser = java.net.URLEncoder.encode(username, "UTF-8")
            val encodedPass = java.net.URLEncoder.encode(password, "UTF-8")
            "$scheme$encodedUser:$encodedPass@$rest"
        }.getOrDefault(url)
    }

    // ---- Save / start / stop --------------------------------------------------------------

    private fun saveAndStart() {
        val newUrl = inputRtspUrl.text.toString().trim()
        if (newUrl.isBlank()) {
            testStatusText.text = getString(R.string.validation_missing_url)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }

        val streamChanged = newUrl != settings.rtspUrl ||
            inputUsername.text.toString() != settings.username ||
            inputPassword.text.toString() != settings.password
        val wasRunning = settings.overlayEnabled

        settings.rtspUrl = newUrl
        settings.username = inputUsername.text.toString()
        settings.password = inputPassword.text.toString()
        settings.position = OverlayPosition.entries[groupPosition.checkedRadioButtonId.coerceAtLeast(0)]
        settings.size = OverlaySize.entries[groupSize.checkedRadioButtonId.coerceAtLeast(0)]
        settings.opacity = OverlayOpacity.entries[groupOpacity.checkedRadioButtonId.coerceAtLeast(0)]
        settings.muted = !switchMute.isChecked
        settings.autoStartOnBoot = switchAutostart.isChecked

        val intent = Intent(this, OverlayService::class.java).apply {
            action = when {
                streamChanged -> OverlayService.ACTION_RESTART_STREAM
                wasRunning -> OverlayService.ACTION_REFRESH_SETTINGS
                else -> null
            }
        }
        ContextCompat.startForegroundService(this, intent)

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
    }
}
