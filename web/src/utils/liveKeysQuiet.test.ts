import { afterEach, describe, expect, test, vi } from "vitest";
import { QueryClient } from "@tanstack/react-query";
import { FakeClock } from "../test/socket";
import { createLiveInvalidator } from "./liveKeys";
import type { LiveServerFrame } from "./liveProtocol";
import { notificationsKey } from "./roomKeys";

const event = (kind: string, roomId: number, threadNumber?: number): LiveServerFrame => ({
  type: "event",
  kind,
  roomId,
  ...(threadNumber === undefined ? {} : { threadNumber }),
});

// A QUIET tab (hidden for 5 minutes, 1.2 D9) re-reads only the bell's count, at most once a minute (`live.md` "The hidden tab").
describe("createLiveInvalidator: a quiet tab", () => {
  const clients: QueryClient[] = [];
  afterEach(() => {
    for (const client of clients.splice(0)) client.clear();
  });

  function setup() {
    const queryClient = new QueryClient();
    clients.push(queryClient);
    const clock = new FakeClock();
    const invalidate = vi.spyOn(queryClient, "invalidateQueries").mockResolvedValue();
    vi.spyOn(queryClient, "isFetching").mockReturnValue(0);
    const invalidator = createLiveInvalidator(queryClient, clock);
    const flushed = () => invalidate.mock.calls.map(([filters]) => JSON.stringify(filters?.queryKey));
    const bell = JSON.stringify(notificationsKey);
    return { clock, invalidator, flushed, bell };
  }

  test("a room hint, a resync's room keys and a ready's rail are dropped; only the bell's key is kept", () => {
    const t = setup();
    t.invalidator.setQuiet(true);
    t.invalidator.handle(event("message.posted", 12, 7));
    t.invalidator.handle({ type: "resync" });
    t.invalidator.handle({ type: "ready", protocol: 1, maxSubscriptions: 128 });
    t.clock.advanceBy(10_000);
    expect(t.flushed()).toEqual([t.bell]);
  });

  test("a notice is re-read at most once a minute, however many arrive", () => {
    const t = setup();
    t.invalidator.setQuiet(true);
    t.invalidator.handle(event("notification.created", 12, 7));
    t.clock.advanceBy(2_000);
    expect(t.flushed()).toEqual([t.bell]);
    for (let i = 0; i < 10; i++) {
      t.clock.advanceBy(5_000);
      t.invalidator.handle(event("notification.created", 12, 7));
    }
    expect(t.flushed()).toEqual([t.bell]);
    t.clock.advanceBy(60_000);
    expect(t.flushed()).toEqual([t.bell, t.bell]);
  });

  test("a request for any other key (a read marker's) is dropped while quiet, and the bell's is spaced", () => {
    const t = setup();
    t.invalidator.setQuiet(true);
    t.invalidator.request([["rooms", "rail"], notificationsKey]);
    t.clock.advanceBy(10_000);
    expect(t.flushed()).toEqual([t.bell]);
  });

  test("shown again, the ordinary rules return: a room hint is re-read within the settle time", () => {
    const t = setup();
    t.invalidator.setQuiet(true);
    t.invalidator.setQuiet(false);
    t.invalidator.handle(event("message.posted", 12, 7));
    t.clock.advanceBy(1_000);
    expect(t.flushed().some((key) => key.startsWith('["rooms"') && key.includes("12"))).toBe(true);
  });
});
