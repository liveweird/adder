import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { ApiError } from "../api/http";
import { LIVE_SOCKET_LIMIT_PROBLEM, LiveMintError } from "../api/live";
import { FakeClock, makeLive, SocketStub } from "../test/socket";
import { bindBrowserSignals, LiveClient, type LiveStatus } from "./liveClient";
import type { LiveServerFrame } from "./liveProtocol";

const TICKET = "t".repeat(43);
const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;

/** The mint and the socket factory resolve on microtasks: let them run (the fake clock never needs real time). */
async function settle() {
  for (let i = 0; i < 6; i++) await Promise.resolve();
}

function setup(mint: () => Promise<{ ticket: string }> = () => Promise.resolve({ ticket: TICKET })) {
  const clock = new FakeClock();
  const frames: LiveServerFrame[] = [];
  const statuses: LiveStatus[] = [];
  const mintTicket = vi.fn(mint);
  const client = new LiveClient({
    url: "ws://localhost/api/v1/live",
    socketFactory: (url) => new SocketStub(url),
    clock,
    mintTicket,
    onFrame: (frame) => frames.push(frame),
    onStatus: (status) => statuses.push(status),
  });
  return { client, clock, frames, statuses, mintTicket };
}

const sockets = () => SocketStub.instances;
const newest = () => SocketStub.instances.at(-1)!;

/** Starts the client and brings its first socket live. */
async function goLive(t: ReturnType<typeof setup>, maxSubscriptions = 128) {
  t.client.start();
  await settle();
  makeLive(newest(), maxSubscriptions);
}

/** Time passes on a live socket whose server answers every ping (otherwise the pong deadline would end it). */
function advanceAnswering(t: ReturnType<typeof setup>, ms: number) {
  for (let left = ms; left > 0; left -= 10 * SECOND) {
    t.clock.advanceBy(Math.min(10 * SECOND, left));
    newest().receive({ type: "pong" });
  }
}

/** Time passes on a QUIET socket whose server answers every minute ping (a silent one is a failure at the next tick). */
function advanceQuiet(t: ReturnType<typeof setup>, ms: number) {
  for (let left = ms; left > 0; left -= MINUTE) {
    t.clock.advanceBy(Math.min(MINUTE, left));
    newest().receive({ type: "pong" });
  }
}

beforeEach(() => SocketStub.reset());
afterEach(() => SocketStub.reset());

