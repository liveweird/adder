import { onlineManager, QueryClient, QueryObserver } from "@tanstack/react-query";
import { act } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { jsonResponse } from "../test/http";
import { makeRoom, makeThread } from "../test/messageFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import { FakeClock } from "../test/socket";
import { createLiveInvalidator } from "../utils/liveKeys";
import { homeKey, roomKeys } from "../utils/roomKeys";
import ThreadLifecycleActions, { type LifecycleThread } from "./ThreadLifecycleActions";

const mutationId = "14d7ed05-24c1-4374-b6eb-60b147595822";
const source = (): LifecycleThread => ({ ...makeThread(), mergedIntoThreadNumber: null, mergedSourceCount: 0 });
const target = (): LifecycleThread => ({ ...source(), id: 7, number: 7, title: "Canonical" });
const refreshedKeys = [roomKeys.threads(12), roomKeys.workflow(12), roomKeys.workflowEdit(12), homeKey];

async function beginMerge(): Promise<void> {
  const user = userEvent.setup();
  await user.click(screen.getByRole("button", { name: "Thread actions" }));
  await user.click(screen.getByRole("menuitem", { name: "Merge duplicate" }));
  const dialog = await screen.findByRole("dialog", { name: "Merge duplicate thread" });
  await user.type(within(dialog).getByRole("textbox", { name: "Target thread number" }), "7");
  await user.click(await within(dialog).findByRole("button", { name: "Merge into #7" }));
}

