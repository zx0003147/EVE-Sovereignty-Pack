package dev.evestaticmapplanner.sovereignty

/** Explicit outcomes from a remote sovereignty source boundary. */
internal sealed interface RemoteSnapshotResult {
    data class Success(val snapshot: SovereigntySnapshot) : RemoteSnapshotResult

    data class Unavailable(val reason: String) : RemoteSnapshotResult

    data class Invalid(val reason: String) : RemoteSnapshotResult
}
