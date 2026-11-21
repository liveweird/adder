import { afterEach, describe, expect, test, vi } from "vitest";
import { QueryClient } from "@tanstack/react-query";
import { FakeClock } from "../test/socket";
import { createLiveInvalidator } from "./liveKeys";
import type { LiveServerFrame } from "./liveProtocol";
import { startLooking } from "./lookingAt";
import { notificationsKey } from "./roomKeys";

const event = (kind: string, roomId: number, threadNumber?: number, messageId?: number): LiveServerFrame => ({
  type: "event",
  kind,
  roomId,
  ...(threadNumber === undefined ? {} : { threadNumber }),
  ...(messageId === undefined ? {} : { messageId }),
});

// The bell and the thread the reader is looking at ("No badge flicker", `web-internals.md` "Unread"), and the spread of a notice's re-read.
describe("createLiveInvalidator: notices", () => {
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
    const flushed = () => invalidate.mock.calls.map(([filters]) => filters?.queryKey);
    return { clock, invalidator, flushed };
  }
  /** A registration whose newest loaded message is [newest]. */
  const looking = (newest: number | undefined) => {
    const acknowledge = vi.fn();
    const stop = startLooking({
      roomId: 5,
      threadNumber: 7,
      covers: (messageId) => messageId === undefined || (newest !== undefined && messageId > newest),
      acknowledge,
    });
    return { acknowledge, stop };
  };
  const bellReads = (t: ReturnType<typeof setup>) => t.flushed().filter((key) => JSON.stringify(key) === JSON.stringify(notificationsKey)).length;

  test("a notice about the thread the reader is looking at does not re-read the bell, it asks the pane to acknowledge it", () => {
    const t = setup();
    const { acknowledge, stop } = looking(50);
    // A message newer than the newest loaded one is the reply arriving; a thread-level notice (a status move, a hand-over) names no message.
    t.invalidator.handle(event("notification.created", 5, 7, 99));
    t.invalidator.handle(event("notification.created", 5, 7));
    t.clock.advanceBy(10_000);
    expect(acknowledge.mock.calls).toEqual([[99], [undefined]]);
    expect(bellReads(t)).toBe(0);
    stop();
  });

  test("a notice about a message the reader already has (an edit that mentions them there) raises the bell as ever", () => {
    const t = setup();
    t.clock.randomValue = 0;
    const { acknowledge, stop } = looking(50);
    t.invalidator.handle(event("notification.created", 5, 7, 12)); // older than the newest loaded message
    t.invalidator.handle(event("notification.created", 5, 7, 50)); // the newest one itself
    t.clock.advanceBy(10_000);
    expect(acknowledge).not.toHaveBeenCalled();
    expect(bellReads(t)).toBe(1);
    stop();
  });

  test("a notice about any other thread, or once the reader stops looking, re-reads the bell as ever", () => {
    const t = setup();
    t.clock.randomValue = 0;
    const { acknowledge, stop } = looking(50);
    t.invalidator.handle(event("notification.created", 5, 8, 99)); // another thread of the room
    t.invalidator.handle(event("notification.created", 6, 7, 99)); // the same number in another room
    t.clock.advanceBy(0);
    expect(bellReads(t)).toBe(1);
    expect(acknowledge).not.toHaveBeenCalled();
    stop(); // scrolled up, hidden or left
    t.clock.advanceBy(3_000);
    t.invalidator.handle(event("notification.created", 5, 7, 99));
    t.clock.advanceBy(0);
    expect(bellReads(t)).toBe(2);
    expect(acknowledge).not.toHaveBeenCalled();
  });

  test("a notice re-reads the bell after a random spread, like a reconnect's catch-up, so a wide event's wave is not in lock step", () => {
    const t = setup();
    t.clock.randomValue = 1; // the longest spread
    t.invalidator.handle(event("notification.created", 5, 7, 1));
    t.clock.advanceBy(1_999);
    expect(bellReads(t)).toBe(0);
    t.clock.advanceBy(1);
    expect(bellReads(t)).toBe(1);
    t.clock.randomValue = 0.5;
    t.invalidator.handle(event("notification.created", 5, 7, 2));
    t.clock.advanceBy(2_999); // the 3 s gap since the last flush holds
    expect(bellReads(t)).toBe(1);
    t.clock.advanceBy(1);
    expect(bellReads(t)).toBe(2);
  });
});
