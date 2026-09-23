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
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyFeatureApiRuntimeSmokeTest {
    @Test
    fun `entrypoint starts when Alliance Directory API classes are absent like Feature API 2_0 to 2_3`() {
        val root = createTempDirectory("sovereignty-legacy-api-smoke-")
        try {
            val cache = root.resolve("cache/public-esi-lkg.json")
            cache.parent.createDirectories()
            Files.writeString(cache, VALID_PUBLIC_ESI_CACHE)
            val parent = MissingAllianceDirectoryApiClassLoader(FeaturePackEntrypoint::class.java.classLoader)
            val packJar = Path.of(checkNotNull(System.getProperty("canonical.pack.jar")))
            URLClassLoader(arrayOf(packJar.toUri().toURL()), parent).use { classLoader ->
                val entrypoint = ServiceLoader.load(FeaturePackEntrypoint::class.java, classLoader).single()
                val context = LegacyContext(root)

                val session = entrypoint.start(context)

                assertTrue(context.overlay.active)
                assertTrue(context.systemInfo.active)
                assertEquals("Cached Alliance", context.overlay.provider?.snapshot()?.entries?.single()?.title)
                assertEquals(
                    "Cached Alliance",
                    context.systemInfo.provider?.provide(30_004_759)?.sections?.single()?.fields?.first()?.value,
                )
                assertTrue(context.events.any { it.contains("predates Alliance Directory") })
                session.close()
                assertFalse(context.overlay.active)
                assertFalse(context.systemInfo.active)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private class MissingAllianceDirectoryApiClassLoader(
        private val delegate: ClassLoader,
    ) : ClassLoader(null) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith("dev.evestaticmapplanner.sovereignty.")) {
                throw ClassNotFoundException("Pack implementation must load from the isolated Pack JAR: $name")
            }
            if (name.startsWith("dev.evestaticmapplanner.feature.api.AllianceDirectory") ||
                name == "dev.evestaticmapplanner.feature.api.AllianceReferenceSnapshot"
            ) {
                throw ClassNotFoundException("Simulated Feature API 2.0-2.3 classpath: $name")
            }
            return delegate.loadClass(name)
        }
    }

    private class LegacyContext(private val root: Path) : FeaturePackContext {
        val overlay = RecordingOverlayRegistry()
        val systemInfo = RecordingSystemInfoRegistry()
        val events = mutableListOf<String>()

        override fun hostInfo() = FeaturePackHostInfo(
            CoreVersion(1, 0, 0),
            FeatureApiVersions.current(),
            HostPlatform("windows", "x64"),
        )

        override fun storage(): PackStorage = object : PackStorage {
            override fun dataPath(relativePath: PackRelativePath) = root.resolve("data").resolve(relativePath.toPath())
            override fun configPath(relativePath: PackRelativePath) =
                root.resolve("config").resolve(relativePath.toPath())
            override fun cachePath(relativePath: PackRelativePath) =
                root.resolve("cache").resolve(relativePath.toPath())
        }

        override fun logger(): FeaturePackLogger = object : FeaturePackLogger {
            override fun log(level: FeaturePackLogLevel, message: String, cause: Throwable?) {
                events += "${level.name}:$message"
            }
        }

        override fun overlays(): OverlayRegistry = overlay
        override fun systemInfo(): SystemInfoRegistry = systemInfo
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

    private companion object {
        val VALID_PUBLIC_ESI_CACHE = """
            {
              "formatVersion": 2,
              "source": "PUBLIC_ESI",
              "records": [
                {"systemId": 30004759, "allianceId": 99000001, "allianceName": "Cached Alliance", "corporationName": null, "sovereigntyStatus": "Claimed"}
              ]
            }
        """.trimIndent()
    }
}
