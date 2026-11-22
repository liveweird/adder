import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { clearSession, persistSession } from "../api/session";
import { ChannelStub } from "../test/broadcast";
import { announceSeen } from "../utils/seenSync";
import { useSeenSync } from "./useSeenSync";

const factory = (name: string) => new ChannelStub(name);

function setup(paused = false) {
  const queryClient = new QueryClient();
  const invalidate = vi.spyOn(queryClient, "invalidateQueries").mockResolvedValue();
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  return { ...renderHook(({ paused: idle }) => useSeenSync(idle, factory), { wrapper, initialProps: { paused } }), invalidate };
}

const login = (userId: number) =>
  persistSession({ token: `t${userId}`, expiresAt: 1, refreshToken: `r${userId}`, refreshExpiresAt: 2, userId, roles: [], disabledFeatures: [], language: "en" });

describe("useSeenSync", () => {
  beforeEach(() => {
    localStorage.setItem("adder.auth.token", "fake-token");
    localStorage.setItem("adder.auth.userId", "7");
  });
  afterEach(() => {
    ChannelStub.reset();
    localStorage.clear();
  });

  test("opens the signed-in user's channel; another tab's announcement re-reads the bell", () => {
    const t = setup();
    expect(ChannelStub.names).toEqual(["adder.seen.7"]);
    const otherTab = new ChannelStub("adder.seen.7");
    act(() => otherTab.postMessage({ v: 1, userId: 7 }));
    expect(t.invalidate).toHaveBeenCalledWith({ queryKey: ["notifications"] });
    t.unmount();
  });

  test("this tab's own announcement reaches the other tabs", () => {
    const t = setup();
    const otherTab = new ChannelStub("adder.seen.7");
    const heard = vi.fn();
    otherTab.addEventListener("message", heard);
    announceSeen();
    expect(heard).toHaveBeenCalledWith({ data: { v: 1, userId: 7 } });
    t.unmount();
  });

  test("unmounting (sign-out) closes the channel", () => {
    const t = setup();
    const channel = ChannelStub.open[0];
    t.unmount();
    expect(channel.close).toHaveBeenCalledTimes(1);
    expect(ChannelStub.open).toEqual([]);
  });

  test("signing out while mounted closes the channel", () => {
    const t = setup();
    const first = ChannelStub.open[0];
    act(() => clearSession());
    expect(first.close).toHaveBeenCalled();
    expect(ChannelStub.open).toEqual([]);
    t.unmount();
  });

  test("nobody signed in (no user id) opens nothing", () => {
    localStorage.removeItem("adder.auth.userId");
    const t = setup();
    expect(ChannelStub.names).toEqual([]);
    t.unmount();
  });

  test("a different user signing in here closes the first user's channel and opens the new user's", () => {
    const t = setup();
    const first = ChannelStub.open[0];
    expect(first.name).toBe("adder.seen.7");
    act(() => login(8));
    expect(first.close).toHaveBeenCalledTimes(1);
    expect(ChannelStub.open.map((channel) => channel.name)).toEqual(["adder.seen.8"]);
    // The old user's messages no longer reach anything here.
    const otherTab = new ChannelStub("adder.seen.7");
    t.invalidate.mockClear();
    act(() => otherTab.postMessage({ v: 1, userId: 7 }));
    expect(t.invalidate).not.toHaveBeenCalled();
    t.unmount();
  });

  test("another tab replacing the stored session (a `storage` event) switches the channel the same way", () => {
    const t = setup();
    expect(ChannelStub.open[0].name).toBe("adder.seen.7");
    act(() => {
      localStorage.setItem("adder.auth.userId", "9");
      localStorage.setItem("adder.auth.sessionIdentity", "another-identity");
      window.dispatchEvent(new StorageEvent("storage", { key: "adder.auth.sessionIdentity" }));
    });
    expect(ChannelStub.open.map((channel) => channel.name)).toEqual(["adder.seen.9"]);
    expect(ChannelStub.names).toEqual(["adder.seen.7", "adder.seen.9"]);
    t.unmount();
  });

  test("a tab hidden for 8 hours (idle, no socket) drops a message: showing it reconnects, and the ready re-read is the backfill", () => {
    const t = setup(true);
    const otherTab = new ChannelStub("adder.seen.7");
    act(() => otherTab.postMessage({ v: 1, userId: 7 }));
    expect(t.invalidate).not.toHaveBeenCalled();
    t.rerender({ paused: false });
    act(() => otherTab.postMessage({ v: 1, userId: 7 }));
    expect(t.invalidate).toHaveBeenCalledTimes(1);
    t.unmount();
  });
});
