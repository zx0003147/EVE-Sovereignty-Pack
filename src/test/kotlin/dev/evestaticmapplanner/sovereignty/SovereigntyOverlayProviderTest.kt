package dev.evestaticmapplanner.sovereignty

import kotlin.test.Test
import kotlin.test.assertEquals

class SovereigntyOverlayProviderTest {
    @Test
    fun `provider exposes a Sovereignty layer with one entry per record`() {
        val repository = SovereigntyRepository(EmbeddedJsonSnapshotProvider())
        val provider = SovereigntyOverlayProvider(repository)

        assertEquals("sovereignty.pack.overlay", provider.descriptor().id)
        assertEquals("Sovereignty", provider.layers().single().name)
        assertEquals("sovereignty", provider.layers().single().id)
        assertEquals(2, provider.snapshot().entries.size)
        with(provider.snapshot().entries.first { it.systemId == 30_004_759 }) {
            assertEquals("Goonswarm Federation", title)
            assertEquals("Claimed (static fixture)", subtitle)
            assertEquals(
                "owner-key:alliance:${SovereigntyVisualIdentity.GOONSWARM_ALLIANCE_ID};" +
                    "presentation-color:${SovereigntyVisualIdentity.GOONSWARM_YELLOW};" +
                    "presentation-emblem-key:eve-alliance:${SovereigntyVisualIdentity.GOONSWARM_ALLIANCE_ID};" +
                    "presentation-emblem-url:https://images.evetech.net/alliances/" +
                    "${SovereigntyVisualIdentity.GOONSWARM_ALLIANCE_ID}/logo?size=256",
                value,
            )
        }
    }
}
