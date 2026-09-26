package work.gadmin.finora.localization

import java.util.Locale

enum class AppLanguage(val tag: String) {
    ENGLISH("en"),
    RUSSIAN("ru");

    val locale: Locale
        get() = Locale.forLanguageTag(tag)
}

enum class LanguagePreference {
    SYSTEM,
    ENGLISH,
    RUSSIAN;

    fun resolve(primaryDeviceLanguage: String): AppLanguage =
        when (this) {
            ENGLISH -> AppLanguage.ENGLISH
            RUSSIAN -> AppLanguage.RUSSIAN
            SYSTEM ->
                if (Locale.forLanguageTag(primaryDeviceLanguage).language == "ru")
                    AppLanguage.RUSSIAN
                else AppLanguage.ENGLISH
        }

    companion object {
        fun fromStored(value: String?): LanguagePreference =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}
