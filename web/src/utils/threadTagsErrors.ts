import type { TFunction } from "i18next";
import { PROBLEM_TYPES, isProblem } from "./problems";
import { saveErrorMessage, withServerDetail } from "./saveError";

/**
 * The tag save's fixed vocabulary (never the server's text; a 400's own sentence is appended in English only). The 409 has three causes: the
 * thread was closed while the editor was open (and the caller is no steward the answer is a 403 instead), the room was archived, or the revision
 * went stale (that one has its own alert, `RevisionConflictAlert`, and never reaches here).
 */
export function threadTagsErrorMessage(err: unknown, t: TFunction): string {
  const conflict = isProblem(err, PROBLEM_TYPES.roomArchived) ? "threads.tags.error.archived" : "threads.tags.error.conflict";
  const message = saveErrorMessage(err, t, {
    forbidden: "threads.tags.error.forbidden",
    notFound: "threads.tags.error.gone",
    conflict,
    invalid: "threads.tags.error.invalid",
    failedStatus: "common.error.saveFailedStatus",
    failed: "common.error.saveFailedNetwork",
  });
  return withServerDetail(message, err);
}
