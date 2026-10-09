package work.gadmin.finora.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Incomplete inputs plus the stable posting request ID, never a financial entry. */
@Serializable
data class ManualReceiptDraft(val requestKey: String, val form: ReceiptForm) {
    fun validate(): ManualReceiptDraft {
        require(REQUEST_KEY.matches(requestKey))
        require(form.merchant.length <= 200 && form.merchantAddress.length <= 500)
        require(form.date.length <= 10 && form.currency.length <= 3)
        require((form.accountId?.length ?: 0) <= 100 && form.fxRate.length <= 100)
        require(form.total.length <= 100 && form.items.size in 1..200)
        require(
            form.items.all {
                it.key.length <= 100 &&
                    it.name.length <= 300 &&
                    it.unit.length <= 12 &&
                    it.quantity.length <= 100 &&
                    it.unitPrice.length <= 100 &&
                    it.total.length <= 100 &&
                    (it.categoryId?.length ?: 0) <= 100
            }
        )
        return this
    }

    fun encode(): String = Json.encodeToString(validate()).also { require(it.length <= MAX_SIZE) }

    companion object {
        const val MAX_SIZE = 300_000
        private val REQUEST_KEY =
            Regex(
                "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
                RegexOption.IGNORE_CASE,
            )

        fun decode(value: String): ManualReceiptDraft {
            require(value.length <= MAX_SIZE)
            return Json.decodeFromString<ManualReceiptDraft>(value).validate()
        }
    }
}
