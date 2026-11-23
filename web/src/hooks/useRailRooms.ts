import { useQuery } from "@tanstack/react-query";
import { listRooms } from "../api/rooms";
import { roomKeys } from "../utils/roomKeys";

/** The rail shows at most this many rooms per list; beyond it a "more rooms" link leads to the Rooms page. */
export const RAIL_PAGE_SIZE = 100;

/** The rail's query options: `useRoomSeen` reads the same cache entry without fetching it (`enabled: false`). */
export const railQuery = (archived: boolean) => ({
  queryKey: [...roomKeys.rail, archived ? "archived" : "active"],
  queryFn: () => listRooms({ page: 1, pageSize: RAIL_PAGE_SIZE, sort: "personalOrder", archived, member: true }),
});

/**
 * The caller's rooms for the shell: the active ones always (the rail and the command palette share this one query), the archived
 * ones only while the rail's "Show archived" toggle is on. Both key under `["rooms", "rail", …]`, so any `["rooms"]` refresh
 * after a mutation updates the rail too.
 */
export function useRailRooms(includeArchived: boolean) {
  const active = useQuery(railQuery(false));
  const archived = useQuery({ ...railQuery(true), enabled: includeArchived });
  return { active, archived };
}
