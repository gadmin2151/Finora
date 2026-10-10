package work.gadmin.finora.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class LedgerPendingMutation(
    val owner: String,
    val organization: String,
    val form: LedgerEdit,
    val path: String,
    val method: String,
    val body: JsonObject?,
) {
    fun command() = FinanceCommand(path, method, body)
}

interface LedgerPendingStore {
    fun read(owner: String, organization: String): LedgerPendingMutation?

    /** Must durably persist before returning; may never overwrite another immutable payload. */
    fun save(pending: LedgerPendingMutation)

    fun clear(pending: LedgerPendingMutation)
}

/** Default fails closed until the integrator supplies application persistence. */
object UnavailableLedgerPendingStore : LedgerPendingStore {
    override fun read(owner: String, organization: String): LedgerPendingMutation? =
        error("Durable ledger storage is unavailable")

    override fun save(pending: LedgerPendingMutation): Unit =
        error("Durable ledger storage is unavailable")

    override fun clear(pending: LedgerPendingMutation): Unit =
        error("Durable ledger storage is unavailable")
}

class PreferencesLedgerPendingStore(context: Context) : LedgerPendingStore {
    private val prefs =
        context.applicationContext.getSharedPreferences(
            "finora_ledger_pending_v1",
            Context.MODE_PRIVATE,
        )
    private val json = Json { ignoreUnknownKeys = true }

    private fun key(owner: String, org: String) =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString((owner + "\u0000" + org).toByteArray(Charsets.UTF_8))

    @Synchronized
    override fun read(owner: String, organization: String): LedgerPendingMutation? {
        require(owner.isNotBlank() && organization.isNotBlank())
        val raw = prefs.getString(key(owner, organization), null) ?: return null
        return json.decodeFromString<LedgerPendingMutation>(raw).also {
            require(it.owner == owner && it.organization == organization) {
                "Pending ledger scope mismatch"
            }
        }
    }

    @Synchronized
    override fun save(pending: LedgerPendingMutation) {
        val old = read(pending.owner, pending.organization)
        require(old == null || old == pending) { "Another immutable ledger operation is pending" }
        check(
            prefs
                .edit()
                .putString(key(pending.owner, pending.organization), json.encodeToString(pending))
                .commit()
        ) {
            "Could not durably save ledger operation; nothing was sent"
        }
    }

    @Synchronized
    override fun clear(pending: LedgerPendingMutation) {
        require(read(pending.owner, pending.organization) == pending)
        check(prefs.edit().remove(key(pending.owner, pending.organization)).commit()) {
            "Could not clear confirmed ledger operation"
        }
    }
}
