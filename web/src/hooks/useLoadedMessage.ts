import { useCallback, useSyncExternalStore } from "react";
import { hashKey, useQueryClient, type InfiniteData } from "@tanstack/react-query";
import type { MessagePage, MessageResponse } from "../api/messages";
import { roomKeys } from "../utils/roomKeys";

/**
 * A message as the thread's conversation has it loaded RIGHT NOW (the pane's own `MessageList` query), or `undefined` when it is not loaded. It
 * reads the cache and never fetches: the composer uses it to keep the message it quotes honest (a delete or an edit that the list has already
 * seen) without a request of its own. Not an observer: a second observer on the list's infinite query would share its options and its fetches.
 */
export function useLoadedMessage(roomId: number, threadNumber: number, messageId: number | null): MessageResponse | undefined {
  const queryClient = useQueryClient();
  const hash = hashKey(roomKeys.messages(roomId, threadNumber));
  const subscribe = useCallback(
    (notify: () => void) =>
      queryClient.getQueryCache().subscribe((event) => {
        if (event.query.queryHash === hash) notify();
      }),
    [queryClient, hash],
  );
  const read = useCallback((): MessageResponse | undefined => {
    if (messageId === null) return undefined;
    const data = queryClient.getQueryData<InfiniteData<MessagePage>>(roomKeys.messages(roomId, threadNumber));
    for (const page of data?.pages ?? []) {
      const found = page.items.find((message) => message.id === messageId);
      if (found !== undefined) return found;
    }
    return undefined;
  }, [queryClient, roomId, threadNumber, messageId]);
  return useSyncExternalStore(subscribe, read);
}
