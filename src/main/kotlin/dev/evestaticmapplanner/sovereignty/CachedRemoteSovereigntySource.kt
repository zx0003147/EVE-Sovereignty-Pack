package dev.evestaticmapplanner.sovereignty

import dev.evestaticmapplanner.feature.api.FeaturePackLogLevel
import dev.evestaticmapplanner.feature.api.FeaturePackLogger

/** Cache-first PUBLIC_ESI source. A valid cache deliberately suppresses all remote work in SV-3C-2. */
internal class CachedRemoteSovereigntySource(
    private val remote: RemoteSovereigntySource,
    private val cache: SovereigntySnapshotCache,
    private val logger: FeaturePackLogger,
) : RemoteSovereigntySource {
    override fun fetchSnapshot(): RemoteSnapshotResult {
        when (val cached = cache.load()) {
            is SovereigntyCacheLoadResult.Hit -> return RemoteSnapshotResult.Success(cached.snapshot)
            SovereigntyCacheLoadResult.Miss -> Unit
            is SovereigntyCacheLoadResult.Unusable -> logger.log(
                FeaturePackLogLevel.WARN,
                "Ignoring unusable PUBLIC_ESI sovereignty LKG cache: ${cached.reason}",
                cached.cause,
            )
        }

        val result = remote.fetchSnapshot()
        if (result !is RemoteSnapshotResult.Success) return result
        SovereigntySnapshotValidation.validatePublicEsi(result.snapshot)?.let { reason ->
            return RemoteSnapshotResult.Invalid("PUBLIC_ESI snapshot failed canonical validation: $reason")
        }

        when (val saved = cache.save(result.snapshot)) {
            SovereigntyCacheSaveResult.Saved -> Unit
            is SovereigntyCacheSaveResult.Failed -> logger.log(
                FeaturePackLogLevel.WARN,
                "Could not save PUBLIC_ESI sovereignty LKG cache: ${saved.reason}",
                saved.cause,
            )
        }
        return result
    }
}