describe("connecting", () => {
  test("mints a ticket, opens the socket, sends the ticket as the FIRST frame, and is live on ready", async () => {
    const t = setup();
    t.client.start();
    expect(t.statuses).toEqual(["connecting"]);
    expect(t.mintTicket).toHaveBeenCalledTimes(1);
    await settle();
    expect(sockets()).toHaveLength(1);
    expect(newest().url).toBe("ws://localhost/api/v1/live");
    expect(newest().url).not.toContain(TICKET);
    expect(newest().sent).toEqual([]);
    newest().open();
    expect(newest().frames()).toEqual([{ type: "auth", ticket: TICKET }]);
    expect(t.client.getStatus()).toBe("connecting");
    newest().receive({ type: "ready", protocol: 1, maxSubscriptions: 128 });
    expect(t.statuses).toEqual(["connecting", "live"]);
    expect(t.frames).toEqual([{ type: "ready", protocol: 1, maxSubscriptions: 128 }]);
  });

  test("subscribes to the wanted rooms once live, in the order given, and never before", async () => {
    const t = setup();
    t.client.setRooms([12, 40, 12]);
    t.client.start();
    await settle();
    newest().open();
    expect(newest().frames()).toEqual([{ type: "auth", ticket: TICKET }]);
    newest().receive({ type: "ready", protocol: 1, maxSubscriptions: 128 });
    expect(newest().frames().slice(1)).toEqual([{ type: "subscribe", roomIds: [12, 40] }]);
  });

  test("start is idempotent and stop-then-start does nothing", async () => {
    const t = setup();
    t.client.start();
    t.client.start();
    await settle();
    expect(t.mintTicket).toHaveBeenCalledTimes(1);
    t.client.stop();
    t.client.start();
    expect(t.mintTicket).toHaveBeenCalledTimes(1);
  });

  test("a socket that never reaches ready within ten seconds is closed and retried", async () => {
    const t = setup();
    t.client.start();
    await settle();
    newest().open();
    t.clock.advanceBy(10 * SECOND - 1);
    expect(newest().close).not.toHaveBeenCalled();
    t.clock.advanceBy(1);
    expect(newest().close).toHaveBeenCalledWith(1000);
    expect(t.client.getStatus()).toBe("down");
    expect(t.clock.pendingDelays()).toEqual([1000]);
  });

  test("a socket that is still connecting when it is dropped is closed once it opens, not mid-handshake", async () => {
    const t = setup();
    t.client.start();
    await settle();
    const socket = newest();
    t.client.stop();
    expect(socket.close).not.toHaveBeenCalled();
    socket.open();
    expect(socket.close).toHaveBeenCalledWith(1000);
    expect(socket.sent).toEqual([]);
  });

  test("a consumer that throws on ready has still been subscribed", async () => {
    const t = setup();
    const client = new LiveClient({
      url: "ws://localhost/api/v1/live",
      socketFactory: (url) => new SocketStub(url),
      clock: t.clock,
      mintTicket: t.mintTicket,
      onFrame: () => {
        throw new Error("consumer failed");
      },
      onStatus: () => {},
    });
    client.setRooms([4]);
    client.start();
    await settle();
    newest().open();
    expect(() => newest().receive({ type: "ready", protocol: 1, maxSubscriptions: 128 })).toThrow("consumer failed");
    expect(newest().frames().at(-1)).toEqual({ type: "subscribe", roomIds: [4] });
  });

  test("a socket error is a failure; the close that follows it is stale", async () => {
    const t = setup();
    t.client.start();
    await settle();
    newest().error();
    expect(t.client.getStatus()).toBe("down");
    const pending = t.clock.pendingDelays();
    newest().closeWith(1006);
    expect(t.clock.pendingDelays()).toEqual(pending);
  });

  test("a socket that cannot even be constructed is a failure", async () => {
    const t = setup();
    const client = new LiveClient({
      url: "ws://localhost/api/v1/live",
      socketFactory: () => {
        throw new Error("blocked");
      },
      clock: t.clock,
      mintTicket: t.mintTicket,
      onFrame: () => {},
      onStatus: (status) => t.statuses.push(status),
    });
    client.start();
    await settle();
    expect(t.statuses).toEqual(["connecting", "down"]);
    expect(t.clock.pendingDelays()).toEqual([1000]);
  });
});

describe("frames", () => {
  test("events, subscribed acks, revocations and resyncs are handed on; pongs, junk and unknown types are not", async () => {
    const t = setup();
    await goLive(t);
    const socket = newest();
    socket.receive({ type: "event", kind: "message.posted", roomId: 12, threadNumber: 7, messageId: 3 });
    socket.receive({ type: "subscribed", roomIds: [12], refused: [], capped: [] });
    socket.receive({ type: "revoked", roomId: 12 });
    socket.receive({ type: "resync" });
    socket.receive({ type: "pong" });
    socket.receive({ type: "from-the-future" });
    socket.receive("{not json");
    expect(t.frames.map((frame) => frame.type)).toEqual(["ready", "event", "subscribed", "revoked", "resync"]);
  });

  test("a second ready on a live socket is ignored", async () => {
    const t = setup();
    t.client.setRooms([1]);
    await goLive(t);
    newest().receive({ type: "ready", protocol: 1, maxSubscriptions: 128 });
    expect(t.frames).toHaveLength(1);
    expect(newest().frames().filter((frame) => frame.type === "subscribe")).toHaveLength(1);
  });

  test("anything before ready is ignored", async () => {
    const t = setup();
    t.client.start();
    await settle();
    newest().open();
    newest().receive({ type: "event", kind: "room.updated", roomId: 1 });
    expect(t.frames).toEqual([]);
  });
});

