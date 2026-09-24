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
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
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
        val publicationState = SovereigntyPublicationState.initial(activation, dataSourceMode)
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
        var allianceDirectoryRegistration: SovereigntyAllianceDirectoryRegistration =
            NoSovereigntyAllianceDirectoryRegistration
        var sovereigntyRegistration: SovereigntyProviderRegistration = NoSovereigntyProviderRegistration
        val refreshRequest = SovereigntyRefreshRequest()
        try {
            sovereigntyRegistration = SovereigntyProviderBridge.register(context, publicationState, refreshRequest)
            allianceDirectoryRegistration = SovereigntyAllianceDirectoryBridge.register(
                context,
                repository,
                activation.allianceMetadataState,
            )
            val metadataRefreshRequired = allianceDirectoryRegistration.active &&
                activation.allianceMetadataSource?.requiresRefresh(repository.observedAllianceNames()) == true
            val backgroundRefreshRequired = activation.refreshRequired || metadataRefreshRequired
            val useLegacyPresentation = !sovereigntyRegistration.active
            val dynamicOverlay = activation.refreshSource?.takeIf { useLegacyPresentation }?.let {
                context.capabilities().find(StandardFeatureCapabilities.DYNAMIC_OVERLAY)
            }
            if (
                backgroundRefreshRequired &&
                activation.refreshSource != null &&
                (sovereigntyRegistration.active || dynamicOverlay != null)
            ) {
                refreshCoordinator = SovereigntyRefreshCoordinator(
                    repository = repository,
                    source = activation.refreshSource,
                    logger = context.logger(),
                    refreshSovereignty = activation.refreshRequired,
                    allianceMetadataSource = activation.allianceMetadataSource
                        ?.takeIf { allianceDirectoryRegistration.active },
                    allianceDirectoryRegistration = allianceDirectoryRegistration,
                    publicationState = publicationState,
                    sovereigntyRegistration = sovereigntyRegistration,
                    clock = activation.clock,
                )
            }
            val dynamicRegistration = if (useLegacyPresentation && dynamicOverlay != null && refreshCoordinator != null) {
                val overlayProvider = SovereigntyOverlayProvider(repository)
                val coordinator = checkNotNull(refreshCoordinator)
                dynamicOverlay.register(
                    RefreshingSovereigntyOverlayProvider(overlayProvider, coordinator::refreshOnce),
                )
            } else {
                if (backgroundRefreshRequired && refreshCoordinator == null) {
                    context.logger().log(
                        FeaturePackLogLevel.WARN,
                        "Host exposes neither typed Sovereignty refresh nor Dynamic Overlay; background refresh is unavailable",
                        null,
                    )
                }
                null
            }
            if (useLegacyPresentation) {
                overlayRegistration = dynamicRegistration
                    ?: context.overlays().register(SovereigntyOverlayProvider(repository))
                systemInfoRegistration = context.systemInfo().register(SovereigntySystemInfoProvider(repository))
                val legacySystemInfoRegistration = checkNotNull(systemInfoRegistration)
                refreshCoordinator?.attachSystemInfoRefresh(legacySystemInfoRegistration::refresh)
            } else {
                refreshCoordinator?.let { coordinator -> refreshRequest.attach(coordinator::requestRefresh) }
                context.logger().log(
                    FeaturePackLogLevel.INFO,
                    "Typed Sovereignty is active; legacy Overlay and System Info providers are not registered",
                    null,
                )
            }
            if (refreshCoordinator == null) activation.refreshSource?.close()
            context.logger().log(FeaturePackLogLevel.INFO, "Sovereignty Pack started", null)
            val session = SovereigntySession(
                overlayRegistration = overlayRegistration,
                systemInfoRegistration = systemInfoRegistration,
                refreshCoordinator = refreshCoordinator,
                allianceDirectoryRegistration = allianceDirectoryRegistration,
                sovereigntyRegistration = sovereigntyRegistration,
                refreshRequest = refreshRequest,
                logger = context.logger(),
            )
            if (backgroundRefreshRequired) dynamicRegistration?.requestRefresh()
            return session
        } catch (error: Throwable) {
            refreshRequest.clear()
            runCatching { refreshCoordinator?.close() }
            runCatching { sovereigntyRegistration.close() }
            runCatching { allianceDirectoryRegistration.close() }
            runCatching { systemInfoRegistration?.close() }
            runCatching { overlayRegistration?.close() }
            if (refreshCoordinator == null) runCatching { activation.refreshSource?.close() }
            throw FeaturePackStartupException("Could not register Sovereignty Pack providers", error)
        }
    }

    private class SovereigntySession(
        private val overlayRegistration: OverlayRegistration?,
        private val systemInfoRegistration: SystemInfoRegistration?,
        private val refreshCoordinator: SovereigntyRefreshCoordinator?,
        private val allianceDirectoryRegistration: SovereigntyAllianceDirectoryRegistration,
        private val sovereigntyRegistration: SovereigntyProviderRegistration,
        private val refreshRequest: SovereigntyRefreshRequest,
        private val logger: FeaturePackLogger,
    ) : FeaturePackSession {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            refreshRequest.clear()
            var failure: Throwable? = null
            try {
                refreshCoordinator?.close()
            } catch (error: Throwable) {
                failure = error
            }
            try {
                allianceDirectoryRegistration.close()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
            try {
                sovereigntyRegistration.close()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
            try {
                systemInfoRegistration?.close()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
            try {
                overlayRegistration?.close()
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
    private val refreshSovereignty: Boolean,
    private val allianceMetadataSource: PublicEsiAllianceMetadataSource?,
    private val allianceDirectoryRegistration: SovereigntyAllianceDirectoryRegistration,
    private val publicationState: SovereigntyPublicationState,
    private val sovereigntyRegistration: SovereigntyProviderRegistration,
    private val clock: java.time.Clock,
) : AutoCloseable {
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sovereignty-refresh").apply { isDaemon = true }
    }
    private var state = RefreshState.NOT_STARTED
    private var systemInfoRefresh: () -> Unit = {}

    fun attachSystemInfoRefresh(refresh: () -> Unit) = synchronized(lock) {
        check(state != RefreshState.CLOSED) { "Sovereignty refresh coordinator is closed" }
        systemInfoRefresh = refresh
    }

    /** Schedules Pack-owned network work without blocking the Host's typed capability call. */
    fun requestRefresh(): Boolean {
        val accepted = synchronized(lock) {
            if (state == RefreshState.NOT_STARTED) {
                state = RefreshState.SCHEDULED
                true
            } else {
                false
            }
        }
        if (!accepted) return false
        return try {
            executor.execute {
                val scheduled = synchronized(lock) {
                    if (state == RefreshState.SCHEDULED) {
                        state = RefreshState.NOT_STARTED
                        true
                    } else {
                        false
                    }
                }
                if (scheduled) refreshOnce()
            }
            true
        } catch (_: RejectedExecutionException) {
            synchronized(lock) {
                if (state == RefreshState.SCHEDULED) state = RefreshState.NOT_STARTED
            }
            false
        }
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
        logger.log(FeaturePackLogLevel.INFO, "PUBLIC_ESI background refresh started", null)
        if (refreshSovereignty) {
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
                        publicationState.publishAvailable(repository.records(), clock.instant())
                        systemInfoRefresh()
                        allianceDirectoryRegistration.requestRefresh()
                        sovereigntyRegistration.requestRefresh()
                        logger.log(
                            FeaturePackLogLevel.INFO,
                            "PUBLIC_ESI sovereignty background refresh published fresh data in " +
                                "${elapsedMillis(startedAt)} ms",
                            null,
                        )
                    }
                    is RemoteSnapshotResult.Unavailable -> {
                        publicationState.publishFailure(result.reason)
                        sovereigntyRegistration.requestRefresh()
                        logger.log(
                            FeaturePackLogLevel.WARN,
                            "PUBLIC_ESI sovereignty background refresh unavailable; retaining current state: ${result.reason}",
                            null,
                        )
                    }
                    is RemoteSnapshotResult.Invalid -> {
                        publicationState.publishFailure(result.reason)
                        sovereigntyRegistration.requestRefresh()
                        logger.log(
                            FeaturePackLogLevel.WARN,
                            "PUBLIC_ESI sovereignty background refresh invalid; retaining current state: ${result.reason}",
                            null,
                        )
                    }
                }
            }
        }

        val metadataPlan = allianceMetadataSource?.let { metadataSource ->
            try {
                metadataSource.fetch(repository.observedAllianceNames())
            } catch (error: Throwable) {
                rethrowFatal(error)
                AllianceMetadataRefreshPlan(
                    requestedCount = 0,
                    replacements = emptyList(),
                    failures = listOf(
                        error.message?.let { "Unexpected Alliance metadata refresh failure: $it" }
                            ?: "Unexpected Alliance metadata refresh failure",
                    ),
                )
            }
        }

        synchronized(lock) {
            if (state == RefreshState.CLOSED) return
            val metadataResult = metadataPlan?.let { allianceMetadataSource.apply(it) }
            if (metadataResult != null) {
                if (metadataResult.changed) allianceDirectoryRegistration.requestRefresh()
                if (metadataResult.failures.isNotEmpty()) {
                    logger.log(
                        FeaturePackLogLevel.WARN,
                        "PUBLIC_ESI Alliance metadata refresh completed with " +
                            "${metadataResult.failures.size} failure(s); retaining last-good metadata: " +
                            metadataResult.failures.joinToString(limit = 3),
                        null,
                    )
                } else if (metadataResult.requestedCount > 0) {
                    logger.log(
                        FeaturePackLogLevel.INFO,
                        "PUBLIC_ESI Alliance metadata refreshed ${metadataResult.updatedCount} observed Alliance(s)",
                        null,
                    )
                }
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
        if (shouldClose) {
            executor.shutdownNow()
            if (!executor.awaitTermination(REFRESH_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                logger.log(
                    FeaturePackLogLevel.WARN,
                    "Sovereignty refresh worker did not stop within the bounded close timeout",
                    null,
                )
            }
            source.close()
        }
    }

    private enum class RefreshState { NOT_STARTED, SCHEDULED, RUNNING, COMPLETED, CLOSED }

    private companion object {
        const val REFRESH_CLOSE_TIMEOUT_MILLIS = 750L
    }
}

private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

@Suppress("DEPRECATION")
private fun rethrowFatal(error: Throwable) {
    if (error is VirtualMachineError || error is ThreadDeath) throw error
}
