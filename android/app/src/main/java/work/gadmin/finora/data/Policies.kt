package work.gadmin.finora.data

import java.math.BigDecimal
import java.net.URI
import java.security.MessageDigest
import java.text.NumberFormat
import java.util.Locale
import work.gadmin.finora.localization.AppLanguage
import work.gadmin.finora.localization.LanguageRuntime
import work.gadmin.finora.localization.Message
import work.gadmin.finora.localization.tr

const val DEFAULT_SERVER = "https://finora.gadmin.work"
const val MAX_PHOTOS = 4

fun serverOrigin(input: String): String {
    val normalized = input.trim().let { if ("://" in it) it else "https://$it" }
    val uri =
        try {
            URI(normalized)
        } catch (_: Exception) {
            throw IllegalArgumentException(tr(Message.CHECK_THE_SERVER_ADDRESS))
        }
    require(uri.scheme.equals("https", ignoreCase = true)) {
        tr(Message.ONLY_HTTPS_CONNECTIONS_ARE_SUPPORTED)
    }
    require(
        !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null
    ) {
        tr(Message.ENTER_A_SERVER_ADDRESS_WITHOUT_CREDENTIALS_PARAMETERS_OR_A)
    }
    require(uri.path.isNullOrEmpty() || uri.path == "/") {
        tr(Message.ENTER_ONLY_THE_SERVER_ADDRESS_FOR_EXAMPLE_HTTPS_FINORA_GAD)
    }
    require(uri.port == -1 || uri.port in 1..65535) { tr(Message.INVALID_SERVER_PORT) }
    return URI(
            "https",
            null,
            uri.host.lowercase(Locale.ROOT),
            if (uri.port == 443) -1 else uri.port,
            null,
            null,
            null,
        )
        .toString()
}

fun mevLink(input: String): String {
    val uri =
        try {
            URI(input.trim())
        } catch (_: Exception) {
            throw IllegalArgumentException(
                tr(Message.THIS_QR_CODE_DOES_NOT_CONTAIN_A_MEV_RECEIPT_LINK)
            )
        }
    require(
        uri.scheme == "https" &&
            uri.host in setOf("mev.sfs.md", "sift-mev.sfs.md") &&
            uri.port in listOf(-1, 443) &&
            uri.userInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null
    ) {
        tr(Message.QR_CODE_READ_BUT_IT_IS_NOT_A_MEV_FISCAL_RECEIPT_LINK)
    }
    val match =
        Regex(
                if (uri.host == "sift-mev.sfs.md") "^/receipt/([A-Fa-f0-9]{32})/?$"
                else "^/(?:ro/|ru/|en/)?receipt-verifier/([A-Za-z0-9_-]{16,128})/?$"
            )
            .matchEntire(uri.rawPath ?: "")
            ?: throw IllegalArgumentException(tr(Message.THIS_QR_CODE_IS_NOT_A_MEV_RECEIPT))
    return "https://mev.sfs.md/receipt-verifier/${match.groupValues[1]}"
}

fun receiptLink(input: String): String {
    // Open the actual QR target on the phone; the server canonicalizes its deduplication key.
    val text = input.trim()
    require(text.length <= 1000 && text.none { it.isWhitespace() || it == '\\' }) {
        tr(Message.INVALID_RECEIPT_LINK)
    }
    val uri =
        try {
            URI(text)
        } catch (_: Exception) {
            throw IllegalArgumentException(
                tr(Message.QR_CODE_READ_BUT_IT_CONTAINS_NO_LINK_TAKE_A_PHOTO_OF_THE_R)
            )
        }
    require(
        uri.scheme == "https" &&
            uri.host?.contains('.') == true &&
            uri.userInfo == null &&
            uri.port in listOf(-1, 443)
    ) {
        tr(Message.AN_HTTPS_RECEIPT_LINK_IS_REQUIRED_YOU_CAN_PHOTOGRAPH_THE_P)
    }
    val host = requireNotNull(uri.host).lowercase(Locale.ROOT).trimEnd('.')
    require(
        host != "localhost" &&
            !host.endsWith(".localhost") &&
            !host.endsWith(".local") &&
            !host.endsWith(".internal")
    ) {
        tr(Message.A_PUBLIC_RECEIPT_LINK_IS_REQUIRED)
    }
    if (host.matches(Regex("[0-9.]+"))) {
        val address = java.net.InetAddress.getByName(host)
        require(publicReceiptAddress(address)) { tr(Message.A_PUBLIC_RECEIPT_LINK_IS_REQUIRED) }
    }
    return uri.toASCIIString()
}

fun publicReceiptAddress(address: java.net.InetAddress): Boolean {
    if (
        address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
    )
        return false
    val bytes = address.address.map { it.toInt() and 255 }
    return if (bytes.size == 4) {
        bytes[0] != 0 &&
            bytes[0] < 224 &&
            !(bytes[0] == 100 && bytes[1] in 64..127) &&
            !(bytes[0] == 198 && bytes[1] in 18..19)
    } else bytes[0] and 0xfe != 0xfc
}

fun scopeKey(server: String, user: String, organization: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest("$server\u0000$user\u0000$organization".toByteArray())
        .joinToString("") { "%02x".format(it) }

fun money(minor: Long, currency: String = "MDL"): String =
    NumberFormat.getNumberInstance(LanguageRuntime.language.locale)
        .apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        .format(BigDecimal.valueOf(minor, 2)) + " $currency"

fun statusLabel(status: String): String =
    when (status) {
        "queued" -> tr(Message.QUEUED)
        "processing" -> tr(Message.PROCESSING)
        "posted" -> tr(Message.RECORDED)
        "review",
        "needs_review",
        "ready" -> tr(Message.REVIEW)
        "error",
        "failed" -> tr(Message.NEEDS_ATTENTION)
        "duplicate" -> tr(Message.ALREADY_ADDED)
        else -> tr(Message.REVIEW)
    }

/** Display labels only: the canonical receipt unit is never modified or written back. */
fun unitLabel(unit: String, language: AppLanguage = LanguageRuntime.language): String {
    if (language == AppLanguage.RUSSIAN) return unit
    return when (unit.trim().lowercase(Locale.ROOT)) {
        "шт",
        "шт.",
        "штук" -> "pcs"
        "кг" -> "kg"
        "г",
        "гр" -> "g"
        "л" -> "L"
        "мл" -> "mL"
        "м" -> "m"
        "уп",
        "уп.",
        "упак",
        "упак." -> "pack"
        else -> unit
    }
}
