package ch.nokillswit.threads

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.requireThreadSteward
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.notify.LiveHint
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.infra.notify.notifyLive
import ch.nokillswit.infra.revision.requireRevision
import ch.nokillswit.rooms.readerGuard
import ch.nokillswit.tags.CODE_POINT_ORDER
import ch.nokillswit.tags.TagCategoryService
import ch.nokillswit.users.UserService
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

val ThreadTagServiceKey = AttributeKey<ThreadTagService>("ThreadTagService")

/**
 * The curated tags of a thread (`thread_tags`, V33; Hammurabi's `AdrTagService`, shaped like [ThreadStatusService.setOwner]). The save is
 * ONE thread write under the room lock ([withThreadWrite]) so the diff, the `TAGS_CHANGED` event, the revision bump and the hint commit
 * together; [forThreads] and [idsOf] run in the CALLER's transaction (the list's and the read's own). **Cross-feature** (`persistence.md`):
 * this service READS the registry's `tags` rows (`TagCategoryService.Tags`, names and the retired flag) and UPDATES the thread row's
 * `revision` and `updated_at`; it writes `thread_tags` and, through [ThreadEventService], `thread_events`. It never touches
 * `last_activity_at`, the room's activity or the `ThreadNotices` port: a tag change is history, not activity (1.2 A4).
 */
