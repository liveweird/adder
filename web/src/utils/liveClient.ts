// The live channel's client state machine (`.claude/docs/live.md`), no React: mint a ticket, open the socket, send it as the FIRST frame, wait for
// `ready`, then keep the room subscriptions in step with what the caller wants. Everything the outside world supplies (the socket, the timers,
// the ticket, the frames' consumer) is injected, so the whole machine runs under a fake clock in the tests (`test/socket.ts`).
//
// Delivery is at-most-once with no replay: every (re)connect ends in `ready` and the consumer re-reads what it shows, so a gap is never silent.
// A socket belongs to one GENERATION; every event of an older one is ignored (Grenadiers' `GameClient` shape), so a late close or a late mint
// can never touch the socket that replaced it.

import { ApiError } from "../api/http";
import { LIVE_SOCKET_LIMIT_PROBLEM, LiveMintError } from "../api/live";
import { parseServerFrame, type LiveClientFrame, type LiveServerFrame } from "./liveProtocol";

/** Everything time and chance: injected so tests run on a fake clock. */
export interface LiveClock {
  setTimeout(callback: () => void, delayMs: number): unknown;
  clearTimeout(handle: unknown): void;
  now(): number;
  random(): number;
}

export const browserClock: LiveClock = {
  setTimeout: (callback, delayMs) => setTimeout(callback, delayMs),
  clearTimeout: (handle) => clearTimeout(handle as ReturnType<typeof setTimeout>),
  now: () => Date.now(),
  random: () => crypto.getRandomValues(new Uint32Array(1))[0] / 0x1_0000_0000,
};

/** The slice of `WebSocket` the client uses (the browser's and the tests' `SocketStub` both fit). */
export interface LiveSocket {
  readonly readyState: number;
  send(data: string): void;
  close(code?: number, reason?: string): void;
  addEventListener(type: "open" | "error", listener: () => void): void;
  addEventListener(type: "message", listener: (event: { data: unknown }) => void): void;
  addEventListener(type: "close", listener: (event: { code: number }) => void): void;
}

/**
 * `connecting` the first attempt (nothing to show); `live` after `ready`; `down` while reconnecting (the badge shows after 5 s of it); `paused`
 * when the per-user socket cap refuses this tab (retried every minute); `idle` after 5 minutes hidden (the socket is closed on purpose and
 * reopens when the tab is shown); `stopped` after `stop()`.
 */
export type LiveStatus = "connecting" | "live" | "down" | "paused" | "idle" | "stopped";

export interface LiveDependencies {
  url: string;
  socketFactory: (url: string) => LiveSocket;
  clock: LiveClock;
  mintTicket: () => Promise<{ ticket: string }>;
  onFrame: (frame: LiveServerFrame) => void;
  onStatus: (status: LiveStatus) => void;
}

/** Backoff steps (each times a random 0.5 to 1); the step resets once a socket has stayed live for `STABLE_AFTER_MS`. */
const BACKOFF_MS = [1_000, 2_000, 4_000, 8_000, 16_000, 30_000];
const STABLE_AFTER_MS = 60_000;
const PAUSED_RETRY_MS = 60_000;
/** A 4001/4003 twice within this window is a loop, not an expiry: fall back to backoff. */
const REMINT_LOOP_MS = 10_000;
const HIDDEN_CLOSE_MS = 5 * 60_000;
const PING_EVERY_MS = 25_000;
const PONG_WITHIN_MS = 10_000;
/** From the socket's creation to `ready`: a handshake a proxy swallows would otherwise hang for minutes. */
const READY_WITHIN_MS = 10_000;
/** A server restart (1001) is retried after a random 1 to 5 s, so a whole room of tabs does not return in one instant. */
const STOPPING_RETRY_MIN_MS = 1_000;
const STOPPING_RETRY_SPREAD_MS = 4_000;
/** A `Retry-After` is honoured up to this (a misbehaving proxy cannot park a tab for an hour). */
const RETRY_AFTER_CAP_MS = 5 * 60_000;
const DEFAULT_MAX_SUBSCRIPTIONS = 128;
/** A tab shown, a network that returns or a token that expired (4001/4003) reconnects after a random 0 to this long, so a crowd does not arrive in one instant. */
const RECONNECT_JITTER_MS = 1_000;
/** Subscription changes are coalesced for this long: quick navigation stays far below the server's 30 frames per 10 s. */
const SUBSCRIPTION_COALESCE_MS = 100;
const CONNECTING = 0;
const OPEN = 1;

