package ch.nokillswit.threads

import ch.nokillswit.infra.db.insertRows
import ch.nokillswit.rooms.RoomService
import ch.nokillswit.users.UserService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select

internal object MessageHereMentions : Table("message_here_mentions") {
    val messageId = reference("message_id", MessageService.Messages)
    val userId = reference("user_id", UserService.Users)
    val threadId = reference("thread_id", ThreadService.Threads)
    val roomId = reference("room_id", RoomService.Rooms)
    override val primaryKey = PrimaryKey(messageId, userId)
}

internal suspend fun replaceHereSnapshot(ref: MessageRef, recipients: Collection<UInt>) {
    MessageHereMentions.deleteWhere { messageId eq ref.messageId }
    MessageHereMentions.insertRows(recipients.distinct()) { user ->
        this[MessageHereMentions.messageId] = ref.messageId
        this[MessageHereMentions.userId] = user
        this[MessageHereMentions.threadId] = ref.threadId
        this[MessageHereMentions.roomId] = ref.roomId
    }
}

internal suspend fun hereSnapshot(messageId: UInt): List<UInt> =
    MessageHereMentions.select(MessageHereMentions.userId).where { MessageHereMentions.messageId eq messageId }
        .map { it[MessageHereMentions.userId].value }.toList()

internal fun threadHereMentionsUser(roomId: UInt, userId: UInt, threadId: org.jetbrains.exposed.v1.core.Column<EntityID<UInt>>) = exists(
    MessageHereMentions.select(MessageHereMentions.messageId).where {
        (MessageHereMentions.roomId eq roomId) and (MessageHereMentions.threadId eq threadId) and
            (MessageHereMentions.userId eq userId)
    },
)
