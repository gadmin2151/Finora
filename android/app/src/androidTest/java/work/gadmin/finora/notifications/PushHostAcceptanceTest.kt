package work.gadmin.finora.notifications

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.data.*
import work.gadmin.finora.ui.PaymentSelectionNotice

/** Synthetic local identity only. No token generation, network or real notifications. */
@RunWith(AndroidJUnit4::class)
class PushHostAcceptanceTest {
    @get:Rule val compose = createComposeRule()
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun persistedGateIsFencedAfterProcessRecreationAndLogout() {
        val sessions = PushSessionStore(context)
        val installation = PushInstallationStore(context)
        try {
            val generation = sessions.begin("https://synthetic.invalid", "synthetic-user", true)
            sessions.workspace("https://synthetic.invalid", "synthetic-user", "synthetic-org")
            val scope =
                PushScope(
                    "https://synthetic.invalid",
                    "synthetic-user",
                    generation,
                    "synthetic-org",
                )
            installation.enable(scope)
            assertEquals(scope, PushInstallationStore(context).scope())
            assertTrue(PushSessionStore(context).active(scope))
            assertFalse(sessions.active(scope.copy(organizationId = "other")))
            sessions.invalidate()
            assertFalse(PushSessionStore(context).active(scope))
            assertTrue(PushSessionStore(context).tokenDeletionRequired())
            val fresh = sessions.begin(scope.server, scope.userId, true)
            assertNotEquals(generation, fresh)
            assertFalse(sessions.active(scope))
        } finally {
            installation.disable()
            sessions.invalidate()
            sessions.tokenDeleted()
        }
    }

    @Test
    fun permissionIsRequestedOnlyAfterExplicitTap() {
        var requests = 0
        var settings = 0
        compose.setContent {
            MaterialTheme {
                PushSettingsUi(
                    PushState.PERMISSION_DENIED,
                    true,
                    {},
                    { requests++ },
                    { settings++ },
                    {},
                )
            }
        }
        compose.runOnIdle {
            assertEquals(0, requests)
            assertEquals(0, settings)
        }
        compose.onNodeWithText("Разрешить уведомления").performClick()
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithText("Настройки Android").performClick()
        compose.runOnIdle { assertEquals(1, settings) }
    }

    @Test
    fun unconfiguredServerCannotEnablePushOrRequestPermission() {
        compose.setContent {
            MaterialTheme { PushSettingsUi(PushState.UNCONFIGURED, false, {}, {}, {}, {}) }
        }
        compose.onNodeWithText("Разрешить уведомления").assertDoesNotExist()
        compose.onNode(isToggleable()).assertIsNotEnabled()
    }

    @Test
    fun notificationBillSelectionDisplaysReadOnlyTemplate() {
        val id = "11111111-1111-4111-8111-111111111111"
        val template =
            PaymentTemplate(
                id,
                "Synthetic bill",
                123,
                "MDL",
                start_date = "2026-10-10",
                recurrence = "monthly",
                active = true,
            )
        compose.setContent {
            MaterialTheme {
                PaymentSelectionNotice(
                    NativeEntitySelection.Selected(NavigationEntity.BILL, id),
                    listOf(template),
                )
            }
        }
        compose.onNodeWithTag("payment-native-selection").assertExists()
        compose.onNodeWithText("Synthetic bill", substring = true).assertExists()
        compose.onNodeWithText("Оплатить").assertDoesNotExist()
        compose.onNodeWithText("Подтвердить").assertDoesNotExist()
    }
}
