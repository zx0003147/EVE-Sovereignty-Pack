package dev.evestaticmapplanner.sovereignty

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Instant

internal interface SovereigntySnapshotCache {
    fun load(): SovereigntyCacheLoadResult

    fun save(snapshot: SovereigntySnapshot): SovereigntyCacheSaveResult
}

internal sealed interface SovereigntyCacheLoadResult {
    data class Hit(
        val snapshot: SovereigntySnapshot,
        val savedAt: Instant,
    ) : SovereigntyCacheLoadResult

    data object Miss : SovereigntyCacheLoadResult

    data class Unusable(
        val reason: String,
        val cause: Throwable? = null,
    ) : SovereigntyCacheLoadResult
}

internal sealed interface SovereigntyCacheSaveResult {
    data object Saved : SovereigntyCacheSaveResult

    data class Failed(
        val reason: String,
        val cause: Throwable? = null,
    ) : SovereigntyCacheSaveResult
}

internal class FileSovereigntySnapshotCache(
    private val path: Path,
) : SovereigntySnapshotCache {
    override fun load(): SovereigntyCacheLoadResult {
        if (!Files.exists(path)) return SovereigntyCacheLoadResult.Miss
        return try {
            val serialized = Files.readString(path, Charsets.UTF_8)
            val savedAt = Files.getLastModifiedTime(path).toInstant()
            SovereigntySnapshotCacheCodec.decode(serialized, savedAt)
        } catch (error: Exception) {
            SovereigntyCacheLoadResult.Unusable("Could not read sovereignty cache", error)
        }
    }

    override fun save(snapshot: SovereigntySnapshot): SovereigntyCacheSaveResult {
        val serialized = try {
            SovereigntySnapshotCacheCodec.encode(snapshot)
        } catch (error: IllegalArgumentException) {
            return SovereigntyCacheSaveResult.Failed(
                error.message ?: "Sovereignty snapshot is not cacheable",
                error,
            )
        }

        var temporaryPath: Path? = null
        return try {
            val directory = requireNotNull(path.parent) { "Sovereignty cache path must have a parent directory" }
            Files.createDirectories(directory)
            temporaryPath = Files.createTempFile(directory, "${path.fileName}.", ".tmp")
            Files.writeString(temporaryPath, serialized, Charsets.UTF_8)
            replaceFromTemporaryFile(temporaryPath, path)
            temporaryPath = null
            SovereigntyCacheSaveResult.Saved
        } catch (error: Exception) {
            SovereigntyCacheSaveResult.Failed("Could not persist sovereignty cache", error)
        } finally {
            temporaryPath?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }
}

private fun replaceFromTemporaryFile(temporaryPath: Path, finalPath: Path) {
    try {
        Files.move(temporaryPath, finalPath, ATOMIC_MOVE, REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        // The complete temporary file is still moved into place when this filesystem lacks atomic moves.
        Files.move(temporaryPath, finalPath, REPLACE_EXISTING)
    }
}

internal object SovereigntySnapshotCacheCodec {
    const val FORMAT_VERSION = 1
    const val SOURCE = "PUBLIC_ESI"

    fun encode(snapshot: SovereigntySnapshot): String {
        SovereigntySnapshotValidation.validatePublicEsi(snapshot)?.let { reason ->
            throw IllegalArgumentException("Invalid PUBLIC_ESI sovereignty snapshot: $reason")
        }
        return buildString {
            append("{\n")
            append("  \"formatVersion\": ").append(FORMAT_VERSION).append(",\n")
            append("  \"source\": \"").append(SOURCE).append("\",\n")
            append("  \"records\": [\n")
            snapshot.records.sortedBy(SovereigntyRecord::systemId).forEachIndexed { index, record ->
                append("    {\"systemId\": ").append(record.systemId)
                append(", \"allianceName\": ").appendJsonString(record.allianceName)
                append(", \"corporationName\": ")
                if (record.corporationName == null) append("null") else appendJsonString(record.corporationName)
                append(", \"sovereigntyStatus\": ").appendJsonString(record.sovereigntyStatus)
                append('}')
                if (index != snapshot.records.lastIndex) append(',')
                append('\n')
            }
            append("  ]\n")
            append("}\n")
        }
    }

    fun decode(
        serialized: String,
        savedAt: Instant,
    ): SovereigntyCacheLoadResult = try {
        val root = (SovereigntyJsonParser(serialized).parse() as? JsonObject)?.fields
            ?: return unusable("Cache root must be a JSON object")
        if (root.keys != setOf("formatVersion", "source", "records")) {
            return unusable("Cache root has missing or unsupported fields")
        }
        val formatVersion = (root["formatVersion"] as? JsonNumber)?.longValueOrNull()
            ?: return unusable("Cache formatVersion must be an integer")
        if (formatVersion != FORMAT_VERSION.toLong()) {
            return unusable("Unsupported sovereignty cache formatVersion $formatVersion")
        }
        val source = (root["source"] as? JsonString)?.value
            ?: return unusable("Cache source must be text")
        if (source != SOURCE) return unusable("Unsupported sovereignty cache source '$source'")
        val values = (root["records"] as? JsonArray)?.values
            ?: return unusable("Cache records must be an array")
        val records = values.mapIndexed { index, value ->
            decodeRecord(value) ?: return unusable("Cache records[$index] is malformed")
        }
        val snapshot = SovereigntySnapshot(records)
        SovereigntySnapshotValidation.validatePublicEsi(snapshot)?.let { reason ->
            return unusable("Invalid canonical sovereignty snapshot: $reason")
        }
        SovereigntyCacheLoadResult.Hit(snapshot, savedAt)
    } catch (error: Exception) {
        SovereigntyCacheLoadResult.Unusable("Malformed sovereignty cache", error)
    }

    private fun decodeRecord(value: JsonValue): SovereigntyRecord? {
        val fields = (value as? JsonObject)?.fields ?: return null
        if (fields.keys != setOf("systemId", "allianceName", "corporationName", "sovereigntyStatus")) return null
        val systemId = (fields["systemId"] as? JsonNumber)?.longValueOrNull()
            ?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
            ?.toInt()
            ?: return null
        val allianceName = (fields["allianceName"] as? JsonString)?.value ?: return null
        val corporationName = when (val corporation = fields["corporationName"]) {
            JsonNull -> null
            is JsonString -> corporation.value
            else -> return null
        }
        val sovereigntyStatus = (fields["sovereigntyStatus"] as? JsonString)?.value ?: return null
        return SovereigntyRecord(systemId, allianceName, corporationName, sovereigntyStatus)
    }

    private fun unusable(reason: String) = SovereigntyCacheLoadResult.Unusable(reason)
}

private fun StringBuilder.appendJsonString(value: String) {
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
