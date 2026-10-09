package work.gadmin.finora.data

import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ChatActionPreviewTest {
    private val now = Instant.parse("2026-10-09T23:00:00Z")
    private val action =
        ChatAction(
            "POST /transactions",
            "{}",
            """{"amount":"25.50","account_id":"synthetic-account","currency":"MDL","note":null,"active":false,"splits":[{"amount":"25.50"}]}""",
            "Income",
            "Add explicit income",
        )

    private fun message() =
        ChatMessage(
            "proposal",
            "assistant",
            "Review",
            now.toString(),
            details =
                ChatDetails(
                    actions = listOf(action),
                    actor_id = "requester",
                    action_status = "pending",
                ),
        )

    @Test
    fun typedProposalsAndResultsDecodeWithoutChangingLegacyMessages() {
        val json = Json { ignoreUnknownKeys = true }
        val result =
            json.decodeFromString<ChatMessage>(
                """{"id":"p","role":"assistant","text":"Review","created_at":"2026-10-09T23:00:00Z","details":{"actions":[{"operation":"POST /transactions","body_json":"{\"amount\":\"25.50\"}","label":"Income","description":"Review exact values"}],"actor_id":"requester","action_status":"completed","action_results":[{"label":"Income","status":200,"detail":"Done"}]}}"""
            )
        assertEquals("POST /transactions", result.details.actions?.single()?.operation)
        assertEquals(200, result.details.action_results?.single()?.status)
        assertFalse(result.ownsPendingActions("requester"))
        assertTrue(
            ChatMessage("old", "user", "Hello", now.toString()).details.actions.isNullOrEmpty()
        )
    }

    @Test
    fun previewPreservesExactDecimalsNullsBooleansNestedValuesAndEmptyContainers() {
        val fields =
            requireNotNull(action.preview()).body.associate {
                it.path.joinToString(".") to it.value
            }
        assertEquals("25.50", fields["amount"]?.content)
        assertTrue(fields.containsKey("note"))
        assertNull(fields["note"])
        assertEquals("false", fields["active"]?.content)
        assertFalse(requireNotNull(fields["active"]).isString)
        assertEquals("25.50", fields["splits.1.amount"]?.content)
        assertEquals("{}", requireNotNull(action.preview()).parameters.single().value?.content)
        assertEquals(
            "[]",
            action.copy(body_json = """{"items":[]}""").preview()?.body?.single()?.value?.content,
        )
    }

    @Test
    fun undecodableValuesAndUnexpectedMethodsCannotBeConfirmed() {
        for (changed in
            listOf(
                action.copy(body_json = "broken"),
                action.copy(body_json = "[]"),
                action.copy(body_json = "null"),
                action.copy(parameters_json = "bad"),
                action.copy(body_json = "x".repeat(12001)),
                action.copy(operation = "GET /transactions"),
                action.copy(operation = "POST https://outside.invalid"),
                action.copy(body_json = "{\"x\":".repeat(17) + "1" + "}".repeat(17)),
            )) {
            assertNull(changed.preview())
            assertFalse(
                message()
                    .copy(details = message().details.copy(actions = listOf(changed)))
                    .canConfirmActions("requester", now)
            )
        }
    }

    @Test
    fun onlyOriginatingRequesterCanConfirmRecentPendingAssistantActions() {
        val pending = message()
        assertTrue(pending.canConfirmActions("requester", now))
        assertFalse(pending.canConfirmActions("other-user", now))
        assertFalse(pending.canConfirmActions(null, now))
        assertFalse(pending.copy(role = "user").canConfirmActions("requester", now))
        for (status in
            listOf("completed", "cancelled", "failed", "executing", "uncertain")) assertFalse(
            pending
                .copy(details = pending.details.copy(action_status = status))
                .canConfirmActions("requester", now)
        )
        assertTrue(pending.canConfirmActions("requester", now.plusSeconds(1800)))
        assertFalse(pending.canConfirmActions("requester", now.plusSeconds(1801)))
        assertTrue(
            pending.copy(created_at = "2026-10-09T23:00:00").canConfirmActions("requester", now)
        )
        assertFalse(pending.copy(created_at = "unknown").canConfirmActions("requester", now))
        assertFalse(
            pending
                .copy(details = pending.details.copy(actions = List(7) { action }))
                .canConfirmActions("requester", now)
        )
    }

    @Test
    fun confirmationSnapshotCannotSilentlyApproveChangedTargetsOrAmounts() {
        val original = message()
        assertTrue(original.sameProposal(original.copy(text = "Updated commentary")))
        assertFalse(original.sameProposal(original.copy(id = "different")))
        assertFalse(
            original.sameProposal(
                original.copy(details = original.details.copy(actor_id = "other"))
            )
        )
        assertFalse(
            original.sameProposal(
                original.copy(
                    details =
                        original.details.copy(
                            actions = listOf(action.copy(body_json = "{\"amount\":\"250.00\"}"))
                        )
                )
            )
        )
        assertFalse(
            original.sameProposal(
                original.copy(
                    details =
                        original.details.copy(
                            actions =
                                listOf(
                                    action.copy(parameters_json = "{\"key\":\"different-target\"}")
                                )
                        )
                )
            )
        )
    }
}
