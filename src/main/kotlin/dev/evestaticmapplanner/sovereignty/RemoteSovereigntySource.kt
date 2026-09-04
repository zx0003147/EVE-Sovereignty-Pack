package dev.evestaticmapplanner.sovereignty

/** Synchronous boundary for a future remote sovereignty transport and validation implementation. */
internal fun interface RemoteSovereigntySource : AutoCloseable {
    fun fetchSnapshot(): RemoteSnapshotResult

    override fun close() = Unit
}

/** Defers HTTP client construction until the Host-owned background refresh actually begins. */
internal class DeferredRemoteSovereigntySource(
    private val factory: () -> RemoteSovereigntySource,
) : RemoteSovereigntySource {
    private val lock = Any()
    private var delegate: RemoteSovereigntySource? = null
    private var closed = false

    override fun fetchSnapshot(): RemoteSnapshotResult {
        val source = synchronized(lock) {
            if (closed) return RemoteSnapshotResult.Unavailable("Sovereignty remote source is closed")
            delegate ?: factory().also { delegate = it }
        }
        return source.fetchSnapshot()
    }

    override fun close() {
        val source = synchronized(lock) {
            if (closed) return
            closed = true
            delegate
        }
        source?.close()
    }
}
