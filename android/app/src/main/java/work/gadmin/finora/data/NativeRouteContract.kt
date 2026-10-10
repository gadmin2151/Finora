package work.gadmin.finora.data

/** The integrator's native screen contract. Context means the exact server catalogue page. */
enum class NativeScreenTarget {
    OVERVIEW,
    WALLET,
    INSIGHTS,
    INCOME,
    DEBTS,
    RECEIPTS,
    ASSISTANT,
    ORGANIZATIONS,
    BILLS,
    BUDGETS,
    TRANSACTIONS,
    REPORTS,
    PURCHASES,
}

object NativeRouteContract {
    val targets: Map<NativeRoute, NativeScreenTarget> =
        mapOf(
            NativeRoute.OVERVIEW to NativeScreenTarget.OVERVIEW,
            NativeRoute.ACCOUNTS to NativeScreenTarget.WALLET,
            NativeRoute.INSIGHTS to NativeScreenTarget.INSIGHTS,
            NativeRoute.INCOME to NativeScreenTarget.INCOME,
            NativeRoute.DEBTS to NativeScreenTarget.DEBTS,
            NativeRoute.RECEIPTS to NativeScreenTarget.RECEIPTS,
            NativeRoute.ASSISTANT to NativeScreenTarget.ASSISTANT,
            NativeRoute.ORGANIZATIONS to NativeScreenTarget.ORGANIZATIONS,
            NativeRoute.BILLS to NativeScreenTarget.BILLS,
            NativeRoute.BUDGETS to NativeScreenTarget.BUDGETS,
            NativeRoute.TRANSACTIONS to NativeScreenTarget.TRANSACTIONS,
            NativeRoute.REPORTS to NativeScreenTarget.REPORTS,
            NativeRoute.PURCHASES to NativeScreenTarget.PURCHASES,
        )
    val available: Set<NativeRoute>
        get() = targets.keys
}
