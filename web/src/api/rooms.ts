// Rooms API (`/api/v1/rooms`) — a room is members-only: a non-member (and an unknown id) gets the same 404, an ADMIN reads every room.
// Thin endpoint wrappers: transport (authedFetch/ApiError) in ./http, types from ./schema. The room's members live here too; the
// workflow's write has its wrapper when its page arrives.

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

export type RoomPage = paths["/api/v1/rooms"]["get"]["responses"]["200"]["content"]["application/json"];
export type RoomListItem = RoomPage["items"][number];
export type RoomResponse = paths["/api/v1/rooms/{id}"]["get"]["responses"]["200"]["content"]["application/json"];
export type RoomWorkflow = paths["/api/v1/rooms/{id}/workflow"]["get"]["responses"]["200"]["content"]["application/json"];
export type RoomCreateBody = paths["/api/v1/rooms"]["post"]["requestBody"]["content"]["application/json"];
export type RoomUpdateBody = paths["/api/v1/rooms/{id}"]["put"]["requestBody"]["content"]["application/json"];

export type RoomRole = paths["/api/v1/rooms/{id}/members"]["get"]["responses"]["200"]["content"]["application/json"]["items"][number]["role"];
export type RoomMemberPage = paths["/api/v1/rooms/{id}/members"]["get"]["responses"]["200"]["content"]["application/json"];
export type RoomMember = RoomMemberPage["items"][number];
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
};

export async function listRooms(q: RoomListQuery): Promise<RoomPage> {
  const params = buildQuery({ page: q.page, pageSize: q.pageSize, sort: q.sort, name: q.name, archived: q.archived, member: q.member });
  return jsonRequest<RoomPage>(`/api/v1/rooms?${params}`);
}

export async function getRoom(id: number): Promise<RoomResponse> {
  return jsonRequest<RoomResponse>(`/api/v1/rooms/${id}`);
}

export async function getRoomWorkflow(id: number): Promise<RoomWorkflow> {
  return jsonRequest<RoomWorkflow>(`/api/v1/rooms/${id}/workflow`);
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
