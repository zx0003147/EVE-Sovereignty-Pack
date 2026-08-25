package dev.evestaticmapplanner.sovereignty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SovereigntySnapshotTest {
    @Test
    fun `snapshot defensively copies records and exposes an immutable view`() {
        val sourceRecords = mutableListOf(
            SovereigntyRecord(30_004_759, "Test Alliance", null, "Claimed"),
        )
        val snapshot = SovereigntySnapshot(sourceRecords)

        sourceRecords.clear()

        assertEquals(1, snapshot.records.size)
        assertFailsWith<UnsupportedOperationException> {
            (snapshot.records as MutableList).clear()
        }
    }
}
