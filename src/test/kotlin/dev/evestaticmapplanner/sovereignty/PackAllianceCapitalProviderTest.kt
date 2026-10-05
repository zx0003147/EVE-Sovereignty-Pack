package dev.evestaticmapplanner.sovereignty

import dev.evestaticmapplanner.feature.api.SovereigntyFreshnessDto
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackAllianceCapitalProviderTest {
    @Test
    fun `fresh snapshot publishes only true capital records for multiple alliances`() {
        val provider = provider(
            records = listOf(
                record(99_000_001, 30_004_759, isCapital = true),
                record(99_000_001, 30_004_760, isCapital = false),
                record(99_000_002, 30_004_761, isCapital = true),
            ),
        )

        val snapshot = provider.snapshot()

        assertEquals(SovereigntyFreshnessDto.AVAILABLE, snapshot.freshness)
        assertEquals(
            setOf(99_000_001L to 30_004_759, 99_000_002L to 30_004_761),
            snapshot.records.map { it.allianceId to it.capitalSystemId }.toSet(),
        )
        assertEquals(OBSERVED_AT, snapshot.observedAt)
        assertEquals(SovereigntyPublicationState.PUBLIC_ESI_SOURCE, snapshot.source)
    }

    @Test
    fun `non-capital and alliance without a true claim publish no fake record`() {
        val snapshot = provider(
            records = listOf(record(99_000_001, 30_004_759, isCapital = false)),
        ).snapshot()

        assertTrue(snapshot.records.isEmpty())
        assertEquals(SovereigntyFreshnessDto.AVAILABLE, snapshot.freshness)
    }

    @Test
    fun `multiple true claims for one alliance are all preserved for Host ambiguity detection`() {
        val snapshot = provider(
            records = listOf(
                record(99_000_001, 30_004_759, isCapital = true),
                record(99_000_001, 30_004_760, isCapital = true),
            ),
        ).snapshot()

        assertEquals(listOf(30_004_759, 30_004_760), snapshot.records.map { it.capitalSystemId })
    }

    @Test
    fun `failed refresh retains capital records as stale`() {
        val state = state(
            records = listOf(record(99_000_001, 30_004_759, isCapital = true)),
        )
        state.publishFailure("offline")

        val snapshot = PackAllianceCapitalProvider(state).snapshot()

        assertEquals(SovereigntyFreshnessDto.STALE, snapshot.freshness)
        assertEquals(30_004_759, snapshot.records.single().capitalSystemId)
        assertEquals("offline", snapshot.errorMessage)
    }

    @Test
    fun `no records and no last good data are unavailable`() {
        val snapshot = provider(
            records = emptyList(),
            freshness = PublishedSovereigntyFreshness.UNAVAILABLE,
        ).snapshot()

        assertEquals(SovereigntyFreshnessDto.UNAVAILABLE, snapshot.freshness)
        assertTrue(snapshot.records.isEmpty())
    }

    @Test
    fun `legacy cache without capital flags stays unavailable until refresh`() {
        val snapshot = provider(
            records = listOf(
                record(99_000_001, 30_004_759, isCapital = null),
            ),
            freshness = PublishedSovereigntyFreshness.STALE,
        ).snapshot()

        assertEquals(SovereigntyFreshnessDto.UNAVAILABLE, snapshot.freshness)
        assertTrue(snapshot.records.isEmpty())
        assertTrue(snapshot.errorMessage.orEmpty().contains("predates Alliance Capital"))
    }

    private fun provider(
        records: List<SovereigntyRecord>,
        freshness: PublishedSovereigntyFreshness = PublishedSovereigntyFreshness.AVAILABLE,
    ) = PackAllianceCapitalProvider(state(records, freshness))

    private fun state(
        records: List<SovereigntyRecord>,
        freshness: PublishedSovereigntyFreshness = PublishedSovereigntyFreshness.AVAILABLE,
    ) = SovereigntyPublicationState(
        PublishedSovereigntySnapshot(
            records = records,
            observedAt = OBSERVED_AT,
            source = SovereigntyPublicationState.PUBLIC_ESI_SOURCE,
            freshness = freshness,
        ),
    )

    private fun record(
        allianceId: Int,
        systemId: Int,
        isCapital: Boolean?,
    ) = SovereigntyRecord(
        systemId = systemId,
        allianceName = "Alliance $allianceId",
        corporationName = null,
        sovereigntyStatus = PUBLIC_ESI_CLAIMED_STATUS,
        allianceId = allianceId,
        isCapitalSystem = isCapital,
    )

    private companion object {
        val OBSERVED_AT: Instant = Instant.parse("2026-10-05T00:00:00Z")
    }
}
