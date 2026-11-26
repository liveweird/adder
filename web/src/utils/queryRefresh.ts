import type { QueryClient, QueryKey } from "@tanstack/react-query";

/** Refresh successful mutations without reusing a pending first fetch with no cached data. */
export async function refreshQueriesAfterMutation(queryClient: QueryClient, ...queryKeys: QueryKey[]): Promise<void> {
  // keepPreviousData belongs to the observer: default invalidation can still reuse that
  // query's initial fetch. Cancel every affected group before starting any fresh reads.
  await Promise.all(queryKeys.map((queryKey) => queryClient.cancelQueries({ queryKey })));
  await Promise.all(queryKeys.map((queryKey) => queryClient.invalidateQueries({ queryKey })));
}

/** Whether every active query covered by [queryKeys] holds authoritative data after a mutation refresh. */
export function activeQueriesAreFresh(queryClient: QueryClient, ...queryKeys: QueryKey[]): boolean {
  return queryKeys.every((queryKey) => queryClient.getQueryCache().findAll({ queryKey, type: "active" })
    .every(({ state }) => state.status === "success" && state.fetchStatus === "idle" && !state.isInvalidated));
}
