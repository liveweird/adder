// Test doubles for the live channel (ported from Grenadiers' `game/client/testSupport.ts`): a `WebSocket` whose events the test fires by hand and a
// clock that only moves when told to. App-level tests `vi.stubGlobal("WebSocket", SocketStub)` and `vi.unstubAllGlobals()` in `afterEach` (the suite
// runs with `isolate: false`), and call `SocketStub.reset()` so no instance outlives its test.
import { vi } from "vitest";
import type { LiveClock, LiveSocket } from "../utils/liveClient";
import type { LiveServerFrame } from "../utils/liveProtocol";

type Listener = (event: never) => void;

export class SocketStub implements LiveSocket {
  /** Every socket constructed since the last `reset()`, oldest first. */
  static readonly instances: SocketStub[] = [];
  static reset(): void {
    SocketStub.instances.length = 0;
  }

  readonly url: string;
  readonly sent: string[] = [];
  readonly close = vi.fn();
  readyState = 0;
  private readonly listeners: Record<"open" | "error" | "message" | "close", Listener[]> = { open: [], error: [], message: [], close: [] };

  constructor(url: string) {
    this.url = url;
    SocketStub.instances.push(this);
  }

  addEventListener(type: "open" | "error", listener: () => void): void;
  addEventListener(type: "message", listener: (event: { data: unknown }) => void): void;
  addEventListener(type: "close", listener: (event: { code: number }) => void): void;
  addEventListener(type: "open" | "error" | "message" | "close", listener: Listener): void {
    this.listeners[type].push(listener);
  }

  send(data: string): void {
    this.sent.push(data);
  }

  /** The browser's `open`: the handshake is done. */
  open(): void {
    this.readyState = 1;
    for (const listener of this.listeners.open) (listener as () => void)();
  }

  error(): void {
    for (const listener of this.listeners.error) (listener as () => void)();
  }

  /** A text frame from the server (an object is JSON-encoded; a string goes as it is, to test the parser). */
  receive(frame: LiveServerFrame | Record<string, unknown> | string): void {
    const data = typeof frame === "string" ? frame : JSON.stringify(frame);
    for (const listener of this.listeners.message) (listener as (event: { data: unknown }) => void)({ data });
  }

  /** The socket closing with a code (1006 when the browser gives none). */
  closeWith(code = 1006): void {
    this.readyState = 3;
    for (const listener of this.listeners.close) (listener as (event: { code: number }) => void)({ code });
  }

  /** What the client has sent, decoded. */
  frames(): Record<string, unknown>[] {
    return this.sent.map((raw) => JSON.parse(raw) as Record<string, unknown>);
  }
}

/** The newest socket, or a failure naming the problem (a test that expected one). */
export function lastSocket(): SocketStub {
  const socket = SocketStub.instances.at(-1);
  if (!socket) throw new Error("no socket was opened");
  return socket;
}

/** Brings a socket to `live`: open, the server's `ready`. */
export function makeLive(socket: SocketStub, maxSubscriptions = 128): void {
  socket.open();
  socket.receive({ type: "ready", protocol: 1, maxSubscriptions });
}

/** Time and chance under the test's control: a timer fires only through `advanceBy`, `random()` answers `randomValue` (1 = the longest delay of a jitter range's top, 0 = its floor). */
export class FakeClock implements LiveClock {
  randomValue = 1;
  private time = 0;
  private nextId = 1;
  private readonly tasks = new Map<number, { due: number; callback: () => void }>();

  setTimeout(callback: () => void, delayMs: number): unknown {
    const id = this.nextId++;
    this.tasks.set(id, { due: this.time + Math.max(0, delayMs), callback });
    return id;
  }

  clearTimeout(handle: unknown): void {
    this.tasks.delete(handle as number);
  }

  now(): number {
    return this.time;
  }

  random(): number {
    return this.randomValue;
  }

  /** The remaining delays of the timers still waiting, soonest first. */
  pendingDelays(): number[] {
    return [...this.tasks.values()].map((task) => task.due - this.time).sort((a, b) => a - b);
  }

  /**
   * A sleeping laptop: the wall clock (`now()`) jumps forward but the timers do not count the sleep, so each pending one is still its full delay away
   * (nothing fires, nothing is skipped).
   */
  skipWallTime(delayMs: number): void {
    this.time += delayMs;
    for (const task of this.tasks.values()) task.due += delayMs;
  }

  /** Moves time forward, firing every timer that falls due on the way (a callback may schedule more; those fire too if they fall inside the span). */
  advanceBy(delayMs: number): void {
    const target = this.time + delayMs;
    for (;;) {
      const next = [...this.tasks.entries()].filter(([, task]) => task.due <= target).sort(([a, x], [b, y]) => x.due - y.due || a - b)[0];
      if (!next) break;
      this.tasks.delete(next[0]);
      this.time = Math.max(this.time, next[1].due);
      next[1].callback();
    }
    this.time = target;
  }
}
