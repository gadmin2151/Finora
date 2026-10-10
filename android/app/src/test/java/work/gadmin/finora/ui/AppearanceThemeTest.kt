package work.gadmin.finora.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import work.gadmin.finora.localization.AppLanguage
import work.gadmin.finora.localization.LanguageRuntime

class AppearanceThemeTest {
    private val previousLanguage = LanguageRuntime.language

    @After
    fun restoreLanguage() {
        LanguageRuntime.language = previousLanguage
    }

    @Test
    fun existingStoredValuesRetainTheirMeaningAndBlackRoundTrips() {
        assertEquals(ThemeMode.DARK, ThemeMode.fromStored("DARK"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromStored("LIGHT"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored("SYSTEM"))
        assertEquals(ThemeMode.BLACK, ThemeMode.fromStored(ThemeMode.BLACK.name))
        listOf(null, "", "unsupported").forEach {
            assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored(it))
        }
        assertEquals(Color(0xFF101715), paletteFor(ThemeMode.fromStored("DARK")).paper)
        assertEquals(Color.White, paletteFor(ThemeMode.fromStored("LIGHT")).surfaceColor)
    }

    @Test
    fun systemFollowsWebClassicAndWhiteMappingAndBarIconBrightness() {
        assertEquals(ThemeMode.DARK, ThemeMode.SYSTEM.resolve(true))
        assertEquals(ThemeMode.LIGHT, ThemeMode.SYSTEM.resolve(false))
        for (systemDark in listOf(false, true)) {
            assertTrue(ThemeMode.DARK.isDark(systemDark))
            assertTrue(ThemeMode.BLACK.isDark(systemDark))
            assertFalse(ThemeMode.LIGHT.isDark(systemDark))
            assertEquals(ThemeMode.BLACK, ThemeMode.BLACK.resolve(systemDark))
        }
        assertEquals(paletteFor(ThemeMode.DARK), paletteFor(ThemeMode.SYSTEM, true))
        assertEquals(paletteFor(ThemeMode.LIGHT), paletteFor(ThemeMode.SYSTEM, false))
    }

    @Test
    fun blackUsesWebNeutralSurfacesAndMintAccent() {
        val black = paletteFor(ThemeMode.BLACK)
        assertEquals(Color(0xFF18191B), black.paper)
        assertEquals(Color(0xFF242529), black.surfaceColor)
        assertEquals(Color(0xFF2A2B30), black.surfaceRaised)
        assertEquals(Color(0xFFA0E4C5), black.green)
        assertEquals(Color(0xFF2B2D32), black.heroStart)
        assertNotEquals(paletteFor(ThemeMode.DARK).surfaceColor, black.surfaceColor)
    }

    @Test
    fun textAndPrimaryControlsMeetNormalTextContrastAcrossPalettes() {
        fun contrast(a: Color, b: Color): Double {
            val l1 = a.luminance().toDouble()
            val l2 = b.luminance().toDouble()
            return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
        }
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.BLACK)) {
            val p = paletteFor(mode)
            for (surface in listOf(p.paper, p.surfaceColor, p.surfaceRaised)) {
                assertTrue("$mode body text", contrast(p.ink, surface) >= 4.5)
                assertTrue("$mode secondary text", contrast(p.muted, surface) >= 4.5)
            }
            assertTrue("$mode primary button", contrast(p.onPrimary, p.green) >= 4.5)
            assertTrue("$mode hero", contrast(p.heroInk, p.heroStart) >= 4.5)
        }
    }

    @Test
    fun labelsFollowLanguageWithoutChangingPersistenceKeys() {
        LanguageRuntime.language = AppLanguage.ENGLISH
        assertEquals(
            listOf("Classic", "White", "Material Black", "System"),
            listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.BLACK, ThemeMode.SYSTEM).map {
                it.label
            },
        )
        LanguageRuntime.language = AppLanguage.RUSSIAN
        assertEquals("Классическая", ThemeMode.DARK.label)
        assertEquals("Белая", ThemeMode.LIGHT.label)
        assertEquals("Material Black", ThemeMode.BLACK.label)
        assertEquals("DARK", ThemeMode.DARK.name)
        assertEquals("LIGHT", ThemeMode.LIGHT.name)
    }
}
