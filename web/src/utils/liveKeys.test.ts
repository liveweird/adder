import { afterEach, describe, expect, test, vi } from "vitest";
import { QueryClient, QueryObserver, type QueryKey } from "@tanstack/react-query";
import { FakeClock } from "../test/socket";
import { createLiveInvalidator, keysForFrame } from "./liveKeys";
import { LIVE_EVENT_KINDS, type LiveServerFrame } from "./liveProtocol";
import { notificationKeys } from "./notificationKeys";
import { markThreadReadInCaches } from "./readCache";
import { notificationsKey, roomKeys, searchAllKey } from "./roomKeys";

const event = (kind: string, roomId: number, threadNumber?: number, messageId?: number): LiveServerFrame => ({
  type: "event",
  kind,
  roomId,
  ...(threadNumber === undefined ? {} : { threadNumber }),
  ...(messageId === undefined ? {} : { messageId }),
});

const RAIL: QueryKey = ["rooms", "rail"];
const LIST: QueryKey = ["rooms", "list"];
const gone = (r: number): QueryKey[] => [
  ["rooms", "detail", r],
  ["rooms", "threads", r],
  ["rooms", "members", r],
  ["rooms", "workflow", r],
  ["rooms", "workflow-edit", r],
  ["rooms", "pins", r],
  RAIL,
  LIST,
];

