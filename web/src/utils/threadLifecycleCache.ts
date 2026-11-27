import { partialMatchKey, type QueryClient, type QueryKey } from "@tanstack/react-query";
import { clearRoomLinkPreviews } from "./linkPreviewCache";
import { clearRoomMessageRevisions } from "./messageRevisionCache";
import { quoteDependencyKeys } from "./quoteCacheDependencies";
import { homeKey, notificationsKey, roomKeys, searchAllKey } from "./roomKeys";
import type { LiveServerFrame } from "./liveProtocol";

/** Shared invalidation for a local lifecycle mutation or a live hint from another writer. */
export function threadLifecycleKeys(roomId: number, deleted: boolean): QueryKey[] {
  const workflow = [roomKeys.workflow(roomId), roomKeys.workflowEdit(roomId), homeKey];
  return deleted
    ? [roomKeys.threadList(roomId), roomKeys.pins(roomId), notificationsKey, roomKeys.rail, ...workflow]
    : [roomKeys.threads(roomId), ...workflow];
}

function isMessageOrPinKey(queryKey: QueryKey): boolean {
  return queryKey[0] === "rooms" && (queryKey.includes("messages") || queryKey.includes("pins"));
}

function minimalPrefixes(keys: readonly QueryKey[]): QueryKey[] {
  return [...keys]
    .sort((left, right) => left.length - right.length)
    .reduce<QueryKey[]>((minimal, key) => {
      if (!minimal.some((prefix) => partialMatchKey(key, prefix))) minimal.push(key);
      return minimal;
    }, []);
}

function isAffectedSearch(queryKey: QueryKey, roomId: number): boolean {
  return queryKey[0] === searchAllKey[0] && (queryKey[1] !== "room" || queryKey[2] === roomId);
}

/** Active non-terminal reads whose successful refresh proves deleted content no longer remains visible. */
export function deletedThreadReliabilityKeys(queryClient: QueryClient, roomId: number): QueryKey[] {
  const activeSearches = queryClient.getQueryCache().findAll({ type: "active" })
    .filter(({ queryKey }) => isAffectedSearch(queryKey, roomId))
    .map(({ queryKey }) => queryKey);
  return minimalPrefixes([roomKeys.pins(roomId), ...activeSearches]);
}

/**
 * Cached or pending destination reads that can expose a quote from a deleted thread. A pending read has no body to inspect yet, so include every
 * foreign message/pin read and let the ordinary authorized read return its current representation.
 */
export function deletedThreadDependencies(queryClient: QueryClient, frame: LiveServerFrame): QueryKey[] {
  if (frame.type !== "event" || frame.kind !== "thread.deleted") return [];
  const cached = quoteDependencyKeys(queryClient, frame);
  const pending = queryClient.getQueryCache().getAll()
    .filter(({ queryKey, state }) => state.fetchStatus === "fetching" && isMessageOrPinKey(queryKey))
    .map(({ queryKey }) => queryKey);
  return minimalPrefixes([...cached, ...pending].filter((key) => key[2] !== frame.roomId));
}

/** Reset data that can hold deleted content, including cached merged histories, cross-room quotes and in-flight reads. */
export async function resetDeletedThreadContent(
  queryClient: QueryClient,
  roomId: number,
  dependencies: readonly QueryKey[] = [],
): Promise<void> {
  clearRoomLinkPreviews(queryClient, roomId);
  clearRoomMessageRevisions(queryClient, roomId);
  await Promise.all([
    queryClient.resetQueries({ queryKey: searchAllKey, predicate: ({ queryKey }) => isAffectedSearch(queryKey, roomId) }),
    queryClient.resetQueries({
      queryKey: roomKeys.threads(roomId),
      predicate: ({ queryKey }) => ["detail", "messages", "events", "watchers", "quote-source"].includes(String(queryKey[3])),
    }),
    queryClient.resetQueries({ queryKey: roomKeys.pins(roomId) }),
    ...dependencies.map((queryKey) => queryClient.resetQueries({ queryKey })),
  ]);
}
