package com.lagradost.cloudstream3.ui.home

import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.ui.home.ServerRatingCalculator.extractorId
import com.lagradost.cloudstream3.ui.home.ServerRatingCalculator.providerId
import com.lagradost.cloudstream3.utils.ExtractorLoadMeasurement
import kotlinx.serialization.Serializable

/**
 * Device-local server measurements. The snapshot is kept in memory and written to the app
 * DataStore so it survives restarts. Nothing here is fetched from a rating service.
 */
object ServerRatings {
    private const val KEY = "server_ratings_v1"

    private val lock = Any()
    private var snapshot = ServerRatingSnapshot()
    private var byId: Map<String, ServerObservation> = emptyMap()
    private var loaded = false

    fun snapshot(): ServerRatingSnapshot {
        return synchronized(lock) {
            ensureLoaded()
            snapshot
        }
    }

    fun onProviderLoad(name: String, outcome: LinkLoadOutcome, latencyMs: Long) {
        if (name.isBlank()) return
        val now = System.currentTimeMillis()
        update { current ->
            ServerRatingCalculator.recordLoad(
                snapshot = current,
                id = providerId(name),
                name = name,
                kind = ServerKind.PROVIDER,
                outcome = outcome,
                latencyMs = latencyMs,
                nowMs = now,
            )
        }
    }

    fun onLinkDiscovered(providerName: String, source: String, linkName: String, quality: Int) {
        val now = System.currentTimeMillis()
        update { current ->
            var next = current
            if (providerName.isNotBlank()) {
                next = ServerRatingCalculator.recordQuality(
                    snapshot = next,
                    id = providerId(providerName),
                    name = providerName,
                    kind = ServerKind.PROVIDER,
                    quality = quality,
                    linkName = linkName,
                    bitrateBps = null,
                    codec = null,
                    nowMs = now,
                )
            }
            if (source.isNotBlank() && source != providerName) {
                next = ServerRatingCalculator.recordQuality(
                    snapshot = next,
                    id = extractorId(source),
                    name = source,
                    kind = ServerKind.EXTRACTOR,
                    quality = quality,
                    linkName = linkName,
                    bitrateBps = null,
                    codec = null,
                    nowMs = now,
                )
            }
            next
        }
    }

    fun onExtractorMeasured(measurement: ExtractorLoadMeasurement) {
        if (measurement.name.isBlank()) return
        val outcome = when {
            measurement.timedOut && measurement.producedLinks <= 0 -> LinkLoadOutcome.TIMEOUT
            measurement.failed || measurement.producedLinks <= 0 -> LinkLoadOutcome.FAILURE
            else -> LinkLoadOutcome.SUCCESS
        }
        val now = System.currentTimeMillis()
        update { current ->
            var next = ServerRatingCalculator.recordLoad(
                snapshot = current,
                id = extractorId(measurement.name),
                name = measurement.name,
                kind = ServerKind.EXTRACTOR,
                outcome = outcome,
                latencyMs = measurement.latencyMs,
                nowMs = now,
            )
            measurement.qualities.forEachIndexed { index, quality ->
                next = ServerRatingCalculator.recordQuality(
                    snapshot = next,
                    id = extractorId(measurement.name),
                    name = measurement.name,
                    kind = ServerKind.EXTRACTOR,
                    quality = quality,
                    linkName = measurement.linkNames.getOrNull(index).orEmpty(),
                    bitrateBps = null,
                    codec = null,
                    nowMs = now,
                )
            }
            next
        }
    }

    fun onPlaybackReady(source: String?, provider: String?, latencyMs: Long) {
        val now = System.currentTimeMillis()
        update { current ->
            applyToSourceAndProvider(current, source, provider, now) { snapshot, id, name, kind ->
                ServerRatingCalculator.recordPlaybackReady(snapshot, id, name, kind, latencyMs, now)
            }
        }
    }

    fun onPlaybackError(source: String?, provider: String?, latencyMs: Long?) {
        val now = System.currentTimeMillis()
        update { current ->
            applyToSourceAndProvider(current, source, provider, now) { snapshot, id, name, kind ->
                ServerRatingCalculator.recordPlaybackError(snapshot, id, name, kind, latencyMs, now)
            }
        }
    }

    fun onBuffering(source: String?, provider: String?) {
        val now = System.currentTimeMillis()
        update { current ->
            applyToSourceAndProvider(current, source, provider, now) { snapshot, id, name, kind ->
                ServerRatingCalculator.recordBuffering(snapshot, id, name, kind, now)
            }
        }
    }

    fun onPlaybackFormat(
        source: String?,
        provider: String?,
        quality: Int,
        bitrateBps: Int?,
        codec: String?,
    ) {
        val now = System.currentTimeMillis()
        update { current ->
            applyToSourceAndProvider(current, source, provider, now) { snapshot, id, name, kind ->
                ServerRatingCalculator.recordQuality(
                    snapshot = snapshot,
                    id = id,
                    name = name,
                    kind = kind,
                    quality = quality,
                    linkName = "",
                    bitrateBps = bitrateBps,
                    codec = codec,
                    nowMs = now,
                )
            }
        }
    }

