package dev.evestaticmapplanner.sovereignty

/** Adapts an explicit remote-source result to the repository's source-neutral snapshot contract. */
internal class RemoteSovereigntySnapshotProvider(
    private val source: RemoteSovereigntySource,
) : SovereigntySnapshotProvider {
    override fun loadSnapshot(): SovereigntySnapshot = when (val result = source.fetchSnapshot()) {
        is RemoteSnapshotResult.Success -> result.snapshot
        is RemoteSnapshotResult.Unavailable -> SovereigntySnapshot.empty(
            "Remote sovereignty source is unavailable: ${result.reason}",
        )
        is RemoteSnapshotResult.Invalid -> SovereigntySnapshot.empty(
            "Remote sovereignty snapshot is invalid: ${result.reason}",
        )
    }
}