const CLOSE_NORMAL = 1000;
const CLOSE_SERVER_STOPPING = 1001;
const CLOSE_TOKEN_EXPIRED = 4001;
const CLOSE_STANDING_CHANGED = 4003;
const CLOSE_SOCKET_CAP = 4429;
/** Ours: the pong never came. */
const CLOSE_PONG_TIMEOUT = 4000;

type TimerName = "reconnect" | "ready" | "ping" | "pong" | "stable" | "hidden" | "subscriptions";

export class LiveClient {
  private readonly deps: LiveDependencies;
  private status: LiveStatus = "idle";
  private started = false;
  private over = false;
  private visible = true;
  private online = true;
  /** Hidden long enough that the socket was closed on purpose. */
  private suspended = false;
  private generation = 0;
  private socket: LiveSocket | null = null;
  private attempt = 0;
  private lastRemintAt: number | null = null;
  private readonly timers = new Map<TimerName, unknown>();
  /** What the caller wants subscribed (in priority order), what this socket has been asked for, and what the server refused. */
  private desired: number[] = [];
  private readonly requested = new Set<number>();
  private readonly refused = new Set<number>();
  private maxSubscriptions = DEFAULT_MAX_SUBSCRIPTIONS;

  constructor(dependencies: LiveDependencies) {
    this.deps = dependencies;
  }

  getStatus(): LiveStatus {
    return this.status;
  }

  start(): void {
    if (this.started || this.over) return;
    this.started = true;
    this.setStatus("connecting");
    this.connect();
  }

  /** Closes the socket (1000) and ends the client for good; a later identity gets a new one. */
  stop(): void {
    if (this.over) return;
    this.over = true;
    this.clearTimer("hidden");
    this.teardown();
    this.setStatus("stopped");
  }

  /** The rooms the caller wants updates for, most important first; sent as a diff once the socket is live. */
  setRooms(roomIds: readonly number[]): void {
    this.desired = [...new Set(roomIds)];
    this.scheduleSync();
  }

  /** Forget the refusals (the route changed): a room that was refused may be readable now. */
  forgetRefused(): void {
    if (this.refused.size === 0) return;
    this.refused.clear();
    this.scheduleSync();
  }

  setVisible(visible: boolean): void {
    if (this.over || visible === this.visible) return;
    this.visible = visible;
    if (!visible) {
      this.setTimer("hidden", HIDDEN_CLOSE_MS, () => this.suspend());
      return;
    }
    this.clearTimer("hidden");
    if (this.suspended) {
      this.suspended = false;
      this.attempt = 0;
      // Shown while offline: there is nothing to try yet, and the badge should say so (coming back online connects).
      this.setStatus(this.online ? "connecting" : "down");
      this.connectSoon();
    } else if (this.status === "down" && this.timers.has("reconnect")) {
      // Waiting out a backoff: the tab is back, so retry shortly (an attempt already in flight is left alone).
      this.connectSoon();
    }
  }

  setOnline(online: boolean): void {
    if (this.over || online === this.online) return;
    this.online = online;
    if (!online) {
      this.teardown();
      if (!this.suspended) this.setStatus("down");
      return;
    }
    if (this.suspended) return;
    this.attempt = 0;
    this.connectSoon();
  }

  // ---- connecting -------------------------------------------------------------------------------------------------------------------

  /** Connects after a random 0 to one second: the tab came back or the network returned, and so did everybody else's. */
  private connectSoon(): void {
    if (!this.online) return;
    this.setTimer("reconnect", this.deps.clock.random() * RECONNECT_JITTER_MS, () => this.connect());
  }

  private connect(): void {
    if (!this.started || this.over || !this.online || this.suspended) return;
    this.clearTimer("reconnect");
    this.dropSocket();
    const generation = this.generation;
    void this.deps.mintTicket().then(
      (minted) => {
        if (generation === this.generation) this.open(generation, minted.ticket);
      },
      (error: unknown) => {
        if (generation === this.generation) this.mintFailed(error);
      },
    );
  }

  private open(generation: number, ticket: string): void {
    let socket: LiveSocket;
    try {
      socket = this.deps.socketFactory(this.deps.url);
    } catch {
      this.fail(generation);
      return;
    }
    this.socket = socket;
    this.setTimer("ready", READY_WITHIN_MS, () => this.fail(generation));
    socket.addEventListener("open", () => {
      // The ticket travels in the FIRST frame, never in the URL (API-AUTH-002).
      if (generation === this.generation) this.send({ type: "auth", ticket });
    });
    socket.addEventListener("message", (event) => this.receive(generation, event.data));
    // A close always follows an error and carries the code; failing here too is idempotent (the generation moves on).
    socket.addEventListener("error", () => this.fail(generation));
    socket.addEventListener("close", (event) => this.closed(generation, event.code));
  }

