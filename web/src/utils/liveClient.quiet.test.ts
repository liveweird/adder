// The hidden tab (1.2 D9, `live.md` "The hidden tab"): quiet after 5 minutes hidden (no room subscribed, the socket kept for personal frames, a
// ping once a minute), closed after 8 hours, and a `resync` backfill when it is shown again. The main client suite is `liveClient.test.ts`.
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { FakeClock, makeLive, SocketStub } from "../test/socket";
import { LiveClient, type LiveStatus } from "./liveClient";
import type { LiveServerFrame } from "./liveProtocol";

const TICKET = "t".repeat(43);
const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;

async function settle() {
  for (let i = 0; i < 6; i++) await Promise.resolve();
}

function setup() {
  const clock = new FakeClock();
  const frames: LiveServerFrame[] = [];
  const statuses: LiveStatus[] = [];
  const quiets: boolean[] = [];
  const mintTicket = vi.fn(() => Promise.resolve({ ticket: TICKET }));
  const client = new LiveClient({
    url: "ws://localhost/api/v1/live",
    socketFactory: (url) => new SocketStub(url),
    clock,
    mintTicket,
    onFrame: (frame) => frames.push(frame),
    onStatus: (status) => statuses.push(status),
    onQuiet: (quiet) => quiets.push(quiet),
  });
  return { client, clock, frames, statuses, quiets, mintTicket };
}

const newest = () => SocketStub.instances.at(-1)!;
const sentTypes = (type: string) => newest().frames().filter((frame) => frame.type === type);

/** A live client following rooms 12 and 40, the subscribe flushed. */
async function liveWithRooms(t: ReturnType<typeof setup>) {
  t.client.setRooms([12, 40]);
  t.client.start();
  await settle();
  makeLive(newest());
  expect(sentTypes("subscribe")).toEqual([{ type: "subscribe", roomIds: [12, 40] }]);
}

/** Time passes on a quiet socket whose server answers every minute ping. */
function advanceQuiet(t: ReturnType<typeof setup>, ms: number) {
  for (let left = ms; left > 0; left -= MINUTE) {
    t.clock.advanceBy(Math.min(MINUTE, left));
    newest().receive({ type: "pong" });
  }
}

beforeEach(() => SocketStub.reset());
afterEach(() => SocketStub.reset());

describe("going quiet", () => {
  test("at exactly five minutes hidden the rooms are dropped, the socket stays open and the status is quiet", async () => {
    const t = setup();
    await liveWithRooms(t);
    const socket = newest();
    t.client.setVisible(false);
    t.clock.advanceBy(5 * MINUTE - 1);
    expect(t.client.getStatus()).toBe("live");
    expect(sentTypes("unsubscribe")).toEqual([]);
    t.clock.advanceBy(1);
    expect(t.client.getStatus()).toBe("quiet");
    expect(sentTypes("unsubscribe")).toEqual([{ type: "unsubscribe", roomIds: [12, 40] }]);
    expect(socket.close).not.toHaveBeenCalled();
    // The deliberate quiet is never "down": the reconnecting badge has nothing to flash for.
    expect(t.statuses).not.toContain("down");
  });

  test("a room asked for while quiet is not subscribed", async () => {
    const t = setup();
    await liveWithRooms(t);
    t.client.setVisible(false);
    t.clock.advanceBy(5 * MINUTE);
    t.client.setRooms([12, 40, 77]);
    t.clock.advanceBy(SECOND);
    expect(sentTypes("subscribe")).toEqual([{ type: "subscribe", roomIds: [12, 40] }]);
  });

  test("a personal frame still reaches the consumer while quiet", async () => {
    const t = setup();
    await liveWithRooms(t);
    t.client.setVisible(false);
    t.clock.advanceBy(5 * MINUTE);
    const hint: LiveServerFrame = { type: "event", kind: "notification.created", roomId: 12, threadNumber: 3 };
    newest().receive(hint);
    expect(t.frames).toContainEqual(hint);
  });

  test("shown again before five minutes nothing is unsubscribed", async () => {
    const t = setup();
    await liveWithRooms(t);
    t.client.setVisible(false);
    t.clock.advanceBy(5 * MINUTE - 1);
    t.client.setVisible(true);
    for (let i = 0; i < 60; i++) {
      t.clock.advanceBy(10 * SECOND);
      newest().receive({ type: "pong" });
    }
    expect(sentTypes("unsubscribe")).toEqual([]);
    expect(t.client.getStatus()).toBe("live");
  });
});

