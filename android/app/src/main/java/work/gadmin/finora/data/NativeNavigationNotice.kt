package work.gadmin.finora.data

import work.gadmin.finora.localization.AppLanguage
import work.gadmin.finora.localization.LanguageRuntime

fun nativeNavigationUnavailable(route: NativeRoute): String =
    if (LanguageRuntime.language == AppLanguage.RUSSIAN)
        "Раздел «${route.page}» пока недоступен в приложении."
    else "The ${route.page} section is not yet available in the app."
