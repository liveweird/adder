package ch.nokillswit.threads

import ch.nokillswit.authz.ConflictException

/**
 * The typed 409 problem of a closed thread (`urn:adder:problem:`, `ProblemDetail.type`): a client tells it apart from the plain
 * conflicts and from `room-archived` by the URI, never by the detail text.
 */
const val THREAD_CLOSED_PROBLEM_TYPE = "urn:adder:problem:thread-closed"

/** A closed thread takes no activity until its owner, the room owner or an ADMIN reopens it (`chat-model.md` "Closed and archived"). */
fun threadClosed(): ConflictException = ConflictException("The thread is closed", problemType = THREAD_CLOSED_PROBLEM_TYPE)
