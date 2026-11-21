import type { TFunction } from "i18next";
import type { QueryClient } from "@tanstack/react-query";
import type { RoomResponse } from "../api/rooms";
import { isAccessRefusal } from "./roomAccess";
import { refreshQueriesAfterMutation } from "./queryRefresh";
import { roomKeys, searchAllKey } from "./roomKeys";
import { saveErrorMessage } from "./saveError";

/**
 * 1.1 D1: any member of the room watches and unwatches any thread of it, a closed thread and an ARCHIVED room included (a watch is the
 * member's own state, not a write to the room); an ADMIN who is not a member has no watch. UX only, the server guards.
 */
export function canWatch(room: Pick<RoomResponse, "role">): boolean {
  return room.role !== null;
}

/** The failure vocabulary of a watch write (a `room-busy` 429 has its own words in `saveErrorMessage`; the bucket's 429 is "too quickly"). */
export function watchErrorMessage(err: unknown, t: TFunction): string {
  return saveErrorMessage(err, t, {
    forbidden: "threads.watch.error.forbidden",
    notFound: "threads.watch.error.gone",
    tooManyRequests: "threads.watch.error.tooManyRequests",
    failedStatus: "common.error.actionFailedStatus",
    failed: "common.error.actionFailed",
  });
}

/** The search keys a watch change makes stale (the watched lists; a `watching=false` search too): the whole `["search"]` prefix. */
export const WATCH_SEARCH_KEYS = [searchAllKey] as const;

/**
 * After the caller's own watch write: the thread's detail (its `watching` and `watcherCount`) and the room's thread lists (the row marks and
 * the "Watching" filter) are re-read. A watch moves no message, count or activity, and emits no live hint (the caller's other tabs catch up
 * on focus), so nothing else changes. The search keys sit OUTSIDE `["rooms"]` and no hint re-reads them, so the caller's own write names them
 * (`searchAllKey` is the prefix of both the search across rooms and a room's own): the "Threads I watch" list, read within its stale time, shows
 * the new state. The paths that watch automatically (starting a thread, taking one over) name them too (`WATCH_SEARCH_KEYS`).
 */
export function refreshAfterWatchWrite(queryClient: QueryClient, roomId: number, threadNumber: number): Promise<void> {
  return refreshQueriesAfterMutation(queryClient, roomKeys.threadDetail(roomId, threadNumber), roomKeys.threadList(roomId), ...WATCH_SEARCH_KEYS);
}


/**
 * After a FAILED watch write: a refusal that says the caller's standing is not what the page assumed (403, 404, 409: removed, the room or
 * the thread gone) re-reads the whole `["rooms"]` prefix so the controls follow the truth; anything else (a 429, a network failure) changed nothing.
 */
export async function refreshAfterWatchFailure(queryClient: QueryClient, err: unknown): Promise<void> {
  if (isAccessRefusal(err)) await refreshQueriesAfterMutation(queryClient, roomKeys.all);
}
