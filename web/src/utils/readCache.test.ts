import { afterEach, describe, expect, test } from "vitest";
import { QueryClient } from "@tanstack/react-query";
import { markRoomReadInCaches, markRoomSeenInCaches, markThreadReadInCaches } from "./readCache";
import { notificationKeys } from "./notificationKeys";
import { roomKeys } from "./roomKeys";
import { ChannelStub } from "../test/broadcast";
import { openSeenSync } from "./seenSync";

const page = (items: unknown[]) => ({ items, page: 1, pageSize: 50, total: items.length });

describe("markRoomSeenInCaches", () => {
  test("clears the room's flag in the rail and the rooms list and leaves other rooms and other shapes alone", () => {
    const queryClient = new QueryClient();
    queryClient.setQueryData([...roomKeys.rail, "active"], page([{ id: 1, unread: true }, { id: 2, unread: true }]));
    queryClient.setQueryData([...roomKeys.list, 1], page([{ id: 1, unread: true }]));
    queryClient.setQueryData([...roomKeys.rail, "odd"], { not: "a page" });
    markRoomSeenInCaches(queryClient, 1);
    expect(queryClient.getQueryData([...roomKeys.rail, "active"])).toMatchObject({ items: [{ id: 1, unread: false }, { id: 2, unread: true }] });
    expect(queryClient.getQueryData([...roomKeys.list, 1])).toMatchObject({ items: [{ id: 1, unread: false }] });
    expect(queryClient.getQueryData([...roomKeys.rail, "odd"])).toEqual({ not: "a page" });
  });
});

describe("markRoomReadInCaches", () => {
  test("reads every cached row and detail of the room as read, clears the flag and re-reads the lists, the rail and the bell", () => {
    const queryClient = new QueryClient();
    queryClient.setQueryData([...roomKeys.threadList(12), 1], page([{ number: 4, unreadCount: 3 }, { number: 5, unreadCount: 100 }]));
    queryClient.setQueryData([...roomKeys.threadList(13), 1], page([{ number: 4, unreadCount: 3 }]));
    queryClient.setQueryData(roomKeys.roomHashtags(12), page([{ tag: "db", threadCount: 2 }]));
    queryClient.setQueryData(roomKeys.threadDetail(12, 4), { number: 4, unreadCount: 3, firstUnreadMessageId: 70 });
    queryClient.setQueryData(roomKeys.threadDetail(13, 4), { number: 4, unreadCount: 3, firstUnreadMessageId: 70 });
    queryClient.setQueryData([...roomKeys.rail, "active"], page([{ id: 12, unread: true }, { id: 13, unread: true }]));
    queryClient.setQueryData(notificationKeys.unseen, page([]));
    markRoomReadInCaches(queryClient, 12);
    expect(queryClient.getQueryData([...roomKeys.threadList(12), 1])).toMatchObject({ items: [{ number: 4, unreadCount: 0 }, { number: 5, unreadCount: 0 }] });
    expect(queryClient.getQueryData([...roomKeys.threadList(13), 1])).toMatchObject({ items: [{ number: 4, unreadCount: 3 }] });
    expect(queryClient.getQueryData(roomKeys.roomHashtags(12))).toEqual(page([{ tag: "db", threadCount: 2 }]));
    expect(queryClient.getQueryData(roomKeys.threadDetail(12, 4))).toEqual({ number: 4, unreadCount: 0, firstUnreadMessageId: null });
    expect(queryClient.getQueryData(roomKeys.threadDetail(13, 4))).toMatchObject({ unreadCount: 3 });
    expect(queryClient.getQueryData([...roomKeys.rail, "active"])).toMatchObject({ items: [{ id: 12, unread: false }, { id: 13, unread: true }] });
    expect(queryClient.getQueryState([...roomKeys.threadList(12), 1])?.isInvalidated).toBe(true);
    expect(queryClient.getQueryState([...roomKeys.threadList(13), 1])?.isInvalidated).toBe(false);
    expect(queryClient.getQueryState([...roomKeys.rail, "active"])?.isInvalidated).toBe(true);
    expect(queryClient.getQueryState(notificationKeys.unseen)?.isInvalidated).toBe(true);
  });

  test("a detail that was never read is not invented", () => {
    const queryClient = new QueryClient();
    markRoomReadInCaches(queryClient, 12);
    expect(queryClient.getQueryData(roomKeys.threadDetail(12, 4))).toBeUndefined();
  });
});

