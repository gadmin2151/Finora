package work.gadmin.finora

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.ui.AppUpdateContent
import work.gadmin.finora.updates.*

/** Offline fixtures only: never opens Android settings or launches an installer. */
@RunWith(AndroidJUnit4::class)
class AppUpdateAcceptanceTest {
    @get:Rule(order = 0) val language = RussianUiLanguageRule()
    @get:Rule(order = 1) val compose = createComposeRule()
    private val manifest =
        UpdateManifest(
            1,
            UpdatePolicy.PACKAGE_NAME,
            BuildConfig.VERSION_CODE.toLong() + 1,
            "1.17.0",
            26,
            "https://finora.gadmin.work/assets/mobile/finora-1.17.0.apk",
            100000000,
            "a".repeat(64),
            UpdatePolicy.CERTIFICATE,
        )

    @Test
    fun checkAndDownloadRequireSeparateExplicitTaps() {
        var checks = 0
        var downloads = 0
        var installs = 0
        val state = mutableStateOf(UpdateState())
        compose.setContent {
            MaterialTheme {
                AppUpdateContent(
                    state.value,
                    check = { checks++ },
                    download = { downloads++ },
                    cancel = {},
                    install = { installs++ },
                )
            }
        }
        compose.runOnIdle {
            assertEquals(0, checks)
            assertEquals(0, installs)
        }
        compose.onNodeWithText("Проверить обновления").performClick()
        compose.runOnIdle {
            assertEquals(1, checks)
            state.value = UpdateState(UpdatePhase.AVAILABLE, manifest)
        }
        compose.onNodeWithText("Установить обновление").assertDoesNotExist()
        compose.onNodeWithText("Скачать обновление").performClick()
        compose.runOnIdle {
            assertEquals(1, downloads)
            assertEquals(0, installs)
        }
    }

    @Test
    fun verifiedDownloadStillRequiresInstallTapAndShowsPermissionInstructions() {
        var installs = 0
        compose.setContent {
            MaterialTheme {
                AppUpdateContent(
                    UpdateState(UpdatePhase.READY, manifest),
                    check = {},
                    download = {},
                    cancel = {},
                    install = { installs++ },
                )
            }
        }
        compose.runOnIdle { assertEquals(0, installs) }
        compose.onNodeWithText("Если Android откроет разрешение", substring = true).assertExists()
        compose.onNodeWithText("Установить обновление").performClick()
        compose.runOnIdle { assertEquals(1, installs) }
    }

    @Test
    fun downloadShowsProgressAndCanBeCancelled() {
        var cancels = 0
        compose.setContent {
            MaterialTheme {
                AppUpdateContent(
                    UpdateState(UpdatePhase.DOWNLOADING, manifest, 20000000),
                    check = {},
                    download = {},
                    cancel = { cancels++ },
                    install = {},
                )
            }
        }
        compose.onNodeWithText("Скачивание…").assertExists()
        compose.onNodeWithText("Установить обновление").assertDoesNotExist()
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle { assertEquals(1, cancels) }
    }
}
