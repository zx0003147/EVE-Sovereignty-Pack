package dev.evestaticmapplanner.sovereignty

import dev.evestaticmapplanner.feature.api.FeaturePackContext
import dev.evestaticmapplanner.feature.api.FeaturePackDescriptor
import dev.evestaticmapplanner.feature.api.FeaturePackEntrypoint
import dev.evestaticmapplanner.feature.api.FeaturePackLogLevel
import dev.evestaticmapplanner.feature.api.FeaturePackLogger
import dev.evestaticmapplanner.feature.api.FeaturePackSession
import dev.evestaticmapplanner.feature.api.FeaturePackStartupException
import dev.evestaticmapplanner.feature.api.OverlayRegistration
import dev.evestaticmapplanner.feature.api.PackId
import dev.evestaticmapplanner.feature.api.PackVersion
import dev.evestaticmapplanner.feature.api.SystemInfoRegistration
import java.util.concurrent.atomic.AtomicBoolean

class SovereigntyFeaturePack internal constructor(
    private val runtimeComposition: SovereigntyRuntimeComposition,
) : FeaturePackEntrypoint {
    constructor() : this(SovereigntyRuntimeComposition.production())

    internal val dataSourceMode: SovereigntyDataSourceMode
        get() = runtimeComposition.dataSourceMode

    override fun descriptor() = FeaturePackDescriptor(
        packId = PackId("sovereignty.pack"),
        displayName = "Sovereignty Pack",
        packVersion = PackVersion("0.1.0"),
        publisher = "EVE Static Map Planner",
    )

    override fun start(context: FeaturePackContext): FeaturePackSession {
        // With no usable LKG this remains a synchronous PUBLIC_ESI load during Pack startup in SV-3C-2.
        val repository = runtimeComposition.createRepository(context.storage(), context.logger())
        repository.metadata.failureMessage?.let { failureMessage ->
            context.logger().log(
                FeaturePackLogLevel.WARN,
                "Sovereignty snapshot could not be loaded from $dataSourceMode; providers will remain empty",
                IllegalStateException(failureMessage),
            )
        }
        if (repository.metadata.ignoredRecordCount > 0) {
            context.logger().log(
                FeaturePackLogLevel.WARN,
                "Ignored ${repository.metadata.ignoredRecordCount} invalid or duplicate sovereignty record(s)",
                null,
            )
        }

        var overlayRegistration: OverlayRegistration? = null
        var systemInfoRegistration: SystemInfoRegistration? = null
        try {
            overlayRegistration = context.overlays().register(SovereigntyOverlayProvider(repository))
            systemInfoRegistration = context.systemInfo().register(SovereigntySystemInfoProvider(repository))
            context.logger().log(FeaturePackLogLevel.INFO, "Sovereignty Pack started", null)
            return SovereigntySession(
                overlayRegistration = overlayRegistration,
                systemInfoRegistration = systemInfoRegistration,
                logger = context.logger(),
            )
        } catch (error: Throwable) {
            runCatching { systemInfoRegistration?.close() }
            runCatching { overlayRegistration?.close() }
            throw FeaturePackStartupException("Could not register Sovereignty Pack providers", error)
        }
    }

    private class SovereigntySession(
        private val overlayRegistration: OverlayRegistration,
        private val systemInfoRegistration: SystemInfoRegistration,
        private val logger: FeaturePackLogger,
    ) : FeaturePackSession {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            var failure: Throwable? = null
            try {
                systemInfoRegistration.close()
            } catch (error: Throwable) {
                failure = error
            }
            try {
                overlayRegistration.close()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
            logger.log(FeaturePackLogLevel.INFO, "Sovereignty Pack stopped", failure)
            failure?.let { throw it }
        }
    }
}
