package work.gadmin.finora

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.runner.RunWith
import work.gadmin.finora.data.*
import work.gadmin.finora.localization.AppLanguage
import work.gadmin.finora.localization.LanguageRuntime
import work.gadmin.finora.ui.ChatNavigation
import work.gadmin.finora.ui.FinoraTheme

@RunWith(AndroidJUnit4::class)
class ChatNavigationAcceptanceTest {
    @get:Rule val rule = createComposeRule()
    private var language = AppLanguage.ENGLISH

    @Before
    fun english() {
        language = LanguageRuntime.language
        LanguageRuntime.language = AppLanguage.ENGLISH
    }

    @After
    fun restoreLanguage() {
        LanguageRuntime.language = language
    }

    @Test
    fun viewingSharedHistoryDoesNotNavigateButExplicitTapOpensNativeSection() {
        var opened: ChatDestination? = null
        rule.setContent {
            FinoraTheme {
                Surface {
                    Column {
                        ChatNavigation(
                            ChatMessage(
                                "old-history",
                                "assistant",
                                "Open income",
                                "2025-01-01",
                                details =
                                    ChatDetails(
                                        navigate_to = "income",
                                        actor_id = "different-requester",
                                    ),
                            ),
                            AppState(starting = false),
                            { opened = it },
                            { Assert.fail("Unexpected receipt") },
                        )
                    }
                }
            }
        }
        rule.onNodeWithText("Open Income").assertIsDisplayed()
        rule.runOnIdle { Assert.assertNull(opened) }
        rule.onNodeWithText("Open Income").performClick()
        rule.runOnIdle { Assert.assertEquals(ChatDestination.INCOME, opened) }
    }

    @Test
    fun memberCannotOpenAdminOnlyRouteFromAResponse() {
        rule.setContent {
            FinoraTheme {
                Surface {
                    Column {
                        ChatNavigation(
                            ChatMessage(
                                "test",
                                "assistant",
                                "Open budgets",
                                "2026-10-09",
                                details = ChatDetails(navigate_to = "budgets"),
                            ),
                            AppState(starting = false),
                            { Assert.fail("Unexpected navigation") },
                            { Assert.fail("Unexpected receipt") },
                        )
                    }
                }
            }
        }
        rule.onNodeWithText("Open budgets on the website").assertDoesNotExist()
    }

    @Test
    fun webOnlySectionOffersExplicitWebsiteActionWithoutOpeningAnotherNativePage() {
        var opened: ChatDestination? = null
        rule.setContent {
            FinoraTheme {
                Surface {
                    Column {
                        ChatNavigation(
                            ChatMessage(
                                "test",
                                "assistant",
                                "Open budgets",
                                "2026-10-09",
                                details = ChatDetails(navigate_to = "budgets"),
                            ),
                            AppState(
                                starting = false,
                                organization = Organization("synthetic", "Test", "admin"),
                            ),
                            { opened = it },
                            { Assert.fail("Unexpected receipt") },
                        )
                    }
                }
            }
        }
        rule.onNodeWithText("Open budgets on the website").assertIsDisplayed()
        rule.runOnIdle { Assert.assertNull(opened) }
        rule.onNodeWithText("Open budgets on the website").performClick()
        rule.runOnIdle {
            Assert.assertEquals(ChatDestination.BUDGETS, opened)
            Assert.assertFalse(requireNotNull(opened).native)
        }
    }
}
