package dev.evestaticmapplanner.sovereignty

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal sealed interface PublicEsiPayloadResult {
    data class Success(val payload: String) : PublicEsiPayloadResult

    data class Unavailable(val reason: String) : PublicEsiPayloadResult

    data class Invalid(val reason: String) : PublicEsiPayloadResult
}

/** Public ESI operations required by sovereignty loading, without exposing HTTP to the source. */
internal interface PublicEsiClient : AutoCloseable {
    fun fetchSovereigntySystems(): PublicEsiPayloadResult

    fun resolveNames(ids: List<Int>): PublicEsiPayloadResult

    override fun close() = Unit
}

internal data class EsiHttpResponse(
    val statusCode: Int,
    val body: String,
)

/** JDK-only HTTP implementation for public, unauthenticated ESI routes. */
internal class JdkPublicEsiClient(
    private val baseUri: URI = URI.create("https://esi.evetech.net/"),
    sendRequest: ((HttpRequest) -> EsiHttpResponse)? = null,
) : PublicEsiClient {
    private val httpClient = if (sendRequest == null) defaultHttpClient() else null
    private val sendRequest = sendRequest ?: { request: HttpRequest ->
        val response = checkNotNull(httpClient).send(
            request,
            HttpResponse.BodyHandlers.ofString(Charsets.UTF_8),
        )
        EsiHttpResponse(response.statusCode(), response.body())
    }

    override fun fetchSovereigntySystems(): PublicEsiPayloadResult = execute(
        operation = "Public ESI sovereignty systems",
        request = requestBuilder("sovereignty/systems?datasource=tranquility")
            .GET()
            .build(),
    )

    override fun resolveNames(ids: List<Int>): PublicEsiPayloadResult {
        require(ids.isNotEmpty()) { "At least one owner ID is required for ESI name resolution" }
        val body = ids.joinToString(prefix = "[", postfix = "]")
        return execute(
            operation = "Public ESI universe names",
            request = requestBuilder("universe/names?datasource=tranquility")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
        )
    }

    private fun requestBuilder(relativePath: String): HttpRequest.Builder = HttpRequest.newBuilder()
        .uri(baseUri.resolve(relativePath))
        .timeout(REQUEST_TIMEOUT)
        .header("Accept", "application/json")
        .header("User-Agent", PackBuildMetadata.USER_AGENT)
        .header("X-Compatibility-Date", COMPATIBILITY_DATE)

    private fun execute(
        operation: String,
        request: HttpRequest,
    ): PublicEsiPayloadResult = try {
        val response = sendRequest(request)
        when {
            response.statusCode in 200..299 -> PublicEsiPayloadResult.Success(response.body)
            response.statusCode.isTemporarilyUnavailable() -> PublicEsiPayloadResult.Unavailable(
                "$operation returned HTTP ${response.statusCode}",
            )
            else -> PublicEsiPayloadResult.Invalid(
                "$operation rejected the request with HTTP ${response.statusCode}",
            )
        }
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        PublicEsiPayloadResult.Unavailable("$operation was interrupted")
    } catch (error: IOException) {
        PublicEsiPayloadResult.Unavailable(
            error.message?.let { "$operation is unavailable: $it" }
                ?: "$operation is unavailable",
        )
    }

    override fun close() {
        httpClient?.shutdownNow()
    }

    private companion object {
        const val COMPATIBILITY_DATE = "2026-05-19"
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)

        fun defaultHttpClient(): HttpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }
}

private fun Int.isTemporarilyUnavailable(): Boolean =
    this == 408 || this == 420 || this == 429 || this >= 500
