package dev.evestaticmapplanner.sovereignty

/** Source-neutral snapshot boundary implemented by embedded, remote, or future cached providers. */
internal fun interface SovereigntySnapshotProvider {
    fun loadSnapshot(): SovereigntySnapshot
}
