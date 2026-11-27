import { onlineManager, QueryClient, QueryObserver } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { clearSession, getSessionSnapshot } from "../api/session";
import { homeKey, roomKeys } from "./roomKeys";
import { beginRoomMembershipEcho, refreshRoomMembership, settleRoomMembershipRefresh } from "./roomMembershipRefresh";

describe("room membership refresh", () => {
  beforeEach(() => localStorage.setItem("adder.auth.token", "token"));

  afterEach(() => {
    onlineManager.setOnline(true);
    localStorage.clear();
    vi.restoreAllMocks();
  });

  test("cancels stale room and Home reads before refreshing both groups", async () => {
    const client = new QueryClient();
    const cancel = vi.spyOn(client, "cancelQueries").mockResolvedValue();
    const invalidate = vi.spyOn(client, "invalidateQueries").mockResolvedValue();

    await expect(refreshRoomMembership(client, getSessionSnapshot())).resolves.toBe(true);
    expect(cancel.mock.calls.map(([filter]) => filter)).toEqual([{ queryKey: roomKeys.all }, { queryKey: homeKey }]);
    expect(invalidate.mock.calls.map(([filter]) => filter)).toEqual([{ queryKey: roomKeys.all }, { queryKey: homeKey }]);
    expect(cancel.mock.invocationCallOrder.at(-1)).toBeLessThan(invalidate.mock.invocationCallOrder[0]!);
  });

  test("omits correlation for cross-origin APIs and unavailable UUID generation", () => {
    const client = new QueryClient();
    expect(beginRoomMembershipEcho(client, 7, "room.member_added", "https://api.example.test", "https://app.example.test/rooms/7")).toBeUndefined();
    vi.stubGlobal("crypto", {});
    expect(beginRoomMembershipEcho(client, 7, "room.member_removed", "", "https://app.example.test/rooms/7")).toBeUndefined();
    vi.unstubAllGlobals();
  });

  test("never cancels the replacement session's reads", async () => {
    const client = new QueryClient();
    const session = getSessionSnapshot();
    clearSession();
    const cancel = vi.spyOn(client, "cancelQueries");

    await expect(refreshRoomMembership(client, session)).resolves.toBe(false);
    expect(cancel).not.toHaveBeenCalled();
  });

  test("does not invalidate when the session changes during cancellation", async () => {
    const client = new QueryClient();
    const session = getSessionSnapshot();
    vi.spyOn(client, "cancelQueries").mockImplementation(async () => clearSession());
    const invalidate = vi.spyOn(client, "invalidateQueries");

    await expect(refreshRoomMembership(client, session)).resolves.toBe(false);
    expect(invalidate).not.toHaveBeenCalled();
  });

  test("does not claim freshness while an active read is paused", async () => {
    onlineManager.setOnline(false);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const observer = new QueryObserver(client, { queryKey: ["rooms", "members", 7], queryFn: async () => [] });
    const unsubscribe = observer.subscribe(() => undefined);

    await expect(refreshRoomMembership(client, getSessionSnapshot())).resolves.toBe(false);
    unsubscribe();
  });

  test("a failed refresh releases the echo instead of suppressing it", async () => {
    const client = new QueryClient();
    vi.spyOn(client, "cancelQueries").mockRejectedValue(new Error("refresh failed"));
    const echo = { id: "id", confirm: vi.fn(), cancel: vi.fn() };

    await expect(settleRoomMembershipRefresh(client, getSessionSnapshot(), echo)).resolves.toBe(true);
    expect(echo.confirm).not.toHaveBeenCalled();
    expect(echo.cancel).toHaveBeenCalledOnce();
  });
});
