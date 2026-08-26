package dev.evestaticmapplanner.sovereignty

import dev.evestaticmapplanner.feature.api.FeaturePackLogLevel
import dev.evestaticmapplanner.feature.api.FeaturePackLogger
import dev.evestaticmapplanner.feature.api.PackRelativePath
import dev.evestaticmapplanner.feature.api.PackStorage
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SovereigntyRuntimeCompositionTest {
    @Test
    fun `embedded mode selects embedded provider and retains the fixture`() {
        val provider = SovereigntyRuntimeComposition(
            SovereigntyDataSourceMode.EMBEDDED,
        ).createSnapshotProvider(TestStorage(Path.of("unused")), SilentLogger)

        assertIs<EmbeddedJsonSnapshotProvider>(provider)
        assertEquals("Goonswarm Federation", provider.loadSnapshot().records
            .single { it.systemId == 30_004_759 }
            .allianceName)
    }

    @Test
    fun `valid production cache short circuits all Public ESI requests`() = withTempDirectory { root ->
        val storage = TestStorage(root)
        val expected = canonicalSnapshot("Cached Alliance")
        assertEquals(
            SovereigntyCacheSaveResult.Saved,
            FileSovereigntySnapshotCache(
                storage.cachePath(SovereigntyRuntimeComposition.PUBLIC_ESI_LKG_CACHE_PATH),
            ).save(expected),
        )
        var sovereigntyRequests = 0
        var namesRequests = 0
        val client = object : PublicEsiClient {
            override fun fetchSovereigntySystems(): PublicEsiPayloadResult {
                sovereigntyRequests += 1
                return PublicEsiPayloadResult.Unavailable("must not be called")
            }

            override fun resolveNames(ids: List<Int>): PublicEsiPayloadResult {
                namesRequests += 1
                return PublicEsiPayloadResult.Unavailable("must not be called")
            }
        }
        val provider = SovereigntyRuntimeComposition(
            dataSourceMode = SovereigntyDataSourceMode.PUBLIC_ESI,
            publicEsiClientFactory = { client },
        ).createSnapshotProvider(storage, SilentLogger)

        assertIs<RemoteSovereigntySnapshotProvider>(provider)
        assertEquals(expected.records, provider.loadSnapshot().records)
        assertEquals(0, sovereigntyRequests)
        assertEquals(0, namesRequests)
    }

    @Test
    fun `cache miss loads Public ESI once and a new composition starts from the saved LKG`() =
        withTempDirectory { root ->
            val storage = TestStorage(root)
            var sovereigntyRequests = 0
            var namesRequests = 0
            val onlineClient = object : PublicEsiClient {
                override fun fetchSovereigntySystems(): PublicEsiPayloadResult {
                    sovereigntyRequests += 1
                    return PublicEsiPayloadResult.Success(
                        """{"solar_systems":[{"solar_system_id":30004759,"claim":{"alliance":{"alliance_id":99000001}}}]}""",
                    )
                }

                override fun resolveNames(ids: List<Int>): PublicEsiPayloadResult {
                    namesRequests += 1
                    assertEquals(listOf(99_000_001), ids)
                    return PublicEsiPayloadResult.Success(
                        """[{"id":99000001,"name":"Remote Alliance","category":"alliance"}]""",
                    )
                }
            }
            val first = SovereigntyRuntimeComposition(
                dataSourceMode = SovereigntyDataSourceMode.PUBLIC_ESI,
                publicEsiClientFactory = { onlineClient },
            ).createSnapshotProvider(storage, SilentLogger)

            assertEquals("Remote Alliance", first.loadSnapshot().records.single().allianceName)
            assertEquals(1, sovereigntyRequests)
            assertEquals(1, namesRequests)

            val offline = SovereigntyRuntimeComposition(
                dataSourceMode = SovereigntyDataSourceMode.PUBLIC_ESI,
                publicEsiClientFactory = {
                    object : PublicEsiClient {
                        override fun fetchSovereigntySystems(): PublicEsiPayloadResult {
                            sovereigntyRequests += 1
                            return PublicEsiPayloadResult.Unavailable("offline")
                        }

                        override fun resolveNames(ids: List<Int>): PublicEsiPayloadResult {
                            namesRequests += 1
                            return PublicEsiPayloadResult.Unavailable("offline")
                        }
                    }
                },
            ).createSnapshotProvider(storage, SilentLogger)

            assertEquals("Remote Alliance", offline.loadSnapshot().records.single().allianceName)
            assertEquals(1, sovereigntyRequests)
            assertEquals(1, namesRequests)
        }

    private class TestStorage(private val root: Path) : PackStorage {
        override fun dataPath(relativePath: PackRelativePath): Path = root.resolve("data").resolve(relativePath.toPath())
        override fun configPath(relativePath: PackRelativePath): Path =
            root.resolve("config").resolve(relativePath.toPath())
        override fun cachePath(relativePath: PackRelativePath): Path =
            root.resolve("cache").resolve(relativePath.toPath())
    }

    private object SilentLogger : FeaturePackLogger {
        override fun log(level: FeaturePackLogLevel, message: String, cause: Throwable?) = Unit
    }

    private inline fun withTempDirectory(block: (Path) -> Unit) {
        val root = createTempDirectory("sv-3c-2-composition-")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun canonicalSnapshot(allianceName: String) = SovereigntySnapshot(
        listOf(SovereigntyRecord(30_004_759, allianceName, null, PUBLIC_ESI_CLAIMED_STATUS)),
    )
}