describe("subscriptions", () => {
  test("a change of the wanted set is sent as a diff: the freed rooms first, then the new ones", async () => {
    const t = setup();
    t.client.setRooms([12, 40]);
    await goLive(t);
    t.client.setRooms([12, 50]);
    t.clock.advanceBy(100);
    expect(newest().frames().slice(2)).toEqual([
      { type: "unsubscribe", roomIds: [40] },
      { type: "subscribe", roomIds: [50] },
    ]);
    t.client.setRooms([12, 50]);
    t.clock.advanceBy(100);
    expect(newest().frames()).toHaveLength(4);
  });

  test("quick changes are coalesced for 100 ms into one diff (the server allows 30 frames per 10 s)", async () => {
    const t = setup();
    t.client.setRooms([1]);
    await goLive(t);
    t.client.setRooms([1, 2]);
    t.clock.advanceBy(40);
    t.client.setRooms([1, 2, 3]);
    t.clock.advanceBy(40);
    t.client.setRooms([1, 3]);
    expect(newest().frames()).toHaveLength(2);
    t.clock.advanceBy(20);
    expect(newest().frames().slice(2)).toEqual([{ type: "subscribe", roomIds: [3] }]);
  });

  test("a pending diff dies with its socket, and the next socket subscribes to the then-wanted set", async () => {
    const t = setup();
    await goLive(t);
    t.client.setRooms([7]);
    newest().closeWith(1006);
    expect(t.clock.pendingDelays()).toEqual([1000]);
    t.clock.advanceBy(1000);
    await settle();
    makeLive(newest());
    expect(newest().frames().filter((frame) => frame.type === "subscribe")).toEqual([{ type: "subscribe", roomIds: [7] }]);
  });

  test("the wanted set is trimmed to the announced cap, keeping the front of the list", async () => {
    const t = setup();
    t.client.setRooms([1, 2, 3]);
    await goLive(t, 2);
    expect(newest().frames().slice(1)).toEqual([{ type: "subscribe", roomIds: [1, 2] }]);
  });

  test("a refused or capped room is not asked for again until the refusals are forgotten", async () => {
    const t = setup();
    t.client.setRooms([12, 40, 41]);
    await goLive(t);
    newest().receive({ type: "subscribed", roomIds: [12], refused: [40], capped: [41] });
    t.client.setRooms([12, 40, 41, 42]);
    t.clock.advanceBy(100);
    expect(newest().frames().slice(2)).toEqual([{ type: "subscribe", roomIds: [42] }]);
    t.client.forgetRefused();
    t.clock.advanceBy(100);
    expect(newest().frames().slice(3)).toEqual([{ type: "subscribe", roomIds: [40, 41] }]);
    t.client.forgetRefused();
    t.clock.advanceBy(100);
    expect(newest().frames()).toHaveLength(4);
  });

  test("a revoked room is dropped from the socket's set; a caller that still wants it asks again at the next diff", async () => {
    const t = setup();
    t.client.setRooms([12]);
    await goLive(t);
    newest().receive({ type: "revoked", roomId: 12 });
    t.client.setRooms([12, 13]);
    t.clock.advanceBy(100);
    expect(newest().frames().slice(2)).toEqual([{ type: "subscribe", roomIds: [12, 13] }]);
  });

  test("a wanted set before the socket is live is only remembered, and a new socket subscribes afresh", async () => {
    const t = setup();
    t.client.setRooms([5]);
    await goLive(t);
    const first = newest();
    first.closeWith(1006);
    t.clock.advanceBy(1000);
    await settle();
    makeLive(newest());
    expect(newest()).not.toBe(first);
    expect(newest().frames().at(-1)).toEqual({ type: "subscribe", roomIds: [5] });
  });
});

