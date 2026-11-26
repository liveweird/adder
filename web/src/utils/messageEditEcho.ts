import type { QueryClient } from "@tanstack/react-query";
import { API_BASE } from "../api/http";
import { activeQueriesAreFresh, refreshQueriesAfterMutation } from "./queryRefresh";
import { beginMessageEditEcho, canCorrelateReply, type MutationEcho } from "./liveReplyEcho";
import { homeKey, roomKeys } from "./roomKeys";

export function beginEditEcho(
  queryClient: QueryClient,
  roomId: number,
  threadNumber: number,
  messageId: number,
  apiBase = API_BASE,
  pageUrl = window.location.href,
): MutationEcho | undefined {
  return canCorrelateReply(apiBase, pageUrl)
    ? beginMessageEditEcho(queryClient, roomId, threadNumber, messageId)
    : undefined;
}

/** Re-read every source-room surface covered by message.edited before consuming the writer's matching event. */
export async function refreshMessageEditWrite(queryClient: QueryClient, roomId: number, echo?: MutationEcho): Promise<void> {
  const keys = [roomKeys.threads(roomId), roomKeys.pins(roomId), roomKeys.rail, homeKey] as const;
  try {
    await refreshQueriesAfterMutation(queryClient, ...keys);
    if (echo && activeQueriesAreFresh(queryClient, ...keys)) echo.confirm();
  } finally {
    echo?.cancel();
  }
}
