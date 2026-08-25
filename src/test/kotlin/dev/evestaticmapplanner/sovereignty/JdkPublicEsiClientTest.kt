package dev.evestaticmapplanner.sovereignty

import java.io.IOException
import java.net.URI
import java.net.http.HttpRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

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
}
