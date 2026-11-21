package ch.nokillswit.threads

import ch.nokillswit.infra.db.EventLog
import ch.nokillswit.infra.db.EventLogActor
import ch.nokillswit.infra.db.EventLogTable
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.users.UserService
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

/**
 * The kinds of history a thread keeps, PERSISTED and frozen (`thread_events.event_type`; no CHECK, this enum is the whitelist, the V5
 * rule): a value is never renamed or removed. Each carries a string-map `params` the SPA renders in the viewer's language
 * (`chat-model.md` "History"): keys and ids plus snapshots of labels and names, never rendered text.
 */
@Serializable
enum class ThreadEventType {
    /** `{status, statusLabel}`: no title (1.0.0: a title can hold a pasted secret; V23 scrubbed the old rows). */
    CREATED,

    /** `{}`: that the title changed, never what it was or became (1.0.0, as `CREATED`; V23 scrubbed the old `from`/`to`). */
    TITLE_CHANGED,

    /** `{from, to, fromLabel, toLabel}`: a move between statuses of one category. */
    STATUS_CHANGED,

    /** `{from, to, fromLabel, toLabel}`: the move into a CLOSED status. */
    CLOSED,

    /** `{from, to, fromLabel, toLabel}`: the move out of a CLOSED status. */
    REOPENED,

    /** `{fromId, toId, fromName, toName}` */
    OWNER_CHANGED,

    /**
     * `{field, fieldLabel, from?, to?}`: one event per field; an absent side means unset. `from`/`to` exist only for a SELECT,
     * MULTI_SELECT, USER, DATE or NUMBER field: a TEXT or URL change records that it happened, never the text (1.0.0, V23).
     */
    FIELD_CHANGED,

    /** `{from, to, fromLabel, toLabel}`: the workflow PUT moved the thread off a retired status. */
    STATUS_REPLACED,

    /**
     * `{added?, removed?}` (1.2.0, V33): the registry names the thread gained and lost in ONE tag save, each joined with ", "; a side
     * with no change has no key. A 1.1.0 binary decodes types strictly, so a rollback deletes these rows first (`persistence.md`
     * "Rollback below 1.2.0").
     */
    TAGS_CHANGED,
}

/** One event to append: the [type] and its string-map [params]. Built by the pure `thread*Event` functions below. */
data class ThreadEventDescriptor(val type: ThreadEventType, val params: Map<String, String>)

/**
 * The thread was started in its room's initial status [statusKey] ([statusLabel] is the label at that moment). The title is NOT
 * recorded: the history is append-only, so a secret pasted into a title could never be taken back (`chat-model.md` "History").
 */
fun threadCreatedEvent(statusKey: String, statusLabel: String) =
    ThreadEventDescriptor(ThreadEventType.CREATED, mapOf("status" to statusKey, "statusLabel" to statusLabel))

/** The title changed. Neither title is recorded (see [threadCreatedEvent]); the thread itself shows the current one. */
fun threadTitleChangedEvent() = ThreadEventDescriptor(ThreadEventType.TITLE_CHANGED, emptyMap())

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

/** The workflow PUT retired status [fromKey] and moved the thread to its replacement [toKey] (both labels as they are at that moment). */
fun threadStatusReplacedEvent(fromKey: String, fromLabel: String, toKey: String, toLabel: String) = ThreadEventDescriptor(
    ThreadEventType.STATUS_REPLACED,
    mapOf("from" to fromKey, "to" to toKey, "fromLabel" to fromLabel, "toLabel" to toLabel),
)

/** The thread passed from user [fromId] to [toId] (the names as they are now; a soft-deleted user's recorded name is still the name). */
fun threadOwnerChangedEvent(fromId: UInt, toId: UInt, fromName: String, toName: String) = ThreadEventDescriptor(
    ThreadEventType.OWNER_CHANGED,
    mapOf("fromId" to fromId.toString(), "toId" to toId.toString(), "fromName" to fromName, "toName" to toName),
)

/** One history entry: `actorDeleted` marks an actor whose account has since been soft-deleted (the recorded name stays). */
@Serializable
data class ThreadEventResponse(
    val id: UInt,
    val actorId: UInt,
    val actorName: String,
    val actorDeleted: Boolean,
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

    /** Appends the same [event] to every thread in [threadIds] inside the caller's transaction (the workflow PUT's moves), at [at]. */
    suspend fun recordAll(threadIds: List<UInt>, byUserId: UInt, event: ThreadEventDescriptor, at: Long) =
        log.createAllInTransaction(threadIds, byUserId, event.type.name, event.params, at)

    /**
     * The thread's history, newest first by default (the id descending as the same-instant tiebreaker). A `FIELD_CHANGED` of a field
     * in [retiredFields] answers without its `from`/`to`: a retired field's values are hidden ("Stored values survive a workflow edit",
     * `chat-model.md`) and come back if the field is revived. The entry itself stays, so the pages and the total do not change.
     */
    suspend fun list(threadId: UInt, paging: PageRequest, retiredFields: Set<String> = emptySet()): ThreadEventListResult {
        val page = log.listFor(threadId, paging)
        return ThreadEventListResult(
            items = page.items.map {
                ThreadEventResponse(
                    id = it.id,
                    actorId = it.userId,
                    actorName = it.userName,
                    actorDeleted = it.userDeleted,
                    type = ThreadEventType.valueOf(it.type),
                    params = hidingRetiredValues(it.type, it.params, retiredFields),
                    createdAt = it.createdAt,
                )
            },
            total = page.total,
        )
    }
}

/** [params] of a `FIELD_CHANGED` for a field in [retiredFields] without its values; every other event as it is. */
private fun hidingRetiredValues(type: String, params: Map<String, String>, retiredFields: Set<String>): Map<String, String> =
    if (type == ThreadEventType.FIELD_CHANGED.name && params["field"] in retiredFields) params - "from" - "to" else params
