package work.gadmin.finora.notifications

import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONObject

/** Nonsecret generation fences persisted gates across logout, login and process recreation. */
class PushSessionStore(context: Context) {
    private val invalidToken = AtomicFile(File(context.noBackupFilesDir, "push-token-invalidation"))
    private val file = AtomicFile(File(context.noBackupFilesDir, "push-session-generation"))

    @Synchronized
    fun session(server: String, user: String): String? = runCatching {
        val value = JSONObject(file.readFully().decodeToString())
        value.getString("generation").takeIf {
            value.getString("server") == server && value.getString("user") == user
        }
    }
        .getOrNull()

    @Synchronized
    fun begin(server: String, user: String, fresh: Boolean): String {
        if (!fresh)
            session(server, user)?.let {
                return it
            }
        val generation = PushEnvelope.installationId()
        val stream = file.startWrite()
        try {
            stream.write(
                JSONObject()
                    .put("server", server)
                    .put("user", user)
                    .put("generation", generation)
                    .toString()
                    .toByteArray()
            )
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        return generation
    }

    @Synchronized
    fun workspace(server: String, user: String, organization: String?) {
        val value =
            runCatching { JSONObject(file.readFully().decodeToString()) }.getOrNull() ?: return
        if (value.optString("server") != server || value.optString("user") != user) return
        value.put("organization", organization ?: JSONObject.NULL)
        val stream = file.startWrite()
        try {
            stream.write(value.toString().toByteArray())
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    @Synchronized
    fun active(scope: PushScope): Boolean = runCatching {
        val value = JSONObject(file.readFully().decodeToString())
        !tokenDeletionRequired() &&
            session(scope.server, scope.userId) == scope.sessionKey &&
            value.optString("organization") == scope.organizationId
    }
        .getOrDefault(false)

    @Synchronized fun tokenDeletionRequired() = invalidToken.baseFile.exists()

    @Synchronized fun tokenDeleted() = invalidToken.delete()

    @Synchronized
    fun invalidate() {
        try {
            val stream = invalidToken.startWrite()
            try {
                stream.write(1)
                invalidToken.finishWrite(stream)
            } catch (error: Exception) {
                invalidToken.failWrite(stream)
                throw error
            }
        } finally {
            file.delete()
        }
    }
}
