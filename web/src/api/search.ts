// Search API — `GET /api/v1/rooms/{id}/search` (one room, a member or an ADMIN) and `GET /api/v1/search` (the caller's own rooms, an ADMIN's too).
// Thin endpoint wrappers: transport in ./http, types from ./schema. An item is a thread with its three newest matching messages; a snippet is a
// list of typed segments (`{text, hit}`), plain text that the SPA never renders as HTML or Markdown. `q` stays in the query string.

import { buildQuery, jsonRequest } from "./http";
import type { paths } from "./schema";

export type ThreadSearchPage = paths["/api/v1/search"]["get"]["responses"]["200"]["content"]["application/json"];
export type ThreadSearchHit = ThreadSearchPage["items"][number];
export type MessageSearchHit = ThreadSearchHit["messages"][number];
export type SnippetSegment = ThreadSearchHit["titleSnippet"][number];

/** A flat page of matching messages inside one thread. */
export type ThreadMessageSearchPage = paths["/api/v1/rooms/{id}/threads/{number}/messages/search"]["get"]["responses"]["200"]["content"]["application/json"];

export interface ThreadMessageSearchQuery {
  q: string;
  syntax?: "structured";
  timeZone?: string;
  page: number;
  pageSize: number;
}

export interface SearchQuery {
  /**
   * Plain text (2 to 100 code points), or up to 512 code points of search clauses with `syntax: "structured"`.
   * A plain search with `watching: true`, `unread: true` or a `threadTag` can omit the query.
   */
  q?: string;
  syntax?: "structured";
  /** Time zone for structured date clauses. The API defaults to UTC. */
  timeZone?: string;
  page: number;
  pageSize: number;
  /** `true` = only closed threads, `false` = only open ones; omitted = both. */
  closed?: boolean;
  /** `true` = only the threads the caller watches (and `q` becomes optional), `false` = only the others; omitted = both. */
  watching?: boolean;
  /** `true` = only threads with something unread for the caller; omitted = both. */
  unread?: boolean;
  /** Any-of these CURATED tag names (the registry's, `GET /tag-categories`; at most 20; retired ones still match); makes `q` optional. */
  threadTag?: readonly string[];
}

/** One page of the threads of ONE room that match (a non-member and an unknown room get the same 404). Newest activity first. */
export async function searchRoom(roomId: number, query: SearchQuery): Promise<ThreadSearchPage> {
  return jsonRequest<ThreadSearchPage>(`/api/v1/rooms/${roomId}/search?${buildQuery({ ...query })}`);
}

/** One page of the threads that match across the rooms the caller is a member of (none: an empty page). Newest activity first. */
export async function searchRooms(query: SearchQuery): Promise<ThreadSearchPage> {
  return jsonRequest<ThreadSearchPage>(`/api/v1/search?${buildQuery({ ...query })}`);
}

/** Matching live messages of one thread, newest first. Thread-level clauses narrow the named thread and never become a title hit. */
export async function searchThreadMessages(
  roomId: number,
  threadNumber: number,
  query: ThreadMessageSearchQuery,
  signal?: AbortSignal,
): Promise<ThreadMessageSearchPage> {
  return jsonRequest<ThreadMessageSearchPage>(
    `/api/v1/rooms/${roomId}/threads/${threadNumber}/messages/search?${buildQuery({ ...query })}`,
    { signal },
  );
}
