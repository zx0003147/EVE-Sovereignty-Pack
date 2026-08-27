package dev.evestaticmapplanner.sovereignty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PublicEsiSovereigntySourceTest {
    @Test
    fun `successful ESI payload resolves names and creates alliance snapshot`() {
        val client = FakePublicEsiClient(
            sovereigntyResult = PublicEsiPayloadResult.Success(VALID_SOVEREIGNTY_PAYLOAD),
            namesResult = PublicEsiPayloadResult.Success(VALID_NAMES_PAYLOAD),
        )

        val result = assertIs<RemoteSnapshotResult.Success>(
            PublicEsiSovereigntySource(client).fetchSnapshot(),
        )

        assertEquals(listOf(98_599_770, 99_003_581), client.requestedNameIds.single())
        assertEquals(1, result.snapshot.records.size)
        with(result.snapshot.records.single()) {
            assertEquals(30_004_759, systemId)
            assertEquals("Remote Alliance", allianceName)
            assertEquals("Remote Corporation", corporationName)
            assertEquals("Claimed", sovereigntyStatus)
            assertEquals(99_003_581, allianceId)
        }
    }

    @Test
    fun `empty response body is invalid`() {
        val result = sourceForSovereigntyPayload("").fetchSnapshot()

        assertIs<RemoteSnapshotResult.Invalid>(result)
    }

    @Test
    fun `payload with no solar systems is invalid`() {
        val result = sourceForSovereigntyPayload("""{"solar_systems":[]}""").fetchSnapshot()

        assertTrue(assertIs<RemoteSnapshotResult.Invalid>(result).reason.contains("no solar systems"))
    }

    @Test
    fun `malformed payload is invalid`() {
        val result = sourceForSovereigntyPayload("{not-json}").fetchSnapshot()

        assertIs<RemoteSnapshotResult.Invalid>(result)
    }

    @Test
    fun `network failure is unavailable`() {
        val client = FakePublicEsiClient(
            sovereigntyResult = PublicEsiPayloadResult.Unavailable("No network connectivity"),
        )

        val result = PublicEsiSovereigntySource(client).fetchSnapshot()

        assertEquals(
            RemoteSnapshotResult.Unavailable("No network connectivity"),
            result,
        )
    }

    @Test
    fun `name resolution network failure is unavailable`() {
        val client = FakePublicEsiClient(
            sovereigntyResult = PublicEsiPayloadResult.Success(alliancePayload()),
            namesResult = PublicEsiPayloadResult.Unavailable("Name service unavailable"),
        )

        val result = PublicEsiSovereigntySource(client).fetchSnapshot()

        assertEquals(
            RemoteSnapshotResult.Unavailable("Name service unavailable"),
            result,
        )
    }

    @Test
    fun `system ID outside New Eden range is invalid`() {
        val result = sourceForSovereigntyPayload(
            alliancePayload(systemId = 42),
        ).fetchSnapshot()

        assertTrue(assertIs<RemoteSnapshotResult.Invalid>(result).reason.contains("invalid solar_system_id 42"))
    }

    @Test
    fun `alliance claim without alliance ID is invalid`() {
        val result = sourceForSovereigntyPayload(
            """
            {
              "solar_systems": [
                {
                  "solar_system_id": 30004759,
                  "claim": {"alliance": {"corporation_id": 98599770}}
                }
              ]
            }
            """.trimIndent(),
        ).fetchSnapshot()

        assertTrue(assertIs<RemoteSnapshotResult.Invalid>(result).reason.contains("alliance_id"))
    }

    @Test
    fun `empty resolved alliance name is invalid`() {
        val client = FakePublicEsiClient(
            sovereigntyResult = PublicEsiPayloadResult.Success(alliancePayload()),
            namesResult = PublicEsiPayloadResult.Success(
                """
                [
                  {"category":"alliance","id":99003581,"name":""},
                  {"category":"corporation","id":98599770,"name":"Remote Corporation"}
                ]
                """.trimIndent(),
            ),
        )

        val result = PublicEsiSovereigntySource(client).fetchSnapshot()

        assertTrue(assertIs<RemoteSnapshotResult.Invalid>(result).reason.contains("name is empty or invalid"))
    }

    @Test
    fun `duplicate system ownership makes the complete payload invalid`() {
        val duplicate = """
            {
              "solar_systems": [
                {
                  "solar_system_id": 30004759,
                  "claim": {"alliance": {"alliance_id": 99003581}}
                },
                {
                  "solar_system_id": 30004759,
                  "claim": {"unclaimed": true}
                }
              ]
            }
        """.trimIndent()

        val result = sourceForSovereigntyPayload(duplicate).fetchSnapshot()

        assertTrue(assertIs<RemoteSnapshotResult.Invalid>(result).reason.contains("duplicate solar_system_id 30004759"))
    }

    @Test
    fun `remote snapshot flows through repository overlay and system info unchanged`() {
        val source = PublicEsiSovereigntySource(
            FakePublicEsiClient(
                sovereigntyResult = PublicEsiPayloadResult.Success(alliancePayload()),
                namesResult = PublicEsiPayloadResult.Success(VALID_NAMES_PAYLOAD),
            ),
        )
        val repository = SovereigntyRepository(RemoteSovereigntySnapshotProvider(source))

        val overlayEntry = SovereigntyOverlayProvider(repository).snapshot().entries.single()
        assertEquals(30_004_759, overlayEntry.systemId)
        assertEquals("Remote Alliance", overlayEntry.title)
        assertEquals("Claimed", overlayEntry.subtitle)
        assertTrue(overlayEntry.value!!.startsWith("owner-key:alliance:99003581;presentation-color:"))

        val fields = SovereigntySystemInfoProvider(repository)
            .provide(30_004_759)
            .sections.single()
            .fields
            .associate { it.label to it.value }
        assertEquals("Remote Alliance", fields["Owner"])
        assertEquals("Claimed", fields["Status"])
    }

    private fun sourceForSovereigntyPayload(payload: String) = PublicEsiSovereigntySource(
        FakePublicEsiClient(
            sovereigntyResult = PublicEsiPayloadResult.Success(payload),
            namesResult = PublicEsiPayloadResult.Success(VALID_NAMES_PAYLOAD),
        ),
    )

    private class FakePublicEsiClient(
        private val sovereigntyResult: PublicEsiPayloadResult,
        private val namesResult: PublicEsiPayloadResult = PublicEsiPayloadResult.Invalid("Unexpected name request"),
    ) : PublicEsiClient {
        val requestedNameIds = mutableListOf<List<Int>>()

        override fun fetchSovereigntySystems(): PublicEsiPayloadResult = sovereigntyResult

        override fun resolveNames(ids: List<Int>): PublicEsiPayloadResult {
            requestedNameIds += ids
            return namesResult
        }
    }

    private companion object {
        val VALID_SOVEREIGNTY_PAYLOAD = """
            {
              "solar_systems": [
                {
                  "solar_system_id": 30004759,
                  "claim": {
                    "alliance": {
                      "alliance_id": 99003581,
                      "corporation_id": 98599770,
                      "claimed_since": "2020-10-08T00:38:16Z",
                      "is_capital_system": false,
                      "development": {"activity_defense_multiplier": 6.0}
                    }
                  }
                },
                {
                  "solar_system_id": 30004760,
                  "claim": {"faction": {"faction_id": 500001}}
                },
                {
                  "solar_system_id": 30004761,
                  "claim": {"unclaimed": true}
                }
              ]
            }
        """.trimIndent()

        val VALID_NAMES_PAYLOAD = """
            [
              {"category":"alliance","id":99003581,"name":"Remote Alliance"},
              {"category":"corporation","id":98599770,"name":"Remote Corporation"}
            ]
        """.trimIndent()

        fun alliancePayload(systemId: Int = 30_004_759) = """
            {
              "solar_systems": [
                {
                  "solar_system_id": $systemId,
                  "claim": {
                    "alliance": {
                      "alliance_id": 99003581,
                      "corporation_id": 98599770
                    }
                  }
                }
              ]
            }
        """.trimIndent()
    }
}