describe("thread lifecycle merge echo", () => {
  let request: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    request = vi.fn();
    vi.stubGlobal("fetch", request);
    vi.spyOn(crypto, "randomUUID").mockReturnValue(mutationId);
    localStorage.setItem("adder.auth.token", "fake-token");
    localStorage.setItem("adder.auth.userId", "5");
    localStorage.setItem("adder.auth.roles", "[]");
  });

  afterEach(() => {
    onlineManager.setOnline(true);
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    localStorage.clear();
  });

  test("an early exact merge hint is consumed only after HTTP success and a fresh room read", async () => {
    let resolveMerge!: (response: Response) => void;
    request.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/rooms/12/threads/7") return Promise.resolve(jsonResponse(200, target()));
      if (url === "/api/v1/rooms/12/threads/42/merge" && init?.method === "PUT") {
        return new Promise<Response>((resolve) => { resolveMerge = resolve; });
      }
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const clock = new FakeClock();
    const invalidator = createLiveInvalidator(queryClient, clock);
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    const onMerged = vi.fn();
    renderWithProviders(
      <ThreadLifecycleActions room={makeRoom()} thread={source()} onDeleted={vi.fn()} onMerged={onMerged} />,
      { queryClient },
    );
    await beginMerge();
    const mergeCall = request.mock.calls.find(([url, init]) => url === "/api/v1/rooms/12/threads/42/merge" && init?.method === "PUT")!;
    expect(new Headers((mergeCall[1] as RequestInit).headers).get("X-Mutation-Id")).toBe(mutationId);

    invalidator.handle({ type: "event", kind: "thread.merged", roomId: 12, threadNumber: 42, mutationId });
    expect(invalidate).not.toHaveBeenCalled();
    resolveMerge(new Response(null, { status: 204 }));

    await waitFor(() => expect(onMerged).toHaveBeenCalledWith(7));
    expect(invalidate.mock.calls.map(([filters]) => filters?.queryKey)).toEqual(refreshedKeys);
    clock.advanceBy(5_000);
    expect(invalidate).toHaveBeenCalledTimes(refreshedKeys.length);
    invalidator.dispose();
  });

  test("a lost response releases the exact hint to ordinary live handling", async () => {
    let rejectMerge!: (reason: Error) => void;
    request.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/rooms/12/threads/7") return Promise.resolve(jsonResponse(200, target()));
      if (url === "/api/v1/rooms/12/threads/42/merge" && init?.method === "PUT") {
        return new Promise<Response>((_resolve, reject) => { rejectMerge = reject; });
      }
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const clock = new FakeClock();
    const invalidator = createLiveInvalidator(queryClient, clock);
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    const onMerged = vi.fn();
    renderWithProviders(
      <ThreadLifecycleActions room={makeRoom()} thread={source()} onDeleted={vi.fn()} onMerged={onMerged} />,
      { queryClient },
    );
    await beginMerge();
    invalidator.handle({ type: "event", kind: "thread.merged", roomId: 12, threadNumber: 42, mutationId });
    rejectMerge(new Error("response lost"));
    await waitFor(() => expect(within(screen.getByRole("dialog", { name: "Merge duplicate thread" }))
      .getByRole("button", { name: "Cancel" })).toBeEnabled());

    clock.advanceBy(300);
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: roomKeys.threads(12) }));
    expect(onMerged).not.toHaveBeenCalled();
    invalidator.dispose();
  });

  test("a completed merge from a replaced session cannot refresh or navigate the new session", async () => {
    let resolveMerge!: (response: Response) => void;
    request.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/rooms/12/threads/7") return Promise.resolve(jsonResponse(200, target()));
      if (url === "/api/v1/rooms/12/threads/42/merge" && init?.method === "PUT") {
        return new Promise<Response>((resolve) => { resolveMerge = resolve; });
      }
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const cancel = vi.spyOn(queryClient, "cancelQueries");
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    const onMerged = vi.fn();
    renderWithProviders(
      <ThreadLifecycleActions room={makeRoom()} thread={source()} onDeleted={vi.fn()} onMerged={onMerged} />,
      { queryClient },
    );
    await beginMerge();

    localStorage.setItem("adder.auth.sessionIdentity", "81bcad5d-18ab-4b70-bac7-37b549702d42");
    resolveMerge(new Response(null, { status: 204 }));

    await waitFor(() => expect(within(screen.getByRole("dialog", { name: "Merge duplicate thread" }))
      .getByRole("button", { name: "Cancel" })).toBeEnabled());
    expect(cancel).not.toHaveBeenCalled();
    expect(invalidate).not.toHaveBeenCalled();
    expect(onMerged).not.toHaveBeenCalled();
  });

  test("a pending first thread-list read is canceled before one authoritative post-merge read", async () => {
    request.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/rooms/12/threads/7") return Promise.resolve(jsonResponse(200, target()));
      if (url === "/api/v1/rooms/12/threads/42/merge" && init?.method === "PUT") {
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    let resolveOld!: (value: unknown) => void;
    const list = roomKeys.threadList(12);
    const listRead = vi.fn()
      .mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve; }))
      .mockResolvedValueOnce({ items: [] });
    const observer = new QueryObserver(queryClient, { queryKey: list, queryFn: listRead });
    const unsubscribe = observer.subscribe(() => undefined);
    const onMerged = vi.fn();
    renderWithProviders(
      <ThreadLifecycleActions room={makeRoom()} thread={source()} onDeleted={vi.fn()} onMerged={onMerged} />,
      { queryClient },
    );
    await vi.waitFor(() => expect(listRead).toHaveBeenCalledOnce());
    await beginMerge();

    await vi.waitFor(() => expect(listRead).toHaveBeenCalledTimes(2));
    resolveOld({ items: [{ number: 42, mergedIntoThreadNumber: null }] });
    await waitFor(() => expect(onMerged).toHaveBeenCalledWith(7));
    expect(queryClient.getQueryData(list)).toEqual({ items: [] });
    expect(listRead).toHaveBeenCalledTimes(2);
    unsubscribe();
  });

  test("a failed authoritative refresh completes the merge and replays the held hint", async () => {
    let resolveMerge!: (response: Response) => void;
    request.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/rooms/12/threads/7") return Promise.resolve(jsonResponse(200, target()));
      if (url === "/api/v1/rooms/12/threads/42/merge" && init?.method === "PUT") {
        return new Promise<Response>((resolve) => { resolveMerge = resolve; });
      }
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const clock = new FakeClock();
    const invalidator = createLiveInvalidator(queryClient, clock);
    const list = roomKeys.threadList(12);
    const listRead = vi.fn()
      .mockResolvedValueOnce({ items: [{ number: 42 }] })
      .mockRejectedValueOnce(new Error("refresh failed"))
      .mockResolvedValueOnce({ items: [{ number: 42, mergedIntoThreadNumber: 7 }] });
    const observer = new QueryObserver(queryClient, { queryKey: list, queryFn: listRead });
    const unsubscribe = observer.subscribe(() => undefined);
    const onMerged = vi.fn();
    renderWithProviders(
      <ThreadLifecycleActions room={makeRoom()} thread={source()} onDeleted={vi.fn()} onMerged={onMerged} />,
      { queryClient },
    );
    await vi.waitFor(() => expect(listRead).toHaveBeenCalledOnce());
    await beginMerge();
    invalidator.handle({ type: "event", kind: "thread.merged", roomId: 12, threadNumber: 42, mutationId });
    resolveMerge(new Response(null, { status: 204 }));

    await waitFor(() => expect(onMerged).toHaveBeenCalledWith(7));
    await vi.waitFor(() => expect(listRead).toHaveBeenCalledTimes(2));
    clock.advanceBy(300);
    await vi.waitFor(() => expect(listRead).toHaveBeenCalledTimes(3));
    await vi.waitFor(() => expect(queryClient.getQueryData(list)).toEqual({ items: [{ number: 42, mergedIntoThreadNumber: 7 }] }));
    unsubscribe();
    invalidator.dispose();
  });

  test("a paused authoritative refresh resumes once before consuming the held hint", async () => {
    let resolveMerge!: (response: Response) => void;
    request.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/rooms/12/threads/7") return Promise.resolve(jsonResponse(200, target()));
      if (url === "/api/v1/rooms/12/threads/42/merge" && init?.method === "PUT") {
        return new Promise<Response>((resolve) => { resolveMerge = resolve; });
      }
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    queryClient.mount();
    const clock = new FakeClock();
    const invalidator = createLiveInvalidator(queryClient, clock);
    const list = roomKeys.threadList(12);
    const listRead = vi.fn()
      .mockResolvedValueOnce({ items: [{ number: 42 }] })
      .mockResolvedValueOnce({ items: [{ number: 42, mergedIntoThreadNumber: 7 }] });
    const observer = new QueryObserver(queryClient, { queryKey: list, queryFn: listRead });
    const unsubscribe = observer.subscribe(() => undefined);
    const onMerged = vi.fn();
    renderWithProviders(
      <ThreadLifecycleActions room={makeRoom()} thread={source()} onDeleted={vi.fn()} onMerged={onMerged} />,
      { queryClient },
    );
    await vi.waitFor(() => expect(listRead).toHaveBeenCalledOnce());
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Thread actions" }));
    await user.click(screen.getByRole("menuitem", { name: "Merge duplicate" }));
    const dialog = await screen.findByRole("dialog", { name: "Merge duplicate thread" });
    await user.type(within(dialog).getByRole("textbox", { name: "Target thread number" }), "7");
    const confirm = await within(dialog).findByRole("button", { name: "Merge into #7" });
    await user.click(confirm);
    await vi.waitFor(() => expect(request.mock.calls.some(([url, init]) =>
      url === "/api/v1/rooms/12/threads/42/merge" && init?.method === "PUT")).toBe(true));
    onlineManager.setOnline(false);
    invalidator.handle({ type: "event", kind: "thread.merged", roomId: 12, threadNumber: 42, mutationId });
    await act(async () => { resolveMerge(new Response(null, { status: 204 })); });
    await vi.waitFor(() => expect(queryClient.getQueryState(list)?.fetchStatus).toBe("paused"));
    expect(onMerged).toHaveBeenCalledWith(7);
    clock.advanceBy(300);

    await act(async () => { onlineManager.setOnline(true); });
    await vi.waitFor(() => expect(queryClient.getQueryData(list)).toEqual({ items: [{ number: 42, mergedIntoThreadNumber: 7 }] }));
    expect(listRead).toHaveBeenCalledTimes(2);
    clock.advanceBy(5_000);
    expect(listRead).toHaveBeenCalledTimes(2);
    unsubscribe();
    queryClient.unmount();
    invalidator.dispose();
  });
});
