// Seen state between the tabs of one browser (1.2 D9): a `BroadcastChannel` carries "this person's notices were seen" after a tab's OWN seen-write (the
// bell's "seen" and "seen all", a read marker that settled a notice, "Mark all as read"), so the other tabs re-read the bell's count and a hidden tab does not
// keep saying "(3)" after the notices were read elsewhere. No server change. The channel is per USER (its name carries the id, and a message that names another
// one is ignored), opened by the shell for the signed-in identity and closed with it; where `BroadcastChannel` does not exist (or throws) nothing happens: the
// other tabs catch up on focus, as before.

const CHANNEL_PREFIX = "adder.seen.";
const MESSAGE_VERSION = 1;

/** The slice of `BroadcastChannel` used (the browser's and the tests' `ChannelStub` both fit). */
export interface SeenChannel {
  postMessage(message: unknown): void;
  close(): void;
  addEventListener(type: "message", listener: (event: { data: unknown }) => void): void;
}

export type SeenChannelFactory = (name: string) => SeenChannel | null;

/** The browser's channel, or null where there is none (old Safari, a locked-down embed) or the constructor refuses. */
export const browserSeenChannel: SeenChannelFactory = (name) => {
  if (typeof BroadcastChannel !== "function") return null;
  try {
    return new BroadcastChannel(name);
  } catch {
    return null;
  }
};

/** What the tab has open, so a write deep in the component tree announces without a prop chain. */
let announcer: { userId: number; channel: SeenChannel } | null = null;

const isSeenMessage = (data: unknown, userId: number): boolean =>
  typeof data === "object" && data !== null && "v" in data && data.v === MESSAGE_VERSION && "userId" in data && data.userId === userId;

/**
 * Opens the user's channel and calls [onSeen] for every announcement of ANOTHER tab of the same user. Returns the closer (idempotent). With no
 * channel available it opens nothing and `announceSeen` is a no-op.
 */
export function openSeenSync(userId: number, onSeen: () => void, factory: SeenChannelFactory = browserSeenChannel): () => void {
  const channel = factory(`${CHANNEL_PREFIX}${userId}`);
  if (channel === null) return () => undefined;
  channel.addEventListener("message", (event) => {
    if (isSeenMessage(event.data, userId)) onSeen();
  });
  const mine = { userId, channel };
  announcer = mine;
  return () => {
    if (announcer === mine) announcer = null;
    channel.close();
  };
}

/** Tells the other tabs of this user that notices were seen here (best effort: a closed or failing channel is silent). */
export function announceSeen(): void {
  try {
    announcer?.channel.postMessage({ v: MESSAGE_VERSION, userId: announcer.userId });
  } catch {
    // A channel that was closed under us: the other tabs catch up on focus.
  }
}
