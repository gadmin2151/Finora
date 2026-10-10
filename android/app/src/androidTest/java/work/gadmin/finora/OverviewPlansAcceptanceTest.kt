package work.gadmin.finora

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.data.*
import work.gadmin.finora.localization.*
import work.gadmin.finora.ui.*

@RunWith(AndroidJUnit4::class)
class OverviewPlansAcceptanceTest {
    @get:Rule val rule = createComposeRule()
    private val dashboard =
        Dashboard(
            "2026-10",
            0,
            0,
            0,
            planned_remaining_minor = 3900,
            bills =
                listOf(
                    DashboardBill(
                        "fake",
                        "Synthetic EUR bill",
                        200,
                        "EUR",
                        "2026-10-09",
                        "overdue",
                    ),
                    DashboardBill("paid", "Paid bill", 100, "MDL", "2026-10-08", "paid"),
                ),
        )

    @Test
    fun largeTextAndLongPlanKeepNavigationReachableOnNarrowScreen() {
        val context =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = LanguagePreferences(context)
        val original = preferences.read()
        try {
            preferences.save(LanguagePreference.ENGLISH)
            LanguageRuntime.initialize(context)
            rule.setContent {
                val density = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides
                        androidx.compose.ui.unit.Density(density.density, 2f)
                ) {
                    FinoraTheme(darkOverride = true) {
                        androidx.compose.foundation.layout.Column(
                            androidx.compose.ui.Modifier.width(320.dp)
                                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                        ) {
                            OverviewPlansCard(
                                dashboard.copy(
                                    bills =
                                        listOf(
                                            dashboard.bills
                                                .first()
                                                .copy(
                                                    name =
                                                        "Synthetic unusually long original plan name "
                                                            .repeat(4)
                                                )
                                        )
                                ),
                                true,
                                false,
                            ) {}
                        }
                    }
                }
            }
            rule
                .onNodeWithTag("planning-budgets")
                .performScrollTo()
                .assertIsDisplayed()
                .assertIsEnabled()
            rule
                .onNodeWithTag("planning-bills")
                .performScrollTo()
                .assertIsDisplayed()
                .assertIsEnabled()
        } finally {
            preferences.save(original)
            LanguageRuntime.initialize(context)
        }
    }

    @Test
    fun memberSeesOriginalCurrencyAndOverduePlanWithoutPaymentActions() {
        rule.setContent {
            FinoraTheme {
                Surface {
                    OverviewPlansCard(dashboard, false, false) { fail("Unexpected navigation") }
                }
            }
        }
        rule.onNodeWithText("Synthetic EUR bill").assertIsDisplayed()
        rule.onNodeWithText("Paid bill").assertDoesNotExist()
        rule.onNodeWithText(tr(Message.OVERVIEW_OVERDUE, 1)).assertIsDisplayed()
        rule.onNodeWithTag("planning-bills").assertDoesNotExist()
    }

    @Test
    fun emptyMonthKeepsPlanSeparateAndBusyPreventsNavigation() {
        rule.setContent {
            FinoraTheme {
                Surface {
                    OverviewPlansCard(
                        dashboard.copy(planned_remaining_minor = 0, bills = emptyList()),
                        true,
                        true,
                    ) {
                        fail("Unexpected navigation")
                    }
                }
            }
        }
        rule.onNodeWithText(tr(Message.OVERVIEW_NO_PENDING)).assertIsDisplayed()
        rule.onNodeWithTag("planning-bills").assertIsNotEnabled()
        rule.onNodeWithTag("planning-budgets").assertIsNotEnabled()
    }

    @Test
    fun planLabelsRemainClearInBothLanguagesAndThemes() {
        val context =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = LanguagePreferences(context)
        val original = preferences.read()
        var dark by androidx.compose.runtime.mutableStateOf(false)
        try {
            preferences.save(LanguagePreference.ENGLISH)
            LanguageRuntime.initialize(context)
            rule.setContent {
                FinoraTheme(darkOverride = dark) {
                    Surface { OverviewPlansCard(dashboard, true, false) {} }
                }
            }
            rule.onNodeWithText("Still to pay for selected month").assertIsDisplayed()
            rule.onNodeWithText("Overdue: 1").assertIsDisplayed()
            saveScreenshot("overview-plans-white-en.png")
            rule.runOnIdle {
                dark = true
                preferences.save(LanguagePreference.RUSSIAN)
                LanguageRuntime.initialize(context)
            }
            rule.onNodeWithText("Ещё к оплате за выбранный месяц").assertIsDisplayed()
            rule.onNodeWithText("Просрочено: 1").assertIsDisplayed()
            saveScreenshot("overview-plans-dark-ru.png")
        } finally {
            preferences.save(original)
            LanguageRuntime.initialize(context)
        }
    }

    private fun saveScreenshot(name: String) {
        rule.waitForIdle()
        val instrumentation =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.filesDir, name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
