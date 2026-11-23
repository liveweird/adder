package ch.nokillswit.home

import ch.nokillswit.authz.RoomAccessType
import ch.nokillswit.threads.GroupMention
import ch.nokillswit.threads.MentionedUser
import ch.nokillswit.workflows.StatusColour
import kotlinx.serialization.Serializable

/** The signed-in caller's small, bounded workspace overview. */
@Serializable
data class HomeResponse(
    val quotes: List<HomeQuote>,
    val ownedThreads: List<HomeThread>,
    val watchedThreads: List<HomeThread>,
    val ownedRooms: List<HomeRoom>,
)

/** A recent reply by another person that quotes one of the caller's live messages. */
@Serializable
data class HomeQuote(
    val roomId: UInt,
    val roomName: String,
    val roomArchived: Boolean,
    val threadNumber: Int,
    val threadTitle: String,
    val messageId: UInt,
    val authorName: String,
    val body: String,
    val truncated: Boolean,
    val mentions: List<MentionedUser>,
    val groupMentions: List<GroupMention>,
    val createdAt: Long,
    val unread: Boolean,
)

/** A compact thread projection shared by the owned and watched sections. */
@Serializable
data class HomeThread(
    val roomId: UInt,
    val roomName: String,
    val roomArchived: Boolean,
    val number: Int,
    val title: String,
    val status: String,
    val statusLabel: String,
    val statusColour: StatusColour,
    val closed: Boolean,
    val unreadCount: Int,
    val lastActivityAt: Long,
)

/** An active room the caller owns, including owner work waiting in its join queue. */
@Serializable
data class HomeRoom(
    val id: UInt,
    val name: String,
    val accessType: RoomAccessType,
    val archived: Boolean,
    val unread: Boolean,
    val pendingJoinRequests: Long,
    val lastActivityAt: Long,
)
