package app.nadaka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class A11yTest {
    @Test fun everyHighContrastPaletteIsAtLeast7to1() {
        Palette.entries.filter { it.highContrast }.forEach {
            val r = contrast(it.fg, it.bg)
            assertTrue("${it.label} is only %.1f:1".format(r), r >= 7.0)
        }
    }

    @Test fun standardViewTextIsAtLeast7to1() {
        val card = 0xFF101820.toInt()
        listOf(0xFFFFFFFF.toInt(), 0xFFFFB300.toInt(), 0xFFAEB7C2.toInt()).forEach {
            assertTrue(contrast(it, card) >= 7.0)
        }
    }

    @Test fun lowVisionPresetIsOneSwitchAndVisibleToCaregivers() {
        Prefs.applyLowVision(true)
        assertTrue(Prefs.lowVision)
        assertEquals(Palette.YELLOW_ON_BLACK, Prefs.palette)
        assertTrue(Prefs.status().startsWith("Low vision ON"))
        Prefs.applyLowVision(false)
        assertEquals("", Prefs.status())
    }
}
