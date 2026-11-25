// Notifications API — the caller's own notices (`GET /api/v1/notifications`) and the two "seen" writes (`POST /api/v1/notifications/{id}/seen`,
// `POST /api/v1/notifications/seen-all`, 204, idempotent). A notice is ids plus the names and the title the server reads when it answers
// (`chat-model.md` "Notifications"); there is no unseen, no delete. Thin wrappers: transport in ./http, types from ./schema.

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { components, paths } from "./schema";

export type NotificationPage = paths["/api/v1/notifications"]["get"]["responses"]["200"]["content"]["application/json"];
export type NotificationItem = NotificationPage["items"][number];
export type NotificationFeedItem = components["schemas"]["NotificationFeedItem"];
export type NotificationFeedPage = paths["/api/v1/notifications/feed"]["get"]["responses"]["200"]["content"]["application/json"];

/** The server caps `total` here (`NotificationPage.total`): a count at the cap means "at least this many". */
export const NOTIFICATION_TOTAL_CAP = 1000;

export type NotificationType = NotificationItem["type"];

/**
 * The kinds ADDRESSED to the reader (a person, a team, the watchers or everyone was named, their message was quoted, or a thread was
 * handed to them): the rooms rail's count and the thread list's `@` mark count only these (0.6 D1, 1.1 A21), and the drawer's "Mentions"
 * view lists only these. The other kinds (replies, new threads, status and owner moves) show in the bell and its badge, never there.
 */
export const ADDRESSED_TYPES: readonly NotificationType[] = ["MENTIONED", "MESSAGE_QUOTED", "TEAM_MENTIONED", "WATCHERS_MENTIONED", "EVERYONE_MENTIONED", "THREAD_ASSIGNED"];

export interface NotificationQuery {
  page: number;
  pageSize: number;
  /** `false` = the unseen ones only; omitted = both. */
  seen?: boolean;
  /** Any-of: only these kinds; omitted or empty = every kind. */
  type?: readonly NotificationType[];
  /** Server sort expression; omitted uses newest first. */
  sort?: string;
}

export interface NotificationFeedQuery extends Omit<NotificationQuery, "type"> {
  /** Any-of, including room membership notices; omitted or empty = every feed kind. */
  type?: readonly NotificationFeedItem["type"][];
  /** Collapse unseen plain reply notices by room/thread before count and pagination. */
  groupReplies?: boolean;
}

/** One page of the caller's notices, newest first (the server's default `-id`). */
export async function listNotifications(query: NotificationQuery): Promise<NotificationPage> {
  return jsonRequest<NotificationPage>(`/api/v1/notifications?${buildQuery({ ...query })}`);
}

/** The bell feed includes room-level notices alongside the released thread notice shape. */
export async function listNotificationFeed(query: NotificationFeedQuery): Promise<NotificationFeedPage> {
  return jsonRequest<NotificationFeedPage>(`/api/v1/notifications/feed?${buildQuery({ ...query })}`);
}

/** Seen again is a 204; someone else's notice and a missing one are the same 404. */
export async function markNotificationSeen(id: number): Promise<void> {
  return voidRequest(`/api/v1/notifications/${id}/seen`, { method: "POST" });
}

/** Mark exactly the raw replies represented by one grouped feed snapshot seen. */
export async function markReplyGroupSeen(roomId: number, threadNumber: number, fromId: number, throughId: number): Promise<void> {
  return voidRequest(`/api/v1/notifications/reply-groups/${roomId}/${threadNumber}/seen`, {
    method: "POST",
    body: JSON.stringify({ fromId, throughId }),
  });
}

export async function markAllNotificationsSeen(): Promise<void> {
  return voidRequest("/api/v1/notifications/seen-all", { method: "POST" });
}

export type NotificationPreferences = paths["/api/v1/users/{id}/notification-preferences"]["get"]["responses"]["200"]["content"]["application/json"];
export type RoomMembershipPreferences = components["schemas"]["RoomMembershipNotificationPreference"];
type PreferencesUpdate = paths["/api/v1/users/{id}/notification-preferences"]["put"]["requestBody"]["content"]["application/json"];
export type NotificationPreferencePair = PreferencesUpdate["disabled"][number];

/** Thread-notice switches plus optional room-membership switches (self or ADMIN); `emailAvailable` is false without outbound email. */
export async function getNotificationPreferences(userId: number): Promise<NotificationPreferences> {
  return jsonRequest<NotificationPreferences>(`/api/v1/users/${userId}/notification-preferences`);
}

/**
 * A wholesale replace (204): the pairs that are OFF and the pairs that are ON; a pair in neither takes its default (the app on, email off),
 * so the page sends every pair in one of the two.
 */
export async function setNotificationPreferences(
  userId: number,
  disabled: PreferencesUpdate["disabled"],
  enabled: PreferencesUpdate["disabled"],
  roomMembership?: RoomMembershipPreferences,
): Promise<void> {
  const body = { disabled, enabled, ...(roomMembership === undefined ? {} : { roomMembership }) } satisfies PreferencesUpdate;
  return voidRequest(`/api/v1/users/${userId}/notification-preferences`, { method: "PUT", body: JSON.stringify(body) });
}
