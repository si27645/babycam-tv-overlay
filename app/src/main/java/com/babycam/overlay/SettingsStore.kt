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
 * Thin wrapper around SharedPreferences holding every user-configurable setting.
 * Shared by MainActivity (edits settings) and OverlayService / BootReceiver (reads them).
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var cameras: List<CameraProfile>
        get() = parseCameraProfiles(prefs.getString(KEY_CAMERAS, "") ?: "")
        set(value) = prefs.edit().putString(KEY_CAMERAS, value.toJsonString()).apply()

    /** The subset of [cameras] currently participating in the overlay (grid tiles, or the rotation set). */
    fun enabledCameras(): List<CameraProfile> = cameras.filter { it.enabled && it.url.isNotBlank() }

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
    }
}
