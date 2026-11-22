// Test double for `BroadcastChannel`: instances of one name hear each other's messages (never their own), as a browser's do. Tests that touch it
// `vi.stubGlobal("BroadcastChannel", ChannelStub)` and `vi.unstubAllGlobals()` + `ChannelStub.reset()` in `afterEach` (the suite runs with `isolate: false`).
import { vi } from "vitest";
import type { SeenChannel } from "../utils/seenSync";

type Listener = (event: { data: unknown }) => void;

export class ChannelStub implements SeenChannel {
  /** Every channel constructed and not yet closed since the last `reset()`. */
  static readonly open: ChannelStub[] = [];
  /** Every name ever opened since the last `reset()`, in order. */
  static readonly names: string[] = [];
  static reset(): void {
    ChannelStub.open.length = 0;
    ChannelStub.names.length = 0;
  }

  readonly name: string;
  readonly posted: unknown[] = [];
  readonly close = vi.fn(() => {
    const at = ChannelStub.open.indexOf(this);
    if (at >= 0) ChannelStub.open.splice(at, 1);
  });
  private readonly listeners: Listener[] = [];

  constructor(name: string) {
    this.name = name;
    ChannelStub.open.push(this);
    ChannelStub.names.push(name);
  }

  addEventListener(_type: "message", listener: Listener): void {
    this.listeners.push(listener);
  }

  postMessage(message: unknown): void {
    this.posted.push(message);
    for (const other of ChannelStub.open) if (other !== this && other.name === this.name) other.deliver(message);
  }

  /** A message from ANOTHER tab (what the browser would deliver), whatever its shape. */
  deliver(data: unknown): void {
    for (const listener of this.listeners) listener({ data });
  }
}
