package dev.evestaticmapplanner.sovereignty

import dev.evestaticmapplanner.feature.api.SovereigntyFreshnessDto
import dev.evestaticmapplanner.feature.api.SovereigntyOwnerKindDto
import dev.evestaticmapplanner.feature.api.SovereigntyProvider
import dev.evestaticmapplanner.feature.api.SovereigntySnapshotDto
import dev.evestaticmapplanner.feature.api.SovereigntyStatusDto
import dev.evestaticmapplanner.feature.api.SystemOwnershipDto

/** Pure in-memory mapping. Calling snapshot never performs HTTP, cache I/O, or refresh work. */
internal class PackSovereigntyProvider(
    private val state: SovereigntyPublicationState,
) : SovereigntyProvider {
    override fun snapshot(): SovereigntySnapshotDto {
        val current = state.snapshot()
        val freshness = current.freshness.toApi()
        val systems = if (freshness == SovereigntyFreshnessDto.UNAVAILABLE) {
            emptyList()
        } else {
            current.records.map { record ->
                SystemOwnershipDto(
                    systemId = record.systemId,
                    ownerKind = if (record.allianceId != null) {
                        SovereigntyOwnerKindDto.ALLIANCE
                    } else {
                        SovereigntyOwnerKindDto.UNKNOWN
                    },
                    allianceId = record.allianceId?.toLong(),
                    allianceName = record.allianceName,
                    corporationId = record.corporationId?.toLong(),
                    corporationName = record.corporationName,
                    sovereigntyStatus = if (record.sovereigntyStatus == PUBLIC_ESI_CLAIMED_STATUS) {
                        SovereigntyStatusDto.CLAIMED
                    } else {
                        SovereigntyStatusDto.UNKNOWN
                    },
                )
            }
        }
        return SovereigntySnapshotDto(
            systems = systems,
            observedAt = current.observedAt,
            source = current.source,
            freshness = freshness,
            errorMessage = current.errorMessage,
        )
    }
}

private fun PublishedSovereigntyFreshness.toApi() = when (this) {
    PublishedSovereigntyFreshness.AVAILABLE -> SovereigntyFreshnessDto.AVAILABLE
    PublishedSovereigntyFreshness.STALE -> SovereigntyFreshnessDto.STALE
    PublishedSovereigntyFreshness.UNAVAILABLE -> SovereigntyFreshnessDto.UNAVAILABLE
}
