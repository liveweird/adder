import { afterEach, describe, expect, test, vi } from "vitest";
import { ChannelStub } from "../test/broadcast";
import { announceSeen, browserSeenChannel, openSeenSync } from "./seenSync";

const factory = (name: string) => new ChannelStub(name);

describe("seenSync", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    ChannelStub.reset();
  });

  test("an announcement reaches the other tabs of the same user, never the announcing one", () => {
    // In this module's one process the NEWEST open is "this tab" (the announcer); the older one stands for another tab.
    const otherTab = vi.fn();
    const thisTab = vi.fn();
    const closeOther = openSeenSync(7, otherTab, factory);
    const closeThis = openSeenSync(7, thisTab, factory);
    announceSeen();
    expect(otherTab).toHaveBeenCalledTimes(1);
    expect(thisTab).not.toHaveBeenCalled();
    closeThis();
    closeOther();
  });

  test("the channel is the user's own: a name per user id, so another user's tab hears nothing", () => {
    const mine = vi.fn();
    const theirs = vi.fn();
    const closeMine = openSeenSync(7, mine, factory);
    const closeTheirs = openSeenSync(8, theirs, factory);
    expect(ChannelStub.names).toEqual(["adder.seen.7", "adder.seen.8"]);
    announceSeen(); // from user 8's tab
    expect(mine).not.toHaveBeenCalled();
    closeTheirs();
    closeMine();
  });

  test("a message that names another user, has no version or is not an object is ignored", () => {
    const onSeen = vi.fn();
    const close = openSeenSync(7, onSeen, factory);
    const channel = ChannelStub.open[0];
    for (const junk of [{ v: 1, userId: 8 }, { v: 2, userId: 7 }, { userId: 7 }, "seen", null, 7, { v: 1 }]) channel.deliver(junk);
    expect(onSeen).not.toHaveBeenCalled();
    channel.deliver({ v: 1, userId: 7 });
    expect(onSeen).toHaveBeenCalledTimes(1);
    close();
  });

  test("closing closes the channel and announcing after it is a no-op", () => {
    const close = openSeenSync(7, vi.fn(), factory);
    const channel = ChannelStub.open[0];
    close();
    expect(channel.close).toHaveBeenCalledTimes(1);
    announceSeen();
    expect(channel.posted).toEqual([]);
  });

  test("a stale closer of a replaced announcer leaves the newer one in place", () => {
    const closeFirst = openSeenSync(7, vi.fn(), factory);
    const closeSecond = openSeenSync(7, vi.fn(), factory);
    closeFirst();
    announceSeen();
    expect(ChannelStub.open).toHaveLength(1);
    expect(ChannelStub.open[0].posted).toEqual([{ v: 1, userId: 7 }]);
    closeSecond();
  });

  test("no BroadcastChannel (or a constructor that throws) is a silent no-op", () => {
    vi.stubGlobal("BroadcastChannel", undefined);
    expect(browserSeenChannel("adder.seen.7")).toBeNull();
    const close = openSeenSync(7, vi.fn());
    expect(() => announceSeen()).not.toThrow();
    expect(() => close()).not.toThrow();
    vi.stubGlobal(
      "BroadcastChannel",
      class {
        constructor() {
          throw new Error("denied");
        }
      },
    );
    expect(browserSeenChannel("adder.seen.7")).toBeNull();
  });

  test("a channel that was closed under the announcer does not throw", () => {
    const close = openSeenSync(7, vi.fn(), factory);
    ChannelStub.open[0].postMessage = () => {
      throw new Error("InvalidStateError");
    };
    expect(() => announceSeen()).not.toThrow();
    close();
  });

  test("the browser's own channel is used when it exists", () => {
    vi.stubGlobal("BroadcastChannel", ChannelStub);
    const channel = browserSeenChannel("adder.seen.9");
    expect(channel).toBeInstanceOf(ChannelStub);
    channel?.close();
  });
});
