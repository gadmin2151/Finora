package work.gadmin.finora

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import work.gadmin.finora.ui.mevReceiptCleanup

@RunWith(AndroidJUnit4::class)
class ReceiptPageCleanupTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun evaluate(view: WebView, script: String): String {
        val done = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync {
            view.evaluateJavascript(script) {
                result = it
                done.countDown()
            }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        return result
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun withPage(host: String, run: (WebView) -> Unit) {
        val loaded = CountDownLatch(1)
        lateinit var view: WebView
        instrumentation.runOnMainSync {
            view = WebView(instrumentation.targetContext)
            view.settings.javaScriptEnabled = true
            view.settings.blockNetworkLoads = true
            view.webViewClient =
                object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        loaded.countDown()
                    }
                }
            view.loadDataWithBaseURL(
                "https://$host/receipt-verifier/test",
                """<html><body>
                <div id="receipt" data-v-7ba5bd90>TOTAL LEI 235.08</div>
                <div id="unrelated" class="fixed p-4">Other content</div>
                <div id="promotion" class="p-4 fixed" data-v-7ba5bd90>Promotion</div>
                <button class="mud-cookie-reopen mud-cookie-reopen--left">Cookies</button>
                </body></html>""",
                "text/html",
                "UTF-8",
                null,
            )
        }
        try {
            assertTrue(loaded.await(10, TimeUnit.SECONDS))
            run(view)
        } finally {
            instrumentation.runOnMainSync { view.destroy() }
        }
    }

    @Test
    fun removesOnlyMevOverlaysIncludingLateWidgets() =
        withPage("mev.sfs.md") { view ->
            assertEquals("true", evaluate(view, mevReceiptCleanup))
            assertEquals("null", evaluate(view, "document.getElementById('promotion')"))
            assertEquals(
                "0",
                evaluate(view, "document.querySelectorAll('.mud-cookie-reopen').length"),
            )
            assertEquals(
                "\"TOTAL LEI 235.08\"",
                evaluate(view, "document.getElementById('receipt').innerText"),
            )
            assertEquals(
                "\"Other content\"",
                evaluate(view, "document.getElementById('unrelated').innerText"),
            )
            evaluate(
                view,
                """document.body.insertAdjacentHTML('beforeend', '<div class="fixed p-4" id="late"><a href="https://moldovaeuropeana.md/">Promotion</a></div><button class="mud-cookie-reopen">Cookies</button>')""",
            )
            assertEquals("null", evaluate(view, "document.getElementById('late')"))
            assertEquals(
                "0",
                evaluate(view, "document.querySelectorAll('.mud-cookie-reopen').length"),
            )
            assertEquals("true", evaluate(view, mevReceiptCleanup))
            assertEquals(
                "1",
                evaluate(view, "document.querySelectorAll('#finora-mev-receipt-style').length"),
            )
        }

    @Test
    fun leavesOtherReceiptProvidersUnchanged() =
        withPage("shop.example") { view ->
            val before = evaluate(view, "document.documentElement.outerHTML")
            assertEquals("false", evaluate(view, mevReceiptCleanup))
            assertEquals(before, evaluate(view, "document.documentElement.outerHTML"))
        }
}
