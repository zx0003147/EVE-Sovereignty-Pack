package dev.evestaticmapplanner.sovereignty

import dev.evestaticmapplanner.feature.api.CoreVersion
import dev.evestaticmapplanner.feature.api.FeatureApiVersions
import dev.evestaticmapplanner.feature.api.FeaturePackContext
import dev.evestaticmapplanner.feature.api.FeaturePackEntrypoint
import dev.evestaticmapplanner.feature.api.FeaturePackHostInfo
import dev.evestaticmapplanner.feature.api.FeaturePackLogLevel
import dev.evestaticmapplanner.feature.api.FeaturePackLogger
import dev.evestaticmapplanner.feature.api.HostPlatform
import dev.evestaticmapplanner.feature.api.OverlayProvider
import dev.evestaticmapplanner.feature.api.OverlayRegistration
import dev.evestaticmapplanner.feature.api.OverlayRegistry
import dev.evestaticmapplanner.feature.api.PackRelativePath
import dev.evestaticmapplanner.feature.api.PackStorage
import dev.evestaticmapplanner.feature.api.SystemInfoProvider
import dev.evestaticmapplanner.feature.api.SystemInfoRegistration
import dev.evestaticmapplanner.feature.api.SystemInfoRegistry
import java.nio.file.Path
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SovereigntyFeaturePackTest {
    @Test
    fun `descriptor is valid and matches the external Pack identity`() {
        val descriptor = SovereigntyFeaturePack().descriptor()

        assertEquals("sovereignty.pack", descriptor.packId.value)
        assertEquals("Sovereignty Pack", descriptor.displayName)
        assertEquals("0.1.0", descriptor.packVersion.value)
        assertEquals("EVE Static Map Planner", descriptor.publisher)
    }

    @Test
    fun `production entrypoint explicitly selects public ESI without loading it`() {
        val pack = SovereigntyFeaturePack()

        assertEquals(SovereigntyDataSourceMode.PUBLIC_ESI, pack.dataSourceMode)
    }

    @Test
    fun `real entrypoint remains ServiceLoader discoverable and constructible`() {
        val entrypoint = ServiceLoader.load(FeaturePackEntrypoint::class.java)
            .filterIsInstance<SovereigntyFeaturePack>()
            .single()

        assertEquals(SovereigntyDataSourceMode.PUBLIC_ESI, entrypoint.dataSourceMode)
    }

    @Test
    fun `start registers both providers and close unregisters both`() {
        val context = RecordingContext()
        val session = embeddedFeaturePack().start(context)

        assertTrue(context.overlayRegistry.active)
        assertTrue(context.systemInfoRegistry.active)
        assertEquals("Sovereignty", context.overlayRegistry.provider?.layers()?.single()?.name)
        assertEquals("Sovereignty", context.systemInfoRegistry.provider?.provide(30_004_759)?.sections?.single()?.title)

        session.close()
        session.close()

        assertFalse(context.overlayRegistry.active)
        assertFalse(context.systemInfoRegistry.active)
        assertEquals(listOf("INFO:Sovereignty Pack started", "INFO:Sovereignty Pack stopped"), context.events)
    }

    @Test
    fun `one public ESI activation shares one loaded snapshot across consumers`() {
        val client = RecordingPublicEsiClient()
        val context = RecordingContext()
        val pack = SovereigntyFeaturePack(
            SovereigntyRuntimeComposition(
                dataSourceMode = SovereigntyDataSourceMode.PUBLIC_ESI,
                publicEsiClientFactory = { client },
            ),
        )

        val session = pack.start(context)
        repeat(3) {
            context.overlayRegistry.provider?.snapshot()
            context.systemInfoRegistry.provider?.provide(30_004_759)
        }

        assertEquals(1, client.sovereigntyRequestCount)
        assertEquals(1, client.namesRequestCount)
        assertEquals("Remote Alliance", context.overlayRegistry.provider?.snapshot()?.entries?.single()?.title)
        assertEquals(
            "Remote Alliance",
            context.systemInfoRegistry.provider?.provide(30_004_759)?.sections?.single()?.fields?.first()?.value,
        )
        session.close()
    }

    private fun embeddedFeaturePack() = SovereigntyFeaturePack(
        SovereigntyRuntimeComposition(SovereigntyDataSourceMode.EMBEDDED),
    )

    private class RecordingPublicEsiClient : PublicEsiClient {
        var sovereigntyRequestCount = 0
        var namesRequestCount = 0

        override fun fetchSovereigntySystems(): PublicEsiPayloadResult.Success {
            sovereigntyRequestCount += 1
            return PublicEsiPayloadResult.Success(
                """{"solar_systems":[{"solar_system_id":30004759,"claim":{"alliance":{"alliance_id":99000001}}}]}""",
            )
        }

        override fun resolveNames(ids: List<Int>): PublicEsiPayloadResult.Success {
            namesRequestCount += 1
            assertEquals(listOf(99_000_001), ids)
            return PublicEsiPayloadResult.Success(
                """[{"id":99000001,"name":"Remote Alliance","category":"alliance"}]""",
            )
        }
    }

    private class RecordingContext : FeaturePackContext {
        val overlayRegistry = RecordingOverlayRegistry()
        val systemInfoRegistry = RecordingSystemInfoRegistry()
        val events = mutableListOf<String>()

        override fun hostInfo() = FeaturePackHostInfo(
            CoreVersion(0, 3, 0),
            FeatureApiVersions.current(),
            HostPlatform("windows", "x64"),
        )

        override fun storage(): PackStorage = object : PackStorage {
            override fun dataPath(relativePath: PackRelativePath): Path = Path.of("data").resolve(relativePath.toPath())
            override fun configPath(relativePath: PackRelativePath): Path = Path.of("config").resolve(relativePath.toPath())
            override fun cachePath(relativePath: PackRelativePath): Path = Path.of("cache").resolve(relativePath.toPath())
        }

        override fun logger(): FeaturePackLogger = object : FeaturePackLogger {
            override fun log(level: FeaturePackLogLevel, message: String, cause: Throwable?) {
                events += "${level.name}:$message"
            }
        }

        override fun overlays(): OverlayRegistry = overlayRegistry

        override fun systemInfo(): SystemInfoRegistry = systemInfoRegistry
    }

    private class RecordingOverlayRegistry : OverlayRegistry {
        var provider: OverlayProvider? = null
        var active = false

        override fun register(provider: OverlayProvider): OverlayRegistration {
            this.provider = provider
            active = true
            return object : OverlayRegistration {
                override fun close() {
                    active = false
                }
            }
        }
    }

    private class RecordingSystemInfoRegistry : SystemInfoRegistry {
        var provider: SystemInfoProvider? = null
        var active = false

        override fun register(provider: SystemInfoProvider): SystemInfoRegistration {
            this.provider = provider
            active = true
            return object : SystemInfoRegistration {
                override fun refresh() = Unit
                override fun close() {
                    active = false
                }
            }
        }
    }
}
