import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { markRoomSeen } from "../api/rooms";
import { markRoomSeenInCaches } from "../utils/readCache";
import { railQuery } from "./useRailRooms";
import { usePageVisible } from "./usePageVisible";

/** "Room seen" is posted at most this often per room (the server's bucket is 240 a minute for all read state). */
export const ROOM_SEEN_INTERVAL_MS = 5000;

/**
 * Tells the server the caller is looking at a room: once on arrival and then whenever the rail says the room is new again, at most once per
 * [ROOM_SEEN_INTERVAL_MS], only for a MEMBER (`member`: an ADMIN who is not in the room has no read state, and the server answers them 403) and only in a
 * tab that is being looked at. The rail's flag comes from the shell's own active-rooms query (watched here, never fetched here); an archived room
 * is not on it, so it is seen on arrival only. A success clears the flag in the cached rail and rooms list without a refetch, and a failure
 * is silent: the next arrival or the next "new" flag tries again. Unseen state is view state, never an error to show.
 */
export function useRoomSeen(roomId: number | null, member: boolean): void {
  const queryClient = useQueryClient();
  const visible = usePageVisible();
  // The shell's active-rooms query, watched and never fetched from here (a page rendered without the shell simply knows no flag).
  const rail = useQuery({ ...railQuery(false), enabled: false });
  const unread = roomId !== null && (rail.data?.items.find((room) => room.id === roomId)?.unread ?? false);
  const last = useRef<{ room: number; at: number } | null>(null);
  // A throttled "new" flag re-runs the effect when the wait is over.
  const [tick, setTick] = useState(0);

  useEffect(() => {
    if (roomId === null || !member || !visible) return;
    const sent = last.current?.room === roomId ? last.current : null;
    if (sent !== null && !unread) return;
    const wait = sent === null ? 0 : sent.at + ROOM_SEEN_INTERVAL_MS - Date.now();
    if (wait > 0) {
      const timer = setTimeout(() => setTick((n) => n + 1), wait);
      return () => clearTimeout(timer);
    }
    last.current = { room: roomId, at: Date.now() };
    markRoomSeen(roomId).then(
      () => markRoomSeenInCaches(queryClient, roomId),
      () => undefined,
    );
  }, [roomId, member, visible, unread, tick, queryClient]);
}
