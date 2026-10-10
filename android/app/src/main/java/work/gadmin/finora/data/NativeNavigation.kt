package work.gadmin.finora.data

/** Read-only navigation catalogue. Navigation never represents a financial command. */
enum class NativeRoute(
    val page: String,
    val admin: Boolean = false,
    val serverAdmin: Boolean = false,
) {
    OVERVIEW("overview"),
    ACCOUNTS("accounts", admin = true),
    INCOME("income"),
    TRANSACTIONS("transactions"),
    DEBTS("debts", admin = true),
    RECEIPTS("receipts"),
    PURCHASES("purchases"),
    BUDGETS("budgets", admin = true),
    BILLS("bills", admin = true),
    REPORTS("reports"),
    INSIGHTS("insights"),
    ASSISTANT("assistant"),
    ORGANIZATIONS("organizations"),
    USERS("users", serverAdmin = true),
    SETTINGS("settings");

    val webSettings: Boolean
        get() = this == SETTINGS || this == USERS

    companion object {
        fun parse(raw: String?): NativeRoute? = entries.firstOrNull { it.page == raw }
    }
}

data class NavigationScope(
    val organizationId: String,
    val month: String,
    val sessionKey: String = "",
)

data class NavigationAccess(val admin: Boolean, val serverAdmin: Boolean)

enum class NavigationEntity {
    RECEIPT,
    ACCOUNT,
    TRANSACTION,
    DEBT,
    BILL,
    BUDGET,
    CATEGORY,
}

data class NativeNavigationIntent(
    val route: NativeRoute,
    val scope: NavigationScope,
    val entities: Map<NavigationEntity, String> = emptyMap(),
)

/** The scope is captured when the button is rendered, then checked again on the tap. */
data class NativeNavigationRequest(
    val route: String?,
    val scope: NavigationScope,
    val entities: Map<NavigationEntity, String> = emptyMap(),
    val assistantMessage: Boolean = true,
    val error: Boolean = false,
    val expiresAtMillis: Long? = null,
)

enum class NavigationRejection {
    NO_EXPLICIT_TAP,
    UNKNOWN_ROUTE,
    INVALID_SCOPE,
    STALE_SCOPE,
    INVALID_ENTITY,
    WRONG_ENTITY,
    FORBIDDEN,
    INVALID_MESSAGE,
    EXPIRED,
}

sealed interface NativeNavigationResolution {
    data class Open(val intent: NativeNavigationIntent) : NativeNavigationResolution

    data class WebSettings(val intent: NativeNavigationIntent) : NativeNavigationResolution

    data class Unavailable(val intent: NativeNavigationIntent) : NativeNavigationResolution

    data class Rejected(val reason: NavigationRejection) : NativeNavigationResolution
}

object NativeNavigationResolver {
    // Exact destinations that exist before the financial-screen integration. No aliases to
    // overview/receipts: an unrelated screen must never look like a successful navigation.
    val existingRoutes =
        setOf(
            NativeRoute.OVERVIEW,
            NativeRoute.INCOME,
            NativeRoute.DEBTS,
            NativeRoute.RECEIPTS,
            NativeRoute.ASSISTANT,
            NativeRoute.ORGANIZATIONS,
        )
    private val month = Regex("^[0-9]{4}-(0[1-9]|1[0-2])$")
    private val uuid =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun resolve(
        request: NativeNavigationRequest,
        current: NavigationScope,
        access: NavigationAccess,
        explicitTap: Boolean,
        nowMillis: Long,
        available: Set<NativeRoute> = existingRoutes,
    ): NativeNavigationResolution {
        fun reject(reason: NavigationRejection) = NativeNavigationResolution.Rejected(reason)
        if (!explicitTap) return reject(NavigationRejection.NO_EXPLICIT_TAP)
        if (!request.assistantMessage || request.error)
            return reject(NavigationRejection.INVALID_MESSAGE)
        val route =
            NativeRoute.parse(request.route) ?: return reject(NavigationRejection.UNKNOWN_ROUTE)
        if (
            request.scope.organizationId.isBlank() ||
                !month.matches(request.scope.month) ||
                current.organizationId.isBlank() ||
                !month.matches(current.month)
        )
            return reject(NavigationRejection.INVALID_SCOPE)
        if (request.scope != current) return reject(NavigationRejection.STALE_SCOPE)
        if (request.expiresAtMillis?.let { nowMillis >= it } == true)
            return reject(NavigationRejection.EXPIRED)
        if ((route.admin && !access.admin) || (route.serverAdmin && !access.serverAdmin))
            return reject(NavigationRejection.FORBIDDEN)
        if (request.entities.values.any { !uuid.matches(it) })
            return reject(NavigationRejection.INVALID_ENTITY)
        val allowed =
            when (route) {
                NativeRoute.RECEIPTS -> setOf(NavigationEntity.RECEIPT)
                NativeRoute.ACCOUNTS -> setOf(NavigationEntity.ACCOUNT)
                NativeRoute.TRANSACTIONS,
                NativeRoute.INCOME -> setOf(NavigationEntity.TRANSACTION, NavigationEntity.ACCOUNT)
                NativeRoute.DEBTS -> setOf(NavigationEntity.DEBT, NavigationEntity.ACCOUNT)
                NativeRoute.BILLS -> setOf(NavigationEntity.BILL, NavigationEntity.ACCOUNT)
                NativeRoute.BUDGETS -> setOf(NavigationEntity.BUDGET, NavigationEntity.CATEGORY)
                NativeRoute.PURCHASES -> setOf(NavigationEntity.CATEGORY, NavigationEntity.RECEIPT)
                else -> emptySet()
            }
        if (!allowed.containsAll(request.entities.keys))
            return reject(NavigationRejection.WRONG_ENTITY)
        val intent = NativeNavigationIntent(route, request.scope, request.entities.toMap())
        return when {
            route.webSettings -> NativeNavigationResolution.WebSettings(intent)
            route in available -> NativeNavigationResolution.Open(intent)
            else -> NativeNavigationResolution.Unavailable(intent)
        }
    }
}

/** Immutable bounded Back state; switching workspace/month invalidates prior destinations. */
data class NativeNavigationHistory(val entries: List<NativeNavigationIntent> = emptyList()) {
    fun push(
        origin: NativeNavigationIntent,
        target: NativeNavigationIntent,
    ): NativeNavigationHistory =
        if (origin == target) this
        else
            NativeNavigationHistory(
                (entries.filter { it.scope == target.scope } +
                        listOfNotNull(origin.takeIf { it.scope == target.scope }))
                    .takeLast(32)
            )

    fun back(
        scope: NavigationScope,
        access: NavigationAccess,
    ): Pair<NativeNavigationIntent?, NativeNavigationHistory> {
        val safe = entries.filter {
            it.scope == scope &&
                (!it.route.admin || access.admin) &&
                (!it.route.serverAdmin || access.serverAdmin)
        }
        return safe.lastOrNull() to NativeNavigationHistory(safe.dropLast(1))
    }
}
