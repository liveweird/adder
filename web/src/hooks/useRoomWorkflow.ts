import { useQuery } from "@tanstack/react-query";
import { getRoomWorkflow } from "../api/rooms";
import { roomKeys } from "../utils/roomKeys";

/**
 * The room's workflow (statuses with their colours and categories, and the fields), under `["rooms", "workflow", roomId]` — ONE
 * key for the thread list, the new-thread form and the thread pane, so a view mounted beside another shares the single read, and
 * any `["rooms"]` refresh (a thread started or moved changes each status's `threadCount`) re-reads it.
 */
export function useRoomWorkflow(roomId: number) {
  return useQuery({ queryKey: roomKeys.workflow(roomId), queryFn: () => getRoomWorkflow(roomId) });
}
