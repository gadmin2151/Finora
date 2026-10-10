package work.gadmin.finora.notifications

/** Pure explicit-tap decision. Background delivery never calls this or selects an organization. */
object PushReceiptRouting {
    fun targetOrganization(
        eventId: String,
        context: PushEventContext,
        captured: PushScope,
        current: PushScope?,
        memberOrganizations: Set<String>,
        optedIn: Boolean,
    ): String? =
        context.organizationId.takeIf {
            optedIn &&
                current == captured &&
                context.eventId == eventId &&
                context.category == "receipts" &&
                it != captured.organizationId &&
                it in memberOrganizations
        }

    fun settled(
        original: PushScope,
        current: PushScope?,
        target: String,
        optedIn: Boolean,
    ): Boolean =
        optedIn &&
            current != null &&
            current.server == original.server &&
            current.userId == original.userId &&
            current.sessionKey == original.sessionKey &&
            current.organizationId == target
}
