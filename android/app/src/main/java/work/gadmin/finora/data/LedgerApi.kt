package work.gadmin.finora.data

/** Adapter shares the authenticated session, organization header and write protocol. */
interface LedgerApi {
    suspend fun history(
        org: String,
        month: String,
        offset: Int,
        kind: String?,
        account: String?,
    ): LedgerHistory

    suspend fun accounts(org: String): List<Account>

    suspend fun categories(org: String): List<Category>

    suspend fun write(org: String, command: FinanceCommand)
}

class AuthenticatedLedgerApi(private val client: () -> ApiClient) : LedgerApi {
    override suspend fun history(
        org: String,
        month: String,
        offset: Int,
        kind: String?,
        account: String?,
    ) = client().transactionHistory(org, month, offset, kind, account)

    override suspend fun accounts(org: String) = client().paymentAccounts(org)

    override suspend fun categories(org: String) = client().categories(org)

    override suspend fun write(org: String, command: FinanceCommand) {
        client().financeWrite(org, command)
    }
}