describe("keysForFrame: the key map", () => {
  test("thread.created re-reads the room's thread list, its workflow counts (both reads) and the rail", () => {
    expect(keysForFrame(event("thread.created", 5, 7))).toEqual([["rooms", "threads", 5, "list"], ["rooms", "workflow", 5], ["rooms", "workflow-edit", 5], RAIL]);
  });

  test("thread.updated and thread.owner_changed re-read the thread, its history, the room's thread list and the rail", () => {
    const keys = [["rooms", "threads", 5, "detail", 7], ["rooms", "threads", 5, "events", 7], ["rooms", "threads", 5, "list"], RAIL];
    expect(keysForFrame(event("thread.updated", 5, 7))).toEqual(keys);
    expect(keysForFrame(event("thread.owner_changed", 5, 7))).toEqual(keys);
  });

  test("thread.status_changed adds the workflow's thread counts", () => {
    expect(keysForFrame(event("thread.status_changed", 5, 7))).toEqual([
      ["rooms", "threads", 5, "detail", 7],
      ["rooms", "threads", 5, "events", 7],
      ["rooms", "threads", 5, "list"],
      RAIL,
      ["rooms", "workflow", 5],
      ["rooms", "workflow-edit", 5],
    ]);
  });

  test("message.posted re-reads the conversation, the thread (its message count), the thread list and the rail", () => {
    expect(keysForFrame(event("message.posted", 5, 7, 99))).toEqual([["rooms", "threads", 5, "messages", 7], ["rooms", "threads", 5, "detail", 7], ["rooms", "threads", 5, "list"], RAIL]);
  });

  test("message.edited re-reads the conversation, the thread (an edit can add or drop a hashtag its header shows), the thread list (the same on its rows) and the room's pins (a redacted body must not linger in the panel)", () => {
    expect(keysForFrame(event("message.edited", 5, 7, 99))).toEqual([
      ["rooms", "threads", 5, "messages", 7],
      ["rooms", "threads", 5, "detail", 7],
      ["rooms", "threads", 5, "list"],
      ["rooms", "pins", 5],
    ]);
  });

  test("message.deleted re-reads the conversation, the thread, the thread list, the room's pins and the notices (not the rail)", () => {
    expect(keysForFrame(event("message.deleted", 5, 7, 99))).toEqual([["rooms", "threads", 5, "messages", 7], ["rooms", "threads", 5, "detail", 7], ["rooms", "threads", 5, "list"], ["rooms", "pins", 5], ["notifications"]]);
  });

  test("message.reaction_changed re-reads the conversation, and nothing else: a reaction moves no count, order or rail", () => {
    expect(keysForFrame(event("message.reaction_changed", 5, 7, 99))).toEqual([["rooms", "threads", 5, "messages", 7]]);
  });

  test("room.pin_changed re-reads the room's pins, and nothing else", () => {
    expect(keysForFrame(event("room.pin_changed", 5, 7, 99))).toEqual([["rooms", "pins", 5]]);
  });

  test("room.created re-reads the rail and the rooms list", () => {
    expect(keysForFrame(event("room.created", 5))).toEqual([RAIL, LIST]);
  });

  test("room.updated, room.archived and room.unarchived re-read the room, the rail and the rooms list", () => {
    const keys = [["rooms", "detail", 5], RAIL, LIST];
    for (const kind of ["room.updated", "room.archived", "room.unarchived"]) expect(keysForFrame(event(kind, 5))).toEqual(keys);
  });

  test("room.workflow_updated re-reads both workflow reads, the thread list and every thread's detail and history", () => {
    expect(keysForFrame(event("room.workflow_updated", 5))).toEqual([
      ["rooms", "workflow", 5],
      ["rooms", "workflow-edit", 5],
      ["rooms", "threads", 5, "list"],
      ["rooms", "threads", 5, "detail"],
      ["rooms", "threads", 5, "events"],
    ]);
  });

  test("room.member_added, room.member_role_changed and room.member_removed re-read the members (table and picker), the room, the rail and the rooms list", () => {
    const keys = [["rooms", "members", 5], ["rooms", "detail", 5], RAIL, LIST];
    for (const kind of ["room.member_added", "room.member_role_changed", "room.member_removed"]) expect(keysForFrame(event(kind, 5))).toEqual(keys);
  });

  test("room.deleted and an unknown kind with a room re-read everything the room's pages read; room.deleted also names the room's search results", () => {
    expect(keysForFrame(event("room.deleted", 5))).toEqual([...gone(5), ["search", "room", 5], ["notifications"]]);
    expect(keysForFrame(event("room.from_the_future", 5, 7))).toEqual(gone(5));
  });

  test("revoked re-reads the same, the room's own search results, and an open search across rooms (a lost room's hits must leave it)", () => {
    expect(keysForFrame({ type: "revoked", roomId: 5 })).toEqual([...gone(5), ["search", "room", 5], ["search"], ["notifications"]]);
  });

  test("of every frame, only revoked and room.deleted name a room's search results: results are not live", () => {
    // A hint "names" a search when invalidating its key would re-read the search (the key is a prefix of the search's, or the other way round).
    const search = roomKeys.search(5);
    const names = (frame: LiveServerFrame) => keysForFrame(frame).some((key) => startsWith(search, key) || startsWith(key, search));
    const frames: LiveServerFrame[] = [
      ...LIVE_EVENT_KINDS.map((kind) => event(kind, 5, 7, 99)),
      ...LIVE_EVENT_KINDS.map((kind) => event(kind, 5)),
      event("room.from_the_future", 5, 7),
      { type: "revoked", roomId: 5 },
      { type: "subscribed", roomIds: [5, 6], refused: [], capped: [] },
      { type: "ready", protocol: 1, maxSubscriptions: 128 },
      { type: "resync" },
      { type: "pong" },
    ];
    const naming = frames.filter(names).map((frame) => (frame.type === "event" ? frame.kind : frame.type));
    expect([...new Set(naming)].sort()).toEqual(["revoked", "room.deleted"]);
    // ...and the search of ANOTHER room is never named by a frame about room 5.
    expect(frames.some((frame) => keysForFrame(frame).some((key) => startsWith(roomKeys.search(6), key) && key.length > 1))).toBe(false);
  });

  test("a thread or message kind without its thread number falls back to the room's thread tree and the rail", () => {
    expect(keysForFrame(event("message.posted", 5))).toEqual([["rooms", "threads", 5], RAIL]);
  });

  test("message.deleted without its thread number still re-reads the notices: the ones about the message went with it", () => {
    expect(keysForFrame(event("message.deleted", 5))).toEqual([["rooms", "threads", 5], RAIL, ["notifications"]]);
  });

  test("a subscribed ack re-reads, per acked room, what the page read before the subscription existed", () => {
    expect(keysForFrame({ type: "subscribed", roomIds: [5, 6], refused: [9], capped: [] })).toEqual([
      ["rooms", "detail", 5],
      ["rooms", "threads", 5],
      ["rooms", "members", 5],
      ["rooms", "workflow", 5],
      ["rooms", "workflow-edit", 5],
      ["rooms", "detail", 6],
      ["rooms", "threads", 6],
      ["rooms", "members", 6],
      ["rooms", "workflow", 6],
      ["rooms", "workflow-edit", 6],
    ]);
    expect(keysForFrame({ type: "subscribed", roomIds: [], refused: [9], capped: [] })).toEqual([]);
  });

  test("ready re-reads the rail, the rooms list and the notices, resync all of ['rooms'] and the notices, pong nothing", () => {
    expect(keysForFrame({ type: "ready", protocol: 1, maxSubscriptions: 128 })).toEqual([RAIL, LIST, ["notifications"]]);
    expect(keysForFrame({ type: "resync" })).toEqual([["rooms"], ["notifications"]]);
    expect(keysForFrame({ type: "pong" })).toEqual([]);
  });

  test("a notice is personal: it re-reads the notifications and nothing of the room", () => {
    expect(keysForFrame(event("notification.created", 5, 7, 99))).toEqual([["notifications"]]);
    expect(keysForFrame(event("notification.created", 5))).toEqual([["notifications"]]);
  });

  test("every kind the protocol lists has a row (none falls through to the unknown-kind answer)", () => {
    for (const kind of LIVE_EVENT_KINDS) {
      const keys = keysForFrame(event(kind, 5, 7, 99));
      if (kind !== "room.deleted") expect(keys, kind).not.toEqual(gone(5));
      expect(keys.length, kind).toBeGreaterThan(0);
    }
  });
});

