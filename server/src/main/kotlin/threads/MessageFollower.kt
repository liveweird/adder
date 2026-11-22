package ch.nokillswit.threads

/**
 * Where one message lives: the ids a follower needs, so it never reads the thread again ([threadNumber] is the per-room number).
 * [opening] is true for the first message of its thread (the notice audience of a thread's opening message is the whole room); it is
 * exact for a post and an edit (asked lazily: an edit that changes no mention never pays for the query), and a delete does not read it.
 * [quotedAuthor] is the author of the message this one quotes (1.3), asked the same way: a post knows it (its quote check read it), an
 * edit reads it only past its early returns, a delete never.
 */
internal class MessageRef(
    val roomId: UInt,
    val threadId: UInt,
    val threadNumber: Int,
    val messageId: UInt,
    private val openingOf: suspend () -> Boolean,
    private val quotedAuthorOf: suspend () -> UInt? = { null },
) {
    constructor(roomId: UInt, threadId: UInt, threadNumber: Int, messageId: UInt, opening: Boolean, quotedAuthor: UInt? = null) :
        this(roomId, threadId, threadNumber, messageId, { opening }, { quotedAuthor })

    private var openingKnown: Boolean? = null
    private var quotedKnown = false
    private var quotedAuthorKnown: UInt? = null

    /** Whether this is the thread's first message; an edit asks lazily (one query, only when its mentions changed), a post knows. */
    suspend fun opening(): Boolean = openingKnown ?: openingOf().also { openingKnown = it }

    /** The author of the message this one quotes, or null when it quotes none; an edit asks lazily (one query), a post knows. */
    suspend fun quotedAuthor(): UInt? {
        if (!quotedKnown) {
            quotedAuthorKnown = quotedAuthorOf()
            quotedKnown = true
        }
        return quotedAuthorKnown
    }
}

/**
 * Something derived from a message's body or lifecycle that must change with it, in the SAME transaction (`persistence.md` "Threads
 * (V14)"). The message writes of [MessageService] (a post, a thread's opening message, an edit, a delete) call every follower INSIDE the
 * write under the room lock, AFTER the row's own statement, the thread and room counters and the write's own live hint
 * (`message.posted`, `thread.created`, `message.edited`, `message.deleted`), so a follower that throws rolls the whole write back, a
 * follower may assume the row it reads is already the new one, and a hint a follower emits (`notification.created`) follows the write's
 * own on the wire. The default of each call does nothing, so a follower overrides only what it derives.
 *
 * [posted] and [edited] answer what the follower notified ([NoticeOutcome.NONE] by default); the service hands the sum to the route
 * as `notices`. `body` is the sanitised body as written; a follower that parses it parses it again (the service has no parsed form but
 * the hashtags).
 */
internal interface MessageFollower {
    /** A message was inserted: a post, or the opening message of a new thread. */
    suspend fun posted(ref: MessageRef, authorId: UInt, body: String, at: Long): NoticeOutcome = NoticeOutcome.NONE

    /** The body of a message was overwritten (never called for the same body again). */
    suspend fun edited(ref: MessageRef, authorId: UInt, body: String): NoticeOutcome = NoticeOutcome.NONE

    /** The message became a tombstone (the author's own delete and a moderator's alike): what was derived from its body goes. */
    suspend fun deleted(ref: MessageRef) {}
}
