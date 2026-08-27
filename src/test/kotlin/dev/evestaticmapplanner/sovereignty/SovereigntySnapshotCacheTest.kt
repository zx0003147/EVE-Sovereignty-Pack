package dev.evestaticmapplanner.sovereignty

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SovereigntySnapshotCacheTest {
    @Test
    fun `valid snapshot round trips through the filesystem cache`() = withTempDirectory { root ->
        val cache = FileSovereigntySnapshotCache(root.resolve("cache/public-esi-lkg.json"))
        val expected = snapshot(
            SovereigntyRecord(30_004_760, "Alliance \"Two\"", null, PUBLIC_ESI_CLAIMED_STATUS, 99_000_002),
            SovereigntyRecord(30_004_759, "Alliance One", "Corporation \\ One", PUBLIC_ESI_CLAIMED_STATUS, 99_000_001),
        )

        assertEquals(SovereigntyCacheSaveResult.Saved, cache.save(expected))
        val loadedResult = assertIs<SovereigntyCacheLoadResult.Hit>(cache.load())
        val loaded = loadedResult.snapshot

        assertEquals(expected.records.sortedBy(SovereigntyRecord::systemId), loaded.records)
        assertEquals(SovereigntySnapshotMetadata(), loaded.metadata)
        assertEquals(
            Files.getLastModifiedTime(root.resolve("cache/public-esi-lkg.json")).toInstant(),
            loadedResult.savedAt,
        )
    }

    @Test
    fun `missing cache is a miss and does not create its directory`() = withTempDirectory { root ->
        val directory = root.resolve("cache")
        val cache = FileSovereigntySnapshotCache(directory.resolve("public-esi-lkg.json"))

        assertEquals(SovereigntyCacheLoadResult.Miss, cache.load())
        assertFalse(directory.exists())
    }

    @Test
    fun `malformed JSON is unusable without throwing`() = withCacheText("{not-json}") { cache ->
        assertIs<SovereigntyCacheLoadResult.Unusable>(cache.load())
    }

    @Test
    fun `unsupported format version is unusable`() = withCacheText(
        """{"formatVersion":3,"source":"PUBLIC_ESI","records":[]}""",
    ) { cache ->
        val result = assertIs<SovereigntyCacheLoadResult.Unusable>(cache.load())
        assertTrue(result.reason.contains("formatVersion"))
    }

    @Test
    fun `structurally valid but invalid canonical record is unusable`() = withCacheText(
        cacheJson(
            """{"systemId":42,"allianceName":"Alliance","corporationName":null,"sovereigntyStatus":"Claimed"}""",
        ),
    ) { cache ->
        val result = assertIs<SovereigntyCacheLoadResult.Unusable>(cache.load())
        assertTrue(result.reason.contains("invalid systemId 42"))
    }

    @Test
    fun `duplicate system ownership is unusable`() = withCacheText(
        cacheJson(
            validRecordJson("Alliance One"),
            validRecordJson("Alliance Two"),
        ),
    ) { cache ->
        val result = assertIs<SovereigntyCacheLoadResult.Unusable>(cache.load())
        assertTrue(result.reason.contains("duplicate systemId 30004759"))
    }

    @Test
    fun `legacy v1 cache remains readable with explicit missing alliance identity`() = withCacheText(
        cacheJson(validRecordJson("Legacy Alliance")),
    ) { cache ->
        val record = assertIs<SovereigntyCacheLoadResult.Hit>(cache.load()).snapshot.records.single()

        assertEquals("Legacy Alliance", record.allianceName)
        assertEquals(null, record.allianceId)
    }

    @Test
    fun `successful replacement leaves one complete new final cache`() = withTempDirectory { root ->
        val path = root.resolve("cache/public-esi-lkg.json")
        val cache = FileSovereigntySnapshotCache(path)
        assertEquals(SovereigntyCacheSaveResult.Saved, cache.save(snapshot(record("Old Alliance"))))
        val oldSavedAt = Instant.parse("2020-01-01T00:00:00Z")
        Files.setLastModifiedTime(path, FileTime.from(oldSavedAt))

        assertEquals(SovereigntyCacheSaveResult.Saved, cache.save(snapshot(record("New Alliance"))))

        val text = Files.readString(path)
        assertTrue(text.contains("New Alliance"))
        assertFalse(text.contains("Old Alliance"))
        assertEquals("New Alliance", assertIs<SovereigntyCacheLoadResult.Hit>(cache.load())
            .snapshot.records.single().allianceName)
        assertTrue(Files.getLastModifiedTime(path).toInstant().isAfter(oldSavedAt))
        Files.list(path.parent).use { paths ->
            assertEquals(listOf(path), paths.toList())
        }
    }

    @Test
    fun `loading cache does not change its modification time`() = withTempDirectory { root ->
        val path = root.resolve("cache/public-esi-lkg.json")
        val cache = FileSovereigntySnapshotCache(path)
        assertEquals(SovereigntyCacheSaveResult.Saved, cache.save(snapshot(record("Cached Alliance"))))
        val savedAt = Instant.parse("2024-04-05T06:07:08Z")
        Files.setLastModifiedTime(path, FileTime.from(savedAt))

        assertIs<SovereigntyCacheLoadResult.Hit>(cache.load())

        assertEquals(savedAt, Files.getLastModifiedTime(path).toInstant())
    }

    private fun withCacheText(text: String, block: (FileSovereigntySnapshotCache) -> Unit) =
        withTempDirectory { root ->
            val path = root.resolve("cache/public-esi-lkg.json")
            Files.createDirectories(path.parent)
            Files.writeString(path, text)
            block(FileSovereigntySnapshotCache(path))
        }

    private inline fun withTempDirectory(block: (Path) -> Unit) {
        val root = createTempDirectory("sv-3c-2-cache-")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun snapshot(vararg records: SovereigntyRecord) = SovereigntySnapshot(records.asList())

    private fun record(allianceName: String) =
        SovereigntyRecord(30_004_759, allianceName, null, PUBLIC_ESI_CLAIMED_STATUS, 99_000_001)

    private fun validRecordJson(allianceName: String) =
        """{"systemId":30004759,"allianceName":"$allianceName","corporationName":null,"sovereigntyStatus":"Claimed"}"""

    private fun cacheJson(vararg records: String) =
        """{"formatVersion":1,"source":"PUBLIC_ESI","records":[${records.joinToString()}]}"""
}
