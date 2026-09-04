package dev.evestaticmapplanner.sovereignty

/**
 * Loads and validates alliance sovereignty from public, unauthenticated ESI routes.
 *
 * Conversion is atomic: an invalid or duplicate system entry invalidates the complete remote snapshot.
 * Valid faction and unclaimed entries are accepted but do not become alliance ownership records.
 */
internal class PublicEsiSovereigntySource(
    private val client: PublicEsiClient = JdkPublicEsiClient(),
) : RemoteSovereigntySource {
    override fun fetchSnapshot(): RemoteSnapshotResult {
        val payload = when (val result = client.fetchSovereigntySystems()) {
            is PublicEsiPayloadResult.Success -> result.payload
            is PublicEsiPayloadResult.Unavailable -> return RemoteSnapshotResult.Unavailable(result.reason)
            is PublicEsiPayloadResult.Invalid -> return RemoteSnapshotResult.Invalid(result.reason)
        }
        val remoteSnapshot = when (val parsed = parseSovereigntyPayload(payload)) {
            is PayloadResult.Success -> parsed.value
            is PayloadResult.Invalid -> return RemoteSnapshotResult.Invalid(parsed.reason)
        }

        val allianceClaims = remoteSnapshot.systems.mapNotNull { system ->
            (system.owner as? RemoteSovereigntyOwnerDto.Alliance)?.let { owner -> system to owner }
        }
        val expectedNameCategories = linkedMapOf<Int, String>()
        allianceClaims.forEach { (_, owner) ->
            if (!expectedNameCategories.recordExpectedCategory(owner.allianceId, ALLIANCE_CATEGORY)) {
                return RemoteSnapshotResult.Invalid(
                    "Remote owner ID ${owner.allianceId} is used as more than one category",
                )
            }
            owner.corporationId?.let { corporationId ->
                if (!expectedNameCategories.recordExpectedCategory(corporationId, CORPORATION_CATEGORY)) {
                    return RemoteSnapshotResult.Invalid(
                        "Remote owner ID $corporationId is used as more than one category",
                    )
                }
            }
        }

        val namesById = when (val result = resolveOwnerNames(expectedNameCategories)) {
            is OwnerNameResolutionResult.Success -> result.namesById
            is OwnerNameResolutionResult.Invalid -> return RemoteSnapshotResult.Invalid(result.reason)
            is OwnerNameResolutionResult.Unavailable -> return RemoteSnapshotResult.Unavailable(result.reason)
        }
        val records = allianceClaims.map { (system, owner) ->
            SovereigntyRecord(
                systemId = system.systemId,
                allianceName = namesById.getValue(owner.allianceId),
                corporationName = owner.corporationId?.let(namesById::getValue),
                sovereigntyStatus = PUBLIC_ESI_CLAIMED_STATUS,
                allianceId = owner.allianceId,
            )
        }
        return RemoteSnapshotResult.Success(SovereigntySnapshot(records))
    }

    override fun close() {
        client.close()
    }

    private fun resolveOwnerNames(expectedCategories: Map<Int, String>): OwnerNameResolutionResult {
        if (expectedCategories.isEmpty()) return OwnerNameResolutionResult.Success(emptyMap())

        val namesById = linkedMapOf<Int, String>()
        expectedCategories.keys.sorted().chunked(NAME_RESOLUTION_BATCH_SIZE).forEach { ids ->
            val payload = when (val result = client.resolveNames(ids)) {
                is PublicEsiPayloadResult.Success -> result.payload
                is PublicEsiPayloadResult.Unavailable -> return OwnerNameResolutionResult.Unavailable(result.reason)
                is PublicEsiPayloadResult.Invalid -> return OwnerNameResolutionResult.Invalid(result.reason)
            }
            val names = when (val parsed = parseNamePayload(payload)) {
                is PayloadResult.Success -> parsed.value
                is PayloadResult.Invalid -> return OwnerNameResolutionResult.Invalid(parsed.reason)
            }
            names.forEach { name ->
                val expectedCategory = expectedCategories[name.id]
                    ?: return OwnerNameResolutionResult.Invalid("ESI returned unexpected owner ID ${name.id}")
                if (name.category != expectedCategory) {
                    return OwnerNameResolutionResult.Invalid(
                        "ESI owner ID ${name.id} has category '${name.category}', expected '$expectedCategory'",
                    )
                }
                if (namesById.put(name.id, name.name) != null) {
                    return OwnerNameResolutionResult.Invalid("ESI returned duplicate name for owner ID ${name.id}")
                }
            }
        }
        val missingIds = expectedCategories.keys - namesById.keys
        if (missingIds.isNotEmpty()) {
            return OwnerNameResolutionResult.Invalid(
                "ESI did not resolve owner ID(s): ${missingIds.sorted().joinToString()}",
            )
        }
        return OwnerNameResolutionResult.Success(namesById)
    }

    private companion object {
        const val ALLIANCE_CATEGORY = "alliance"
        const val CORPORATION_CATEGORY = "corporation"
        const val NAME_RESOLUTION_BATCH_SIZE = 1_000
    }
}

