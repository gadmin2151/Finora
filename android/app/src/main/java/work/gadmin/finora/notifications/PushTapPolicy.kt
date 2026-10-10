package work.gadmin.finora.notifications

import java.security.MessageDigest

/** Receipt identity mode survives workspace choice, but never a login/account/server change. */
object PushTapPolicy {
    private fun digest(parts: List<String>): String =
        MessageDigest.getInstance("SHA-256")
            .digest(parts.joinToString("\u0000").toByteArray())
            .joinToString("") { "%02x".format(it) }

    // Preserve the legacy exact-workspace digest for every normal event.
    fun scopeDigest(scope: PushScope): String =
        digest(listOf(scope.server, scope.userId, scope.sessionKey, scope.organizationId))

    fun receiptIdentityDigest(scope: PushScope): String =
        digest(listOf("receipt-all-org-v1", scope.server, scope.userId, scope.sessionKey))

    fun receiptIdentityMode(category: String?, optedIn: Boolean): Boolean =
        category == "receipts" && optedIn

    fun accepts(
        suppliedDigest: String?,
        current: PushScope,
        receiptMode: Boolean,
        category: String?,
        optedIn: Boolean,
    ): Boolean =
        if (receiptMode)
            receiptIdentityMode(category, optedIn) &&
                suppliedDigest == receiptIdentityDigest(current)
        else suppliedDigest == scopeDigest(current)

    /**
     * Updates dedupe across logins/workspaces of the same user, independently for other accounts.
     */
    fun updateRecordKey(scope: PushScope): String =
        digest(listOf("update-notified-user-v1", scope.server, scope.userId))
}
