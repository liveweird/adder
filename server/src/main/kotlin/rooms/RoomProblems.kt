package ch.nokillswit.rooms

import ch.nokillswit.authz.ConflictException

/**
 * The typed 409 problems of the room surface (`urn:adder:problem:`, `ProblemDetail.type`): a client tells them apart from the plain
 * conflicts (a name clash, a full key budget) by the URI, never by the detail text. The workflow problems (`status-in-use`,
 * `field-type-immutable`) and `last-owner` arrive with the routes that raise them.
 */
const val ROOM_ARCHIVED_PROBLEM_TYPE = "urn:adder:problem:room-archived"

/** An archived room is read-only for name, description and workflow (and, from 0.3, content); unarchive it first. */
fun roomArchived(): ConflictException = ConflictException("The room is archived", problemType = ROOM_ARCHIVED_PROBLEM_TYPE)
