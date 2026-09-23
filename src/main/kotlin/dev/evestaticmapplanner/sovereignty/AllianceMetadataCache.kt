package dev.evestaticmapplanner.sovereignty

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Instant

internal interface AllianceMetadataCache {
    fun load(): AllianceMetadataCacheLoadResult

    fun save(records: List<AllianceMetadata>): AllianceMetadataCacheSaveResult
}

internal sealed interface AllianceMetadataCacheLoadResult {
    data class Hit(val records: List<AllianceMetadata>) : AllianceMetadataCacheLoadResult

    data object Miss : AllianceMetadataCacheLoadResult

    data class Unusable(
        val reason: String,
        val cause: Throwable? = null,
    ) : AllianceMetadataCacheLoadResult
}

internal sealed interface AllianceMetadataCacheSaveResult {
    data object Saved : AllianceMetadataCacheSaveResult

    data class Failed(
        val reason: String,
        val cause: Throwable? = null,
    ) : AllianceMetadataCacheSaveResult
}

internal class FileAllianceMetadataCache(
    private val path: Path,
) : AllianceMetadataCache {
    override fun load(): AllianceMetadataCacheLoadResult {
        if (!Files.exists(path)) return AllianceMetadataCacheLoadResult.Miss
        return try {
            AllianceMetadataCacheCodec.decode(Files.readString(path, Charsets.UTF_8))
        } catch (error: Exception) {
            AllianceMetadataCacheLoadResult.Unusable("Could not read alliance metadata cache", error)
        }
    }

    override fun save(records: List<AllianceMetadata>): AllianceMetadataCacheSaveResult {
        val serialized = try {
            AllianceMetadataCacheCodec.encode(records)
        } catch (error: IllegalArgumentException) {
            return AllianceMetadataCacheSaveResult.Failed(
                error.message ?: "Alliance metadata is not cacheable",
                error,
            )
        }
        var temporaryPath: Path? = null
        return try {
            val directory = requireNotNull(path.parent) { "Alliance metadata cache path must have a parent directory" }
            Files.createDirectories(directory)
            temporaryPath = Files.createTempFile(directory, "${path.fileName}.", ".tmp")
            Files.writeString(temporaryPath, serialized, Charsets.UTF_8)
            replaceMetadataCache(temporaryPath, path)
            temporaryPath = null
            AllianceMetadataCacheSaveResult.Saved
        } catch (error: Exception) {
            AllianceMetadataCacheSaveResult.Failed("Could not persist alliance metadata cache", error)
        } finally {
            temporaryPath?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }
}

internal object AllianceMetadataCacheCodec {
    private const val FORMAT_VERSION = 1
    private const val SOURCE = "PUBLIC_ESI_ALLIANCE_METADATA"

    fun encode(records: List<AllianceMetadata>): String {
        require(records.map(AllianceMetadata::allianceId).distinct().size == records.size) {
            "Alliance metadata cache contains duplicate Alliance IDs"
        }
        return buildString {
            append("{\n")
            append("  \"formatVersion\": ").append(FORMAT_VERSION).append(",\n")
            append("  \"source\": \"").append(SOURCE).append("\",\n")
            append("  \"records\": [\n")
            records.sortedBy(AllianceMetadata::allianceId).forEachIndexed { index, record ->
                append("    {\"allianceId\": ").append(record.allianceId)
                append(", \"name\": ").appendMetadataJsonString(record.name)
                append(", \"ticker\": ").appendMetadataJsonString(record.ticker)
                append(", \"observedSovereigntyName\": ").appendMetadataJsonString(record.observedSovereigntyName)
                append(", \"etag\": ").appendNullableMetadataJsonString(record.etag)
                append(", \"lastModified\": ").appendNullableMetadataJsonString(record.lastModified)
                append(", \"cacheControl\": ").appendNullableMetadataJsonString(record.cacheControl)
                append(", \"freshUntilEpochMillis\": ").append(record.freshUntil.toEpochMilli())
                append(", \"lastSuccessfulAtEpochMillis\": ").append(record.lastSuccessfulAt.toEpochMilli())
                append('}')
                if (index != records.lastIndex) append(',')
                append('\n')
            }
            append("  ]\n")
            append("}\n")
        }
    }

    fun decode(serialized: String): AllianceMetadataCacheLoadResult = try {
        val root = (SovereigntyJsonParser(serialized).parse() as? JsonObject)?.fields
            ?: return unusable("Alliance metadata cache root must be a JSON object")
        if (root.keys != setOf("formatVersion", "source", "records")) {
            return unusable("Alliance metadata cache root has missing or unsupported fields")
        }
        val formatVersion = (root["formatVersion"] as? JsonNumber)?.longValueOrNull()
        if (formatVersion != FORMAT_VERSION.toLong()) {
            return unusable("Unsupported alliance metadata cache formatVersion $formatVersion")
        }
        if ((root["source"] as? JsonString)?.value != SOURCE) {
            return unusable("Unsupported alliance metadata cache source")
        }
        val values = (root["records"] as? JsonArray)?.values
            ?: return unusable("Alliance metadata cache records must be an array")
        val records = values.mapIndexed { index, value ->
            decodeRecord(value) ?: return unusable("Alliance metadata cache records[$index] is malformed")
        }
        if (records.map(AllianceMetadata::allianceId).distinct().size != records.size) {
            return unusable("Alliance metadata cache contains duplicate Alliance IDs")
        }
        AllianceMetadataCacheLoadResult.Hit(records.sortedBy(AllianceMetadata::allianceId))
    } catch (error: Exception) {
        AllianceMetadataCacheLoadResult.Unusable("Malformed alliance metadata cache", error)
    }

    private fun decodeRecord(value: JsonValue): AllianceMetadata? {
        val fields = (value as? JsonObject)?.fields ?: return null
        if (fields.keys != setOf(
                "allianceId",
                "name",
                "ticker",
                "observedSovereigntyName",
                "etag",
                "lastModified",
                "cacheControl",
                "freshUntilEpochMillis",
                "lastSuccessfulAtEpochMillis",
            )
        ) return null
        val allianceId = fields.positiveInt("allianceId") ?: return null
        val name = fields.requiredCacheText("name", 128) ?: return null
        val ticker = fields.requiredCacheText("ticker", 32) ?: return null
        val observedName = fields.requiredCacheText("observedSovereigntyName", 128) ?: return null
        val etag = fields.optionalCacheText("etag", 512) ?: if (fields["etag"] == JsonNull) null else return null
        val lastModified = fields.optionalCacheText("lastModified", 128)
            ?: if (fields["lastModified"] == JsonNull) null else return null
        val cacheControl = fields.optionalCacheText("cacheControl", 512)
            ?: if (fields["cacheControl"] == JsonNull) null else return null
        val freshUntil = fields.instantFromMillis("freshUntilEpochMillis") ?: return null
        val lastSuccessfulAt = fields.instantFromMillis("lastSuccessfulAtEpochMillis") ?: return null
        return runCatching {
            AllianceMetadata(
                allianceId,
                name,
                ticker,
                observedName,
                etag,
                lastModified,
                cacheControl,
                freshUntil,
                lastSuccessfulAt,
            )
        }.getOrNull()
    }

    private fun unusable(reason: String) = AllianceMetadataCacheLoadResult.Unusable(reason)
}

private fun replaceMetadataCache(temporaryPath: Path, finalPath: Path) {
    try {
        Files.move(temporaryPath, finalPath, ATOMIC_MOVE, REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporaryPath, finalPath, REPLACE_EXISTING)
    }
}

private fun Map<String, JsonValue>.positiveInt(key: String): Int? =
    (get(key) as? JsonNumber)?.longValueOrNull()?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()

private fun Map<String, JsonValue>.requiredCacheText(key: String, maximumLength: Int): String? =
    (get(key) as? JsonString)?.value?.takeIf { it.isCacheText(maximumLength) }

private fun Map<String, JsonValue>.optionalCacheText(key: String, maximumLength: Int): String? =
    (get(key) as? JsonString)?.value?.takeIf { it.isCacheText(maximumLength) }

private fun Map<String, JsonValue>.instantFromMillis(key: String): Instant? =
    (get(key) as? JsonNumber)?.longValueOrNull()?.let { runCatching { Instant.ofEpochMilli(it) }.getOrNull() }

private fun String.isCacheText(maximumLength: Int): Boolean =
    isNotBlank() && this == trim() && length <= maximumLength && none(Char::isISOControl)

private fun StringBuilder.appendNullableMetadataJsonString(value: String?) {
    if (value == null) append("null") else appendMetadataJsonString(value)
}

private fun StringBuilder.appendMetadataJsonString(value: String) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u").append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}