class ThreadTagService internal constructor(
    private val database: R2dbcDatabase,
    private val events: ThreadEventService,
) {
    /** The join (V33): a thread's tags. Hard-deleted; `tag_id` has no `ON DELETE` rule because a registry tag is only ever retired. */
    object ThreadTags : Table("thread_tags") {
        val threadId = reference("thread_id", ThreadService.Threads)
        val tagId = reference("tag_id", TagCategoryService.Tags)
        val addedAt = long("added_at")
        val addedBy = reference("added_by", UserService.Users)
        override val primaryKey = PrimaryKey(threadId, tagId)
    }

    private val tags = TagCategoryService.Tags

    /**
     * The tags of every thread in [threadIds] in ONE statement (a list page, or the one thread of a read), in code point order per thread
     * (`CODE_POINT_ORDER`, the registry's one order of names); a retired tag is marked. A thread with none is absent. Runs inside the
     * caller's transaction and its budget: no lock, ids in, names out.
     */
    suspend fun forThreads(threadIds: Collection<UInt>): Map<UInt, List<ThreadTag>> {
        if (threadIds.isEmpty()) return emptyMap()
        return ThreadTags.innerJoin(tags).select(ThreadTags.threadId, tags.name, tags.markedAsDeleted)
            .where { ThreadTags.threadId inList threadIds.distinct() }.toList()
            .groupBy({ it[ThreadTags.threadId].value }) { ThreadTag(it[tags.name], it[tags.markedAsDeleted]) }
            .mapValues { (_, list) -> list.sortedWith(compareBy(CODE_POINT_ORDER) { it.name }) }
    }

    /**
     * The registry ids of [names] (normalised), RETIRED tags included: the `threadTag` filter still finds the threads that kept a retired
     * tag (1.2 A6). A name the registry has never held is simply absent, so the filter matches nothing for it. Inside the caller's
     * transaction (the list's own).
     */
    suspend fun idsOf(names: Collection<String>): List<UInt> {
        if (names.isEmpty()) return emptyList()
        return tags.select(tags.id).where { tags.name inList names }.toList().map { it[tags.id].value }
    }

    /** The owner and the closed flag of thread [number] read WITHOUT a lock, or null when there is none: the route's steward pre-check. */
    suspend fun stewardView(roomId: UInt, number: Int): ThreadStewardView? = suspendTransaction(database) {
        val threads = ThreadService.Threads
        threads.select(threads.ownerId, threads.closedAt).where { (threads.roomId eq roomId) and (threads.number eq number) }
            .toList().singleOrNull()?.let { ThreadStewardView(it[threads.ownerId].value, it[threads.closedAt] != null) }
    }

    /**
     * Replaces thread [number]'s tags with [raw] as a DIFF in ONE thread write, in this order: the reader guard on the fresh caller and
     * standing, the thread (`404`), the steward guard when the thread is CLOSED (`403`: on an open thread any reader tags, on a closed one
     * only its stewards, 1.2 D1), an archived room `409`, a stale [expectedRevision] `409 revision-conflict` (the tag save rides the
     * thread's own revision, 1.2 A3), every name must be a LIVE registry tag (`400`, a fixed detail that echoes no name: a retired tag the
     * thread still carries included, so a save that keeps it is refused until the picker drops it, 1.2 A6), then the diff. The same set
     * again is null (a `204` with no write). A real change deletes and inserts `thread_tags` rows, bumps the thread's `revision` and
     * `updated_at`, appends ONE `TAGS_CHANGED` event and emits `thread.updated`.
     *
     * **A tag retired a moment earlier can still be attached.** The foreign key takes `FOR KEY SHARE` on the tag row and the registry's
     * retirement is a non-key `UPDATE`, which does not conflict with it; locking the live tags `FOR SHARE` here would close that, at the
     * price of a possible lock-order deadlock with the registry's multi-row update. The outcome is harmless and already a legal state: the
     * thread then carries a tag the registry retired in the same instant, shown struck through exactly as it would be a second later.
     */
    suspend fun replace(
        roomId: UInt,
        number: Int,
        caller: CallerPrincipal,
        expectedRevision: Long,
        raw: ThreadTagsRequest,
    ): ThreadTagChange? {
        val request = normalizedThreadTags(raw)
        validateThreadTags(request)
        return database.withThreadWrite(
            roomId, number, caller, readerGuard,
            actGuard = { fresh, standing, thread -> if (thread.closed) requireThreadSteward(fresh, standing, thread.ownerId) },
        ) { write ->
            write.requireRoomActive()
            val thread = write.thread
            requireRevision(expectedRevision, thread.revision)
            val live = liveTagIds(request.tags)
            if (request.tags.any { it !in live }) throw BadRequestException("Every tag must be one of the registry's live tags")
            val current = currentTagIds(thread.id)
            val diff = TagDiff(
                added = request.tags.filter { it !in current }.sortedWith(CODE_POINT_ORDER),
                removed = current.keys.filter { it !in request.tags }.sortedWith(CODE_POINT_ORDER),
            )
            if (diff.isEmpty) return@withThreadWrite null
            val stamp = nowMillis()
            if (diff.removed.isNotEmpty()) {
                val dropped = diff.removed.map { current.getValue(it) }
                ThreadTags.deleteWhere { (ThreadTags.threadId eq thread.id) and (ThreadTags.tagId inList dropped) }
            }
            diff.added.forEach { name ->
                ThreadTags.insert {
                    it[threadId] = thread.id
                    it[tagId] = live.getValue(name)
                    it[addedAt] = stamp
                    it[addedBy] = caller.userId
                }
            }
            val revision = thread.revision + 1
            val threads = ThreadService.Threads
            // Not activity: `last_activity_at` and the room's rail stay where they are (1.2 A4); only the revision and `updated_at` move.
            threads.update({ threads.id eq thread.id }) {
                it[threads.revision] = revision
                it[updatedAt] = stamp
            }
            events.record(thread.id, caller.userId, threadTagsChangedEvent(diff.added, diff.removed), stamp)
            notifyLive(LiveHint(LiveKind.THREAD_UPDATED, roomId, threadNumber = number))
            ThreadTagChange(thread.id, revision, diff)
        }
    }

    /** The LIVE registry tags among [names] by name (empty in, no query). */
    private suspend fun liveTagIds(names: List<String>): Map<String, UInt> {
        if (names.isEmpty()) return emptyMap()
        return tags.select(tags.id, tags.name).where { tags.active() and (tags.name inList names) }.toList()
            .associate { it[tags.name] to it[tags.id].value }
    }

    /** The thread's current tags by name, retired ones included. */
    private suspend fun currentTagIds(threadId: UInt): Map<String, UInt> =
        ThreadTags.innerJoin(tags).select(ThreadTags.tagId, tags.name).where { ThreadTags.threadId eq threadId }.toList()
            .associate { it[tags.name] to it[ThreadTags.tagId].value }
}

/**
 * `EXISTS (SELECT 1 FROM thread_tags WHERE thread_id = <threadId> AND tag_id IN (<tagIds>))`: the thread carries any of the tags, the
 * `threadTag` filter's predicate (any-of; the primary key `(thread_id, tag_id)` answers it by probing the correlated thread). An empty
 * [tagIds] (every requested name unknown to the registry) matches nothing.
 */
internal fun threadCarriesAnyThreadTag(threadId: Column<EntityID<UInt>>, tagIds: List<UInt>): Op<Boolean> {
    if (tagIds.isEmpty()) return Op.FALSE
    val join = ThreadTagService.ThreadTags
    return exists(join.select(join.threadId).where { (join.threadId eq threadId) and (join.tagId inList tagIds) })
}
