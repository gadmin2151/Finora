package work.gadmin.finora.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PoliciesTest {
    @Test
    fun serverDefaultsToHttpsAndNormalizesOrigin() {
        assertEquals(DEFAULT_SERVER, serverOrigin("finora.gadmin.work"))
        assertEquals(DEFAULT_SERVER, serverOrigin(" https://FINORA.GADMIN.WORK:443/ "))
        assertEquals(
            "https://finance.example.com:8443",
            serverOrigin("https://finance.example.com:8443"),
        )
    }

    @Test
    fun rejectsCleartextAndOtherSchemes() {
        listOf(
                "http://finora.gadmin.work",
                "ftp://server",
                "file:///etc/passwd",
                "javascript:alert(1)",
            )
            .forEach {
                assertThrows(IllegalArgumentException::class.java) { serverOrigin(it) }
            }
    }

    @Test
    fun serverRejectsCredentialsPathsAndParameters() {
        listOf(
                "https://user:secret@example.com",
                "https://example.com/api",
                "https://example.com?key=x",
                "https://example.com/#overview",
                "https://",
                "https://example.com:70000",
            )
            .forEach {
                assertThrows(IllegalArgumentException::class.java) { serverOrigin(it) }
            }
    }

    @Test
    fun normalizesSupportedMevLanguagesToSameReceipt() {
        listOf("", "ro/", "ru/", "en/").forEach {
            assertEquals(
                "https://mev.sfs.md/receipt-verifier/0123456789abcdef",
                mevLink("https://mev.sfs.md/${it}receipt-verifier/0123456789abcdef/"),
            )
        }
    }

    @Test
    fun normalizesPrintedSiftReceiptLink() {
        val id = "0123456789ABCDEF0123456789ABCDEF"
        assertEquals(
            "https://mev.sfs.md/receipt-verifier/$id",
            mevLink("https://sift-mev.sfs.md/receipt/$id"),
        )
        listOf(
                "http://sift-mev.sfs.md/receipt/$id",
                "https://sift-mev.sfs.md.evil.test/receipt/$id",
                "https://sift-mev.sfs.md/receipt/$id?url=https://evil.test",
                "https://sift-mev.sfs.md:444/receipt/$id",
                "https://user@sift-mev.sfs.md/receipt/$id",
                "https://sift-mev.sfs.md/receipt/short",
                "https://sift-mev.sfs.md/receipt-verifier/$id",
            )
            .forEach { assertThrows(IllegalArgumentException::class.java) { mevLink(it) } }
    }

    @Test
    fun rejectsUnrelatedQrAndEncodedPaths() {
        listOf(
                "http://mev.sfs.md/receipt-verifier/0123456789abcdef",
                "https://mev.sfs.md.evil.test/receipt-verifier/0123456789abcdef",
                "https://mev.sfs.md@evil.test/receipt-verifier/0123456789abcdef",
                "https://mev.sfs.md/receipt-verifier/short",
                "https://mev.sfs.md:444/receipt-verifier/0123456789abcdef",
                "https://mev.sfs.md/receipt-verifier/0123456789abcdef?x=1",
                "https://mev.sfs.md/receipt-verifier/0123456789abcdef#x",
                "https://mev.sfs.md/receipt-verifier/%2e%2e%2f0123456789abcdef",
            )
            .forEach {
                assertThrows(IllegalArgumentException::class.java) { mevLink(it) }
            }
    }

    @Test
    fun supportsOtherReceiptProvidersAndSegmentedSiftLinks() {
        listOf(
                "https://shop.example/receipt?id=123&key=abc#/view",
                "https://sift-mev.sfs.md/receipt/TEST123/30.00/12345/2026-08-11",
            )
            .forEach { assertEquals(it, receiptLink(it)) }
        val id = "0123456789ABCDEF0123456789ABCDEF"
        assertEquals(
            "https://mev.sfs.md/receipt-verifier/$id",
            receiptLink("https://sift-mev.sfs.md/receipt/$id"),
        )
    }

    @Test
    fun rejectsQrWithoutSecureReceiptUrl() {
        listOf(
                "http://shop.example/receipt",
                "https://user:secret@shop.example/receipt",
                "https://shop.example:8443/receipt",
                "https://shop.example/\\@localhost",
                "https://shop.example/receipt\n123",
                "https://shop.example/" + "x".repeat(1000),
                "not a receipt link",
                "file:///etc/passwd",
            )
            .forEach { assertThrows(IllegalArgumentException::class.java) { receiptLink(it) } }
    }

    @Test
    fun draftsAreIsolatedByServerUserAndOrganization() {
        val keys =
            listOf(
                scopeKey("https://a", "u", "o"),
                scopeKey("https://b", "u", "o"),
                scopeKey("https://a", "v", "o"),
                scopeKey("https://a", "u", "p"),
            )
        assertEquals(4, keys.toSet().size)
        assertTrue(keys.all { it.matches(Regex("[0-9a-f]{64}")) })
        assertEquals(keys[0], scopeKey("https://a", "u", "o"))
    }

    @Test
    fun decimalAmountsDoNotLosePrecision() {
        assertTrue(money(23508).contains("235,08"))
        assertTrue(money(-1, "EUR").contains("-0,01"))
        assertTrue(money(10_000_000_000_001L).endsWith(",01 MDL"))
    }

    @Test
    fun actualApiReceiptShapeDecodesNullableAndDecimalFields() {
        val data =
            """{"id":"r","source":"photo","merchant":"","status":"review","version":2,"created_at":"2026-09-25T12:09:00Z","total_minor":23508,"purchased_on":null,"transaction_id":null,"files":[],"warnings":[],"items":[{"id":"i","name":"PORTOCALE","quantity":"1.312","unit":"kg","unit_price_minor":3399,"total_minor":4459,"category_id":null}],"new_server_field":true}"""
        val receipt = Json { ignoreUnknownKeys = true }.decodeFromString<Receipt>(data)
        assertEquals(23508L, receipt.total_minor)
        assertEquals("1.312", receipt.items.single().quantity)
        assertFalse(receipt.isProcessing)
        assertEquals("Чек без названия", receipt.title)
    }
}
