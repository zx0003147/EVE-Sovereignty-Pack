package dev.evestaticmapplanner.sovereignty

import java.time.Clock
import java.time.Instant

internal data class AllianceMetadataRefreshResult(
    val requestedCount: Int,
    val updatedCount: Int,
    val failures: List<String>,
) {
    val changed: Boolean get() = updatedCount > 0
}

internal data class AllianceMetadataRefreshPlan(
    val requestedCount: Int,
    val replacements: List<AllianceMetadata>,
    val failures: List<String>,
)

internal class PublicEsiAllianceMetadataSource(
    private val client: PublicEsiClient,
    private val state: AllianceMetadataState,
    private val cache: AllianceMetadataCache,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun requiresRefresh(observedAllianceNames: Map<Int, String>): Boolean {
        val now = clock.instant()
        return observedAllianceNames.any { (allianceId, sovereigntyName) ->
            val metadata = state.find(allianceId)
            metadata == null || metadata.observedSovereigntyName != sovereigntyName || !metadata.isFreshAt(now)
        }
    }

    fun fetch(observedAllianceNames: Map<Int, String>): AllianceMetadataRefreshPlan {
        val now = clock.instant()
        var requestedCount = 0
        val replacements = mutableListOf<AllianceMetadata>()
        val failures = mutableListOf<String>()
        observedAllianceNames.toSortedMap().forEach { (allianceId, sovereigntyName) ->
            val current = state.find(allianceId)
            if (current != null && current.observedSovereigntyName == sovereigntyName && current.isFreshAt(now)) {
                return@forEach
            }
            requestedCount += 1
            val validators = AllianceMetadataValidators(current?.etag, current?.lastModified)
            when (val result = client.fetchAllianceMetadata(allianceId, validators)) {
                is PublicEsiAllianceMetadataResult.Updated -> {
                    val detail = parseAllianceMetadataPayload(result.payload)
                    if (detail == null) {
                        failures += "Alliance $allianceId metadata payload is invalid"
                    } else {
                        val replacement = AllianceMetadata(
                            allianceId = allianceId,
                            name = detail.name,
                            ticker = detail.ticker,
                            observedSovereigntyName = sovereigntyName,
                            etag = result.cacheHeaders.etag,
                            lastModified = result.cacheHeaders.lastModified,
                            cacheControl = result.cacheHeaders.cacheControl,
                            freshUntil = result.cacheHeaders.freshUntil(now, null),
                            lastSuccessfulAt = now,
                        )
                        replacements += replacement
                    }
                }
                is PublicEsiAllianceMetadataResult.NotModified -> {
                    if (current == null) {
                        failures += "Alliance $allianceId returned 304 without last-good metadata"
                    } else {
                        val cacheControl = result.cacheHeaders.cacheControl ?: current.cacheControl
                        val replacement = current.copy(
                            observedSovereigntyName = sovereigntyName,
                            etag = result.cacheHeaders.etag ?: current.etag,
                            lastModified = result.cacheHeaders.lastModified ?: current.lastModified,
                            cacheControl = cacheControl,
                            freshUntil = EsiCacheHeaders(cacheControl = cacheControl).freshUntil(now, current),
                            lastSuccessfulAt = now,
                        )
                        replacements += replacement
                    }
                }
                is PublicEsiAllianceMetadataResult.Unavailable -> failures += result.reason
                is PublicEsiAllianceMetadataResult.Invalid -> failures += result.reason
            }
        }

        return AllianceMetadataRefreshPlan(requestedCount, replacements, failures)
    }

    fun apply(plan: AllianceMetadataRefreshPlan): AllianceMetadataRefreshResult {
        val updatedCount = plan.replacements.count(state::put)
        val failures = plan.failures.toMutableList()
        if (updatedCount > 0) {
            when (val saved = cache.save(state.records())) {
                AllianceMetadataCacheSaveResult.Saved -> Unit
                is AllianceMetadataCacheSaveResult.Failed -> failures += saved.reason
            }
        }
        return AllianceMetadataRefreshResult(plan.requestedCount, updatedCount, failures)
    }

    fun refresh(observedAllianceNames: Map<Int, String>): AllianceMetadataRefreshResult =
        apply(fetch(observedAllianceNames))
}

private data class AllianceMetadataDetail(
    val name: String,
    val ticker: String,
)

private fun parseAllianceMetadataPayload(payload: String): AllianceMetadataDetail? = runCatching {
    val fields = (SovereigntyJsonParser(payload).parse() as? JsonObject)?.fields ?: return@runCatching null
    val name = (fields["name"] as? JsonString)?.value?.takeIf { it.isAllianceDetailText(128) }
        ?: return@runCatching null
    val ticker = (fields["ticker"] as? JsonString)?.value?.takeIf { it.isAllianceDetailText(32) }
        ?: return@runCatching null
    AllianceMetadataDetail(name, ticker)
}.getOrNull()

private fun String.isAllianceDetailText(maximumLength: Int): Boolean =
    isNotBlank() && this == trim() && length <= maximumLength && none(Char::isISOControl)

private fun EsiCacheHeaders.freshUntil(now: Instant, previous: AllianceMetadata?): Instant {
    val effectiveCacheControl = cacheControl ?: previous?.cacheControl
    val maxAgeSeconds = effectiveCacheControl?.maxAgeSecondsOrNull() ?: 0L
    return runCatching { now.plusSeconds(maxAgeSeconds) }.getOrDefault(now)
}

private fun String.maxAgeSecondsOrNull(): Long? {
    val directives = split(',').map(String::trim)
    if (directives.any { it.equals("no-cache", ignoreCase = true) || it.equals("no-store", ignoreCase = true) }) {
        return 0
    }
    return directives.firstNotNullOfOrNull { directive ->
        val parts = directive.split('=', limit = 2)
        if (parts.size == 2 && parts[0].trim().equals("max-age", ignoreCase = true)) {
            parts[1].trim().trim('"').toLongOrNull()?.coerceAtLeast(0)
        } else {
            null
        }
    }
}
