package work.gadmin.finora.accounts

import work.gadmin.finora.data.ApiClient
import work.gadmin.finora.data.FinanceCommand

interface AccountManagementApi {
    suspend fun list(organizationId: String): List<ManagedAccount>

    suspend fun write(organizationId: String, command: FinanceCommand)
}

/** Reuses authenticated same-origin transport, CSRF and X-Organization-ID. No retry added. */
class AccountManagementApiAdapter(private val client: () -> ApiClient) : AccountManagementApi {
    override suspend fun list(organizationId: String) =
        client().getAccountsDetails(organizationId).map {
            ManagedAccount(
                it.id,
                it.name,
                it.currency,
                it.kind,
                it.color,
                it.opening_minor,
                it.balance_minor,
                it.archived,
            )
        }

    override suspend fun write(organizationId: String, command: FinanceCommand) =
        client().financeWrite(organizationId, command)
}
