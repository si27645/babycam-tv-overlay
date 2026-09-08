package com.babycam.overlay

import android.content.Context
import android.content.SharedPreferences
import java.net.URLEncoder

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

/**
 * Thin wrapper around SharedPreferences holding every user-configurable setting.
 * Shared by MainActivity (edits settings) and OverlayService / BootReceiver (reads them).
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var rtspUrl: String
        get() = prefs.getString(KEY_RTSP_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_RTSP_URL, value.trim()).apply()

    var username: String
        get() = prefs.getString(KEY_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USERNAME, value.trim()).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

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

    /**
     * Returns the RTSP URL with username/password injected as userinfo (rtsp://user:pass@host/...)
     * unless the URL already carries credentials or no username is set.
     */
    fun buildAuthenticatedUrl(): String {
        val url = rtspUrl
        if (username.isBlank() || url.isBlank()) return url
        return runCatching {
            val schemeIdx = url.indexOf("://")
            if (schemeIdx == -1) return url
            val scheme = url.substring(0, schemeIdx + 3)
            val rest = url.substring(schemeIdx + 3)
            if (rest.contains("@")) return url // credentials already embedded
            val encodedUser = URLEncoder.encode(username, "UTF-8")
            val encodedPass = URLEncoder.encode(password, "UTF-8")
            "$scheme$encodedUser:$encodedPass@$rest"
        }.getOrDefault(url)
    }

    companion object {
        private const val PREFS_NAME = "babycam_settings"
        private const val KEY_RTSP_URL = "rtsp_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_POSITION = "position"
        private const val KEY_SIZE = "size"
        private const val KEY_OPACITY = "opacity"
        private const val KEY_MUTED = "muted"
        private const val KEY_AUTOSTART = "autostart"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
    }
}
