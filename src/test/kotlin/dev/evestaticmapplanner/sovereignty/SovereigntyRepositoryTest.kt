package dev.evestaticmapplanner.sovereignty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SovereigntyRepositoryTest {
    @Test
    fun `bundled JSON fixture loads once into the repository`() {
        val loaded = SovereigntyRepository.load(javaClass.getResourceAsStream("/sovereignty.json"))

        assertNull(loaded.failure)
        assertEquals(0, loaded.ignoredRecordCount)
        assertEquals(listOf(30_004_712, 30_004_759), loaded.repository.records().map { it.systemId })
        assertEquals("SV-1 Fixture Corporation", loaded.repository.find(30_004_759)?.corporationName)
    }

    @Test
    fun `invalid and duplicate records are ignored without losing valid records`() {
        val loaded = load(
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
        )

        assertNull(loaded.failure)
        assertEquals(3, loaded.ignoredRecordCount)
        assertEquals("Valid Alliance", loaded.repository.find(30_004_759)?.allianceName)
        assertEquals(1, loaded.repository.records().size)
    }

    @Test
    fun `malformed JSON fails safely with an empty repository`() {
        val loaded = load("[{not-json}]")

        assertNotNull(loaded.failure)
        assertEquals(emptyList(), loaded.repository.records())
    }

    private fun load(json: String) = SovereigntyRepository.load(json.byteInputStream(Charsets.UTF_8))
}
