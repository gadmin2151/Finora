package work.gadmin.finora.data

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.serialization.json.*

data class ChatActionField(val path: List<String>, val value: JsonPrimitive?)

data class ChatActionPreview(val body: List<ChatActionField>, val parameters: List<ChatActionField>)

/** Fail closed: never approve a summary when exact stored values cannot be shown. */
fun ChatAction.preview(): ChatActionPreview? = runCatching {
    require(operation.length in 1..200 && label.length in 1..120 && description.length in 1..600)
    require(Regex("^(POST|PUT|PATCH|DELETE) /[a-zA-Z0-9_/{}/-]+$").matches(operation))
    fun fields(raw: String, max: Int): List<ChatActionField> {
        require(raw.length <= max)
        val root = Json.parseToJsonElement(raw)
        require(root is JsonObject)
        val result = mutableListOf<ChatActionField>()
        fun walk(value: JsonElement, path: List<String>) {
            require(path.size <= 16 && result.size < 2000)
            when (value) {
                is JsonObject ->
                    if (value.isEmpty()) result.add(ChatActionField(path, JsonPrimitive("{}")))
                    else value.forEach { (key, child) -> walk(child, path + key) }
                is JsonArray ->
                    if (value.isEmpty()) result.add(ChatActionField(path, JsonPrimitive("[]")))
                    else
                        value.forEachIndexed { index, child ->
                            walk(child, path + (index + 1).toString())
                        }
                JsonNull -> result.add(ChatActionField(path, null))
                is JsonPrimitive -> result.add(ChatActionField(path, value))
            }
        }
        walk(root, emptyList())
        return result
    }
    ChatActionPreview(fields(body_json, 12000), fields(parameters_json, 2048))
}
    .getOrNull()

fun ChatMessage.ownsPendingActions(userId: String?): Boolean =
    role == "assistant" &&
        userId != null &&
        details.actor_id == userId &&
        details.action_status == "pending" &&
        !details.actions.isNullOrEmpty()

fun ChatMessage.actionsExpired(now: Instant = Instant.now()): Boolean {
    val created =
        runCatching { Instant.parse(created_at) }
            .getOrElse {
                runCatching { LocalDateTime.parse(created_at).toInstant(ZoneOffset.UTC) }
                    .getOrNull()
            } ?: return true
    return created.isBefore(now.minusSeconds(30 * 60))
}

fun ChatMessage.canConfirmActions(userId: String?, now: Instant = Instant.now()): Boolean =
    ownsPendingActions(userId) &&
        !actionsExpired(now) &&
        details.actions.orEmpty().size in 1..6 &&
        details.actions.orEmpty().all { it.preview() != null }

fun ChatMessage.sameProposal(other: ChatMessage): Boolean =
    id == other.id &&
        role == other.role &&
        created_at == other.created_at &&
        details.actor_id == other.details.actor_id &&
        details.actions == other.details.actions
