import type { TFunction } from "i18next";
import { PROBLEM_TYPES, isProblem } from "./problems";
import { saveErrorMessage } from "./saveError";

/**
 * The room workflow save vocabulary. A `revision-conflict` is the caller's (its own alert with Reload); the other typed 409s say
 * what to change, and the plain 409 left over is the room's key budget (200 status and 200 field keys, retired ones included).
 */
export function roomWorkflowSaveErrorMessage(err: unknown, t: TFunction): string {
  if (isProblem(err, PROBLEM_TYPES.roomArchived)) return t("rooms.workflow.saveArchived");
  if (isProblem(err, PROBLEM_TYPES.statusInUse)) return t("rooms.workflow.saveStatusInUse");
  if (isProblem(err, PROBLEM_TYPES.fieldTypeImmutable)) return t("rooms.workflow.saveFieldType", { keys: err.keys.join(", ") });
  return saveErrorMessage(err, t, {
    retryAfter: true,
    forbidden: "rooms.workflow.saveForbidden",
    notFound: "rooms.workflow.saveGone",
    conflict: "rooms.workflow.saveKeyBudget",
    invalid: "rooms.workflow.saveInvalid",
    failedStatus: "common.error.saveFailedStatus",
    failed: "common.error.saveFailedNetwork",
  });
}
