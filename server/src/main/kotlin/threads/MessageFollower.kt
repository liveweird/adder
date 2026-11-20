package ch.nokillswit.threads

/** Where one message lives: the ids a follower needs, so it never reads the thread again ([threadNumber] is the per-room number). */
internal class MessageRef(val roomId: UInt, val threadId: UInt, val threadNumber: Int, val messageId: UInt)

/**
 * Something derived from a message's body or lifecycle that must change with it, in the SAME transaction (`persistence.md` "Threads
 * (V14)"). The message writes of [MessageService] (a post, a thread's opening message, an edit, a delete) call every follower INSIDE the
 * write under the room lock, AFTER the row's own statement, the thread and room counters and the write's own live hint
 * (`message.posted`, `thread.created`, `message.edited`, `message.deleted`), so a follower that throws rolls the whole write back, a
 * follower may assume the row it reads is already the new one, and a hint a follower emits (`notification.created`) follows the write's
 * own on the wire. The default of each call does nothing, so a follower overrides only what it derives.
 *
 * [posted] and [edited] answer the ids of the people the follower notified (nobody, by default); the service hands the union to the route
 * as `notified`. `body` is the sanitised body as written; a follower that parses it parses it again (the service has no parsed form but
 * the hashtags).
 */
internal interface MessageFollower {
    /** A message was inserted: a post, or the opening message of a new thread. */
    suspend fun posted(ref: MessageRef, authorId: UInt, body: String, at: Long): List<UInt> = emptyList()

    /** The body of a message was overwritten (never called for the same body again). */
    suspend fun edited(ref: MessageRef, authorId: UInt, body: String): List<UInt> = emptyList()

    /** The message became a tombstone (the author's own delete and a moderator's alike): what was derived from its body goes. */
    suspend fun deleted(ref: MessageRef) {}
}
