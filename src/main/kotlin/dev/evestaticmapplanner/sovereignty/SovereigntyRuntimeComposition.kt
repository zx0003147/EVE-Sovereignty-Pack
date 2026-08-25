package dev.evestaticmapplanner.sovereignty

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
) {
    fun createSnapshotProvider(): SovereigntySnapshotProvider = when (dataSourceMode) {
        SovereigntyDataSourceMode.EMBEDDED -> embeddedProviderFactory()
        SovereigntyDataSourceMode.PUBLIC_ESI -> RemoteSovereigntySnapshotProvider(
            PublicEsiSovereigntySource(publicEsiClientFactory()),
        )
    }

    fun createRepository(): SovereigntyRepository = SovereigntyRepository(createSnapshotProvider())

    companion object {
        fun production() = SovereigntyRuntimeComposition(SovereigntyDataSourceMode.PUBLIC_ESI)
    }
}
