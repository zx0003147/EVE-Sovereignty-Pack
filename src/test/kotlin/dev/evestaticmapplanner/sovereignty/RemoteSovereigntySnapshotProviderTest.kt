package dev.evestaticmapplanner.sovereignty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class RemoteSovereigntySnapshotProviderTest {
    @Test
    fun `successful remote result supplies its snapshot`() {
        val expected = SovereigntySnapshot(
            records = listOf(
                SovereigntyRecord(
                    systemId = 30_004_759,
                    allianceName = "Remote Alliance",
                    corporationName = "Remote Corporation",
                    sovereigntyStatus = "Claimed",
                ),
            ),
        )
        val provider = RemoteSovereigntySnapshotProvider(
            source = RemoteSovereigntySource { RemoteSnapshotResult.Success(expected) },
        )

        val actual = provider.loadSnapshot()

        assertSame(expected, actual)
        assertNull(actual.metadata.failureMessage)
    }

    @Test
    fun `unavailable remote result becomes an empty failed snapshot`() {
        val provider = RemoteSovereigntySnapshotProvider(
            source = RemoteSovereigntySource {
                RemoteSnapshotResult.Unavailable("No network connectivity")
            },
        )

        val snapshot = provider.loadSnapshot()

        assertEquals(emptyList(), snapshot.records)
        assertEquals(
            "Remote sovereignty source is unavailable: No network connectivity",
            snapshot.metadata.failureMessage,
        )
    }

    @Test
    fun `invalid remote result becomes an empty failed snapshot`() {
        val provider = RemoteSovereigntySnapshotProvider(
            source = RemoteSovereigntySource {
                RemoteSnapshotResult.Invalid("Schema mismatch")
            },
        )

        val snapshot = provider.loadSnapshot()

        assertEquals(emptyList(), snapshot.records)
        assertEquals(
            "Remote sovereignty snapshot is invalid: Schema mismatch",
            snapshot.metadata.failureMessage,
        )
    }

    @Test
    fun `remote provider can be injected into repository`() {
        var fetchCount = 0
        val provider = RemoteSovereigntySnapshotProvider(
            source = RemoteSovereigntySource {
                fetchCount += 1
                RemoteSnapshotResult.Success(
                    SovereigntySnapshot(
                        records = listOf(
                            SovereigntyRecord(
                                systemId = 30_004_712,
                                allianceName = "Injected Alliance",
                                corporationName = null,
                                sovereigntyStatus = "Contested",
                            ),
                        ),
                    ),
                )
            },
        )

        val repository = SovereigntyRepository(provider)

        assertEquals("Injected Alliance", repository.find(30_004_712)?.allianceName)
        assertEquals(1, fetchCount)
    }
}
