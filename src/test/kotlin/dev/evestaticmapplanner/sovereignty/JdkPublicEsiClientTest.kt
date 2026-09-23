package dev.evestaticmapplanner.sovereignty

import java.io.IOException
import java.net.URI
import java.net.http.HttpRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class JdkPublicEsiClientTest {
    @Test
    fun `sovereignty request uses public route and pinned compatibility date without authorization`() {
        var capturedRequest: HttpRequest? = null
        val client = JdkPublicEsiClient(
            baseUri = URI.create("https://example.test/"),
            sendRequest = { request ->
                capturedRequest = request
                EsiHttpResponse(200, "{\"solar_systems\":[]}")
            },
        )

        assertIs<PublicEsiPayloadResult.Success>(client.fetchSovereigntySystems())

        val request = checkNotNull(capturedRequest)
        assertEquals("GET", request.method())
        assertEquals(
            "https://example.test/sovereignty/systems?datasource=tranquility",
            request.uri().toString(),
        )
        assertEquals("2026-05-19", request.headers().firstValue("X-Compatibility-Date").orElse(null))
        assertEquals(
            "EVE-Sovereignty-Pack/${PackBuildMetadata.PACK_VERSION}",
            request.headers().firstValue("User-Agent").orElse(null),
        )
        assertFalse(request.headers().firstValue("Authorization").isPresent)
    }

    @Test
    fun `I-O failure becomes unavailable result`() {
        val client = JdkPublicEsiClient(
            sendRequest = { throw IOException("Connection refused") },
        )

        val result = assertIs<PublicEsiPayloadResult.Unavailable>(client.fetchSovereigntySystems())

        assertEquals(
            "Public ESI sovereignty systems is unavailable: Connection refused",
            result.reason,
        )
    }

    @Test
    fun `service unavailable HTTP status becomes unavailable result`() {
        val client = JdkPublicEsiClient(
            sendRequest = { EsiHttpResponse(503, "Service unavailable") },
        )

        val result = assertIs<PublicEsiPayloadResult.Unavailable>(client.fetchSovereigntySystems())

        assertEquals("Public ESI sovereignty systems returned HTTP 503", result.reason)
    }

    @Test
    fun `alliance metadata request is public conditional and exposes cache headers`() {
        var capturedRequest: HttpRequest? = null
        val client = JdkPublicEsiClient(
            baseUri = URI.create("https://example.test/"),
            sendRequest = { request ->
                capturedRequest = request
                EsiHttpResponse(
                    200,
                    """{"name":"Alliance","ticker":"ALLY"}""",
                    mapOf(
                        "cache-control" to listOf("public, max-age=3600"),
                        "etag" to listOf("\"metadata-v1\""),
                        "last-modified" to listOf("Wed, 23 Sep 2026 10:00:00 GMT"),
                    ),
                )
            },
        )

        val result = assertIs<PublicEsiAllianceMetadataResult.Updated>(
            client.fetchAllianceMetadata(
                99_000_001,
                AllianceMetadataValidators("\"old\"", "Tue, 22 Sep 2026 10:00:00 GMT"),
            ),
        )

        val request = checkNotNull(capturedRequest)
        assertEquals("GET", request.method())
        assertEquals(
            "https://example.test/alliances/99000001?datasource=tranquility",
            request.uri().toString(),
        )
        assertEquals("2026-05-19", request.headers().firstValue("X-Compatibility-Date").orElse(null))
        assertEquals(
            "EVE-Sovereignty-Pack/${PackBuildMetadata.PACK_VERSION}",
            request.headers().firstValue("User-Agent").orElse(null),
        )
        assertEquals("\"old\"", request.headers().firstValue("If-None-Match").orElse(null))
        assertEquals(
            "Tue, 22 Sep 2026 10:00:00 GMT",
            request.headers().firstValue("If-Modified-Since").orElse(null),
        )
        assertFalse(request.headers().firstValue("Authorization").isPresent)
        assertEquals("public, max-age=3600", result.cacheHeaders.cacheControl)
        assertEquals("\"metadata-v1\"", result.cacheHeaders.etag)
        assertEquals("Wed, 23 Sep 2026 10:00:00 GMT", result.cacheHeaders.lastModified)
    }

    @Test
    fun `alliance metadata 304 preserves conditional response semantics`() {
        val client = JdkPublicEsiClient(
            sendRequest = { EsiHttpResponse(304, "", mapOf("Cache-Control" to listOf("max-age=600"))) },
        )

        val result = assertIs<PublicEsiAllianceMetadataResult.NotModified>(
            client.fetchAllianceMetadata(99_000_001),
        )

        assertEquals("max-age=600", result.cacheHeaders.cacheControl)
        assertNull(result.cacheHeaders.etag)
    }

    @Test
    fun `alliance metadata temporary HTTP status is unavailable`() {
        listOf(408, 420, 429, 500, 503).forEach { status ->
            val client = JdkPublicEsiClient(sendRequest = { EsiHttpResponse(status, "temporary") })
            assertIs<PublicEsiAllianceMetadataResult.Unavailable>(client.fetchAllianceMetadata(99_000_001))
        }
    }
}
