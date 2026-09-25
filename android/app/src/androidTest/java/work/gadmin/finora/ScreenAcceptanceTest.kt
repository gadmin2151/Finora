package work.gadmin.finora

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Compose navigation with a private, disposable server account; screenshots are test
 * artifacts.
 */
@RunWith(AndroidJUnit4::class)
class ScreenAcceptanceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun screenshot(name: String) {
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        File(rule.activity.filesDir, "acceptance-$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun loginOrganizationCaptureDraftHistoryAndOverview() {
        val credentialsFile = File(rule.activity.filesDir, "acceptance-credentials.json")
        assumeTrue(credentialsFile.isFile)
        val credentials = Json.parseToJsonElement(credentialsFile.readText()).jsonObject
        val vm = ViewModelProvider(rule.activity)[FinoraViewModel::class.java]
        rule.waitUntil(15_000) { !vm.state.value.starting }
        if (vm.state.value.user != null) {
            rule.runOnIdle { vm.logout() }
            rule.waitUntil(15_000) { vm.state.value.user == null && !vm.state.value.busy }
        }
        rule.onNodeWithText("Войти в Finora").assertIsDisplayed()
        screenshot("login")
        rule
            .onNode(hasSetTextAction() and hasText("Логин"))
            .performTextReplacement(credentials.getValue("username").jsonPrimitive.content)
        rule
            .onNode(hasSetTextAction() and hasText("Пароль"))
            .performTextReplacement(credentials.getValue("password").jsonPrimitive.content)
        rule.onNodeWithText("Войти", useUnmergedTree = true).performScrollTo().performClick()
        rule.waitUntil(30_000) { vm.state.value.user != null }
        val org = requireNotNull(vm.state.value.user).organizations.first()
        assertTrue(org.name.startsWith("Android QA "))
        screenshot("organizations")
        rule.onNodeWithText(org.name).performClick()
        rule.waitUntil(30_000) {
            !vm.state.value.workspaceLoading && vm.state.value.organization != null
        }
        rule.onNodeWithText("Сканировать QR").assertIsDisplayed()
        screenshot("capture")

        val fixture = File(rule.activity.cacheDir, "screen-test.jpg")
        val bitmap = Bitmap.createBitmap(900, 1600, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint =
                Paint().apply {
                    color = Color.BLACK
                    textSize = 44f
                }
            drawText("FINORA ANDROID UI TEST", 40f, 140f, paint)
            drawText("NOT A FISCAL RECEIPT", 40f, 240f, paint)
            drawText("1 PRODUCT 5.00 MDL", 40f, 350f, paint)
            drawText("TOTAL 5.00 MDL", 40f, 500f, paint)
        }
        fixture.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        rule.runOnIdle { vm.addPhotos(listOf(Uri.fromFile(fixture), Uri.fromFile(fixture))) }
        rule.waitUntil(20_000) { !vm.state.value.busy && vm.state.value.draft.photos.size == 2 }
        screenshot("draft")
        val original = vm.state.value.draft.photos
        rule.runOnIdle { vm.movePhoto(1, -1) }
        rule.waitUntil {
            !vm.state.value.busy && vm.state.value.draft.photos == original.reversed()
        }
        rule.onNodeWithText("Отправить чек").performScrollTo().performClick()
        rule.waitUntil(60_000) {
            !vm.state.value.busy && vm.state.value.detail != null && !vm.state.value.detailLoading
        }
        assertEquals(2, requireNotNull(vm.state.value.detail).files.size)
        screenshot("receipt")
        rule.runOnIdle {
            vm.closeDetail()
            vm.navigate(Page.RECEIPTS)
        }
        rule.waitUntil(20_000) { !vm.state.value.receiptsLoading }
        rule.onNodeWithText("Ваши чеки").assertIsDisplayed()
        screenshot("history")
        rule.onNodeWithText("Обзор").performClick()
        rule.waitUntil(20_000) {
            !vm.state.value.dashboardLoading && vm.state.value.dashboard != null
        }
        screenshot("overview")
        rule.onNodeWithText("Профиль").performClick()
        rule.onNodeWithText("Защищённое подключение").assertIsDisplayed()
        screenshot("profile")
        rule.runOnIdle { vm.logout() }
        rule.waitUntil(15_000) { vm.state.value.user == null }
        fixture.delete()
    }
}
