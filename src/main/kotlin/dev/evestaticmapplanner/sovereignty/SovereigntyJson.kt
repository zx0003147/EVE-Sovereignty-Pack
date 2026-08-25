package dev.evestaticmapplanner.sovereignty

internal sealed interface JsonValue

internal data class JsonObject(val fields: Map<String, JsonValue>) : JsonValue

internal data class JsonArray(val values: List<JsonValue>) : JsonValue

internal data class JsonString(val value: String) : JsonValue

internal data class JsonNumber(val literal: String) : JsonValue {
    fun longValueOrNull(): Long? = literal
        .takeUnless { '.' in it || 'e' in it || 'E' in it }
        ?.toLongOrNull()
}

internal data class JsonBoolean(val value: Boolean) : JsonValue

internal data object JsonNull : JsonValue

/** Small dependency-free JSON parser shared by the Pack's embedded and remote loaders. */
internal class SovereigntyJsonParser(private val source: String) {
    private var index = 0

    fun parse(): JsonValue {
        skipWhitespace()
        val result = parseValue()
        skipWhitespace()
        check(index == source.length) { "Unexpected content after JSON value" }
        return result
    }

    fun parseObjectArray(): List<Map<String, JsonValue>> {
        val array = parse() as? JsonArray ?: error("Expected a JSON array")
        return array.values.mapIndexed { itemIndex, value ->
            (value as? JsonObject)?.fields
                ?: error("Expected a JSON object at array index $itemIndex")
        }
    }

    private fun parseValue(): JsonValue = when (peek()) {
        '{' -> parseObject()
        '[' -> parseArray()
        '"' -> JsonString(parseString())
        't' -> {
            expectLiteral("true")
            JsonBoolean(true)
        }
        'f' -> {
            expectLiteral("false")
            JsonBoolean(false)
        }
        'n' -> {
            expectLiteral("null")
            JsonNull
        }
        '-', in '0'..'9' -> JsonNumber(parseNumber())
        else -> error("Unsupported JSON value at character $index")
    }

    private fun parseObject(): JsonObject {
        expect('{')
        skipWhitespace()
        val result = linkedMapOf<String, JsonValue>()
        if (consume('}')) return JsonObject(result)
        while (true) {
            val key = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            check(result.put(key, parseValue()) == null) { "Duplicate JSON field: $key" }
            skipWhitespace()
            if (consume('}')) return JsonObject(result)
            expect(',')
            skipWhitespace()
        }
    }

    private fun parseArray(): JsonArray {
        expect('[')
        skipWhitespace()
        val result = mutableListOf<JsonValue>()
        if (consume(']')) return JsonArray(result)
        while (true) {
            result += parseValue()
            skipWhitespace()
            if (consume(']')) return JsonArray(result)
            expect(',')
            skipWhitespace()
        }
    }

    private fun parseNumber(): String {
        val start = index
        consume('-')
        if (consume('0')) {
            check(peekOrNull()?.isDigit() != true) { "JSON number has a leading zero at character $start" }
        } else {
            check(peekOrNull()?.isDigit() == true) { "Expected JSON number at character $start" }
            while (peekOrNull()?.isDigit() == true) index += 1
        }
        if (consume('.')) {
            check(peekOrNull()?.isDigit() == true) { "Expected JSON fraction at character $index" }
            while (peekOrNull()?.isDigit() == true) index += 1
        }
        if (peekOrNull() == 'e' || peekOrNull() == 'E') {
            index += 1
            if (peekOrNull() == '+' || peekOrNull() == '-') index += 1
            check(peekOrNull()?.isDigit() == true) { "Expected JSON exponent at character $index" }
            while (peekOrNull()?.isDigit() == true) index += 1
        }
        return source.substring(start, index)
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
