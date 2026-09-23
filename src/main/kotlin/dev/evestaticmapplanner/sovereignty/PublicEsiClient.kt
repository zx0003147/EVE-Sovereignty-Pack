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

internal data class AllianceMetadataValidators(
    val etag: String? = null,
    val lastModified: String? = null,
)

internal data class EsiCacheHeaders(
    val cacheControl: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
)

internal sealed interface PublicEsiAllianceMetadataResult {
    data class Updated(
        val payload: String,
        val cacheHeaders: EsiCacheHeaders,
    ) : PublicEsiAllianceMetadataResult

    data class NotModified(val cacheHeaders: EsiCacheHeaders) : PublicEsiAllianceMetadataResult

    data class Unavailable(val reason: String) : PublicEsiAllianceMetadataResult

    data class Invalid(val reason: String) : PublicEsiAllianceMetadataResult
}

/** Public ESI operations required by sovereignty loading, without exposing HTTP to the source. */
internal interface PublicEsiClient : AutoCloseable {
    fun fetchSovereigntySystems(): PublicEsiPayloadResult

    fun resolveNames(ids: List<Int>): PublicEsiPayloadResult

    fun fetchAllianceMetadata(
        allianceId: Int,
        validators: AllianceMetadataValidators = AllianceMetadataValidators(),
    ): PublicEsiAllianceMetadataResult = PublicEsiAllianceMetadataResult.Unavailable(
        "Public ESI alliance metadata is unavailable from this client",
    )

    override fun close() = Unit
}

internal data class EsiHttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
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
        EsiHttpResponse(response.statusCode(), response.body(), response.headers().map())
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

    override fun fetchAllianceMetadata(
        allianceId: Int,
        validators: AllianceMetadataValidators,
    ): PublicEsiAllianceMetadataResult {
        require(allianceId > 0) { "Alliance ID must be positive" }
        val builder = requestBuilder("alliances/$allianceId?datasource=tranquility")
        validators.etag?.let { builder.header("If-None-Match", it) }
        validators.lastModified?.let { builder.header("If-Modified-Since", it) }
        val operation = "Public ESI alliance $allianceId metadata"
        return try {
            val response = sendRequest(builder.GET().build())
            val cacheHeaders = response.cacheHeaders()
            when {
                response.statusCode == 200 -> PublicEsiAllianceMetadataResult.Updated(response.body, cacheHeaders)
                response.statusCode == 304 -> PublicEsiAllianceMetadataResult.NotModified(cacheHeaders)
                response.statusCode.isTemporarilyUnavailable() -> PublicEsiAllianceMetadataResult.Unavailable(
                    "$operation returned HTTP ${response.statusCode}",
                )
                else -> PublicEsiAllianceMetadataResult.Invalid(
                    "$operation rejected the request with HTTP ${response.statusCode}",
                )
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            PublicEsiAllianceMetadataResult.Unavailable("$operation was interrupted")
        } catch (error: IOException) {
            PublicEsiAllianceMetadataResult.Unavailable(
                error.message?.let { "$operation is unavailable: $it" } ?: "$operation is unavailable",
            )
        }
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

/** One lazy client shared by sovereignty and alliance metadata operations for a Pack activation. */
internal class DeferredPublicEsiClient(
    private val factory: () -> PublicEsiClient,
) : PublicEsiClient {
    private var delegate: PublicEsiClient? = null
    private var closed = false

    override fun fetchSovereigntySystems(): PublicEsiPayloadResult = instance().fetchSovereigntySystems()

    override fun resolveNames(ids: List<Int>): PublicEsiPayloadResult = instance().resolveNames(ids)

    override fun fetchAllianceMetadata(
        allianceId: Int,
        validators: AllianceMetadataValidators,
    ): PublicEsiAllianceMetadataResult = instance().fetchAllianceMetadata(allianceId, validators)

    @Synchronized
    private fun instance(): PublicEsiClient {
        check(!closed) { "Public ESI client is closed" }
        return delegate ?: factory().also { delegate = it }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        delegate?.close()
    }
}

private fun EsiHttpResponse.cacheHeaders() = EsiCacheHeaders(
    cacheControl = firstHeader("Cache-Control"),
    etag = firstHeader("ETag"),
    lastModified = firstHeader("Last-Modified"),
)

private fun EsiHttpResponse.firstHeader(name: String): String? = headers.entries
    .firstOrNull { (headerName, _) -> headerName.equals(name, ignoreCase = true) }
    ?.value
    ?.firstOrNull()

private fun Int.isTemporarilyUnavailable(): Boolean =
    this == 408 || this == 420 || this == 429 || this >= 500
