package dev.evestaticmapplanner.sovereignty

import dev.evestaticmapplanner.feature.api.FeaturePackLogLevel
import dev.evestaticmapplanner.feature.api.FeaturePackLogger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CachedRemoteSovereigntySourceTest {
    @Test
    fun `cache miss loads remote once saves LKG and next source uses it offline`() = withTempDirectory { root ->
        val path = root.resolve("cache/public-esi-lkg.json")
        val expected = snapshot("Remote Alliance")
        var remoteCalls = 0
        val first = CachedRemoteSovereigntySource(
            remote = RemoteSovereigntySource {
                remoteCalls += 1
                RemoteSnapshotResult.Success(expected)
            },
            cache = FileSovereigntySnapshotCache(path),
            logger = RecordingLogger(),
        )

        assertSame(expected, assertIs<RemoteSnapshotResult.Success>(first.fetchSnapshot()).snapshot)
        assertEquals(1, remoteCalls)

        val second = CachedRemoteSovereigntySource(
            remote = RemoteSovereigntySource {
                remoteCalls += 1
                RemoteSnapshotResult.Unavailable("offline")
            },
            cache = FileSovereigntySnapshotCache(path),
            logger = RecordingLogger(),
        )
        assertEquals(expected.records, assertIs<RemoteSnapshotResult.Success>(second.fetchSnapshot()).snapshot.records)
        assertEquals(1, remoteCalls)
    }

    @Test
    fun `invalid cache is ignored and replaced after one remote success`() = withTempDirectory { root ->
        val path = root.resolve("cache/public-esi-lkg.json")
        Files.createDirectories(path.parent)
        Files.writeString(path, "{malformed}")
        val logger = RecordingLogger()
        var remoteCalls = 0
        val expected = snapshot("Replacement Alliance")
        val source = CachedRemoteSovereigntySource(
            remote = RemoteSovereigntySource {
                remoteCalls += 1
                RemoteSnapshotResult.Success(expected)
            },
            cache = FileSovereigntySnapshotCache(path),
            logger = logger,
        )

        assertSame(expected, assertIs<RemoteSnapshotResult.Success>(source.fetchSnapshot()).snapshot)
        assertEquals(1, remoteCalls)
        assertTrue(logger.events.single().message.startsWith("Ignoring unusable"))
        assertEquals("Replacement Alliance", assertIs<SovereigntyCacheLoadResult.Hit>(
            FileSovereigntySnapshotCache(path).load(),
        ).snapshot.records.single().allianceName)
    }

    @Test
    fun `invalid cache plus remote failure preserves the cache file and failure result`() = withTempDirectory { root ->
        val path = root.resolve("cache/public-esi-lkg.json")
        val malformed = "{malformed}"
        Files.createDirectories(path.parent)
        Files.writeString(path, malformed)
        val source = CachedRemoteSovereigntySource(
            remote = RemoteSovereigntySource { RemoteSnapshotResult.Unavailable("offline") },
            cache = FileSovereigntySnapshotCache(path),
            logger = RecordingLogger(),
        )

        assertEquals(RemoteSnapshotResult.Unavailable("offline"), source.fetchSnapshot())
        assertEquals(malformed, Files.readString(path))
    }

    @Test
    fun `remote unavailable preserves failure behavior and writes no cache`() = withTempDirectory { root ->
        val path = root.resolve("cache/public-esi-lkg.json")
        val source = CachedRemoteSovereigntySource(
            remote = RemoteSovereigntySource { RemoteSnapshotResult.Unavailable("offline") },
            cache = FileSovereigntySnapshotCache(path),
            logger = RecordingLogger(),
        )

        assertEquals(RemoteSnapshotResult.Unavailable("offline"), source.fetchSnapshot())
        assertTrue(!path.exists())
    }

    @Test
    fun `remote invalid preserves failure behavior and writes no cache`() = withTempDirectory { root ->
        val path = root.resolve("cache/public-esi-lkg.json")
        val source = CachedRemoteSovereigntySource(
            remote = RemoteSovereigntySource { RemoteSnapshotResult.Invalid("bad payload") },
            cache = FileSovereigntySnapshotCache(path),
            logger = RecordingLogger(),
        )

        assertEquals(RemoteSnapshotResult.Invalid("bad payload"), source.fetchSnapshot())
        assertTrue(!path.exists())
    }

    @Test
    fun `empty remote success is rejected and never cached`() = withTempDirectory { root ->
        val path = root.resolve("cache/public-esi-lkg.json")
        val source = CachedRemoteSovereigntySource(
            remote = RemoteSovereigntySource { RemoteSnapshotResult.Success(SovereigntySnapshot(emptyList())) },
            cache = FileSovereigntySnapshotCache(path),
            logger = RecordingLogger(),
        )

        assertIs<RemoteSnapshotResult.Invalid>(source.fetchSnapshot())
        assertTrue(!path.exists())
    }

    @Test
    fun `cache write failure does not hide valid remote snapshot`() {
        val expected = snapshot("In-memory Alliance")
        val logger = RecordingLogger()
        val provider = RemoteSovereigntySnapshotProvider(
            CachedRemoteSovereigntySource(
                remote = RemoteSovereigntySource { RemoteSnapshotResult.Success(expected) },
                cache = object : SovereigntySnapshotCache {
                    override fun load() = SovereigntyCacheLoadResult.Miss
                    override fun save(snapshot: SovereigntySnapshot) =
                        SovereigntyCacheSaveResult.Failed("read-only filesystem")
                },
                logger = logger,
            ),
        )

        assertEquals(expected.records, provider.loadSnapshot().records)
        assertTrue(logger.events.single().message.contains("Could not save"))
    }

    private class RecordingLogger : FeaturePackLogger {
        val events = mutableListOf<LogEvent>()

        override fun log(level: FeaturePackLogLevel, message: String, cause: Throwable?) {
            events += LogEvent(level, message, cause)
        }
    }

    private data class LogEvent(
        val level: FeaturePackLogLevel,
        val message: String,
        val cause: Throwable?,
    )

    private inline fun withTempDirectory(block: (Path) -> Unit) {
        val root = createTempDirectory("sv-3c-2-source-")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun snapshot(allianceName: String) = SovereigntySnapshot(
        listOf(SovereigntyRecord(30_004_759, allianceName, null, PUBLIC_ESI_CLAIMED_STATUS)),
    )
}
