// Live API — the single-use ticket for the live-update WebSocket (`POST /api/v1/live/tickets`, `.claude/docs/live.md`). The socket itself is
// `utils/liveClient.ts`; this wrapper only mints. It goes through `authedFetch` (the silent refresh, `timeoutSignal()`) like every wrapper, but
// reads the response itself so a refusal keeps its `Retry-After` for the client's backoff (`jsonRequest` drops the headers).

import { ApiError, authedFetch, safeJson } from "./http";
import type { paths } from "./schema";

export type LiveTicket = paths["/api/v1/live/tickets"]["post"]["responses"]["200"]["content"]["application/json"];

/** The problem `type` of the per-user socket cap (`429`): the tab shows "Live updates off" and tries again in a minute. */
export const LIVE_SOCKET_LIMIT_PROBLEM = "urn:adder:problem:live-socket-limit";

/** A refused mint: an `ApiError` that also carries the server's `Retry-After` in milliseconds (null when it sent none). */
export class LiveMintError extends ApiError {
  retryAfterMs: number | null;
  constructor(status: number, body: unknown, retryAfterMs: number | null) {
    super(status, body);
    this.retryAfterMs = retryAfterMs;
  }
}

/** `Retry-After` in whole seconds (the only form the server sends); anything else is "none". */
function retryAfterMillis(res: Response): number | null {
  const raw = res.headers.get("Retry-After");
  if (raw === null || !/^\d{1,6}$/.test(raw)) return null;
  return Number(raw) * 1000;
}

/** Mints one ticket: any signed-in caller; 429 on the per-user bucket, the socket cap or a full ticket store, 503 while the server stops. */
export async function mintLiveTicket(): Promise<LiveTicket> {
  const res = await authedFetch("/api/v1/live/tickets", { method: "POST" });
  if (!res.ok) throw new LiveMintError(res.status, await safeJson(res), retryAfterMillis(res));
  return (await res.json()) as LiveTicket;
}
