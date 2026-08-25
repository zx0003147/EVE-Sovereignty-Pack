package dev.evestaticmapplanner.sovereignty

import kotlin.test.Test
import kotlin.test.assertEquals

class SovereigntyRepositoryTest {
    @Test
    fun `injected provider loads once and repository queries its snapshot`() {
        var loadCount = 0
        val provider = SovereigntySnapshotProvider {
            loadCount += 1
            SovereigntySnapshot(
                listOf(
                    SovereigntyRecord(
                        systemId = 30_004_759,
                        allianceName = "Test Alliance",
                        corporationName = "Test Corporation",
                        sovereigntyStatus = "Claimed",
                    ),
                    SovereigntyRecord(
                        systemId = 30_004_712,
                        allianceName = "Other Alliance",
                        corporationName = null,
                        sovereigntyStatus = "Contested",
                    ),
                ),
            )
        }

        val repository = SovereigntyRepository(provider)

        assertEquals(1, loadCount)
        assertEquals(listOf(30_004_712, 30_004_759), repository.records().map { it.systemId })
        assertEquals("Test Corporation", repository.find(30_004_759)?.corporationName)
        assertEquals(null, repository.find(30_999_999))
        assertEquals(1, loadCount)
    }
}
