package dev.evestaticmapplanner.sovereignty

import java.io.InputStream

internal class EmbeddedJsonSnapshotProvider(
    private val resourceLoader: (String) -> InputStream? = { resourcePath ->
        EmbeddedJsonSnapshotProvider::class.java.getResourceAsStream(resourcePath)
    },
) : SovereigntySnapshotProvider {
    override fun loadSnapshot(): SovereigntySnapshot {
        val input = resourceLoader(RESOURCE_PATH)
            ?: return SovereigntySnapshot.empty("Bundled sovereignty.json resource is missing")

        return try {
            val objects = input.bufferedReader(Charsets.UTF_8).use { reader ->
                SovereigntyJsonParser(reader.readText()).parseObjectArray()
            }
            var ignored = 0
            val records = linkedMapOf<Int, SovereigntyRecord>()
            objects.forEach { values ->
                val record = values.toSovereigntyRecord()
                if (record == null || records.containsKey(record.systemId)) {
                    ignored += 1
                } else {
                    records[record.systemId] = record
                }
            }
            SovereigntySnapshot(
                records = records.values,
                metadata = SovereigntySnapshotMetadata(ignoredRecordCount = ignored),
            )
        } catch (error: Throwable) {
            SovereigntySnapshot.empty(
                error.message?.let { "Bundled sovereignty.json is invalid: $it" }
                    ?: "Bundled sovereignty.json could not be loaded",
            )
        }
    }

    private companion object {
        const val RESOURCE_PATH = "/sovereignty.json"
    }
}

private fun Map<String, JsonValue>.toSovereigntyRecord(): SovereigntyRecord? {
    val systemId = (get("systemId") as? JsonNumber)?.longValueOrNull()
        ?.takeIf { it in 1..Int.MAX_VALUE.toLong() }
        ?.toInt()
        ?: return null
    val allianceName = requiredText("allianceName") ?: return null
    val sovereigntyStatus = requiredText("sovereigntyStatus") ?: return null
    val allianceId = when (val value = get("allianceId")) {
        null, JsonNull -> null
        is JsonNumber -> value.longValueOrNull()?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt() ?: return null
        else -> return null
    }
    val corporationName = when (val value = get("corporationName")) {
        null, JsonNull -> null
        is JsonString -> value.value.takeIf(::isValidText) ?: return null
        else -> return null
    }
    return SovereigntyRecord(systemId, allianceName, corporationName, sovereigntyStatus, allianceId)
}

private fun Map<String, JsonValue>.requiredText(key: String): String? =
    (get(key) as? JsonString)?.value?.takeIf(::isValidText)

private fun isValidText(value: String): Boolean =
    value.isNotBlank() && value == value.trim() && value.none(Char::isISOControl)
