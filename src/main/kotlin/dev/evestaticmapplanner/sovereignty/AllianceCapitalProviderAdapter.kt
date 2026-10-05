package dev.evestaticmapplanner.sovereignty

import dev.evestaticmapplanner.feature.api.FeaturePackContext
import dev.evestaticmapplanner.feature.api.StandardFeatureCapabilities

/** Loaded reflectively only after the complete Feature API 2.6 Alliance Capital contract is present. */
internal class AllianceCapitalProviderAdapter private constructor() {
    companion object {
        @JvmStatic
        fun register(
            context: FeaturePackContext,
            publicationState: SovereigntyPublicationState,
            refreshRequest: SovereigntyRefreshRequest,
        ): AllianceCapitalProviderRegistration {
            val capability = context.capabilities().find(StandardFeatureCapabilities.ALLIANCE_CAPITAL)
                ?: return NoAllianceCapitalProviderRegistration
            val registration = capability.register(PackAllianceCapitalProvider(publicationState, refreshRequest))
            return object : AllianceCapitalProviderRegistration {
                override val active: Boolean = true
                override fun requestRefresh() = registration.requestRefresh()
                override fun close() = registration.close()
            }
        }
    }
}
