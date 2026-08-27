package dev.evestaticmapplanner.sovereignty

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pack-owned, deterministic alliance identity projected through generic text metadata. */
internal object SovereigntyVisualIdentity {
    const val GOONSWARM_ALLIANCE_ID = 1_354_830_081
    const val FRATERNITY_ALLIANCE_ID = 99_003_581
    const val GOONSWARM_YELLOW = "#CCF2C94C"
    const val FRATERNITY_BLUE = "#CC4D9DE0"
    const val UNKNOWN_GRAY = "#CC8EA8BD"

    // Generated colors deliberately stay bright on the navy map without becoming pastel or neon.
    const val MIN_SATURATION = 0.68
    const val MAX_SATURATION = 0.78
    const val MIN_LIGHTNESS = 0.60
    const val MAX_LIGHTNESS = 0.66

    private val allianceColorOverridesById = mapOf(
        GOONSWARM_ALLIANCE_ID to GOONSWARM_YELLOW,
        FRATERNITY_ALLIANCE_ID to FRATERNITY_BLUE,
    )
    private val legacyAllianceColorOverridesByName = mapOf(
        "Goonswarm Federation" to GOONSWARM_YELLOW,
        "Fraternity" to FRATERNITY_BLUE,
    )
    private val neutralOwnerNames = setOf("unknown", "unclaimed", "unknown / unclaimed")

    fun presentationMetadata(record: SovereigntyRecord): String = presentationMetadata(
        allianceId = record.allianceId,
        allianceName = record.allianceName,
    )

    fun presentationMetadata(allianceId: Int?, allianceName: String): String = buildString {
        append("owner-key:").append(ownerKey(allianceId, allianceName))
        append(";presentation-color:").append(colorFor(allianceId, allianceName))
        emblemReference(allianceId, allianceName)?.let { emblem ->
            append(";presentation-emblem-key:").append(emblem.key)
            append(";presentation-emblem-url:").append(emblem.url)
        }
    }

    internal fun emblemReference(allianceId: Int?, allianceName: String): SovereigntyEmblemReference? {
        if (allianceId == null || allianceName.trim().lowercase(Locale.ROOT) in neutralOwnerNames) return null
        return SovereigntyEmblemReference(
            key = "eve-alliance:$allianceId",
            url = "https://images.evetech.net/alliances/$allianceId/logo?size=$ALLIANCE_EMBLEM_SIZE",
        )
    }

    internal fun colorFor(allianceId: Int, allianceName: String = "Alliance $allianceId"): String =
        colorFor(allianceId as Int?, allianceName)

    internal fun colorFor(allianceId: Int?, allianceName: String): String {
        val canonicalName = allianceName.trim().lowercase(Locale.ROOT)
        if (canonicalName in neutralOwnerNames) return UNKNOWN_GRAY
        allianceId?.let { id -> allianceColorOverridesById[id]?.let { return it } }
        if (allianceId == null) legacyAllianceColorOverridesByName[allianceName]?.let { return it }
        return allianceId?.let(::generatedColor) ?: legacyGeneratedColor(canonicalName)
    }

    /** Retained only for explicitly identified v1 LKG/fixture compatibility. */
    internal fun colorFor(allianceName: String): String = colorFor(null, allianceName)

    private fun ownerKey(allianceId: Int?, allianceName: String): String = allianceId?.let { "alliance:$it" }
        ?: "legacy-name:${stableNameHash(allianceName.trim().lowercase(Locale.ROOT)).toString(16)}"

    private fun generatedColor(allianceId: Int): String {
        val seed = mixedAllianceId(allianceId)
        val hue = (seed % 36_000u).toDouble() / 100.0
        val saturation = MIN_SATURATION + ((seed shr 16) % 11u).toDouble() / 100.0
        val lightness = MIN_LIGHTNESS + ((seed shr 24) % 7u).toDouble() / 100.0
        return hslColor(hue, saturation.coerceAtMost(MAX_SATURATION), lightness.coerceAtMost(MAX_LIGHTNESS))
    }

    private fun legacyGeneratedColor(canonicalName: String): String {
        val seed = stableNameHash(canonicalName)
        val hue = (seed % 36_000u).toDouble() / 100.0
        return hslColor(hue, 0.72, 0.63)
    }

    private fun mixedAllianceId(allianceId: Int): UInt {
        var value = allianceId.toUInt()
        value = (value xor (value shr 16)) * 0x7FEB352Du
        value = (value xor (value shr 15)) * 0x846CA68Bu
        return value xor (value shr 16)
    }

    private fun stableNameHash(canonicalName: String): UInt {
        var hash = FNV_OFFSET_BASIS
        canonicalName.forEach { character -> hash = (hash xor character.code.toUInt()) * FNV_PRIME }
        return hash
    }

    private fun hslColor(hueDegrees: Double, saturation: Double, lightness: Double): String {
        val chroma = (1.0 - abs(2.0 * lightness - 1.0)) * saturation
        val hue = ((hueDegrees % 360.0) + 360.0) % 360.0 / 60.0
        val secondary = chroma * (1.0 - abs(hue % 2.0 - 1.0))
        val (redPrime, greenPrime, bluePrime) = when (hue.toInt()) {
            0 -> Triple(chroma, secondary, 0.0)
            1 -> Triple(secondary, chroma, 0.0)
            2 -> Triple(0.0, chroma, secondary)
            3 -> Triple(0.0, secondary, chroma)
            4 -> Triple(secondary, 0.0, chroma)
            else -> Triple(chroma, 0.0, secondary)
        }
        val match = lightness - chroma / 2.0
        fun channel(value: Double) = ((value + match) * 255.0).roundToInt().coerceIn(0, 255)
        return "#CC%02X%02X%02X".format(channel(redPrime), channel(greenPrime), channel(bluePrime))
    }

    private const val FNV_OFFSET_BASIS = 2_166_136_261u
    private const val FNV_PRIME = 16_777_619u
    private const val ALLIANCE_EMBLEM_SIZE = 256
}

internal data class SovereigntyEmblemReference(val key: String, val url: String)
