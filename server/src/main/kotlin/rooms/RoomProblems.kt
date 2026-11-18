package ch.nokillswit.rooms

import ch.nokillswit.authz.ConflictException

/**
 * The typed 409 problems of the room surface (`urn:adder:problem:`, `ProblemDetail.type`): a client tells them apart from the plain
 * conflicts (a name clash, a full key budget, the member cap) by the URI, never by the detail text.
 */
const val ROOM_ARCHIVED_PROBLEM_TYPE = "urn:adder:problem:room-archived"

/** An archived room is read-only for name, description and workflow (and, from 0.3, content); unarchive it first. */
fun roomArchived(): ConflictException = ConflictException("The room is archived", problemType = ROOM_ARCHIVED_PROBLEM_TYPE)

const val LAST_OWNER_PROBLEM_TYPE = "urn:adder:problem:last-owner"

/** The write would leave the room without an OWNER whose user is active: promote someone else first (`chat-model.md` "Membership"). */
fun lastOwner(): ConflictException = ConflictException("The room needs at least one active owner", problemType = LAST_OWNER_PROBLEM_TYPE)

const val STATUS_IN_USE_PROBLEM_TYPE = "urn:adder:problem:status-in-use"

/**
 * A workflow change would retire or re-categorise a status that threads still use and carries no valid replacement for them: [keys] are
 * the statuses at fault (the `ProblemDetail.keys` member), so the SPA can name them in the retire dialog.
 */
fun statusInUse(keys: List<String>): ConflictException = ConflictException(
    "These statuses are used by threads: ${keys.joinToString()}. Retiring one needs a replacement of the same category; " +
        "a status's category cannot change while threads use it",
    problemType = STATUS_IN_USE_PROBLEM_TYPE,
    keys = keys,
)

const val FIELD_TYPE_IMMUTABLE_PROBLEM_TYPE = "urn:adder:problem:field-type-immutable"

/** A field's `type` is fixed per key for the life of the room (retired keys included): [keys] are the fields the change would retype. */
fun fieldTypeImmutable(keys: List<String>): ConflictException = ConflictException(
    "A field's type cannot change once its key exists: ${keys.joinToString()}",
    problemType = FIELD_TYPE_IMMUTABLE_PROBLEM_TYPE,
    keys = keys,
)
