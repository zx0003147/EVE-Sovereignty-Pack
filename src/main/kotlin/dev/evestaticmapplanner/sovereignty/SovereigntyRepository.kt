package dev.evestaticmapplanner.sovereignty

internal class SovereigntyRepository(
    initialSnapshot: SovereigntySnapshot,
) {
    constructor(snapshotProvider: SovereigntySnapshotProvider) : this(snapshotProvider.loadSnapshot())

    private val state = java.util.concurrent.atomic.AtomicReference(RepositoryState(initialSnapshot))

    val metadata: SovereigntySnapshotMetadata
        get() = state.get().snapshot.metadata

    fun find(systemId: Int): SovereigntyRecord? = state.get().recordsBySystemId[systemId]

    fun records(): List<SovereigntyRecord> = state.get().orderedRecords

    fun replace(snapshot: SovereigntySnapshot) {
        state.set(RepositoryState(snapshot))
    }

    private class RepositoryState(val snapshot: SovereigntySnapshot) {
        val orderedRecords = snapshot.records.sortedBy(SovereigntyRecord::systemId)
        val recordsBySystemId = orderedRecords.associateBy(SovereigntyRecord::systemId)
    }
}
