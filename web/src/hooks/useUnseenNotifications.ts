import { useQuery } from "@tanstack/react-query";
import { getToken } from "../api/session";
import { ADDRESSED_TYPES, listNotifications, type NotificationItem } from "../api/notifications";
import { notificationKeys } from "../utils/notificationKeys";
import { useNotificationRetryAfter } from "./useNotificationRetryAfter";

/** One empty list for every render without rows, so a memo keyed on `items` does not recompute while nothing has loaded. */
const NO_ITEMS: NotificationItem[] = [];

/** The unseen notices the bell reads in one go (the server's page maximum). */
const UNSEEN_PAGE_SIZE = 100;

/** Both unseen queries read this: one page of the caller's unseen notices, optionally of the addressed kinds only. */
function useUnseenQuery(queryKey: readonly unknown[], addressedOnly: boolean) {
  const query = useQuery({
    queryKey,
    queryFn: () => listNotifications({ page: 1, pageSize: UNSEEN_PAGE_SIZE, seen: false, type: addressedOnly ? ADDRESSED_TYPES : undefined }),
    enabled: getToken() !== null,
  });
  useNotificationRetryAfter(query.error);
  return { ...query, total: query.data?.total ?? 0, items: query.data?.items ?? NO_ITEMS };
}

/**
 * ALL the caller's unseen notices under `["notifications", "unseen"]`: the header badge (its `total`, capped by the server at 1000). No
 * polling: `notification.created`, `resync`, `ready` and the loss of a room re-read it (`utils/liveKeys.ts`). It waits for a signed-in
 * session, so a sign-out never fetches it without a token. The rail's count and the thread list's `@` mark read [useAddressedNotifications]
 * instead, so an activity notice (a reply, a new thread) never raises them (1.1 A21).
 */
export function useUnseenNotifications() {
  return useUnseenQuery(notificationKeys.unseen, false);
}

/**
 * The caller's unseen notices of the ADDRESSED kinds (`ADDRESSED_TYPES`: a mention of any kind, a quote of their message, a hand-over) under
 * `["notifications", "addressed"]`: their count per room is the rail's `@` number and their threads carry the thread list's `@` mark
 * (0.6 D1 stays). Re-read by the same hints as the badge, because it sits under the same `["notifications"]` prefix.
 */
export function useAddressedNotifications() {
  return useUnseenQuery(notificationKeys.addressed, true);
}
