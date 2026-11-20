import { useQuery } from "@tanstack/react-query";
import { getToken } from "../api/session";
import { listNotifications, type NotificationItem } from "../api/notifications";
import { notificationKeys } from "../utils/notificationKeys";

/** One empty list for every render without rows, so a memo keyed on `items` does not recompute while nothing has loaded. */
const NO_ITEMS: NotificationItem[] = [];

/** The unseen notices the bell reads in one go (the server's page maximum). */
const UNSEEN_PAGE_SIZE = 100;

/**
 * The caller's unseen notices under `["notifications", "unseen"]`: ONE query for the header badge (its `total`, capped by the server at 1000)
 * and, later, the counts the rail and the thread list derive from its rows. No polling: `notification.created`, `resync`, `ready` and the
 * loss of a room re-read it (`utils/liveKeys.ts`). It waits for a signed-in session, so a sign-out never fetches it without a token.
 */
export function useUnseenNotifications() {
  const query = useQuery({
    queryKey: notificationKeys.unseen,
    queryFn: () => listNotifications({ page: 1, pageSize: UNSEEN_PAGE_SIZE, wasSeen: false }),
    enabled: getToken() !== null,
  });
  return { ...query, total: query.data?.total ?? 0, items: query.data?.items ?? NO_ITEMS };
}
