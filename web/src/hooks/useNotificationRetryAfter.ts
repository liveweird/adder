import { useEffect } from "react";
import { useQueryClient, type QueryClient } from "@tanstack/react-query";
import { ApiError } from "../api/http";
import { notificationKeys } from "../utils/notificationKeys";
import { requestRefresh } from "../utils/liveKeys";

/** At least this long before the one re-read, whatever a missing or tiny `Retry-After` says. */
const MIN_RETRY_MS = 1_000;
/** Each tab adds a random 0 to this much on top, so tabs that were refused in the same instant (one wide event) do not retry in lock step. */
const JITTER_MS = 2_000;
/** Every refusal in a row doubles the wait, up to this many doublings (x8): a stretch of refusals backs off instead of hammering. */
const MAX_DOUBLINGS = 3;

/** A random 0 to [JITTER_MS] from the platform's generator (not a security use: it only spreads retries, and the lint rule wants this one). */
const jitter = (): number => (crypto.getRandomValues(new Uint32Array(1))[0] / 2 ** 32) * JITTER_MS;

/** The one pending re-read per query client: the badge, the addressed count and the drawer all fail together on a 429 and share it. */
const scheduled = new WeakMap<QueryClient, ReturnType<typeof setTimeout>>();
/** How many refusals in a row this client has had (cleared by a read that was not refused). */
const refusals = new WeakMap<QueryClient, number>();

/**
 * The notification list is bucketed per user (`notification-read`, 240 a minute) and capped in flight in the process: a `429` is a short
 * pause, not a failure. When a query of the bell, the rail or the drawer ends in one, this schedules ONE re-read of every notification
 * query after the server's `Retry-After`, a random jitter and a capped exponential backoff for refusals in a row (shared by the three
 * callers; a re-read that is refused again schedules its own).
 */
export function useNotificationRetryAfter(error: unknown): void {
  const queryClient = useQueryClient();
  useEffect(() => {
    if (!(error instanceof ApiError) || error.status !== 429) {
      if (error === null || error === undefined) refusals.delete(queryClient);
      return;
    }
    if (scheduled.has(queryClient)) return;
    const earlier = refusals.get(queryClient) ?? 0;
    refusals.set(queryClient, earlier + 1);
    const base = Math.max(error.retryAfterMs ?? MIN_RETRY_MS, MIN_RETRY_MS) * 2 ** Math.min(earlier, MAX_DOUBLINGS);
    scheduled.set(
      queryClient,
      setTimeout(() => {
        scheduled.delete(queryClient);
        requestRefresh(queryClient, notificationKeys.all);
      }, base + jitter()),
    );
  }, [error, queryClient]);
}
