// Notifications API — the caller's own notices (`GET /api/v1/notifications`) and the two "seen" writes (`POST /api/v1/notifications/{id}/seen`,
// `POST /api/v1/notifications/seen-all`, 204, idempotent). A notice is ids plus the names and the title the server reads when it answers
// (`chat-model.md` "Notifications"); there is no unseen, no delete. Thin wrappers: transport in ./http, types from ./schema.

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

export type NotificationPage = paths["/api/v1/notifications"]["get"]["responses"]["200"]["content"]["application/json"];
export type NotificationItem = NotificationPage["items"][number];

/** The server caps `total` here (`NotificationPage.total`): a count at the cap means "at least this many". */
export const NOTIFICATION_TOTAL_CAP = 1000;

export interface NotificationQuery {
  page: number;
  pageSize: number;
  /** `false` = the unseen ones only; omitted = both. */
  wasSeen?: boolean;
}

/** One page of the caller's notices, newest first (the server's default `-id`). */
export async function listNotifications(query: NotificationQuery): Promise<NotificationPage> {
  return jsonRequest<NotificationPage>(`/api/v1/notifications?${buildQuery({ ...query })}`);
}

/** Seen again is a 204; someone else's notice and a missing one are the same 404. */
export async function markNotificationSeen(id: number): Promise<void> {
  return voidRequest(`/api/v1/notifications/${id}/seen`, { method: "POST" });
}

export async function markAllNotificationsSeen(): Promise<void> {
  return voidRequest("/api/v1/notifications/seen-all", { method: "POST" });
}
