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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.ui.CameraScreen
import work.gadmin.finora.ui.FinoraTheme

@RunWith(AndroidJUnit4::class)
class CameraAcceptanceTest {
    @get:Rule(order = 0) val languageRule = RussianUiLanguageRule()

    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun cameraBindsCapturesAndProducesReadablePhoto() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.CAMERA,
        )
        val captured = mutableStateOf<File?>(null)
        val error = mutableStateOf<String?>(null)
        val mode = mutableStateOf(CameraMode.QR)
        rule.runOnUiThread {
            rule.activity.setContent {
                FinoraTheme {
                    CameraScreen(
                        mode.value,
                        false,
                        0,
                        {},
                        { captured.value = it },
                        {},
                        error.value,
                        { error.value = it },
                    )
                }
            }
        }
        rule.waitUntil(30_000) {
            error.value != null ||
                rule.onAllNodes(hasText("QR-код чека")).fetchSemanticsNodes().isNotEmpty()
        }
        assertNull(error.value)
        rule.runOnIdle { mode.value = CameraMode.PHOTO }
        rule.waitUntil(30_000) {
            error.value != null ||
                rule
                    .onAllNodes(hasContentDescription("Снять чек") and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
        assertNull(error.value)
        rule.onNodeWithContentDescription("Снять чек").performClick()
        rule.waitUntil(30_000) { captured.value != null || error.value != null }
        assertNull(error.value)
        val photo = requireNotNull(captured.value)
        val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photo.path, dimensions)
        assertTrue(dimensions.outWidth >= 640 && dimensions.outHeight >= 480)
        assertTrue(photo.length() > 0)
        photo.delete()
    }
}