describe("backoff", () => {
  test("waits 1, 2, 4, 8, 16, then 30 seconds, each scaled by the jitter in [0.5, 1)", async () => {
    const t = setup();
    t.client.start();
    const delays: number[][] = [];
    for (let i = 0; i < 7; i++) {
      await settle();
      newest().closeWith(1006);
      delays.push(t.clock.pendingDelays());
      t.clock.advanceBy(60 * SECOND);
    }
    expect(delays.flat()).toEqual([1000, 2000, 4000, 8000, 16000, 30000, 30000]);
  });

  test("the jitter spans half the step to the whole step", async () => {
    const t = setup();
    t.clock.randomValue = 0;
    t.client.start();
    await settle();
    newest().closeWith(1006);
    expect(t.clock.pendingDelays()).toEqual([500]);
  });

  test("the steps reset once a socket has stayed live for a minute, and not before", async () => {
    const t = setup();
    t.client.start();
    for (let i = 0; i < 3; i++) {
      await settle();
      newest().closeWith(1006);
      t.clock.advanceBy(60 * SECOND);
    }
    await settle();
    makeLive(newest());
    advanceAnswering(t, MINUTE - 1);
    newest().closeWith(1006);
    expect(t.clock.pendingDelays()).toEqual([8000]);
    t.clock.advanceBy(8000);
    await settle();
    makeLive(newest());
    advanceAnswering(t, MINUTE);
    newest().closeWith(1006);
    expect(t.clock.pendingDelays()).toEqual([1000]);
  });

  test("while down the status stays down through every attempt, and is live again on ready", async () => {
    const t = setup();
    await goLive(t);
    newest().closeWith(1006);
    t.clock.advanceBy(1000);
    await settle();
    expect(t.statuses).toEqual(["connecting", "live", "down"]);
    makeLive(newest());
    expect(t.statuses).toEqual(["connecting", "live", "down", "live"]);
  });
});

describe("close codes", () => {
  test.each([1003, 1008, 1009, 1011, 4401, 1006])("%i falls back to backoff", async (code) => {
    const t = setup();
    await goLive(t);
    newest().closeWith(code);
    expect(t.client.getStatus()).toBe("down");
    expect(t.clock.pendingDelays()).toEqual([1000]);
  });

  test("1001 (the server is stopping) reconnects after a random one to five seconds, not through the backoff", async () => {
    const t = setup();
    await goLive(t);
    t.clock.randomValue = 0;
    newest().closeWith(1001);
    expect(t.clock.pendingDelays()).toEqual([1000]);
    t.clock.advanceBy(1000);
    await settle();
    makeLive(newest());
    t.clock.randomValue = 1;
    newest().closeWith(1001);
    expect(t.clock.pendingDelays()).toEqual([5000]);
  });

  test("4001 re-mints after a random 0 to 1 s of jitter, with a fresh ticket", async () => {
    const t = setup();
    await goLive(t);
    t.clock.randomValue = 0.5;
    newest().closeWith(4001);
    expect(t.client.getStatus()).toBe("down");
    expect(t.clock.pendingDelays()).toEqual([500]);
    expect(t.mintTicket).toHaveBeenCalledTimes(1);
    t.clock.advanceBy(499);
    expect(t.mintTicket).toHaveBeenCalledTimes(1);
    t.clock.advanceBy(1);
    expect(t.mintTicket).toHaveBeenCalledTimes(2);
    await settle();
    expect(sockets()).toHaveLength(2);
  });

  test("the jitter before a re-mint spans 0 to 1 s and never more", async () => {
    for (const [random, expected] of [
      [0, 0],
      [0.999, 999],
    ] as const) {
      SocketStub.reset();
      const t = setup();
      await goLive(t);
      t.clock.randomValue = random;
      newest().closeWith(4003);
      expect(t.clock.pendingDelays()).toEqual([expected]);
    }
  });

  test("4003 re-mints too, and a second 4001 or 4003 within ten seconds falls back to backoff", async () => {
    const t = setup();
    await goLive(t);
    newest().closeWith(4003);
    t.clock.advanceBy(SECOND);
    await settle();
    makeLive(newest());
    t.clock.advanceBy(8 * SECOND);
    newest().closeWith(4001);
    expect(t.mintTicket).toHaveBeenCalledTimes(2);
    expect(t.clock.pendingDelays()).toEqual([1000]);
  });

  test("a 4001 more than ten seconds after the last re-mint re-mints again", async () => {
    const t = setup();
    await goLive(t);
    newest().closeWith(4001);
    t.clock.advanceBy(SECOND);
    await settle();
    makeLive(newest());
    t.clock.advanceBy(10 * SECOND);
    newest().closeWith(4003);
    expect(t.clock.pendingDelays()).toEqual([1000]);
    t.clock.advanceBy(SECOND);
    expect(t.mintTicket).toHaveBeenCalledTimes(3);
  });

  test("4429 pauses for a minute, then asks again; a socket that goes live ends the pause", async () => {
    const t = setup();
    await goLive(t);
    newest().closeWith(4429);
    expect(t.client.getStatus()).toBe("paused");
    expect(t.clock.pendingDelays()).toEqual([MINUTE]);
    t.clock.advanceBy(MINUTE - 1);
    expect(t.mintTicket).toHaveBeenCalledTimes(1);
    t.clock.advanceBy(1);
    expect(t.mintTicket).toHaveBeenCalledTimes(2);
    expect(t.client.getStatus()).toBe("paused");
    await settle();
    makeLive(newest());
    expect(t.client.getStatus()).toBe("live");
  });
});

