package com.lagradost.cloudstream3.ui.home

import com.lagradost.cloudstream3.utils.Qualities
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A content provider (extension source) or an extractor host that actually serves a stream.
 * Ratings are stored separately so a provider and a host that share a display name do not collide.
 */
enum class ServerKind {
    PROVIDER,
    EXTRACTOR,
}

/** One measured attempt to obtain playable links from a server. */
enum class LinkLoadOutcome {
    SUCCESS,
    FAILURE,
    TIMEOUT,
}

enum class ServerSort {
    RATING,
    QUALITY,
    NAME,
    LATENCY,
}

enum class ServerFilter {
    ALL,
    RATED,
    UNRATED,
    PROVIDERS,
    EXTRACTORS,
}

/**
 * Counters measured on this device. A missing counter stays at zero and is not treated as a
 * successful or failed sample.
 */
data class ServerObservation(
    val id: String,
    val name: String,
    val kind: ServerKind,
    val attempts: Int = 0,
    val successes: Int = 0,
    val failures: Int = 0,
    val timeouts: Int = 0,
    val playbackStarts: Int = 0,
    val playbackErrors: Int = 0,
    val bufferingEvents: Int = 0,
    val latencyTotalMs: Long = 0,
    val latencySamples: Int = 0,
    /** Highest known resolution height, or 0 when no resolution has been seen. */
    val bestQuality: Int = 0,
    /** Highest observed bitrate in bits per second, or 0 when unknown. */
    val bestBitrate: Int = 0,
    val codecs: List<String> = emptyList(),
    val updatedAtMs: Long = 0,
)

data class ServerRatingSnapshot(
    val servers: List<ServerObservation> = emptyList(),
)

/**
 * Derived from [ServerObservation]. [score] and [stars] are null when nothing has been measured.
 */
data class ServerRating(
    val score: Int?,
    val stars: Float?,
    val successRate: Float?,
    val failureRate: Float?,
    val timeoutRate: Float?,
    val playbackErrorRate: Float?,
    val averageLatencyMs: Long?,
    val sampleCount: Int,
    val hasData: Boolean,
)

data class ServerCard(
    val observation: ServerObservation,
    val rating: ServerRating,
)

data class StreamQualitySummary(
    val bestQuality: Int,
    val label: String,
    val bestBitrateBps: Int,
    val bitrateLabel: String?,
    val codecsLabel: String?,
)

/**
 * Pure rating math. Every score comes from counters the app recorded while loading or playing
 * links. Signals with no samples are left out of the score instead of being filled in.
 *
 * When a signal is present it scales the score:
 * - link success rate
 * - an extra penalty when failures were timeouts (timeouts already fail the success rate)
 * - share of playbacks that did not error
 * - rebuffering after playback had started
 * - average response or start latency, which only lowers the score when it was slow
 */
object ServerRatingCalculator {
    fun providerId(name: String): String = "provider:$name"

    fun extractorId(name: String): String = "extractor:$name"

    fun emptyObservation(id: String, name: String, kind: ServerKind): ServerObservation {
        return ServerObservation(id = id, name = name, kind = kind)
    }

    fun rate(observation: ServerObservation): ServerRating {
        val attempts = observation.attempts.coerceAtLeast(0)
        val playbackStarts = observation.playbackStarts.coerceAtLeast(0)
        val latencySamples = observation.latencySamples.coerceAtLeast(0)
        var score = 100.0
        var used = false

        val successRate = if (attempts > 0) {
            observation.successes.coerceAtLeast(0).toFloat() / attempts
        } else {
            null
        }
        val failureRate = if (attempts > 0) {
            observation.failures.coerceAtLeast(0).toFloat() / attempts
        } else {
            null
        }
        val timeoutRate = if (attempts > 0) {
            observation.timeouts.coerceAtLeast(0).toFloat() / attempts
        } else {
            null
        }
        if (attempts > 0 && successRate != null && timeoutRate != null) {
            used = true
            score *= successRate.toDouble()
            score *= 1.0 - (0.5 * timeoutRate.coerceIn(0f, 1f))
        }

        val playbackErrorRate = if (playbackStarts > 0) {
            (observation.playbackErrors.coerceAtLeast(0).toFloat() / playbackStarts).coerceAtMost(1f)
        } else {
            null
        }
        if (playbackStarts > 0 && playbackErrorRate != null) {
            used = true
            score *= (1.0 - playbackErrorRate)
            score *= bufferingFactor(observation.bufferingEvents, playbackStarts)
        }

        val averageLatencyMs = if (latencySamples > 0) {
            observation.latencyTotalMs.coerceAtLeast(0) / latencySamples
        } else {
            null
        }
        if (averageLatencyMs != null) {
            used = true
            score *= latencyFactor(averageLatencyMs)
        }

        val rounded = if (used) score.roundToInt().coerceIn(0, 100) else null
        return ServerRating(
            score = rounded,
            stars = rounded?.let { it / 20f },
            successRate = successRate,
            failureRate = failureRate,
            timeoutRate = timeoutRate,
            playbackErrorRate = playbackErrorRate,
            averageLatencyMs = averageLatencyMs,
            sampleCount = attempts + playbackStarts,
            hasData = rounded != null,
        )
    }

