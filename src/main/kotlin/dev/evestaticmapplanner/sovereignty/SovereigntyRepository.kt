package dev.evestaticmapplanner.sovereignty

internal class SovereigntyRepository(
    snapshotProvider: SovereigntySnapshotProvider,
) {
    private val snapshot = snapshotProvider.loadSnapshot()
    private val recordsBySystemId = snapshot.records.associateBy(SovereigntyRecord::systemId)

    val metadata: SovereigntySnapshotMetadata
        get() = snapshot.metadata

    fun find(systemId: Int): SovereigntyRecord? = recordsBySystemId[systemId]

    fun records(): List<SovereigntyRecord> = recordsBySystemId.values.sortedBy(SovereigntyRecord::systemId)
}
