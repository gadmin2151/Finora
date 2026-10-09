package work.gadmin.finora

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import org.junit.*
import org.junit.runner.RunWith
import work.gadmin.finora.data.*
import work.gadmin.finora.localization.AppLanguage
import work.gadmin.finora.localization.LanguageRuntime
import work.gadmin.finora.ui.ChatActions
import work.gadmin.finora.ui.FinoraTheme

/** Exact-value confirmation only; the callback never contacts an API. */
@RunWith(AndroidJUnit4::class)
class ChatActionsAcceptanceTest {
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

    private val action =
        ChatAction(
            "POST /transactions",
            "{}",
            """{"amount":"25.50","currency":"MDL","account_id":"test-account"}""",
            "Synthetic income",
            "Add reviewed income",
        )

    private fun proposal() =
        ChatMessage(
            "synthetic",
            "assistant",
            "Review",
            Instant.now().toString(),
            details =
                ChatDetails(
                    actions = listOf(action),
                    actor_id = "requester",
                    action_status = "pending",
                ),
        )

    private fun state() =
        AppState(
            starting = false,
            user = User("requester", "test", "Test", "offline"),
            accounts = listOf(Account("test-account", "Synthetic cash", "MDL")),
        )

    @Test
    fun inspectingProposalDoesNotExecuteUntilSeparateExactValueConfirmation() {
        val message = proposal()
        val decisions = mutableListOf<Boolean>()
        rule.setContent {
            FinoraTheme {
                Surface {
                    Column {
                        ChatActions(message, state()) { reviewed, confirm ->
                            Assert.assertTrue(message.sameProposal(reviewed))
                            decisions.add(confirm)
                        }
                    }
                }
            }
        }
        rule.onNodeWithText("25.50").assertIsDisplayed()
        rule.onNodeWithText("Synthetic cash (test-account)").assertIsDisplayed()
        rule.onNodeWithTag("ai-action-review").performClick()
        rule.runOnIdle { Assert.assertTrue(decisions.isEmpty()) }
        rule.onNodeWithText("Execute these actions?").assertIsDisplayed()
        rule.onNodeWithTag("ai-action-confirm").performClick()
        rule.runOnIdle { Assert.assertEquals(listOf(true), decisions) }
    }

    @Test
    fun changedProposalCannotBeApprovedFromAnOpenConfirmation() {
        var message by mutableStateOf(proposal())
        var executed = false
        rule.setContent {
            FinoraTheme {
                Surface {
                    Column {
                        ChatActions(message, state()) { _, _ -> executed = true }
                    }
                }
            }
        }
        rule.onNodeWithTag("ai-action-review").performClick()
        rule.runOnIdle {
            message =
                message.copy(
                    details =
                        message.details.copy(
                            actions = listOf(action.copy(body_json = "{\"amount\":\"250.00\"}"))
                        )
                )
        }
        rule.onNodeWithTag("ai-action-confirm").assertIsNotEnabled()
        rule.runOnIdle { Assert.assertFalse(executed) }
    }

    @Test
    fun otherSharedChatReaderHasNoExecutionOrCancellationControls() {
        rule.setContent {
            FinoraTheme {
                Surface {
                    Column {
                        ChatActions(
                            proposal(),
                            state().copy(user = User("other", "other", "Other", "offline")),
                        ) { _, _ ->
                            Assert.fail("Unexpected action")
                        }
                    }
                }
            }
        }
        rule.onNodeWithTag("ai-action-review").assertDoesNotExist()
        rule.onNodeWithText("Cancel proposal").assertDoesNotExist()
        rule
            .onNodeWithText("Only the person who requested this proposal can execute it.")
            .assertExists()
    }
}
