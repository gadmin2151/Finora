package work.gadmin.finora.notifications

import org.junit.Assert.*
import org.junit.Test

class PushReceiptRoutingTest {
    private val scope = PushScope("https://finora.example", "user", "session-generation", "org-a")
    private val event = PushEventContext("event", "receipts", "org-b")

    @Test
    fun explicitOptedInMemberReceiptCanSelectExactOrganization() {
        assertEquals(
            "org-b",
            PushReceiptRouting.targetOrganization(
                "event",
                event,
                scope,
                scope,
                setOf("org-b"),
                true,
            ),
        )
    }

    @Test
    fun noOptInOrMembershipNeverSelectsOrganization() {
        assertNull(
            PushReceiptRouting.targetOrganization(
                "event",
                event,
                scope,
                scope,
                setOf("org-b"),
                false,
            )
        )
        assertNull(
            PushReceiptRouting.targetOrganization("event", event, scope, scope, emptySet(), true)
        )
    }

    @Test
    fun onlyAuthenticatedMatchingReceiptEventCanChangeOrganization() {
        assertNull(
            PushReceiptRouting.targetOrganization(
                "other-event",
                event,
                scope,
                scope,
                setOf("org-b"),
                true,
            )
        )
        assertNull(
            PushReceiptRouting.targetOrganization(
                "event",
                event.copy(category = "update_available"),
                scope,
                scope,
                setOf("org-b"),
                true,
            )
        )
    }

    @Test
    fun staleUserServerSessionOrOrganizationRejectsContext() {
        for (current in
            listOf(
                scope.copy(userId = "other"),
                scope.copy(server = "https://other.example"),
                scope.copy(sessionKey = "new-login"),
                scope.copy(organizationId = "org-c"),
            )) {
            assertNull(
                PushReceiptRouting.targetOrganization(
                    "event",
                    event,
                    scope,
                    current,
                    setOf("org-b"),
                    true,
                )
            )
        }
    }

    @Test
    fun settledTargetRetainsOriginalIdentityAndSession() {
        val target = scope.copy(organizationId = "org-b")
        assertTrue(PushReceiptRouting.settled(scope, target, "org-b", true))
        assertFalse(
            PushReceiptRouting.settled(scope, target.copy(sessionKey = "new-login"), "org-b", true)
        )
        assertFalse(PushReceiptRouting.settled(scope, target.copy(userId = "other"), "org-b", true))
        assertFalse(PushReceiptRouting.settled(scope, target, "org-c", true))
        assertFalse(PushReceiptRouting.settled(scope, target, "org-b", false))
    }
}