const startsWith = (key: readonly unknown[], prefix: readonly unknown[]) => prefix.every((part, i) => key[i] === part);

/** The key a filter names (the spy's parameter type hides it behind `Omit`). */
const keyOf = (filters: unknown) => (filters as { queryKey: unknown[] }).queryKey;

describe("the keys and the readers cannot drift apart", () => {
  // The query keys the pages really read under, built from the SAME roomKeys the readers use (plus the parameters they append).
  const READERS: (readonly unknown[])[] = [
    [...roomKeys.rail, "active"],
    [...roomKeys.list, 1, 20, "name", "", false, false],
    roomKeys.detail(5),
    [...roomKeys.members(5), 1, 20, "name", "", null],
    [...roomKeys.members(5), "picker", "term"],
    roomKeys.workflow(5),
    roomKeys.workflowEdit(5),
    roomKeys.pins(5),
    [...roomKeys.threadList(5), 1, 50, "-lastActivityAt", "open"],
    [...roomKeys.roomHashtags(5), "term"],
    roomKeys.threadDetail(5, 7),
    [...roomKeys.threadEvents(5, 7), 1],
    roomKeys.messages(5, 7),
    [...roomKeys.search(5), "term", "all", 1],
    [...searchAllKey, "term", "all", 1],
    [...notificationsKey, "unseen"], // the bell reads under this prefix
  ];

  test("the room's tag options sit under the thread-list prefix, so every hint that refreshes the list refreshes them", () => {
    expect(startsWith(roomKeys.roomHashtags(5), roomKeys.threadList(5))).toBe(true);
    const refreshesList = (kind: (typeof LIVE_EVENT_KINDS)[number]) =>
      keysForFrame(event(kind, 5, 7, 99)).some((key) => startsWith(roomKeys.threadList(5), key));
    expect(LIVE_EVENT_KINDS.filter(refreshesList).length).toBeGreaterThan(0);
    for (const kind of LIVE_EVENT_KINDS.filter(refreshesList)) {
      expect(
        keysForFrame(event(kind, 5, 7, 99)).some((key) => startsWith(roomKeys.roomHashtags(5), key)),
        kind,
      ).toBe(true);
    }
  });

  test("a room's search results sit outside both the room's thread tree and ['rooms'], so no hint for those re-reads them", () => {
    expect(startsWith(roomKeys.search(5), roomKeys.threads(5))).toBe(false);
    expect(startsWith(roomKeys.search(5), roomKeys.all)).toBe(false);
    expect(startsWith(roomKeys.search(5), searchAllKey)).toBe(true);
  });

  test("every key a hint names is a prefix of a key some reader uses", () => {
    for (const kind of LIVE_EVENT_KINDS) {
      for (const key of keysForFrame(event(kind, 5, 7, 99))) {
        expect(
          READERS.some((reader) => startsWith(reader, key)),
          `${kind}: ${JSON.stringify(key)}`,
        ).toBe(true);
      }
    }
    for (const key of keysForFrame({ type: "subscribed", roomIds: [5], refused: [], capped: [] })) expect(READERS.some((reader) => startsWith(reader, key))).toBe(true);
  });

  // The readers' and the hints' keys share roomKeys; this guard stops a new reader from spelling a prefix by hand (the desync it would hide).
  const sources = import.meta.glob(["../**/*.ts", "../**/*.tsx", "!../**/*.test.*", "!../test/**", "!../api/schema.ts", "!./roomKeys.ts"], {
    query: "?raw",
    import: "default",
    eager: true,
  }) as Record<string, string>;

  test("no source file spells a room key prefix outside roomKeys.ts", () => {
    const spelled = /"rooms",\s*"(rail|list|detail|members|workflow|workflow-edit|threads)"/;
    const offenders = Object.entries(sources)
      .filter(([, source]) =>
        source.split("\n").some((line) => {
          const text = line.trim();
          return !text.startsWith("//") && !text.startsWith("*") && !text.startsWith("/*") && spelled.test(line);
        }),
      )
      .map(([file]) => file);
    expect(offenders).toEqual([]);
  });
});

