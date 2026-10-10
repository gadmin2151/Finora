package work.gadmin.finora

import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.runner.RunWith
import work.gadmin.finora.data.*
import work.gadmin.finora.localization.AppLanguage
import work.gadmin.finora.localization.LanguageRuntime
import work.gadmin.finora.ui.FinoraTheme
import work.gadmin.finora.ui.NativeChatNavigation

@RunWith(AndroidJUnit4::class)
class NativeNavigationAcceptanceTest {
    @get:Rule val rule = createComposeRule()
    private var language = AppLanguage.ENGLISH
    private val state =
        AppState(
            starting = false,
            month = "2026-10",
            server = "https://example.test",
            user = User("user-a", "user", "User", "csrf"),
            organization = Organization("org-a", "Test", "admin"),
        )

    @Before
    fun english() {
        language = LanguageRuntime.language
        LanguageRuntime.language = AppLanguage.ENGLISH
    }

    @After
    fun restore() {
        LanguageRuntime.language = language
    }

    @Test
    fun historicalFinancialNavigationRequiresTapAndCarriesExactScope() {
        var request: NativeNavigationRequest? = null
        rule.setContent {
            FinoraTheme {
                Surface {
                    NativeChatNavigation(
                        ChatMessage(
                            "history",
                            "assistant",
                            "Open budgets",
                            "2025-01-01",
                            details =
                                ChatDetails(navigate_to = "budgets", actor_id = "another-user"),
                        ),
                        state,
                    ) {
                        request = it
                    }
                }
            }
        }
        rule.onNodeWithTag("native-nav-budgets").assertIsDisplayed()
        rule.onNodeWithText("Open budgets on the website").assertDoesNotExist()
        rule.runOnIdle { Assert.assertNull(request) }
        rule.onNodeWithTag("native-nav-budgets").performClick()
        rule.runOnIdle {
            Assert.assertEquals("budgets", request?.route)
            Assert.assertEquals(
                NavigationScope("org-a", "2026-10", "user-a@https://example.test"),
                request?.scope,
            )
        }
    }

    @Test
    fun nativeReceiptButtonPreservesIdAndDoesNothingUntilTapped() {
        val id = "e522c84a-ced9-4820-9d90-c21da2b63547"
        var request: NativeNavigationRequest? = null
        rule.setContent {
            FinoraTheme {
                Surface {
                    NativeChatNavigation(
                        ChatMessage(
                            "history",
                            "assistant",
                            "Receipt",
                            "2025-01-01",
                            details = ChatDetails(open_receipt_id = id),
                        ),
                        state,
                    ) {
                        request = it
                    }
                }
            }
        }
        rule.runOnIdle { Assert.assertNull(request) }
        rule.onNodeWithTag("native-nav-receipt").performClick()
        rule.runOnIdle { Assert.assertEquals(id, request?.entities?.get(NavigationEntity.RECEIPT)) }
    }

    @Test
    fun memberNeverSeesAdminFinancialNavigationButton() {
        rule.setContent {
            FinoraTheme {
                Surface {
                    NativeChatNavigation(
                        ChatMessage(
                            "history",
                            "assistant",
                            "Open accounts",
                            "2026-10-10",
                            details = ChatDetails(navigate_to = "accounts"),
                        ),
                        state.copy(organization = Organization("org-a", "Test", "member")),
                    ) {
                        Assert.fail("Unexpected navigation")
                    }
                }
            }
        }
        rule.onNodeWithTag("native-nav-accounts").assertDoesNotExist()
    }
}
