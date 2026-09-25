package work.gadmin.finora.data

import java.math.BigDecimal
import java.net.URI
import java.security.MessageDigest
import java.text.NumberFormat
import java.util.Locale

const val DEFAULT_SERVER = "https://finora.gadmin.work"
const val MAX_PHOTOS = 4

fun serverOrigin(input: String): String {
    val normalized = input.trim().let { if ("://" in it) it else "https://$it" }
    val uri =
        try {
            URI(normalized)
        } catch (_: Exception) {
            throw IllegalArgumentException("Проверьте адрес сервера")
        }
    require(uri.scheme.equals("https", ignoreCase = true)) {
        "Подключение возможно только по HTTPS"
    }
    require(
        !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null
    ) {
        "Укажите адрес сервера без логина, параметров и ссылки на раздел"
    }
    require(uri.path.isNullOrEmpty() || uri.path == "/") {
        "Укажите только адрес сервера, например https://finora.gadmin.work"
    }
    require(uri.port == -1 || uri.port in 1..65535) { "Некорректный порт сервера" }
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
            throw IllegalArgumentException("QR-код не содержит ссылку чека MEV")
        }
    require(
        uri.scheme == "https" &&
            uri.host == "mev.sfs.md" &&
            uri.port in listOf(-1, 443) &&
            uri.userInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null
    ) {
        "Нужен QR-код фискального чека mev.sfs.md"
    }
    val match =
        Regex("^/(?:ro/|ru/|en/)?receipt-verifier/([A-Za-z0-9_-]{16,128})/?$")
            .matchEntire(uri.rawPath ?: "")
            ?: throw IllegalArgumentException("Этот QR-код не является чеком MEV")
    return "https://mev.sfs.md/receipt-verifier/${match.groupValues[1]}"
}

fun scopeKey(server: String, user: String, organization: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest("$server\u0000$user\u0000$organization".toByteArray())
        .joinToString("") { "%02x".format(it) }

fun money(minor: Long, currency: String = "MDL"): String =
    NumberFormat.getNumberInstance(Locale.forLanguageTag("ru-MD"))
        .apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        .format(BigDecimal.valueOf(minor, 2)) + " $currency"

fun statusLabel(status: String): String =
    when (status) {
        "queued" -> "В очереди"
        "processing" -> "Распознаётся"
        "posted" -> "В учёте"
        "review",
        "needs_review",
        "ready" -> "Проверить"
        "error",
        "failed" -> "Нужна помощь"
        "duplicate" -> "Уже добавлен"
        else -> "Проверить"
    }
