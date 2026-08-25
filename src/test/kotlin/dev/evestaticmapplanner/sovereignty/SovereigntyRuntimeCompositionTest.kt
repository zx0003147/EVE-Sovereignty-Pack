package dev.evestaticmapplanner.sovereignty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SovereigntyRuntimeCompositionTest {
    @Test
    fun `embedded mode selects embedded provider and retains the fixture`() {
        val provider = SovereigntyRuntimeComposition(
            SovereigntyDataSourceMode.EMBEDDED,
        ).createSnapshotProvider()

        assertIs<EmbeddedJsonSnapshotProvider>(provider)
        assertEquals("Goonswarm Federation", provider.loadSnapshot().records
            .single { it.systemId == 30_004_759 }
            .allianceName)
    }

    @Test
    fun `public ESI mode selects remote provider through Public ESI client`() {
        val client = object : PublicEsiClient {
            override fun fetchSovereigntySystems() = PublicEsiPayloadResult.Success(
                """{"solar_systems":[{"solar_system_id":30004759,"claim":{"unclaimed":true}}]}""",
            )

            override fun resolveNames(ids: List<Int>): PublicEsiPayloadResult =
                error("Unclaimed sovereignty must not request owner names")
        }
        val provider = SovereigntyRuntimeComposition(
            dataSourceMode = SovereigntyDataSourceMode.PUBLIC_ESI,
            publicEsiClientFactory = { client },
        ).createSnapshotProvider()

        assertIs<RemoteSovereigntySnapshotProvider>(provider)
        assertEquals(emptyList(), provider.loadSnapshot().records)
    }
}
