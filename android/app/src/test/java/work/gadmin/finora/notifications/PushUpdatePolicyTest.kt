package work.gadmin.finora.notifications

import org.junit.Assert.*
import org.junit.Test

class PushUpdatePolicyTest {
    private fun data(code: String = "32", name: String = "1.18.0") =
        mapOf("category" to "update_available", "version_code" to code, "version_name" to name)

    @Test
    fun onlyHigherVersionCanNotify() {
        assertEquals(32L, PushUpdatePolicy.incoming(data(), 31, 0)?.code)
        assertNull(PushUpdatePolicy.incoming(data("31"), 31, 0))
        assertNull(PushUpdatePolicy.incoming(data("30"), 31, 0))
        assertNull(PushUpdatePolicy.incoming(data(), 0, 0))
    }

    @Test
    fun oneNotificationPerNewRelease() {
        assertNull(PushUpdatePolicy.incoming(data(), 31, 32))
        assertNull(PushUpdatePolicy.incoming(data(), 31, 33))
        assertEquals(33L, PushUpdatePolicy.incoming(data("33", "1.19.0"), 31, 32)?.code)
    }

    @Test
    fun malformedVersionsCannotNotify() {
        for (code in listOf("-1", "0", "2147483648", "32.5", "../update")) assertNull(
            PushUpdatePolicy.incoming(data(code), 31, 0)
        )
        assertNull(PushUpdatePolicy.incoming(data(name = "https://evil.example/app.apk"), 31, 0))
    }

    @Test
    fun arbitraryUrlsDoNotEnterReleaseIdentity() {
        assertEquals(
            InstalledPushVersion(32, "1.18.0"),
            PushUpdatePolicy.incoming(
                data() + mapOf("apk_url" to "http://evil.example", "action" to "install"),
                31,
                0,
            ),
        )
    }

    @Test
    fun ordinaryReceiptIsNotUpdate() {
        assertNull(PushUpdatePolicy.incoming(data() + ("category" to "receipts"), 31, 0))
    }
}