describe("the quiet ping", () => {
  test("a ping goes once a minute (not every 25 seconds) and an answered one keeps the socket", async () => {
    const t = setup();
    await liveWithRooms(t);
    t.client.setVisible(false);
    t.clock.advanceBy(5 * MINUTE);
    const before = sentTypes("ping").length;
    t.clock.advanceBy(59 * SECOND);
    expect(sentTypes("ping")).toHaveLength(before);
    t.clock.advanceBy(SECOND);
    expect(sentTypes("ping")).toHaveLength(before + 1);
    newest().receive({ type: "pong" });
    advanceQuiet(t, 30 * MINUTE);
    expect(newest().close).not.toHaveBeenCalled();
    expect(t.client.getStatus()).toBe("quiet");
  });

  test("no frame since the last ping is a failure at the NEXT tick, and the tab reconnects (quiet again)", async () => {
    const t = setup();
    await liveWithRooms(t);
    t.client.setVisible(false);
    t.clock.advanceBy(5 * MINUTE);
    const socket = newest();
    t.clock.advanceBy(MINUTE); // the ping goes out, nothing answers
    expect(socket.close).not.toHaveBeenCalled();
    t.clock.advanceBy(MINUTE - 1);
    expect(socket.close).not.toHaveBeenCalled();
    t.clock.advanceBy(1);
    expect(socket.close).toHaveBeenCalledWith(4000);
    expect(t.client.getStatus()).toBe("down");
    t.clock.advanceBy(2 * SECOND);
    await settle();
    makeLive(newest());
    expect(t.client.getStatus()).toBe("quiet");
    expect(sentTypes("subscribe")).toEqual([]);
  });

  test("any frame counts as an answer, not only a pong", async () => {
    const t = setup();
    await liveWithRooms(t);
    t.client.setVisible(false);
    t.clock.advanceBy(5 * MINUTE + MINUTE);
    newest().receive({ type: "event", kind: "notification.created", roomId: 12, threadNumber: 3 });
    t.clock.advanceBy(MINUTE);
    expect(newest().close).not.toHaveBeenCalled();
  });
});

describe("while quiet, the session lasts", () => {
  test("a 4001 re-mints and the new socket comes up quiet, with no rooms", async () => {
    const t = setup();
    await liveWithRooms(t);
    t.client.setVisible(false);
    t.clock.advanceBy(5 * MINUTE);
    newest().closeWith(4001);
    t.clock.advanceBy(SECOND);
    await settle();
    expect(t.mintTicket).toHaveBeenCalledTimes(2);
    makeLive(newest());
    expect(t.client.getStatus()).toBe("quiet");
    expect(sentTypes("subscribe")).toEqual([]);
  });

  test("a socket that dropped while the tab went quiet comes up quiet", async () => {
    const t = setup();
    await liveWithRooms(t);
    newest().closeWith(1006);
    t.client.setVisible(false);
    t.clock.advanceBy(5 * MINUTE);
    expect(t.client.getStatus()).toBe("down");
    await settle();
    makeLive(newest());
    expect(t.client.getStatus()).toBe("quiet");
    expect(sentTypes("subscribe")).toEqual([]);
  });

  test("at eight hours hidden the socket closes (1000) and the status is idle", async () => {
    const t = setup();
    await liveWithRooms(t);
    t.client.setVisible(false);
    const socket = newest();
    advanceQuiet(t, 8 * HOUR - 1);
    expect(t.client.getStatus()).toBe("quiet");
    t.clock.advanceBy(1);
    expect(socket.close).toHaveBeenCalledWith(1000);
    expect(t.client.getStatus()).toBe("idle");
    expect(t.clock.pendingDelays()).toEqual([]);
  });

  test("stop() cancels both hidden-tab timers", async () => {
    const t = setup();
    await liveWithRooms(t);
    t.client.setVisible(false);
    t.client.stop();
    expect(t.clock.pendingDelays()).toEqual([]);
  });
});

