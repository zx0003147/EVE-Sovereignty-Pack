package dev.evestaticmapplanner.sovereignty

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublicEsiAllianceMetadataSourceTest {
    @Test
    fun `successful detail request enriches name ticker and persists validators`() {
        val client = MetadataClient { allianceId, validators ->
            assertEquals(99_000_001, allianceId)
            assertEquals(AllianceMetadataValidators(), validators)
            PublicEsiAllianceMetadataResult.Updated(
                """{"name":"ESI Alliance","ticker":"ESI"}""",
                EsiCacheHeaders("public, max-age=3600", "\"v1\"", LAST_MODIFIED),
            )
        }
        val cache = RecordingMetadataCache()
        val state = AllianceMetadataState()
        val source = source(client, state, cache)

        val result = source.refresh(mapOf(99_000_001 to "Sovereignty Alliance"))

        assertEquals(1, result.requestedCount)
        assertEquals(1, result.updatedCount)
        assertTrue(result.failures.isEmpty())
        val metadata = state.find(99_000_001)!!
        assertEquals("ESI Alliance", metadata.name)
        assertEquals("ESI", metadata.ticker)
        assertEquals("Sovereignty Alliance", metadata.observedSovereigntyName)
        assertEquals("\"v1\"", metadata.etag)
        assertEquals(LAST_MODIFIED, metadata.lastModified)
        assertEquals(NOW.plusSeconds(3600), metadata.freshUntil)
        assertEquals(listOf(metadata), cache.savedRecords)
    }

    @Test
    fun `temporary failure retains last-good metadata without rewriting cache`() {
        val lastGood = metadata(freshUntil = NOW.minusSeconds(1))
        val state = AllianceMetadataState(listOf(lastGood))
        val cache = RecordingMetadataCache()
        val source = source(
            MetadataClient { _, validators ->
                assertEquals(AllianceMetadataValidators("\"old\"", LAST_MODIFIED), validators)
                PublicEsiAllianceMetadataResult.Unavailable("offline")
            },
            state,
            cache,
        )

        val result = source.refresh(mapOf(99_000_001 to "Observed Alliance"))

        assertEquals(listOf("offline"), result.failures)
        assertEquals(lastGood, state.find(99_000_001))
        assertTrue(cache.savedRecords.isEmpty())
    }

    @Test
    fun `304 keeps cached metadata and refreshes cache lifetime`() {
        val lastGood = metadata(freshUntil = NOW.minusSeconds(1))
        val state = AllianceMetadataState(listOf(lastGood))
        val cache = RecordingMetadataCache()
        val source = source(
            MetadataClient { _, validators ->
                assertEquals(AllianceMetadataValidators("\"old\"", LAST_MODIFIED), validators)
                PublicEsiAllianceMetadataResult.NotModified(EsiCacheHeaders(cacheControl = "max-age=600"))
            },
            state,
            cache,
        )

        val result = source.refresh(mapOf(99_000_001 to "Observed Alliance"))

        assertTrue(result.failures.isEmpty())
        val refreshed = state.find(99_000_001)!!
        assertEquals("ALLY", refreshed.ticker)
        assertEquals("\"old\"", refreshed.etag)
        assertEquals(LAST_MODIFIED, refreshed.lastModified)
        assertEquals("max-age=600", refreshed.cacheControl)
        assertEquals(NOW.plusSeconds(600), refreshed.freshUntil)
        assertEquals(NOW, refreshed.lastSuccessfulAt)
        assertEquals(listOf(refreshed), cache.savedRecords)
    }

    @Test
    fun `one Alliance failure does not block other observed Alliance metadata`() {
        val state = AllianceMetadataState()
        val cache = RecordingMetadataCache()
        val client = MetadataClient { allianceId, _ ->
            if (allianceId == 99_000_001) {
                PublicEsiAllianceMetadataResult.Unavailable("first unavailable")
            } else {
                PublicEsiAllianceMetadataResult.Updated(
                    """{"name":"Second Alliance","ticker":"TWO"}""",
                    EsiCacheHeaders(cacheControl = "max-age=60"),
                )
            }
        }
        val result = source(client, state, cache).refresh(
            mapOf(99_000_001 to "First", 99_000_002 to "Second"),
        )

        assertEquals(2, result.requestedCount)
        assertEquals(1, result.updatedCount)
        assertEquals(listOf("first unavailable"), result.failures)
        assertEquals(null, state.find(99_000_001))
        assertEquals("TWO", state.find(99_000_002)?.ticker)
    }

    @Test
    fun `fresh last-good metadata performs no request`() {
        val state = AllianceMetadataState(listOf(metadata(freshUntil = NOW.plusSeconds(1))))
        val client = MetadataClient { _, _ -> error("must not request fresh metadata") }
        val source = source(client, state, RecordingMetadataCache())

        assertFalse(source.requiresRefresh(mapOf(99_000_001 to "Observed Alliance")))
        assertEquals(0, source.refresh(mapOf(99_000_001 to "Observed Alliance")).requestedCount)
        assertEquals(0, client.requests)
    }

    private fun source(
        client: MetadataClient,
        state: AllianceMetadataState,
        cache: RecordingMetadataCache,
    ) = PublicEsiAllianceMetadataSource(client, state, cache, FIXED_CLOCK)

    private class MetadataClient(
        private val result: (Int, AllianceMetadataValidators) -> PublicEsiAllianceMetadataResult,
    ) : PublicEsiClient {
        var requests = 0

        override fun fetchSovereigntySystems() = PublicEsiPayloadResult.Unavailable("not used")
        override fun resolveNames(ids: List<Int>) = PublicEsiPayloadResult.Unavailable("not used")

        override fun fetchAllianceMetadata(
            allianceId: Int,
            validators: AllianceMetadataValidators,
        ): PublicEsiAllianceMetadataResult {
            requests += 1
            return result(allianceId, validators)
        }
    }

    private class RecordingMetadataCache : AllianceMetadataCache {
        var savedRecords = emptyList<AllianceMetadata>()

        override fun load() = AllianceMetadataCacheLoadResult.Miss

        override fun save(records: List<AllianceMetadata>): AllianceMetadataCacheSaveResult {
            savedRecords = records
            return AllianceMetadataCacheSaveResult.Saved
        }
    }

    private fun metadata(freshUntil: Instant) = AllianceMetadata(
        allianceId = 99_000_001,
        name = "Alliance",
        ticker = "ALLY",
        observedSovereigntyName = "Observed Alliance",
        etag = "\"old\"",
        lastModified = LAST_MODIFIED,
        cacheControl = "max-age=3600",
        freshUntil = freshUntil,
        lastSuccessfulAt = NOW.minusSeconds(3600),
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-23T12:00:00Z")
        val FIXED_CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
        const val LAST_MODIFIED = "Wed, 23 Sep 2026 10:00:00 GMT"
    }
}
