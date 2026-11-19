// Search API — `GET /api/v1/rooms/{id}/search` (one room, a member or an ADMIN) and `GET /api/v1/search` (the caller's own rooms, an ADMIN's too).
// Thin endpoint wrappers: transport in ./http, types from ./schema. An item is a thread with its three newest matching messages; a snippet is a
// list of typed segments (`{text, hit}`), plain text that the SPA never renders as HTML or Markdown. `q` stays in the query string.

import { buildQuery, jsonRequest } from "./http";
import type { paths } from "./schema";

export type ThreadSearchPage = paths["/api/v1/search"]["get"]["responses"]["200"]["content"]["application/json"];
export type ThreadSearchHit = ThreadSearchPage["items"][number];
export type MessageSearchHit = ThreadSearchHit["messages"][number];
export type SnippetSegment = ThreadSearchHit["titleSnippet"][number];

export interface SearchQuery {
  /** Trimmed, 2 to 100 characters with a letter or a digit (`utils/searchParams.ts` `queryProblem`): the server answers 400 otherwise, never echoing it. */
  q: string;
  page: number;
  pageSize: number;
  /** `true` = only closed threads, `false` = only open ones; omitted = both. */
  closed?: boolean;
}

/** One page of the threads of ONE room that match (a non-member and an unknown room get the same 404). Newest activity first. */
export async function searchRoom(roomId: number, query: SearchQuery): Promise<ThreadSearchPage> {
  return jsonRequest<ThreadSearchPage>(`/api/v1/rooms/${roomId}/search?${buildQuery({ ...query })}`);
}

/** One page of the threads that match across the rooms the caller is a member of (none: an empty page). Newest activity first. */
export async function searchRooms(query: SearchQuery): Promise<ThreadSearchPage> {
  return jsonRequest<ThreadSearchPage>(`/api/v1/search?${buildQuery({ ...query })}`);
}
