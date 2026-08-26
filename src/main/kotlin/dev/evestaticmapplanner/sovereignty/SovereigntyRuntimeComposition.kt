package dev.evestaticmapplanner.sovereignty

import dev.evestaticmapplanner.feature.api.FeaturePackLogger
import dev.evestaticmapplanner.feature.api.PackRelativePath
import dev.evestaticmapplanner.feature.api.PackStorage
import java.time.Clock

/** Internal runtime choice; this is deliberately not part of the Feature API or user settings. */
internal enum class SovereigntyDataSourceMode {
    EMBEDDED,
    PUBLIC_ESI,
}

/** The single composition point that maps a source mode to the repository's provider boundary. */
internal class SovereigntyRuntimeComposition(
    val dataSourceMode: SovereigntyDataSourceMode,
    private val embeddedProviderFactory: () -> SovereigntySnapshotProvider = ::EmbeddedJsonSnapshotProvider,
    private val publicEsiClientFactory: () -> PublicEsiClient = ::JdkPublicEsiClient,
    private val cacheFactory: (PackStorage) -> SovereigntySnapshotCache = { storage ->
        FileSovereigntySnapshotCache(storage.cachePath(PUBLIC_ESI_LKG_CACHE_PATH))
    },
    private val clock: Clock = Clock.systemUTC(),
) {
    fun createSnapshotProvider(
        storage: PackStorage,
        logger: FeaturePackLogger,
    ): SovereigntySnapshotProvider = when (dataSourceMode) {
        SovereigntyDataSourceMode.EMBEDDED -> embeddedProviderFactory()
        SovereigntyDataSourceMode.PUBLIC_ESI -> RemoteSovereigntySnapshotProvider(
            CachedRemoteSovereigntySource(
                remote = PublicEsiSovereigntySource(publicEsiClientFactory()),
                cache = cacheFactory(storage),
                logger = logger,
                clock = clock,
            ),
        )
    }

    fun createRepository(
        storage: PackStorage,
        logger: FeaturePackLogger,
    ): SovereigntyRepository = SovereigntyRepository(createSnapshotProvider(storage, logger))

    companion object {
        val PUBLIC_ESI_LKG_CACHE_PATH = PackRelativePath("public-esi-lkg.json")

        fun production() = SovereigntyRuntimeComposition(SovereigntyDataSourceMode.PUBLIC_ESI)
    }
}
