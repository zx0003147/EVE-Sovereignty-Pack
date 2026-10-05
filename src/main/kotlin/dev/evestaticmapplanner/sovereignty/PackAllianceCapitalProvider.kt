package dev.evestaticmapplanner.sovereignty

import dev.evestaticmapplanner.feature.api.AllianceCapitalProvider
import dev.evestaticmapplanner.feature.api.AllianceCapitalRecordDto
import dev.evestaticmapplanner.feature.api.AllianceCapitalSnapshotDto
import dev.evestaticmapplanner.feature.api.SovereigntyFreshnessDto

/** Pure in-memory view over the same sovereignty publication used by [PackSovereigntyProvider]. */
internal class PackAllianceCapitalProvider(
    private val state: SovereigntyPublicationState,
    private val refreshRequest: SovereigntyRefreshRequest = SovereigntyRefreshRequest(),
) : AllianceCapitalProvider {
    override fun snapshot(): AllianceCapitalSnapshotDto {
        val current = state.snapshot()
        val missingCapitalFlags = current.records.any { it.isCapitalSystem == null }
        val freshness = if (missingCapitalFlags) {
            SovereigntyFreshnessDto.UNAVAILABLE
        } else {
            current.freshness.toAllianceCapitalApi()
        }
        val records = if (freshness == SovereigntyFreshnessDto.UNAVAILABLE) {
            emptyList()
        } else {
            current.records.asSequence()
                .filter { it.isCapitalSystem == true }
                .map { record ->
                    AllianceCapitalRecordDto(
                        allianceId = requireNotNull(record.allianceId).toLong(),
                        capitalSystemId = record.systemId,
                    )
                }
                .toList()
        }
        return AllianceCapitalSnapshotDto(
            records = records,
            observedAt = current.observedAt,
            source = current.source,
            freshness = freshness,
            errorMessage = if (missingCapitalFlags) {
                "Cached sovereignty snapshot predates Alliance Capital data; refresh required"
            } else {
                current.errorMessage
            },
        )
    }

    override fun requestRefresh(): Boolean = refreshRequest.request()
}

private fun PublishedSovereigntyFreshness.toAllianceCapitalApi() = when (this) {
    PublishedSovereigntyFreshness.AVAILABLE -> SovereigntyFreshnessDto.AVAILABLE
    PublishedSovereigntyFreshness.STALE -> SovereigntyFreshnessDto.STALE
    PublishedSovereigntyFreshness.UNAVAILABLE -> SovereigntyFreshnessDto.UNAVAILABLE
}
