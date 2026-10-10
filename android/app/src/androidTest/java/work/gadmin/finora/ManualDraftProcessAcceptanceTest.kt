package work.gadmin.finora

import android.os.Build
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.data.*

/** Separate write/read instrumentation runs exercise an actual process boundary on our emulator. */
@RunWith(AndroidJUnit4::class)
class ManualDraftProcessAcceptanceTest {
    @Test
    fun exactIncompleteDraftAndRequestKeySurviveProcessRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(Build.MODEL.startsWith("sdk_gphone"))
        assumeTrue(!File(context.noBackupFilesDir, "session.enc").exists())
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString("qaPhase") ?: "roundtrip"
        val run = arguments.getString("qaRun") ?: UUID.randomUUID().toString()
        require(run.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        require(phase in listOf("write", "read", "roundtrip"))
        val scope = scopeKey("https://synthetic.invalid", "qa-process-only", run)
        val directory = File(context.noBackupFilesDir, "drafts/$scope")
        val store = ManualReceiptDraftStore(context, scope)
        val pid = File(directory, "qa-process-pid")
        val expected =
            ManualReceiptDraft(
                "59ac32d9-1473-4c03-9ff7-a47c4fb57192",
                ReceiptForm.manual(emptyList())
                    .copy(
                        merchant = "Synthetic Process Boundary",
                        items =
                            listOf(
                                ReceiptLineForm(
                                    key = "synthetic-line",
                                    name = "Incomplete item",
                                    quantity = "1,",
                                    unitPrice = "",
                                    total = "",
                                )
                            ),
                    ),
            )
        var preserve = false
        try {
            if (phase != "read") {
                assertNull(store.read())
                store.save(expected)
                pid.writeText(Process.myPid().toString())
            } else {
                assertNotEquals(pid.readText().toInt(), Process.myPid())
            }
            assertEquals(expected, ManualReceiptDraftStore(context, scope).read())
            assertNull(
                ManualReceiptDraftStore(
                        context,
                        scopeKey("https://synthetic.invalid", "other-user", run),
                    )
                    .read()
            )
            assertNull(
                ManualReceiptDraftStore(
                        context,
                        scopeKey("https://synthetic.invalid", "qa-process-only", "other-$run"),
                    )
                    .read()
            )
            preserve = phase == "write"
        } finally {
            if (!preserve) directory.deleteRecursively()
            File(
                    context.noBackupFilesDir,
                    "drafts/${scopeKey("https://synthetic.invalid", "other-user", run)}",
                )
                .deleteRecursively()
            File(
                    context.noBackupFilesDir,
                    "drafts/${scopeKey("https://synthetic.invalid", "qa-process-only", "other-$run")}",
                )
                .deleteRecursively()
        }
    }
}