    fun recordLoad(
        snapshot: ServerRatingSnapshot,
        id: String,
        name: String,
        kind: ServerKind,
        outcome: LinkLoadOutcome,
        latencyMs: Long,
        nowMs: Long,
    ): ServerRatingSnapshot {
        if (id.isBlank() || name.isBlank()) return snapshot
        return upsert(snapshot, id, name, kind, nowMs) { current ->
            val latency = latencyMs.coerceAtLeast(0)
            current.copy(
                attempts = current.attempts + 1,
                successes = current.successes + if (outcome == LinkLoadOutcome.SUCCESS) 1 else 0,
                failures = current.failures + if (outcome == LinkLoadOutcome.FAILURE) 1 else 0,
                timeouts = current.timeouts + if (outcome == LinkLoadOutcome.TIMEOUT) 1 else 0,
                latencyTotalMs = current.latencyTotalMs + latency,
                latencySamples = current.latencySamples + 1,
            )
        }
    }

    fun recordQuality(
        snapshot: ServerRatingSnapshot,
        id: String,
        name: String,
        kind: ServerKind,
        quality: Int,
        linkName: String,
        bitrateBps: Int?,
        codec: String?,
        nowMs: Long,
    ): ServerRatingSnapshot {
        if (id.isBlank() || name.isBlank()) return snapshot
        val resolvedQuality = resolveQuality(quality, linkName)
        val parsedBitrate = bitrateBps?.takeIf { it > 0 } ?: bitrateFromText(linkName)
        val parsedCodec = videoCodecLabel(codec) ?: codecFromText(linkName)
        if (resolvedQuality == 0 && parsedBitrate == null && parsedCodec == null) return snapshot
        val existing = snapshot.servers.find { it.id == id }
        if (existing != null) {
            val nextQuality = betterQuality(existing.bestQuality, resolvedQuality)
            val nextBitrate = maxOf(existing.bestBitrate, parsedBitrate ?: 0)
            val nextCodecs = mergeCodecs(existing.codecs, parsedCodec)
            if (
                existing.bestQuality == nextQuality &&
                existing.bestBitrate == nextBitrate &&
                existing.codecs == nextCodecs
            ) {
                return snapshot
            }
        }
        return upsert(snapshot, id, name, kind, nowMs) { current ->
            current.copy(
                bestQuality = betterQuality(current.bestQuality, resolvedQuality),
                bestBitrate = maxOf(current.bestBitrate, parsedBitrate ?: 0),
                codecs = mergeCodecs(current.codecs, parsedCodec),
            )
        }
    }

    fun recordPlaybackReady(
        snapshot: ServerRatingSnapshot,
        id: String,
        name: String,
        kind: ServerKind,
        latencyMs: Long,
        nowMs: Long,
    ): ServerRatingSnapshot {
        if (id.isBlank() || name.isBlank()) return snapshot
        return upsert(snapshot, id, name, kind, nowMs) { current ->
            current.copy(
                playbackStarts = current.playbackStarts + 1,
                latencyTotalMs = current.latencyTotalMs + latencyMs.coerceAtLeast(0),
                latencySamples = current.latencySamples + 1,
            )
        }
    }

    fun recordPlaybackError(
        snapshot: ServerRatingSnapshot,
        id: String,
        name: String,
        kind: ServerKind,
        latencyMs: Long?,
        nowMs: Long,
    ): ServerRatingSnapshot {
        if (id.isBlank() || name.isBlank()) return snapshot
        return upsert(snapshot, id, name, kind, nowMs) { current ->
            val countStart = latencyMs != null
            current.copy(
                playbackStarts = current.playbackStarts + if (countStart) 1 else 0,
                playbackErrors = current.playbackErrors + 1,
                latencyTotalMs = current.latencyTotalMs + (latencyMs?.coerceAtLeast(0) ?: 0),
                latencySamples = current.latencySamples + if (countStart) 1 else 0,
            )
        }
    }

    fun recordBuffering(
        snapshot: ServerRatingSnapshot,
        id: String,
        name: String,
        kind: ServerKind,
        nowMs: Long,
    ): ServerRatingSnapshot {
        if (id.isBlank() || name.isBlank()) return snapshot
        return upsert(snapshot, id, name, kind, nowMs) { current ->
            current.copy(bufferingEvents = current.bufferingEvents + 1)
        }
    }

