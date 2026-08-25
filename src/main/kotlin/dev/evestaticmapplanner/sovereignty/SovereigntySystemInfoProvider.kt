package dev.evestaticmapplanner.sovereignty

import dev.evestaticmapplanner.feature.api.SystemInfoField
import dev.evestaticmapplanner.feature.api.SystemInfoProvider
import dev.evestaticmapplanner.feature.api.SystemInfoProviderDescriptor
import dev.evestaticmapplanner.feature.api.SystemInfoSection
import dev.evestaticmapplanner.feature.api.SystemInfoSnapshot

internal class SovereigntySystemInfoProvider(
    private val repository: SovereigntyRepository,
) : SystemInfoProvider {
    override fun descriptor() = SystemInfoProviderDescriptor(
        id = "sovereignty.pack.system-info",
        name = "Sovereignty",
        priority = 20,
    )

    override fun provide(systemId: Int): SystemInfoSnapshot {
        val record = repository.find(systemId)
        return SystemInfoSnapshot(
            systemId = systemId,
            sections = record?.let {
                listOf(
                    SystemInfoSection(
                        sectionId = "sovereignty",
                        title = "Sovereignty",
                        priority = 20,
                        fields = listOf(
                            SystemInfoField("owner", "Owner", it.allianceName),
                            SystemInfoField("status", "Status", it.sovereigntyStatus),
                        ),
                    ),
                )
            }.orEmpty(),
        )
    }
}
