package work.gadmin.finora.notifications

import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONObject

/**
 * No-backup local metadata, never tokens/cookies. Host clears the gate before logout, 401,
 * switching server/user/org or disabling permission/preferences; only READY may persist it. On cold
 * service start also require SessionStore's authenticated identity to match this scope.
 */
class PushInstallationStore(context: Context) {
    private val identity = AtomicFile(File(context.noBackupFilesDir, "push-installation-id"))
    private val receiptOptIn =
        AtomicFile(File(context.noBackupFilesDir, "push-receipt-all-organizations"))

    @Synchronized
    fun receiptAllOrganizations(): Boolean = runCatching {
        receiptOptIn.readFully().decodeToString() == "true"
    }
        .getOrDefault(false)

    @Synchronized
    fun setReceiptAllOrganizations(enabled: Boolean) = write(receiptOptIn, enabled.toString())

    private val delivery = AtomicFile(File(context.noBackupFilesDir, "push-delivery-gate"))

    @Synchronized
    fun installationId(): String {
        runCatching { identity.readFully().decodeToString() }
            .getOrNull()
            ?.let { id ->
                if (PushEnvelope.eventId(mapOf("event_id" to id)) != null) return id
            }
        return PushEnvelope.installationId().also { write(identity, it) }
    }

    @Synchronized
    fun enable(scope: PushScope) {
        write(
            delivery,
            JSONObject()
                .put("server", scope.server)
                .put("user", scope.userId)
                .put("generation", scope.sessionKey)
                .put("organization", scope.organizationId)
                .toString(),
        )
    }

    @Synchronized
    fun scope(): PushScope? = runCatching {
        val value = JSONObject(delivery.readFully().decodeToString())
        PushScope(
            value.getString("server"),
            value.getString("user"),
            value.getString("generation"),
            value.getString("organization"),
        )
    }
        .getOrNull()

    @Synchronized fun disable() = delivery.delete()

    private fun write(file: AtomicFile, value: String) {
        val stream = file.startWrite()
        try {
            stream.write(value.toByteArray())
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}
