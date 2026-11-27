import { QueryClient } from "@tanstack/react-query";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { makeRoom, makeThread } from "../test/messageFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import { FakeClock } from "../test/socket";
import { createLiveInvalidator } from "../utils/liveKeys";
import ThreadLifecycleActions, { type LifecycleThread } from "./ThreadLifecycleActions";

const thread = (): LifecycleThread => ({ ...makeThread(), mergedIntoThreadNumber: null, mergedSourceCount: 0 });

async function confirmDelete(): Promise<void> {
  const user = userEvent.setup();
  await user.click(screen.getByRole("button", { name: "Thread actions" }));
  await user.click(screen.getByRole("menuitem", { name: "Delete thread" }));
  await user.click(within(await screen.findByRole("dialog", { name: "Delete #42?" })).getByRole("button", { name: "Delete thread" }));
}

describe("thread lifecycle delete commit", () => {
  let request: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    request = vi.fn();
    vi.stubGlobal("fetch", request);
    localStorage.setItem("adder.auth.token", "fake-token");
    localStorage.setItem("adder.auth.userId", "5");
    localStorage.setItem("adder.auth.roles", "[]");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("an exact live deletion makes a lost HTTP response succeed once", async () => {
    let rejectDelete!: (reason: Error) => void;
    request.mockReturnValue(new Promise<Response>((_resolve, reject) => { rejectDelete = reject; }));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidator = createLiveInvalidator(queryClient, new FakeClock());
    const onDeleted = vi.fn();
    renderWithProviders(
      <ThreadLifecycleActions room={makeRoom()} thread={thread()} onDeleted={onDeleted} onMerged={vi.fn()} />,
      { queryClient },
    );
    await confirmDelete();
    await waitFor(() => expect(request).toHaveBeenCalledOnce());

    invalidator.handle({ type: "event", kind: "thread.deleted", roomId: 12, threadNumber: 42 });
    rejectDelete(new Error("response lost"));
    await waitFor(() => expect(onDeleted).toHaveBeenCalledOnce());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    invalidator.dispose();
  });

  test("a deletion completed for an old session cannot run its success callback", async () => {
    let resolveDelete!: (response: Response) => void;
    request.mockReturnValue(new Promise<Response>((resolve) => { resolveDelete = resolve; }));
    const onDeleted = vi.fn();
    renderWithProviders(
      <ThreadLifecycleActions room={makeRoom()} thread={thread()} onDeleted={onDeleted} onMerged={vi.fn()} />,
    );
    await confirmDelete();
    await waitFor(() => expect(request).toHaveBeenCalledOnce());

    localStorage.setItem("adder.auth.sessionIdentity", crypto.randomUUID());
    resolveDelete(new Response(null, { status: 204 }));
    await waitFor(() => expect(within(screen.getByRole("dialog", { name: "Delete #42?" }))
      .getByRole("button", { name: "Cancel" })).toBeEnabled());
    expect(onDeleted).not.toHaveBeenCalled();
  });
});
