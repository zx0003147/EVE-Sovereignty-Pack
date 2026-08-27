package dev.evestaticmapplanner.sovereignty

import dev.evestaticmapplanner.feature.api.FeaturePackLogLevel
import dev.evestaticmapplanner.feature.api.FeaturePackLogger
import java.time.Clock
import java.time.Duration

/** Selects one final PUBLIC_ESI snapshot synchronously during Pack startup. */
internal class CachedRemoteSovereigntySource(
    private val remote: RemoteSovereigntySource,
    private val cache: SovereigntySnapshotCache,
    private val logger: FeaturePackLogger,
    private val clock: Clock = Clock.systemUTC(),
    private val freshnessThreshold: Duration = STARTUP_FRESHNESS_THRESHOLD,
) : RemoteSovereigntySource {
    override fun fetchSnapshot(): RemoteSnapshotResult {
        val staleFallback = when (val cached = cache.load()) {
            is SovereigntyCacheLoadResult.Hit -> if (isFresh(cached)) {
                logLegacyIdentityIfPresent(cached.snapshot)
                logger.log(
                    FeaturePackLogLevel.INFO,
                    "Using fresh cached PUBLIC_ESI sovereignty snapshot",
                    null,
                )
                return RemoteSnapshotResult.Success(cached.snapshot)
            } else {
                logLegacyIdentityIfPresent(cached.snapshot)
                logger.log(
                    FeaturePackLogLevel.INFO,
                    "Cached PUBLIC_ESI sovereignty snapshot is stale; attempting one startup refresh",
                    null,
                )
                cached.snapshot
            }
            SovereigntyCacheLoadResult.Miss -> null
            is SovereigntyCacheLoadResult.Unusable -> {
                logger.log(
                    FeaturePackLogLevel.WARN,
                    "Ignoring unusable PUBLIC_ESI sovereignty LKG cache: ${cached.reason}",
                    cached.cause,
                )
                null
            }
        }

        val remoteResult = remote.fetchSnapshot()
        val validRemote = when (remoteResult) {
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

        if (validRemote is RemoteSnapshotResult.Success) {
            when (val saved = cache.save(validRemote.snapshot)) {
                SovereigntyCacheSaveResult.Saved -> logger.log(
                    FeaturePackLogLevel.INFO,
                    "PUBLIC_ESI sovereignty startup refresh succeeded",
                    null,
                )
                is SovereigntyCacheSaveResult.Failed -> logger.log(
                    FeaturePackLogLevel.WARN,
                    "Could not save PUBLIC_ESI sovereignty LKG cache; using valid remote snapshot in memory: " +
                        saved.reason,
                    saved.cause,
                )
            }
            return validRemote
        }

        if (staleFallback == null) return validRemote
        when (validRemote) {
            is RemoteSnapshotResult.Unavailable -> logger.log(
                FeaturePackLogLevel.WARN,
                "Retaining stale PUBLIC_ESI sovereignty LKG because startup refresh is unavailable: " +
                    validRemote.reason,
                null,
            )
            is RemoteSnapshotResult.Invalid -> logger.log(
                FeaturePackLogLevel.WARN,
                "Retaining stale PUBLIC_ESI sovereignty LKG because startup refresh is invalid: " + validRemote.reason,
                null,
            )
            is RemoteSnapshotResult.Success -> error("Handled above")
        }
        return RemoteSnapshotResult.Success(staleFallback)
    }

    private fun isFresh(cached: SovereigntyCacheLoadResult.Hit): Boolean {
        val age = Duration.between(cached.savedAt, clock.instant())
        return age.isNegative || age <= freshnessThreshold
    }

    private fun logLegacyIdentityIfPresent(snapshot: SovereigntySnapshot) {
        if (snapshot.records.any { it.allianceId == null }) {
            logger.log(
                FeaturePackLogLevel.WARN,
                "Using backwards-compatible v1 PUBLIC_ESI LKG identity fallback; a successful startup refresh will restore alliance-ID visual identity",
                null,
            )
        }
    }

    internal companion object {
        val STARTUP_FRESHNESS_THRESHOLD: Duration = Duration.ofHours(1)
    }
}
