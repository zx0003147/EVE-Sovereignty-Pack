package dev.evestaticmapplanner.sovereignty

import dev.evestaticmapplanner.feature.api.SovereigntyFreshnessDto
import dev.evestaticmapplanner.feature.api.SovereigntyOwnerKindDto
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PackSovereigntyProviderTest {
    @Test
    fun `provider maps repository records to typed ownership without network work`() {
        val observedAt = Instant.parse("2026-09-24T00:00:00Z")
        val state = SovereigntyPublicationState(
            PublishedSovereigntySnapshot(
                records = listOf(
                    SovereigntyRecord(
                        systemId = 30_004_759,
                        allianceName = "Alliance",
                        corporationName = "Corporation",
                        sovereigntyStatus = PUBLIC_ESI_CLAIMED_STATUS,
                        allianceId = 99_000_001,
                        corporationId = 98_000_001,
                    ),
                ),
                observedAt = observedAt,
                source = SovereigntyPublicationState.PUBLIC_ESI_SOURCE,
                freshness = PublishedSovereigntyFreshness.AVAILABLE,
            ),
        )

        val snapshot = PackSovereigntyProvider(state).snapshot()
        val ownership = snapshot.systems.single()

        assertEquals(30_004_759, ownership.systemId)
        assertEquals(SovereigntyOwnerKindDto.ALLIANCE, ownership.ownerKind)
        assertEquals(99_000_001L, ownership.allianceId)
        assertEquals(98_000_001L, ownership.corporationId)
        assertEquals(observedAt, snapshot.observedAt)
        assertEquals(SovereigntyFreshnessDto.AVAILABLE, snapshot.freshness)
    }

    @Test
    fun `legacy name-only record remains unknown instead of inventing stable identity`() {
        val state = SovereigntyPublicationState(
            PublishedSovereigntySnapshot(
                records = listOf(
                    SovereigntyRecord(30_004_759, "Legacy Name", null, PUBLIC_ESI_CLAIMED_STATUS),
                ),
                observedAt = null,
                source = SovereigntyPublicationState.PUBLIC_ESI_SOURCE,
                freshness = PublishedSovereigntyFreshness.STALE,
            ),
        )

        val ownership = PackSovereigntyProvider(state).snapshot().systems.single()

        assertEquals(SovereigntyOwnerKindDto.UNKNOWN, ownership.ownerKind)
        assertNull(ownership.allianceId)
        assertEquals("Legacy Name", ownership.allianceName)
    }

    @Test
    fun `failed refresh retains last good records as stale`() {
        val state = SovereigntyPublicationState(
            PublishedSovereigntySnapshot(
                records = listOf(
                    SovereigntyRecord(30_004_759, "Alliance", null, PUBLIC_ESI_CLAIMED_STATUS, 99_000_001),
                ),
                observedAt = Instant.parse("2026-09-24T00:00:00Z"),
                source = SovereigntyPublicationState.PUBLIC_ESI_SOURCE,
                freshness = PublishedSovereigntyFreshness.AVAILABLE,
            ),
        )

        state.publishFailure("offline")
        val snapshot = PackSovereigntyProvider(state).snapshot()

        assertEquals(SovereigntyFreshnessDto.STALE, snapshot.freshness)
        assertEquals(99_000_001L, snapshot.systems.single().allianceId)
        assertEquals("offline", snapshot.errorMessage)
    }

    @Test
    fun `typed sovereignty and Alliance Directory publish the same stable Alliance ID`() {
        val record = SovereigntyRecord(
            30_004_759,
            "Alliance",
            null,
            PUBLIC_ESI_CLAIMED_STATUS,
            99_000_001,
        )
        val state = SovereigntyPublicationState(
            PublishedSovereigntySnapshot(
                records = listOf(record),
                observedAt = null,
                source = SovereigntyPublicationState.PUBLIC_ESI_SOURCE,
                freshness = PublishedSovereigntyFreshness.AVAILABLE,
            ),
        )
        val typedId = PackSovereigntyProvider(state).snapshot().systems.single().allianceId
        val directoryId = SovereigntyAllianceDirectoryProvider(
            SovereigntyRepository(SovereigntySnapshot(listOf(record))),
            AllianceMetadataState(),
        ).snapshot().alliances.single().allianceId

        assertEquals(directoryId, typedId)
    }
}
