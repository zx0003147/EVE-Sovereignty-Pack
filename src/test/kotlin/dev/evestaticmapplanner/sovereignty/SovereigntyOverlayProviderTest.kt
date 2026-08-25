package dev.evestaticmapplanner.sovereignty

import kotlin.test.Test
import kotlin.test.assertEquals

class SovereigntyOverlayProviderTest {
    @Test
    fun `provider exposes a Sovereignty layer with one entry per record`() {
        val repository = SovereigntyRepository.load(javaClass.getResourceAsStream("/sovereignty.json")).repository
        val provider = SovereigntyOverlayProvider(repository)

        assertEquals("sovereignty.pack.overlay", provider.descriptor().id)
        assertEquals("Sovereignty", provider.layers().single().name)
        assertEquals("sovereignty", provider.layers().single().id)
        assertEquals(2, provider.snapshot().entries.size)
        with(provider.snapshot().entries.first { it.systemId == 30_004_759 }) {
            assertEquals("SV-1 Fixture Alliance", title)
            assertEquals("Claimed (static fixture)", subtitle)
        }
    }
}
