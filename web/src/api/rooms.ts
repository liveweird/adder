// Rooms API (`/api/v1/rooms`) — a room is members-only: a non-member (and an unknown id) gets the same 404, an ADMIN reads every room.
// Thin endpoint wrappers: transport (authedFetch/ApiError) in ./http, types from ./schema. The room's members and its workflow
// (a whole-document replace under `X-Expected-Revision`) live here too.

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

export type RoomPage = paths["/api/v1/rooms"]["get"]["responses"]["200"]["content"]["application/json"];
export type RoomListItem = RoomPage["items"][number];
export type RoomAccessType = RoomListItem["accessType"];
export type RoomResponse = paths["/api/v1/rooms/{id}"]["get"]["responses"]["200"]["content"]["application/json"];
export type RoomAccessPreview = paths["/api/v1/rooms/{id}/access"]["get"]["responses"]["200"]["content"]["application/json"];
export type RoomJoinRequestPage = paths["/api/v1/rooms/{id}/join-requests"]["get"]["responses"]["200"]["content"]["application/json"];
export type RoomJoinRequest = RoomJoinRequestPage["items"][number];
export type RoomWorkflow = paths["/api/v1/rooms/{id}/workflow"]["get"]["responses"]["200"]["content"]["application/json"];
export type RoomWorkflowUpdateBody = paths["/api/v1/rooms/{id}/workflow"]["put"]["requestBody"]["content"]["application/json"];
export type RoomCreateBody = paths["/api/v1/rooms"]["post"]["requestBody"]["content"]["application/json"];
export type RoomUpdateBody = paths["/api/v1/rooms/{id}"]["put"]["requestBody"]["content"]["application/json"];

export type RoomRole = paths["/api/v1/rooms/{id}/members"]["get"]["responses"]["200"]["content"]["application/json"]["items"][number]["role"];
export type RoomMemberPage = paths["/api/v1/rooms/{id}/members"]["get"]["responses"]["200"]["content"]["application/json"];
export type RoomMembersAddBody = paths["/api/v1/rooms/{id}/members"]["post"]["requestBody"]["content"]["application/json"];
export type RoomMembersAddResult = paths["/api/v1/rooms/{id}/members"]["post"]["responses"]["200"]["content"]["application/json"];

type RoomListQuery = {
  page: number;
  pageSize: number;
  sort?: string;
  name?: string;
  /** Omitted = both; the rail sends `false` (and a second `true` query for the archived toggle). */
  archived?: boolean;
  /** `true` = only the caller's own rooms (the rail always sends it); omitted = everything readable (every room for an ADMIN). */
  member?: boolean;
  /** Only the rooms this person is a member of, narrowed to what the caller may read (the profile page's rooms): the shared ones for a member. */
  memberId?: number;
};

export async function listRooms(q: RoomListQuery): Promise<RoomPage> {
  const params = buildQuery({
    page: q.page,
    pageSize: q.pageSize,
    sort: q.sort,
    name: q.name,
    archived: q.archived,
    member: q.member,
    memberId: q.memberId,
  });
  return jsonRequest<RoomPage>(`/api/v1/rooms?${params}`);
}

export async function getRoom(id: number): Promise<RoomResponse> {
  return jsonRequest<RoomResponse>(`/api/v1/rooms/${id}`);
}

export async function getRoomAccess(id: number): Promise<RoomAccessPreview> {
  return jsonRequest<RoomAccessPreview>(`/api/v1/rooms/${id}/access`);
}

export async function joinPublicRoom(id: number): Promise<void> {
  await voidRequest(`/api/v1/rooms/${id}/join`, { method: "POST" });
}

export async function requestRoomMembership(id: number): Promise<void> {
  await voidRequest(`/api/v1/rooms/${id}/join-requests`, { method: "POST" });
}

export async function listRoomJoinRequests(id: number, q: { page: number; pageSize: number; sort?: string }): Promise<RoomJoinRequestPage> {
  const params = buildQuery(q);
  return jsonRequest<RoomJoinRequestPage>(`/api/v1/rooms/${id}/join-requests?${params}`);
}

export async function decideRoomJoinRequest(id: number, requestId: number, decision: "APPROVE" | "REJECT"): Promise<void> {
  await voidRequest(`/api/v1/rooms/${id}/join-requests/${requestId}`, {
    method: "PUT",
    body: JSON.stringify({ decision }),
  });
}

