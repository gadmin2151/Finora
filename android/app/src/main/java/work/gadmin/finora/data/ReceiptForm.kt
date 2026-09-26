package work.gadmin.finora.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import work.gadmin.finora.localization.Message
import work.gadmin.finora.localization.tr

private val decimal = Regex("^[0-9]{1,10}(?:[.,][0-9]{1,6})?$")

fun receiptNumber(value: String, money: Boolean = false): BigDecimal {
    val text = value.trim()
    require(decimal.matches(text)) { tr(Message.ENTER_A_NUMBER_WITHOUT_SPACES_FOR_EXAMPLE_12_50) }
    val number = text.replace(',', '.').toBigDecimal()
    require(number <= BigDecimal("1000000000")) { tr(Message.THE_AMOUNT_IS_TOO_LARGE) }
    if (money)
        require(number.stripTrailingZeros().scale() <= 2) {
            tr(Message.AN_AMOUNT_CAN_HAVE_NO_MORE_THAN_TWO_DECIMAL_PLACES)
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
) {
    fun calculateTotal(): ReceiptLineForm =
        copy(
            total =
                runCatching {
                        val count = receiptNumber(quantity)
                        require(count > BigDecimal.ZERO && count <= BigDecimal("100000"))
                        val amount =
                            (count * receiptNumber(unitPrice, true)).setScale(
                                2,
                                RoundingMode.HALF_UP,
                            )
                        require(amount <= BigDecimal("1000000000"))
                        amount.toPlainString()
                    }
                    .getOrDefault("")
        )
}

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
    val merchantAddress: String = "",
) {
    fun lineSum(): BigDecimal? = runCatching {
        items.fold(BigDecimal.ZERO) { sum, item -> sum + receiptNumber(item.total, true) }
    }
        .getOrNull()

    fun payload(): JsonObject {
        require(merchant.trim().length in 1..200) { tr(Message.ENTER_A_STORE) }
        require(merchantAddress.length <= 500) { tr(Message.THE_ADDRESS_IS_TOO_LONG) }
        val purchased = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
        require(purchased != null && purchased.year >= 1990 && purchased <= LocalDate.now()) {
            tr(Message.CHECK_THE_DATE_YYYY_MM_DD_NO_LATER_THAN_TODAY)
        }
        require(currency in setOf("MDL", "EUR", "USD", "RON")) { tr(Message.CHOOSE_A_CURRENCY) }
        require(!accountId.isNullOrBlank()) { tr(Message.CHOOSE_AN_ACCOUNT) }
        require(items.size in 1..200) { tr(Message.A_RECEIPT_MUST_HAVE_1_200_ITEMS) }
        val amount = receiptNumber(total, true)
        require(amount > BigDecimal.ZERO) { tr(Message.THE_TOTAL_MUST_BE_GREATER_THAN_ZERO) }
        require(lineSum()?.compareTo(amount) == 0) {
            tr(Message.THE_ITEM_SUM_DOES_NOT_MATCH_THE_TOTAL_CHECK_THE_ITEMS_AND)
        }
        val rate = if (currency == "MDL") BigDecimal.ONE else receiptNumber(fxRate)
        require(rate > BigDecimal.ZERO && rate <= BigDecimal("100000")) {
            tr(Message.ENTER_THE_RATE_MDL_PER_CURRENCY_UNIT)
        }
        return buildJsonObject {
            put("merchant", merchant.trim())
            put("merchant_address", merchantAddress.trim())
            put("purchased_on", purchased.toString())
            put("currency", currency)
            put("total", amount.toPlainString())
            put("account_id", accountId)
            put("fx_rate", rate.toPlainString())
            put("version", version)
            putJsonArray("items") {
                items.forEachIndexed { index, item ->
                    require(item.name.trim().length in 1..300) {
                        tr(Message.ENTER_A_NAME_FOR_ITEM_1_S, index + 1)
                    }
                    val quantity = receiptNumber(item.quantity)
                    require(quantity > BigDecimal.ZERO && quantity <= BigDecimal("100000")) {
                        tr(Message.CHECK_THE_QUANTITY_FOR_ITEM_1_S, index + 1)
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

    fun manualPayload(requestKey: String): JsonObject =
        JsonObject(
            payload().filterKeys { it != "version" } +
                ("request_key" to JsonPrimitive(UUID.fromString(requestKey).toString()))
        )

    companion object {
        fun manual(accounts: List<Account>, selectedAccount: String? = null): ReceiptForm {
            val account =
                accounts.firstOrNull { it.id == selectedAccount && !it.archived }
                    ?: accounts.firstOrNull { !it.archived && it.currency == "MDL" }
                    ?: accounts.firstOrNull { !it.archived }
            return ReceiptForm(
                merchant = "",
                date = LocalDate.now().toString(),
                currency = account?.currency ?: "MDL",
                total = "",
                accountId = account?.id,
                fxRate = if (account?.currency in listOf(null, "MDL")) "1" else "",
                version = 1,
                items = listOf(ReceiptLineForm()),
            )
        }

        fun from(receipt: Receipt, accounts: List<Account>): ReceiptForm =
            ReceiptForm(
                merchant = receipt.merchant,
                merchantAddress = receipt.merchant_address,
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
