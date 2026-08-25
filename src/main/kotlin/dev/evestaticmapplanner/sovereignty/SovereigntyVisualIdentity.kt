package dev.evestaticmapplanner.sovereignty

internal object SovereigntyVisualIdentity {
    const val GOONSWARM_YELLOW = "#CCF2C94C"
    const val FRATERNITY_BLUE = "#CC4D9DE0"
    const val UNKNOWN_GRAY = "#CC8EA8BD"

    private val allianceColors = mapOf(
        "Goonswarm Federation" to GOONSWARM_YELLOW,
        "Fraternity" to FRATERNITY_BLUE,
    )

    fun ringMetadata(allianceName: String): String =
        "ring-color:${allianceColors[allianceName] ?: UNKNOWN_GRAY}"
}
