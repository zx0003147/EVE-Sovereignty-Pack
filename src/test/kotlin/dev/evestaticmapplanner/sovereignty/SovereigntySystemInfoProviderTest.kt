package dev.evestaticmapplanner.sovereignty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SovereigntySystemInfoProviderTest {
    private val provider = SovereigntySystemInfoProvider(
        SovereigntyRepository(EmbeddedJsonSnapshotProvider()),
    )

    @Test
    fun `selected known system returns sovereignty section`() {
        val section = provider.provide(30_004_759).sections.single()

        assertEquals("Sovereignty", section.title)
        assertEquals(
            listOf("Owner" to "Goonswarm Federation", "Status" to "Claimed (static fixture)"),
            section.fields.map { it.label to it.value },
        )
    }

    @Test
    fun `unknown system returns empty snapshot`() {
        assertTrue(provider.provide(30_999_999).sections.isEmpty())
    }
}