  private mintFailed(error: unknown): void {
    if (error instanceof ApiError) {
      if (error.status === 429 && error.type === LIVE_SOCKET_LIMIT_PROBLEM) {
        this.pause();
        return;
      }
      const retryAfter = error instanceof LiveMintError ? error.retryAfterMs : null;
      // The server's Retry-After, plus a little jitter so the tabs it turned away do not all come back on the same second.
      this.backoff(retryAfter === null ? 0 : Math.min(retryAfter, RETRY_AFTER_CAP_MS) + this.deps.clock.random() * RECONNECT_JITTER_MS);
      return;
    }
    this.backoff();
  }

  // ---- receiving --------------------------------------------------------------------------------------------------------------------

  private receive(generation: number, raw: unknown): void {
    if (generation !== this.generation) return;
    const frame = parseServerFrame(raw);
    if (frame === null) return;
    // Any frame proves the server is there; only `ready` is expected before the socket is live.
    this.clearTimer("pong");
    if (frame.type === "ready") {
      if (this.status !== "live") this.becomeLive(frame);
      return;
    }
    if (this.status !== "live" || frame.type === "pong") return;
    if (frame.type === "subscribed") {
      for (const id of [...frame.refused, ...frame.capped]) {
        this.requested.delete(id);
        this.refused.add(id);
      }
    } else if (frame.type === "revoked") {
      // The server dropped the room; if the caller still wants it, the next diff asks again and is told no.
      this.requested.delete(frame.roomId);
    }
    this.deps.onFrame(frame);
  }

  private becomeLive(frame: Extract<LiveServerFrame, { type: "ready" }>): void {
    this.clearTimer("ready");
    this.maxSubscriptions = frame.maxSubscriptions;
    this.requested.clear();
    this.setStatus("live");
    this.setTimer("stable", STABLE_AFTER_MS, () => {
      this.attempt = 0;
    });
    this.schedulePing(this.generation);
    // Subscribe first: a consumer that throws on `ready` must not leave the socket without its rooms.
    this.syncSubscriptions();
    this.deps.onFrame(frame);
  }

  private schedulePing(generation: number): void {
    this.setTimer("ping", PING_EVERY_MS, () => {
      if (generation !== this.generation) return;
      // A hidden tab's timers are throttled and nobody is looking: ping only while visible.
      if (this.visible) {
        this.send({ type: "ping" });
        this.setTimer("pong", PONG_WITHIN_MS, () => this.fail(generation, CLOSE_PONG_TIMEOUT));
      }
      this.schedulePing(generation);
    });
  }

  // ---- closing and retrying ---------------------------------------------------------------------------------------------------------

  private closed(generation: number, code: number): void {
    if (generation !== this.generation) return;
    this.socket = null; // already closed: nothing to close again
    this.dropSocket();
    switch (code) {
      case CLOSE_SERVER_STOPPING:
        this.reconnectAfter(STOPPING_RETRY_MIN_MS + this.deps.clock.random() * STOPPING_RETRY_SPREAD_MS);
        break;
      case CLOSE_TOKEN_EXPIRED:
      case CLOSE_STANDING_CHANGED:
        this.remint();
        break;
      case CLOSE_SOCKET_CAP:
        this.pause();
        break;
      default:
        this.backoff();
    }
  }

  /**
   * 4001 and 4003: the minting token expired or its standing changed; a fresh mint (through `authedFetch`) is the answer, unless it keeps
   * happening. Tokens minted together expire together, so the mint waits a random 0 to 1 s: a whole room of sockets does not mint in one instant.
   */
  private remint(): void {
    const now = this.deps.clock.now();
    const looping = this.lastRemintAt !== null && now - this.lastRemintAt < REMINT_LOOP_MS;
    this.lastRemintAt = now;
    if (looping) {
      this.backoff();
      return;
    }
    this.setStatus("down");
    this.connectSoon();
  }

  private fail(generation: number, code = CLOSE_NORMAL): void {
    if (generation !== this.generation) return;
    this.dropSocket(code);
    this.backoff();
  }

  private backoff(minimumMs = 0): void {
    const base = BACKOFF_MS[Math.min(this.attempt, BACKOFF_MS.length - 1)];
    this.attempt += 1;
    this.reconnectAfter(Math.max(minimumMs, Math.round(base * (0.5 + this.deps.clock.random() * 0.5))));
  }

