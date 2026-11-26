import type { QueryClient, QueryKey } from "@tanstack/react-query";
import { roomKeys } from "./roomKeys";

/** Cancel and forget sensitive prior message bodies immediately. */
export function clearMessageRevisions(queryClient: QueryClient, roomId: number, threadNumber: number, messageId?: number): void {
  clearSensitiveQueries(queryClient, roomKeys.messageRevisions(roomId, threadNumber, messageId));
}

/** Cancel and forget every prior body read under a room whose access has gone. */
export function clearRoomMessageRevisions(queryClient: QueryClient, roomId: number): void {
  clearSensitiveQueries(queryClient, roomKeys.messageRevisionRoom(roomId));
}

function clearSensitiveQueries(queryClient: QueryClient, key: QueryKey): void {
  // Reset clears active observers and cancels requests. Eviction alone would leave the last body in a mounted dialog.
  queryClient.getQueryCache().findAll({ queryKey: key }).forEach((query) => query.reset());
  queryClient.removeQueries({ queryKey: key });
}
