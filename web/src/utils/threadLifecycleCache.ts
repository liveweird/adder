import type { QueryClient, QueryKey } from "@tanstack/react-query";
import { clearRoomLinkPreviews } from "./linkPreviewCache";
import { clearRoomMessageRevisions } from "./messageRevisionCache";
import { homeKey, notificationsKey, roomKeys, searchAllKey } from "./roomKeys";

/** Shared invalidation for a local lifecycle mutation or a live hint from another writer. */
export function threadLifecycleKeys(roomId: number, deleted: boolean): QueryKey[] {
  const workflow = [roomKeys.workflow(roomId), roomKeys.workflowEdit(roomId), homeKey];
  return deleted
    ? [roomKeys.threadList(roomId), roomKeys.pins(roomId), notificationsKey, roomKeys.rail, ...workflow]
    : [roomKeys.threads(roomId), ...workflow];
}

/** Reset data that can hold deleted content, including cached merged histories and in-flight reads. */
export async function resetDeletedThreadContent(queryClient: QueryClient, roomId: number): Promise<void> {
  clearRoomLinkPreviews(queryClient, roomId);
  clearRoomMessageRevisions(queryClient, roomId);
  await Promise.all([
    queryClient.resetQueries({ queryKey: searchAllKey, predicate: ({ queryKey }) => queryKey[1] !== "room" || queryKey[2] === roomId }),
    queryClient.resetQueries({
      queryKey: roomKeys.threads(roomId),
      predicate: ({ queryKey }) => ["detail", "messages", "events", "watchers"].includes(String(queryKey[3])),
    }),
  ]);
}
