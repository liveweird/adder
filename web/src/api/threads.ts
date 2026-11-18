// Threads API (`/api/v1/rooms/{id}/threads`) — a thread lives in one room and is numbered per room (`#42`); the room's guards apply
// unchanged (a non-member and an unknown room get the same 404). Thin endpoint wrappers: transport (authedFetch/ApiError) in ./http,
// types from ./schema. A thread is addressed by its per-room number, never its database id.

import { buildQuery, jsonRequest } from "./http";
import type { paths } from "./schema";

export type ThreadPage = paths["/api/v1/rooms/{id}/threads"]["get"]["responses"]["200"]["content"]["application/json"];
export type ThreadResponse = paths["/api/v1/rooms/{id}/threads/{number}"]["get"]["responses"]["200"]["content"]["application/json"];
export type ThreadCreateBody = paths["/api/v1/rooms/{id}/threads"]["post"]["requestBody"]["content"]["application/json"];
export type ThreadFieldValues = NonNullable<ThreadCreateBody["fields"]>;

type ThreadListQuery = {
  page: number;
  pageSize: number;
  sort?: string;
  /** `true` = only closed threads, `false` = only open ones; omitted = both. */
  closed?: boolean;
};

export async function listThreads(roomId: number, q: ThreadListQuery): Promise<ThreadPage> {
  const params = buildQuery({ page: q.page, pageSize: q.pageSize, sort: q.sort, closed: q.closed });
  return jsonRequest<ThreadPage>(`/api/v1/rooms/${roomId}/threads?${params}`);
}

export async function getThread(roomId: number, number: number): Promise<ThreadResponse> {
  return jsonRequest<ThreadResponse>(`/api/v1/rooms/${roomId}/threads/${number}`);
}

/** Members only (an ADMIN who is not a member gets a 403). Starts in the initial status with the caller as owner; the opening message is part of the create. */
export async function createThread(roomId: number, body: ThreadCreateBody): Promise<ThreadResponse> {
  return jsonRequest<ThreadResponse>(`/api/v1/rooms/${roomId}/threads`, { method: "POST", body: JSON.stringify(body) });
}