    fun qualitySummary(observation: ServerObservation): StreamQualitySummary {
        val quality = observation.bestQuality.takeIf { isKnownResolution(it) } ?: 0
        return StreamQualitySummary(
            bestQuality = quality,
            label = qualityLabel(quality),
            bestBitrateBps = observation.bestBitrate.coerceAtLeast(0),
            bitrateLabel = formatBitrate(observation.bestBitrate),
            codecsLabel = observation.codecs.takeIf { it.isNotEmpty() }?.joinToString(" / "),
        )
    }

    fun assemble(
        providerNames: List<String>,
        snapshot: ServerRatingSnapshot,
        sort: ServerSort,
        filter: ServerFilter,
    ): List<ServerCard> {
        val byId = snapshot.servers.associateBy { it.id }
        val cards = LinkedHashMap<String, ServerCard>()
        providerNames.forEach { name ->
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return@forEach
            val id = providerId(trimmed)
            val observation = byId[id] ?: emptyObservation(id, trimmed, ServerKind.PROVIDER)
            cards[id] = ServerCard(observation.copy(name = trimmed), rate(observation))
        }
        snapshot.servers.forEach { observation ->
            if (observation.id in cards) return@forEach
            if (observation.name.isBlank()) return@forEach
            cards[observation.id] = ServerCard(observation, rate(observation))
        }
        return cards.values
            .filter { card -> matches(card, filter) }
            .sortedWith(cardComparator(sort))
    }

    /**
     * Higher quality-profile priority wins. Equal priority prefers a higher observed score.
     * A server with no measured rating sorts after any measured score and ties with other
     * unmeasured servers so their existing order is kept.
     */
    fun compareLinkOrder(priorityA: Int, ratingA: Int?, priorityB: Int, ratingB: Int?): Int {
        if (priorityA != priorityB) return priorityB.compareTo(priorityA)
        val scoreA = ratingA ?: Int.MIN_VALUE
        val scoreB = ratingB ?: Int.MIN_VALUE
        return scoreB.compareTo(scoreA)
    }

    fun linkRatingSuffix(stars: Float?, noDataLabel: String): String {
        if (stars == null) return noDataLabel
        return "★ ${formatStars(stars)}"
    }

    fun formatStars(stars: Float): String {
        return String.format(Locale.US, "%.1f", stars)
    }

    fun starGlyphs(stars: Float): String {
        val full = stars.roundToInt().coerceIn(0, 5)
        return "★".repeat(full) + "☆".repeat(5 - full)
    }

    fun formatLatency(ms: Long): String {
        if (ms < 1000) return "$ms ms"
        val seconds = ms / 1000.0
        return if (seconds >= 10) {
            "${seconds.toInt()} s"
        } else {
            String.format(Locale.US, "%.1f s", seconds)
        }
    }

    fun formatBitrate(bps: Int): String? {
        if (bps <= 0) return null
        return if (bps >= 1_000_000) {
            val mbps = bps / 1_000_000.0
            if (mbps >= 10) "${mbps.toInt()} Mbps" else String.format(Locale.US, "%.1f Mbps", mbps)
        } else {
            "${bps / 1000} kbps"
        }
    }

    fun percent(rate: Float?): Int? = rate?.let { (it * 100f).roundToInt().coerceIn(0, 100) }

    fun isKnownResolution(quality: Int): Boolean {
        return quality > 0 && quality != Qualities.Unknown.value
    }

    fun qualityLabel(quality: Int): String {
        if (!isKnownResolution(quality)) return ""
        return Qualities.getStringByIntFull(quality)
    }

    fun resolveQuality(quality: Int, name: String): Int {
        val fromValue = quality.takeIf { isKnownResolution(it) }
        val fromName = qualityFromText(name)
        return listOfNotNull(fromValue, fromName).maxOrNull() ?: 0
    }

    /**
     * Snaps a measured video height to a standard label when it is close, and otherwise keeps
     * the measured height so the badge is not invented.
     */
    fun snapHeight(height: Int): Int {
        if (height <= 0) return 0
        val known = Qualities.entries.filter { it != Qualities.Unknown }
        val nearest = known.minBy { abs(it.value - height) }
        val delta = abs(nearest.value - height)
        val tolerance = (nearest.value * 0.12f).toInt().coerceAtLeast(8)
        return if (delta <= tolerance) nearest.value else height
    }

    fun qualityFromText(text: String): Int? {
        val token = QUALITY_TOKEN.find(text)?.value ?: return null
        val quality = com.lagradost.cloudstream3.utils.getQualityFromName(token)
        return quality.takeIf { isKnownResolution(it) }
    }

