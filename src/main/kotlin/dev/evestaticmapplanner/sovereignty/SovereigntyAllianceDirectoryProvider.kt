package dev.evestaticmapplanner.sovereignty

import dev.evestaticmapplanner.feature.api.AllianceDirectoryProvider
import dev.evestaticmapplanner.feature.api.AllianceDirectoryProviderSnapshot
import dev.evestaticmapplanner.feature.api.AllianceReferenceSnapshot

internal class SovereigntyAllianceDirectoryProvider(
    private val repository: SovereigntyRepository,
    private val metadataState: AllianceMetadataState,
) : AllianceDirectoryProvider {
    override fun snapshot(): AllianceDirectoryProviderSnapshot {
        val observed = repository.observedAllianceNames()
        val references = observed.toSortedMap().map { (allianceId, sovereigntyName) ->
            val metadata = metadataState.find(allianceId)
            AllianceReferenceSnapshot(
                allianceId = allianceId.toLong(),
                name = metadata
                    ?.takeIf { it.observedSovereigntyName == sovereigntyName }
                    ?.name
                    ?: sovereigntyName,
                ticker = metadata?.ticker,
            )
        }
        val observedAt = observed.keys.mapNotNull { metadataState.find(it)?.lastSuccessfulAt }.maxOrNull()
        return AllianceDirectoryProviderSnapshot(references, observedAt = observedAt)
    }
}
