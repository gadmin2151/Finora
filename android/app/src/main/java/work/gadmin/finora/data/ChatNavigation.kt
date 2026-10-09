package work.gadmin.finora.data

/** Only server catalogue routes; never treat a model response as a URL. */
enum class ChatDestination(val route: String, val native: Boolean = false) {
    OVERVIEW("overview", true),
    INCOME("income", true),
    DEBTS("debts", true),
    RECEIPTS("receipts", true),
    SETTINGS("settings"),
    ASSISTANT("assistant", true),
    ORGANIZATIONS("organizations", true),
    ACCOUNTS("accounts"),
    TRANSACTIONS("transactions"),
    PURCHASES("purchases"),
    REPORTS("reports"),
    INSIGHTS("insights"),
    BUDGETS("budgets"),
    BILLS("bills"),
    USERS("users");

    fun available(admin: Boolean, serverAdmin: Boolean): Boolean =
        when (this) {
            ACCOUNTS,
            DEBTS,
            BUDGETS,
            BILLS -> admin
            USERS -> serverAdmin
            else -> true
        }
}

fun ChatMessage.destination(admin: Boolean, serverAdmin: Boolean): ChatDestination? =
    if (role != "assistant" || details.error) null
    else
        ChatDestination.entries.firstOrNull {
            it.route == details.navigate_to && it.available(admin, serverAdmin)
        }

fun ChatMessage.linkedReceipt(): String? =
    details.open_receipt_id?.takeIf {
        role == "assistant" &&
            !details.error &&
            it != receipt_id &&
            Regex(
                    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
                    RegexOption.IGNORE_CASE,
                )
                .matches(it)
    }
