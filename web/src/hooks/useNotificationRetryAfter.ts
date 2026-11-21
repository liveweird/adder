import { useEffect } from "react";
import { useQueryClient, type QueryClient } from "@tanstack/react-query";
import { ApiError } from "../api/http";
import { notificationKeys } from "../utils/notificationKeys";
import { requestRefresh } from "../utils/liveKeys";

/** At least this long before the one re-read, whatever a missing or tiny `Retry-After` says. */
const MIN_RETRY_MS = 1_000;

/** The one pending re-read per query client: the badge, the addressed count and the drawer all fail together on a 429 and share it. */
const scheduled = new WeakMap<QueryClient, ReturnType<typeof setTimeout>>();

/**
 * The notification list is bucketed per user (`notification-read`, 240 a minute): a `429` is a short pause, not a failure. When a query of
 * the bell, the rail or the drawer ends in one, this schedules ONE re-read of every notification query after the server's `Retry-After`
 * (shared by the three callers; a re-read that is refused again schedules its own, after its own `Retry-After`).
 */
export function useNotificationRetryAfter(error: unknown): void {
  const queryClient = useQueryClient();
  useEffect(() => {
    if (!(error instanceof ApiError) || error.status !== 429 || scheduled.has(queryClient)) return;
    const wait = Math.max(error.retryAfterMs ?? MIN_RETRY_MS, MIN_RETRY_MS);
    scheduled.set(
      queryClient,
      setTimeout(() => {
        scheduled.delete(queryClient);
        requestRefresh(queryClient, notificationKeys.all);
      }, wait),
    );
  }, [error, queryClient]);
}
