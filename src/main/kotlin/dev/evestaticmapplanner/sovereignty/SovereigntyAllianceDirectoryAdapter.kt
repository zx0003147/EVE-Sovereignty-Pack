package dev.evestaticmapplanner.sovereignty

import dev.evestaticmapplanner.feature.api.FeaturePackContext
import dev.evestaticmapplanner.feature.api.StandardFeatureCapabilities

/** Loaded reflectively only after the 2.4 Alliance Directory API is known to be present. */
internal class SovereigntyAllianceDirectoryAdapter private constructor() {
    companion object {
        @JvmStatic
        fun register(
            context: FeaturePackContext,
            repository: SovereigntyRepository,
            metadataState: AllianceMetadataState,
        ): SovereigntyAllianceDirectoryRegistration {
            val capability = context.capabilities().find(StandardFeatureCapabilities.ALLIANCE_DIRECTORY)
                ?: return NoSovereigntyAllianceDirectoryRegistration
            val registration = capability.register(
                SovereigntyAllianceDirectoryProvider(repository, metadataState),
            )
            return object : SovereigntyAllianceDirectoryRegistration {
                override val active: Boolean = true

                override fun requestRefresh() = registration.requestRefresh()

                override fun close() = registration.close()
            }
        }
    }
}
