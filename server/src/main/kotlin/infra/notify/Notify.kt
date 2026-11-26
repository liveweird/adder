package ch.nokillswit.infra.notify

import kotlinx.coroutines.flow.toList
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager

/** The one NOTIFY channel of the live feature (`live.md` "The NOTIFY wire"). */
const val LIVE_CHANNEL = "adder_live"

/** The wire version carried in every payload (`v`); a reader ignores a payload with another one. */
const val LIVE_HINT_VERSION = 1

/** PostgreSQL rejects a NOTIFY payload of 8000 bytes or more; [notifyLive] refuses one before the database does. */
const val MAX_NOTIFY_PAYLOAD_BYTES = 8000

/**
 * What changed, as the live channel names it. The wire name IS the audit event name of the same write (`observability.md`), pinned by
 * `LiveHintTest`; the opening message of a thread is part of [THREAD_CREATED], never a [MESSAGE_POSTED]. **One exception to the name
 * rule:** a thread's tag save (1.2, audited `thread.tags_changed`) hints [THREAD_UPDATED] (`thread.updated`): the SPA re-reads the same
 * thread, list and history on it, so a kind of its own would add nothing.
 */
@Serializable
enum class LiveKind {
    @SerialName("room.created") ROOM_CREATED,
    @SerialName("room.updated") ROOM_UPDATED,
    @SerialName("room.archived") ROOM_ARCHIVED,
    @SerialName("room.unarchived") ROOM_UNARCHIVED,
    @SerialName("room.deleted") ROOM_DELETED,
    @SerialName("room.member_added") ROOM_MEMBER_ADDED,
    @SerialName("room.member_role_changed") ROOM_MEMBER_ROLE_CHANGED,
    @SerialName("room.member_removed") ROOM_MEMBER_REMOVED,
    @SerialName("room.workflow_updated") ROOM_WORKFLOW_UPDATED,
    @SerialName("thread.created") THREAD_CREATED,
    @SerialName("thread.updated") THREAD_UPDATED,
    @SerialName("thread.deleted") THREAD_DELETED,
    @SerialName("thread.merged") THREAD_MERGED,
    @SerialName("thread.status_changed") THREAD_STATUS_CHANGED,
    @SerialName("thread.owner_changed") THREAD_OWNER_CHANGED,
    @SerialName("message.posted") MESSAGE_POSTED,
    @SerialName("message.edited") MESSAGE_EDITED,
    @SerialName("message.deleted") MESSAGE_DELETED,
    @SerialName("message.reaction_changed") MESSAGE_REACTION_CHANGED,
    @SerialName("room.pin_changed") ROOM_PIN_CHANGED,
    @SerialName("notification.created") NOTIFICATION_CREATED,
}

/**
 * One hint: that something changed, never what. Ids only, enforced by the type (no title, body, name or field value can be put here).
 * A thread is named by its per-room NUMBER, the key the SPA uses (never its row id or its revision). [targetUserIds] are the people a
 * personal frame is for (a creator, an added or removed member, the members of a deleted room); they may not be subscribed yet.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LiveHint(
    val kind: LiveKind,
    val roomId: UInt,
    val threadNumber: Int? = null,
    val messageId: UInt? = null,
    val targetUserIds: List<UInt>? = null,
    /** True when an ADMIN outside the room must lose the subscription too (a hidden room member left). */
    val requiresMembership: Boolean = false,
    @EncodeDefault val v: Int = LIVE_HINT_VERSION,
)

private val wire = Json { explicitNulls = false }

/** An internal, roomless prompt to re-judge every live token of one account. It is never a browser frame. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LiveAccountHint(
    val accountUserId: UInt,
    @EncodeDefault val v: Int = LIVE_HINT_VERSION,
)

/** The two version-one payload shapes accepted from the private PostgreSQL channel. */
sealed interface LiveNotification {
    data class Room(val hint: LiveHint) : LiveNotification
    data class Account(val userId: UInt) : LiveNotification
}

/** The NOTIFY payload of [hint]: compact JSON, nulls omitted, `v` always present. */
fun LiveHint.toPayload(): String = wire.encodeToString(LiveHint.serializer(), this)

/** The deliberately roomless account envelope; field order is pinned because this is an internal protocol. */
fun LiveAccountHint.toPayload(): String = wire.encodeToString(LiveAccountHint.serializer(), this)

/**
 * Decodes only one of the two version-one envelopes. In particular, an account prompt cannot be widened with room-event fields and
 * can therefore never accidentally become a public event.
 */
fun decodeLiveNotification(payload: String): LiveNotification? = runCatching {
    val element = wire.parseToJsonElement(payload)
    val objectValue: JsonObject = element.jsonObject
    if ("accountUserId" in objectValue) {
        if (objectValue.keys != setOf("accountUserId", "v")) return null
        val hint = wire.decodeFromJsonElement<LiveAccountHint>(element)
        hint.takeIf { it.v == LIVE_HINT_VERSION && it.accountUserId > 0u }?.let { LiveNotification.Account(it.accountUserId) }
    } else {
        val hint = wire.decodeFromJsonElement<LiveHint>(element)
        hint.takeIf { it.v == LIVE_HINT_VERSION }?.let(LiveNotification::Room)
    }
}.getOrNull()

/**
 * Emits [hint] on [LIVE_CHANNEL] through the CURRENT transaction (call it right before a change branch returns, inside the write's own
 * transaction): PostgreSQL delivers a NOTIFY only when that transaction commits, in commit order, and drops it on rollback, so a refused or
 * rolled-back write can never announce itself. The statement's result is consumed on purpose (an R2DBC statement nobody subscribes to never
 * runs). The payload must stay under [MAX_NOTIFY_PAYLOAD_BYTES] (the worst case is a 500-id `room.member_added`, about 5.6 KB).
 */
suspend fun notifyLive(hint: LiveHint) {
    notifyPayload(hint.toPayload(), hint.kind.toString())
}

/** Emits a roomless account prompt in the caller's current transaction. */
suspend fun notifyLiveAccount(userId: UInt) {
    notifyPayload(LiveAccountHint(userId).toPayload(), "account")
}

private suspend fun notifyPayload(payload: String, description: String) {
    check(payload.toByteArray(Charsets.UTF_8).size < MAX_NOTIFY_PAYLOAD_BYTES) {
        "A live hint of $description exceeds the NOTIFY payload limit"
    }
    val arguments = listOf(TextColumnType() to payload)
    TransactionManager.current()
        .exec("SELECT 1 FROM pg_notify('$LIVE_CHANNEL', ?)", arguments, explicitStatementType = StatementType.SELECT) { 1 }
        ?.toList()
}
