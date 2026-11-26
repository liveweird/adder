package ch.nokillswit.threads

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.isThreadSteward
import ch.nokillswit.authz.requireThreadSteward
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.notify.LiveHint
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.infra.notify.notifyLive
import ch.nokillswit.rooms.RoomMemberService
import ch.nokillswit.rooms.RoomService
import ch.nokillswit.rooms.RoomWorkflowService
import ch.nokillswit.rooms.readerGuard
import ch.nokillswit.workflows.StatusCategory
import ch.nokillswit.workflows.shown
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.update

val ThreadStatusServiceKey = AttributeKey<ThreadStatusService>("ThreadStatusService")

/**
 * The thread's two single-value sets, each ONE thread write under the room lock ([withThreadWrite]): the status move (open, close,
 * reopen) and the owner hand-over. Last writer wins by design (no revision token); the history records every change
 * (`chat-model.md` "History"), and every change stamps its event, the activity bump and `updated_at` with ONE instant. The thread
 * row's own `revision` (the details PUT's token) is NOT bumped by either.
 */
class ThreadStatusService internal constructor(
    private val database: R2dbcDatabase,
    private val rooms: RoomService,
    private val workflows: RoomWorkflowService,
    private val members: RoomMemberService,
    private val events: ThreadEventService,
    private val notices: ThreadNotices,
    private val watches: ThreadWatchService,
) {
    /**
     * Moves thread [number] to the status [targetKey], in this order: the target must be an ACTIVE status of the room (an unknown or
     * retired key is the `400`, which discloses nothing: every reader can read the workflow); [statusTransition] then decides with the
     * FRESH caller (a move that enters or leaves CLOSED needs a steward: `403`); an archived room is `409`; the same status again is
     * null (a `204` with no write). A closed thread is deliberately NOT refused: a steward reopens it or moves it between CLOSED statuses.
     * The move sets `closed_at` on the close (kept across a CLOSED to CLOSED move) or clears it on a move into an OPEN status,
     * records `STATUS_CHANGED`, `CLOSED` or `REOPENED` and bumps the thread and the room.
     */
    suspend fun setStatus(roomId: UInt, number: Int, caller: CallerPrincipal, targetKey: String): StatusMove? =
        database.withThreadWrite(roomId, number, caller, readerGuard) { write ->
            val thread = write.thread
            val snapshot = workflows.snapshot(roomId)
            val target = snapshot.statuses.firstOrNull { it.key == targetKey && !it.retired }
                ?: throw BadRequestException("Unknown or retired status '${targetKey.shown()}'")
            val current = snapshot.statuses.single { it.key == thread.statusKey }
            val steward = isThreadSteward(write.caller, write.standing, thread.ownerId)
            val transition = statusTransition(current.key, current.category, target.key, target.category, steward)
            write.requireRoomActive()
            if (transition == null) return@withThreadWrite null
            val stamp = nowMillis()
            ThreadService.Threads.update({ ThreadService.Threads.id eq thread.id }) {
                it[statusKey] = target.key
                it[closedAt] = if (target.category == StatusCategory.CLOSED) thread.closedAt ?: stamp else null
                it[lastActivityAt] = stamp
                it[updatedAt] = stamp
            }
            val eventId = events.record(
                thread.id,
                caller.userId,
                threadStatusEvent(transition, current.key, current.label, target.key, target.label),
                stamp,
            )
            rooms.touchActivity(roomId, stamp, caller.userId)
            notifyLive(LiveHint(LiveKind.THREAD_STATUS_CHANGED, roomId, threadNumber = number))
            // The watchers hear of it (1.1 D3; the actor never); the hint above comes first on the wire, the notice's own follows it.
            val audience = NoticeAudience.ofStatusMove(transition, caller.userId, watches.recipientIds(thread.id))
            val told = notices.forThread(roomId, thread.id, number, caller.userId, audience, eventId)
            StatusMove(thread.id, current.key, target.key, transition, told)
        }

    /**
     * Passes thread [number] to [newOwnerId]: the steward guard on the FRESH caller and row ([requireThreadSteward], a `403`), an
     * archived room `409`, a closed thread `409` (reopen it first), the current owner again is null (a `204` with no write, even
     * if they have left), else the new owner must be an ACTIVE member of the room (`400`). Records `OWNER_CHANGED` (ids plus the
     * names as they are now) and bumps the thread and the room, then tells the new owner and the other watchers ([OwnerMove.notices]: the
     * new owner gets no notice when the caller took the thread themself). A removed or deleted owner keeps the thread, inert, until a
     * steward does this.
     */
    suspend fun setOwner(
        roomId: UInt,
        number: Int,
        caller: CallerPrincipal,
        newOwnerId: UInt,
        mutationId: String? = null,
    ): OwnerMove? =
        database.withThreadWrite(
            roomId, number, caller, readerGuard,
            actGuard = { fresh, standing, thread -> requireThreadSteward(fresh, standing, thread.ownerId) },
        ) { write ->
            write.requireRoomActive()
            write.requireThreadOpen()
            val thread = write.thread
            // The same owner again is a no-op even if they have left the room (nothing is set, so there is nothing to validate).
            if (newOwnerId == thread.ownerId) return@withThreadWrite null
            if (members.activeMemberIds(roomId, listOf(newOwnerId.toLong())).isEmpty()) {
                throw BadRequestException("The new owner must be an active member of the room")
            }
            val names = userNames(listOf(thread.ownerId, newOwnerId))
            val stamp = nowMillis()
            ThreadService.Threads.update({ ThreadService.Threads.id eq thread.id }) {
                it[ownerId] = newOwnerId
                it[lastActivityAt] = stamp
                it[updatedAt] = stamp
            }
            val eventId = events.record(
                thread.id,
                caller.userId,
                threadOwnerChangedEvent(thread.ownerId, newOwnerId, names.getValue(thread.ownerId), names.getValue(newOwnerId)),
                stamp,
            )
            // Handing over the thread can start a watch, while an explicit off survives this implicit change.
            watches.addInTransaction(thread.id, newOwnerId, stamp)
            rooms.touchActivity(roomId, stamp, caller.userId)
            notifyLive(
                LiveHint(
                    LiveKind.THREAD_OWNER_CHANGED,
                    roomId,
                    threadNumber = number,
                    mutationId = mutationId,
                    originUserId = caller.userId.takeIf { mutationId != null },
                ),
            )
            // The new owner hears `THREAD_ASSIGNED` (not when they took it themself), the other watchers `THREAD_OWNER_CHANGED`.
            val audience = NoticeAudience.ofHandOver(caller.userId, newOwnerId, watches.recipientIds(thread.id))
            val told = notices.forThread(roomId, thread.id, number, caller.userId, audience, eventId)
            OwnerMove(thread.id, thread.ownerId, newOwnerId, told)
        }
}

/** What a status move wrote, for the route's audit lines; [notices] is what it told the thread's watchers. */
data class StatusMove(
    val threadId: UInt,
    val from: String,
    val to: String,
    val transition: StatusTransition,
    val notices: NoticeOutcome,
)

/**
 * What an owner hand-over wrote, for the route's audit lines; [notices] is what it told people (the new owner, unless the caller took
 * the thread themself, and the other watchers).
 */
data class OwnerMove(val threadId: UInt, val fromUserId: UInt, val toUserId: UInt, val notices: NoticeOutcome)
