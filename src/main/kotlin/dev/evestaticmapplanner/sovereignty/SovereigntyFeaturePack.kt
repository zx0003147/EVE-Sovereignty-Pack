package dev.evestaticmapplanner.sovereignty

import dev.evestaticmapplanner.feature.api.FeaturePackContext
import dev.evestaticmapplanner.feature.api.FeaturePackDescriptor
import dev.evestaticmapplanner.feature.api.FeaturePackEntrypoint
import dev.evestaticmapplanner.feature.api.FeaturePackLogLevel
import dev.evestaticmapplanner.feature.api.FeaturePackLogger
import dev.evestaticmapplanner.feature.api.FeaturePackSession
import dev.evestaticmapplanner.feature.api.FeaturePackStartupException
import dev.evestaticmapplanner.feature.api.DynamicOverlayRegistration
import dev.evestaticmapplanner.feature.api.OverlayLayer
import dev.evestaticmapplanner.feature.api.OverlayProvider
import dev.evestaticmapplanner.feature.api.OverlayProviderDescriptor
import dev.evestaticmapplanner.feature.api.OverlayRegistration
import dev.evestaticmapplanner.feature.api.OverlaySnapshot
import dev.evestaticmapplanner.feature.api.PackId
import dev.evestaticmapplanner.feature.api.PackVersion
import dev.evestaticmapplanner.feature.api.StandardFeatureCapabilities
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
        packVersion = PackVersion(PackBuildMetadata.PACK_VERSION),
        publisher = "EVE Static Map Planner",
    )

    override fun start(context: FeaturePackContext): FeaturePackSession {
        // Startup performs only local work. PUBLIC_ESI refresh runs through Host's dynamic-overlay worker.
        val activation = runtimeComposition.createActivation(context.storage(), context.logger())
        val repository = SovereigntyRepository(activation.initialSnapshot)
        repository.metadata.failureMessage?.let { failureMessage ->
            context.logger().log(
                FeaturePackLogLevel.WARN,
                "Sovereignty snapshot could not be loaded from $dataSourceMode: $failureMessage",
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
        var refreshCoordinator: SovereigntyRefreshCoordinator? = null
        try {
            val overlayProvider = SovereigntyOverlayProvider(repository)
            val dynamicOverlay = activation.refreshSource?.let {
                context.capabilities().find(StandardFeatureCapabilities.DYNAMIC_OVERLAY)
            }
            val dynamicRegistration = if (activation.refreshSource != null && dynamicOverlay != null) {
                refreshCoordinator = SovereigntyRefreshCoordinator(
                    repository = repository,
                    source = activation.refreshSource,
                    logger = context.logger(),
                )
                dynamicOverlay.register(
                    RefreshingSovereigntyOverlayProvider(overlayProvider, refreshCoordinator::refreshOnce),
                )
            } else {
                if (activation.refreshRequired) {
                    context.logger().log(
                        FeaturePackLogLevel.WARN,
                        "Host does not expose Dynamic Overlay capability; PUBLIC_ESI background refresh is unavailable",
                        null,
                    )
                }
                activation.refreshSource?.close()
                null
            }
            overlayRegistration = dynamicRegistration ?: context.overlays().register(overlayProvider)
            systemInfoRegistration = context.systemInfo().register(SovereigntySystemInfoProvider(repository))
            refreshCoordinator?.attachSystemInfoRefresh(systemInfoRegistration::refresh)
            context.logger().log(FeaturePackLogLevel.INFO, "Sovereignty Pack started", null)
            val session = SovereigntySession(
                overlayRegistration = overlayRegistration,
                systemInfoRegistration = systemInfoRegistration,
                refreshCoordinator = refreshCoordinator,
                logger = context.logger(),
            )
            if (activation.refreshRequired) dynamicRegistration?.requestRefresh()
            return session
        } catch (error: Throwable) {
            runCatching { refreshCoordinator?.close() }
            runCatching { systemInfoRegistration?.close() }
            runCatching { overlayRegistration?.close() }
            if (refreshCoordinator == null) runCatching { activation.refreshSource?.close() }
            throw FeaturePackStartupException("Could not register Sovereignty Pack providers", error)
        }
    }

    private class SovereigntySession(
        private val overlayRegistration: OverlayRegistration,
        private val systemInfoRegistration: SystemInfoRegistration,
        private val refreshCoordinator: SovereigntyRefreshCoordinator?,
        private val logger: FeaturePackLogger,
    ) : FeaturePackSession {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            var failure: Throwable? = null
            try {
                refreshCoordinator?.close()
            } catch (error: Throwable) {
                failure = error
            }
            try {
                systemInfoRegistration.close()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
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

/** First snapshot is the already-loaded LKG; only Host invalidation performs the remote refresh. */
private class RefreshingSovereigntyOverlayProvider(
    private val delegate: SovereigntyOverlayProvider,
    private val refresh: () -> Unit,
) : OverlayProvider {
    private val initialSnapshotPublished = AtomicBoolean(false)

    override fun descriptor(): OverlayProviderDescriptor = delegate.descriptor()

    override fun layers(): List<OverlayLayer> = delegate.layers()

    override fun snapshot(): OverlaySnapshot {
        if (!initialSnapshotPublished.compareAndSet(false, true)) refresh()
        return delegate.snapshot()
    }
}

/** Serializes the single refresh commit against disable/shutdown and owns the remote resource. */
private class SovereigntyRefreshCoordinator(
    private val repository: SovereigntyRepository,
    private val source: CachedRemoteSovereigntySource,
    private val logger: FeaturePackLogger,
) : AutoCloseable {
    private val lock = Any()
    private var state = RefreshState.NOT_STARTED
    private var systemInfoRefresh: () -> Unit = {}

    fun attachSystemInfoRefresh(refresh: () -> Unit) = synchronized(lock) {
        check(state != RefreshState.CLOSED) { "Sovereignty refresh coordinator is closed" }
        systemInfoRefresh = refresh
    }

    fun refreshOnce() {
        val shouldRun = synchronized(lock) {
            if (state == RefreshState.NOT_STARTED) {
                state = RefreshState.RUNNING
                true
            } else {
                false
            }
        }
        if (!shouldRun) return

        val startedAt = System.nanoTime()
        logger.log(FeaturePackLogLevel.INFO, "PUBLIC_ESI sovereignty background refresh started", null)
        val result = try {
            source.fetchFreshSnapshot()
        } catch (error: Throwable) {
            rethrowFatal(error)
            RemoteSnapshotResult.Unavailable(
                error.message?.let { "Unexpected refresh failure: $it" } ?: "Unexpected refresh failure",
            )
        }

        synchronized(lock) {
            if (state == RefreshState.CLOSED) return
            when (result) {
                is RemoteSnapshotResult.Success -> {
                    source.saveFreshSnapshot(result.snapshot)
                    repository.replace(result.snapshot)
                    systemInfoRefresh()
                    logger.log(
                        FeaturePackLogLevel.INFO,
                        "PUBLIC_ESI sovereignty background refresh published fresh data in " +
                            "${elapsedMillis(startedAt)} ms",
                        null,
                    )
                }
                is RemoteSnapshotResult.Unavailable -> logger.log(
                    FeaturePackLogLevel.WARN,
                    "PUBLIC_ESI sovereignty background refresh unavailable; retaining current state: ${result.reason}",
                    null,
                )
                is RemoteSnapshotResult.Invalid -> logger.log(
                    FeaturePackLogLevel.WARN,
                    "PUBLIC_ESI sovereignty background refresh invalid; retaining current state: ${result.reason}",
                    null,
                )
            }
            state = RefreshState.COMPLETED
        }
    }

    override fun close() {
        val shouldClose = synchronized(lock) {
            if (state == RefreshState.CLOSED) false else {
                state = RefreshState.CLOSED
                true
            }
        }
        if (shouldClose) source.close()
    }

    private enum class RefreshState { NOT_STARTED, RUNNING, COMPLETED, CLOSED }
}

private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

@Suppress("DEPRECATION")
private fun rethrowFatal(error: Throwable) {
    if (error is VirtualMachineError || error is ThreadDeath) throw error
}
