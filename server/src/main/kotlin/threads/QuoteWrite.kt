package ch.nokillswit.threads

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.requireRoomMember
import ch.nokillswit.authz.requireRoomReader
import ch.nokillswit.infra.db.active
import ch.nokillswit.rooms.freshCaller
import ch.nokillswit.rooms.readStanding
import ch.nokillswit.rooms.withRoomWrites
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

internal class QuotedThreadWrite(val destination: ThreadWrite, val quoted: QuotedMessage)

/** Resolves a lock address only when the caller can read it now; the locked check below repeats every condition. */
private suspend fun R2dbcDatabase.quoteSourceRoom(quotedMessageId: UInt, caller: CallerPrincipal): UInt? = suspendTransaction(this) {
    val messages = MessageService.Messages
    val threads = ThreadService.Threads
    val source = messages.innerJoin(threads).select(threads.roomId)
        .where { (messages.id eq quotedMessageId) and messages.active() and threads.active() }
        .toList().singleOrNull()?.get(threads.roomId)?.value
        ?: return@suspendTransaction null
    val fresh = freshCaller(caller) ?: return@suspendTransaction null
    try {
        requireRoomReader(fresh, readStanding(source, fresh.userId, lock = false))
        source
    } catch (_: NotFoundException) {
        null
    }
}

/**
 * A quoting post across one or two rooms. It resolves the source room without disclosing it, locks source and destination in ascending
 * order, re-reads the current caller and both standings, then repeats every source and destination check under those locks. The source
 * can be a closed or merged historical thread. A deleted thread or message is unavailable.
 */
internal suspend fun <T> R2dbcDatabase.withQuotedThreadWrite(
    roomId: UInt,
    number: Int,
    quotedMessageId: UInt,
    caller: CallerPrincipal,
    block: suspend (QuotedThreadWrite) -> T,
): T {
    val sourceRoomId = quoteSourceRoom(quotedMessageId, caller)
    return withRoomWrites(listOfNotNull(roomId, sourceRoomId), caller) { fresh, standings ->
        val destinationStanding = standings[roomId]
        requireRoomMember(fresh, destinationStanding)
        val destination = threadRow(roomId, number)
        destination.requireUnmerged()
        val standing = destinationStanding ?: throw NotFoundException("Room not found")
        val destinationWrite = ThreadWrite(fresh, standing, destination)
        destinationWrite.requireRoomActive()
        destinationWrite.requireThreadOpen()
        val source = sourceRoomId ?: throw quoteUnavailable()
        try {
            requireRoomReader(fresh, standings[source])
        } catch (_: NotFoundException) {
            // An inaccessible source is indistinguishable from an unknown message. Do not disclose that the room exists.
            throw quoteUnavailable()
        }
        val quoted = checkedQuote(source, quotedMessageId, fresh.userId)
        block(QuotedThreadWrite(destinationWrite, quoted))
    }
}
