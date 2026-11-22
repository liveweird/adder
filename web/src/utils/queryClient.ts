import { QueryClient } from "@tanstack/react-query";
import { queryRetryDelay, shouldRetryQuery } from "../api/http";
import type { LiveStatus } from "./liveClient";

/**
 * The live channel's status as the query client's reconnect rule sees it (reported by `useLiveUpdates`). Module state on purpose: the
 * rule is a query-client default, which has no React context to read it from.
 */
let liveStatus: LiveStatus = "connecting";

/** `useLiveUpdates` reports every status change of the tab's socket here (and `stopped` when it ends). */
export function reportLiveStatus(status: LiveStatus): void {
  liveStatus = status;
}

/**
 * Whether the browser coming back online may re-read the mounted queries. A HIDDEN tab and an `idle` one (hidden 8 hours, its socket
 * closed on purpose) make no API call of their own: a re-read on `online` would, through the silent refresh behind every `401`, keep the
 * session alive past the 8 hours the hidden-tab rule allows (`security.md`, "the 8 hours are the client's bound"). What the tab missed
 * is caught up when it is shown again: TanStack's focus refetch (`visibilitychange`) re-reads every stale query and the live client
 * reconnects with a `resync`; the bell of a quiet tab is the live channel's own, not this rule's. TanStack's other triggers cannot leak:
 * the focus refetch fires only on becoming VISIBLE (its focus manager reads `visibilityState`), and the one interval poll
 * (`TelemetryPanel`) does not run in the background.
 */
export function mayRefetchOnReconnect(doc: Pick<Document, "visibilityState"> = document): boolean {
  return doc.visibilityState !== "hidden" && liveStatus !== "idle";
}

/**
 * The app's ONE query client: never retries a 4xx except ONE retry of a read refused for load (`shouldRetryQuery`, `queryRetryDelay`:
 * after `Retry-After`), and re-reads on reconnect only while the tab is looked at.
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: shouldRetryQuery, retryDelay: queryRetryDelay, refetchOnReconnect: () => mayRefetchOnReconnect() } },
  });
}
