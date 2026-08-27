package dev.evestaticmapplanner.sovereignty

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SovereigntyVisualIdentityTest {
    @Test
    fun `alliance ID keeps base identity stable across rename`() {
        val oldName = SovereigntyVisualIdentity.presentationMetadata(99_100_001, "Old Alliance Name")
        val newName = SovereigntyVisualIdentity.presentationMetadata(99_100_001, "New Alliance Name")

        assertEquals(oldName, newName)
        assertTrue(oldName.startsWith("owner-key:alliance:99100001;presentation-color:"))
    }

    @Test
    fun `different alliance IDs receive diverse deterministic colors`() {
        val ids = (99_100_001..99_100_032).toList()
        val firstPass = ids.map(SovereigntyVisualIdentity::colorFor)
        val secondPass = ids.map(SovereigntyVisualIdentity::colorFor)

        assertEquals(firstPass, secondPass)
        assertTrue(firstPass.toSet().size >= 28, "Expected ID color diversity")
        assertNotEquals(firstPass[0], firstPass[1])
    }

    @Test
    fun `generated colors obey strategic saturation and lightness clamps`() {
        (99_100_001..99_100_128).forEach { allianceId ->
            val hsl = parseRgb(SovereigntyVisualIdentity.colorFor(allianceId)).toHsl()
            assertTrue(hsl.saturation + 0.01 >= SovereigntyVisualIdentity.MIN_SATURATION)
            assertTrue(hsl.saturation - 0.01 <= SovereigntyVisualIdentity.MAX_SATURATION)
            assertTrue(hsl.lightness + 0.01 >= SovereigntyVisualIdentity.MIN_LIGHTNESS)
            assertTrue(hsl.lightness - 0.01 <= SovereigntyVisualIdentity.MAX_LIGHTNESS)
        }
    }

    @Test
    fun `known alliance overrides are keyed by alliance ID`() {
        assertEquals(
            SovereigntyVisualIdentity.GOONSWARM_YELLOW,
            SovereigntyVisualIdentity.colorFor(SovereigntyVisualIdentity.GOONSWARM_ALLIANCE_ID, "Renamed Goons"),
        )
        assertEquals(
            SovereigntyVisualIdentity.FRATERNITY_BLUE,
            SovereigntyVisualIdentity.colorFor(SovereigntyVisualIdentity.FRATERNITY_ALLIANCE_ID, "Renamed Fraternity"),
        )
    }

    @Test
    fun `unknown and unclaimed sentinels use neutral fallback`() {
        assertEquals(SovereigntyVisualIdentity.UNKNOWN_GRAY, SovereigntyVisualIdentity.colorFor(null, "Unknown / Unclaimed"))
        assertEquals(SovereigntyVisualIdentity.UNKNOWN_GRAY, SovereigntyVisualIdentity.colorFor(null, "unclaimed"))
        assertNotEquals(SovereigntyVisualIdentity.UNKNOWN_GRAY, SovereigntyVisualIdentity.colorFor(99_100_001, "Unknown Alliance"))
    }

    @Test
    fun `alliance emblem reference is stable across rename and uses official image service`() {
        val oldReference = SovereigntyVisualIdentity.emblemReference(99_100_001, "Old Alliance Name")
        val renamedReference = SovereigntyVisualIdentity.emblemReference(99_100_001, "New Alliance Name")

        assertEquals(oldReference, renamedReference)
        assertEquals("eve-alliance:99100001", oldReference?.key)
        assertEquals(
            "https://images.evetech.net/alliances/99100001/logo?size=256",
            oldReference?.url,
        )
        val metadata = SovereigntyVisualIdentity.presentationMetadata(99_100_001, "Alliance")
        assertTrue(metadata.contains(";presentation-emblem-key:eve-alliance:99100001"))
        assertTrue(metadata.contains(";presentation-emblem-url:${oldReference?.url}"))
    }

    @Test
    fun `unknown unclaimed and legacy owners never expose remote emblem metadata`() {
        assertNull(SovereigntyVisualIdentity.emblemReference(null, "Unknown / Unclaimed"))
        assertNull(SovereigntyVisualIdentity.emblemReference(null, "Legacy Alliance"))
        assertNull(SovereigntyVisualIdentity.emblemReference(99_100_001, "Unclaimed"))
        assertTrue("presentation-emblem" !in SovereigntyVisualIdentity.presentationMetadata(null, "Unknown / Unclaimed"))
    }

    private fun parseRgb(color: String): Rgb {
        val rgb = color.removePrefix("#").drop(2).toInt(16)
        return Rgb((rgb shr 16 and 0xFF) / 255.0, (rgb shr 8 and 0xFF) / 255.0, (rgb and 0xFF) / 255.0)
    }

    private data class Rgb(val red: Double, val green: Double, val blue: Double) {
        fun toHsl(): Hsl {
            val maximum = max(red, max(green, blue))
            val minimum = min(red, min(green, blue))
            val delta = maximum - minimum
            val lightness = (maximum + minimum) / 2.0
            val saturation = if (delta == 0.0) 0.0 else delta / (1.0 - abs(2.0 * lightness - 1.0))
            return Hsl(saturation, lightness)
        }
    }

    private data class Hsl(val saturation: Double, val lightness: Double)
}
