package io.github.kvmy666.autoexpand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage for the uxres palette maths.
 *
 * The XML produced here is written **verbatim** into
 * `/data/oplus/uxres/uxcolor/ux_custom_color{,_night}.xml` and parsed by the OEM's resource
 * loader, so the shape assertions below are the contract with that parser — not cosmetics.
 *
 * Pure JVM: no Robolectric, no Android.
 */
class CouiPaletteTest {

    /** The colour this was reverse-engineered against (`accent_color":"FFF13871"`). */
    private val pink = 0xFFF13871.toInt()

    @Test
    fun `parse accepts 6- and 8-digit hex, with or without hash`() {
        assertEquals(pink, CouiPalette.parseArgb("f13871"))
        assertEquals(pink, CouiPalette.parseArgb("#F13871"))
        assertEquals(pink, CouiPalette.parseArgb("FFF13871"))
        assertEquals(pink, CouiPalette.parseArgb("  #fff13871 "))
        assertEquals(0xFF212DAF.toInt(), CouiPalette.parseArgb("FF212DAF"))
    }

    @Test
    fun `parse rejects anything that is not a colour`() {
        assertNull(CouiPalette.parseArgb(""))
        assertNull(CouiPalette.parseArgb("#12345"))
        assertNull(CouiPalette.parseArgb("#gggggg"))
        assertNull(CouiPalette.parseArgb("null"))
    }

    @Test
    fun `normal slot is the opaque accent, duplicated as an NXcolor twin`() {
        val day = CouiPalette.slots(night = false, accent = pink)
        assertEquals(12, day.size)                                  // 6 slots × (plain + NX)
        assertEquals(6, day.keys.count { it.startsWith("NXcolor") })
        assertEquals(pink, day["couiSingleFirstNormal"])
        assertEquals(pink, day["NXcolorSingleFirstNormal"])
    }

    @Test
    fun `light and highlight slots carry the OEM alphas, and keep the accent RGB`() {
        val day = CouiPalette.slots(night = false, accent = pink)
        assertEquals(0x4C, day.getValue("couiSingleFirstLightNormal") ushr 24)
        assertEquals(0x4C, day.getValue("couiSingleFirstLightPressed") ushr 24)
        assertEquals(0x26, day.getValue("couiSingleFirstTextHighLight") ushr 24)
        assertEquals(0x26, day.getValue("couiSingleFirstBarDisabledColor") ushr 24)
        assertEquals(0x00F13871, day.getValue("couiSingleFirstLightNormal") and 0x00FFFFFF)

        val night = CouiPalette.slots(night = true, accent = pink)
        assertEquals(0x66, night.getValue("couiSingleFirstLightNormal") ushr 24)
        assertEquals(0x4C, night.getValue("couiSingleFirstLightPressed") ushr 24)
    }

    @Test
    fun `pressed is a darker shade of the accent in both stores`() {
        val day = CouiPalette.slots(night = false, accent = pink)
            .getValue("couiSingleFirstPressed")
        val night = CouiPalette.slots(night = true, accent = pink)
            .getValue("couiSingleFirstPressed")
        for (pressed in listOf(day, night)) {
            assertEquals(0xFF, pressed ushr 24)
            assertTrue((pressed ushr 16) and 0xFF < (pink ushr 16) and 0xFF)
            assertTrue((pressed ushr 8) and 0xFF < (pink ushr 8) and 0xFF)
            assertTrue(pressed and 0xFF < pink and 0xFF)
        }
        // The day store is shaded harder than the night store.
        assertTrue((day ushr 16) and 0xFF < (night ushr 16) and 0xFF)
    }

    @Test
    fun `xml matches the OEM stub shape and has no trailing newline`() {
        val xml = CouiPalette.xml(night = false, accent = pink)
        val lines = xml.split("\n")
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\" ?>", lines.first())
        assertEquals("<resources>", lines[1])
        assertEquals("</resources>", lines.last())
        assertEquals(15, lines.size)                                // header + open + 12 + close
        assertTrue(xml.contains("<color name=\"couiSingleFirstNormal\">#FFF13871</color>"))
        assertTrue(xml.contains("<color name=\"NXcolorSingleFirstBarDisabledColor\">#26F13871</color>"))
        assertTrue(!xml.endsWith("\n"))
    }

    @Test
    fun `shade clamps at full and zero brightness, keeping the alpha channel`() {
        assertEquals(0xFF000000.toInt(), CouiPalette.shade(0xFFFFFFFF.toInt(), 0.0))
        assertEquals(0x80FFFFFF.toInt(), CouiPalette.shade(0x80FFFFFF.toInt(), 2.0))
        assertEquals(0xFF404040.toInt(), CouiPalette.shade(0xFF808080.toInt(), 0.5))
    }

    @Test
    fun `format is uppercase ARGB, locale-independent`() {
        assertEquals("#FFF13871", CouiPalette.format(pink))
        assertEquals("#FF212DAF", CouiPalette.format(0xFF212DAF.toInt()))
        assertEquals("#26F13871", CouiPalette.format(CouiPalette.withAlpha(pink, 0x26)))
    }
}
