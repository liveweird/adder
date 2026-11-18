// Rooms API (`/api/v1/rooms`) — a room is members-only: a non-member (and an unknown id) gets the same 404, an ADMIN reads every room.
// Thin endpoint wrappers: transport (authedFetch/ApiError) in ./http, types from ./schema. The room's members and workflow
// writes have their own wrappers when their pages arrive.

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

export type RoomPage = paths["/api/v1/rooms"]["get"]["responses"]["200"]["content"]["application/json"];
export type RoomListItem = RoomPage["items"][number];
export type RoomResponse = paths["/api/v1/rooms/{id}"]["get"]["responses"]["200"]["content"]["application/json"];
export type RoomWorkflow = paths["/api/v1/rooms/{id}/workflow"]["get"]["responses"]["200"]["content"]["application/json"];
export type RoomCreateBody = paths["/api/v1/rooms"]["post"]["requestBody"]["content"]["application/json"];
export type RoomUpdateBody = paths["/api/v1/rooms/{id}"]["put"]["requestBody"]["content"]["application/json"];

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
