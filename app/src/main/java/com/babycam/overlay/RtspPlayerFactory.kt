@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.babycam.overlay

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource

/**
 * Builds the ExoPlayer instance and RTSP media source shared by both the persistent
 * overlay (OverlayService) and the one-off "Test connection" check (MainActivity).
 */
object RtspPlayerFactory {

    fun createPlayer(context: Context): ExoPlayer = ExoPlayer.Builder(context).build()

    fun createMediaSource(url: String): MediaSource {
        val mediaItem = MediaItem.fromUri(url)
        return RtspMediaSource.Factory()
            // TCP is slower than UDP but far more reliable across flaky Wi-Fi / NAT setups,
            // which matters a lot more than raw latency for a baby monitor overlay.
            .setForceUseRtpTcp(true)
            .createMediaSource(mediaItem)
    }
}
