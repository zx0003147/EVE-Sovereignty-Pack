package dev.evestaticmapplanner.sovereignty

import java.time.Instant

internal data class AllianceMetadata(
    val allianceId: Int,
    val name: String,
    val ticker: String,
    val observedSovereigntyName: String,
    val etag: String?,
    val lastModified: String?,
    val cacheControl: String?,
    val freshUntil: Instant,
    val lastSuccessfulAt: Instant,
) {
    init {
        require(allianceId > 0) { "Alliance ID must be positive" }
        requireMetadataText("Alliance name", name, 128)
        requireMetadataText("Alliance ticker", ticker, 32)
        requireMetadataText("Observed sovereignty name", observedSovereigntyName, 128)
        optionalMetadataText("ETag", etag, 512)
        optionalMetadataText("Last-Modified", lastModified, 128)
        optionalMetadataText("Cache-Control", cacheControl, 512)
    }

    fun isFreshAt(now: Instant): Boolean = now.isBefore(freshUntil)
}

internal class AllianceMetadataState(initial: Iterable<AllianceMetadata> = emptyList()) {
    private var recordsById = initial.associateBy(AllianceMetadata::allianceId)

    @Synchronized
    fun find(allianceId: Int): AllianceMetadata? = recordsById[allianceId]

    @Synchronized
    fun records(): List<AllianceMetadata> = recordsById.values.sortedBy(AllianceMetadata::allianceId)

    @Synchronized
    fun put(record: AllianceMetadata): Boolean {
        val changed = recordsById[record.allianceId] != record
        if (changed) recordsById = recordsById + (record.allianceId to record)
        return changed
    }
}

internal fun SovereigntyRepository.observedAllianceNames(): Map<Int, String> {
    val names = linkedMapOf<Int, String>()
    records().forEach { record ->
        record.allianceId?.let { allianceId -> names.putIfAbsent(allianceId, record.allianceName) }
    }
    return names
}

private fun requireMetadataText(label: String, value: String, maximumLength: Int) {
    require(value.isNotBlank() && value == value.trim()) { "$label must be non-blank and trimmed" }
    require(value.length <= maximumLength) { "$label must not exceed $maximumLength characters" }
    require(value.none(Char::isISOControl)) { "$label must not contain control characters" }
}

private fun optionalMetadataText(label: String, value: String?, maximumLength: Int) {
    if (value == null) return
    requireMetadataText(label, value, maximumLength)
}
