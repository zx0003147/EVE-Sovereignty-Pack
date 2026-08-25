package dev.evestaticmapplanner.sovereignty

/** Synchronous boundary for a future remote sovereignty transport and validation implementation. */
internal fun interface RemoteSovereigntySource {
    fun fetchSnapshot(): RemoteSnapshotResult
}
