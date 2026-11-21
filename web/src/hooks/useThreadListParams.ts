import { useCallback, useEffect, useLayoutEffect, useMemo, useRef } from "react";
import { readStoredJson, writeStoredJson } from "./useStoredState";
import { useLiveSearchParams } from "./useLiveSearchParams";
import {
  hasListKeys,
  parseThreadListParams,
  rememberedQuery,
  restoreRememberedQuery,
  writeThreadListParams,
  type ThreadListParams,
} from "../utils/threadListParams";

/** The view-settings key of a room's remembered list query (`adder.viewSettings.roomThreads.<id>.query`). */
const rememberedKey = (roomId: number) => `roomThreads.${roomId}.query`;

/** The remembered query of the room, or "" (nothing kept, or what is kept is not a string). */
function readRemembered(roomId: number): string {
  const stored = readStoredJson(rememberedKey(roomId));
  return typeof stored === "string" ? stored : "";
}

/**
 * The thread list's state, kept in the URL (`utils/threadListParams.ts` is the pure half). Writes go through `useLiveSearchParams`
 * (a handler captured by an older render still builds on the latest parameters), use `replace` (a filter is not a history entry)
 * and keep `location.state`.
 *
 * The last non-default query of each room (the page left out) is remembered in localStorage and comes back when the Threads view opens
 * on a URL that spells no list key: the rendered state reads it at once (no request goes out for the defaults first) and an effect
 * writes it into the URL by `replace`. Every write updates what is remembered (a default list remembers nothing), so the restore
 * happens once: after the reader chooses the plain list the URL is bare AND nothing is kept. `clear` drops the panel filters and keeps the rest.
 */
export function useThreadListParams(roomId: number) {
  const [search, setSearch, live] = useLiveSearchParams();
  // The room the latest render is for, read when a write runs: a handler captured in room A's render must never file B's params under A's key.
  const room = useRef(roomId);
  useLayoutEffect(() => {
    room.current = roomId;
  }, [roomId]);
  const bare = !hasListKeys(search);
  const remembered = bare ? readRemembered(roomId) : "";
  const params = useMemo(
    () => parseThreadListParams(remembered === "" ? search : restoreRememberedQuery(search, remembered)),
    [search, remembered],
  );

  useEffect(() => {
    // Read the live URL, not the render's: a write that landed since must not be overwritten by the restore.
    if (remembered === "" || hasListKeys(live.params())) return;
    setSearch((prev) => restoreRememberedQuery(prev, remembered), { replace: true, state: live.state() });
  }, [remembered, setSearch, live]);

  /** Lay `patch` over the list's state. Anything but the page goes back to page 1 in the same write. */
  const update = useCallback(
    (patch: Partial<ThreadListParams>) =>
      setSearch(
        (prev) => {
          const next = writeThreadListParams(prev, patch);
          writeStoredJson(rememberedKey(room.current), rememberedQuery(next));
          return next;
        },
        { replace: true, state: live.state() },
      ),
    [setSearch, live],
  );

  /**
   * Clear the PANEL filters (status, owner, "mentions me", "watching", hashtags, field) and go back to page 1; the scope and the order stay. What is remembered becomes what
   * remains (nothing, when that is the plain list).
   */
  const clear = useCallback(
    () =>
      setSearch(
        (prev) => {
          const next = writeThreadListParams(prev, { status: [], owner: null, mention: false, watching: false, tag: [], field: [] });
          writeStoredJson(rememberedKey(room.current), rememberedQuery(next));
          return next;
        },
        { replace: true, state: live.state() },
      ),
    [setSearch, live],
  );

  return { params, update, clear };
}
