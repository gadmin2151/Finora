package work.gadmin.finora.notifications

/** Release hints never contain an APK URL, signature, hash, or installation command. */
data class InstalledPushVersion(val code: Long, val name: String) {
    init {
        require(code in 1..Int.MAX_VALUE.toLong())
        require(name.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
    }
}

object PushUpdatePolicy {
    const val CATEGORY = "update_available"
    const val ROUTE = "updater"

    fun newer(candidate: InstalledPushVersion, installedCode: Long): Boolean =
        installedCode > 0 && candidate.code > installedCode

    /** Untrusted FCM hints can only suppress/display a neutral notification, never navigate. */
    fun incoming(
        data: Map<String, String>,
        installedCode: Long,
        lastNotifiedCode: Long,
    ): InstalledPushVersion? {
        if (data["category"] != CATEGORY) return null
        val version =
            runCatching {
                InstalledPushVersion(
                    requireNotNull(data["version_code"]?.toLongOrNull()),
                    requireNotNull(data["version_name"]),
                )
            }
                .getOrNull() ?: return null
        return version.takeIf { newer(it, installedCode) && it.code > lastNotifiedCode }
    }
}