    /**
     * Score for the name shown on a link. Extractor measurements win when that host has been
     * observed; otherwise a provider recorded under the same name is used.
     */
    fun scoreForName(name: String?): Int? {
        return ratingForName(name)?.score
    }

    fun starsForName(name: String?): Float? {
        return ratingForName(name)?.stars
    }

    fun ratingForName(name: String?): ServerRating? {
        if (name.isNullOrBlank()) return null
        val observation = synchronized(lock) {
            ensureLoaded()
            byId[extractorId(name)] ?: byId[providerId(name)]
        } ?: return null
        val rating = ServerRatingCalculator.rate(observation)
        return rating.takeIf { it.hasData }
    }

    private fun applyToSourceAndProvider(
        snapshot: ServerRatingSnapshot,
        source: String?,
        provider: String?,
        nowMs: Long,
        block: (ServerRatingSnapshot, String, String, ServerKind) -> ServerRatingSnapshot,
    ): ServerRatingSnapshot {
        var next = snapshot
        if (!provider.isNullOrBlank()) {
            next = block(next, providerId(provider), provider, ServerKind.PROVIDER)
        }
        if (!source.isNullOrBlank() && source != provider) {
            next = block(next, extractorId(source), source, ServerKind.EXTRACTOR)
        }
        return next
    }

    private fun update(block: (ServerRatingSnapshot) -> ServerRatingSnapshot) {
        synchronized(lock) {
            ensureLoaded()
            val next = try {
                block(snapshot)
            } catch (_: Throwable) {
                return
            }
            if (next === snapshot) return
            snapshot = next
            byId = next.servers.associateBy { it.id }
            try {
                CloudStreamApp.setKey(KEY, next.toStored())
            } catch (_: Throwable) {
            }
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val stored = try {
            CloudStreamApp.getKey<StoredServerRatings>(KEY)
        } catch (_: Throwable) {
            null
        }
        snapshot = stored?.toSnapshot() ?: ServerRatingSnapshot()
        byId = snapshot.servers.associateBy { it.id }
    }
}

@Serializable
data class StoredServerObservation(
    val id: String,
    val name: String,
    val kind: String,
    val attempts: Int = 0,
    val successes: Int = 0,
    val failures: Int = 0,
    val timeouts: Int = 0,
    val playbackStarts: Int = 0,
    val playbackErrors: Int = 0,
    val bufferingEvents: Int = 0,
    val latencyTotalMs: Long = 0,
    val latencySamples: Int = 0,
    val bestQuality: Int = 0,
    val bestBitrate: Int = 0,
    val codecs: List<String> = emptyList(),
    val updatedAtMs: Long = 0,
)

@Serializable
data class StoredServerRatings(
    val servers: List<StoredServerObservation> = emptyList(),
)

private fun ServerRatingSnapshot.toStored(): StoredServerRatings {
    return StoredServerRatings(
        servers = servers.map { observation ->
            StoredServerObservation(
                id = observation.id,
                name = observation.name,
                kind = observation.kind.name,
                attempts = observation.attempts,
                successes = observation.successes,
                failures = observation.failures,
                timeouts = observation.timeouts,
                playbackStarts = observation.playbackStarts,
                playbackErrors = observation.playbackErrors,
                bufferingEvents = observation.bufferingEvents,
                latencyTotalMs = observation.latencyTotalMs,
                latencySamples = observation.latencySamples,
                bestQuality = observation.bestQuality,
                bestBitrate = observation.bestBitrate,
                codecs = observation.codecs,
                updatedAtMs = observation.updatedAtMs,
            )
        }
    )
}

private fun StoredServerRatings.toSnapshot(): ServerRatingSnapshot {
    return ServerRatingSnapshot(
        servers = servers.mapNotNull { stored ->
            val kind = runCatching { ServerKind.valueOf(stored.kind) }.getOrNull() ?: return@mapNotNull null
            if (stored.id.isBlank() || stored.name.isBlank()) return@mapNotNull null
            ServerObservation(
                id = stored.id,
                name = stored.name,
                kind = kind,
                attempts = stored.attempts,
                successes = stored.successes,
                failures = stored.failures,
                timeouts = stored.timeouts,
                playbackStarts = stored.playbackStarts,
                playbackErrors = stored.playbackErrors,
                bufferingEvents = stored.bufferingEvents,
                latencyTotalMs = stored.latencyTotalMs,
                latencySamples = stored.latencySamples,
                bestQuality = stored.bestQuality,
                bestBitrate = stored.bestBitrate,
                codecs = stored.codecs,
                updatedAtMs = stored.updatedAtMs,
            )
        }
    )
}
