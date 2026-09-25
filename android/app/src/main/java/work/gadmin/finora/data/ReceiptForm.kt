package work.gadmin.finora.data

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

private val decimal = Regex("^[0-9]{1,10}(?:[.,][0-9]{1,6})?$")

fun receiptNumber(value: String, money: Boolean = false): BigDecimal {
    val text = value.trim()
    require(decimal.matches(text)) { "Введите число без пробелов, например 12,50" }
    val number = text.replace(',', '.').toBigDecimal()
    require(number <= BigDecimal("1000000000")) { "Слишком большая сумма" }
    if (money)
        require(number.stripTrailingZeros().scale() <= 2) {
            "У суммы может быть не больше двух знаков после запятой"
        }
    return number
}

fun editableMoney(minor: Long): String = BigDecimal.valueOf(minor, 2).toPlainString()

@Serializable
data class ReceiptLineForm(
    val key: String = UUID.randomUUID().toString(),
    val name: String = "",
    val quantity: String = "1",
    val unit: String = "шт",
    val unitPrice: String = "",
    val total: String = "",
    val categoryId: String? = null,
)

@Serializable
data class ReceiptForm(
    val merchant: String,
    val date: String,
    val currency: String,
    val total: String,
    val accountId: String?,
    val fxRate: String,
    val version: Int,
    val items: List<ReceiptLineForm>,
) {
    fun lineSum(): BigDecimal? = runCatching {
        items.fold(BigDecimal.ZERO) { sum, item -> sum + receiptNumber(item.total, true) }
    }
        .getOrNull()

    fun payload(): JsonObject {
        require(merchant.trim().length in 1..200) { "Укажите магазин" }
        val purchased = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
        require(purchased != null && purchased.year >= 1990 && purchased <= LocalDate.now()) {
            "Проверьте дату: ГГГГ-ММ-ДД, не позднее сегодняшнего дня"
        }
        require(currency in setOf("MDL", "EUR", "USD", "RON")) { "Выберите валюту" }
        require(!accountId.isNullOrBlank()) { "Выберите счёт" }
        require(items.size in 1..200) { "В чеке должно быть от 1 до 200 позиций" }
        val amount = receiptNumber(total, true)
        require(amount > BigDecimal.ZERO) { "Итог должен быть больше нуля" }
        require(lineSum()?.compareTo(amount) == 0) {
            "Сумма позиций не совпадает с итогом. Проверьте строки и скидки."
        }
        val rate = if (currency == "MDL") BigDecimal.ONE else receiptNumber(fxRate)
        require(rate > BigDecimal.ZERO && rate <= BigDecimal("100000")) {
            "Укажите курс: сколько MDL за единицу валюты"
        }
        return buildJsonObject {
            put("merchant", merchant.trim())
            put("purchased_on", purchased.toString())
            put("currency", currency)
            put("total", amount.toPlainString())
            put("account_id", accountId)
            put("fx_rate", rate.toPlainString())
            put("version", version)
            putJsonArray("items") {
                items.forEachIndexed { index, item ->
                    require(item.name.trim().length in 1..300) {
                        "Укажите название позиции ${index + 1}"
                    }
                    val quantity = receiptNumber(item.quantity)
                    require(quantity > BigDecimal.ZERO && quantity <= BigDecimal("100000")) {
                        "Проверьте количество в позиции ${index + 1}"
                    }
                    require(item.unit.length <= 12)
                    add(
                        buildJsonObject {
                            put("name", item.name.trim())
                            put("quantity", quantity.toPlainString())
                            put("unit", item.unit.trim())
                            put("unit_price", receiptNumber(item.unitPrice, true).toPlainString())
                            put("total", receiptNumber(item.total, true).toPlainString())
                            put("category_id", item.categoryId?.let(::JsonPrimitive) ?: JsonNull)
                        }
                    )
                }
            }
        }
    }

    companion object {
        fun from(receipt: Receipt, accounts: List<Account>): ReceiptForm =
            ReceiptForm(
                merchant = receipt.merchant,
                date = receipt.purchased_on ?: "",
                currency = receipt.currency,
                total = receipt.total_minor?.let(::editableMoney) ?: "",
                accountId =
                    receipt.account_id?.takeIf { id ->
                        accounts.any {
                            it.id == id && it.currency == receipt.currency && !it.archived
                        }
                    }
                        ?: accounts
                            .firstOrNull { it.currency == receipt.currency && !it.archived }
                            ?.id,
                fxRate = receipt.fx_rate,
                version = receipt.version,
                items =
                    receipt.items.map {
                        ReceiptLineForm(
                            it.id,
                            it.name,
                            it.quantity,
                            it.unit,
                            editableMoney(it.unit_price_minor),
                            editableMoney(it.total_minor),
                            it.category_id,
                        )
                    },
            )
    }
}