    fun bitrateFromText(text: String): Int? {
        val match = BITRATE.find(text) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        if (value <= 0.0) return null
        val unit = match.groupValues[2].lowercase(Locale.US)
        val bps = if (unit.startsWith("m")) value * 1_000_000.0 else value * 1_000.0
        return bps.toInt()
    }

    fun codecFromText(text: String): String? {
        val token = CODEC.find(text)?.value ?: return null
        return videoCodecLabel(token)
    }

    fun videoCodecLabel(raw: String?): String? {
        val value = raw?.lowercase(Locale.US)?.trim().orEmpty()
        if (value.isEmpty()) return null
        return when {
            value.contains("av1") -> "AV1"
            value.contains("hevc") || value.contains("h265") || value.contains("h.265") -> "HEVC"
            value.contains("avc") || value.contains("h264") || value.contains("h.264") -> "AVC"
            value.contains("vp9") -> "VP9"
            value.contains("vp8") -> "VP8"
            else -> null
        }
    }

    fun looksLikeTimeout(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val name = current::class.simpleName.orEmpty()
            if (name.contains("Timeout", ignoreCase = true)) return true
            current = current.cause
        }
        return false
    }

    private fun upsert(
        snapshot: ServerRatingSnapshot,
        id: String,
        name: String,
        kind: ServerKind,
        nowMs: Long,
        transform: (ServerObservation) -> ServerObservation,
    ): ServerRatingSnapshot {
        val current = snapshot.servers.find { it.id == id } ?: emptyObservation(id, name, kind)
        val updated = transform(
            current.copy(
                name = name.ifBlank { current.name },
                kind = kind,
                updatedAtMs = nowMs,
            )
        )
        return snapshot.copy(servers = snapshot.servers.filter { it.id != id } + updated)
    }

    private fun betterQuality(current: Int, candidate: Int): Int {
        if (!isKnownResolution(candidate)) return current
        if (!isKnownResolution(current)) return candidate
        return maxOf(current, candidate)
    }

    private fun mergeCodecs(current: List<String>, codec: String?): List<String> {
        if (codec.isNullOrBlank() || codec in current) return current
        if (current.size >= 6) return current
        return current + codec
    }

    private fun bufferingFactor(bufferingEvents: Int, playbackStarts: Int): Double {
        if (playbackStarts <= 0) return 1.0
        val average = bufferingEvents.coerceAtLeast(0).toDouble() / playbackStarts
        return when {
            average <= 0.25 -> 1.0
            average <= 1.0 -> 0.9
            average <= 2.0 -> 0.75
            average <= 4.0 -> 0.55
            else -> 0.35
        }
    }

    private fun latencyFactor(averageLatencyMs: Long): Double {
        return when {
            averageLatencyMs <= 800 -> 1.0
            averageLatencyMs <= 2_000 -> 0.95
            averageLatencyMs <= 4_000 -> 0.85
            averageLatencyMs <= 8_000 -> 0.7
            else -> 0.5
        }
    }

    private fun matches(card: ServerCard, filter: ServerFilter): Boolean {
        return when (filter) {
            ServerFilter.ALL -> true
            ServerFilter.RATED -> card.rating.hasData
            ServerFilter.UNRATED -> !card.rating.hasData
            ServerFilter.PROVIDERS -> card.observation.kind == ServerKind.PROVIDER
            ServerFilter.EXTRACTORS -> card.observation.kind == ServerKind.EXTRACTOR
        }
    }

    private fun cardComparator(sort: ServerSort): Comparator<ServerCard> {
        val byName = compareBy<ServerCard> { it.observation.name.lowercase(Locale.US) }
        return when (sort) {
            ServerSort.RATING -> compareByDescending<ServerCard> { it.rating.score ?: Int.MIN_VALUE }
                .thenByDescending { it.observation.bestQuality.takeIf { quality -> isKnownResolution(quality) } ?: Int.MIN_VALUE }
                .then(byName)

            ServerSort.QUALITY -> compareByDescending<ServerCard> {
                it.observation.bestQuality.takeIf { quality -> isKnownResolution(quality) } ?: Int.MIN_VALUE
            }
                .thenByDescending { it.observation.bestBitrate }
                .thenByDescending { it.rating.score ?: Int.MIN_VALUE }
                .then(byName)

            ServerSort.NAME -> byName
            ServerSort.LATENCY -> compareBy<ServerCard> { it.rating.averageLatencyMs ?: Long.MAX_VALUE }
                .then(byName)
        }
    }

    private val QUALITY_TOKEN = Regex("""(?i)\b(4k|2160p|1440p|1080p|720p|480p|360p|240p|144p)\b""")
    private val BITRATE = Regex("""(?i)(\d+(?:\.\d+)?)\s*(kbps|mbps|kbit|mbit)\b""")
    private val CODEC = Regex("""(?i)\b(av1|hevc|h\.?265|h\.?264|avc|vp9|vp8)\b""")
}
