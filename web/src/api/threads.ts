// Threads API (`/api/v1/rooms/{id}/threads`) — a thread lives in one room and is numbered per room (`#42`); the room's guards apply
// unchanged (a non-member and an unknown room get the same 404). Thin endpoint wrappers: transport (authedFetch/ApiError) in ./http,
// types from ./schema. A thread is addressed by its per-room number, never its database id.

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

export type ThreadPage = paths["/api/v1/rooms/{id}/threads"]["get"]["responses"]["200"]["content"]["application/json"];
export type ThreadResponse = paths["/api/v1/rooms/{id}/threads/{number}"]["get"]["responses"]["200"]["content"]["application/json"];
export type ThreadCreateBody = paths["/api/v1/rooms/{id}/threads"]["post"]["requestBody"]["content"]["application/json"];
export type ThreadFieldValues = NonNullable<ThreadCreateBody["fields"]>;
export type ThreadUpdateBody = paths["/api/v1/rooms/{id}/threads/{number}"]["put"]["requestBody"]["content"]["application/json"];
export type ThreadEventPage = paths["/api/v1/rooms/{id}/threads/{number}/events"]["get"]["responses"]["200"]["content"]["application/json"];
export type ThreadEvent = ThreadEventPage["items"][number];

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

/**
 * Edits the title and fields (204; read the thread again for the new revision). `fields` MERGES over the stored values: a key left out
 * keeps its value and `null` clears it, so a cleared field is always sent as `null`. A stale `expectedRevision` is a 409
 * `revision-conflict`; a closed thread a 409 `thread-closed`.
 */
export async function updateThreadDetails(roomId: number, number: number, body: ThreadUpdateBody, expectedRevision: number): Promise<void> {
  await voidRequest(`/api/v1/rooms/${roomId}/threads/${number}`, {
    method: "PUT",
    headers: { "X-Expected-Revision": String(expectedRevision) },
    body: JSON.stringify(body),
  });
}

/** Moves the thread to the status with this KEY (204). Not revisioned: the last writer wins. Entering or leaving a CLOSED status needs a steward (403). */
export async function setThreadStatus(roomId: number, number: number, status: string): Promise<void> {
  await voidRequest(`/api/v1/rooms/${roomId}/threads/${number}/status`, { method: "PUT", body: JSON.stringify({ status }) });
}

/** Passes the thread on to an active member of the room (204); stewards only, and a closed thread refuses it (409 `thread-closed`). */
export async function setThreadOwner(roomId: number, number: number, userId: number): Promise<void> {
  await voidRequest(`/api/v1/rooms/${roomId}/threads/${number}/owner`, { method: "PUT", body: JSON.stringify({ userId }) });
}

/** One page of the thread's history, newest first (the server's default order). */
export async function listThreadEvents(roomId: number, number: number, q: { page: number; pageSize: number }): Promise<ThreadEventPage> {
  const params = buildQuery({ page: q.page, pageSize: q.pageSize });
  return jsonRequest<ThreadEventPage>(`/api/v1/rooms/${roomId}/threads/${number}/events?${params}`);
}
