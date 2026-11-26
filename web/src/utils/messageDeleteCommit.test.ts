import { QueryClient, QueryObserver } from "@tanstack/react-query";
import { afterEach, describe, expect, test, vi } from "vitest";
import { FakeClock } from "../test/socket";
import { createLiveInvalidator } from "./liveKeys";
import { beginMessageDeleteCommit, clearMessageDeleteCommits } from "./messageDeleteCommit";
import { homeKey, notificationsKey, roomKeys } from "./roomKeys";

const target = { roomId: 5, threadNumber: 7, messageId: 99 };
const deleted = (messageId = 99) => ({ type: "event", kind: "message.deleted", roomId: 5, threadNumber: 7, messageId } as const);
const expected = [
  roomKeys.messages(5, 7),
  roomKeys.threadDetail(5, 7),
  roomKeys.threadList(5),
  roomKeys.pins(5),
  notificationsKey,
  homeKey,
];

describe("message delete commit", () => {
  const clients: QueryClient[] = [];

  afterEach(() => {
    clients.forEach((client) => {
      clearMessageDeleteCommits(client);
      client.clear();
    });
    clients.length = 0;
    vi.useRealTimers();
  });

  function setup() {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    clients.push(client);
    const clock = new FakeClock();
    const invalidator = createLiveInvalidator(client, clock);
    const invalidate = vi.spyOn(client, "invalidateQueries");
    return { client, clock, invalidator, invalidate };
  }

  test.each(["live first", "HTTP first"])("%s performs one authoritative refresh per deletion key", async (order) => {
    const t = setup();
    const commit = beginMessageDeleteCommit(t.client, target);
    if (order === "live first") {
      t.invalidator.handle(deleted());
      await commit.confirm();
    } else {
      await commit.confirm();
      t.invalidator.handle(deleted());
    }
    await vi.waitFor(() => expect(t.invalidate).toHaveBeenCalledTimes(expected.length));
    expect(t.invalidate.mock.calls.map(([filters]) => filters?.queryKey)).toEqual(expected);
    t.clock.advanceBy(5_000);
    expect(t.invalidate).toHaveBeenCalledTimes(expected.length);
    t.invalidator.dispose();
  });

  test("registration is not an optimistic delete and a failed response leaves the ordinary hint path available", async () => {
    const t = setup();
    const conversation = roomKeys.messages(5, 7);
    t.client.setQueryData(conversation, { pages: [{ items: [{ id: 99, body: "still here", deleted: false }] }] });
    const commit = beginMessageDeleteCommit(t.client, target);
    expect(t.client.getQueryData(conversation)).toMatchObject({ pages: [{ items: [{ body: "still here" }] }] });
    expect(await commit.reject()).toBe(false);
    expect(t.invalidate).not.toHaveBeenCalled();

    t.invalidator.handle(deleted());
    t.clock.advanceBy(300);
    expect(t.invalidate).toHaveBeenCalledWith({ queryKey: conversation });
    t.invalidator.dispose();
  });

  test("only an exact terminal hint commits the pending operation", async () => {
    const t = setup();
    const commit = beginMessageDeleteCommit(t.client, target);
    t.invalidator.handle(deleted(100));
    expect(await commit.reject()).toBe(false);
    t.clock.advanceBy(300);
    expect(t.invalidate).toHaveBeenCalledWith({ queryKey: roomKeys.messages(5, 7) });
    t.invalidator.dispose();
  });

  test("an HTTP success still refreshes after the live-correlation retention expires", async () => {
    vi.useFakeTimers();
    const t = setup();
    const commit = beginMessageDeleteCommit(t.client, target);
    vi.advanceTimersByTime(30_001);
    await commit.confirm();
    expect(t.invalidate.mock.calls.map(([filters]) => filters?.queryKey)).toEqual(expected);
    t.invalidator.dispose();
  });

  test("capacity eviction drops only live correlation, not a valid HTTP commit", async () => {
    const t = setup();
    const first = beginMessageDeleteCommit(t.client, { ...target, messageId: 1 });
    for (let messageId = 2; messageId <= 33; messageId++) {
      beginMessageDeleteCommit(t.client, { ...target, messageId });
    }
    await first.confirm();
    expect(t.invalidate.mock.calls.map(([filters]) => filters?.queryKey)).toEqual(expected);
    t.invalidator.dispose();
  });

  test("starting the same exact delete again replaces its live-correlation slot", async () => {
    const t = setup();
    const first = beginMessageDeleteCommit(t.client, target);
    const replacement = beginMessageDeleteCommit(t.client, target);
    t.invalidator.handle(deleted());
    expect(await replacement.reject()).toBe(true);
    expect(await first.reject()).toBe(false);
    t.invalidator.dispose();
  });

  test("a session boundary makes an old handle unable to refresh the new caller's cache", async () => {
    const t = setup();
    const commit = beginMessageDeleteCommit(t.client, target);
    clearMessageDeleteCommits(t.client);
    await commit.confirm();
    expect(t.invalidate).not.toHaveBeenCalled();
    expect(await commit.reject()).toBe(true);
    t.invalidator.dispose();
  });

  test("a session boundary stops a refresh already queued behind cancellation", async () => {
    const t = setup();
    let release!: () => void;
    const cancelled = new Promise<void>((resolve) => { release = resolve; });
    vi.spyOn(t.client, "cancelQueries").mockReturnValue(cancelled);
    const commit = beginMessageDeleteCommit(t.client, target);
    const confirming = commit.confirm();
    clearMessageDeleteCommits(t.client);
    release();
    await confirming;
    expect(t.invalidate).not.toHaveBeenCalled();
    t.invalidator.dispose();
  });

  test("a proven delete clears revisions and redacts cached quotes without dropping loaded timeline pages", async () => {
    const t = setup();
    const destination = roomKeys.messages(5, 7);
    const revisions = [...roomKeys.messageRevisions(5, 7, 99), 1];
    t.client.setQueryData(destination, {
      pages: [
        { items: [
          { id: 95, quote: { ...target, roomId: 6, body: "other room", deleted: false } },
          { id: 96, quote: { ...target, threadNumber: 8, body: "other thread", deleted: false } },
          { id: 97, quote: { ...target, messageId: 100, body: "other message", deleted: false } },
          { id: 98, quote: { ...target, authorName: "Ann", body: "private excerpt", deleted: false } },
        ] },
        { items: [{ id: 99, body: "source" }] },
      ],
      pageParams: [null, 50],
    });
    t.client.setQueryData(revisions, { items: [{ body: "previous secret" }] });
    beginMessageDeleteCommit(t.client, target);
    t.invalidator.handle(deleted());

    expect(t.client.getQueryData(revisions)).toBeUndefined();
    expect(t.client.getQueryData(destination)).toMatchObject({
      pages: [
        { items: [
          { quote: { body: "other room", deleted: false } },
          { quote: { body: "other thread", deleted: false } },
          { quote: { body: "other message", deleted: false } },
          { quote: { body: null, deleted: true } },
        ] },
        { items: [{ id: 99, body: "source" }] },
      ],
      pageParams: [null, 50],
    });
    t.invalidator.dispose();
  });

  test("a pending destination read cannot restore a stale quote after the delete commits", async () => {
    const t = setup();
    const destination = roomKeys.messageContext(12, 42, 200);
    let resolveOld!: (value: unknown) => void;
    let resolveFresh!: (value: unknown) => void;
    const queryFn = vi.fn()
      .mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve; }))
      .mockImplementationOnce(() => new Promise((resolve) => { resolveFresh = resolve; }));
    const observer = new QueryObserver(t.client, { queryKey: destination, queryFn });
    const unsubscribe = observer.subscribe(() => undefined);
    await vi.waitFor(() => expect(queryFn).toHaveBeenCalledOnce());

    beginMessageDeleteCommit(t.client, target);
    t.invalidator.handle(deleted());
    await vi.waitFor(() => expect(queryFn).toHaveBeenCalledTimes(2));
    resolveOld({ message: { id: 200, quote: { ...target, body: "stale excerpt", deleted: false } } });
    resolveFresh({ message: { id: 200, quote: { ...target, body: null, deleted: true } } });
    await vi.waitFor(() => expect(t.client.getQueryData(destination)).toMatchObject({ message: { quote: { body: null, deleted: true } } }));
    expect(t.client.getQueryData(destination)).not.toMatchObject({ message: { quote: { body: "stale excerpt" } } });
    unsubscribe();
    t.invalidator.dispose();
  });

  test("a held source context read is cancelled and restarted exactly once", async () => {
    const t = setup();
    const context = roomKeys.messageContext(5, 7, 99);
    let resolveOld!: (value: string) => void;
    const queryFn = vi.fn()
      .mockImplementationOnce(() => new Promise<string>((resolve) => { resolveOld = resolve; }))
      .mockResolvedValueOnce("fresh tombstone context");
    const observer = new QueryObserver(t.client, { queryKey: context, queryFn });
    const unsubscribe = observer.subscribe(() => undefined);
    await vi.waitFor(() => expect(queryFn).toHaveBeenCalledOnce());

    beginMessageDeleteCommit(t.client, target);
    t.invalidator.handle(deleted());
    await vi.waitFor(() => expect(queryFn).toHaveBeenCalledTimes(2));
    resolveOld("stale body context");
    await vi.waitFor(() => expect(t.client.getQueryData(context)).toBe("fresh tombstone context"));
    expect(queryFn).toHaveBeenCalledTimes(2);
    unsubscribe();
    t.invalidator.dispose();
  });

  test("one foreign timeline prefix restarts its held base and context reads without overlapping invalidations", async () => {
    const t = setup();
    const timeline = roomKeys.messages(12, 42);
    const context = roomKeys.messageContext(12, 42, 200);
    let resolveTimeline!: (value: string) => void;
    let resolveContext!: (value: string) => void;
    const timelineRead = vi.fn()
      .mockImplementationOnce(() => new Promise<string>((resolve) => { resolveTimeline = resolve; }))
      .mockResolvedValueOnce("fresh timeline");
    const contextRead = vi.fn()
      .mockImplementationOnce(() => new Promise<string>((resolve) => { resolveContext = resolve; }))
      .mockResolvedValueOnce("fresh context");
    const timelineObserver = new QueryObserver(t.client, { queryKey: timeline, queryFn: timelineRead });
    const contextObserver = new QueryObserver(t.client, { queryKey: context, queryFn: contextRead });
    const unsubscribeTimeline = timelineObserver.subscribe(() => undefined);
    const unsubscribeContext = contextObserver.subscribe(() => undefined);
    await vi.waitFor(() => {
      expect(timelineRead).toHaveBeenCalledOnce();
      expect(contextRead).toHaveBeenCalledOnce();
    });

    beginMessageDeleteCommit(t.client, target);
    t.invalidator.handle(deleted());
    await vi.waitFor(() => {
      expect(timelineRead).toHaveBeenCalledTimes(2);
      expect(contextRead).toHaveBeenCalledTimes(2);
    });
    const refreshed = t.invalidate.mock.calls.map(([filters]) => filters?.queryKey);
    expect(refreshed.filter((key) => JSON.stringify(key) === JSON.stringify(timeline))).toHaveLength(1);
    expect(refreshed).not.toContainEqual(context);
    resolveTimeline("stale timeline");
    resolveContext("stale context");
    unsubscribeTimeline();
    unsubscribeContext();
    t.invalidator.dispose();
  });

  test("a failed authoritative read releases the exact hint to the ordinary retry path", async () => {
    const t = setup();
    const conversation = roomKeys.messages(5, 7);
    const queryFn = vi.fn()
      .mockResolvedValueOnce("initial")
      .mockRejectedValueOnce(new Error("first authoritative read failed"))
      .mockResolvedValueOnce("ordinary retry");
    const observer = new QueryObserver(t.client, { queryKey: conversation, queryFn });
    const unsubscribe = observer.subscribe(() => undefined);
    await vi.waitFor(() => expect(t.client.getQueryData(conversation)).toBe("initial"));

    beginMessageDeleteCommit(t.client, target);
    t.invalidator.handle(deleted());
    await vi.waitFor(() => expect(queryFn).toHaveBeenCalledTimes(2));
    t.clock.advanceBy(300);
    await vi.waitFor(() => expect(queryFn).toHaveBeenCalledTimes(3));
    await vi.waitFor(() => expect(t.client.getQueryData(conversation)).toBe("ordinary retry"));
    unsubscribe();
    t.invalidator.dispose();
  });
});