private data class RemoteSovereigntyPayloadDto(
    val systems: List<RemoteSovereigntySystemDto>,
)

private data class RemoteSovereigntySystemDto(
    val systemId: Int,
    val owner: RemoteSovereigntyOwnerDto,
)

private sealed interface RemoteSovereigntyOwnerDto {
    data class Alliance(
        val allianceId: Int,
        val corporationId: Int?,
    ) : RemoteSovereigntyOwnerDto

    data class Faction(val factionId: Int) : RemoteSovereigntyOwnerDto

    data object Unclaimed : RemoteSovereigntyOwnerDto
}

private data class RemoteOwnerNameDto(
    val id: Int,
    val name: String,
    val category: String,
)

private sealed interface PayloadResult<out T> {
    data class Success<T>(val value: T) : PayloadResult<T>

    data class Invalid(val reason: String) : PayloadResult<Nothing>
}

private sealed interface OwnerNameResolutionResult {
    data class Success(val namesById: Map<Int, String>) : OwnerNameResolutionResult

    data class Invalid(val reason: String) : OwnerNameResolutionResult

    data class Unavailable(val reason: String) : OwnerNameResolutionResult
}

private fun parseSovereigntyPayload(payload: String): PayloadResult<RemoteSovereigntyPayloadDto> = parsePayload {
    val root = SovereigntyJsonParser(payload).parse().requiredObject("sovereignty payload")
    val systems = root.requiredArray("solar_systems", "sovereignty payload")
    invalidIf(systems.isEmpty()) { "Sovereignty payload contains no solar systems" }

    val seenSystemIds = mutableSetOf<Int>()
    val remoteSystems = systems.mapIndexed { index, value ->
        val context = "solar_systems[$index]"
        val system = value.requiredObject(context)
        val systemId = system.requiredPositiveInt("solar_system_id", context)
        invalidIf(systemId !in NEW_EDEN_SYSTEM_ID_RANGE) {
            "$context has invalid solar_system_id $systemId"
        }
        invalidIf(!seenSystemIds.add(systemId)) {
            "Sovereignty payload contains duplicate solar_system_id $systemId"
        }
        val claim = system.requiredObject("claim", context)
        val claimKinds = listOf("alliance", "faction", "unclaimed").filter(claim::containsKey)
        invalidIf(claimKinds.size != 1) {
            "$context.claim must contain exactly one supported ownership kind"
        }
        val owner = when (claimKinds.single()) {
            "alliance" -> {
                val alliance = claim.getValue("alliance").requiredObject("$context.claim.alliance")
                RemoteSovereigntyOwnerDto.Alliance(
                    allianceId = alliance.requiredPositiveInt("alliance_id", "$context.claim.alliance"),
                    corporationId = alliance.optionalPositiveInt("corporation_id", "$context.claim.alliance"),
                )
            }
            "faction" -> {
                val faction = claim.getValue("faction").requiredObject("$context.claim.faction")
                RemoteSovereigntyOwnerDto.Faction(
                    factionId = faction.requiredPositiveInt("faction_id", "$context.claim.faction"),
                )
            }
            else -> {
                val unclaimed = (claim.getValue("unclaimed") as? JsonBoolean)?.value
                invalidIf(unclaimed != true) { "$context.claim.unclaimed must be true" }
                RemoteSovereigntyOwnerDto.Unclaimed
            }
        }
        RemoteSovereigntySystemDto(systemId, owner)
    }
    RemoteSovereigntyPayloadDto(remoteSystems)
}

