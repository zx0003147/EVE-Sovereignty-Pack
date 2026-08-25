package dev.evestaticmapplanner.sovereignty

import dev.evestaticmapplanner.feature.api.CoreVersion
import dev.evestaticmapplanner.feature.api.FeatureApiVersions
import dev.evestaticmapplanner.feature.api.FeaturePackContext
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
    fun `start registers both providers and close unregisters both`() {
        val context = RecordingContext()
        val session = SovereigntyFeaturePack().start(context)

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
