package dev.evestaticmapplanner.sovereignty

import dev.evestaticmapplanner.feature.api.FeaturePackLogLevel
import dev.evestaticmapplanner.feature.api.FeaturePackLogger
import java.time.Clock
import java.time.Duration

internal enum class SovereigntyInitialCacheState {
    FRESH,
    STALE_LAST_GOOD,
    MISSING,
    UNUSABLE,
}

internal data class SovereigntyInitialSnapshot(
    val snapshot: SovereigntySnapshot,
    val cacheState: SovereigntyInitialCacheState,
    val refreshRequired: Boolean,
)

/** Separates local LKG selection from remote refresh so Pack startup never waits for network. */
internal class CachedRemoteSovereigntySource(
    private val remote: RemoteSovereigntySource,
    private val cache: SovereigntySnapshotCache,
    private val logger: FeaturePackLogger,
    private val clock: Clock = Clock.systemUTC(),
    private val freshnessThreshold: Duration = STARTUP_FRESHNESS_THRESHOLD,
) : RemoteSovereigntySource {
    fun loadInitialSnapshot(): SovereigntyInitialSnapshot = when (val cached = cache.load()) {
        is SovereigntyCacheLoadResult.Hit -> if (isFresh(cached)) {
            logLegacyIdentityIfPresent(cached.snapshot)
            logger.log(
                FeaturePackLogLevel.INFO,
                "Using fresh cached PUBLIC_ESI sovereignty snapshot",
                null,
            )
            SovereigntyInitialSnapshot(
                snapshot = cached.snapshot,
                cacheState = SovereigntyInitialCacheState.FRESH,
                refreshRequired = false,
            )
        } else {
            logLegacyIdentityIfPresent(cached.snapshot)
            logger.log(
                FeaturePackLogLevel.INFO,
                "Using stale PUBLIC_ESI sovereignty LKG; scheduling one background refresh",
                null,
            )
            SovereigntyInitialSnapshot(
                snapshot = cached.snapshot,
                cacheState = SovereigntyInitialCacheState.STALE_LAST_GOOD,
                refreshRequired = true,
            )
        }
        SovereigntyCacheLoadResult.Miss -> SovereigntyInitialSnapshot(
            snapshot = SovereigntySnapshot.empty("No cached PUBLIC_ESI sovereignty snapshot; refresh pending"),
            cacheState = SovereigntyInitialCacheState.MISSING,
            refreshRequired = true,
        )
        is SovereigntyCacheLoadResult.Unusable -> {
            logger.log(
                FeaturePackLogLevel.WARN,
                "Ignoring unusable PUBLIC_ESI sovereignty LKG cache: ${cached.reason}",
                cached.cause,
            )
            SovereigntyInitialSnapshot(
                snapshot = SovereigntySnapshot.empty(
                    "Cached PUBLIC_ESI sovereignty snapshot is unusable; refresh pending",
                ),
                cacheState = SovereigntyInitialCacheState.UNUSABLE,
                refreshRequired = true,
            )
        }
    }

    fun fetchFreshSnapshot(): RemoteSnapshotResult {
        val remoteResult = remote.fetchSnapshot()
        return when (remoteResult) {
            is RemoteSnapshotResult.Success -> {
                val reason = SovereigntySnapshotValidation.validatePublicEsi(remoteResult.snapshot)
                if (reason == null) {
                    remoteResult
                } else {
                    RemoteSnapshotResult.Invalid("PUBLIC_ESI snapshot failed canonical validation: $reason")
                }
            }
            is RemoteSnapshotResult.Unavailable,
            is RemoteSnapshotResult.Invalid,
            -> remoteResult
        }
    }

    fun saveFreshSnapshot(snapshot: SovereigntySnapshot): SovereigntyCacheSaveResult = cache.save(snapshot).also { saved ->
        if (saved is SovereigntyCacheSaveResult.Failed) {
            logger.log(
                FeaturePackLogLevel.WARN,
                "Could not save PUBLIC_ESI sovereignty LKG cache; using valid remote snapshot in memory: " +
                    saved.reason,
                saved.cause,
            )
        }
    }

    override fun fetchSnapshot(): RemoteSnapshotResult {
        val initial = loadInitialSnapshot()
        if (!initial.refreshRequired) return RemoteSnapshotResult.Success(initial.snapshot)

        val validRemote = fetchFreshSnapshot()
        if (validRemote is RemoteSnapshotResult.Success) {
            saveFreshSnapshot(validRemote.snapshot)
            logger.log(
                FeaturePackLogLevel.INFO,
                "PUBLIC_ESI sovereignty refresh succeeded",
                null,
            )
            return validRemote
        }

        if (initial.cacheState != SovereigntyInitialCacheState.STALE_LAST_GOOD) return validRemote
        when (validRemote) {
            is RemoteSnapshotResult.Unavailable -> logger.log(
                FeaturePackLogLevel.WARN,
                "Retaining stale PUBLIC_ESI sovereignty LKG because refresh is unavailable: " + validRemote.reason,
                null,
            )
            is RemoteSnapshotResult.Invalid -> logger.log(
                FeaturePackLogLevel.WARN,
                "Retaining stale PUBLIC_ESI sovereignty LKG because refresh is invalid: " + validRemote.reason,
                null,
            )
            is RemoteSnapshotResult.Success -> error("Handled above")
        }
        return RemoteSnapshotResult.Success(initial.snapshot)
    }

    override fun close() {
        remote.close()
    }

    private fun isFresh(cached: SovereigntyCacheLoadResult.Hit): Boolean {
        val age = Duration.between(cached.savedAt, clock.instant())
        return age.isNegative || age <= freshnessThreshold
    }

    private fun logLegacyIdentityIfPresent(snapshot: SovereigntySnapshot) {
        if (snapshot.records.any { it.allianceId == null }) {
            logger.log(
                FeaturePackLogLevel.WARN,
                "Using backwards-compatible v1 PUBLIC_ESI LKG identity fallback; a successful background refresh will restore alliance-ID visual identity",
                null,
            )
        }
    }

    internal companion object {
        val STARTUP_FRESHNESS_THRESHOLD: Duration = Duration.ofHours(1)
    }
}