describe("shown again", () => {
  test("a quiet tab subscribes its rooms back, goes live and asks for the backfill (resync); the ping is 25 s again", async () => {
    const t = setup();
    await liveWithRooms(t);
    t.client.setVisible(false);
    advanceQuiet(t, 20 * MINUTE);
    expect(t.client.getStatus()).toBe("quiet");
    t.frames.length = 0;
    t.client.setVisible(true);
    expect(t.client.getStatus()).toBe("live");
    expect(sentTypes("subscribe")).toEqual([
      { type: "subscribe", roomIds: [12, 40] },
      { type: "subscribe", roomIds: [12, 40] },
    ]);
    expect(t.frames).toEqual([{ type: "resync" }]);
    const pings = sentTypes("ping").length;
    t.clock.advanceBy(25 * SECOND);
    expect(sentTypes("ping")).toHaveLength(pings + 1);
  });

  test("the status is live BEFORE the backfill is handed to the consumer", async () => {
    const t = setup();
    await liveWithRooms(t);
    t.client.setVisible(false);
    t.clock.advanceBy(5 * MINUTE);
    const order: string[] = [];
    const client = t.client;
    // The consumer sees the status of the moment its frame arrives.
    const original = t.frames.push.bind(t.frames);
    t.frames.push = (...items: LiveServerFrame[]) => {
      order.push(`${items[0].type}:${client.getStatus()}`);
      return original(...items);
    };
    client.setVisible(true);
    expect(order).toEqual(["resync:live"]);
  });

  test("shown while the quiet socket is reconnecting, the retry is brought forward and `ready` is the backfill", async () => {
    const t = setup();
    await liveWithRooms(t);
    t.client.setVisible(false);
    t.clock.advanceBy(5 * MINUTE);
    newest().closeWith(1006);
    t.clock.randomValue = 0.25;
    t.client.setVisible(true);
    expect(t.clock.pendingDelays()).toEqual([250]);
    t.clock.advanceBy(250);
    await settle();
    makeLive(newest());
    expect(t.client.getStatus()).toBe("live");
    expect(sentTypes("subscribe")).toEqual([{ type: "subscribe", roomIds: [12, 40] }]);
  });
});

describe("the tab's quiet flag follows the tab, not the socket", () => {
  test("it is told at five minutes hidden, stays through a drop and a reconnect, and ends when the tab is shown, BEFORE the backfill", async () => {
    const t = setup();
    await liveWithRooms(t);
    t.client.setVisible(false);
    t.clock.advanceBy(5 * MINUTE);
    expect(t.quiets).toEqual([true]);
    newest().closeWith(1006);
    expect(t.client.getStatus()).toBe("down");
    expect(t.quiets, "a dropped socket does not make the tab less quiet").toEqual([true]);
    t.clock.advanceBy(2 * SECOND);
    await settle();
    makeLive(newest());
    expect(t.quiets).toEqual([true]);
    t.client.setVisible(true);
    expect(t.quiets).toEqual([true, false]);
  });

  test("a tab shown while it is still down ends its quiet at once", async () => {
    const t = setup();
    await liveWithRooms(t);
    t.client.setVisible(false);
    t.clock.advanceBy(5 * MINUTE);
    newest().closeWith(1006);
    t.client.setVisible(true);
    expect(t.quiets).toEqual([true, false]);
  });

  test("a tab shown after the 8-hour close ends its quiet too", async () => {
    const t = setup();
    await liveWithRooms(t);
    t.client.setVisible(false);
    advanceQuiet(t, 8 * HOUR);
    expect(t.client.getStatus()).toBe("idle");
    expect(t.quiets).toEqual([true]);
    t.client.setVisible(true);
    expect(t.quiets).toEqual([true, false]);
  });
});

