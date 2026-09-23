package dev.evestaticmapplanner.sovereignty

import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AllianceMetadataCacheTest {
    @Test
    fun `cache round trip preserves last-good metadata and validators`() = withCache { cache, _ ->
        val expected = metadata()

        assertEquals(AllianceMetadataCacheSaveResult.Saved, cache.save(listOf(expected)))
        val loaded = assertIs<AllianceMetadataCacheLoadResult.Hit>(cache.load())

        assertEquals(listOf(expected), loaded.records)
    }

    @Test
    fun `corrupt cache is unusable without throwing`() = withCache { cache, path ->
        Files.createDirectories(path.parent)
        Files.writeString(path, "{corrupt}")

        assertIs<AllianceMetadataCacheLoadResult.Unusable>(cache.load())
    }

    @Test
    fun `save replaces cache and leaves no temporary file`() = withCache { cache, path ->
        assertEquals(AllianceMetadataCacheSaveResult.Saved, cache.save(listOf(metadata(ticker = "OLD"))))
        assertEquals(AllianceMetadataCacheSaveResult.Saved, cache.save(listOf(metadata(ticker = "NEW"))))

        val loaded = assertIs<AllianceMetadataCacheLoadResult.Hit>(cache.load())
        assertEquals("NEW", loaded.records.single().ticker)
        val temporary = Files.list(path.parent).use { files ->
            files.filter { it.fileName.toString().endsWith(".tmp") }.toList()
        }
        assertTrue(temporary.isEmpty())
    }

    private inline fun withCache(block: (FileAllianceMetadataCache, java.nio.file.Path) -> Unit) {
        val root = createTempDirectory("alliance-metadata-cache-")
        try {
            val path = root.resolve("cache/alliance-metadata-lkg.json")
            block(FileAllianceMetadataCache(path), path)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun metadata(ticker: String = "ALLY") = AllianceMetadata(
        allianceId = 99_000_001,
        name = "Alliance",
        ticker = ticker,
        observedSovereigntyName = "Observed Alliance",
        etag = "\"metadata-v1\"",
        lastModified = "Wed, 23 Sep 2026 10:00:00 GMT",
        cacheControl = "public, max-age=3600",
        freshUntil = Instant.parse("2026-09-23T13:00:00Z"),
        lastSuccessfulAt = Instant.parse("2026-09-23T12:00:00Z"),
    )
}
