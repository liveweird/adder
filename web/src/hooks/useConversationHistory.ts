import { keepPreviousData, useInfiniteQuery } from "@tanstack/react-query";
import type { MessageContext, MessageResponse } from "../api/messages";
import { listThreadEvents, type ThreadEvent } from "../api/threads";
import { roomKeys } from "../utils/roomKeys";

const PAGE_SIZE = 50;

export function useConversationHistory(
  roomId: number, number: number, messages: MessageResponse[], hasOlderMessages: boolean, context: MessageContext | undefined,
) {
  const hasOlder = context?.older ?? hasOlderMessages;
  const hasNewer = context?.newer === true;
  const from = hasOlder ? messages[0]?.createdAt : undefined;
  const to = hasNewer ? messages.at(-1)?.createdAt : undefined;
  const query = useInfiniteQuery({
    queryKey: [...roomKeys.threadEvents(roomId, number), "conversation", from, to],
    queryFn: ({ pageParam, signal }) => listThreadEvents(roomId, number, { page: pageParam, pageSize: PAGE_SIZE, from, to, signal }),
    initialPageParam: 1,
    getNextPageParam: (last, _pages, page) => page * PAGE_SIZE < last.total ? page + 1 : undefined,
    enabled: messages.length > 0,
    placeholderData: keepPreviousData,
  });
  const events = query.data?.pages.flatMap((page) => page.items).filter(
    (event) => (from === undefined || event.createdAt >= from) && (to === undefined || event.createdAt <= to),
  ) ?? [];
  return { query, events: messages.length ? events : [] };
}

/** At the same millisecond, show the thread start first, messages next and changes after them. */
export function interleaveConversation(messages: MessageResponse[], events: ThreadEvent[]) {
  const entries = [
    ...messages.map((message) => ({ message, rank: 1, id: message.id, at: message.createdAt })),
    ...events.map((event) => ({ event, rank: event.type === "CREATED" ? 0 : 2, id: event.id, at: event.createdAt })),
  ];
  return entries.sort((a, b) => a.at - b.at || a.rank - b.rank || a.id - b.id);
}
