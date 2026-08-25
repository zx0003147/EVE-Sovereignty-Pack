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
                FlatJsonParser(reader.readText()).parseObjectArray()
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
    val systemId = (get("systemId") as? JsonNumber)?.value
        ?.takeIf { it in 1..Int.MAX_VALUE.toLong() }
        ?.toInt()
        ?: return null
    val allianceName = requiredText("allianceName") ?: return null
    val sovereigntyStatus = requiredText("sovereigntyStatus") ?: return null
    val corporationName = when (val value = get("corporationName")) {
        null, JsonNull -> null
        is JsonString -> value.value.takeIf(::isValidText) ?: return null
        else -> return null
    }
    return SovereigntyRecord(systemId, allianceName, corporationName, sovereigntyStatus)
}

private fun Map<String, JsonValue>.requiredText(key: String): String? =
    (get(key) as? JsonString)?.value?.takeIf(::isValidText)

private fun isValidText(value: String): Boolean =
    value.isNotBlank() && value == value.trim() && value.none(Char::isISOControl)

private sealed interface JsonValue

private data class JsonString(val value: String) : JsonValue

private data class JsonNumber(val value: Long) : JsonValue

private data object JsonNull : JsonValue

/** Minimal parser for the Pack's bundled array of flat JSON objects. */
private class FlatJsonParser(private val source: String) {
    private var index = 0

    fun parseObjectArray(): List<Map<String, JsonValue>> {
        skipWhitespace()
        expect('[')
        skipWhitespace()
        val result = mutableListOf<Map<String, JsonValue>>()
        if (consume(']')) return finish(result)
        while (true) {
            result += parseObject()
            skipWhitespace()
            if (consume(']')) return finish(result)
            expect(',')
        }
    }

    private fun parseObject(): Map<String, JsonValue> {
        skipWhitespace()
        expect('{')
        skipWhitespace()
        val result = linkedMapOf<String, JsonValue>()
        if (consume('}')) return result
        while (true) {
            val key = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            check(result.put(key, parseValue()) == null) { "Duplicate JSON field: $key" }
            skipWhitespace()
            if (consume('}')) return result
            expect(',')
            skipWhitespace()
        }
    }

    private fun parseValue(): JsonValue = when (peek()) {
        '"' -> JsonString(parseString())
        'n' -> {
            expectLiteral("null")
            JsonNull
        }
        '-', in '0'..'9' -> JsonNumber(parseInteger())
        else -> error("Unsupported JSON value at character $index")
    }

    private fun parseInteger(): Long {
        val start = index
        consume('-')
        if (consume('0')) {
            check(peekOrNull()?.isDigit() != true) { "JSON number has a leading zero at character $start" }
        } else {
            check(peekOrNull()?.isDigit() == true) { "Expected JSON integer at character $start" }
            while (peekOrNull()?.isDigit() == true) index += 1
        }
        return source.substring(start, index).toLong()
    }

    private fun parseString(): String {
        expect('"')
        return buildString {
            while (true) {
                check(index < source.length) { "Unterminated JSON string" }
                when (val character = source[index++]) {
                    '"' -> return@buildString
                    '\\' -> append(parseEscape())
                    else -> {
                        check(character.code >= 0x20) { "Control character in JSON string" }
                        append(character)
                    }
                }
            }
        }
    }

    private fun parseEscape(): Char {
        check(index < source.length) { "Unterminated JSON escape" }
        return when (val escaped = source[index++]) {
            '"', '\\', '/' -> escaped
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                check(index + 4 <= source.length) { "Incomplete JSON Unicode escape" }
                source.substring(index, index + 4).toInt(16).toChar().also { index += 4 }
            }
            else -> error("Unsupported JSON escape: \\$escaped")
        }
    }

    private fun finish(result: List<Map<String, JsonValue>>): List<Map<String, JsonValue>> {
        skipWhitespace()
        check(index == source.length) { "Unexpected content after JSON array" }
        return result
    }

    private fun expectLiteral(value: String) {
        check(source.regionMatches(index, value, 0, value.length)) {
            "Expected '$value' at character $index"
        }
        index += value.length
    }

    private fun expect(character: Char) {
        check(consume(character)) { "Expected '$character' at character $index" }
    }

    private fun consume(character: Char): Boolean {
        if (peekOrNull() != character) return false
        index += 1
        return true
    }

    private fun peek(): Char = peekOrNull() ?: error("Unexpected end of JSON input")

    private fun peekOrNull(): Char? = source.getOrNull(index)

    private fun skipWhitespace() {
        while (source.getOrNull(index)?.isWhitespace() == true) index += 1
    }
}
