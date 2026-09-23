package dev.evestaticmapplanner.sovereignty

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SovereigntyAllianceDirectoryProviderTest {
    @Test
    fun `duplicate systems publish one immediate Alliance ID and sovereignty name`() {
        val repository = SovereigntyRepository(
            SovereigntySnapshot(
                listOf(
                    record(30_000_001, 99_000_001, "Observed Alliance"),
                    record(30_000_002, 99_000_001, "Observed Alliance"),
                ),
            ),
        )

        val alliance = SovereigntyAllianceDirectoryProvider(repository, AllianceMetadataState())
            .snapshot().alliances.single()

        assertEquals(99_000_001L, alliance.allianceId)
        assertEquals("Observed Alliance", alliance.name)
        assertNull(alliance.ticker)
    }

    @Test
    fun `metadata enriches name and ticker while stable ID remains the entity`() {
        val repository = SovereigntyRepository(
            SovereigntySnapshot(listOf(record(30_000_001, 99_000_001, "Observed Alliance"))),
        )
        val metadata = AllianceMetadataState(
            listOf(metadata(99_000_001, "ESI Alliance", "ESI", "Observed Alliance")),
        )
        val provider = SovereigntyAllianceDirectoryProvider(repository, metadata)

        val enriched = provider.snapshot().alliances.single()
        assertEquals(99_000_001L, enriched.allianceId)
        assertEquals("ESI Alliance", enriched.name)
        assertEquals("ESI", enriched.ticker)

        repository.replace(
            SovereigntySnapshot(listOf(record(30_000_001, 99_000_001, "Renamed Alliance"))),
        )
        val renamed = provider.snapshot().alliances.single()
        assertEquals(99_000_001L, renamed.allianceId)
        assertEquals("Renamed Alliance", renamed.name)
        assertEquals("ESI", renamed.ticker)
    }

    @Test
    fun `different IDs with the same name remain distinct and legacy identity is skipped`() {
        val repository = SovereigntyRepository(
            SovereigntySnapshot(
                listOf(
                    record(30_000_001, 99_000_001, "Shared Name"),
                    record(30_000_002, 99_000_002, "Shared Name"),
                    SovereigntyRecord(30_000_003, "Legacy Name", null, PUBLIC_ESI_CLAIMED_STATUS, null),
                ),
            ),
        )

        val alliances = SovereigntyAllianceDirectoryProvider(repository, AllianceMetadataState())
            .snapshot().alliances

        assertEquals(listOf(99_000_001L, 99_000_002L), alliances.map { it.allianceId })
        assertEquals(listOf("Shared Name", "Shared Name"), alliances.map { it.name })
    }

    @Test
    fun `repository shrink stops publication without deleting historical metadata`() {
        val repository = SovereigntyRepository(
            SovereigntySnapshot(
                listOf(
                    record(30_000_001, 99_000_001, "One"),
                    record(30_000_002, 99_000_002, "Two"),
                ),
            ),
        )
        val metadata = AllianceMetadataState(
            listOf(
                metadata(99_000_001, "One", "ONE", "One"),
                metadata(99_000_002, "Two", "TWO", "Two"),
            ),
        )
        val provider = SovereigntyAllianceDirectoryProvider(repository, metadata)

        repository.replace(SovereigntySnapshot(listOf(record(30_000_001, 99_000_001, "One"))))

        assertEquals(listOf(99_000_001L), provider.snapshot().alliances.map { it.allianceId })
        assertEquals(setOf(99_000_001, 99_000_002), metadata.records().map { it.allianceId }.toSet())
    }

    private fun record(systemId: Int, allianceId: Int, name: String) =
        SovereigntyRecord(systemId, name, null, PUBLIC_ESI_CLAIMED_STATUS, allianceId)

    private fun metadata(allianceId: Int, name: String, ticker: String, observedName: String) = AllianceMetadata(
        allianceId = allianceId,
        name = name,
        ticker = ticker,
        observedSovereigntyName = observedName,
        etag = "\"$allianceId\"",
        lastModified = "Wed, 23 Sep 2026 10:00:00 GMT",
        cacheControl = "max-age=3600",
        freshUntil = Instant.parse("2026-09-23T13:00:00Z"),
        lastSuccessfulAt = Instant.parse("2026-09-23T12:00:00Z"),
    )
}
