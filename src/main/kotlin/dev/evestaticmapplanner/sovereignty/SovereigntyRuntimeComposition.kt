package dev.evestaticmapplanner.sovereignty

import dev.evestaticmapplanner.feature.api.FeaturePackLogger
import dev.evestaticmapplanner.feature.api.FeaturePackLogLevel
import dev.evestaticmapplanner.feature.api.PackRelativePath
import dev.evestaticmapplanner.feature.api.PackStorage
import java.time.Clock

/** Internal runtime choice; this is deliberately not part of the Feature API or user settings. */
internal enum class SovereigntyDataSourceMode {
    EMBEDDED,
    PUBLIC_ESI,
}

internal data class SovereigntyRuntimeActivation(
    val initialSnapshot: SovereigntySnapshot,
    val initialCacheState: SovereigntyInitialCacheState?,
    val refreshRequired: Boolean,
    val refreshSource: CachedRemoteSovereigntySource?,
    val allianceMetadataState: AllianceMetadataState,
    val allianceMetadataSource: PublicEsiAllianceMetadataSource?,
)

/** The single composition point that maps a source mode to the repository's provider boundary. */
internal class SovereigntyRuntimeComposition(
    val dataSourceMode: SovereigntyDataSourceMode,
    private val embeddedProviderFactory: () -> SovereigntySnapshotProvider = ::EmbeddedJsonSnapshotProvider,
    private val publicEsiClientFactory: () -> PublicEsiClient = ::JdkPublicEsiClient,
    private val cacheFactory: (PackStorage) -> SovereigntySnapshotCache = { storage ->
        FileSovereigntySnapshotCache(storage.cachePath(PUBLIC_ESI_LKG_CACHE_PATH))
    },
    private val allianceMetadataCacheFactory: (PackStorage) -> AllianceMetadataCache = { storage ->
        FileAllianceMetadataCache(storage.cachePath(ALLIANCE_METADATA_LKG_CACHE_PATH))
    },
    private val clock: Clock = Clock.systemUTC(),
) {
    fun createSnapshotProvider(
        storage: PackStorage,
        logger: FeaturePackLogger,
    ): SovereigntySnapshotProvider = when (dataSourceMode) {
        SovereigntyDataSourceMode.EMBEDDED -> embeddedProviderFactory()
        SovereigntyDataSourceMode.PUBLIC_ESI -> RemoteSovereigntySnapshotProvider(
            createPublicEsiSource(storage, logger),
        )
    }

    fun createActivation(
        storage: PackStorage,
        logger: FeaturePackLogger,
    ): SovereigntyRuntimeActivation = when (dataSourceMode) {
        SovereigntyDataSourceMode.EMBEDDED -> SovereigntyRuntimeActivation(
            initialSnapshot = embeddedProviderFactory().loadSnapshot(),
            initialCacheState = null,
            refreshRequired = false,
            refreshSource = null,
            allianceMetadataState = AllianceMetadataState(),
            allianceMetadataSource = null,
        )
        SovereigntyDataSourceMode.PUBLIC_ESI -> {
            val sharedClient = DeferredPublicEsiClient(publicEsiClientFactory)
            val source = createPublicEsiSource(storage, logger, sharedClient)
            val initial = source.loadInitialSnapshot()
            val metadataCache = allianceMetadataCacheFactory(storage)
            val metadataState = loadAllianceMetadataState(metadataCache, logger)
            SovereigntyRuntimeActivation(
                initialSnapshot = initial.snapshot,
                initialCacheState = initial.cacheState,
                refreshRequired = initial.refreshRequired,
                refreshSource = source,
                allianceMetadataState = metadataState,
                allianceMetadataSource = PublicEsiAllianceMetadataSource(
                    client = sharedClient,
                    state = metadataState,
                    cache = metadataCache,
                    clock = clock,
                ),
            )
        }
    }

    fun createRepository(
        storage: PackStorage,
        logger: FeaturePackLogger,
    ): SovereigntyRepository = SovereigntyRepository(createSnapshotProvider(storage, logger))

    private fun createPublicEsiSource(
        storage: PackStorage,
        logger: FeaturePackLogger,
        client: PublicEsiClient = DeferredPublicEsiClient(publicEsiClientFactory),
    ) = CachedRemoteSovereigntySource(
        remote = OwnedPublicEsiSovereigntySource(client),
        cache = cacheFactory(storage),
        logger = logger,
        clock = clock,
    )

    private fun loadAllianceMetadataState(
        cache: AllianceMetadataCache,
        logger: FeaturePackLogger,
    ): AllianceMetadataState = when (val loaded = cache.load()) {
        is AllianceMetadataCacheLoadResult.Hit -> AllianceMetadataState(loaded.records)
        AllianceMetadataCacheLoadResult.Miss -> AllianceMetadataState()
        is AllianceMetadataCacheLoadResult.Unusable -> {
            logger.log(
                FeaturePackLogLevel.WARN,
                "Ignoring unusable PUBLIC_ESI alliance metadata LKG cache: ${loaded.reason}",
                loaded.cause,
            )
            AllianceMetadataState()
        }
    }

    companion object {
        val PUBLIC_ESI_LKG_CACHE_PATH = PackRelativePath("public-esi-lkg.json")
        val ALLIANCE_METADATA_LKG_CACHE_PATH = PackRelativePath("alliance-metadata-lkg.json")

        fun production() = SovereigntyRuntimeComposition(SovereigntyDataSourceMode.PUBLIC_ESI)
    }
}

/** Closes the shared client even when only alliance metadata, not sovereignty, initialized it. */
private class OwnedPublicEsiSovereigntySource(
    private val client: PublicEsiClient,
) : RemoteSovereigntySource {
    private val sovereignty = DeferredRemoteSovereigntySource { PublicEsiSovereigntySource(client) }

    override fun fetchSnapshot(): RemoteSnapshotResult = sovereignty.fetchSnapshot()

    override fun close() {
        runCatching { sovereignty.close() }
        client.close()
    }
}
