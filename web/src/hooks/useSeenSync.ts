import { useEffect, useRef, useSyncExternalStore } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { getSessionSnapshot, getUserId } from "../api/session";
import { requestRefresh } from "../utils/liveKeys";
import { notificationsKey } from "../utils/roomKeys";
import { openSeenSync, type SeenChannelFactory } from "../utils/seenSync";
import { subscribeLogicalSession } from "../utils/sessionQueryCache";

/** The session identity AND the user it belongs to: a switch of either (here, or from another tab through `storage`) reopens the channel for the right user. */
const currentSession = () => `${getSessionSnapshot().identity}|${getUserId()}`;

/**
 * Keeps this tab in step with the person's other tabs (1.2 D9): another tab's "notices seen" re-reads the bell (through the live invalidator, so a
 * quiet hidden tab spaces it a minute apart). Called in the shell; the channel belongs to the signed-in user and closes at sign-out or a switch of
 * user. [paused] is a tab hidden for 8 hours (`idle`, no socket): a message is dropped there, since showing the tab reconnects and the `ready` re-read
 * is the backfill. [factory] is for tests.
 */
export function useSeenSync(paused = false, factory?: SeenChannelFactory): void {
  const queryClient = useQueryClient();
  const session = useSyncExternalStore(subscribeLogicalSession, currentSession, () => "");
  const pausedRef = useRef(paused);
  useEffect(() => {
    pausedRef.current = paused;
  });
  useEffect(() => {
    const userId = getUserId();
    if (getSessionSnapshot().identity === null || userId === null) return;
    return openSeenSync(
      userId,
      () => {
        if (!pausedRef.current) requestRefresh(queryClient, notificationsKey);
      },
      factory,
    );
  }, [queryClient, session, factory]);
}
