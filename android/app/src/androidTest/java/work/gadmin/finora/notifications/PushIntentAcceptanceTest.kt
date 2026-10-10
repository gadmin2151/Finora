package work.gadmin.finora.notifications

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PushIntentAcceptanceTest {
    private val event = "11111111-1111-4111-8111-111111111111"
    private val scope = PushScope("https://finora.example", "user", "generation", "org")

    private fun tap() =
        Intent(PushNotifications.ACTION)
            .putExtra("finora_push_event", event)
            .putExtra("finora_push_scope", PushNotifications.scopeDigest(scope))

    @Test
    fun coldStartTapIsConsumedOnce() {
        val intent = tap()
        assertEquals(event, PushNotifications.consumeTap(intent, scope))
        assertNull(PushNotifications.consumeTap(intent, scope))
        assertFalse(intent.hasExtra("finora_push_event"))
    }

    @Test
    fun foregroundTapUsesSameConsumptionPath() {
        assertEquals(event, PushNotifications.consumeTap(tap(), scope))
    }

    @Test
    fun oldLoginOrOrganizationCannotConsumeTap() {
        assertNull(PushNotifications.consumeTap(tap(), scope.copy(sessionKey = "new-generation")))
        assertNull(PushNotifications.consumeTap(tap(), scope.copy(organizationId = "other")))
        assertNull(PushNotifications.consumeTap(tap(), scope.copy(userId = "other")))
        assertNull(
            PushNotifications.consumeTap(tap(), scope.copy(server = "https://other.example"))
        )
    }

    @Test
    fun arbitraryIntentCannotNavigate() {
        assertNull(
            PushNotifications.consumeTap(
                Intent("android.intent.action.VIEW").putExtra("finora_push_event", event),
                scope,
            )
        )
    }

    private fun allOrganizationReceiptTap() =
        Intent(PushNotifications.ACTION)
            .putExtra("finora_push_event", event)
            .putExtra("finora_push_scope", PushNotifications.receiptIdentityDigest(scope))
            .putExtra("finora_push_receipt_all_organizations", true)
            .putExtra("finora_push_category", "receipts")

    @Test
    fun receiptColdChooserTapSurvivesDifferentOrganizationOnce() {
        val intent = allOrganizationReceiptTap()
        assertTrue(PushNotifications.isAllOrganizationReceiptTap(intent))
        assertEquals(
            event,
            PushNotifications.consumeTap(intent, scope.copy(organizationId = "chooser-org"), true),
        )
        assertNull(
            PushNotifications.consumeTap(intent, scope.copy(organizationId = "chooser-org"), true)
        )
        assertFalse(intent.hasExtra("finora_push_receipt_all_organizations"))
        assertFalse(intent.hasExtra("finora_push_category"))
    }

    @Test
    fun receiptIdentityTapRequiresCurrentOptInAndSameLogin() {
        assertNull(PushNotifications.consumeTap(allOrganizationReceiptTap(), scope, false))
        assertNull(
            PushNotifications.consumeTap(
                allOrganizationReceiptTap(),
                scope.copy(sessionKey = "new-login"),
                true,
            )
        )
        assertNull(
            PushNotifications.consumeTap(
                allOrganizationReceiptTap(),
                scope.copy(userId = "other"),
                true,
            )
        )
    }

    @Test
    fun updaterAndPaymentCannotUseReceiptIdentityMode() {
        for (category in listOf("update_available", "payments")) {
            val intent = allOrganizationReceiptTap().putExtra("finora_push_category", category)
            assertFalse(PushNotifications.isAllOrganizationReceiptTap(intent))
            assertNull(
                PushNotifications.consumeTap(intent, scope.copy(organizationId = "other"), true)
            )
        }
    }
}