describe("a refused mint", () => {
  test("the per-user socket cap pauses the tab for a minute", async () => {
    const t = setup(() => Promise.reject(new ApiError(429, { type: LIVE_SOCKET_LIMIT_PROBLEM })));
    t.client.start();
    await settle();
    expect(t.client.getStatus()).toBe("paused");
    expect(t.clock.pendingDelays()).toEqual([MINUTE]);
    expect(sockets()).toHaveLength(0);
  });

  test("any other refusal backs off, and a Retry-After longer than the step wins (capped at five minutes, plus up to a second of jitter)", async () => {
    let retryAfterMs: number | null = 30_000;
    const t = setup(() => Promise.reject(new LiveMintError(503, { type: "urn:adder:problem:live-unavailable" }, retryAfterMs)));
    t.client.start();
    await settle();
    expect(t.clock.pendingDelays()).toEqual([31_000]);
    retryAfterMs = 3_600_000;
    t.clock.advanceBy(31_000);
    await settle();
    expect(t.clock.pendingDelays()).toEqual([5 * MINUTE + 1000]);
    retryAfterMs = null;
    t.clock.advanceBy(5 * MINUTE + 1000);
    await settle();
    expect(t.clock.pendingDelays()).toEqual([4000]);
  });

  test("a plain ApiError and a network failure back off", async () => {
    let failure: unknown = new ApiError(401, {});
    const t = setup(() => Promise.reject(failure));
    t.client.start();
    await settle();
    expect(t.clock.pendingDelays()).toEqual([1000]);
    t.clock.advanceBy(1000);
    failure = new TypeError("Failed to fetch");
    await settle();
    expect(t.clock.pendingDelays()).toEqual([2000]);
    expect(t.client.getStatus()).toBe("down");
  });

  test("a mint that answers after stop() opens nothing", async () => {
    let release: (ticket: { ticket: string }) => void = () => {};
    const t = setup(() => new Promise((resolve) => (release = resolve)));
    t.client.start();
    t.client.stop();
    release({ ticket: TICKET });
    await settle();
    expect(sockets()).toHaveLength(0);
    expect(t.client.getStatus()).toBe("stopped");
  });
});

describe("staying alive", () => {
  test("pings every 25 seconds while visible and is content with a pong", async () => {
    const t = setup();
    await goLive(t);
    t.clock.advanceBy(25 * SECOND);
    expect(newest().frames().filter((frame) => frame.type === "ping")).toHaveLength(1);
    newest().receive({ type: "pong" });
    t.clock.advanceBy(10 * SECOND);
    expect(newest().close).not.toHaveBeenCalled();
    t.clock.advanceBy(15 * SECOND);
    expect(newest().frames().filter((frame) => frame.type === "ping")).toHaveLength(2);
  });

  test("no pong within ten seconds closes the socket and reconnects", async () => {
    const t = setup();
    await goLive(t);
    const socket = newest();
    t.clock.advanceBy(25 * SECOND);
    t.clock.advanceBy(10 * SECOND - 1);
    expect(socket.close).not.toHaveBeenCalled();
    t.clock.advanceBy(1);
    expect(socket.close).toHaveBeenCalledWith(4000);
    expect(t.client.getStatus()).toBe("down");
    expect(t.clock.pendingDelays()).toEqual([1000]);
  });

  test("any frame, not only a pong, shows the server is there", async () => {
    const t = setup();
    await goLive(t);
    t.clock.advanceBy(25 * SECOND);
    newest().receive({ type: "event", kind: "room.updated", roomId: 1 });
    t.clock.advanceBy(10 * SECOND);
    expect(newest().close).not.toHaveBeenCalled();
  });
});

