package ch.nokillswit.infra

/**
 * The ONE registry of the typed RFC 7807 problems (`ProblemDetail.type`, `urn:adder:problem:<name>`): every throw site that types a
 * problem takes its URI from here (the per-feature `*_PROBLEM_TYPE` constants are aliases of these), so the set the SPA branches on is
 * one list. `ProblemTypesTest` scans the main sources for a quoted `urn:adder:problem:` literal outside this file and diffs [ALL]
 * against the list `ProblemDetail.type` documents in `openapi/documentation.yaml`; a new type lands here, in [ALL] and in that list
 * in the same commit. Every other problem is `about:blank`.
 */
object ProblemTypes {
    /** A stale `X-Expected-Revision` (409). */
    const val REVISION_CONFLICT = "urn:adder:problem:revision-conflict"

    /** The write would leave a room without an active OWNER (409). */
    const val LAST_OWNER = "urn:adder:problem:last-owner"

    /** An archived room is read-only (409). */
    const val ROOM_ARCHIVED = "urn:adder:problem:room-archived"

    /** A closed thread takes no activity until it is reopened (409). */
    const val THREAD_CLOSED = "urn:adder:problem:thread-closed"

    /** A workflow change would retire or re-categorise a status threads still use (409, with `keys`). */
    const val STATUS_IN_USE = "urn:adder:problem:status-in-use"

    /** A field's type is fixed per key for the life of the room (409, with `keys`). */
    const val FIELD_TYPE_IMMUTABLE = "urn:adder:problem:field-type-immutable"

    /** The room's write lock stayed busy past the bounded wait (429, `Retry-After`). */
    const val ROOM_BUSY = "urn:adder:problem:room-busy"

    /** The room already holds its 50 pins (409). */
    const val PIN_LIMIT = "urn:adder:problem:pin-limit"

    /** The message holds its maximum of distinct emoji, or the caller their maximum on it (409). */
    const val REACTION_LIMIT = "urn:adder:problem:reaction-limit"

    /** A search outran its deadline (429, `Retry-After`). */
    const val SEARCH_TIMEOUT = "urn:adder:problem:search-timeout"

    /** The server already runs its maximum of searches (429, `Retry-After`). */
    const val SEARCH_BUSY = "urn:adder:problem:search-busy"

    /** A filtered thread list or a tag list outran its deadline (429, `Retry-After`). */
    const val READ_TIMEOUT = "urn:adder:problem:read-timeout"

    /** The caller or the server already runs its maximum of list reads (429, `Retry-After`). */
    const val READ_BUSY = "urn:adder:problem:read-busy"

    /** The live channel is unavailable: stopping, a full socket table or a deaf listener (503, `Retry-After`). */
    const val LIVE_UNAVAILABLE = "urn:adder:problem:live-unavailable"

    /** The caller already holds their per-user maximum of live sockets (429, `Retry-After`). */
    const val LIVE_SOCKET_LIMIT = "urn:adder:problem:live-socket-limit"

    /** Every registered URI; the spec's list and the main sources are diffed against it. */
    val ALL: Set<String> = setOf(
        REVISION_CONFLICT, LAST_OWNER, ROOM_ARCHIVED, THREAD_CLOSED, STATUS_IN_USE, FIELD_TYPE_IMMUTABLE, ROOM_BUSY, PIN_LIMIT,
        REACTION_LIMIT, SEARCH_TIMEOUT, SEARCH_BUSY, READ_TIMEOUT, READ_BUSY, LIVE_UNAVAILABLE, LIVE_SOCKET_LIMIT,
    )
}
