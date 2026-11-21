import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { listRoomPins } from "../api/pins";
import { roomKeys } from "../utils/roomKeys";

/**
 * The room's pins (one page of 50 is the whole set) under `["rooms", "pins", roomId]`, ONE key for the header's count, the panel, the thread
 * pane's toggle, each message's menu and the list's marks, so they share the single read. The SPA derives "is this pinned" from it
 * (`threadNumbers`, `messageIds`, `pinnedByMessage`: who pinned each message, for its mark); a thread and a message of it can both be pinned.
 * `room.pin_changed` re-reads it.
 */
export function useRoomPins(roomId: number) {
  const query = useQuery({ queryKey: roomKeys.pins(roomId), queryFn: () => listRoomPins(roomId) });
  const items = query.data?.items;
  const sets = useMemo(
    () => ({
      threadNumbers: new Set((items ?? []).filter((pin) => pin.kind === "THREAD").map((pin) => pin.threadNumber)),
      messageIds: new Set((items ?? []).flatMap((pin) => (pin.kind === "MESSAGE" && pin.message ? [pin.message.id] : []))),
      pinnedByMessage: new Map((items ?? []).flatMap((pin) => (pin.kind === "MESSAGE" && pin.message ? [[pin.message.id, pin.pinnedByName] as const] : []))),
    }),
    [items],
  );
  return { ...query, ...sets };
}
