package ch.nokillswit.threads

import ch.nokillswit.infra.db.EventLog
import ch.nokillswit.infra.db.EventLogActor
import ch.nokillswit.infra.db.EventLogTable
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.users.UserService
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

val ThreadEventServiceKey = AttributeKey<ThreadEventService>("ThreadEventService")

/**
 * The kinds of history a thread keeps, PERSISTED and frozen (`thread_events.event_type`; no CHECK, this enum is the whitelist, the V5
 * rule): a value is never renamed or removed. Each carries a string-map `params` the SPA renders in the viewer's language
 * (`chat-model.md` "History"): keys and ids plus snapshots of labels and names, never rendered text.
 */
@Serializable
enum class ThreadEventType {
    /** `{title, status, statusLabel}` */
    CREATED,

    /** `{from, to}` */
    TITLE_CHANGED,

    /** `{from, to, fromLabel, toLabel}`: a move between statuses of one category. */
    STATUS_CHANGED,

    /** `{from, to, fromLabel, toLabel}`: the move into a CLOSED status. */
    CLOSED,

    /** `{from, to, fromLabel, toLabel}`: the move out of a CLOSED status. */
    REOPENED,

    /** `{fromId, toId, fromName, toName}` */
    OWNER_CHANGED,

    /** `{field, fieldLabel, from?, to?}`: one event per field; an absent side means unset. */
    FIELD_CHANGED,

    /** `{from, to, fromLabel, toLabel}`: the workflow PUT moved the thread off a retired status. */
    STATUS_REPLACED,
}

/** One event to append: the [type] and its string-map [params]. Built by the pure `thread*Event` functions below. */
data class ThreadEventDescriptor(val type: ThreadEventType, val params: Map<String, String>)

/** The thread was started in its room's initial status [statusKey] ([statusLabel] is the label at that moment). */
fun threadCreatedEvent(title: String, statusKey: String, statusLabel: String) =
    ThreadEventDescriptor(ThreadEventType.CREATED, mapOf("title" to title, "status" to statusKey, "statusLabel" to statusLabel))

/** The title changed from [from] to [to] (both recorded: nothing is encrypted at rest). */
fun threadTitleChangedEvent(from: String, to: String) =
    ThreadEventDescriptor(ThreadEventType.TITLE_CHANGED, mapOf("from" to from, "to" to to))

/** The thread moved from status [fromKey] to [toKey]; [transition] picks `STATUS_CHANGED`, `CLOSED` or `REOPENED` (labels as now). */
fun threadStatusEvent(
    transition: StatusTransition,
    fromKey: String,
    fromLabel: String,
    toKey: String,
    toLabel: String,
): ThreadEventDescriptor {
    val type = when (transition) {
        StatusTransition.MOVED -> ThreadEventType.STATUS_CHANGED
        StatusTransition.CLOSED -> ThreadEventType.CLOSED
        StatusTransition.REOPENED -> ThreadEventType.REOPENED
    }
    return ThreadEventDescriptor(type, mapOf("from" to fromKey, "to" to toKey, "fromLabel" to fromLabel, "toLabel" to toLabel))
}

/** The thread passed from user [fromId] to [toId] (the names as they are now; a soft-deleted user's recorded name is still the name). */
fun threadOwnerChangedEvent(fromId: UInt, toId: UInt, fromName: String, toName: String) = ThreadEventDescriptor(
    ThreadEventType.OWNER_CHANGED,
    mapOf("fromId" to fromId.toString(), "toId" to toId.toString(), "fromName" to fromName, "toName" to toName),
)

/** One history entry: `userDeleted` marks an actor whose account has since been soft-deleted (the recorded name stays). */
@Serializable
data class ThreadEventResponse(
    val id: UInt,
    val userId: UInt,
    val userName: String,
    val userDeleted: Boolean,
    val type: ThreadEventType,
    val params: Map<String, String>,
    val createdAt: Long,
)

typealias ThreadEventPageResponse = PageResponse<ThreadEventResponse>

/** One page of a thread's history plus the pre-pagination total (the list-endpoint envelope). */
data class ThreadEventListResult(val items: List<ThreadEventResponse>, val total: Long)

/**
 * The thread's history (`thread_events`, V14, append-only): a thin wrapper over the shared [EventLog] mechanics
 * (`infra/db/EventLog.kt`), which takes the acting-user table as a parameter because `infra/` may not name `users/`. A thread write
 * appends through [record] INSIDE its own transaction, so each event commits or rolls back with the change it describes. There is no create
 * endpoint and nothing here ever updates or deletes a row. Message edits and deletes are not history: they go to the audit trail only.
 */
class ThreadEventService(database: R2dbcDatabase) {
    object ThreadEvents : EventLogTable(
        "thread_events",
        "thread_id",
        ThreadService.Threads,
        EventLogActor(UserService.Users, UserService.Users.name, UserService.Users.markedAsDeleted),
    )

    private val log = EventLog(database, ThreadEvents)

    /** Appends one event inside the caller's transaction (a thread write, under the room lock), stamped [at] (the write's own instant). */
    suspend fun record(threadId: UInt, byUserId: UInt, event: ThreadEventDescriptor, at: Long = nowMillis()): UInt =
        log.createInTransaction(threadId, byUserId, event.type.name, event.params, at)

    /** The thread's history, newest first by default (the id descending as the same-instant tiebreaker). */
    suspend fun list(threadId: UInt, paging: PageRequest): ThreadEventListResult {
        val page = log.listFor(threadId, paging)
        return ThreadEventListResult(
            items = page.items.map {
                ThreadEventResponse(
                    id = it.id,
                    userId = it.userId,
                    userName = it.userName,
                    userDeleted = it.userDeleted,
                    type = ThreadEventType.valueOf(it.type),
                    params = it.params,
                    createdAt = it.createdAt,
                )
            },
            total = page.total,
        )
    }
}
