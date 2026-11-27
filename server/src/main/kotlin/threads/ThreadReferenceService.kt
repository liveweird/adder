package ch.nokillswit.threads

import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.rooms.RoomService
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select

const val MAX_THREAD_REFERENCE_NUMBERS = 100

val THREAD_REFERENCE_SORT_FIELDS = setOf("number")
val THREAD_REFERENCE_DEFAULT_SORT = listOf(SortField("number", descending = false))

@Serializable
data class ThreadReferenceResponse(val number: Int)

data class ThreadReferenceListResult(val items: List<ThreadReferenceResponse>, val total: Long)

internal val ThreadReferenceServiceKey = AttributeKey<ThreadReferenceService>("ThreadReferenceService")

/** A bounded, access-scoped lookup of thread numbers for validating cross-thread references. */
internal class ThreadReferenceService(
    private val database: R2dbcDatabase,
    private val readLimiter: ReadLimiter,
) {
    suspend fun list(callerUserId: UInt, roomId: UInt, numbers: List<Int>, paging: PageRequest): ThreadReferenceListResult =
        readLimiter.run(database, callerUserId, roomId, "thread-references") { budget ->
            val rooms = RoomService.Rooms
            val threads = ThreadService.Threads
            val source = rooms.join(threads, JoinType.LEFT, additionalConstraint = {
                (threads.roomId eq rooms.id) and (threads.number inList numbers) and threads.active()
            })
            budget.arm(this)
            val rows = source.select(rooms.id, threads.number).where {
                (rooms.id eq roomId) and rooms.active() and roomContentReadable(rooms.id, callerUserId)
            }.map { it.getOrNull(threads.number) }.toList()
            if (rows.isEmpty()) throw NotFoundException("Room not found")

            val descending = paging.sort.first { it.name == "number" }.descending
            val matches = rows.filterNotNull().distinct().let { values ->
                if (descending) values.sortedDescending() else values.sorted()
            }
            val offset = (paging.page - 1).toLong() * paging.pageSize.toLong()
            val page = if (offset >= matches.size) {
                emptyList()
            } else {
                matches.drop(offset.toInt()).take(paging.pageSize).map(::ThreadReferenceResponse)
            }
            ThreadReferenceListResult(page, matches.size.toLong())
        }
}
