package work.gadmin.finora.notifications

import kotlinx.serialization.json.*
import work.gadmin.finora.data.ApiException

/** Shared HTTP semantics for Activity and cold FCM service. Every request is scope fenced. */
class MappedPushTransport(
    private val delegate: PushTransport,
    private val currentScope: () -> PushScope?,
    private val onUnauthorized: (PushScope) -> Unit,
) : PushTransport {
    override suspend fun getContext(scope: PushScope, path: String): JsonObject {
        checkScope(scope)
        return try {
            delegate.getContext(scope, path).also { checkScope(scope) }
        } catch (error: ApiException) {
            throw mapped(scope, error)
        }
    }

    override suspend fun get(scope: PushScope, path: String): JsonObject {
        checkScope(scope)
        return try {
            delegate.get(scope, path).also { checkScope(scope) }
        } catch (error: ApiException) {
            if (path == "/api/push/capabilities" && error.code == 404)
                buildJsonObject {
                    put("schema_version", 1)
                    put("delivery_configured", false)
                }
            else throw mapped(scope, error)
        }
    }

    override suspend fun write(
        scope: PushScope,
        path: String,
        method: String,
        body: JsonObject?,
    ): JsonObject {
        val revoke = method == "DELETE"
        if (!revoke) checkScope(scope)
        return try {
            delegate.write(scope, path, method, body).also { if (!revoke) checkScope(scope) }
        } catch (error: ApiException) {
            if (revoke && error.code in setOf(401, 404)) buildJsonObject { put("ok", true) }
            else throw mapped(scope, error)
        }
    }

    private fun checkScope(scope: PushScope) {
        if (currentScope() != scope) throw PushRevokedException()
    }

    private fun mapped(scope: PushScope, error: ApiException): Exception {
        if (error.code == 401 && currentScope() == scope) onUnauthorized(scope)
        return when {
            error.code == 429 && (error.retryAfterMillis ?: 10000) in 1..10000 ->
                PushRetryException(error.retryAfterMillis ?: 10000)
            error.code in setOf(401, 403, 404) -> PushRevokedException()
            else -> IllegalStateException("Push service unavailable")
        }
    }
}

/** Enables persisted cold delivery only after successful registration in the same session. */
suspend fun synchronizeColdPush(
    controller: PushController,
    permission: PushPermission,
    token: PushToken,
    expected: PushScope,
    currentScope: () -> PushScope?,
    enable: (PushScope) -> Unit,
) {
    controller.synchronize(permission, token)
    if (controller.state == PushState.READY && currentScope() == expected) enable(expected)
}
