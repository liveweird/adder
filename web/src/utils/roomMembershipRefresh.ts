import type { QueryClient } from "@tanstack/react-query";
import { API_BASE } from "../api/http";
import { getSessionSnapshot, isSameSessionIdentity, type SessionSnapshot } from "../api/session";
import { activeQueriesAreFresh } from "./queryRefresh";
import { homeKey, roomKeys } from "./roomKeys";
import { beginRoomMemberEcho, canCorrelateReply, type MutationEcho } from "./liveReplyEcho";

const MEMBERSHIP_KEYS = [roomKeys.all, homeKey] as const;

/** Start optional same-origin room correlation; cross-origin deployments retain ordinary live invalidation. */
export function beginRoomMembershipEcho(
  queryClient: QueryClient,
  roomId: number,
  kind: "room.member_added" | "room.member_role_changed" | "room.member_removed",
  apiBase = API_BASE,
  pageUrl = window.location.href,
): MutationEcho | undefined {
  return canCorrelateReply(apiBase, pageUrl) ? beginRoomMemberEcho(queryClient, roomId, kind) : undefined;
}

/** Refresh every caller-visible room-membership projection without touching a replacement session's reads. */
export async function refreshRoomMembership(queryClient: QueryClient, session: SessionSnapshot): Promise<boolean> {
  if (!isSameSessionIdentity(session, getSessionSnapshot())) return false;
  await Promise.all(MEMBERSHIP_KEYS.map((queryKey) => queryClient.cancelQueries({ queryKey })));
  if (!isSameSessionIdentity(session, getSessionSnapshot())) return false;
  await Promise.all(MEMBERSHIP_KEYS.map((queryKey) => queryClient.invalidateQueries({ queryKey })));
  return isSameSessionIdentity(session, getSessionSnapshot()) && activeQueriesAreFresh(queryClient, ...MEMBERSHIP_KEYS);
}

/** Consume an exact live echo only after the committed write's active projections are authoritative. */
export async function settleRoomMembershipRefresh(
  queryClient: QueryClient,
  session: SessionSnapshot,
  echo?: MutationEcho,
): Promise<boolean> {
  let fresh = false;
  try {
    fresh = await refreshRoomMembership(queryClient, session);
  } catch {
    // The HTTP write committed. Replaying a held hint is the safe recovery when its refresh failed.
  }
  const current = isSameSessionIdentity(session, getSessionSnapshot());
  // A replacement session must neither receive the old refresh nor replay an old held frame into its caches.
  if (fresh || !current) echo?.confirm();
  echo?.cancel();
  return current;
}