describe("the tab and the network", () => {
  test("hidden for eight hours closes the socket on purpose and goes idle; shown again it connects afresh", async () => {
    const t = setup();
    await goLive(t);
    const socket = newest();
    t.client.setVisible(false);
    advanceQuiet(t, 8 * HOUR - 1);
    expect(socket.close).not.toHaveBeenCalled();
    t.clock.advanceBy(1);
    expect(socket.close).toHaveBeenCalledWith(1000);
    expect(t.client.getStatus()).toBe("idle");
    expect(t.clock.pendingDelays()).toEqual([]);
    socket.closeWith(1000);
    expect(t.clock.pendingDelays()).toEqual([]);
    t.client.setVisible(true);
    expect(t.client.getStatus()).toBe("connecting");
    t.clock.advanceBy(1000);
    await settle();
    expect(sockets()).toHaveLength(2);
    makeLive(newest());
    expect(t.client.getStatus()).toBe("live");
  });

  test("shown again before five minutes, nothing changed", async () => {
    const t = setup();
    await goLive(t);
    t.client.setVisible(false);
    t.clock.advanceBy(4 * MINUTE);
    t.client.setVisible(true);
    advanceAnswering(t, 10 * MINUTE);
    expect(newest().close).not.toHaveBeenCalled();
    expect(t.client.getStatus()).toBe("live");
  });

  test("a hidden tab does not ping", async () => {
    const t = setup();
    await goLive(t);
    t.client.setVisible(false);
    t.clock.advanceBy(100 * SECOND);
    expect(newest().frames().filter((frame) => frame.type === "ping")).toEqual([]);
  });

  test("a tab shown while waiting out a backoff reconnects within a random second, not at once", async () => {
    const t = setup();
    await goLive(t);
    newest().closeWith(1006);
    t.client.setVisible(false);
    t.clock.randomValue = 0.25;
    t.client.setVisible(true);
    expect(t.clock.pendingDelays()).toEqual([250]);
    expect(t.mintTicket).toHaveBeenCalledTimes(1);
    t.clock.advanceBy(250);
    expect(t.mintTicket).toHaveBeenCalledTimes(2);
    await settle();
    expect(sockets()).toHaveLength(2);
  });

  test("going offline closes the socket and goes down without retrying; coming back reconnects within a random second", async () => {
    const t = setup();
    await goLive(t);
    const socket = newest();
    t.client.setOnline(false);
    expect(socket.close).toHaveBeenCalledWith(1000);
    expect(t.client.getStatus()).toBe("down");
    t.clock.advanceBy(10 * MINUTE);
    expect(t.mintTicket).toHaveBeenCalledTimes(1);
    t.client.setOnline(true);
    expect(t.mintTicket).toHaveBeenCalledTimes(1);
    t.clock.advanceBy(1000);
    expect(t.mintTicket).toHaveBeenCalledTimes(2);
    await settle();
    makeLive(newest());
    expect(t.client.getStatus()).toBe("live");
  });

  test("a tab shown while offline says down at once, and the network's return connects it", async () => {
    const t = setup();
    await goLive(t);
    t.client.setVisible(false);
    advanceQuiet(t, 8 * HOUR);
    t.client.setOnline(false);
    expect(t.client.getStatus()).toBe("idle");
    t.client.setVisible(true);
    expect(t.client.getStatus()).toBe("down");
    t.clock.advanceBy(MINUTE);
    expect(t.mintTicket).toHaveBeenCalledTimes(1);
    t.client.setOnline(true);
    t.clock.advanceBy(1000);
    expect(t.mintTicket).toHaveBeenCalledTimes(2);
    await settle();
    makeLive(newest());
    expect(t.client.getStatus()).toBe("live");
  });

  test("a failure while offline schedules nothing", async () => {
    const t = setup();
    t.client.start();
    await settle();
    t.client.setOnline(false);
    newest().closeWith(1006);
    expect(t.clock.pendingDelays()).toEqual([]);
  });

  test("offline while idle stays idle, and the tab shown later connects", async () => {
    const t = setup();
    await goLive(t);
    t.client.setVisible(false);
    advanceQuiet(t, 8 * HOUR);
    t.client.setOnline(false);
    t.client.setOnline(true);
    expect(t.client.getStatus()).toBe("idle");
    expect(t.mintTicket).toHaveBeenCalledTimes(1);
  });

  test("bindBrowserSignals feeds the client from the window and the document and unbinds", () => {
    const client = { setVisible: vi.fn(), setOnline: vi.fn() };
    const handlers = new Map<string, () => void>();
    const target = {
      addEventListener: (type: string, handler: () => void) => handlers.set(type, handler),
      removeEventListener: (type: string) => handlers.delete(type),
    };
    const doc = { ...target, visibilityState: "hidden" } as unknown as Document;
    const unbind = bindBrowserSignals(client, target as unknown as Window, doc, { onLine: false });
    expect(client.setOnline).toHaveBeenLastCalledWith(false);
    expect(client.setVisible).toHaveBeenLastCalledWith(false);
    handlers.get("online")!();
    expect(client.setOnline).toHaveBeenLastCalledWith(true);
    handlers.get("offline")!();
    expect(client.setOnline).toHaveBeenLastCalledWith(false);
    Object.defineProperty(doc, "visibilityState", { value: "visible" });
    handlers.get("visibilitychange")!();
    expect(client.setVisible).toHaveBeenLastCalledWith(true);
    unbind();
    expect([...handlers.keys()]).toEqual([]);
  });
});

