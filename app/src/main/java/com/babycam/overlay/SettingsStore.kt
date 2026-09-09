package com.babycam.overlay

import android.content.Context
import android.content.SharedPreferences

/** Where the floating overlay window is anchored on screen. */
enum class OverlayPosition(val label: String) {
    TOP_START("Top left"),
    TOP_END("Top right"),
    BOTTOM_START("Bottom left"),
    BOTTOM_END("Bottom right"),
    CENTER("Center")
}

/** Overlay width as a percentage of the screen; height follows a 16:9 box (real footage is letterboxed to fit). */
enum class OverlaySize(val label: String, val widthPercent: Float) {
    SMALL("Small", 0.22f),
    MEDIUM("Medium", 0.32f),
    LARGE("Large", 0.45f),
    XLARGE("Extra large", 0.60f)
}

enum class OverlayOpacity(val label: String, val alpha: Float) {
    LOW("40%", 0.4f),
    MEDIUM("70%", 0.7f),
    FULL("100%", 1.0f)
}

/** How multiple enabled cameras are shown in the one overlay window. */
enum class LayoutMode(val label: String) {
    SINGLE_ROTATE("Single feed (rotate through cameras)"),
    GRID("Grid - show up to 4 at once")
}

/**
 * How a doorbell trigger displays its camera(s) relative to the main overlay. Both need only
 * one video pipeline (TAKEOVER) or two running at once (SEPARATE_WINDOW) - which one actually
 * works smoothly depends entirely on the TV box's own GPU/decoder, hence this being a setting
 * rather than a fixed choice. See README "Doorbell trigger" for the hardware finding behind
 * TAKEOVER being the default.
 */
enum class DoorbellDisplayMode(val label: String) {
    TAKEOVER("Take over the main overlay (safer on weaker/older boxes)"),
    SEPARATE_WINDOW("Open a separate window (needs a TV box that can decode 2 videos at once)")
}

