package work.gadmin.finora

import android.Manifest
import android.graphics.BitmapFactory
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.ui.CameraScreen
import work.gadmin.finora.ui.FinoraTheme

@RunWith(AndroidJUnit4::class)
class LongReceiptScreenTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun cameraFramesReachScrollableReviewAndPrivateDraftPhoto() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeFalse(
            "Use a disposable emulator without a saved user session",
            File(context.noBackupFilesDir, "session.enc").exists(),
        )
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.CAMERA,
        )
        val photo = mutableStateOf<File?>(null)
        val error = mutableStateOf<String?>(null)
        rule.runOnUiThread {
            rule.activity.setContent {
                FinoraTheme {
                    CameraScreen(
                        CameraMode.LONG_RECEIPT,
                        false,
                        0,
                        {},
                        { photo.value = it },
                        {},
                        error.value,
                        { error.value = it },
                    )
                }
            }
        }
        rule.waitUntil(30_000) {
            rule.onAllNodes(hasText("Начать") and isEnabled()).fetchSemanticsNodes().isNotEmpty() ||
                error.value != null
        }
        assertNull(error.value)
        rule.onNodeWithText("Начать").performClick()
        rule.waitUntil(30_000) {
            rule.onAllNodes(hasText("Готово") and isEnabled()).fetchSemanticsNodes().isNotEmpty() ||
                error.value != null
        }
        assertNull(error.value)
        rule.onNodeWithText("Готово").performClick()
        rule.waitUntil(30_000) {
            rule
                .onAllNodes(hasText("Использовать") and isEnabled())
                .fetchSemanticsNodes()
                .isNotEmpty() || error.value != null
        }
        assertNull(error.value)
        rule.onNodeWithText("Ваш длинный чек").assertIsDisplayed()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.filesDir, "long-receipt-review-screen.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        rule.onNodeWithText("Использовать").performClick()
        rule.waitUntil(10_000) { photo.value != null || error.value != null }
        assertNull(error.value)
        val result = requireNotNull(photo.value)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(result.path, bounds)
        assertTrue(bounds.outWidth >= 300 && bounds.outHeight >= 300)
        assertTrue(result.length() > 0)
        result.delete()
    }
}
