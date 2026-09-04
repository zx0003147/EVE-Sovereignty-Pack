package dev.evestaticmapplanner.sovereignty

import dev.evestaticmapplanner.feature.api.CoreVersion
import dev.evestaticmapplanner.feature.api.DynamicOverlayCapability
import dev.evestaticmapplanner.feature.api.DynamicOverlayRegistration
import dev.evestaticmapplanner.feature.api.FeatureApiVersions
import dev.evestaticmapplanner.feature.api.FeatureCapability
import dev.evestaticmapplanner.feature.api.FeatureCapabilityKey
import dev.evestaticmapplanner.feature.api.FeatureCapabilityLookup
import dev.evestaticmapplanner.feature.api.FeaturePackContext
import dev.evestaticmapplanner.feature.api.FeaturePackHostInfo
import dev.evestaticmapplanner.feature.api.FeaturePackLogLevel
import dev.evestaticmapplanner.feature.api.FeaturePackLogger
import dev.evestaticmapplanner.feature.api.HostPlatform
import dev.evestaticmapplanner.feature.api.OverlayProvider
import dev.evestaticmapplanner.feature.api.OverlayRegistration
import dev.evestaticmapplanner.feature.api.OverlayRegistry
import dev.evestaticmapplanner.feature.api.OverlaySnapshot
import dev.evestaticmapplanner.feature.api.PackRelativePath
import dev.evestaticmapplanner.feature.api.PackStorage
import dev.evestaticmapplanner.feature.api.StandardFeatureCapabilities
import dev.evestaticmapplanner.feature.api.SystemInfoProvider
import dev.evestaticmapplanner.feature.api.SystemInfoRegistration
import dev.evestaticmapplanner.feature.api.SystemInfoRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SovereigntyRefreshLifecycleTest {
    @Test
    fun `fresh cache publishes immediately and schedules no network refresh`() = withRuntime { runtime ->
        runtime.saveCache(cachedSnapshot("Fresh Alliance"), NOW.minus(Duration.ofMinutes(30)))
        val client = ControlledPublicEsiClient(RemoteMode.FAILURE)

        val session = runtime.start(client)

        assertEquals("Fresh Alliance", runtime.context.dynamicOverlay.currentAlliance())
        assertEquals(0, runtime.context.dynamicOverlay.refreshRequests.get())
        assertEquals(0, client.sovereigntyRequests.get())
        session.close()
        assertFalse(client.closed.get(), "unused HTTP client factory must remain lazy")
    }

    @Test
    fun `stale LKG is immediate and one background success atomically publishes fresh data`() = withRuntime { runtime ->
        runtime.saveCache(cachedSnapshot("Stale Alliance"), NOW.minus(Duration.ofHours(2)))
        val client = ControlledPublicEsiClient(RemoteMode.SUCCESS)

        val session = runtime.start(client)

        assertEquals("Stale Alliance", runtime.context.dynamicOverlay.currentAlliance())
        assertTrue(client.started.await(1, TimeUnit.SECONDS))
        assertEquals(1, runtime.context.dynamicOverlay.refreshRequests.get())
        client.release.countDown()
        runtime.context.dynamicOverlay.awaitRefresh()

        assertEquals("Remote Alliance", runtime.context.dynamicOverlay.currentAlliance())
        assertEquals("Remote Alliance", runtime.context.systemInfo.currentAlliance())
        assertEquals(1, runtime.context.systemInfo.refreshes.get())
        assertEquals("Remote Alliance", runtime.loadCache().records.single().allianceName)
        assertEquals(1, client.sovereigntyRequests.get())
        assertEquals(1, client.namesRequests.get())
        assertTrue(runtime.context.events.any { it.contains("background refresh published fresh data") })
        session.close()
    }

    @Test
    fun `duplicate invalidations cannot start duplicate refreshes or let stale overwrite fresh`() = withRuntime { runtime ->
        runtime.saveCache(cachedSnapshot("Stale Alliance"), NOW.minus(Duration.ofHours(2)))
        val client = ControlledPublicEsiClient(RemoteMode.SUCCESS)
        val session = runtime.start(client)
        assertTrue(client.started.await(1, TimeUnit.SECONDS))

        repeat(3) { runtime.context.dynamicOverlay.requestAgain() }
        client.release.countDown()
        runtime.context.dynamicOverlay.awaitAllRefreshes()

        assertEquals(1, client.sovereigntyRequests.get())
        assertEquals("Remote Alliance", runtime.context.dynamicOverlay.currentAlliance())
        session.close()
    }

    @Test
    fun `stale LKG survives background failure without touching cache`() = withRuntime { runtime ->
        val stale = cachedSnapshot("Stale Alliance")
        val savedAt = NOW.minus(Duration.ofHours(2))
        runtime.saveCache(stale, savedAt)
        val original = Files.readString(runtime.cachePath)
        val client = ControlledPublicEsiClient(RemoteMode.FAILURE)
        val session = runtime.start(client)

        assertEquals("Stale Alliance", runtime.context.dynamicOverlay.currentAlliance())
        assertTrue(client.started.await(1, TimeUnit.SECONDS))
        client.release.countDown()
        runtime.context.dynamicOverlay.awaitRefresh()

        assertEquals("Stale Alliance", runtime.context.dynamicOverlay.currentAlliance())
        assertEquals(original, Files.readString(runtime.cachePath))
        assertEquals(savedAt, Files.getLastModifiedTime(runtime.cachePath).toInstant())
        assertTrue(runtime.context.events.any { it.contains("retaining current state") })
        session.close()
    }

    @Test
    fun `missing cache starts empty then background success publishes and persists data`() = withRuntime { runtime ->
        val client = ControlledPublicEsiClient(RemoteMode.SUCCESS)
        val session = runtime.start(client)

        assertTrue(runtime.context.dynamicOverlay.currentSnapshot().entries.isEmpty())
        assertTrue(client.started.await(1, TimeUnit.SECONDS))
        client.release.countDown()
        runtime.context.dynamicOverlay.awaitRefresh()

        assertEquals("Remote Alliance", runtime.context.dynamicOverlay.currentAlliance())
        assertEquals("Remote Alliance", runtime.loadCache().records.single().allianceName)
        session.close()
    }

    @Test
    fun `missing cache and network failure keep an empty recoverable Pack`() = withRuntime { runtime ->
        val client = ControlledPublicEsiClient(RemoteMode.FAILURE)
        val session = runtime.start(client)

        assertTrue(runtime.context.dynamicOverlay.currentSnapshot().entries.isEmpty())
        assertTrue(client.started.await(1, TimeUnit.SECONDS))
        client.release.countDown()
        runtime.context.dynamicOverlay.awaitRefresh()

        assertTrue(runtime.context.dynamicOverlay.currentSnapshot().entries.isEmpty())
        assertFalse(Files.exists(runtime.cachePath))
        assertTrue(runtime.context.dynamicOverlay.active.get())
        session.close()
    }

    @Test
    fun `corrupt cache is never published and successful refresh replaces it`() = withRuntime { runtime ->
        Files.createDirectories(runtime.cachePath.parent)
        Files.writeString(runtime.cachePath, "{corrupt}")
        val client = ControlledPublicEsiClient(RemoteMode.SUCCESS)
        val session = runtime.start(client)

        assertTrue(runtime.context.dynamicOverlay.currentSnapshot().entries.isEmpty())
        assertTrue(client.started.await(1, TimeUnit.SECONDS))
        client.release.countDown()
        runtime.context.dynamicOverlay.awaitRefresh()

        assertEquals("Remote Alliance", runtime.context.dynamicOverlay.currentAlliance())
        assertEquals("Remote Alliance", runtime.loadCache().records.single().allianceName)
        assertTrue(runtime.context.events.any { it.contains("Ignoring unusable") })
        session.close()
    }

    @Test
    fun `closing during refresh cancels HTTP and prevents cache or state publication`() = withRuntime { runtime ->
        val client = ControlledPublicEsiClient(RemoteMode.SUCCESS)
        val session = runtime.start(client)
        assertTrue(client.started.await(1, TimeUnit.SECONDS))

        session.close()
        runtime.context.dynamicOverlay.awaitRefresh()

        assertTrue(client.closed.get())
        assertFalse(runtime.context.dynamicOverlay.active.get())
        assertFalse(Files.exists(runtime.cachePath))
        assertEquals(0, runtime.context.systemInfo.refreshes.get())
        assertTrue(runtime.temporaryCacheFiles().isEmpty())
    }

    @Test
    fun `disabling stale refresh cannot overwrite cache or reactivate providers`() = withRuntime { runtime ->
        val stale = cachedSnapshot("Stale Alliance")
        runtime.saveCache(stale, NOW.minus(Duration.ofHours(2)))
        val original = Files.readString(runtime.cachePath)
        val client = ControlledPublicEsiClient(RemoteMode.SUCCESS)
        val session = runtime.start(client)
        assertTrue(client.started.await(1, TimeUnit.SECONDS))

        session.close()
        client.release.countDown()
        runtime.context.dynamicOverlay.awaitRefresh()

        assertFalse(runtime.context.dynamicOverlay.active.get())
        assertFalse(runtime.context.systemInfo.active.get())
        assertEquals(original, Files.readString(runtime.cachePath))
        assertEquals(0, runtime.context.systemInfo.refreshes.get())
        assertTrue(runtime.temporaryCacheFiles().isEmpty())
    }

    private class TestRuntime(private val root: Path) {
        val context = RecordingContext(root)
        val cachePath: Path = context.storage.cachePath(SovereigntyRuntimeComposition.PUBLIC_ESI_LKG_CACHE_PATH)

        fun start(client: ControlledPublicEsiClient) = SovereigntyFeaturePack(
            SovereigntyRuntimeComposition(
                dataSourceMode = SovereigntyDataSourceMode.PUBLIC_ESI,
                publicEsiClientFactory = { client },
                clock = FIXED_CLOCK,
            ),
        ).start(context)

        fun saveCache(snapshot: SovereigntySnapshot, savedAt: Instant) {
            assertEquals(SovereigntyCacheSaveResult.Saved, FileSovereigntySnapshotCache(cachePath).save(snapshot))
            Files.setLastModifiedTime(cachePath, FileTime.from(savedAt))
        }

        fun loadCache(): SovereigntySnapshot =
            assertIs<SovereigntyCacheLoadResult.Hit>(FileSovereigntySnapshotCache(cachePath).load()).snapshot

        fun temporaryCacheFiles(): List<Path> {
            val directory = cachePath.parent
            if (!Files.isDirectory(directory)) return emptyList()
            return Files.list(directory).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".tmp") }.toList()
            }
        }
    }

    private class RecordingContext(root: Path) : FeaturePackContext {
        val storage = TestStorage(root)
        val dynamicOverlay = RecordingDynamicOverlayCapability()
        val systemInfo = RecordingSystemInfoRegistry()
        val events = CopyOnWriteArrayList<String>()

        override fun hostInfo() = FeaturePackHostInfo(
            CoreVersion(0, 3, 0),
            FeatureApiVersions.current(),
            HostPlatform("windows", "x64"),
        )

        override fun storage(): PackStorage = storage

        override fun logger(): FeaturePackLogger = object : FeaturePackLogger {
            override fun log(level: FeaturePackLogLevel, message: String, cause: Throwable?) {
                events += "${level.name}:$message"
            }
        }

        override fun overlays(): OverlayRegistry = OverlayRegistry {
            error("PUBLIC_ESI lifecycle must use Dynamic Overlay capability")
        }

        override fun systemInfo(): SystemInfoRegistry = systemInfo

        override fun capabilities(): FeatureCapabilityLookup = object : FeatureCapabilityLookup {
            override fun <T : FeatureCapability> find(key: FeatureCapabilityKey<T>): T? =
                if (key == StandardFeatureCapabilities.DYNAMIC_OVERLAY) key.type.cast(dynamicOverlay) else null
        }
    }

    private class RecordingDynamicOverlayCapability : DynamicOverlayCapability {
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "test-sovereignty-refresh").apply { isDaemon = true }
        }
        private val provider = AtomicReference<OverlayProvider>()
        private val latest = AtomicReference(OverlaySnapshot(emptyList()))
        private val futures = CopyOnWriteArrayList<Future<*>>()
        private val completions = CopyOnWriteArrayList<CountDownLatch>()
        val active = AtomicBoolean(false)
        val refreshRequests = AtomicInteger()

        override fun register(provider: OverlayProvider): DynamicOverlayRegistration {
            this.provider.set(provider)
            latest.set(provider.snapshot())
            active.set(true)
            return object : DynamicOverlayRegistration {
                override fun requestRefresh() = submitRefresh()

                override fun close() {
                    if (!active.compareAndSet(true, false)) return
                    futures.forEach { it.cancel(true) }
                    executor.shutdownNow()
                    executor.awaitTermination(1, TimeUnit.SECONDS)
                }
            }
        }

        fun requestAgain() = submitRefresh()

        fun currentSnapshot(): OverlaySnapshot = latest.get()

        fun currentAlliance(): String? = latest.get().entries.singleOrNull()?.title

        fun awaitRefresh() {
            assertTrue(completions.first().await(2, TimeUnit.SECONDS), "background refresh did not finish")
        }

        fun awaitAllRefreshes() {
            completions.forEach { latch ->
                assertTrue(latch.await(2, TimeUnit.SECONDS), "background refresh did not finish")
            }
        }

        private fun submitRefresh() {
            refreshRequests.incrementAndGet()
            val completion = CountDownLatch(1)
            completions += completion
            futures += executor.submit {
                try {
                    val refreshed = provider.get().snapshot()
                    if (active.get()) latest.set(refreshed)
                } finally {
                    completion.countDown()
                }
            }
        }
    }

    private class RecordingSystemInfoRegistry : SystemInfoRegistry {
        private val provider = AtomicReference<SystemInfoProvider>()
        val active = AtomicBoolean(false)
        val refreshes = AtomicInteger()

        override fun register(provider: SystemInfoProvider): SystemInfoRegistration {
            this.provider.set(provider)
            active.set(true)
            return object : SystemInfoRegistration {
                override fun refresh() {
                    if (active.get()) refreshes.incrementAndGet()
                }

                override fun close() {
                    active.set(false)
                }
            }
        }

        fun currentAlliance(): String? = provider.get().provide(30_004_759)
            .sections.singleOrNull()?.fields?.firstOrNull()?.value
    }

    private class ControlledPublicEsiClient(
        private val mode: RemoteMode,
    ) : PublicEsiClient {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val sovereigntyRequests = AtomicInteger()
        val namesRequests = AtomicInteger()

        override fun fetchSovereigntySystems(): PublicEsiPayloadResult {
            sovereigntyRequests.incrementAndGet()
            started.countDown()
            try {
                release.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return PublicEsiPayloadResult.Unavailable("interrupted")
            }
            if (closed.get()) return PublicEsiPayloadResult.Unavailable("closed")
            return when (mode) {
                RemoteMode.SUCCESS -> PublicEsiPayloadResult.Success(SOVEREIGNTY_PAYLOAD)
                RemoteMode.FAILURE -> PublicEsiPayloadResult.Unavailable("offline")
            }
        }

        override fun resolveNames(ids: List<Int>): PublicEsiPayloadResult {
            namesRequests.incrementAndGet()
            return PublicEsiPayloadResult.Success(NAME_PAYLOAD)
        }

        override fun close() {
            closed.set(true)
            release.countDown()
        }
    }

    private class TestStorage(private val root: Path) : PackStorage {
        override fun dataPath(relativePath: PackRelativePath): Path = root.resolve("data").resolve(relativePath.toPath())
        override fun configPath(relativePath: PackRelativePath): Path = root.resolve("config").resolve(relativePath.toPath())
        override fun cachePath(relativePath: PackRelativePath): Path = root.resolve("cache").resolve(relativePath.toPath())
    }

    private enum class RemoteMode { SUCCESS, FAILURE }

    private inline fun withRuntime(block: (TestRuntime) -> Unit) {
        val root = createTempDirectory("sovereignty-refresh-lifecycle-")
        try {
            block(TestRuntime(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun cachedSnapshot(allianceName: String) = SovereigntySnapshot(
        listOf(SovereigntyRecord(30_004_759, allianceName, null, PUBLIC_ESI_CLAIMED_STATUS, 99_000_001)),
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-26T12:00:00Z")
        val FIXED_CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
        const val SOVEREIGNTY_PAYLOAD =
            """{"solar_systems":[{"solar_system_id":30004759,"claim":{"alliance":{"alliance_id":99000001}}}]}"""
        const val NAME_PAYLOAD = """[{"id":99000001,"name":"Remote Alliance","category":"alliance"}]"""
    }
}