describe("eight hours is wall time", () => {
  test("a 4001 every 15 minutes re-mints and keeps the session until 8 hours hidden, then no mint goes out", async () => {
    const t = setup();
    await liveWithRooms(t);
    t.client.setVisible(false);
    let mints = 1;
    for (let at = 15; at < 8 * 60; at += 15) {
      advanceQuiet(t, 15 * MINUTE);
      newest().closeWith(4001);
      t.clock.advanceBy(SECOND);
      await settle();
      makeLive(newest());
      mints += 1;
      expect(t.mintTicket).toHaveBeenCalledTimes(mints);
    }
    expect(t.client.getStatus()).toBe("quiet");
    advanceQuiet(t, 15 * MINUTE - 20 * SECOND);
    newest().closeWith(4001);
    t.clock.advanceBy(2 * SECOND);
    await settle();
    expect(t.client.getStatus(), "8 hours hidden: closed, not re-minted").toBe("idle");
    expect(t.mintTicket).toHaveBeenCalledTimes(mints);
    t.clock.advanceBy(HOUR);
    expect(t.mintTicket).toHaveBeenCalledTimes(mints);
  });

  test("a clock that jumped past 8 hours while the timers slept: the next quiet ping tick closes the socket instead of pinging", async () => {
    const t = setup();
    await liveWithRooms(t);
    t.client.setVisible(false);
    t.clock.advanceBy(5 * MINUTE);
    const socket = newest();
    t.clock.skipWallTime(8 * HOUR);
    expect(t.client.getStatus(), "nothing fired during the sleep").toBe("quiet");
    t.clock.advanceBy(MINUTE);
    expect(socket.close).toHaveBeenCalledWith(1000);
    expect(t.client.getStatus()).toBe("idle");
    expect(t.clock.pendingDelays()).toEqual([]);
  });

  test("a clock that jumped past 8 hours while a 4001 re-mint was waiting: the mint never goes out", async () => {
    const t = setup();
    await liveWithRooms(t);
    t.client.setVisible(false);
    t.clock.advanceBy(5 * MINUTE);
    newest().closeWith(4001);
    t.clock.skipWallTime(8 * HOUR);
    t.clock.advanceBy(SECOND);
    await settle();
    expect(t.mintTicket).toHaveBeenCalledTimes(1);
    expect(t.client.getStatus()).toBe("idle");
  });

  test("a visible tab never counts: hiding resets the 8 hours", async () => {
    const t = setup();
    await liveWithRooms(t);
    t.client.setVisible(false);
    t.clock.skipWallTime(7 * HOUR);
    t.client.setVisible(true);
    t.client.setVisible(false);
    t.clock.skipWallTime(2 * HOUR);
    newest().closeWith(4001);
    t.clock.advanceBy(SECOND);
    await settle();
    expect(t.mintTicket).toHaveBeenCalledTimes(2);
  });
});

describe("a 4003 while quiet", () => {
  test("re-mints like a 4001 and the new socket comes up quiet, with no rooms", async () => {
    const t = setup();
    await liveWithRooms(t);
    t.client.setVisible(false);
    t.clock.advanceBy(5 * MINUTE);
    newest().closeWith(4003);
    t.clock.advanceBy(SECOND);
    await settle();
    expect(t.mintTicket).toHaveBeenCalledTimes(2);
    makeLive(newest());
    expect(t.client.getStatus()).toBe("quiet");
    expect(sentTypes("subscribe")).toEqual([]);
  });
});
