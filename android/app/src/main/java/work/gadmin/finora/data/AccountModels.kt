package work.gadmin.finora.data

import kotlinx.serialization.Serializable

@Serializable
data class AccountLifecycleModel(
    val id: String,
    val name: String,
    val currency: String,
    val kind: String,
    val color: String,
    val opening_minor: Long,
    val balance_minor: Long,
    val archived: Boolean,
)

fun accountText(ru: String, en: String): String =
    if (
        work.gadmin.finora.localization.LanguageRuntime.language ==
            work.gadmin.finora.localization.AppLanguage.RUSSIAN
    )
        ru
    else en
