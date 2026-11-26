import type { QueryClient } from "@tanstack/react-query";
import { API_BASE } from "../api/http";
import { activeQueriesAreFresh, refreshQueriesAfterMutation } from "./queryRefresh";
import { beginThreadUpdateEcho, canCorrelateReply, type MutationEcho } from "./liveReplyEcho";
import { homeKey, roomKeys } from "./roomKeys";

export function beginThreadRevisionEcho(
  queryClient: QueryClient,
  roomId: number,
  threadNumber: number,
  apiBase = API_BASE,
  pageUrl = window.location.href,
): MutationEcho | undefined {
  return canCorrelateReply(apiBase, pageUrl)
    ? beginThreadUpdateEcho(queryClient, roomId, threadNumber)
    : undefined;
}

/** Re-read every cache family covered by thread.updated before consuming the writer's matching live event. */
export async function refreshThreadRevisionWrite(queryClient: QueryClient, roomId: number, echo?: MutationEcho): Promise<void> {
  const keys = [roomKeys.threads(roomId), roomKeys.rail, homeKey] as const;
  try {
    await refreshQueriesAfterMutation(queryClient, ...keys);
    if (echo && activeQueriesAreFresh(queryClient, ...keys)) echo.confirm();
  } finally {
    echo?.cancel();
  }
}
