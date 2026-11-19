import type { KeyboardEvent } from "react";
import type { TFunction } from "i18next";
import type { QueryClient } from "@tanstack/react-query";
import { PROBLEM_TYPES, isProblem } from "./problems";
import { refreshQueriesAfterMutation } from "./queryRefresh";
import { saveErrorMessage } from "./saveError";
import { roomKeys } from "./roomKeys";

/**
 * The failure vocabulary of every message write (post, edit, delete). The two 409s are told apart by their problem type (a closed thread and
 * an archived room need different words); the caller re-reads the room and the thread on a 403, 404 or 409 (`isAccessRefusal`), so the notices
 * and the controls follow the truth.
 */
export function messageErrorMessage(err: unknown, t: TFunction): string {
  const conflict = isProblem(err, PROBLEM_TYPES.threadClosed)
    ? "threads.message.error.closed"
    : isProblem(err, PROBLEM_TYPES.roomArchived)
      ? "threads.message.error.archived"
      : "threads.message.error.conflict";
  return saveErrorMessage(err, t, {
    forbidden: "threads.message.error.forbidden",
    notFound: "threads.message.error.gone",
    conflict,
    tooManyRequests: "threads.message.error.tooManyRequests",
    invalid: "threads.message.error.invalid",
    failedStatus: "common.error.actionFailedStatus",
    failed: "common.error.actionFailed",
  });
}

/** Ctrl+Enter or Cmd+Enter sends (a plain Enter is a newline); a key pressed while an IME composition is open never does. */
export function isSendShortcut(event: KeyboardEvent): boolean {
  return event.key === "Enter" && (event.ctrlKey || event.metaKey) && !event.nativeEvent.isComposing;
}

/**
 * After the caller's own message write (post, edit, delete): the room's thread queries (the list's order, the thread's count and times, the
 * conversation) and the rail, per plan 4.5. A message changes no workflow count and no room header, so those stay as they are; an access
 * refusal re-reads the whole `["rooms"]` prefix instead (`isAccessRefusal`).
 */
export function refreshAfterMessageWrite(queryClient: QueryClient, roomId: number): Promise<void> {
  return refreshQueriesAfterMutation(queryClient, roomKeys.threads(roomId), roomKeys.rail);
}
