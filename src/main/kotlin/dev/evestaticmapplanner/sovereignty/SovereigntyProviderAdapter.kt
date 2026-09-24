package dev.evestaticmapplanner.sovereignty

import dev.evestaticmapplanner.feature.api.FeaturePackContext
import dev.evestaticmapplanner.feature.api.StandardFeatureCapabilities

/** Loaded reflectively only after the complete Feature API 2.5 Sovereignty contract is present. */
internal class SovereigntyProviderAdapter private constructor() {
    companion object {
        @JvmStatic
        fun register(
            context: FeaturePackContext,
            publicationState: SovereigntyPublicationState,
            refreshRequest: SovereigntyRefreshRequest,
        ): SovereigntyProviderRegistration {
            val capability = context.capabilities().find(StandardFeatureCapabilities.SOVEREIGNTY)
                ?: return NoSovereigntyProviderRegistration
            val registration = capability.register(PackSovereigntyProvider(publicationState, refreshRequest))
            return object : SovereigntyProviderRegistration {
                override val active: Boolean = true
                override fun requestRefresh() = registration.requestRefresh()
                override fun close() = registration.close()
            }
        }
    }
}
