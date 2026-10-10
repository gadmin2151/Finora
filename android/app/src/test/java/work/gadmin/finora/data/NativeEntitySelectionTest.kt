package work.gadmin.finora.data

import org.junit.Assert.*
import org.junit.Test

class NativeEntitySelectionTest {
    private val scope = NavigationScope("org", "2026-10", "user@server")
    private val id = "e522c84a-ced9-4820-9d90-c21da2b63547"

    @Test
    fun onlyRealLoadedScopedIdsCanBeSelected() {
        for ((route, entity) in
            listOf(
                NativeRoute.ACCOUNTS to NavigationEntity.ACCOUNT,
                NativeRoute.TRANSACTIONS to NavigationEntity.TRANSACTION,
                NativeRoute.BILLS to NavigationEntity.BILL,
            )) {
            val intent = NativeNavigationIntent(route, scope, mapOf(entity to id))
            assertEquals(
                NativeEntitySelection.Loading,
                NativeEntitySelection.resolve(intent, scope, false, emptySet()),
            )
            assertEquals(
                NativeEntitySelection.NotFound,
                NativeEntitySelection.resolve(intent, scope, true, emptySet()),
            )
            assertEquals(
                NativeEntitySelection.Selected(entity, id),
                NativeEntitySelection.resolve(intent, scope, true, setOf(id)),
            )
            assertEquals(
                NativeEntitySelection.StaleScope,
                NativeEntitySelection.resolve(
                    intent,
                    scope.copy(sessionKey = "other"),
                    true,
                    setOf(id),
                ),
            )
        }
    }

    @Test
    fun wrongEntityTypeDoesNotOpenAWriteEditorOrGuessAnId() {
        val intent =
            NativeNavigationIntent(NativeRoute.ACCOUNTS, scope, mapOf(NavigationEntity.BILL to id))
        assertEquals(
            NativeEntitySelection.Unsupported,
            NativeEntitySelection.resolve(intent, scope, true, setOf(id)),
        )
        assertEquals(
            NativeEntitySelection.NoSelection,
            NativeEntitySelection.resolve(
                intent.copy(entities = emptyMap()),
                scope,
                true,
                setOf(id),
            ),
        )
    }
}
