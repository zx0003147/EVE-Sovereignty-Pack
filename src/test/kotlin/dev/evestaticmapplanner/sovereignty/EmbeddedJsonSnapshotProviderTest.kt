package dev.evestaticmapplanner.sovereignty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EmbeddedJsonSnapshotProviderTest {
    @Test
    fun `bundled sovereignty JSON loads into a snapshot`() {
        val snapshot = EmbeddedJsonSnapshotProvider().loadSnapshot()

        assertNull(snapshot.metadata.failureMessage)
        assertEquals(0, snapshot.metadata.ignoredRecordCount)
        assertEquals(listOf(30_004_759, 30_004_712), snapshot.records.map { it.systemId })
        assertEquals("SV-1 Fixture Corporation", snapshot.records.first().corporationName)
    }

    @Test
    fun `invalid JSON is handled safely with an empty snapshot`() {
        val snapshot = providerFor("[{not-json}]").loadSnapshot()

        assertNotNull(snapshot.metadata.failureMessage)
        assertEquals(emptyList(), snapshot.records)
    }

    @Test
    fun `missing resource is handled safely with an empty snapshot`() {
        val snapshot = EmbeddedJsonSnapshotProvider(resourceLoader = { null }).loadSnapshot()

        assertEquals("Bundled sovereignty.json resource is missing", snapshot.metadata.failureMessage)
        assertEquals(emptyList(), snapshot.records)
    }

    @Test
    fun `invalid and duplicate records are ignored without losing valid records`() {
        val snapshot = providerFor(
            """
            [
              {"systemId": 30004759, "allianceName": "Valid Alliance", "corporationName": null,
               "sovereigntyStatus": "Claimed"},
              {"systemId": -1, "allianceName": "Invalid Alliance", "corporationName": null,
               "sovereigntyStatus": "Claimed"},
              {"systemId": 30004760, "allianceName": "", "corporationName": null,
               "sovereigntyStatus": "Claimed"},
              {"systemId": 30004759, "allianceName": "Duplicate Alliance", "corporationName": null,
               "sovereigntyStatus": "Claimed"}
            ]
            """.trimIndent(),
        ).loadSnapshot()

        assertNull(snapshot.metadata.failureMessage)
        assertEquals(3, snapshot.metadata.ignoredRecordCount)
        assertEquals("Valid Alliance", snapshot.records.single().allianceName)
    }

    private fun providerFor(json: String) = EmbeddedJsonSnapshotProvider(
        resourceLoader = { json.byteInputStream(Charsets.UTF_8) },
    )
}
