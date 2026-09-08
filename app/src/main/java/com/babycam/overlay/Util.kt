package com.babycam.overlay

import android.content.Context
import android.os.Build
import android.provider.Settings

/** dp -> px using this Context's display metrics. Shared by OverlayService and MainActivity. */
fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

/**
 * Settings.canDrawOverlays() requires API 23+; before that, SYSTEM_ALERT_WINDOW (declared in
 * the manifest) was granted automatically at install time with no runtime check available or
 * needed, so treat pre-23 devices as always having the permission.
 */
fun canDrawOverlaysCompat(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
