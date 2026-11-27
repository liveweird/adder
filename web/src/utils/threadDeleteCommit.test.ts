import { onlineManager, QueryClient, QueryObserver } from "@tanstack/react-query";
import { afterEach, describe, expect, test, vi } from "vitest";
import { FakeClock } from "../test/socket";
import { createLiveInvalidator } from "./liveKeys";
import { homeKey, notificationsKey, roomKeys, searchAllKey } from "./roomKeys";
import { beginThreadDeleteCommit, clearThreadDeleteCommits } from "./threadDeleteCommit";

const target = { roomId: 5, threadNumber: 7 };
const deleted = (threadNumber = 7) => ({ type: "event", kind: "thread.deleted", roomId: 5, threadNumber } as const);
const aggregates = [
  roomKeys.threadList(5),
  notificationsKey,
  roomKeys.rail,
  roomKeys.workflow(5),
  roomKeys.workflowEdit(5),
  homeKey,
];

describe("thread delete commit", () => {
  const clients: QueryClient[] = [];

  afterEach(() => {
    clients.forEach((client) => {
      clearThreadDeleteCommits(client);
      client.clear();
    });
    clients.length = 0;
    onlineManager.setOnline(true);
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

  test.each(["live first", "HTTP first"])("%s performs one aggregate refresh for the deleting tab", async (order) => {
    const t = setup();
    const commit = beginThreadDeleteCommit(t.client, target);
    if (order === "live first") {
      t.invalidator.handle(deleted());
      await commit.confirm();
    } else {
      await commit.confirm();
      t.invalidator.handle(deleted());
    }

    await vi.waitFor(() => expect(t.invalidate).toHaveBeenCalledTimes(aggregates.length));
    expect(t.invalidate.mock.calls.map(([filters]) => filters?.queryKey)).toEqual(aggregates);
    t.clock.advanceBy(5_000);
    expect(t.invalidate).toHaveBeenCalledTimes(aggregates.length);
    t.invalidator.dispose();
  });

  test("an HTTP success still refreshes after its live-correlation slot expires", async () => {
    vi.useFakeTimers();
    const t = setup();
    const commit = beginThreadDeleteCommit(t.client, target);
    vi.advanceTimersByTime(30_001);

    await commit.confirm();

    expect(t.invalidate.mock.calls.map(([filters]) => filters?.queryKey)).toEqual(aggregates);
    t.invalidator.dispose();
  });

  test("a session boundary makes an old handle unable to refresh the next caller's cache", async () => {
    const t = setup();
    const commit = beginThreadDeleteCommit(t.client, target);
    clearThreadDeleteCommits(t.client);

    await commit.confirm();

    expect(t.invalidate).not.toHaveBeenCalled();
    expect(await commit.reject()).toBe(true);
    t.invalidator.dispose();
  });

  test("a session boundary stops aggregate refresh queued behind cancellation", async () => {
    const t = setup();
    let release!: () => void;
    const cancelled = new Promise<void>((resolve) => { release = resolve; });
    vi.spyOn(t.client, "cancelQueries").mockReturnValue(cancelled);
    const commit = beginThreadDeleteCommit(t.client, target);
    const confirming = commit.confirm();
    await vi.waitFor(() => expect(t.client.cancelQueries).toHaveBeenCalled());

    clearThreadDeleteCommits(t.client);
    release();
    await confirming;

    expect(t.invalidate).not.toHaveBeenCalled();
    t.invalidator.dispose();
  });

  test("a session boundary stops aggregate cancellation queued behind content reset", async () => {
    const t = setup();
    let release!: () => void;
    const resetting = new Promise<void>((resolve) => { release = resolve; });
    vi.spyOn(t.client, "resetQueries").mockReturnValue(resetting);
    const cancel = vi.spyOn(t.client, "cancelQueries");
    const commit = beginThreadDeleteCommit(t.client, target);
    const confirming = commit.confirm();
    await vi.waitFor(() => expect(t.client.resetQueries).toHaveBeenCalled());

    clearThreadDeleteCommits(t.client);
    release();
    await confirming;

    expect(cancel).not.toHaveBeenCalled();
    expect(t.invalidate).not.toHaveBeenCalled();
    t.invalidator.dispose();
  });

  test("an unrelated deletion keeps the pending operation uncommitted", async () => {
    const t = setup();
    const commit = beginThreadDeleteCommit(t.client, target);
    t.invalidator.handle(deleted(8));
    expect(await commit.reject()).toBe(false);
    t.clock.advanceBy(3_000);
    expect(t.invalidate).toHaveBeenCalledWith({ queryKey: roomKeys.threadList(5) });
    t.invalidator.dispose();
  });

  test("a proven deletion clears every source history and refreshes a cached cross-room quote once", async () => {
    const t = setup();
    const source = roomKeys.messages(5, 8);
    const sourcePins = roomKeys.pins(5);
    const destination = roomKeys.messages(12, 42);
    t.client.setQueryData(source, { pages: [{ items: [{ body: "merged source secret" }] }] });
    t.client.setQueryData(sourcePins, { items: [{ message: { body: "pinned secret" } }] });
    t.client.setQueryData(destination, {
      pages: [{ items: [{ quote: { roomId: 5, threadNumber: 8, messageId: 91, body: "quoted secret" } }] }],
    });
    beginThreadDeleteCommit(t.client, target);
    t.invalidator.handle(deleted());

    await vi.waitFor(() => expect(t.client.getQueryData(source)).toBeUndefined());
    expect(t.client.getQueryData(sourcePins)).toBeUndefined();
    expect(t.client.getQueryData(destination)).toBeUndefined();
    expect(t.invalidate.mock.calls.filter(([filters]) => filters?.queryKey === destination)).toHaveLength(0);
    t.invalidator.dispose();
  });

  test("an active source-room pin list is cancelled and refreshed only once", async () => {
    const t = setup();
    const sourcePins = roomKeys.pins(5);
    const queryFn = vi.fn()
      .mockResolvedValueOnce({ items: [{ message: { body: "pinned secret" } }] })
      .mockResolvedValueOnce({ items: [] });
    const observer = new QueryObserver(t.client, { queryKey: sourcePins, queryFn });
    const unsubscribe = observer.subscribe(() => undefined);
    await vi.waitFor(() => expect(queryFn).toHaveBeenCalledOnce());

    const commit = beginThreadDeleteCommit(t.client, target);
    t.invalidator.handle(deleted());
    await commit.confirm();

    await vi.waitFor(() => expect(t.client.getQueryData(sourcePins)).toEqual({ items: [] }));
    t.clock.advanceBy(5_000);
    expect(queryFn).toHaveBeenCalledTimes(2);
    unsubscribe();
    t.invalidator.dispose();
  });

  test("a deletion clears global and source-room searches without refreshing an unrelated room's search", async () => {
    const t = setup();
    const cases = [
      { key: [...searchAllKey, "incident"], affected: true },
      { key: [...roomKeys.search(5), "incident"], affected: true },
      { key: [...roomKeys.search(12), "incident"], affected: false },
    ].map(({ key, affected }) => {
      const queryFn = vi.fn().mockResolvedValueOnce(["Original hit"]).mockResolvedValueOnce([]);
      const observer = new QueryObserver(t.client, { queryKey: key, queryFn });
      const unsubscribe = observer.subscribe(() => undefined);
      return { key, affected, queryFn, unsubscribe };
    });
    await vi.waitFor(() => cases.forEach(({ key }) => expect(t.client.getQueryData(key)).toEqual(["Original hit"])));

    const commit = beginThreadDeleteCommit(t.client, target);
    t.invalidator.handle(deleted());
    cases.forEach(({ key, affected }) => expect(t.client.getQueryData(key)).toEqual(affected ? undefined : ["Original hit"]));
    await commit.confirm();
    t.clock.advanceBy(5_000);

    cases.forEach(({ key, affected, queryFn, unsubscribe }) => {
      expect(t.client.getQueryData(key)).toEqual(affected ? [] : ["Original hit"]);
      expect(queryFn).toHaveBeenCalledTimes(affected ? 2 : 1);
      unsubscribe();
    });
    t.invalidator.dispose();
  });

  test("a pending first thread-list read is cancelled before one post-delete read", async () => {
    const t = setup();
    const list = roomKeys.threadList(5);
    let resolveOld!: (value: unknown) => void;
    const queryFn = vi.fn()
      .mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve; }))
      .mockResolvedValueOnce({ items: [] });
    const observer = new QueryObserver(t.client, { queryKey: list, queryFn });
    const unsubscribe = observer.subscribe(() => undefined);
    await vi.waitFor(() => expect(queryFn).toHaveBeenCalledOnce());

    const commit = beginThreadDeleteCommit(t.client, target);
    t.invalidator.handle(deleted());

    await vi.waitFor(() => expect(queryFn).toHaveBeenCalledTimes(2));
    resolveOld({ items: [{ number: 7, title: "deleted" }] });
    await commit.confirm();
    await vi.waitFor(() => expect(t.client.getQueryData(list)).toEqual({ items: [] }));
    expect(queryFn).toHaveBeenCalledTimes(2);
    unsubscribe();
    t.invalidator.dispose();
  });

  test("a pending foreign quote read is cancelled and restarted once", async () => {
    const t = setup();
    const destination = roomKeys.messageContext(12, 42, 200);
    let resolveOld!: (value: unknown) => void;
    const queryFn = vi.fn()
      .mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve; }))
      .mockResolvedValueOnce({ message: { quote: { roomId: 5, threadNumber: 8, body: null, deleted: true } } });
    const observer = new QueryObserver(t.client, { queryKey: destination, queryFn });
    const unsubscribe = observer.subscribe(() => undefined);
    await vi.waitFor(() => expect(queryFn).toHaveBeenCalledOnce());

    beginThreadDeleteCommit(t.client, target);
    t.invalidator.handle(deleted());
    await vi.waitFor(() => expect(queryFn).toHaveBeenCalledTimes(2));
    resolveOld({ message: { quote: { roomId: 5, threadNumber: 8, body: "stale" } } });
    await vi.waitFor(() => expect(t.client.getQueryData(destination)).toMatchObject({ message: { quote: { deleted: true } } }));
    expect(queryFn).toHaveBeenCalledTimes(2);
    unsubscribe();
    t.invalidator.dispose();
  });

  test("a failed foreign refresh releases the event to the ordinary live retry", async () => {
    const t = setup();
    const destination = roomKeys.messages(12, 42);
    const queryFn = vi.fn()
      .mockResolvedValueOnce({ quote: { roomId: 5, threadNumber: 8, messageId: 91, body: "old" } })
      .mockRejectedValueOnce(new Error("first refresh failed"))
      .mockResolvedValueOnce({ quote: { roomId: 5, threadNumber: 8, messageId: 91, body: null, deleted: true } });
    const observer = new QueryObserver(t.client, { queryKey: destination, queryFn });
    const unsubscribe = observer.subscribe(() => undefined);
    await vi.waitFor(() => expect(queryFn).toHaveBeenCalledOnce());

    const commit = beginThreadDeleteCommit(t.client, target);
    t.invalidator.handle(deleted());
    await vi.waitFor(() => expect(queryFn).toHaveBeenCalledTimes(2));
    expect(await commit.reject()).toBe(true);
    await vi.waitFor(() => expect(queryFn).toHaveBeenCalledTimes(3));
    unsubscribe();
    t.invalidator.dispose();
  });

  test("an unexpected reset failure releases the event to ordinary live handling", async () => {
    const t = setup();
    vi.spyOn(t.client, "resetQueries").mockRejectedValueOnce(new Error("reset failed"));
    const commit = beginThreadDeleteCommit(t.client, target);

    t.invalidator.handle(deleted());
    expect(await commit.reject()).toBe(true);
    t.clock.advanceBy(300);

    await vi.waitFor(() => expect(t.invalidate).toHaveBeenCalledWith({ queryKey: roomKeys.threadList(5) }));
    t.invalidator.dispose();
  });

  test("a paused foreign refresh resumes as the same authoritative read after reconnecting", async () => {
    const t = setup();
    t.client.mount();
    onlineManager.setOnline(false);
    const destination = roomKeys.messages(12, 42);
    t.client.setQueryData(destination, {
      quote: { roomId: 5, threadNumber: 8, messageId: 91, body: "old" },
    });
    const queryFn = vi.fn().mockResolvedValue({
      quote: { roomId: 5, threadNumber: 8, messageId: 91, body: null, deleted: true },
    });
    const observer = new QueryObserver(t.client, { queryKey: destination, queryFn });
    const unsubscribe = observer.subscribe(() => undefined);
    await vi.waitFor(() => expect(t.client.getQueryState(destination)?.fetchStatus).toBe("paused"));

    const commit = beginThreadDeleteCommit(t.client, target);
    t.invalidator.handle(deleted());
    expect(queryFn).not.toHaveBeenCalled();
    onlineManager.setOnline(true);
    await commit.confirm();

    await vi.waitFor(() => expect(t.client.getQueryData(destination)).toMatchObject({ quote: { deleted: true } }));
    expect(queryFn).toHaveBeenCalledOnce();
    unsubscribe();
    t.client.unmount();
    t.invalidator.dispose();
  });
});
