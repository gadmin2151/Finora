package work.gadmin.finora.data

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ManualReceiptDraftTest {
    private fun draft() =
        ManualReceiptDraft(
            UUID.randomUUID().toString(),
            ReceiptForm(
                "Incomplete",
                "2026-10-09",
                "MDL",
                "",
                "synthetic-account",
                "1",
                1,
                listOf(ReceiptLineForm(name = "Milk", quantity = "1,", unitPrice = "")),
            ),
        )

    @Test
    fun incompleteDraftRoundTripPreservesInputsAndRequestId() {
        val original = draft()
        assertEquals(original, ManualReceiptDraft.decode(original.encode()))
        assertEquals(
            "1,",
            ManualReceiptDraft.decode(original.encode()).form.items.single().quantity,
        )
    }

    @Test
    fun invalidOrOversizedDraftsAreRetainedAsErrorsInsteadOfFinancialInputs() {
        for (value in
            listOf("bad json", "null", "{}", "x".repeat(ManualReceiptDraft.MAX_SIZE + 1))) {
            assertTrue(runCatching { ManualReceiptDraft.decode(value) }.isFailure)
        }
        val original = draft()
        for (changed in
            listOf(
                original.copy(requestKey = "wrong-key"),
                original.copy(form = original.form.copy(merchant = "x".repeat(201))),
                original.copy(form = original.form.copy(items = emptyList())),
                original.copy(form = original.form.copy(items = List(201) { ReceiptLineForm() })),
            )) assertTrue(runCatching { changed.encode() }.isFailure)
    }

    @Test
    fun localScopeSeparatesServersUsersAndOrganizations() {
        val current = scopeKey("https://one.invalid", "user-1", "org-1")
        assertNotEquals(current, scopeKey("https://two.invalid", "user-1", "org-1"))
        assertNotEquals(current, scopeKey("https://one.invalid", "user-2", "org-1"))
        assertNotEquals(current, scopeKey("https://one.invalid", "user-1", "org-2"))
    }
}