  private reconnectAfter(delayMs: number): void {
    this.setStatus("down");
    if (!this.online) return;
    this.setTimer("reconnect", Math.round(delayMs), () => this.connect());
  }

  private pause(): void {
    this.setStatus("paused");
    if (!this.online) return;
    this.setTimer("reconnect", PAUSED_RETRY_MS, () => this.connect());
  }

  /** Hidden for five minutes: close on purpose and stay quiet until the tab is shown again. */
  private suspend(): void {
    this.suspended = true;
    this.teardown();
    this.setStatus("idle");
  }

  // ---- plumbing ---------------------------------------------------------------------------------------------------------------------

  /** Ends the current socket's generation: its late events are ignored from here on. */
  private dropSocket(code = CLOSE_NORMAL): void {
    this.generation += 1;
    for (const name of ["ready", "ping", "pong", "stable", "subscriptions"] as const) this.clearTimer(name);
    const socket = this.socket;
    this.socket = null;
    this.requested.clear();
    if (socket === null) return;
    // Closing a socket that is still connecting makes the browser log a failed handshake: let it open, then close it (its late events are stale).
    if (socket.readyState === CONNECTING) socket.addEventListener("open", () => socket.close(code));
    else socket.close(code);
  }

  /** Closes the socket and cancels every pending retry; the hidden-tab timer is the one thing that outlives it. */
  private teardown(): void {
    this.clearTimer("reconnect");
    this.dropSocket();
  }

  private send(frame: LiveClientFrame): void {
    if (this.socket?.readyState === OPEN) this.socket.send(JSON.stringify(frame));
  }

  /** Coalesces a burst of changes (a rail that loads, quick navigation) into one diff. */
  private scheduleSync(): void {
    if (this.status !== "live" || this.timers.has("subscriptions")) return;
    this.setTimer("subscriptions", SUBSCRIPTION_COALESCE_MS, () => this.syncSubscriptions());
  }

  /** Brings the server's subscriptions to the wanted set: frees first, then asks, in frames of at most the announced cap. */
  private syncSubscriptions(): void {
    this.clearTimer("subscriptions");
    if (this.status !== "live" || this.socket === null) return;
    const wanted = new Set(this.desired.filter((id) => !this.refused.has(id)).slice(0, this.maxSubscriptions));
    const drop = [...this.requested].filter((id) => !wanted.has(id));
    const add = [...wanted].filter((id) => !this.requested.has(id));
    for (let i = 0; i < drop.length; i += this.maxSubscriptions) {
      this.send({ type: "unsubscribe", roomIds: drop.slice(i, i + this.maxSubscriptions) });
    }
    for (let i = 0; i < add.length; i += this.maxSubscriptions) {
      this.send({ type: "subscribe", roomIds: add.slice(i, i + this.maxSubscriptions) });
    }
    for (const id of drop) this.requested.delete(id);
    for (const id of add) this.requested.add(id);
  }

  private setStatus(status: LiveStatus): void {
    if (status === this.status) return;
    this.status = status;
    this.deps.onStatus(status);
  }

  private setTimer(name: TimerName, delayMs: number, callback: () => void): void {
    this.clearTimer(name);
    this.timers.set(
      name,
      this.deps.clock.setTimeout(() => {
        this.timers.delete(name);
        callback();
      }, delayMs),
    );
  }

  private clearTimer(name: TimerName): void {
    if (!this.timers.has(name)) return;
    this.deps.clock.clearTimeout(this.timers.get(name));
    this.timers.delete(name);
  }
}

/**
 * Feeds the browser's tab visibility and connectivity to the client: it reconnects at once when the tab is shown or the network is back,
 * closes after five minutes hidden and goes down while offline. Bind AFTER `start()`; the returned function unbinds.
 */
export function bindBrowserSignals(
  client: Pick<LiveClient, "setVisible" | "setOnline">,
  win: Pick<Window, "addEventListener" | "removeEventListener">,
  doc: Pick<Document, "visibilityState" | "addEventListener" | "removeEventListener">,
  nav: Pick<Navigator, "onLine">,
): () => void {
  const onOnline = () => client.setOnline(true);
  const onOffline = () => client.setOnline(false);
  const onVisibility = () => client.setVisible(doc.visibilityState !== "hidden");
  win.addEventListener("online", onOnline);
  win.addEventListener("offline", onOffline);
  doc.addEventListener("visibilitychange", onVisibility);
  client.setOnline(nav.onLine);
  onVisibility();
  return () => {
    win.removeEventListener("online", onOnline);
    win.removeEventListener("offline", onOffline);
    doc.removeEventListener("visibilitychange", onVisibility);
  };
}
