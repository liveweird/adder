package ch.nokillswit.threads

import ch.nokillswit.authz.ConflictException
import ch.nokillswit.authz.TooManyRequestsException

/**
 * The typed 409 problem of a closed thread (`urn:adder:problem:`, `ProblemDetail.type`): a client tells it apart from the plain
 * conflicts and from `room-archived` by the URI, never by the detail text.
 */
const val THREAD_CLOSED_PROBLEM_TYPE = "urn:adder:problem:thread-closed"

/** A closed thread takes no activity until its owner, the room owner or an ADMIN reopens it (`chat-model.md` "Closed and archived"). */
fun threadClosed(): ConflictException = ConflictException("The thread is closed", problemType = THREAD_CLOSED_PROBLEM_TYPE)

const val SEARCH_TIMEOUT_PROBLEM_TYPE = "urn:adder:problem:search-timeout"

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

const val SEARCH_BUSY_PROBLEM_TYPE = "urn:adder:problem:search-busy"

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
