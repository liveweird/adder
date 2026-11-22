package ch.nokillswit.threads

import ch.nokillswit.authz.ConflictException
import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.authz.TypedBadRequestException
import ch.nokillswit.infra.ProblemTypes

/**
 * The typed 409 problem of a closed thread (`urn:adder:problem:`, `ProblemDetail.type`): a client tells it apart from the plain
 * conflicts and from `room-archived` by the URI, never by the detail text.
 */
const val THREAD_CLOSED_PROBLEM_TYPE = ProblemTypes.THREAD_CLOSED

/** A closed thread takes no activity until its owner, the room owner or an ADMIN reopens it (`chat-model.md` "Closed and archived"). */
fun threadClosed(): ConflictException = ConflictException("The thread is closed", problemType = THREAD_CLOSED_PROBLEM_TYPE)

const val QUOTE_UNAVAILABLE_PROBLEM_TYPE = ProblemTypes.QUOTE_UNAVAILABLE

/**
 * The message a post quotes cannot be quoted: unknown, of another thread or room, or deleted - ONE answer for all of them, with a fixed
 * detail that echoes no id (`chat-model.md` "Quotes (1.3)"). A `400`, never a `404`: on the message POST a `404` means the thread or
 * the room is gone (the `template-gone` precedent), and the SPA reads it that way.
 */
fun quoteUnavailable(): TypedBadRequestException =
    TypedBadRequestException("The quoted message is not available", problemType = QUOTE_UNAVAILABLE_PROBLEM_TYPE)

const val REACTION_LIMIT_PROBLEM_TYPE = ProblemTypes.REACTION_LIMIT

/**
 * A reaction would pass a limit of `chat-model.md` "Reactions (V20)": a 21st distinct emoji on the message, or a 6th emoji of the caller's
 * on it. Typed, so a client tells it from the plain conflicts by the URI. The detail names no emoji.
 */
fun reactionLimit(): ConflictException =
    ConflictException("The message has reached its reaction limit", problemType = REACTION_LIMIT_PROBLEM_TYPE)

const val SEARCH_TIMEOUT_PROBLEM_TYPE = ProblemTypes.SEARCH_TIMEOUT

/** Seconds the `Retry-After` header of a [searchTimeout] answer names: a heavy search is not helped by an instant retry. */
const val SEARCH_TIMEOUT_RETRY_AFTER_SECONDS = 2

/**
 * A search outran its statement budget (`search.timeoutSeconds`): a `429` with `Retry-After`, typed so a client tells it apart from a
 * rate-limit bucket by the URI. 429 (not 503/504) because it is already a cross-cutting status on every operation, the `room-busy`
 * precedent. The detail never names the term.
 */
fun searchTimeout(): TooManyRequestsException = TooManyRequestsException(
    "The search took too long - narrow it and retry in a moment",
    problemType = SEARCH_TIMEOUT_PROBLEM_TYPE,
    retryAfterSeconds = SEARCH_TIMEOUT_RETRY_AFTER_SECONDS,
)

const val SEARCH_BUSY_PROBLEM_TYPE = ProblemTypes.SEARCH_BUSY

/** Seconds the `Retry-After` header of a [searchBusy] answer names: a slot frees within a search's own budget, usually in milliseconds. */
const val SEARCH_BUSY_RETRY_AFTER_SECONDS = 1

/**
 * The process already runs its maximum of concurrent searches (`search.maxConcurrent`): a `429` with `Retry-After`, typed APART from
 * [searchTimeout] because the remedy differs (this one is the server's load, not the query: retry shortly, nothing to narrow). Sent at
 * once, never after a wait, so a flood cannot hold pool connections. The detail never names the term.
 */
fun searchBusy(): TooManyRequestsException = TooManyRequestsException(
    "Too many searches are running - retry in a moment",
    problemType = SEARCH_BUSY_PROBLEM_TYPE,
    retryAfterSeconds = SEARCH_BUSY_RETRY_AFTER_SECONDS,
)

const val PIN_LIMIT_PROBLEM_TYPE = ProblemTypes.PIN_LIMIT

/** The room already holds its [MAX_ROOM_PINS] pins: unpin something first (`chat-model.md` "Pins"). Unpinning is never refused. */
fun pinLimit(): ConflictException = ConflictException("The room already has $MAX_ROOM_PINS pins", problemType = PIN_LIMIT_PROBLEM_TYPE)

const val READ_TIMEOUT_PROBLEM_TYPE = ProblemTypes.READ_TIMEOUT

/** Seconds the `Retry-After` header of a [readTimeout] answer names: a list that was too slow is not helped by an instant retry. */
const val READ_TIMEOUT_RETRY_AFTER_SECONDS = 2

/**
 * A filtered thread list or a room's tag list outran its statement budget (`threads.readTimeoutSeconds`): a `429` with `Retry-After`,
 * typed APART from [searchTimeout] (a client that shows "narrow your search" for that one must not show it for a list) and from a
 * rate-limit bucket; the one type serves both reads. The detail names no parameter value.
 */
fun readTimeout(): TooManyRequestsException = TooManyRequestsException(
    "The list took too long - retry in a moment, or narrow the filter",
    problemType = READ_TIMEOUT_PROBLEM_TYPE,
    retryAfterSeconds = READ_TIMEOUT_RETRY_AFTER_SECONDS,
)

const val READ_BUSY_PROBLEM_TYPE = ProblemTypes.READ_BUSY

/** Seconds the `Retry-After` header of a [readBusy] answer names: a permit frees within a read's own budget, usually in milliseconds. */
const val READ_BUSY_RETRY_AFTER_SECONDS = 1

/**
 * The caller already has its maximum of reads in flight, or the process runs its maximum (`threads.readMaxConcurrent`) and none freed
 * within a short wait: a `429` with `Retry-After`, typed APART from [readTimeout] because the remedy differs (this is load, not the
 * query: retry shortly, nothing to narrow). Sent before any pool connection is asked for. The detail names no parameter value.
 */
fun readBusy(): TooManyRequestsException = TooManyRequestsException(
    "The server is busy with other lists - retry in a moment",
    problemType = READ_BUSY_PROBLEM_TYPE,
    retryAfterSeconds = READ_BUSY_RETRY_AFTER_SECONDS,
)

/**
 * The caller's previous "Mark all as read" has not finished (one in flight per person, `ThreadReadService.markRoomRead`): the same
 * `read-busy` type and `Retry-After` as [readBusy], with its own words. Sent before any write or notice transaction (the visibility
 * read has run), so a refused call holds no pooled connection.
 */
fun readAllBusy(): TooManyRequestsException = TooManyRequestsException(
    "Your previous mark-all-as-read is still running - retry in a moment",
    problemType = READ_BUSY_PROBLEM_TYPE,
    retryAfterSeconds = READ_BUSY_RETRY_AFTER_SECONDS,
)
