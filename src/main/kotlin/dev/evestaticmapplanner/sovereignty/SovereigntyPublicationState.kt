package dev.evestaticmapplanner.sovereignty

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

internal enum class PublishedSovereigntyFreshness {
    AVAILABLE,
    STALE,
    UNAVAILABLE,
}

internal data class PublishedSovereigntySnapshot(
    val records: List<SovereigntyRecord>,
    val observedAt: Instant?,
    val source: String,
    val freshness: PublishedSovereigntyFreshness,
    val errorMessage: String? = null,
)

/** API-independent state so the Pack entrypoint remains linkable on pre-2.5 Hosts. */
internal class SovereigntyPublicationState(initial: PublishedSovereigntySnapshot) {
    private val state = AtomicReference(initial.copy(records = initial.records.toList()))

    fun snapshot(): PublishedSovereigntySnapshot = state.get()

    fun publishAvailable(records: List<SovereigntyRecord>, observedAt: Instant) {
        state.set(
            PublishedSovereigntySnapshot(
                records = records.toList(),
                observedAt = observedAt,
                source = PUBLIC_ESI_SOURCE,
                freshness = PublishedSovereigntyFreshness.AVAILABLE,
            ),
        )
    }

    fun publishFailure(reason: String) {
        state.updateAndGet { current ->
            if (current.records.isEmpty()) {
                current.copy(
                    freshness = PublishedSovereigntyFreshness.UNAVAILABLE,
                    errorMessage = reason,
                )
            } else {
                current.copy(
                    freshness = PublishedSovereigntyFreshness.STALE,
                    errorMessage = reason,
                )
            }
        }
    }

    companion object {
        const val PUBLIC_ESI_SOURCE = "Sovereignty Pack / PUBLIC_ESI"
        const val EMBEDDED_SOURCE = "Sovereignty Pack / EMBEDDED"

        fun initial(
            activation: SovereigntyRuntimeActivation,
            dataSourceMode: SovereigntyDataSourceMode,
        ): SovereigntyPublicationState {
            val hasRecords = activation.initialSnapshot.records.isNotEmpty()
            val freshness = when {
                !hasRecords -> PublishedSovereigntyFreshness.UNAVAILABLE
                dataSourceMode == SovereigntyDataSourceMode.EMBEDDED -> PublishedSovereigntyFreshness.AVAILABLE
                activation.initialCacheState == SovereigntyInitialCacheState.STALE_LAST_GOOD ->
                    PublishedSovereigntyFreshness.STALE
                else -> PublishedSovereigntyFreshness.AVAILABLE
            }
            return SovereigntyPublicationState(
                PublishedSovereigntySnapshot(
                    records = activation.initialSnapshot.records,
                    observedAt = activation.initialObservedAt,
                    source = if (dataSourceMode == SovereigntyDataSourceMode.PUBLIC_ESI) {
                        PUBLIC_ESI_SOURCE
                    } else {
                        EMBEDDED_SOURCE
                    },
                    freshness = freshness,
                    errorMessage = activation.initialSnapshot.metadata.failureMessage,
                ),
            )
        }
    }
}
