import { useState } from "react";

/**
 * The first unread message of a thread, taken ONCE: from the first thread read that has settled, so a cached copy from an earlier visit never
 * decides it and neither does a later re-read, which (after the reader has read on) would name a message that arrived under them. The "New"
 * separator therefore does not move while the thread is read. `null` while nothing is unread (or until the read settles).
 */
export function useFirstUnreadOnOpen(firstUnreadMessageId: number | null, settled: boolean): number | null {
  const [taken, setTaken] = useState<{ id: number | null } | null>(null);
  if (taken === null && settled) setTaken({ id: firstUnreadMessageId });
  return taken?.id ?? null;
}

/**
 * How many messages from other people arrived while the reader was not at the end of the conversation. The newest message seen at the end (or
 * at the first load) is remembered; everything newer than it, by someone else, counts until the reader is at the end again. The caller's own
 * messages never count: a post takes the reader to the end by itself. `messages` is oldest first.
 */
export function useArrivals(messages: readonly { id: number; authorId: number }[], atEnd: boolean, userId: number | null): number {
  const newestId = messages.at(-1)?.id ?? null;
  const [seen, setSeen] = useState<number | null>(null);
  // At the end the newest message is simply seen; before the first load of messages there is nothing to remember.
  if (newestId !== null && (seen === null || (atEnd && seen !== newestId))) setSeen(newestId);
  if (atEnd || seen === null) return 0;
  let count = 0;
  for (let i = messages.length - 1; i >= 0 && messages[i]!.id > seen; i--) {
    if (messages[i]!.authorId !== userId) count++;
  }
  return count;
}
