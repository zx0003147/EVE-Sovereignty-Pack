package dev.evestaticmapplanner.sovereignty

import dev.evestaticmapplanner.feature.api.OverlayEntry
import dev.evestaticmapplanner.feature.api.OverlayLayer
import dev.evestaticmapplanner.feature.api.OverlayProvider
import dev.evestaticmapplanner.feature.api.OverlayProviderDescriptor
import dev.evestaticmapplanner.feature.api.OverlaySnapshot

internal class SovereigntyOverlayProvider(
    private val repository: SovereigntyRepository,
) : OverlayProvider {
    override fun descriptor() = OverlayProviderDescriptor(
        id = "sovereignty.pack.overlay",
        name = "Sovereignty",
        description = "Static sovereignty ownership supplied by the Sovereignty Pack",
    )

    override fun layers() = listOf(
        OverlayLayer(
            id = LAYER_ID,
            name = "Sovereignty",
            description = "Alliance sovereignty by solar system",
            priority = 20,
        ),
    )

    override fun snapshot(): OverlaySnapshot = OverlaySnapshot(repository.records().map { record ->
        OverlayEntry(
            layerId = LAYER_ID,
            systemId = record.systemId,
            title = record.allianceName,
            subtitle = record.sovereigntyStatus,
            value = SovereigntyVisualIdentity.presentationMetadata(record),
        )
    })

    private companion object {
        const val LAYER_ID = "sovereignty"
    }
}
