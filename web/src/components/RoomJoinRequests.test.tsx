import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryObserver } from "@tanstack/react-query";
import RoomJoinRequests from "./RoomJoinRequests";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import { FakeClock } from "../test/socket";
import { createLiveInvalidator } from "../utils/liveKeys";
import { clearSession } from "../api/session";
import { homeKey, roomKeys } from "../utils/roomKeys";

const request = (id: number) => ({ id, userId: 1000 + id, name: `Person ${id}`, requestedAt: 1_700_000_000_000 + id });

describe("RoomJoinRequests", () => {
  const mockFetch = vi.fn();

  beforeEach(() => {
    mockFetch.mockReset();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("deciding the last request on the last page returns to the last valid page", async () => {
    let decided = false;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (init?.method === "PUT") {
        decided = true;
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      const page = new URL(url, "http://example.test").searchParams.get("page");
      if (page === "2") {
        return Promise.resolve(jsonResponse(200, { items: decided ? [] : [request(21)], total: decided ? 20 : 21, page: 2, pageSize: 20 }));
      }
      return Promise.resolve(jsonResponse(200, { items: Array.from({ length: 20 }, (_, index) => request(index + 1)), total: decided ? 20 : 21, page: 1, pageSize: 20 }));
    });
    const user = userEvent.setup();
    renderWithProviders(<RoomJoinRequests roomId={12} />);

    expect(await screen.findByText("Person 1")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "2" }));
    expect(await screen.findByText("Person 21")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Decline Person 21" }));

    await waitFor(() => expect(screen.getByText("Person 1")).toBeInTheDocument());
    expect(screen.queryByText("No pending requests.")).not.toBeInTheDocument();
  });

  test("focuses the request queue when an async room route carries its anchor", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [], total: 0, page: 1, pageSize: 20 }));
    renderWithProviders(<RoomJoinRequests roomId={12} />, { route: "/rooms/12/members#room-join-requests-title" });

    const heading = await screen.findByRole("heading", { name: "Join requests" });
    await waitFor(() => expect(heading).toHaveFocus());
  });

  test("approval holds its exact early event and performs one authoritative refresh after HTTP success", async () => {
    let approved = false;
    let release: () => void = () => {};
    const held = new Promise<void>((resolve) => { release = resolve; });
    mockFetch.mockImplementation((_url: string, init?: RequestInit) => {
      if (init?.method === "PUT") return held.then(() => {
        approved = true;
        return new Response(null, { status: 204 });
      });
      return Promise.resolve(jsonResponse(200, {
        items: approved ? [] : [request(1)], total: approved ? 0 : 1, page: 1, pageSize: 20,
      }));
    });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const clock = new FakeClock();
    const invalidator = createLiveInvalidator(queryClient, clock);
    const user = userEvent.setup();
    renderWithProviders(<RoomJoinRequests roomId={12} />, { queryClient });
    await user.click(await screen.findByRole("button", { name: "Approve Person 1" }));
    const put = mockFetch.mock.calls.find(([, init]) => init?.method === "PUT")!;
    const mutationId = new Headers(put[1].headers).get("X-Mutation-Id")!;

    invalidator.handle({ type: "event", kind: "room.member_added", roomId: 12, mutationId });
    clock.advanceBy(300);
    expect(mockFetch.mock.calls.filter(([, init]) => !init?.method)).toHaveLength(1);

    release();
    await waitFor(() => expect(screen.getByText("No pending requests")).toBeInTheDocument());
    expect(mockFetch.mock.calls.filter(([, init]) => !init?.method)).toHaveLength(2);
    clock.advanceBy(5_000);
    expect(mockFetch.mock.calls.filter(([, init]) => !init?.method)).toHaveLength(2);
    invalidator.dispose();
  });

  test("a held exact event is released when the approval response is lost", async () => {
    let reject: (error: Error) => void = () => {};
    mockFetch.mockImplementation((_url: string, init?: RequestInit) => init?.method === "PUT"
      ? new Promise<Response>((_resolve, rejectRequest) => { reject = rejectRequest; })
      : Promise.resolve(jsonResponse(200, { items: [request(1)], total: 1, page: 1, pageSize: 20 })));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const clock = new FakeClock();
    const invalidator = createLiveInvalidator(queryClient, clock);
    const user = userEvent.setup();
    renderWithProviders(<RoomJoinRequests roomId={12} />, { queryClient });
    await user.click(await screen.findByRole("button", { name: "Approve Person 1" }));
    const put = mockFetch.mock.calls.find(([, init]) => init?.method === "PUT")!;
    const mutationId = new Headers(put[1].headers).get("X-Mutation-Id")!;
    invalidator.handle({ type: "event", kind: "room.member_added", roomId: 12, mutationId });
    clock.advanceBy(300);
    expect(mockFetch.mock.calls.filter(([, init]) => !init?.method)).toHaveLength(1);

    reject(new Error("response lost"));
    expect(await screen.findByRole("alert")).toHaveTextContent("Could not review the request");
    clock.advanceBy(300);
    await waitFor(() => expect(mockFetch.mock.calls.filter(([, init]) => !init?.method)).toHaveLength(2));
    invalidator.dispose();
  });

  test("rejection stays uncorrelated and refreshes its queue", async () => {
    let rejected = false;
    mockFetch.mockImplementation((_url: string, init?: RequestInit) => {
      if (init?.method === "PUT") {
        rejected = true;
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      return Promise.resolve(jsonResponse(200, {
        items: rejected ? [] : [request(1)], total: rejected ? 0 : 1, page: 1, pageSize: 20,
      }));
    });
    const user = userEvent.setup();
    renderWithProviders(<RoomJoinRequests roomId={12} />);
    await user.click(await screen.findByRole("button", { name: "Decline Person 1" }));
    const put = mockFetch.mock.calls.find(([, init]) => init?.method === "PUT")!;
    expect(new Headers(put[1].headers).get("X-Mutation-Id")).toBeNull();
    expect(await screen.findByText("No pending requests")).toBeInTheDocument();
  });

  test("approval refresh continues after the owner controls unmount", async () => {
    let release: () => void = () => {};
    mockFetch.mockImplementation((_url: string, init?: RequestInit) => init?.method === "PUT"
      ? new Promise<Response>((resolve) => { release = () => resolve(new Response(null, { status: 204 })); })
      : Promise.resolve(jsonResponse(200, { items: [request(1)], total: 1, page: 1, pageSize: 20 })));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    const user = userEvent.setup();
    const view = renderWithProviders(<RoomJoinRequests roomId={12} />, { queryClient });
    await user.click(await screen.findByRole("button", { name: "Approve Person 1" }));
    view.unmount();
    release();

    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: roomKeys.all }));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: homeKey });
  });

  test("rejection refresh continues after unmount and makes an active Home projection fresh", async () => {
    let release: () => void = () => {};
    mockFetch.mockImplementation((_url: string, init?: RequestInit) => init?.method === "PUT"
      ? new Promise<Response>((resolve) => { release = () => resolve(new Response(null, { status: 204 })); })
      : Promise.resolve(jsonResponse(200, { items: [request(1)], total: 1, page: 1, pageSize: 20 })));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const staleHome = { pendingJoinRequests: 1 };
    const freshHome = { pendingJoinRequests: 0 };
    queryClient.setQueryData(homeKey, staleHome);
    const readHome = vi.fn().mockResolvedValue(freshHome);
    const home = new QueryObserver(queryClient, { queryKey: homeKey, queryFn: readHome, staleTime: Infinity });
    const detachHome = home.subscribe(() => undefined);
    const user = userEvent.setup();
    const view = renderWithProviders(<RoomJoinRequests roomId={12} />, { queryClient });
    await user.click(await screen.findByRole("button", { name: "Decline Person 1" }));
    view.unmount();
    release();

    await waitFor(() => expect(readHome).toHaveBeenCalledOnce());
    expect(queryClient.getQueryData(homeKey)).toEqual(freshHome);
    expect(queryClient.getQueryState(homeKey)?.isInvalidated).toBe(false);
    detachHome();
  });

  test("rejection from an old logical session cannot refresh replacement caches", async () => {
    let release: () => void = () => {};
    mockFetch.mockImplementation((_url: string, init?: RequestInit) => init?.method === "PUT"
      ? new Promise<Response>((resolve) => { release = () => resolve(new Response(null, { status: 204 })); })
      : Promise.resolve(jsonResponse(200, { items: [request(1)], total: 1, page: 1, pageSize: 20 })));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    const user = userEvent.setup();
    renderWithProviders(<RoomJoinRequests roomId={12} />, { queryClient });
    await user.click(await screen.findByRole("button", { name: "Decline Person 1" }));
    clearSession();
    localStorage.setItem("adder.auth.token", "replacement-token");
    queryClient.setQueryData(["rooms", "replacement"], { private: "new session" });
    release();

    await waitFor(() => expect(screen.getByRole("button", { name: "Decline Person 1" })).toBeEnabled());
    expect(invalidate).not.toHaveBeenCalled();
    expect(queryClient.getQueryData(["rooms", "replacement"])).toEqual({ private: "new session" });
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  test("approval from an old logical session cannot refresh or overwrite replacement caches", async () => {
    let release: () => void = () => {};
    mockFetch.mockImplementation((_url: string, init?: RequestInit) => init?.method === "PUT"
      ? new Promise<Response>((resolve) => { release = () => resolve(new Response(null, { status: 204 })); })
      : Promise.resolve(jsonResponse(200, { items: [request(1)], total: 1, page: 1, pageSize: 20 })));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    const user = userEvent.setup();
    renderWithProviders(<RoomJoinRequests roomId={12} />, { queryClient });
    await user.click(await screen.findByRole("button", { name: "Approve Person 1" }));
    clearSession();
    localStorage.setItem("adder.auth.token", "replacement-token");
    queryClient.setQueryData(["rooms", "replacement"], { private: "new session" });
    release();

    await waitFor(() => expect(screen.getByRole("button", { name: "Approve Person 1" })).toBeEnabled());
    expect(invalidate).not.toHaveBeenCalled();
    expect(queryClient.getQueryData(["rooms", "replacement"])).toEqual({ private: "new session" });
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