/**
 * Thin wrapper around SharedPreferences holding every user-configurable setting.
 * Shared by MainActivity (edits settings) and OverlayService / BootReceiver (reads them).
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var cameras: List<CameraProfile>
        get() = parseCameraProfiles(prefs.getString(KEY_CAMERAS, "") ?: "")
        set(value) = prefs.edit().putString(KEY_CAMERAS, value.toJsonString()).apply()

    /**
     * The subset of [cameraList] (defaults to re-fetching [cameras]) currently participating in
     * the overlay (grid tiles, or the rotation set). Pass an already-fetched list from a caller
     * that also needs [doorbellCameras]/[doorbellConfigured] in the same call, to avoid parsing
     * the camera-list JSON twice.
     */
    fun enabledCameras(cameraList: List<CameraProfile> = cameras): List<CameraProfile> =
        cameraList.filter { it.enabled && it.url.isNotBlank() }

    var layoutMode: LayoutMode
        get() = runCatching {
            LayoutMode.valueOf(prefs.getString(KEY_LAYOUT_MODE, LayoutMode.SINGLE_ROTATE.name)!!)
        }.getOrDefault(LayoutMode.SINGLE_ROTATE)
        set(value) = prefs.edit().putString(KEY_LAYOUT_MODE, value.name).apply()

    /** How long each camera stays on screen in SINGLE_ROTATE mode, when more than one is enabled. */
    var rotationIntervalSeconds: Int
        get() = prefs.getInt(KEY_ROTATION_INTERVAL, 30)
        set(value) = prefs.edit().putInt(KEY_ROTATION_INTERVAL, value).apply()

    var position: OverlayPosition
        get() = runCatching {
            OverlayPosition.valueOf(prefs.getString(KEY_POSITION, OverlayPosition.TOP_END.name)!!)
        }.getOrDefault(OverlayPosition.TOP_END)
        set(value) = prefs.edit().putString(KEY_POSITION, value.name).apply()

    var size: OverlaySize
        get() = runCatching {
            OverlaySize.valueOf(prefs.getString(KEY_SIZE, OverlaySize.MEDIUM.name)!!)
        }.getOrDefault(OverlaySize.MEDIUM)
        set(value) = prefs.edit().putString(KEY_SIZE, value.name).apply()

    var opacity: OverlayOpacity
        get() = runCatching {
            OverlayOpacity.valueOf(prefs.getString(KEY_OPACITY, OverlayOpacity.FULL.name)!!)
        }.getOrDefault(OverlayOpacity.FULL)
        set(value) = prefs.edit().putString(KEY_OPACITY, value.name).apply()

    var muted: Boolean
        get() = prefs.getBoolean(KEY_MUTED, true)
        set(value) = prefs.edit().putBoolean(KEY_MUTED, value).apply()

    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTOSTART, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTOSTART, value).apply()

    /** True while the user intends the overlay to be showing (used to restore state after boot/crash). */
    var overlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_OVERLAY_ENABLED, value).apply()

    // ---- Doorbell trigger (MQTT) --------------------------------------------------------

    /** Whether the app should connect to an MQTT broker and react to doorbell messages at all. */
    var mqttEnabled: Boolean
        get() = prefs.getBoolean(KEY_MQTT_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_MQTT_ENABLED, value).apply()

    var mqttHost: String
        get() = prefs.getString(KEY_MQTT_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MQTT_HOST, value.trim()).apply()

    var mqttPort: Int
        get() = prefs.getInt(KEY_MQTT_PORT, 1883)
        set(value) = prefs.edit().putInt(KEY_MQTT_PORT, value).apply()

    var mqttUseTls: Boolean
        get() = prefs.getBoolean(KEY_MQTT_TLS, false)
        set(value) = prefs.edit().putBoolean(KEY_MQTT_TLS, value).apply()

    var mqttUsername: String
        get() = prefs.getString(KEY_MQTT_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MQTT_USERNAME, value).apply()

    var mqttPassword: String
        get() = prefs.getString(KEY_MQTT_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MQTT_PASSWORD, value).apply()

    /** Topic to subscribe to; any message received on it counts as a doorbell trigger. */
    var mqttTopic: String
        get() = prefs.getString(KEY_MQTT_TOPIC, "babycam/doorbell") ?: "babycam/doorbell"
        set(value) = prefs.edit().putString(KEY_MQTT_TOPIC, value.trim()).apply()

    /**
     * ids of the CameraProfiles to show when triggered - may include cameras not otherwise
     * enabled in the normal rotation/grid. One selected -> shown statically for the whole
     * duration; several -> rotated between them, splitting the duration evenly.
     */
    var doorbellCameraIds: Set<String>
        get() = (prefs.getString(KEY_DOORBELL_CAMERA_IDS, "") ?: "")
            .split(",")
            .map { it.trim() }
            .filterTo(LinkedHashSet()) { it.isNotEmpty() }
        set(value) = prefs.edit().putString(KEY_DOORBELL_CAMERA_IDS, value.joinToString(",")).apply()

    var doorbellDurationSeconds: Int
        get() = prefs.getInt(KEY_DOORBELL_DURATION, 20)
        set(value) = prefs.edit().putInt(KEY_DOORBELL_DURATION, value).apply()

    var doorbellDisplayMode: DoorbellDisplayMode
        get() = runCatching {
            DoorbellDisplayMode.valueOf(prefs.getString(KEY_DOORBELL_DISPLAY_MODE, DoorbellDisplayMode.TAKEOVER.name)!!)
        }.getOrDefault(DoorbellDisplayMode.TAKEOVER)
        set(value) = prefs.edit().putString(KEY_DOORBELL_DISPLAY_MODE, value.name).apply()

    /** SEPARATE_WINDOW mode only - its own position/size, independent of the main overlay's. */
    var doorbellPosition: OverlayPosition
        get() = runCatching {
            OverlayPosition.valueOf(prefs.getString(KEY_DOORBELL_POSITION, OverlayPosition.BOTTOM_START.name)!!)
        }.getOrDefault(OverlayPosition.BOTTOM_START)
        set(value) = prefs.edit().putString(KEY_DOORBELL_POSITION, value.name).apply()

    var doorbellSize: OverlaySize
        get() = runCatching {
            OverlaySize.valueOf(prefs.getString(KEY_DOORBELL_SIZE, OverlaySize.MEDIUM.name)!!)
        }.getOrDefault(OverlaySize.MEDIUM)
        set(value) = prefs.edit().putString(KEY_DOORBELL_SIZE, value.name).apply()

    /** [doorbellCameraIds] resolved against [cameraList] (defaults to re-fetching [cameras]), in that list's order. */
    fun doorbellCameras(cameraList: List<CameraProfile> = cameras): List<CameraProfile> {
        val ids = doorbellCameraIds
        return cameraList.filter { it.id in ids }
    }

    /** Whether the doorbell trigger is fully configured (enabled, a broker host, and at least one camera picked). */
    fun doorbellConfigured(cameraList: List<CameraProfile> = cameras): Boolean =
        mqttEnabled && mqttHost.isNotBlank() && doorbellCameras(cameraList).isNotEmpty()

    /**
     * Whether OverlayService has any reason to be running at all: main cameras to show, or
     * doorbell listening configured - the two are independent, so either one alone is enough.
     * Without this, turning off every main camera (or never enabling one) would also silently
     * kill MQTT listening, even if the doorbell trigger was otherwise fully set up.
     */
    fun needsOverlayService(): Boolean {
        val cameraList = cameras
        return enabledCameras(cameraList).isNotEmpty() || doorbellConfigured(cameraList)
    }

    companion object {
        private const val PREFS_NAME = "babycam_settings"
        private const val KEY_CAMERAS = "cameras"
        private const val KEY_LAYOUT_MODE = "layout_mode"
        private const val KEY_ROTATION_INTERVAL = "rotation_interval_seconds"
        private const val KEY_POSITION = "position"
        private const val KEY_SIZE = "size"
        private const val KEY_OPACITY = "opacity"
        private const val KEY_MUTED = "muted"
        private const val KEY_AUTOSTART = "autostart"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_MQTT_ENABLED = "mqtt_enabled"
        private const val KEY_MQTT_HOST = "mqtt_host"
        private const val KEY_MQTT_PORT = "mqtt_port"
        private const val KEY_MQTT_TLS = "mqtt_use_tls"
        private const val KEY_MQTT_USERNAME = "mqtt_username"
        private const val KEY_MQTT_PASSWORD = "mqtt_password"
        private const val KEY_MQTT_TOPIC = "mqtt_topic"
        private const val KEY_DOORBELL_CAMERA_IDS = "doorbell_camera_ids"
        private const val KEY_DOORBELL_DURATION = "doorbell_duration_seconds"
        private const val KEY_DOORBELL_DISPLAY_MODE = "doorbell_display_mode"
        private const val KEY_DOORBELL_POSITION = "doorbell_position"
        private const val KEY_DOORBELL_SIZE = "doorbell_size"
    }
}
