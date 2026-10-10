package work.gadmin.finora.data

sealed interface NativeEntitySelection {
    data object NoSelection : NativeEntitySelection

    data object Loading : NativeEntitySelection

    data object StaleScope : NativeEntitySelection

    data object NotFound : NativeEntitySelection

    data object Unsupported : NativeEntitySelection

    data class Selected(val entity: NavigationEntity, val id: String) : NativeEntitySelection

    companion object {
        /**
         * IDs come only from a scoped native list response. Selection never opens a write editor.
         */
        fun resolve(
            intent: NativeNavigationIntent,
            currentScope: NavigationScope,
            loaded: Boolean,
            actualIds: Set<String>,
        ): NativeEntitySelection {
            if (intent.scope != currentScope) return StaleScope
            if (intent.entities.isEmpty()) return NoSelection
            val entity =
                when (intent.route) {
                    NativeRoute.ACCOUNTS -> NavigationEntity.ACCOUNT
                    NativeRoute.TRANSACTIONS -> NavigationEntity.TRANSACTION
                    NativeRoute.BILLS -> NavigationEntity.BILL
                    else -> return Unsupported
                }
            if (intent.entities.keys != setOf(entity)) return Unsupported
            val id = intent.entities[entity] ?: return Unsupported
            if (!loaded) return Loading
            return if (id in actualIds) Selected(entity, id) else NotFound
        }
    }
}
