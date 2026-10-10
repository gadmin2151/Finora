package work.gadmin.finora.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/** Invalid requests are rejected before HTTP. The origin is deliberately nonresolving. */
class PushApiPathTest {
    private val client = ApiClient("https://finora.invalid")

    @Test
    fun pushHelpersCannotAddressMoneyEndpointsOrEncodedTraversal() {
        val bad =
            listOf(
                "/api/transactions",
                "/api/push/../transactions",
                "/api/push/%2e%2e/transactions",
                "/api/push/devices?token=private",
                "/api/push/devices#x",
                "/api/push/\\transactions",
            )
        for (path in bad) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { client.pushGet(path, null) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { client.pushWrite(path, null, "POST") }
            }
        }
    }

    @Test
    fun pushWritesRejectUnexpectedHttpMethodsBeforeDispatch() {
        for (method in listOf("GET", "PATCH", "TRACE", "POST\nInjected: true")) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { client.pushWrite("/api/push/devices", null, method) }
            }
        }
    }
}
