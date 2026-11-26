import type { KeyboardEvent } from "react";
import type { TFunction } from "i18next";
import type { QueryClient } from "@tanstack/react-query";
import { PROBLEM_TYPES, isProblem } from "./problems";
import { activeQueriesAreFresh, refreshQueriesAfterMutation } from "./queryRefresh";
import { saveErrorMessage } from "./saveError";
import { roomKeys } from "./roomKeys";
import { WATCH_SEARCH_KEYS } from "./watch";

/**
 * The failure vocabulary of every message write (post, edit, delete, react). The 409s are told apart by their problem type (a closed thread and
 * an archived room need different words); the caller re-reads the room and the thread on a 403, 404 or 409 (`isAccessRefusal`), so the notices
 * and the controls follow the truth.
 */
export function messageErrorMessage(err: unknown, t: TFunction): string {
  // The post's typed 400 (1.3): the message being quoted is gone, so the fix is to remove the quote, not to check the text.
  if (isProblem(err, PROBLEM_TYPES.quoteUnavailable)) return t("threads.quote.unavailable");
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

/** A reply changes the conversation and activity. It can also start watching, which affects Home and watched searches. */
export async function refreshAfterReply(
  queryClient: QueryClient,
  roomId: number,
  onCovered?: () => void,
): Promise<void> {
  const keys = [roomKeys.threads(roomId), roomKeys.rail, ...WATCH_SEARCH_KEYS];
  await refreshQueriesAfterMutation(queryClient, ...keys);
  if (!onCovered) return;
  // TanStack resolves invalidation after a failed or paused fetch as well. Keep the live fallback
  // unless every active query in this refresh has reached fresh data.
  if (activeQueriesAreFresh(queryClient, ...keys)) onCovered();
}
