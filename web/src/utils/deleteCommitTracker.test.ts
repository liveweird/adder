import { QueryClient } from "@tanstack/react-query";
import { afterEach, describe, expect, test, vi } from "vitest";
import type { LiveServerFrame } from "./liveProtocol";
import { createDeleteCommitTracker } from "./deleteCommitTracker";

type Target = { id: number };

const deleted = (id: number): LiveServerFrame => ({
  type: "event",
  kind: "thread.deleted",
  roomId: 1,
  threadNumber: id,
});

function setup(refresh = vi.fn<() => Promise<boolean>>().mockResolvedValue(true)) {
  const tracker = createDeleteCommitTracker<Target>({
    idOf: ({ id }) => String(id),
    targetFromFrame: (frame) => frame.type === "event" && frame.kind === "thread.deleted" && frame.threadNumber !== undefined
      ? { id: frame.threadNumber }
      : null,
    frameFromTarget: ({ id }) => deleted(id),
    refresh: (_queryClient, _target, _frame, sessionIsValid) => {
      sessionIsValid();
      return refresh();
    },
  });
  return { tracker, refresh, queryClient: new QueryClient() };
}

describe("delete commit tracker", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  test("an exact live proof and HTTP success share one reliable refresh", async () => {
    const { tracker, refresh, queryClient } = setup();
    const replay = vi.fn();
    const commit = tracker.begin(queryClient, { id: 7 });

    expect(tracker.commit(queryClient, { type: "pong" }, replay)).toBe(false);
    expect(tracker.commit(queryClient, deleted(8), replay)).toBe(false);
    expect(tracker.commit(queryClient, deleted(7), replay)).toBe(true);
    await commit.confirm();

    expect(refresh).toHaveBeenCalledOnce();
    expect(await commit.reject()).toBe(true);
    expect(replay).not.toHaveBeenCalled();
    tracker.clear(queryClient);
  });

  test("an unreliable proof is removed and replayed through ordinary live handling", async () => {
    const { tracker, queryClient } = setup(vi.fn<() => Promise<boolean>>().mockResolvedValue(false));
    const replay = vi.fn();
    tracker.begin(queryClient, { id: 7 });

    expect(tracker.commit(queryClient, deleted(7), replay)).toBe(true);
    await vi.waitFor(() => expect(replay).toHaveBeenCalledOnce());
    expect(tracker.commit(queryClient, deleted(7), replay)).toBe(false);
    tracker.clear(queryClient);
  });

  test("a same-id replacement owns live proof and leaves the replaced handle uncommitted", async () => {
    const { tracker, refresh, queryClient } = setup();
    const first = tracker.begin(queryClient, { id: 7 });
    const replacement = tracker.begin(queryClient, { id: 7 });

    expect(tracker.commit(queryClient, deleted(7), vi.fn())).toBe(true);

    expect(await replacement.reject()).toBe(true);
    expect(await first.reject()).toBe(false);
    expect(refresh).toHaveBeenCalledOnce();
    tracker.clear(queryClient);
  });

  test("expiry and capacity eviction drop only correlation, not a handle's HTTP commit", async () => {
    vi.useFakeTimers();
    const { tracker, refresh, queryClient } = setup();
    const expired = tracker.begin(queryClient, { id: 1 });
    vi.advanceTimersByTime(30_001);
    expect(tracker.commit(queryClient, deleted(1), vi.fn())).toBe(false);
    await expired.confirm();

    const evicted = tracker.begin(queryClient, { id: 2 });
    for (let id = 3; id <= 34; id++) tracker.begin(queryClient, { id });
    expect(tracker.commit(queryClient, deleted(2), vi.fn())).toBe(false);
    await evicted.confirm();

    expect(refresh).toHaveBeenCalledTimes(2);
    tracker.clear(queryClient);
  });

  test("rejection without proof and session cleanup never refresh", async () => {
    const { tracker, refresh, queryClient } = setup();
    const unproven = tracker.begin(queryClient, { id: 7 });
    expect(await unproven.reject()).toBe(false);

    const stale = tracker.begin(queryClient, { id: 8 });
    tracker.clear(queryClient);
    await stale.confirm();

    expect(await stale.reject()).toBe(true);
    expect(refresh).not.toHaveBeenCalled();
    tracker.clear(new QueryClient());
  });
});
