package com.lagradost.cloudstream3.ui.home

import com.lagradost.cloudstream3.ui.player.CSPlayerLoading

/**
 * Tracks one playback attempt and writes start latency, rebuffering, errors, and the
 * measured stream format into [ServerRatings]. The initial buffer before the first frame
 * is start latency, not a rebuffer.
 */
class PlaybackRatingTracker(
    private val onChanged: () -> Unit = {},
) {
    private var source: String? = null
    private var provider: String? = null
    private var startedAtMs = 0L
    private var ready = false
    private var buffering = false

    fun begin(source: String?, provider: String?) {
        this.source = source?.takeIf { it.isNotBlank() }
        this.provider = provider?.takeIf { it.isNotBlank() }
        startedAtMs = System.currentTimeMillis()
        ready = false
        buffering = false
    }

    fun onStatus(status: CSPlayerLoading) {
        when (status) {
            CSPlayerLoading.IsPlaying -> {
                buffering = false
                markReady()
            }

            CSPlayerLoading.IsBuffering -> {
                if (ready && !buffering) {
                    buffering = true
                    ServerRatings.onBuffering(source, provider)
                    onChanged()
                }
            }

            else -> buffering = false
        }
    }

    fun onFormat(height: Int?, bitrateBps: Int?, codec: String?) {
        val quality = height?.let { ServerRatingCalculator.snapHeight(it) } ?: 0
        val bitrate = bitrateBps?.takeIf { it > 0 }
        val label = ServerRatingCalculator.videoCodecLabel(codec)
        if (quality == 0 && bitrate == null && label == null) return
        ServerRatings.onPlaybackFormat(source, provider, quality, bitrate, label)
        if (!ready && quality > 0) {
            markReady()
        }
    }

    fun onError() {
        val latency = if (!ready && startedAtMs > 0L) {
            (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0)
        } else {
            null
        }
        ready = true
        buffering = false
        ServerRatings.onPlaybackError(source, provider, latency)
        onChanged()
    }

    private fun markReady() {
        if (ready || startedAtMs <= 0L) return
        ready = true
        val latency = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0)
        ServerRatings.onPlaybackReady(source, provider, latency)
        onChanged()
    }
}
