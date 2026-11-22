import { useEffect, useRef, useState } from "react";
import { useQueryClient, type QueryClient } from "@tanstack/react-query";
import { setThreadReadMarker } from "../api/threads";
import { requestRefresh } from "../utils/liveKeys";
import { startLooking } from "../utils/lookingAt";
import { notificationKeys } from "../utils/notificationKeys";
import { markThreadReadInCaches } from "../utils/readCache";
import { announceSeen } from "../utils/seenSync";
import { usePageVisible } from "./usePageVisible";

/** A thread's read marker is sent at most this often (the server's read-state bucket is 240 a minute for every kind of read state). */
export const READ_MARKER_INTERVAL_MS = 2000;

/** A marker that is wanted but held back by the interval: the thread it belongs to and the newest message the reader has seen. */
type Wanted = { roomId: number; threadNumber: number; messageId: number };

/** The bell re-reads when an acknowledgement it was promised did not happen: the notice it swallowed is unseen on the server. */
const refreshBell = (queryClient: QueryClient) => requestRefresh(queryClient, notificationKeys.all);

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
 * While the reader is looking (the three conditions above) the thread is registered in `utils/lookingAt.ts`: a `notification.created` hint
 * about it does not raise the bell, it asks this hook to acknowledge the notice, which sends the newest id once more (a thread-level notice
 * has no message to be newer than the last marker; a notice for an older, edited message neither).
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
  const current = useRef({
    key: threadKey,
    acknowledged: 0,
    sentAt: null as number | null,
    inFlight: false,
    wanted: null as Wanted | null,
    // A thread-level notice about this thread arrived while the reader was looking at it: send the newest id once more even though it was sent.
    force: false,
    // A reply that is arriving was left out of the bell: the marker that reaches this id acknowledges it (until then the bell owes a re-read).
    target: null as number | null,
  });
  // A held-back marker re-runs the effect when the wait is over, and a success re-runs it for a message that arrived meanwhile.
  const [tick, setTick] = useState(0);
  // The newest id loaded, for the registration below (it decides which notices the pane acknowledges on its own).
  const newestRef = useRef(newestId);
  useEffect(() => {
    newestRef.current = newestId;
  });

  useEffect(() => {
    const state = current.current;
    if (state.key !== threadKey) {
      // Another thread: what the last one held back goes out now, and nothing of its progress carries over.
      if (state.wanted !== null) void sendMarker(queryClient, state.wanted);
      if (state.force || state.target !== null) refreshBell(queryClient);
      current.current = { key: threadKey, acknowledged: 0, sentAt: null, inFlight: false, wanted: null, force: false, target: null };
    }
    const now = current.current;
    if (!visible || !atEnd) {
      now.wanted = null;
      // The reader stopped looking before the acknowledgement went out: the notice the bell skipped is still unseen, so the bell re-reads.
      if (now.force || now.target !== null) refreshBell(queryClient);
      now.force = false;
      now.target = null;
      return;
    }
    // While the conversation is being re-read (`enabled` false) an acknowledgement waits: the message that caused it is what is loading.
    if (!enabled || newestId === undefined || (newestId <= now.acknowledged && !now.force)) {
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
    const forced = now.force;
    now.wanted = null;
    now.force = false;
    now.inFlight = true;
    now.sentAt = Date.now();
    void sendMarker(queryClient, sent).then((landed) => {
      // A marker that was an acknowledgement and did not land leaves the swallowed notice unseen: the bell re-reads (whichever thread is open).
      const owed = now.target !== null && sent.messageId >= now.target;
      if (!landed && (forced || owed)) refreshBell(queryClient);
      // A notice the pane acknowledged never reached this tab's bell cache (the live channel left it out), so `markThreadReadInCaches` could not
      // tell: the person's other tabs hear of it here (1.2 D9), or they would keep counting it.
      if (landed && (forced || owed)) announceSeen();
      if (owed) now.target = null;
      // The reader may have moved on to another thread meanwhile: its state is not this one's.
      if (current.current !== now) return;
      now.inFlight = false;
      if (!landed) return;
      now.acknowledged = Math.max(now.acknowledged, sent.messageId);
      setTick((n) => n + 1);
    });
  }, [enabled, visible, atEnd, newestId, roomId, threadNumber, threadKey, tick, queryClient]);

  // While the reader is looking at the thread (enabled, visible, at the end) the live channel leaves a notice about it out of the bell and
  // asks this hook to acknowledge it instead (`utils/lookingAt.ts`): the marker is what marks the notice seen, so the badge never goes up.
  useEffect(() => {
    if (!enabled || !visible || !atEnd) return;
    return startLooking({
      roomId,
      threadNumber,
      covers: (messageId) => messageId === undefined || (newestRef.current !== undefined && messageId > newestRef.current),
      acknowledge: (messageId) => {
        if (current.current.key !== threadKey) return;
        if (messageId === undefined) {
          current.current.force = true;
          setTick((n) => n + 1);
        } else {
          // The reply is on its way (its message hint re-reads the conversation): the ordinary marker for the newest id acknowledges it.
          current.current.target = Math.max(current.current.target ?? 0, messageId);
        }
      },
    });
  }, [enabled, visible, atEnd, roomId, threadNumber, threadKey]);

  // Leaving (unmount only, so it reads the ref and never re-runs): the marker held back by the interval goes out now, at most one.
  useEffect(
    () => () => {
      const { wanted, force, target } = current.current;
      if (wanted !== null) void sendMarker(queryClient, wanted);
      if (force || target !== null) refreshBell(queryClient);
    },
    [queryClient],
  );
}
