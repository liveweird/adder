import type { KeyboardEvent } from "react";
import type { TFunction } from "i18next";
import type { QueryClient } from "@tanstack/react-query";
import { PROBLEM_TYPES, isProblem } from "./problems";
import { refreshQueriesAfterMutation } from "./queryRefresh";
import { saveErrorMessage } from "./saveError";
import { roomKeys } from "./roomKeys";

/**
 * The failure vocabulary of every message write (post, edit, delete, react). The 409s are told apart by their problem type (a closed thread and
 * an archived room need different words); the caller re-reads the room and the thread on a 403, 404 or 409 (`isAccessRefusal`), so the notices
 * and the controls follow the truth.
 */
export function messageErrorMessage(err: unknown, t: TFunction): string {
  return saveErrorMessage(err, t, {
    forbidden: "threads.message.error.forbidden",
    notFound: "threads.message.error.gone",
    conflict: conflictKey(err),
    tooManyRequests: "threads.message.error.tooManyRequests",
    invalid: "threads.message.error.invalid",
    failedStatus: "common.error.actionFailedStatus",
    failed: "common.error.actionFailed",
  });
}

/** The words of a 409, by its problem type: a closed thread, an archived room and a message that takes no more reactions each need their own. */
function conflictKey(err: unknown) {
  if (isProblem(err, PROBLEM_TYPES.threadClosed)) return "threads.message.error.closed";
  if (isProblem(err, PROBLEM_TYPES.roomArchived)) return "threads.message.error.archived";
  return isProblem(err, PROBLEM_TYPES.reactionLimit) ? "threads.reaction.limit" : "threads.message.error.conflict";
}

/** Ctrl+Enter or Cmd+Enter sends (a plain Enter is a newline); a key pressed while an IME composition is open never does. */
export function isSendShortcut(event: KeyboardEvent): boolean {
  return event.key === "Enter" && (event.ctrlKey || event.metaKey) && !event.nativeEvent.isComposing;
}

/**
 * After the caller's own message write (post, edit, delete): the room's thread queries (the list's order, the thread's count and times, the
 * conversation) and the rail, per plan 4.5. A message changes no workflow count and no room header, so those stay as they are; an access
 * refusal re-reads the whole `["rooms"]` prefix instead (`isAccessRefusal`). An edit or a delete of a message that exists passes `pins`: the
 * pinned panel shows the body, so a redacted one must not linger there (a post changes no pin, so it skips the read).
 */
export function refreshAfterMessageWrite(queryClient: QueryClient, roomId: number, { pins = false }: { pins?: boolean } = {}): Promise<void> {
  const keys = [roomKeys.threads(roomId), roomKeys.rail];
  return refreshQueriesAfterMutation(queryClient, ...(pins ? [...keys, roomKeys.pins(roomId)] : keys));
}