describe("stopping and stale sockets", () => {
  test("stop() closes the socket with 1000, cancels every timer and ends the client", async () => {
    const t = setup();
    t.client.setRooms([1]);
    await goLive(t);
    const socket = newest();
    t.client.stop();
    expect(socket.close).toHaveBeenCalledWith(1000);
    expect(t.client.getStatus()).toBe("stopped");
    expect(t.clock.pendingDelays()).toEqual([]);
    t.client.setRooms([1, 2]);
    t.client.setVisible(false);
    t.client.setOnline(false);
    expect(socket.frames().filter((frame) => frame.type === "subscribe")).toHaveLength(1);
    expect(t.clock.pendingDelays()).toEqual([]);
    t.client.stop();
    expect(t.statuses.filter((status) => status === "stopped")).toHaveLength(1);
  });

  test("a socket that was replaced can no longer speak: its frames and its close are ignored", async () => {
    const t = setup();
    await goLive(t);
    const old = newest();
    old.closeWith(1006);
    t.clock.advanceBy(1000);
    await settle();
    const fresh = newest();
    makeLive(fresh);
    const before = t.frames.length;
    old.receive({ type: "event", kind: "room.updated", roomId: 1 });
    old.closeWith(4429);
    old.error();
    expect(t.frames).toHaveLength(before);
    expect(t.client.getStatus()).toBe("live");
    expect(fresh.close).not.toHaveBeenCalled();
    expect(t.clock.pendingDelays().filter((delay) => delay !== 25 * SECOND && delay !== MINUTE)).toEqual([]);
  });

  test("a stopped client's socket events are ignored", async () => {
    const t = setup();
    await goLive(t);
    const socket = newest();
    t.client.stop();
    socket.receive({ type: "event", kind: "room.updated", roomId: 1 });
    socket.closeWith(1006);
    expect(t.frames.map((frame) => frame.type)).toEqual(["ready"]);
    expect(t.clock.pendingDelays()).toEqual([]);
  });
});
