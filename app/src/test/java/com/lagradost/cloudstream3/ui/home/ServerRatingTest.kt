package com.lagradost.cloudstream3.ui.home

import com.lagradost.cloudstream3.utils.Qualities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRatingTest {
    @Test
    fun `no measurements produces no rating`() {
        val rating = ServerRatingCalculator.rate(
            ServerObservation(id = "provider:Alpha", name = "Alpha", kind = ServerKind.PROVIDER)
        )

        assertFalse(rating.hasData)
        assertNull(rating.score)
        assertNull(rating.stars)
        assertNull(rating.successRate)
        assertNull(rating.averageLatencyMs)
        assertEquals("No data yet", ServerRatingCalculator.linkRatingSuffix(rating.stars, "No data yet"))
    }

    @Test
    fun `quality alone does not invent a rating`() {
        val observation = ServerObservation(
            id = "extractor:Host",
            name = "Host",
            kind = ServerKind.EXTRACTOR,
            bestQuality = Qualities.P1080.value,
            bestBitrate = 4_500_000,
            codecs = listOf("HEVC"),
        )

        val rating = ServerRatingCalculator.rate(observation)
        val quality = ServerRatingCalculator.qualitySummary(observation)

        assertFalse(rating.hasData)
        assertEquals("1080p", quality.label)
        assertEquals("4.5 Mbps", quality.bitrateLabel)
        assertEquals("HEVC", quality.codecsLabel)
    }

    @Test
    fun `perfect link loads score 100 and five stars`() {
        val rating = ServerRatingCalculator.rate(loads(successes = 8, failures = 0, timeouts = 0))

        assertEquals(100, rating.score)
        assertEquals(5f, rating.stars)
        assertEquals("★★★★★", ServerRatingCalculator.starGlyphs(rating.stars!!))
        assertEquals(1f, rating.successRate)
        assertEquals(0f, rating.timeoutRate)
    }

    @Test
    fun `timeouts score lower than other failures at the same success rate`() {
        val failed = ServerRatingCalculator.rate(loads(successes = 5, failures = 5, timeouts = 0))
        val timedOut = ServerRatingCalculator.rate(loads(successes = 5, failures = 0, timeouts = 5))

        assertEquals(50, failed.score)
        assertEquals(38, timedOut.score)
        assertTrue(timedOut.score!! < failed.score!!)
        assertEquals(0.5f, timedOut.timeoutRate)
        assertEquals(0.5f, failed.failureRate)
    }

    @Test
    fun `missing latency is omitted instead of assumed fast or slow`() {
        val unmeasured = ServerRatingCalculator.rate(loads(successes = 4, failures = 0, timeouts = 0))
        val fast = ServerRatingCalculator.rate(
            loads(successes = 4, failures = 0, timeouts = 0).copy(
                latencyTotalMs = 2_000,
                latencySamples = 4,
            )
        )
        val slow = ServerRatingCalculator.rate(
            loads(successes = 4, failures = 0, timeouts = 0).copy(
                latencyTotalMs = 80_000,
                latencySamples = 4,
            )
        )

        assertNull(unmeasured.averageLatencyMs)
        assertEquals(100, unmeasured.score)
        assertEquals(100, fast.score)
        assertEquals(500L, fast.averageLatencyMs)
        assertEquals(50, slow.score)
        assertTrue(slow.score!! < fast.score!!)
    }

    @Test
    fun `playback errors and rebuffering lower the score only when observed`() {
        val clean = ServerRatingCalculator.rate(
            loads(successes = 2, failures = 0, timeouts = 0).copy(playbackStarts = 4, playbackErrors = 0)
        )
        val errors = ServerRatingCalculator.rate(
            loads(successes = 2, failures = 0, timeouts = 0).copy(playbackStarts = 4, playbackErrors = 2)
        )
        val rebuffers = ServerRatingCalculator.rate(
            loads(successes = 2, failures = 0, timeouts = 0).copy(playbackStarts = 1, bufferingEvents = 4)
        )
        val linksOnly = ServerRatingCalculator.rate(loads(successes = 2, failures = 0, timeouts = 0))

        assertEquals(100, clean.score)
        assertEquals(50, errors.score)
        assertEquals(0.5f, errors.playbackErrorRate)
        assertEquals(55, rebuffers.score)
        assertNull(linksOnly.playbackErrorRate)
        assertEquals(100, linksOnly.score)
    }

    @Test
    fun `every playback error scores zero`() {
        val rating = ServerRatingCalculator.rate(
            ServerObservation(
                id = "extractor:Host",
                name = "Host",
                kind = ServerKind.EXTRACTOR,
                playbackStarts = 3,
                playbackErrors = 3,
                latencyTotalMs = 900,
                latencySamples = 3,
            )
        )

        assertEquals(0, rating.score)
        assertEquals(0f, rating.stars)
    }

    @Test
    fun `unknown quality sentinel does not outrank a real resolution`() {
        var snapshot = ServerRatingSnapshot()
        snapshot = ServerRatingCalculator.recordQuality(
            snapshot, "extractor:Host", "Host", ServerKind.EXTRACTOR,
            Qualities.Unknown.value, "backup", null, null, nowMs = 1,
        )
        snapshot = ServerRatingCalculator.recordQuality(
            snapshot, "extractor:Host", "Host", ServerKind.EXTRACTOR,
            Qualities.P720.value, "720p", null, null, nowMs = 2,
        )
        snapshot = ServerRatingCalculator.recordQuality(
            snapshot, "extractor:Host", "Host", ServerKind.EXTRACTOR,
            Qualities.Unknown.value, "later", null, null, nowMs = 3,
        )

        assertEquals(Qualities.P720.value, snapshot.servers.single().bestQuality)
        assertEquals("720p", ServerRatingCalculator.qualitySummary(snapshot.servers.single()).label)
        assertFalse(ServerRatingCalculator.rate(snapshot.servers.single()).hasData)
    }

    @Test
    fun `link name supplies resolution bitrate and codec when the quality field is unknown`() {
        val snapshot = ServerRatingCalculator.recordQuality(
            snapshot = ServerRatingSnapshot(),
            id = "extractor:Host",
            name = "Host",
            kind = ServerKind.EXTRACTOR,
            quality = Qualities.Unknown.value,
            linkName = "1080p HEVC 2.5 Mbps",
            bitrateBps = null,
            codec = null,
            nowMs = 1,
        )
        val observation = snapshot.servers.single()
        val quality = ServerRatingCalculator.qualitySummary(observation)

        assertEquals(Qualities.P1080.value, observation.bestQuality)
        assertEquals("1080p", quality.label)
        assertEquals(2_500_000, observation.bestBitrate)
        assertEquals("2.5 Mbps", quality.bitrateLabel)
        assertEquals(listOf("HEVC"), observation.codecs)
    }

    @Test
    fun `ordinary words are not treated as codecs or bitrates`() {
        assertNull(ServerRatingCalculator.codecFromText("Super Server"))
        assertNull(ServerRatingCalculator.bitrateFromText("Super Server"))
        assertNull(ServerRatingCalculator.videoCodecLabel("video/mp4"))
        assertEquals("AVC", ServerRatingCalculator.videoCodecLabel("video/avc"))
        assertNull(ServerRatingCalculator.formatBitrate(0))
        assertEquals("800 kbps", ServerRatingCalculator.formatBitrate(800_000))
    }

    @Test
    fun `measured height snaps only when it is close to a standard label`() {
        assertEquals(Qualities.P1080.value, ServerRatingCalculator.snapHeight(1072))
        assertEquals(900, ServerRatingCalculator.snapHeight(900))
        assertEquals(0, ServerRatingCalculator.snapHeight(0))
    }

    @Test
    fun `load outcomes stay partitioned and blank names are ignored`() {
        var snapshot = ServerRatingSnapshot()
        snapshot = ServerRatingCalculator.recordLoad(
            snapshot, "provider:Alpha", "Alpha", ServerKind.PROVIDER,
            LinkLoadOutcome.SUCCESS, 100, 1,
        )
        snapshot = ServerRatingCalculator.recordLoad(
            snapshot, "provider:Alpha", "Alpha", ServerKind.PROVIDER,
            LinkLoadOutcome.FAILURE, 200, 2,
        )
        snapshot = ServerRatingCalculator.recordLoad(
            snapshot, "provider:Alpha", "Alpha", ServerKind.PROVIDER,
            LinkLoadOutcome.TIMEOUT, 300, 3,
        )
        val ignored = ServerRatingCalculator.recordLoad(
            snapshot, "provider:", "", ServerKind.PROVIDER,
            LinkLoadOutcome.SUCCESS, 1, 4,
        )

        val observation = snapshot.servers.single()
        assertEquals(3, observation.attempts)
        assertEquals(1, observation.successes)
        assertEquals(1, observation.failures)
        assertEquals(1, observation.timeouts)
        assertEquals(observation.attempts, observation.successes + observation.failures + observation.timeouts)
        assertEquals(3, observation.latencySamples)
        assertEquals(200L, ServerRatingCalculator.rate(observation).averageLatencyMs)
        assertEquals(snapshot.servers.size, ignored.servers.size)
    }

    @Test
    fun `playback ready error and buffering update the right counters`() {
        var snapshot = ServerRatingSnapshot()
        snapshot = ServerRatingCalculator.recordPlaybackReady(
            snapshot, "extractor:Host", "Host", ServerKind.EXTRACTOR, latencyMs = 1200, nowMs = 1,
        )
        snapshot = ServerRatingCalculator.recordBuffering(
            snapshot, "extractor:Host", "Host", ServerKind.EXTRACTOR, nowMs = 2,
        )
        snapshot = ServerRatingCalculator.recordPlaybackError(
            snapshot, "extractor:Host", "Host", ServerKind.EXTRACTOR, latencyMs = null, nowMs = 3,
        )
        snapshot = ServerRatingCalculator.recordPlaybackError(
            snapshot, "provider:Alpha", "Alpha", ServerKind.PROVIDER, latencyMs = 4000, nowMs = 4,
        )

        val host = snapshot.servers.first { it.name == "Host" }
        assertEquals(1, host.playbackStarts)
        assertEquals(1, host.playbackErrors)
        assertEquals(1, host.bufferingEvents)
        assertEquals(1, host.latencySamples)

        val provider = snapshot.servers.first { it.name == "Alpha" }
        assertEquals(1, provider.playbackStarts)
        assertEquals(1, provider.playbackErrors)
        assertEquals(0, provider.attempts)
        assertTrue(ServerRatingCalculator.rate(provider).hasData)
    }

    @Test
    fun `assemble includes unmeasured providers and sorts rated servers first`() {
        val rated = loads(successes = 9, failures = 1, timeouts = 0).copy(
            id = ServerRatingCalculator.providerId("Beta"),
            name = "Beta",
            kind = ServerKind.PROVIDER,
            bestQuality = Qualities.P720.value,
        )
        val host = loads(successes = 10, failures = 0, timeouts = 0).copy(
            id = ServerRatingCalculator.extractorId("FastHost"),
            name = "FastHost",
            kind = ServerKind.EXTRACTOR,
            bestQuality = Qualities.P2160.value,
        )
        val cards = ServerRatingCalculator.assemble(
            providerNames = listOf("Alpha", "Beta", " ", "Alpha"),
            snapshot = ServerRatingSnapshot(listOf(rated, host)),
            sort = ServerSort.RATING,
            filter = ServerFilter.ALL,
        )

        assertEquals(listOf("FastHost", "Beta", "Alpha"), cards.map { it.observation.name })
        assertFalse(cards.last().rating.hasData)
        assertEquals(
            listOf("FastHost"),
            ServerRatingCalculator.assemble(
                listOf("Alpha"),
                ServerRatingSnapshot(listOf(rated, host)),
                ServerSort.RATING,
                ServerFilter.EXTRACTORS,
            ).map { it.observation.name },
        )
        assertEquals(
            listOf("Alpha"),
            ServerRatingCalculator.assemble(
                listOf("Alpha", "Beta"),
                ServerRatingSnapshot(listOf(rated, host)),
                ServerSort.NAME,
                ServerFilter.UNRATED,
            ).map { it.observation.name },
        )
    }

    @Test
    fun `quality sort prefers resolution then bitrate and latency sort puts unknown last`() {
        val low = ServerObservation(
            id = "provider:Low", name = "Low", kind = ServerKind.PROVIDER, bestQuality = 720,
        )
        val high = ServerObservation(
            id = "provider:High", name = "High", kind = ServerKind.PROVIDER,
            bestQuality = 2160, bestBitrate = 8_000_000,
            attempts = 1, successes = 1, latencyTotalMs = 5_000, latencySamples = 1,
        )
        val quick = ServerObservation(
            id = "provider:Quick", name = "Quick", kind = ServerKind.PROVIDER,
            attempts = 1, successes = 1, latencyTotalMs = 400, latencySamples = 1,
        )
        val snapshot = ServerRatingSnapshot(listOf(low, high, quick))

        assertEquals(
            listOf("High", "Low", "Quick"),
            ServerRatingCalculator.assemble(emptyList(), snapshot, ServerSort.QUALITY, ServerFilter.ALL)
                .map { it.observation.name },
        )
        assertEquals(
            listOf("Quick", "High", "Low"),
            ServerRatingCalculator.assemble(emptyList(), snapshot, ServerSort.LATENCY, ServerFilter.ALL)
                .map { it.observation.name },
        )
    }

    @Test
    fun `link order keeps quality priority and uses rating only as a tie break`() {
        assertTrue(
            ServerRatingCalculator.compareLinkOrder(
                priorityA = 10, ratingA = 1, priorityB = 4, ratingB = 100,
            ) < 0
        )
        assertTrue(
            ServerRatingCalculator.compareLinkOrder(
                priorityA = 5, ratingA = 90, priorityB = 5, ratingB = 20,
            ) < 0
        )
        assertTrue(
            ServerRatingCalculator.compareLinkOrder(
                priorityA = 5, ratingA = 10, priorityB = 5, ratingB = null,
            ) < 0
        )
        assertEquals(
            0,
            ServerRatingCalculator.compareLinkOrder(
                priorityA = 5, ratingA = null, priorityB = 5, ratingB = null,
            ),
        )
        assertEquals("★ 4.2", ServerRatingCalculator.linkRatingSuffix(4.2f, "No data yet"))
        assertEquals("1.5 s", ServerRatingCalculator.formatLatency(1500))
        assertEquals("800 ms", ServerRatingCalculator.formatLatency(800))
    }

    private fun loads(successes: Int, failures: Int, timeouts: Int): ServerObservation {
        return ServerObservation(
            id = "provider:Server",
            name = "Server",
            kind = ServerKind.PROVIDER,
            attempts = successes + failures + timeouts,
            successes = successes,
            failures = failures,
            timeouts = timeouts,
        )
    }
}
