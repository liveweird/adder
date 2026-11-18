import type { TFunction } from "i18next";
import { ApiError } from "../api/http";
import { PROBLEM_TYPES, isProblem } from "./problems";
import { isAccessRefusal } from "./roomAccess";
import { saveErrorMessage } from "./saveError";

/**
 * The status move's failure vocabulary. A 403 says the caller is not a steward (the thread's owner moved, or the caller's standing did), a 400 that
 * the status was retired or removed since the workflow was read, a 409 that the room was archived; the caller re-reads the room on a 403, 404 or 409
 * (`isAccessRefusal`) and the workflow on a 400, so the options follow the truth.
 */
export function threadStatusErrorMessage(err: unknown, t: TFunction): string {
  return saveErrorMessage(err, t, {
    forbidden: "threads.status.error.forbidden",
    notFound: "threads.status.error.gone",
    conflict: "threads.status.error.archived",
    invalid: "threads.status.error.unavailable",
    failedStatus: "common.error.actionFailedStatus",
    failed: "common.error.actionFailed",
  });
}

/**
 * The owner hand-over's failure vocabulary: a 400 is a person who is no longer an active member of the room, the two 409s (a closed thread, an
 * archived room) are told apart by their problem type, a 403 that the caller is no longer a steward.
 */
export function threadOwnerErrorMessage(err: unknown, t: TFunction): string {
  const conflict = isProblem(err, PROBLEM_TYPES.threadClosed)
    ? "threads.owner.error.closed"
    : isProblem(err, PROBLEM_TYPES.roomArchived)
      ? "threads.owner.error.archived"
      : "threads.owner.error.conflict";
  return saveErrorMessage(err, t, {
    forbidden: "threads.owner.error.forbidden",
    notFound: "threads.owner.error.gone",
    conflict,
    invalid: "threads.owner.error.invalid",
    failedStatus: "common.error.actionFailedStatus",
    failed: "common.error.actionFailed",
  });
}

/**
 * Whether a failed status or owner write says the page's picture is stale and the room should be re-read: the caller's standing moved (403, 404,
 * 409: `isAccessRefusal`), or the status or person it chose is gone (400: retired since the workflow was read, left the room since the picker listed them).
 */
export function isStaleWrite(err: unknown): boolean {
  return isAccessRefusal(err) || (err instanceof ApiError && err.status === 400);
}