private fun parseNamePayload(payload: String): PayloadResult<List<RemoteOwnerNameDto>> = parsePayload {
    val values = (SovereigntyJsonParser(payload).parse() as? JsonArray)?.values
        ?: invalid("ESI names payload must be a JSON array")
    values.mapIndexed { index, value ->
        val context = "names[$index]"
        val name = value.requiredObject(context)
        RemoteOwnerNameDto(
            id = name.requiredPositiveInt("id", context),
            name = name.requiredText("name", context),
            category = name.requiredText("category", context),
        )
    }
}

private inline fun <T> parsePayload(block: () -> T): PayloadResult<T> = try {
    PayloadResult.Success(block())
} catch (error: InvalidPayloadException) {
    PayloadResult.Invalid(error.message ?: "Remote payload is invalid")
} catch (error: RuntimeException) {
    PayloadResult.Invalid(
        error.message?.let { "Remote payload is malformed: $it" }
            ?: "Remote payload is malformed",
    )
}

private fun JsonValue.requiredObject(context: String): Map<String, JsonValue> =
    (this as? JsonObject)?.fields ?: invalid("$context must be a JSON object")

private fun Map<String, JsonValue>.requiredObject(key: String, context: String): Map<String, JsonValue> =
    get(key)?.requiredObject("$context.$key") ?: invalid("$context is missing $key")

private fun Map<String, JsonValue>.requiredArray(key: String, context: String): List<JsonValue> =
    (get(key) as? JsonArray)?.values ?: invalid("$context.$key must be a JSON array")

private fun Map<String, JsonValue>.requiredPositiveInt(key: String, context: String): Int {
    val value = (get(key) as? JsonNumber)?.longValueOrNull()
        ?: invalid("$context.$key must be an integer")
    if (value !in 1..Int.MAX_VALUE.toLong()) invalid("$context.$key must be a positive 32-bit integer")
    return value.toInt()
}

private fun Map<String, JsonValue>.optionalPositiveInt(key: String, context: String): Int? = when (val value = get(key)) {
    null, JsonNull -> null
    is JsonNumber -> {
        val integer = value.longValueOrNull()
            ?: invalid("$context.$key must be an integer")
        if (integer !in 1..Int.MAX_VALUE.toLong()) {
            invalid("$context.$key must be a positive 32-bit integer")
        }
        integer.toInt()
    }
    else -> invalid("$context.$key must be an integer or null")
}

private fun Map<String, JsonValue>.requiredText(key: String, context: String): String {
    val value = (get(key) as? JsonString)?.value ?: invalid("$context.$key must be text")
    invalidIf(value.isBlank() || value != value.trim() || value.any(Char::isISOControl)) {
        "$context.$key is empty or invalid"
    }
    return value
}

private fun MutableMap<Int, String>.recordExpectedCategory(id: Int, category: String): Boolean {
    val existing = putIfAbsent(id, category)
    return existing == null || existing == category
}

private inline fun invalidIf(condition: Boolean, message: () -> String) {
    if (condition) invalid(message())
}

private fun invalid(message: String): Nothing = throw InvalidPayloadException(message)

private class InvalidPayloadException(message: String) : RuntimeException(message)
