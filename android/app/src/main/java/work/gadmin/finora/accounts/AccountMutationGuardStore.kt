package work.gadmin.finora.accounts

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.*
import work.gadmin.finora.data.FinanceCommand

/** No credentials or replayable request body: only scope, identity and human review summary. */
data class AccountMutationGuard(
    val requestId: String,
    val ownerId: String,
    val organizationId: String,
    val serverOrigin: String,
    val commandDigest: String,
    val description: String,
) {
    companion object {
        fun create(scope: AccountManagementScope, command: FinanceCommand, description: String) =
            AccountMutationGuard(
                UUID.randomUUID().toString(),
                scope.ownerId,
                scope.organizationId,
                scope.serverOrigin,
                accountGuardDigest(
                    listOf(command.method, command.path, command.body?.toString().orEmpty())
                ),
                description,
            )
    }
}

private fun accountGuardDigest(parts: List<String>): String =
    MessageDigest.getInstance("SHA-256")
        .digest(
            parts
                .joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }
                .toByteArray(Charsets.UTF_8)
        )
        .joinToString("") { "%02x".format(it) }

interface AccountMutationGuardStore {
    /** Throws on corruption/I/O failure. Absence alone means no unresolved request. */
    fun read(scope: AccountManagementScope): AccountMutationGuard?

    /** Must durably finish before HTTP dispatch. Never replace an unresolved request. */
    fun put(scope: AccountManagementScope, guard: AccountMutationGuard)

    /**
     * Removes only this identity, after acknowledged response or explicit successful reread review.
     */
    fun clear(scope: AccountManagementScope, requestId: String)
}

/** Place under application.noBackupFilesDir; same-directory atomic rename + fsync. */
class FileAccountMutationGuardStore(private val directory: File) : AccountMutationGuardStore {
    private companion object {
        val monitor = Any()
    }

    private fun <T> locked(block: () -> T): T =
        synchronized(monitor) {
            check(directory.isDirectory || directory.mkdirs()) {
                "Cannot create account guard directory"
            }
            FileChannel.open(
                    File(directory, ".guard.lock").toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                )
                .use { channel ->
                    channel.lock().use { block() }
                }
        }

    private fun target(scope: AccountManagementScope): File {
        val origin = java.net.URI(scope.serverOrigin)
        require(scope.ownerId.isNotBlank() && scope.organizationId.isNotBlank())
        require(
            origin.scheme == "https" &&
                origin.host != null &&
                origin.userInfo == null &&
                origin.rawQuery == null &&
                origin.fragment == null &&
                origin.path in listOf("", "/")
        )
        return File(
            directory,
            accountGuardDigest(listOf(scope.serverOrigin, scope.ownerId, scope.organizationId)) +
                ".json",
        )
    }

    @Synchronized
    override fun read(scope: AccountManagementScope): AccountMutationGuard? {
        val file = target(scope)
        if (!file.exists()) return null
        val obj = Json.parseToJsonElement(file.readText()).jsonObject
        fun field(name: String) = obj.getValue(name).jsonPrimitive.content
        return AccountMutationGuard(
                field("requestId"),
                field("ownerId"),
                field("organizationId"),
                field("serverOrigin"),
                field("commandDigest"),
                field("description"),
            )
            .also {
                require(
                    it.ownerId == scope.ownerId &&
                        it.organizationId == scope.organizationId &&
                        it.serverOrigin == scope.serverOrigin
                )
                UUID.fromString(it.requestId)
                require(Regex("[a-f0-9]{64}").matches(it.commandDigest))
            }
    }

    private fun syncDirectory() {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    @Synchronized
    override fun put(scope: AccountManagementScope, guard: AccountMutationGuard) = locked {
        require(
            guard.ownerId == scope.ownerId &&
                guard.organizationId == scope.organizationId &&
                guard.serverOrigin == scope.serverOrigin
        )
        check(read(scope) == null) { "An account request already requires review" }
        check(directory.isDirectory || directory.mkdirs()) {
            "Cannot create account guard directory"
        }
        val target = target(scope)
        val temporary = File.createTempFile("account-", ".pending", directory)
        try {
            val body = buildJsonObject {
                put("requestId", guard.requestId)
                put("ownerId", guard.ownerId)
                put("organizationId", guard.organizationId)
                put("serverOrigin", guard.serverOrigin)
                put("commandDigest", guard.commandDigest)
                put("description", guard.description)
            }
                .toString()
                .toByteArray(Charsets.UTF_8)
            FileOutputStream(temporary).use {
                it.write(body)
                it.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncDirectory()
            check(read(scope) == guard) { "Account guard verification failed" }
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    override fun clear(scope: AccountManagementScope, requestId: String) = locked {
        val before = read(scope) ?: return@locked
        check(before.requestId == requestId) { "Account guard identity changed" }
        Files.delete(target(scope).toPath())
        syncDirectory()
    }
}
