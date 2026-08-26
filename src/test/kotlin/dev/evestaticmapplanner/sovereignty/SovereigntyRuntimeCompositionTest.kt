package dev.evestaticmapplanner.sovereignty

import dev.evestaticmapplanner.feature.api.FeaturePackLogLevel
import dev.evestaticmapplanner.feature.api.FeaturePackLogger
import dev.evestaticmapplanner.feature.api.PackRelativePath
import dev.evestaticmapplanner.feature.api.PackStorage
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
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
        val cachePath = storage.cachePath(SovereigntyRuntimeComposition.PUBLIC_ESI_LKG_CACHE_PATH)
        assertEquals(
            SovereigntyCacheSaveResult.Saved,
            FileSovereigntySnapshotCache(cachePath).save(expected),
        )
        Files.setLastModifiedTime(cachePath, FileTime.from(NOW.minus(Duration.ofMinutes(30))))
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
            clock = FIXED_CLOCK,
        ).createSnapshotProvider(storage, SilentLogger)

        assertIs<RemoteSovereigntySnapshotProvider>(provider)
        assertEquals(expected.records, provider.loadSnapshot().records)
        assertEquals(0, sovereigntyRequests)
        assertEquals(0, namesRequests)
    }

    @Test
    fun `production composition selects missing then fresh then stale snapshots across activations`() =
        withTempDirectory { root ->
            val storage = TestStorage(root)
            val cachePath = storage.cachePath(SovereigntyRuntimeComposition.PUBLIC_ESI_LKG_CACHE_PATH)
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
                clock = FIXED_CLOCK,
            ).createSnapshotProvider(storage, SilentLogger)

            assertEquals("Remote Alliance", first.loadSnapshot().records.single().allianceName)
            assertEquals(1, sovereigntyRequests)
            assertEquals(1, namesRequests)
            Files.setLastModifiedTime(cachePath, FileTime.from(NOW.minus(Duration.ofMinutes(15))))

            val freshActivation = SovereigntyRuntimeComposition(
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
                clock = FIXED_CLOCK,
            ).createSnapshotProvider(storage, SilentLogger)

            assertEquals("Remote Alliance", freshActivation.loadSnapshot().records.single().allianceName)
            assertEquals(1, sovereigntyRequests)
            assertEquals(1, namesRequests)

            Files.setLastModifiedTime(cachePath, FileTime.from(NOW.minus(Duration.ofHours(2))))
            val staleActivation = SovereigntyRuntimeComposition(
                dataSourceMode = SovereigntyDataSourceMode.PUBLIC_ESI,
                publicEsiClientFactory = {
                    object : PublicEsiClient {
                        override fun fetchSovereigntySystems(): PublicEsiPayloadResult {
                            sovereigntyRequests += 1
                            return PublicEsiPayloadResult.Unavailable("offline")
                        }

                        override fun resolveNames(ids: List<Int>): PublicEsiPayloadResult {
                            namesRequests += 1
                            return PublicEsiPayloadResult.Unavailable("must not resolve names")
                        }
                    }
                },
                clock = FIXED_CLOCK,
            ).createSnapshotProvider(storage, SilentLogger)

            assertEquals("Remote Alliance", staleActivation.loadSnapshot().records.single().allianceName)
            assertEquals(2, sovereigntyRequests)
            assertEquals(1, namesRequests)
            assertEquals(NOW.minus(Duration.ofHours(2)), Files.getLastModifiedTime(cachePath).toInstant())
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

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-26T12:00:00Z")
        val FIXED_CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }
}
