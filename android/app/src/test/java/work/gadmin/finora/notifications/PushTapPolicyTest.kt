package work.gadmin.finora.notifications

import org.junit.Assert.*
import org.junit.Test

class PushTapPolicyTest {
    private val scope =
        PushScope("https://finora.example", "user", "login-generation", "receipt-org")

    @Test
    fun optedInReceiptSurvivesOrganizationSwitchAndColdChooser() {
        val digest = PushTapPolicy.receiptIdentityDigest(scope)
        assertTrue(
            PushTapPolicy.accepts(
                digest,
                scope.copy(organizationId = "chooser-org"),
                true,
                "receipts",
                true,
            )
        )
        assertTrue(PushTapPolicy.accepts(digest, scope, true, "receipts", true))
    }

    @Test
    fun legacyNotificationStaysBoundToExactWorkspaceEvenWhenOptedIn() {
        val digest = PushTapPolicy.scopeDigest(scope)
        assertTrue(PushTapPolicy.accepts(digest, scope, false, "receipts", true))
        assertFalse(
            PushTapPolicy.accepts(
                digest,
                scope.copy(organizationId = "other"),
                false,
                "receipts",
                true,
            )
        )
    }

    @Test
    fun revokedOptInCannotAcceptReceiptIdentityMode() {
        assertFalse(
            PushTapPolicy.accepts(
                PushTapPolicy.receiptIdentityDigest(scope),
                scope,
                true,
                "receipts",
                false,
            )
        )
    }

    @Test
    fun logoutAccountAndServerChangesRejectReceiptIdentity() {
        val digest = PushTapPolicy.receiptIdentityDigest(scope)
        for (changed in
            listOf(
                scope.copy(sessionKey = "new-login"),
                scope.copy(userId = "other-user"),
                scope.copy(server = "https://other.example"),
            )) {
            assertFalse(PushTapPolicy.accepts(digest, changed, true, "receipts", true))
        }
    }

    @Test
    fun updatePaymentAndMissingCategoriesNeverUseReceiptIdentityMode() {
        for (category in listOf("update_available", "payments", "important", null)) {
            assertFalse(PushTapPolicy.receiptIdentityMode(category, true))
            assertFalse(
                PushTapPolicy.accepts(
                    PushTapPolicy.receiptIdentityDigest(scope),
                    scope.copy(organizationId = "other"),
                    true,
                    category,
                    true,
                )
            )
        }
    }

    @Test
    fun updateDedupeIsIndependentAcrossUsersAndServers() {
        val key = PushTapPolicy.updateRecordKey(scope)
        assertNotEquals(key, PushTapPolicy.updateRecordKey(scope.copy(userId = "other-user")))
        assertNotEquals(
            key,
            PushTapPolicy.updateRecordKey(scope.copy(server = "https://other.example")),
        )
        assertEquals(
            key,
            PushTapPolicy.updateRecordKey(
                scope.copy(organizationId = "other-org", sessionKey = "new-login")
            ),
        )
    }
}
