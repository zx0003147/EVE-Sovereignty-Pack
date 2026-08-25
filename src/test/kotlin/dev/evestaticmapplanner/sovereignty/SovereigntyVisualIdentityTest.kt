package dev.evestaticmapplanner.sovereignty

import kotlin.test.Test
import kotlin.test.assertEquals

class SovereigntyVisualIdentityTest {
    @Test
    fun `alliance color mapping is deterministic`() {
        assertEquals(
            "ring-color:${SovereigntyVisualIdentity.GOONSWARM_YELLOW}",
            SovereigntyVisualIdentity.ringMetadata("Goonswarm Federation"),
        )
        assertEquals(
            "ring-color:${SovereigntyVisualIdentity.FRATERNITY_BLUE}",
            SovereigntyVisualIdentity.ringMetadata("Fraternity"),
        )
        assertEquals(
            SovereigntyVisualIdentity.ringMetadata("Goonswarm Federation"),
            SovereigntyVisualIdentity.ringMetadata("Goonswarm Federation"),
        )
    }

    @Test
    fun `unknown alliance uses neutral fallback`() {
        assertEquals(
            "ring-color:${SovereigntyVisualIdentity.UNKNOWN_GRAY}",
            SovereigntyVisualIdentity.ringMetadata("Unknown Alliance"),
        )
    }
}