describe("createLiveInvalidator", () => {
  const clients: QueryClient[] = [];
  afterEach(() => {
    for (const client of clients.splice(0)) client.clear();
  });

  function setup() {
    const queryClient = new QueryClient();
    clients.push(queryClient);
    const clock = new FakeClock();
    const invalidate = vi.spyOn(queryClient, "invalidateQueries").mockResolvedValue();
    const fetching = vi.spyOn(queryClient, "isFetching").mockReturnValue(0);
    const invalidator = createLiveInvalidator(queryClient, clock);
    const flushed = () => invalidate.mock.calls.map(([filters]) => filters?.queryKey);
    // `message.edited` also re-reads the room's thread list (an aggregate, spaced on its own): a positive assertion about the conversation key filters it.
    const flushedMessages = () => flushed().filter((key) => (key as unknown[])[3] === "messages");
    /** Holds the room's thread list busy (a re-read of it waits, never cancels), so a test can watch the conversation key's timing alone, strictly. */
    const holdListBusy = () => fetching.mockImplementation((filters) => (keyOf(filters)[3] === "list" ? 1 : 0));
    return { queryClient, clock, invalidate, fetching, invalidator, flushed, flushedMessages, holdListBusy };
  }

  test("a re-read of an open search is flushed like any focused key, and the rail's next re-read is not delayed by it", () => {
    const t = setup();
    t.invalidator.handle({ type: "revoked", roomId: 5 });
    t.clock.advanceBy(300);
    expect(t.flushed()).toContainEqual(["search"]);
    expect(t.flushed()).toContainEqual(["search", "room", 5]);
    const railReads = () => t.flushed().filter((key) => JSON.stringify(key) === JSON.stringify(RAIL)).length;
    expect(railReads()).toBe(1);
    // The rail is spaced from ITS OWN last re-read (3 s), never from a search's: a hint 3 s after it re-reads it again at once (300 ms settle).
    t.clock.advanceBy(2_700);
    t.invalidator.handle(event("room.created", 5));
    t.clock.advanceBy(299);
    expect(railReads()).toBe(1);
    t.clock.advanceBy(1);
    expect(railReads()).toBe(2);
  });

  test("a read marker's re-read of the notices joins the hints' queue: one flush per spacing window", () => {
    const t = setup();
    t.queryClient.setQueryData(notificationKeys.unseen, { items: [{ id: 1, roomId: 5, threadNumber: 7 }], page: 1, pageSize: 100, total: 1 });
    const notificationReads = () => t.flushed().filter((key) => JSON.stringify(key) === JSON.stringify(notificationsKey)).length;

    // A hint and two markers in the same moment are ONE re-read after the settle.
    t.invalidator.handle(event("notification.created", 5, 7, 1));
    markThreadReadInCaches(t.queryClient, 5, 7, 10);
    markThreadReadInCaches(t.queryClient, 5, 7, 11);
    expect(notificationReads()).toBe(0);
    t.clock.advanceBy(300);
    expect(notificationReads()).toBe(1);

    // The next marker, a moment later, waits out the 3 s gap since that flush instead of re-reading at once.
    markThreadReadInCaches(t.queryClient, 5, 7, 12);
    t.clock.advanceBy(2_000);
    expect(notificationReads()).toBe(1);
    t.clock.advanceBy(1_000);
    expect(notificationReads()).toBe(2);

    // Once the channel is gone the marker re-reads straight away again.
    t.invalidator.dispose();
    markThreadReadInCaches(t.queryClient, 5, 7, 13);
    expect(notificationReads()).toBe(3);
  });

  test("a burst of hints settles for 300 ms after the last one and then re-reads each focused key once", () => {
    const t = setup();
    t.holdListBusy();
    t.invalidator.handle(event("message.edited", 5, 7, 1));
    t.clock.advanceBy(200);
    t.invalidator.handle(event("message.edited", 5, 7, 1));
    t.clock.advanceBy(299);
    expect(t.flushed()).toEqual([]);
    t.clock.advanceBy(1);
    expect(t.flushedMessages()).toEqual([["rooms", "threads", 5, "messages", 7]]);
  });

  test("a hint's keys are each re-read once, however many hints named them", () => {
    const t = setup();
    t.invalidator.handle(event("message.posted", 5, 7, 1));
    t.invalidator.handle(event("message.posted", 5, 7, 2));
    t.clock.advanceBy(300);
    expect(t.flushed()).toHaveLength(4);
    expect(new Set(t.flushed().map((key) => JSON.stringify(key))).size).toBe(4);
  });

  test("a focused key never waits more than a second from its first hint, however steadily they keep coming", () => {
    const t = setup();
    t.holdListBusy();
    t.invalidator.handle(event("message.edited", 5, 7, 1));
    for (let i = 0; i < 3; i++) {
      t.clock.advanceBy(250);
      t.invalidator.handle(event("message.edited", 5, 7, 1));
    }
    t.clock.advanceBy(249);
    expect(t.flushed()).toEqual([]);
    t.clock.advanceBy(1);
    expect(t.flushedMessages()).toEqual([["rooms", "threads", 5, "messages", 7]]);
  });

  test("the rail, the rooms list and a room's thread list are re-read at most once every three seconds", () => {
    const t = setup();
    t.invalidator.handle(event("room.created", 5));
    t.clock.advanceBy(300);
    expect(t.flushed()).toEqual([RAIL, LIST]);
    t.invalidator.handle(event("room.created", 5));
    t.invalidator.handle(event("room.created", 5));
    t.clock.advanceBy(2999);
    expect(t.flushed()).toHaveLength(2);
    t.clock.advanceBy(1);
    expect(t.flushed()).toEqual([RAIL, LIST, RAIL, LIST]);
    t.invalidator.handle(event("message.posted", 5, 7, 3));
    t.clock.advanceBy(300);
    expect(t.flushed().filter((key) => (key as unknown[])[3] === "list")).toHaveLength(1);
    t.invalidator.handle(event("message.posted", 5, 7, 4));
    t.clock.advanceBy(2999);
    expect(t.flushed().filter((key) => (key as unknown[])[3] === "list")).toHaveLength(1);
    t.clock.advanceBy(1);
    expect(t.flushed().filter((key) => (key as unknown[])[3] === "list")).toHaveLength(2);
  });

  test("ready and subscribed wait the same random 0 to 2 s, so reconnecting tabs do not refetch in one instant", () => {
    const t = setup();
    t.clock.randomValue = 0.5;
    t.invalidator.handle({ type: "ready", protocol: 1, maxSubscriptions: 128 });
    t.invalidator.handle({ type: "subscribed", roomIds: [5], refused: [], capped: [] });
    t.clock.advanceBy(999);
    expect(t.flushed()).toEqual([]);
    t.clock.advanceBy(1);
    expect(t.flushed()).toHaveLength(8);
  });

  test("a hint that arrives while a spread flush waits brings it forward to the settle", () => {
    const t = setup();
    t.clock.randomValue = 1;
    t.invalidator.handle({ type: "subscribed", roomIds: [5], refused: [], capped: [] });
    t.invalidator.handle(event("message.edited", 5, 7, 1));
    t.clock.advanceBy(300);
    expect(t.flushedMessages()).toEqual([["rooms", "threads", 5, "messages", 7]]);
  });

  test("a subscribed flush of the room's thread tree counts as the thread list's re-read: the 3 s gap holds", () => {
    const t = setup();
    t.clock.randomValue = 0;
    t.invalidator.handle({ type: "subscribed", roomIds: [5], refused: [], capped: [] });
    t.clock.advanceBy(0);
    expect(t.flushed()).toContainEqual(["rooms", "threads", 5]);
    t.invalidator.handle(event("thread.created", 5, 8));
    t.clock.advanceBy(2999);
    expect(t.flushed().filter((key) => (key as unknown[])[3] === "list")).toEqual([]);
    t.clock.advanceBy(1);
    expect(t.flushed().filter((key) => (key as unknown[])[3] === "list")).toHaveLength(1);
  });

  const bellReads = (t: ReturnType<typeof setup>) => t.flushed().filter((key) => JSON.stringify(key) === JSON.stringify(notificationsKey)).length;

  test("a burst of notification.created frames re-reads the notices at most once per 3 s", () => {
    const t = setup();
    t.clock.randomValue = 0;
    const notices = () => bellReads(t);
    t.invalidator.handle(event("notification.created", 5, 7, 1));
    t.invalidator.handle(event("notification.created", 5, 7, 2));
    t.clock.advanceBy(0);
    expect(notices()).toBe(1);
    // A hint right after the flush waits out the gap (3 s from that flush), then re-reads once for the whole burst.
    t.invalidator.handle(event("notification.created", 5, 7, 3));
    t.clock.advanceBy(1_000);
    t.invalidator.handle(event("notification.created", 5, 7, 4));
    t.clock.advanceBy(1_999);
    expect(notices()).toBe(1);
    t.clock.advanceBy(1);
    expect(notices()).toBe(2);
    // ...and the gap holds for the next one too.
    t.invalidator.handle(event("notification.created", 5, 7, 5));
    t.clock.advanceBy(2_999);
    expect(notices()).toBe(2);
    t.clock.advanceBy(1);
    expect(notices()).toBe(3);
  });

  test("a resync counts as the rail's and the rooms list's re-read too", () => {
    const t = setup();
    t.clock.randomValue = 0;
    t.invalidator.handle({ type: "resync" });
    t.clock.advanceBy(0);
    t.invalidator.handle(event("room.created", 5));
    t.clock.advanceBy(2999);
    expect(t.flushed()).toEqual([["rooms"], ["notifications"]]);
    t.clock.advanceBy(1);
    expect(t.flushed()).toEqual([["rooms"], ["notifications"], RAIL, LIST]);
  });

  test("a focused key is never spaced by the 3 s gap (only the aggregates are)", () => {
    const t = setup();
    t.invalidator.handle(event("message.edited", 5, 7, 1));
    t.clock.advanceBy(300);
    t.invalidator.handle(event("message.edited", 5, 7, 2));
    t.clock.advanceBy(300);
    expect(t.flushedMessages()).toHaveLength(2);
  });

  test("the thread-list re-read a message.edited causes keeps the 3 s spacing of the aggregates, while the conversation is not spaced", () => {
    const t = setup();
    const lists = () => t.flushed().filter((key) => (key as unknown[])[3] === "list");
    t.invalidator.handle(event("message.edited", 5, 7, 1));
    t.clock.advanceBy(300);
    expect(lists()).toHaveLength(1);
    t.invalidator.handle(event("message.edited", 5, 7, 2));
    t.clock.advanceBy(300);
    expect(t.flushedMessages()).toHaveLength(2);
    expect(lists()).toHaveLength(1);
    t.clock.advanceBy(2699);
    expect(lists()).toHaveLength(1);
    t.clock.advanceBy(1);
    expect(lists()).toHaveLength(2);
    expect(t.flushedMessages()).toHaveLength(2);
  });

  test("reaction hints re-read a conversation at most once per 3 s, and a post in between still re-reads it within a second", () => {
    const t = setup();
    t.holdListBusy();
    t.invalidator.handle(event("message.reaction_changed", 5, 7, 1));
    t.clock.advanceBy(300);
    expect(t.flushedMessages()).toHaveLength(1);
    // A storm of reactions: one re-read, three seconds after the last one.
    t.invalidator.handle(event("message.reaction_changed", 5, 7, 1));
    t.invalidator.handle(event("message.reaction_changed", 5, 7, 2));
    t.clock.advanceBy(2_699);
    expect(t.flushedMessages()).toHaveLength(1);
    // A post for the same conversation brings the re-read forward: within its own settle time, not three seconds.
    t.invalidator.handle(event("message.posted", 5, 7, 3));
    t.clock.advanceBy(299);
    expect(t.flushedMessages()).toHaveLength(1);
    t.clock.advanceBy(1);
    expect(t.flushedMessages()).toHaveLength(2);
    // Nothing is left over for the reactions' original due time.
    t.clock.advanceBy(3_000);
    expect(t.flushedMessages()).toHaveLength(2);
  });

  test("a post alone is never spaced, whatever reactions went before: only the reaction hint keeps the gap", () => {
    const t = setup();
    t.holdListBusy();
    t.invalidator.handle(event("message.reaction_changed", 5, 7, 1));
    t.clock.advanceBy(300);
    t.invalidator.handle(event("message.posted", 5, 7, 2));
    t.clock.advanceBy(300);
    expect(t.flushedMessages()).toHaveLength(2);
  });

  test("a post that joins a waiting reaction re-read and flushes it still gaps a reaction right after it by 3 s", () => {
    const t = setup();
    t.holdListBusy();
    t.invalidator.handle(event("message.reaction_changed", 5, 7, 1));
    t.clock.advanceBy(300);
    t.invalidator.handle(event("message.reaction_changed", 5, 7, 2));
    t.invalidator.handle(event("message.posted", 5, 7, 3));
    t.clock.advanceBy(300);
    expect(t.flushedMessages()).toHaveLength(2);
    t.invalidator.handle(event("message.reaction_changed", 5, 7, 4));
    t.clock.advanceBy(2_999);
    expect(t.flushedMessages()).toHaveLength(2);
    t.clock.advanceBy(1);
    expect(t.flushedMessages()).toHaveLength(3);
  });

  test("a post-driven flush stamps the conversation: a reaction right after a post is gapped, though no reaction came before it", () => {
    const t = setup();
    t.holdListBusy();
    // The reaction joins the post's pending entry without owning it (the same due time), so the flush is the post's.
    t.invalidator.handle(event("message.posted", 5, 7, 1));
    t.invalidator.handle(event("message.reaction_changed", 5, 7, 2));
    t.clock.advanceBy(300);
    expect(t.flushedMessages()).toHaveLength(1);
    t.invalidator.handle(event("message.reaction_changed", 5, 7, 3));
    t.clock.advanceBy(2_999);
    expect(t.flushedMessages()).toHaveLength(1);
    t.clock.advanceBy(1);
    expect(t.flushedMessages()).toHaveLength(2);
  });

  test("a reaction hint without a thread number falls back to the room's thread tree and the rail, like any thread-scoped hint", () => {
    expect(keysForFrame(event("message.reaction_changed", 5))).toEqual([["rooms", "threads", 5], RAIL]);
  });

  test("a resync waits a random 0 to 2 s and then re-reads everything under ['rooms']", () => {
    const t = setup();
    t.clock.randomValue = 0.5;
    t.invalidator.handle({ type: "resync" });
    t.clock.advanceBy(999);
    expect(t.flushed()).toEqual([]);
    t.clock.advanceBy(1);
    expect(t.flushed()).toEqual([["rooms"], ["notifications"]]);
  });

  test("a key being fetched is NOT cancelled or re-read: it is tried again until it is idle", () => {
    const t = setup();
    // The conversation, the thread, the list and the pins are all being fetched: none is re-read, nor cancelled, until it is idle.
    t.fetching.mockImplementation((filters) => (["messages", "detail", "list"].includes(keyOf(filters)[3] as string) || keyOf(filters)[1] === "pins" ? 1 : 0));
    t.invalidator.handle(event("message.edited", 5, 7, 1));
    t.clock.advanceBy(300);
    t.clock.advanceBy(300);
    t.clock.advanceBy(300);
    expect(t.flushed()).toEqual([]);
    t.fetching.mockReturnValue(0);
    t.clock.advanceBy(300);
    expect(t.flushed()).toEqual([["rooms", "threads", 5, "messages", 7], ["rooms", "threads", 5, "detail", 7], ["rooms", "threads", 5, "list"], ["rooms", "pins", 5]]);
    // Nothing was ever asked to cancel.
    expect(t.queryClient.isFetching()).toBe(0);
  });

  test("a busy key does not hold the others back", () => {
    const t = setup();
    t.fetching.mockImplementation((filters) => (keyOf(filters)[4] === 7 && keyOf(filters)[3] === "detail" ? 1 : 0));
    t.invalidator.handle(event("message.posted", 5, 7, 1));
    t.clock.advanceBy(300);
    expect(t.flushed()).toHaveLength(3);
    t.fetching.mockReturnValue(0);
    t.clock.advanceBy(300);
    expect(t.flushed()).toHaveLength(4);
  });

  test("dispose drops everything pending and its timer", () => {
    const t = setup();
    t.invalidator.handle(event("message.posted", 5, 7, 1));
    t.invalidator.dispose();
    expect(t.clock.pendingDelays()).toEqual([]);
    t.clock.advanceBy(10_000);
    expect(t.flushed()).toEqual([]);
  });

  test("with a real in-flight fetch, the hint waits for it instead of cancelling it, then re-reads", async () => {
    const queryClient = new QueryClient();
    clients.push(queryClient);
    const clock = new FakeClock();
    let release: (value: string) => void = () => {};
    let calls = 0;
    const queryFn = vi.fn(() => {
      calls += 1;
      return calls === 1 ? new Promise<string>((resolve) => (release = resolve)) : Promise.resolve("again");
    });
    const observer = new QueryObserver(queryClient, { queryKey: ["rooms", "threads", 5, "messages", 7], queryFn });
    const unsubscribe = observer.subscribe(() => {});
    await vi.waitFor(() => expect(queryFn).toHaveBeenCalledTimes(1));
    const invalidator = createLiveInvalidator(queryClient, clock);
    invalidator.handle(event("message.edited", 5, 7, 1));
    clock.advanceBy(900);
    expect(queryFn).toHaveBeenCalledTimes(1);
    release("first");
    await vi.waitFor(() => expect(queryClient.isFetching()).toBe(0));
    clock.advanceBy(300);
    await vi.waitFor(() => expect(queryFn).toHaveBeenCalledTimes(2));
    unsubscribe();
    invalidator.dispose();
  });
});
