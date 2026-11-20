import { useEffect, useRef, useState } from "react";
import { useQueryClient, type QueryClient } from "@tanstack/react-query";
import { setThreadReadMarker } from "../api/threads";
import { markThreadReadInCaches } from "../utils/readCache";
import { usePageVisible } from "./usePageVisible";

/** A thread's read marker is sent at most this often (the server's read-state bucket is 240 a minute for every kind of read state). */
export const READ_MARKER_INTERVAL_MS = 2000;

/** A marker that is wanted but held back by the interval: the thread it belongs to and the newest message the reader has seen. */
type Wanted = { roomId: number; threadNumber: number; messageId: number };

/** Sends one marker and, on success, reads the thread as read in the caches. A failure is silent. Resolves to whether it landed. */
function sendMarker(queryClient: QueryClient, { roomId, threadNumber, messageId }: Wanted): Promise<boolean> {
  return setThreadReadMarker(roomId, threadNumber, messageId).then(
    () => {
      markThreadReadInCaches(queryClient, roomId, threadNumber, messageId);
      return true;
    },
    () => false,
  );
}

/**
 * Moves the caller's read marker of a thread to its newest message once that message is actually seen: the reader is at the end of the
 * conversation (`atEnd`), the tab is visible and the id is newer than the last one acknowledged. The first marker goes at once, later ones are
 * held back to one per [READ_MARKER_INTERVAL_MS]; an id is never sent twice and the marker never goes backwards. `enabled` is false for a
 * reader who is not a member (an ADMIN outside the room has no read state), while the conversation has not loaded (`newestId` undefined) and
 * while it is being re-read (the newest id of a stale copy was never seen).
 *
 * A marker still held back when the reader leaves (the hook unmounts, or it is handed another thread) is sent then, once: the last message
 * they saw must not be lost to the interval. A success reads the thread as read in the cached list row, the thread's detail and the rail (no
 * refetch, unless a newer message is already cached) and re-reads the bell only when its unseen rows held a notice of this thread
 * (`markThreadReadInCaches`). A failure is silent: nothing is shown and the id stays unacknowledged, so the next arrival (or the reader
 * reaching the end again) tries again; a failed write never retries by itself.
 */
export function useThreadReadMarker({
  roomId,
  threadNumber,
  newestId,
  atEnd,
  enabled,
}: {
  roomId: number;
  threadNumber: number;
  newestId: number | undefined;
  atEnd: boolean;
  enabled: boolean;
}): void {
  const queryClient = useQueryClient();
  const visible = usePageVisible();
  const threadKey = `${roomId}/${threadNumber}`;
  const current = useRef({ key: threadKey, acknowledged: 0, sentAt: null as number | null, inFlight: false, wanted: null as Wanted | null });
  // A held-back marker re-runs the effect when the wait is over, and a success re-runs it for a message that arrived meanwhile.
  const [tick, setTick] = useState(0);

  useEffect(() => {
    const state = current.current;
    if (state.key !== threadKey) {
      // Another thread: what the last one held back goes out now, and nothing of its progress carries over.
      if (state.wanted !== null) void sendMarker(queryClient, state.wanted);
      current.current = { key: threadKey, acknowledged: 0, sentAt: null, inFlight: false, wanted: null };
    }
    const now = current.current;
    if (!enabled || !visible || !atEnd || newestId === undefined || newestId <= now.acknowledged) {
      now.wanted = null;
      return;
    }
    now.wanted = { roomId, threadNumber, messageId: newestId };
    const wait = now.sentAt === null ? 0 : now.sentAt + READ_MARKER_INTERVAL_MS - Date.now();
    if (now.inFlight) return;
    if (wait > 0) {
      const timer = setTimeout(() => setTick((n) => n + 1), wait);
      return () => clearTimeout(timer);
    }
    const sent = now.wanted;
    now.wanted = null;
    now.inFlight = true;
    now.sentAt = Date.now();
    void sendMarker(queryClient, sent).then((landed) => {
      // The reader may have moved on to another thread meanwhile: its state is not this one's.
      if (current.current !== now) return;
      now.inFlight = false;
      if (!landed) return;
      now.acknowledged = Math.max(now.acknowledged, sent.messageId);
      setTick((n) => n + 1);
    });
  }, [enabled, visible, atEnd, newestId, roomId, threadNumber, threadKey, tick, queryClient]);

  // Leaving (unmount only, so it reads the ref and never re-runs): the marker held back by the interval goes out now, at most one.
  useEffect(
    () => () => {
      const { wanted } = current.current;
      if (wanted !== null) void sendMarker(queryClient, wanted);
    },
    [queryClient],
  );
}
