import { describe, expect, test } from "vitest";
import { QueryClient } from "@tanstack/react-query";
import { refreshAfterWatchWrite, WATCH_SEARCH_KEYS } from "./watch";
import { refreshQueriesAfterMutation } from "./queryRefresh";
import { roomKeys, searchAllKey } from "./roomKeys";

/** A client holding one fresh (not invalidated) result under each key. */
function seeded(keys: readonly (readonly unknown[])[]) {
  const client = new QueryClient();
  for (const key of keys) client.setQueryData(key, { items: [] });
  return client;
}
const stale = (client: QueryClient, key: readonly unknown[]) => client.getQueryState(key)?.isInvalidated === true;

describe("what a watch write makes stale", () => {
  const allRooms = [...searchAllKey, "", "all", 1, true] as const;
  const oneRoom = [...roomKeys.search(12), "", "all", 1, true] as const;
  const detail = roomKeys.threadDetail(12, 42);
  const list = [...roomKeys.threadList(12), "x"] as const;
  const otherRoomList = [...roomKeys.threadList(13), "x"] as const;

  test("the caller's own watch write re-reads the thread, the room's lists AND the watched search lists (the live channel never names them)", async () => {
    const client = seeded([allRooms, oneRoom, detail, list, otherRoomList]);
    await refreshAfterWatchWrite(client, 12, 42);
    for (const key of [allRooms, oneRoom, detail, list]) expect(stale(client, key), JSON.stringify(key)).toBe(true);
    expect(stale(client, otherRoomList)).toBe(false);
  });

  test("the keys the automatic watches (a new thread, a hand-over) add are the search prefix, outside the rooms tree", async () => {
    expect(WATCH_SEARCH_KEYS).toEqual([searchAllKey]);
    const client = seeded([allRooms, oneRoom, list]);
    await refreshQueriesAfterMutation(client, ["rooms"], ...WATCH_SEARCH_KEYS);
    expect(stale(client, allRooms)).toBe(true);
    expect(stale(client, oneRoom)).toBe(true);
    expect(stale(client, list)).toBe(true);
  });
});
