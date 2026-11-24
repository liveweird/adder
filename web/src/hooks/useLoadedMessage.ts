import { useCallback, useMemo, useSyncExternalStore } from "react";
import { partialMatchKey, useQueryClient, type InfiniteData } from "@tanstack/react-query";
import type { MessageContext, MessagePage, MessageResponse } from "../api/messages";
import { roomKeys } from "../utils/roomKeys";

/** A message already fetched by the conversation, including its active history window. Never makes a request. */
export function useLoadedMessage(roomId: number, threadNumber: number, messageId: number | null): MessageResponse | undefined {
  const queryClient = useQueryClient();
  const messagesKey = useMemo(() => roomKeys.messages(roomId, threadNumber), [roomId, threadNumber]);
  const subscribe = useCallback(
    (notify: () => void) =>
      queryClient.getQueryCache().subscribe((event) => {
        if (partialMatchKey(event.query.queryKey, messagesKey)) notify();
      }),
    [queryClient, messagesKey],
  );
  const read = useCallback((): MessageResponse | undefined => {
    if (messageId === null) return undefined;
    const latest = queryClient.getQueryState<InfiniteData<MessagePage>>(messagesKey);
    let fromLatest: MessageResponse | undefined;
    for (const page of latest?.data?.pages ?? []) {
      fromLatest = page.items.find((message) => message.id === messageId);
      if (fromLatest !== undefined) break;
    }
    // A tombstone remains authoritative even if another mounted copy is still waiting on the same live hint.
    if (fromLatest?.deleted) return fromLatest;
    const history = queryClient.getQueriesData<MessageContext>({ queryKey: [...messagesKey, "context"], type: "active" });
    for (const [key, data] of history) {
      const state = queryClient.getQueryState(key);
      if (state?.status !== "success") continue;
      const found = data?.items.find((message) => message.id === messageId);
      if (found?.deleted || (found && (!fromLatest || state.dataUpdatedAt >= (latest?.dataUpdatedAt ?? 0)))) return found;
    }
    return fromLatest;
  }, [queryClient, messagesKey, messageId]);
  return useSyncExternalStore(subscribe, read);
}