export async function getRoomWorkflow(id: number): Promise<RoomWorkflow> {
  return jsonRequest<RoomWorkflow>(`/api/v1/rooms/${id}/workflow`);
}

/**
 * Replaces the room's workflow (204; read it again for the new revision). `expectedRevision` is the revision the caller read: a
 * stale one is a 409 `revision-conflict`; a retired status that threads use without a replacement is a 409 `status-in-use`.
 */
export async function updateRoomWorkflow(id: number, body: RoomWorkflowUpdateBody, expectedRevision: number): Promise<void> {
  await voidRequest(`/api/v1/rooms/${id}/workflow`, {
    method: "PUT",
    headers: { "X-Expected-Revision": String(expectedRevision) },
    body: JSON.stringify(body),
  });
}

export async function createRoom(body: RoomCreateBody): Promise<RoomResponse> {
  return jsonRequest<RoomResponse>("/api/v1/rooms", { method: "POST", body: JSON.stringify(body) });
}

export async function updateRoom(id: number, body: RoomUpdateBody): Promise<void> {
  await voidRequest(`/api/v1/rooms/${id}`, { method: "PUT", body: JSON.stringify(body) });
}

/** Idempotent: archiving an archived room is a 204 that changes nothing. */
export async function archiveRoom(id: number): Promise<void> {
  await voidRequest(`/api/v1/rooms/${id}/archive`, { method: "POST" });
}

export async function unarchiveRoom(id: number): Promise<void> {
  await voidRequest(`/api/v1/rooms/${id}/unarchive`, { method: "POST" });
}

/**
 * Records that the caller has looked at the room (204, forward only, no live hint). Members only: an ADMIN outside the room is a 403 and a
 * non-member a 404, so the SPA never sends it for one. View state, not an action: nothing is audited.
 */
export async function markRoomSeen(id: number): Promise<void> {
  await voidRequest(`/api/v1/rooms/${id}/seen`, { method: "POST" });
}

/**
 * "Mark all as read" (204, idempotent, no live hint): every thread of the room reads as read for the caller up to now and their unseen notices
 * of the room are seen. Members only (an ADMIN outside the room is a 403, a non-member a 404). One call per person at a time (a second is a `429`
 * `read-busy`). Audited on the server: ONE `room.read_all` line when the call settled
 * notices or failed part-way (a call with nothing to settle writes none).
 */
export async function markRoomRead(id: number): Promise<void> {
  await voidRequest(`/api/v1/rooms/${id}/read-all`, { method: "POST" });
}

/** ADMIN only (a soft delete: the room's name is freed, there is no restore). */
export async function deleteRoom(id: number): Promise<void> {
  await voidRequest(`/api/v1/rooms/${id}`, { method: "DELETE" });
}

type RoomMemberListQuery = {
  page: number;
  pageSize: number;
  sort?: string;
  /** Case- and accent-insensitive substring of the display name (at most 100 characters). */
  name?: string;
  role?: RoomRole;
};

export async function listRoomMembers(roomId: number, q: RoomMemberListQuery): Promise<RoomMemberPage> {
  const params = buildQuery({ page: q.page, pageSize: q.pageSize, sort: q.sort, name: q.name, role: q.role });
  return jsonRequest<RoomMemberPage>(`/api/v1/rooms/${roomId}/members?${params}`);
}

/** Idempotent: a person who already belongs is skipped and counted in `alreadyMembers`. A team is a snapshot of its roster now. */
export async function addRoomMembers(roomId: number, body: RoomMembersAddBody): Promise<RoomMembersAddResult> {
  return jsonRequest<RoomMembersAddResult>(`/api/v1/rooms/${roomId}/members`, { method: "POST", body: JSON.stringify(body) });
}

/** 409 `last-owner` when it would leave the room without an active owner. */
export async function setRoomMemberRole(roomId: number, userId: number, role: RoomRole): Promise<void> {
  await voidRequest(`/api/v1/rooms/${roomId}/members/${userId}`, { method: "PUT", body: JSON.stringify({ role }) });
}

/** A member removes themselves (a leave) or the room's manager removes someone; 409 `last-owner` for the last active owner either way. */
export async function removeRoomMember(roomId: number, userId: number): Promise<void> {
  await voidRequest(`/api/v1/rooms/${roomId}/members/${userId}`, { method: "DELETE" });
}