describe("markThreadReadInCaches", () => {
  test("reads the thread as read in its list row and its detail, without touching other threads or the tag pages", () => {
    const queryClient = new QueryClient();
    queryClient.setQueryData([...roomKeys.threadList(12), 1], page([{ number: 4, unreadCount: 3 }, { number: 5, unreadCount: 2 }]));
    queryClient.setQueryData(roomKeys.roomHashtags(12), page([{ tag: "db", threadCount: 2 }]));
    queryClient.setQueryData(roomKeys.threadDetail(12, 4), { number: 4, unreadCount: 3, firstUnreadMessageId: 70 });
    queryClient.setQueryData([...roomKeys.rail, "active"], page([{ id: 12, unread: true }]));
    markThreadReadInCaches(queryClient, 12, 4, 30);
    expect(queryClient.getQueryData([...roomKeys.threadList(12), 1])).toMatchObject({ items: [{ number: 4, unreadCount: 0 }, { number: 5, unreadCount: 2 }] });
    expect(queryClient.getQueryData(roomKeys.roomHashtags(12))).toEqual(page([{ tag: "db", threadCount: 2 }]));
    expect(queryClient.getQueryData(roomKeys.threadDetail(12, 4))).toEqual({ number: 4, unreadCount: 0, firstUnreadMessageId: null });
    expect(queryClient.getQueryData([...roomKeys.rail, "active"])).toMatchObject({ items: [{ id: 12, unread: false }] });
  });

  test("a detail that was never read is not invented", () => {
    const queryClient = new QueryClient();
    markThreadReadInCaches(queryClient, 12, 4, 30);
    expect(queryClient.getQueryData(roomKeys.threadDetail(12, 4))).toBeUndefined();
  });

  test("the bell is re-read only when its unseen rows held a notice of this thread", () => {
    const queryClient = new QueryClient();
    queryClient.setQueryData(notificationKeys.unseen, page([{ id: 1, roomId: 12, threadNumber: 9 }]));
    markThreadReadInCaches(queryClient, 12, 4, 30);
    expect(queryClient.getQueryState(notificationKeys.unseen)?.isInvalidated).toBe(false);
    markThreadReadInCaches(queryClient, 12, 9, 30);
    expect(queryClient.getQueryState(notificationKeys.unseen)?.isInvalidated).toBe(true);
  });

  test("the rail's addressed query holding a notice of the thread re-reads the bell too", () => {
    const queryClient = new QueryClient();
    queryClient.setQueryData(notificationKeys.unseen, page([]));
    queryClient.setQueryData(notificationKeys.addressed, page([{ id: 1, roomId: 12, threadNumber: 9 }]));
    markThreadReadInCaches(queryClient, 12, 4, 30);
    expect(queryClient.getQueryState(notificationKeys.addressed)?.isInvalidated).toBe(false);
    markThreadReadInCaches(queryClient, 12, 9, 30);
    expect(queryClient.getQueryState(notificationKeys.addressed)?.isInvalidated).toBe(true);
    expect(queryClient.getQueryState(notificationKeys.unseen)?.isInvalidated).toBe(true);
  });

  test("a newer message already cached (a live re-read listed it while the write was in flight) keeps the row and re-reads instead of zeroing it", () => {
    const queryClient = new QueryClient();
    queryClient.setQueryData(roomKeys.messages(12, 4), { pages: [page([{ id: 31 }, { id: 30 }])], pageParams: [undefined] });
    queryClient.setQueryData([...roomKeys.threadList(12), 1], page([{ number: 4, unreadCount: 1 }]));
    queryClient.setQueryData(roomKeys.threadDetail(12, 4), { number: 4, unreadCount: 1, firstUnreadMessageId: 31 });
    queryClient.setQueryData([...roomKeys.rail, "active"], page([{ id: 12, unread: true }]));
    markThreadReadInCaches(queryClient, 12, 4, 30);
    expect(queryClient.getQueryData([...roomKeys.threadList(12), 1])).toMatchObject({ items: [{ number: 4, unreadCount: 1 }] });
    expect(queryClient.getQueryData(roomKeys.threadDetail(12, 4))).toMatchObject({ unreadCount: 1, firstUnreadMessageId: 31 });
    expect(queryClient.getQueryData([...roomKeys.rail, "active"])).toMatchObject({ items: [{ id: 12, unread: true }] });
    expect(queryClient.getQueryState([...roomKeys.threadList(12), 1])?.isInvalidated).toBe(true);
    expect(queryClient.getQueryState(roomKeys.threadDetail(12, 4))?.isInvalidated).toBe(true);
    expect(queryClient.getQueryState([...roomKeys.rail, "active"])?.isInvalidated).toBe(true);
  });

  test("a cached newest message that is the one sent zeroes the row as usual", () => {
    const queryClient = new QueryClient();
    queryClient.setQueryData(roomKeys.messages(12, 4), { pages: [page([{ id: 30 }])], pageParams: [undefined] });
    queryClient.setQueryData([...roomKeys.threadList(12), 1], page([{ number: 4, unreadCount: 2 }]));
    markThreadReadInCaches(queryClient, 12, 4, 30);
    expect(queryClient.getQueryData([...roomKeys.threadList(12), 1])).toMatchObject({ items: [{ number: 4, unreadCount: 0 }] });
  });
});

// The person's other tabs hear of a write that settled notices (1.2 D9); a marker that settled none is not worth a re-read over there.
describe("announcing seen notices to the other tabs", () => {
  afterEach(() => ChannelStub.reset());

  function withAnnouncer(run: (posted: () => unknown[]) => void) {
    const close = openSeenSync(7, () => undefined, (name) => new ChannelStub(name));
    try {
      run(() => ChannelStub.open[0].posted);
    } finally {
      close();
    }
  }

  test("Mark all as read announces", () => {
    withAnnouncer((posted) => {
      markRoomReadInCaches(new QueryClient(), 12);
      expect(posted()).toEqual([{ v: 1, userId: 7 }]);
    });
  });

  test("a read marker announces only when the bell's rows held a notice of the thread", () => {
    withAnnouncer((posted) => {
      const queryClient = new QueryClient();
      queryClient.setQueryData(notificationKeys.unseen, page([{ id: 1, roomId: 12, threadNumber: 9 }]));
      markThreadReadInCaches(queryClient, 12, 4, 30);
      expect(posted()).toEqual([]);
      markThreadReadInCaches(queryClient, 12, 9, 30);
      expect(posted()).toEqual([{ v: 1, userId: 7 }]);
    });
  });
});
