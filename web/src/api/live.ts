// Live API — the single-use ticket for the live-update WebSocket (`POST /api/v1/live/tickets`, `.claude/docs/live.md`). The socket itself is
// `utils/liveClient.ts`; this wrapper only mints. It goes through `authedFetch` (the silent refresh, `timeoutSignal()`) like every wrapper, but
// reads the response itself so a refusal is a `LiveMintError` (the client's backoff reads its `retryAfterMs`).

import { ApiError, authedFetch, retryAfterMillis, safeJson } from "./http";
import type { paths } from "./schema";

export type LiveTicket = paths["/api/v1/live/tickets"]["post"]["responses"]["200"]["content"]["application/json"];

/** The problem `type` of the per-user socket cap (`429`): the tab shows "Live updates off" and tries again in a minute. */
export const LIVE_SOCKET_LIMIT_PROBLEM = "urn:adder:problem:live-socket-limit";

/** A refused mint: an `ApiError` whose `retryAfterMs` is the server's `Retry-After` (null when it sent none). */
export class LiveMintError extends ApiError {}

/** Mints one ticket: any signed-in caller; 429 on the per-user bucket, the socket cap or a full ticket store, 503 while the server stops. */
export async function mintLiveTicket(): Promise<LiveTicket> {
  const res = await authedFetch("/api/v1/live/tickets", { method: "POST" });
  if (!res.ok) throw new LiveMintError(res.status, await safeJson(res), retryAfterMillis(res));
  return (await res.json()) as LiveTicket;
}
