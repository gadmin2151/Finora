package work.gadmin.finora.data

import org.junit.Assert.*
import org.junit.Test

class ChatNavigationTest {
    private fun message(route: String?) =
        ChatMessage(
            "test",
            "assistant",
            "Open section",
            "2026-10-09",
            details = ChatDetails(navigate_to = route),
        )

    @Test
    fun onlyKnownCataloguePagesResolveAndRolesAreCheckedAgain() {
        assertEquals(ChatDestination.INCOME, message("income").destination(false, false))
        assertEquals(ChatDestination.DEBTS, message("debts").destination(true, false))
        assertNull(message("debts").destination(false, false))
        assertNull(message("users").destination(true, false))
        assertEquals(ChatDestination.USERS, message("users").destination(false, true))
        for (raw in
            listOf(
                null,
                "",
                "INCOME",
                "javascript:alert(1)",
                "https://outside.invalid",
                "../settings",
                "receipts?token=secret",
                "/#income",
            )) assertNull(message(raw).destination(true, true))
        assertNull(message("income").copy(role = "user").destination(true, true))
        assertNull(
            message("income")
                .copy(details = ChatDetails(navigate_to = "income", error = true))
                .destination(true, true)
        )
    }

    @Test
    fun receiptReferencesRemainExplicitScopedLookupsWithoutPathInjectionOrDuplicateLinks() {
        val id = "e522c84a-ced9-4820-9d90-c21da2b63547"
        val source = message(null).copy(details = ChatDetails(open_receipt_id = id))
        assertEquals(id, source.linkedReceipt())
        assertNull(source.copy(receipt_id = id).linkedReceipt())
        assertNull(source.copy(role = "user").linkedReceipt())
        for (raw in
            listOf(
                "",
                "../../users",
                "https://outside.invalid",
                "$id/../settings",
                "$id?x=1",
            )) assertNull(source.copy(details = ChatDetails(open_receipt_id = raw)).linkedReceipt())
    }

    @Test
    fun webOnlyDestinationsCannotSilentlyOpenAnUnrelatedNativeScreen() {
        for (route in
            listOf(
                "transactions",
                "purchases",
                "reports",
                "budgets",
                "bills",
                "accounts",
            )) assertFalse(requireNotNull(message(route).destination(true, true)).native)
        for (route in
            listOf(
                "overview",
                "income",
                "debts",
                "receipts",
                "settings",
                "organizations",
            )) assertTrue(requireNotNull(message(route).destination(true, true)).native)
    }
}
