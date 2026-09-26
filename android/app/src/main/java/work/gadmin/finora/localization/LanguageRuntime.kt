package work.gadmin.finora.localization

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/** Device-only preference, independent of authentication and organization membership. */
class LanguagePreferences(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences("language", Context.MODE_PRIVATE)

    fun read(): LanguagePreference =
        LanguagePreference.fromStored(preferences.getString("preference", null))

    fun save(preference: LanguagePreference) {
        preferences.edit().putString("preference", preference.name).apply()
    }

    fun observe(listener: () -> Unit): () -> Unit {
        val callback = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "preference" || key == null) listener()
        }
        preferences.registerOnSharedPreferenceChangeListener(callback)
        return { preferences.unregisterOnSharedPreferenceChangeListener(callback) }
    }
}

object LanguageRuntime {
    var language by
        mutableStateOf(LanguagePreference.SYSTEM.resolve(Locale.getDefault().toLanguageTag()))
        internal set

    fun initialize(context: Context) {
        language =
            LanguagePreferences(context)
                .read()
                .resolve(context.resources.configuration.locales[0]?.toLanguageTag().orEmpty())
    }
}

@Stable
class LanguageSelection(
    val preference: LanguagePreference,
    val language: AppLanguage,
    val select: (LanguagePreference) -> Unit,
)

val LocalLanguageSelection = staticCompositionLocalOf {
    LanguageSelection(LanguagePreference.SYSTEM, LanguageRuntime.language) {}
}

/** Localized context also translates Compose's built-in date picker and accessibility labels. */
@Composable
fun LanguageEnvironment(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val deviceConfiguration = LocalConfiguration.current
    val preferences = remember(context) { LanguagePreferences(context) }
    var preference by remember(preferences) { mutableStateOf(preferences.read()) }
    DisposableEffect(preferences) {
        val stop = preferences.observe { preference = preferences.read() }
        onDispose(stop)
    }
    val primaryDeviceLanguage = deviceConfiguration.locales[0]?.toLanguageTag().orEmpty()
    val language = preference.resolve(primaryDeviceLanguage)
    SideEffect { LanguageRuntime.language = language }
    val localizedContext =
        remember(context, deviceConfiguration, language) {
            ContextThemeWrapper(context, 0).apply {
                applyOverrideConfiguration(
                    Configuration(deviceConfiguration).apply { setLocale(language.locale) }
                )
            }
        }
    val selection =
        LanguageSelection(preference, language) { choice ->
            preference = choice
            LanguageRuntime.language = choice.resolve(primaryDeviceLanguage)
            preferences.save(choice)
        }
    CompositionLocalProvider(
        LocalLanguageSelection provides selection,
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedContext.resources.configuration,
        content = content,
    )
}
