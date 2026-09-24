package dev.evestaticmapplanner.sovereignty

import dev.evestaticmapplanner.feature.api.CoreVersion
import dev.evestaticmapplanner.feature.api.AllianceDirectoryCapability
import dev.evestaticmapplanner.feature.api.AllianceDirectoryProvider
import dev.evestaticmapplanner.feature.api.AllianceDirectoryRegistration
import dev.evestaticmapplanner.feature.api.FeatureApiVersions
import dev.evestaticmapplanner.feature.api.FeatureCapability
import dev.evestaticmapplanner.feature.api.FeatureCapabilityKey
import dev.evestaticmapplanner.feature.api.FeatureCapabilityLookup
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
import dev.evestaticmapplanner.feature.api.StandardFeatureCapabilities
import dev.evestaticmapplanner.feature.api.SovereigntyCapability
import dev.evestaticmapplanner.feature.api.SovereigntyProvider
import dev.evestaticmapplanner.feature.api.SovereigntyRegistration
import java.nio.file.Path
import java.util.ServiceLoader
import java.util.jar.JarFile
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
        assertEquals(PackBuildMetadata.PACK_VERSION, descriptor.packVersion.value)
        assertEquals("EVE Static Map Planner", descriptor.publisher)

        val canonicalPackJar = Path.of(checkNotNull(System.getProperty("canonical.pack.jar"))).toFile()
        val manifestPackVersion = JarFile(canonicalPackJar).use { jar ->
            checkNotNull(jar.manifest).mainAttributes.getValue("EVE-Feature-Pack-Version")
        }
        assertEquals(PackBuildMetadata.PACK_VERSION, manifestPackVersion)
        assertEquals(manifestPackVersion, descriptor.packVersion.value)
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
    fun `one public ESI activation shares one fresh cached snapshot across consumers`() {
        val client = RecordingPublicEsiClient()
        val context = RecordingContext()
        val cached = SovereigntySnapshot(
            listOf(SovereigntyRecord(30_004_759, "Cached Alliance", null, PUBLIC_ESI_CLAIMED_STATUS, 99_000_001)),
        )
        val pack = SovereigntyFeaturePack(
            SovereigntyRuntimeComposition(
                dataSourceMode = SovereigntyDataSourceMode.PUBLIC_ESI,
                publicEsiClientFactory = { client },
                cacheFactory = {
                    object : SovereigntySnapshotCache {
                        override fun load() = SovereigntyCacheLoadResult.Hit(cached, java.time.Instant.now())
                        override fun save(snapshot: SovereigntySnapshot) = SovereigntyCacheSaveResult.Saved
                    }
                },
            ),
        )

        val session = pack.start(context)
        repeat(3) {
            context.overlayRegistry.provider?.snapshot()
            context.systemInfoRegistry.provider?.provide(30_004_759)
        }

        assertEquals(0, client.sovereigntyRequestCount)
        assertEquals(0, client.namesRequestCount)
        assertEquals("Cached Alliance", context.overlayRegistry.provider?.snapshot()?.entries?.single()?.title)
        assertEquals(
            "Cached Alliance",
            context.systemInfoRegistry.provider?.provide(30_004_759)?.sections?.single()?.fields?.first()?.value,
        )
        session.close()
    }

    @Test
    fun `Feature API 2_4 Host receives and closes Alliance Directory provider`() {
        val directory = RecordingAllianceDirectoryCapability()
        val context = RecordingContext(directory)

        val session = embeddedFeaturePack().start(context)

        assertTrue(directory.active)
        val alliances = checkNotNull(directory.provider).snapshot().alliances
        assertEquals(setOf(1_354_830_081L, 99_003_581L), alliances.map { it.allianceId }.toSet())
        assertEquals("Goonswarm Federation", alliances.single { it.allianceId == 1_354_830_081L }.name)

        session.close()
        assertFalse(directory.active)
    }

    @Test
    fun `Feature API 2_5 Host receives typed sovereignty provider and shutdown stops publication`() {
        val sovereignty = RecordingSovereigntyCapability()
        val context = RecordingContext(sovereignty = sovereignty)

        val session = embeddedFeaturePack().start(context)

        assertTrue(sovereignty.active)
        val ownership = checkNotNull(sovereignty.provider).snapshot().systems
            .single { it.systemId == 30_004_759 }
        assertEquals(1_354_830_081L, ownership.allianceId)
        assertEquals("Goonswarm Federation", ownership.allianceName)

        session.close()
        assertFalse(sovereignty.active)
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

    private class RecordingContext(
        private val allianceDirectory: RecordingAllianceDirectoryCapability? = null,
        private val sovereignty: RecordingSovereigntyCapability? = null,
    ) : FeaturePackContext {
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

        override fun capabilities(): FeatureCapabilityLookup = object : FeatureCapabilityLookup {
            override fun <T : FeatureCapability> find(key: FeatureCapabilityKey<T>): T? =
                when {
                    key == StandardFeatureCapabilities.ALLIANCE_DIRECTORY && allianceDirectory != null ->
                        key.type.cast(allianceDirectory)
                    key == StandardFeatureCapabilities.SOVEREIGNTY && sovereignty != null ->
                        key.type.cast(sovereignty)
                    else -> null
                }
        }
    }

    private class RecordingSovereigntyCapability : SovereigntyCapability {
        var provider: SovereigntyProvider? = null
        var active = false
        var refreshes = 0

        override fun register(provider: SovereigntyProvider): SovereigntyRegistration {
            this.provider = provider
            active = true
            return object : SovereigntyRegistration {
                override fun requestRefresh() {
                    if (active) refreshes += 1
                }

                override fun close() {
                    active = false
                }
            }
        }
    }

    private class RecordingAllianceDirectoryCapability : AllianceDirectoryCapability {
        var provider: AllianceDirectoryProvider? = null
        var active = false
        var refreshes = 0

        override fun register(provider: AllianceDirectoryProvider): AllianceDirectoryRegistration {
            this.provider = provider
            active = true
            return object : AllianceDirectoryRegistration {
                override fun requestRefresh() {
                    if (active) refreshes += 1
                }

                override fun close() {
                    active = false
                }
            }
        }
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
