package dev.evestaticmapplanner.sovereignty

import java.util.Collections

internal data class SovereigntyRecord(
    val systemId: Int,
    val allianceName: String,
    val corporationName: String?,
    val sovereigntyStatus: String,
)

internal data class SovereigntySnapshotMetadata(
    val ignoredRecordCount: Int = 0,
    val failureMessage: String? = null,
)

/** Immutable, Pack-owned representation of sovereignty data from one source load. */
internal class SovereigntySnapshot(
    records: Iterable<SovereigntyRecord>,
    val metadata: SovereigntySnapshotMetadata = SovereigntySnapshotMetadata(),
) {
    val records: List<SovereigntyRecord> = Collections.unmodifiableList(records.toList())

    internal companion object {
        fun empty(failureMessage: String) = SovereigntySnapshot(
            records = emptyList(),
            metadata = SovereigntySnapshotMetadata(failureMessage = failureMessage),
        )
    }
}
