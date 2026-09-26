package work.gadmin.finora

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource
import work.gadmin.finora.localization.LanguagePreference
import work.gadmin.finora.localization.LanguagePreferences
import work.gadmin.finora.localization.LanguageRuntime

/**
 * Existing Russian acceptance fixtures opt in explicitly instead of relying on the device locale.
 */
class RussianUiLanguageRule : ExternalResource() {
    private lateinit var preferences: LanguagePreferences
    private var original = LanguagePreference.SYSTEM

    override fun before() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        preferences = LanguagePreferences(context)
        original = preferences.read()
        preferences.save(LanguagePreference.RUSSIAN)
        LanguageRuntime.initialize(context)
    }

    override fun after() {
        preferences.save(original)
        LanguageRuntime.initialize(InstrumentationRegistry.getInstrumentation().targetContext)
    }
}
