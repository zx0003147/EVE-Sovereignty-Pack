package dev.evestaticmapplanner.sovereignty

import dev.evestaticmapplanner.feature.api.FeaturePackLogLevel
import dev.evestaticmapplanner.feature.api.FeaturePackLogger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CachedRemoteSovereigntySourceTest {
    @Test
    fun `cache age 59 minutes is fresh and neither calls remote nor rewrites file`() = withTempDirectory { root ->
        val path = root.resolve("cache/public-esi-lkg.json")
        val cached = snapshot("Fresh Alliance")
        val savedAt = NOW.minus(Duration.ofMinutes(59))
        saveAt(path, cached, savedAt)
        var remoteCalls = 0
        val source = source(path) {
            remoteCalls += 1
            RemoteSnapshotResult.Unavailable("must not be called")
        }

        assertEquals(cached.records, assertIs<RemoteSnapshotResult.Success>(source.fetchSnapshot()).snapshot.records)
        assertEquals(0, remoteCalls)
        assertEquals(savedAt, Files.getLastModifiedTime(path).toInstant())
    }

    @Test
    fun `cache age exactly one hour is still fresh`() {
        val cached = snapshot("Boundary Alliance")
        val cache = RecordingCache(SovereigntyCacheLoadResult.Hit(cached, NOW.minus(Duration.ofHours(1))))
        var remoteCalls = 0
        val source = source(cache) {
            remoteCalls += 1
            RemoteSnapshotResult.Unavailable("must not be called")
        }

        assertSame(cached, assertIs<RemoteSnapshotResult.Success>(source.fetchSnapshot()).snapshot)
        assertEquals(0, remoteCalls)
        assertEquals(0, cache.saveCalls)
    }

    @Test
    fun `cache age one hour plus one nanosecond is stale and calls remote once`() {
        val cached = snapshot("Stale Boundary Alliance")
        val cache = RecordingCache(
            SovereigntyCacheLoadResult.Hit(cached, NOW.minus(Duration.ofHours(1)).minusNanos(1)),
        )
        var remoteCalls = 0
        val source = source(cache) {
            remoteCalls += 1
            RemoteSnapshotResult.Unavailable("offline")
        }

        assertSame(cached, assertIs<RemoteSnapshotResult.Success>(source.fetchSnapshot()).snapshot)
        assertEquals(1, remoteCalls)
        assertEquals(0, cache.saveCalls)
    }

    @Test
    fun `future cache timestamp is treated as fresh`() {
        val cached = snapshot("Future Alliance")
        val cache = RecordingCache(SovereigntyCacheLoadResult.Hit(cached, NOW.plusSeconds(10)))
        var remoteCalls = 0
        val source = source(cache) {
            remoteCalls += 1
            RemoteSnapshotResult.Unavailable("must not be called")
        }

        assertSame(cached, assertIs<RemoteSnapshotResult.Success>(source.fetchSnapshot()).snapshot)
        assertEquals(0, remoteCalls)
    }

    @Test
    fun `stale cache plus remote success replaces LKG and advances modification time`() = withTempDirectory { root ->
        val path = root.resolve("cache/public-esi-lkg.json")
        val stale = snapshot("Stale Alliance")
        val replacement = snapshot("Replacement Alliance")
        val staleSavedAt = Instant.parse("2020-01-01T00:00:00Z")
        saveAt(path, stale, staleSavedAt)
        var remoteCalls = 0
        val source = source(path) {
            remoteCalls += 1
            RemoteSnapshotResult.Success(replacement)
        }

        assertSame(replacement, assertIs<RemoteSnapshotResult.Success>(source.fetchSnapshot()).snapshot)
        assertEquals(1, remoteCalls)
        assertEquals(replacement.records, loadHit(path).snapshot.records)
        assertTrue(Files.getLastModifiedTime(path).toInstant().isAfter(staleSavedAt))
    }

    @Test
    fun `stale cache plus remote unavailable retains LKG without touching it`() = withTempDirectory { root ->
        val path = root.resolve("cache/public-esi-lkg.json")
        val stale = snapshot("Stale Alliance")
        val staleSavedAt = NOW.minus(Duration.ofHours(2))
        saveAt(path, stale, staleSavedAt)
        val logger = RecordingLogger()
        var remoteCalls = 0
        val source = source(path, logger) {
            remoteCalls += 1
            RemoteSnapshotResult.Unavailable("offline")
        }

        assertEquals(stale.records, assertIs<RemoteSnapshotResult.Success>(source.fetchSnapshot()).snapshot.records)
        assertEquals(1, remoteCalls)
        assertEquals(stale.records, loadHit(path).snapshot.records)
        assertEquals(staleSavedAt, Files.getLastModifiedTime(path).toInstant())
        assertTrue(logger.events.any { it.level == FeaturePackLogLevel.WARN && it.message.contains("Retaining stale") })
    }

    @Test
    fun `stale cache plus remote invalid retains LKG without touching it`() = withTempDirectory { root ->
        val path = root.resolve("cache/public-esi-lkg.json")
        val stale = snapshot("Stale Alliance")
        val staleSavedAt = NOW.minus(Duration.ofHours(2))
        saveAt(path, stale, staleSavedAt)
        var remoteCalls = 0
        val source = source(path) {
            remoteCalls += 1
            RemoteSnapshotResult.Invalid("bad payload")
        }

        assertEquals(stale.records, assertIs<RemoteSnapshotResult.Success>(source.fetchSnapshot()).snapshot.records)
        assertEquals(1, remoteCalls)
        assertEquals(stale.records, loadHit(path).snapshot.records)
        assertEquals(staleSavedAt, Files.getLastModifiedTime(path).toInstant())
    }

    @Test
    fun `stale cache rejects invalid success and retains LKG`() {
        val stale = snapshot("Stale Alliance")
        val cache = RecordingCache(SovereigntyCacheLoadResult.Hit(stale, NOW.minus(Duration.ofHours(2))))
        var remoteCalls = 0
        val source = source(cache) {
            remoteCalls += 1
            RemoteSnapshotResult.Success(SovereigntySnapshot(emptyList()))
        }

        assertSame(stale, assertIs<RemoteSnapshotResult.Success>(source.fetchSnapshot()).snapshot)
        assertEquals(1, remoteCalls)
        assertEquals(0, cache.saveCalls)
    }

    @Test
    fun `stale cache save failure returns valid remote snapshot and keeps prior LKG`() {
        val stale = snapshot("Stale Alliance")
        val replacement = snapshot("In-memory Alliance")
        val cache = RecordingCache(
            initial = SovereigntyCacheLoadResult.Hit(stale, NOW.minus(Duration.ofHours(2))),
            saveResult = SovereigntyCacheSaveResult.Failed("read-only filesystem"),
        )
        val logger = RecordingLogger()
        val source = source(cache, logger) { RemoteSnapshotResult.Success(replacement) }

        assertSame(replacement, assertIs<RemoteSnapshotResult.Success>(source.fetchSnapshot()).snapshot)
        assertSame(stale, assertIs<SovereigntyCacheLoadResult.Hit>(cache.load()).snapshot)
        assertEquals(1, cache.saveCalls)
        assertTrue(logger.events.any { it.level == FeaturePackLogLevel.WARN && it.message.contains("using valid remote") })
    }

    @Test
    fun `cache miss loads remote once saves LKG and next source uses it offline`() = withTempDirectory { root ->
        val path = root.resolve("cache/public-esi-lkg.json")
        val expected = snapshot("Remote Alliance")
        var remoteCalls = 0
        val first = source(path) {
            remoteCalls += 1
            RemoteSnapshotResult.Success(expected)
        }

        assertSame(expected, assertIs<RemoteSnapshotResult.Success>(first.fetchSnapshot()).snapshot)
        assertEquals(1, remoteCalls)
        Files.setLastModifiedTime(path, FileTime.from(NOW))

        val second = source(path) {
            remoteCalls += 1
            RemoteSnapshotResult.Unavailable("offline")
        }
        assertEquals(expected.records, assertIs<RemoteSnapshotResult.Success>(second.fetchSnapshot()).snapshot.records)
        assertEquals(1, remoteCalls)
    }

    @Test
    fun `malformed cache is unusable and replaced after one remote success`() = withTempDirectory { root ->
        assertUnusableCacheIsReplaced(root, "{malformed}")
    }

    @Test
    fun `unsupported cache version is unusable and replaced after one remote success`() = withTempDirectory { root ->
        assertUnusableCacheIsReplaced(root, """{"formatVersion":2,"source":"PUBLIC_ESI","records":[]}""")
    }

    @Test
    fun `invalid cache record is unusable and replaced after one remote success`() = withTempDirectory { root ->
        assertUnusableCacheIsReplaced(
            root,
            """{"formatVersion":1,"source":"PUBLIC_ESI","records":[{"systemId":42,"allianceName":"Bad","corporationName":null,"sovereigntyStatus":"Claimed"}]}""",
        )
    }

    @Test
    fun `invalid cache plus remote failure preserves the cache file and failure result`() = withTempDirectory { root ->
        val path = root.resolve("cache/public-esi-lkg.json")
        val malformed = "{malformed}"
        Files.createDirectories(path.parent)
        Files.writeString(path, malformed)
        var remoteCalls = 0
        val source = source(path) {
            remoteCalls += 1
            RemoteSnapshotResult.Unavailable("offline")
        }

        assertEquals(RemoteSnapshotResult.Unavailable("offline"), source.fetchSnapshot())
        assertEquals(1, remoteCalls)
        assertEquals(malformed, Files.readString(path))
    }

    @Test
    fun `remote unavailable on cache miss preserves failure behavior and writes no cache`() = withTempDirectory { root ->
        val path = root.resolve("cache/public-esi-lkg.json")
        var remoteCalls = 0
        val source = source(path) {
            remoteCalls += 1
            RemoteSnapshotResult.Unavailable("offline")
        }

        assertEquals(RemoteSnapshotResult.Unavailable("offline"), source.fetchSnapshot())
        assertEquals(1, remoteCalls)
        assertTrue(!path.exists())
    }

    @Test
    fun `remote invalid on cache miss preserves failure behavior and writes no cache`() = withTempDirectory { root ->
        val path = root.resolve("cache/public-esi-lkg.json")
        var remoteCalls = 0
        val source = source(path) {
            remoteCalls += 1
            RemoteSnapshotResult.Invalid("bad payload")
        }

        assertEquals(RemoteSnapshotResult.Invalid("bad payload"), source.fetchSnapshot())
        assertEquals(1, remoteCalls)
        assertTrue(!path.exists())
    }

    @Test
    fun `empty remote success on cache miss is rejected and never cached`() = withTempDirectory { root ->
        val path = root.resolve("cache/public-esi-lkg.json")
        val source = source(path) { RemoteSnapshotResult.Success(SovereigntySnapshot(emptyList())) }

        assertIs<RemoteSnapshotResult.Invalid>(source.fetchSnapshot())
        assertTrue(!path.exists())
    }

    @Test
    fun `cache write failure on miss does not hide valid remote snapshot`() {
        val expected = snapshot("In-memory Alliance")
        val logger = RecordingLogger()
        val cache = RecordingCache(
            initial = SovereigntyCacheLoadResult.Miss,
            saveResult = SovereigntyCacheSaveResult.Failed("read-only filesystem"),
        )
        val provider = RemoteSovereigntySnapshotProvider(
            source(cache, logger) { RemoteSnapshotResult.Success(expected) },
        )

        assertEquals(expected.records, provider.loadSnapshot().records)
        assertTrue(logger.events.any { it.message.contains("Could not save") })
    }

    private fun assertUnusableCacheIsReplaced(root: Path, cacheText: String) {
        val path = root.resolve("cache/public-esi-lkg.json")
        Files.createDirectories(path.parent)
        Files.writeString(path, cacheText)
        val logger = RecordingLogger()
        var remoteCalls = 0
        val expected = snapshot("Replacement Alliance")
        val source = source(path, logger) {
            remoteCalls += 1
            RemoteSnapshotResult.Success(expected)
        }

        assertSame(expected, assertIs<RemoteSnapshotResult.Success>(source.fetchSnapshot()).snapshot)
        assertEquals(1, remoteCalls)
        assertTrue(logger.events.any { it.message.startsWith("Ignoring unusable") })
        assertEquals("Replacement Alliance", loadHit(path).snapshot.records.single().allianceName)
    }

    private fun source(
        path: Path,
        logger: RecordingLogger = RecordingLogger(),
        remote: () -> RemoteSnapshotResult,
    ) = source(FileSovereigntySnapshotCache(path), logger, remote)

    private fun source(
        cache: SovereigntySnapshotCache,
        logger: RecordingLogger = RecordingLogger(),
        remote: () -> RemoteSnapshotResult,
    ) = CachedRemoteSovereigntySource(
        remote = RemoteSovereigntySource(remote),
        cache = cache,
        logger = logger,
        clock = FIXED_CLOCK,
    )

    private fun saveAt(path: Path, snapshot: SovereigntySnapshot, savedAt: Instant) {
        assertEquals(SovereigntyCacheSaveResult.Saved, FileSovereigntySnapshotCache(path).save(snapshot))
        Files.setLastModifiedTime(path, FileTime.from(savedAt))
        assertEquals(savedAt, Files.getLastModifiedTime(path).toInstant())
    }

    private fun loadHit(path: Path) =
        assertIs<SovereigntyCacheLoadResult.Hit>(FileSovereigntySnapshotCache(path).load())

    private class RecordingCache(
        initial: SovereigntyCacheLoadResult,
        private val saveResult: SovereigntyCacheSaveResult = SovereigntyCacheSaveResult.Saved,
    ) : SovereigntySnapshotCache {
        private var result = initial
        var saveCalls = 0
            private set

        override fun load() = result

        override fun save(snapshot: SovereigntySnapshot): SovereigntyCacheSaveResult {
            saveCalls += 1
            if (saveResult == SovereigntyCacheSaveResult.Saved) {
                result = SovereigntyCacheLoadResult.Hit(snapshot, NOW)
            }
            return saveResult
        }
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
        val root = createTempDirectory("sv-lite-1-source-")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun snapshot(allianceName: String) = SovereigntySnapshot(
        listOf(SovereigntyRecord(30_004_759, allianceName, null, PUBLIC_ESI_CLAIMED_STATUS)),
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-26T12:00:00Z")
        val FIXED_CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }
}
