package ch.nokillswit.threads

import ch.nokillswit.authz.ForbiddenException
import ch.nokillswit.workflows.StatusCategory

/**
 * The pure status rules of a thread move (D1, option 1): no database, no request. Any reader moves a thread between OPEN statuses;
 * everything that touches a CLOSED status (close, reopen, CLOSED to CLOSED) needs a steward: the thread owner while a member, the room
 * OWNER or an ADMIN, i.e. whoever can undo a close. The caller computes `steward` with `requireThreadSteward`'s rule and passes the
 * categories of the thread's current status and of the resolved (active) target.
 */
enum class StatusTransition {
    /** OPEN to OPEN, or CLOSED to CLOSED: a plain `STATUS_CHANGED` event. */
    MOVED,

    /** OPEN to CLOSED: the thread closes (a `CLOSED` event, `closed_at` set). */
    CLOSED,

    /** CLOSED to OPEN: the thread reopens (a `REOPENED` event, `closed_at` cleared). */
    REOPENED,
}

/**
 * The transition for a move from [fromKey] ([from]) to [toKey] ([to]), or `null` when it is no change (the same status again: a 204
 * with no event). Authorization comes first: a move that enters or leaves CLOSED by a non-[steward] is a [ForbiddenException] even
 * when the status is unchanged, so the status of a closed thread is never "settable" by a plain member.
 */
fun statusTransition(fromKey: String, from: StatusCategory, toKey: String, to: StatusCategory, steward: Boolean): StatusTransition? {
    if (!steward && (from == StatusCategory.CLOSED || to == StatusCategory.CLOSED)) {
        throw ForbiddenException("Only the thread owner, the room owner or an administrator may close, reopen or move a closed thread")
    }
    return when {
        fromKey == toKey -> null
        from == to -> StatusTransition.MOVED
        to == StatusCategory.CLOSED -> StatusTransition.CLOSED
        else -> StatusTransition.REOPENED
    }
}
