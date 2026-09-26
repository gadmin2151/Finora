package work.gadmin.finora.localization

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import work.gadmin.finora.data.incomeRecurrences
import work.gadmin.finora.data.unitLabel
import work.gadmin.finora.ui.ThemeMode

class LocalizationTest {
    private val previous = LanguageRuntime.language

    @After
    fun restoreLanguage() {
        LanguageRuntime.language = previous
    }

    @Test
    fun followsPrimaryPhoneLanguageWithEnglishFallback() {
        listOf("ru", "ru-MD", "ru-RU").forEach {
            assertEquals(AppLanguage.RUSSIAN, LanguagePreference.SYSTEM.resolve(it))
        }
        listOf("en", "en-US", "ro-MD", "de", "", "fr-CA").forEach {
            assertEquals(AppLanguage.ENGLISH, LanguagePreference.SYSTEM.resolve(it))
        }
        assertEquals(AppLanguage.ENGLISH, LanguagePreference.ENGLISH.resolve("ru"))
        assertEquals(AppLanguage.RUSSIAN, LanguagePreference.RUSSIAN.resolve("en"))
        assertEquals(LanguagePreference.SYSTEM, LanguagePreference.fromStored("unsupported"))
    }

    @Test
    fun everyMessageHasBothLanguagesAndMatchingArguments() {
        val placeholder = Regex("%([1-9][0-9]*)\\\$s")
        Message.entries.forEach { message ->
            assertTrue(message.name, message.english.isNotBlank())
            assertTrue(message.name, message.russian.isNotBlank())
            assertFalse(message.name, Regex("[А-Яа-яЁё]").containsMatchIn(message.english))
            fun arguments(value: String) =
                placeholder.findAll(value).map { it.groupValues[1] }.toSet()
            assertEquals(message.name, arguments(message.english), arguments(message.russian))
            val count = arguments(message.english).map(String::toInt).maxOrNull() ?: 0
            val values = Array<Any?>(count) { "User data %1\$s / Мария / & < >" }
            for (language in AppLanguage.entries) {
                val rendered = message.render(language, *values)
                assertTrue(message.name, rendered.isNotBlank())
                if (count > 0) assertTrue(message.name, rendered.contains(values[0].toString()))
            }
        }
    }

    @Test
    fun receiptUnitsTranslateOnlyForDisplay() {
        val stored = "шт"
        assertEquals("pcs", unitLabel(stored, AppLanguage.ENGLISH))
        assertEquals("шт", unitLabel(stored, AppLanguage.RUSSIAN))
        assertEquals("custom", unitLabel("custom", AppLanguage.ENGLISH))
        assertEquals("шт", stored)
    }

    @Test
    fun fixedChoiceLabelsUpdateWithoutRestartOrChangingStableValues() {
        LanguageRuntime.language = AppLanguage.ENGLISH
        assertEquals("Light", ThemeMode.LIGHT.label)
        assertEquals("Monthly", incomeRecurrences["monthly"])
        val keys = incomeRecurrences.keys
        LanguageRuntime.language = AppLanguage.RUSSIAN
        assertEquals("Светлая", ThemeMode.LIGHT.label)
        assertEquals("Каждый месяц", incomeRecurrences["monthly"])
        assertEquals(keys, incomeRecurrences.keys)
    }
}
