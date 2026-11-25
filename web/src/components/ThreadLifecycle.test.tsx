import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import { jsonResponse } from "../test/http";
import { makeRoom, makeThread } from "../test/messageFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import { homeKey, roomKeys } from "../utils/roomKeys";
import ThreadLifecycleActions, { type LifecycleThread } from "./ThreadLifecycleActions";
import MergedThreadBanner from "./MergedThreadBanner";
import MergedThreadSources from "./MergedThreadSources";

const thread = (values: Partial<LifecycleThread> = {}): LifecycleThread => ({
  ...makeThread(),
  mergedIntoThreadNumber: null,
  mergedSourceCount: 0,
  ...values,
});

describe("thread lifecycle", () => {
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

  test("only a steward in an active room sees lifecycle actions, and a merged source can only be deleted", async () => {
    const user = userEvent.setup();
    const { rerender } = renderWithProviders(
      <ThreadLifecycleActions room={makeRoom()} thread={thread()} onDeleted={vi.fn()} onMerged={vi.fn()} />,
    );
    await user.click(screen.getByRole("button", { name: "Thread actions" }));
    expect(screen.getByRole("menuitem", { name: "Merge duplicate" })).toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: "Delete thread" })).toBeInTheDocument();
    await user.keyboard("{Escape}");

    rerender(<ThreadLifecycleActions room={makeRoom()} thread={thread({ mergedIntoThreadNumber: 8 })} onDeleted={vi.fn()} onMerged={vi.fn()} />);
    await user.click(screen.getByRole("button", { name: "Thread actions" }));
    expect(screen.queryByRole("menuitem", { name: "Merge duplicate" })).not.toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: "Delete thread" })).toBeInTheDocument();

    rerender(<ThreadLifecycleActions room={makeRoom({ archived: true })} thread={thread()} onDeleted={vi.fn()} onMerged={vi.fn()} />);
    expect(screen.queryByRole("button", { name: "Thread actions" })).not.toBeInTheDocument();
    localStorage.setItem("adder.auth.userId", "6");
    rerender(<ThreadLifecycleActions room={makeRoom()} thread={thread()} onDeleted={vi.fn()} onMerged={vi.fn()} />);
    expect(screen.queryByRole("button", { name: "Thread actions" })).not.toBeInTheDocument();
  });

  test("merge explains the result, verifies the target, and sends its number", async () => {
    const user = userEvent.setup();
    const onMerged = vi.fn();
    request.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/rooms/12/threads/7") return Promise.resolve(jsonResponse(200, thread({ number: 7, title: "Canonical incident" })));
      if (url === "/api/v1/rooms/12/threads/42/merge" && init?.method === "PUT") return Promise.resolve(new Response(null, { status: 204 }));
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
    renderWithProviders(<ThreadLifecycleActions room={makeRoom()} thread={thread()} onDeleted={vi.fn()} onMerged={onMerged} />);

    await user.click(screen.getByRole("button", { name: "Thread actions" }));
    await user.click(screen.getByRole("menuitem", { name: "Merge duplicate" }));
    const dialog = await screen.findByRole("dialog", { name: "Merge duplicate thread" });
    expect(dialog).toHaveTextContent("The history of #42 stays readable");
    expect(dialog).toHaveTextContent("New replies belong in the target thread");
    expect(dialog).toHaveTextContent("The target keeps its status, owner and notification settings");
    expect(dialog).toHaveTextContent("Watches from #42 do not transfer");
    await user.type(within(dialog).getByRole("textbox", { name: "Target thread number" }), "7");
    expect(await within(dialog).findByText("#7 Canonical incident")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Merge into #7" }));
    await waitFor(() => expect(onMerged).toHaveBeenCalledWith(7));
    const merge = request.mock.calls.find(([url, init]) => url === "/api/v1/rooms/12/threads/42/merge" && (init as RequestInit).method === "PUT");
    expect(JSON.parse((merge?.[1] as RequestInit).body as string)).toEqual({ targetNumber: 7 });
  });

  test("a caller who does not steward the target cannot confirm a merge", async () => {
    const user = userEvent.setup();
    request.mockResolvedValue(jsonResponse(200, thread({ number: 7, ownerId: 99, title: "Someone else's thread" })));
    renderWithProviders(
      <ThreadLifecycleActions room={makeRoom()} thread={thread({ mergedSourceCount: undefined })} onDeleted={vi.fn()} onMerged={vi.fn()} />,
    );
    await user.click(screen.getByRole("button", { name: "Thread actions" }));
    await user.click(screen.getByRole("menuitem", { name: "Merge duplicate" }));
    const dialog = await screen.findByRole("dialog", { name: "Merge duplicate thread" });
    await user.type(within(dialog).getByRole("textbox", { name: "Target thread number" }), "7");
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("You must own both threads");
    expect(within(dialog).getByRole("button", { name: "Merge threads" })).toBeDisabled();
  });

  test("the merge target must be distinct, readable and canonical", async () => {
    const user = userEvent.setup();
    request.mockImplementation((url: string) => Promise.resolve(
      url.endsWith("/7")
        ? jsonResponse(200, thread({ number: 7, mergedIntoThreadNumber: 9 }))
        : jsonResponse(404, { title: "Not found", status: 404 }),
    ));
    renderWithProviders(<ThreadLifecycleActions room={makeRoom()} thread={thread()} onDeleted={vi.fn()} onMerged={vi.fn()} />);
    await user.click(screen.getByRole("button", { name: "Thread actions" }));
    await user.click(screen.getByRole("menuitem", { name: "Merge duplicate" }));
    const dialog = await screen.findByRole("dialog", { name: "Merge duplicate thread" });
    const target = within(dialog).getByRole("textbox", { name: "Target thread number" });

    await user.type(target, "42");
    expect(within(dialog).getByRole("alert")).toHaveTextContent("Choose another thread");
    expect(request.mock.calls.some(([url]) => String(url).endsWith("/42"))).toBe(false);

    await user.clear(target);
    await user.type(target, "7");
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("already been merged");

    await user.clear(target);
    await user.type(target, "8");
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("No readable thread");
  });

  test("a refused merge keeps the dialog open, can be retried, and clears its error when cancelled", async () => {
    const user = userEvent.setup();
    const onMerged = vi.fn();
    let refused = true;
    request.mockImplementation((url: string, init?: RequestInit) => {
      if (url.endsWith("/7") && init?.method === undefined) {
        return Promise.resolve(jsonResponse(200, thread({ number: 7, title: "Canonical incident" })));
      }
      if (url.endsWith("/42/merge")) {
        return Promise.resolve(refused
          ? jsonResponse(409, { title: "Conflict", status: 409 })
          : new Response(null, { status: 204 }));
      }
      throw new Error(`Unexpected request: ${url}`);
    });
    renderWithProviders(<ThreadLifecycleActions room={makeRoom()} thread={thread()} onDeleted={vi.fn()} onMerged={onMerged} />);
    await user.click(screen.getByRole("button", { name: "Thread actions" }));
    await user.click(screen.getByRole("menuitem", { name: "Merge duplicate" }));
    const dialog = await screen.findByRole("dialog", { name: "Merge duplicate thread" });
    await user.type(within(dialog).getByRole("textbox", { name: "Target thread number" }), "7");
    await user.click(await within(dialog).findByRole("button", { name: "Merge into #7" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("One of these threads changed");
    expect(onMerged).not.toHaveBeenCalled();

    refused = false;
    await user.click(within(dialog).getByRole("button", { name: "Merge into #7" }));
    await waitFor(() => expect(onMerged).toHaveBeenCalledWith(7));
  });

  test("cancelling lifecycle dialogs resets their draft and refusal state", async () => {
    const user = userEvent.setup();
    request.mockImplementation((url: string, init?: RequestInit) => {
      if (url.endsWith("/7") && init?.method === undefined) return Promise.resolve(jsonResponse(200, thread({ number: 7 })));
      return Promise.resolve(jsonResponse(403, { title: "Forbidden", status: 403 }));
    });
    renderWithProviders(<ThreadLifecycleActions room={makeRoom()} thread={thread()} onDeleted={vi.fn()} onMerged={vi.fn()} />);
    await user.click(screen.getByRole("button", { name: "Thread actions" }));
    await user.click(screen.getByRole("menuitem", { name: "Merge duplicate" }));
    let dialog = await screen.findByRole("dialog", { name: "Merge duplicate thread" });
    await user.type(within(dialog).getByRole("textbox", { name: "Target thread number" }), "7");
    await user.click(await within(dialog).findByRole("button", { name: "Merge into #7" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("no longer have permission");
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));

    await user.click(screen.getByRole("button", { name: "Thread actions" }));
    await user.click(screen.getByRole("menuitem", { name: "Merge duplicate" }));
    dialog = await screen.findByRole("dialog", { name: "Merge duplicate thread" });
    expect(within(dialog).getByRole("textbox", { name: "Target thread number" })).toHaveValue("");
    expect(within(dialog).queryByRole("alert")).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));

    await user.click(screen.getByRole("button", { name: "Thread actions" }));
    await user.click(screen.getByRole("menuitem", { name: "Delete thread" }));
    dialog = await screen.findByRole("dialog", { name: "Delete #42?" });
    expect(dialog).toHaveTextContent("entire conversation history");
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog", { name: "Delete #42?" })).not.toBeInTheDocument();
  });

  test("deleting a target warns that its merged conversations are deleted too", async () => {
    const user = userEvent.setup();
    const onDeleted = vi.fn();
    request.mockResolvedValue(new Response(null, { status: 204 }));
    renderWithProviders(
      <ThreadLifecycleActions room={makeRoom()} thread={thread({ mergedSourceCount: 3 })} onDeleted={onDeleted} onMerged={vi.fn()} />,
    );
    await user.click(screen.getByRole("button", { name: "Thread actions" }));
    await user.click(screen.getByRole("menuitem", { name: "Delete thread" }));
    const dialog = await screen.findByRole("dialog", { name: "Delete #42?" });
    expect(dialog).toHaveTextContent("also permanently deletes its 3 merged conversations");
    await user.click(within(dialog).getByRole("button", { name: "Delete thread" }));
    await waitFor(() => expect(onDeleted).toHaveBeenCalledOnce());
    expect(request).toHaveBeenCalledWith("/api/v1/rooms/12/threads/42", expect.objectContaining({ method: "DELETE" }));
  });

  test("deletion clears cached room content and relevant searches without disturbing another room", async () => {
    const user = userEvent.setup();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const globalSearch = ["search", "disk", "all", 1] as const;
    const roomSearch = ["search", "room", 12, "disk", "all", 1] as const;
    const otherRoomSearch = ["search", "room", 13, "disk", "all", 1] as const;
    const mergedSource = roomKeys.threadDetail(12, 8);
    const otherRoomDetail = roomKeys.threadDetail(13, 8);
    queryClient.setQueryData(globalSearch, { result: "global hit" });
    queryClient.setQueryData(roomSearch, { result: "room hit" });
    queryClient.setQueryData(otherRoomSearch, { result: "other room hit" });
    queryClient.setQueryData(mergedSource, thread({ number: 8, mergedIntoThreadNumber: 42 }));
    queryClient.setQueryData(otherRoomDetail, thread({ number: 8 }));
    request.mockResolvedValue(new Response(null, { status: 204 }));
    renderWithProviders(
      <ThreadLifecycleActions room={makeRoom()} thread={thread()} onDeleted={vi.fn()} onMerged={vi.fn()} />,
      { queryClient },
    );
    await user.click(screen.getByRole("button", { name: "Thread actions" }));
    await user.click(screen.getByRole("menuitem", { name: "Delete thread" }));
    await user.click(within(await screen.findByRole("dialog", { name: "Delete #42?" })).getByRole("button", { name: "Delete thread" }));

    await waitFor(() => expect(queryClient.getQueryData(mergedSource)).toBeUndefined());
    expect(queryClient.getQueryData(globalSearch)).toBeUndefined();
    expect(queryClient.getQueryData(roomSearch)).toBeUndefined();
    expect(queryClient.getQueryData(otherRoomSearch)).toEqual({ result: "other room hit" });
    expect(queryClient.getQueryData(otherRoomDetail)).toEqual(thread({ number: 8 }));
  });

  test("merge invalidates thread descendants, workflow editors and Home while preserving another room", async () => {
    const user = userEvent.setup();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const sourceMessages = roomKeys.messages(12, 42);
    const workflow = roomKeys.workflow(12);
    const workflowEdit = roomKeys.workflowEdit(12);
    const otherRoomMessages = roomKeys.messages(13, 42);
    for (const key of [sourceMessages, workflow, workflowEdit, homeKey, otherRoomMessages]) {
      queryClient.setQueryData(key, { cached: true });
    }
    request.mockImplementation((url: string, init?: RequestInit) => {
      if (url.endsWith("/7") && init?.method === undefined) return Promise.resolve(jsonResponse(200, thread({ number: 7 })));
      return Promise.resolve(new Response(null, { status: 204 }));
    });
    renderWithProviders(
      <ThreadLifecycleActions room={makeRoom()} thread={thread()} onDeleted={vi.fn()} onMerged={vi.fn()} />,
      { queryClient },
    );
    await user.click(screen.getByRole("button", { name: "Thread actions" }));
    await user.click(screen.getByRole("menuitem", { name: "Merge duplicate" }));
    const dialog = await screen.findByRole("dialog", { name: "Merge duplicate thread" });
    await user.type(within(dialog).getByRole("textbox", { name: "Target thread number" }), "7");
    await user.click(await within(dialog).findByRole("button", { name: "Merge into #7" }));

    await waitFor(() => expect(queryClient.getQueryState(sourceMessages)?.isInvalidated).toBe(true));
    expect(queryClient.getQueryState(workflow)?.isInvalidated).toBe(true);
    expect(queryClient.getQueryState(workflowEdit)?.isInvalidated).toBe(true);
    expect(queryClient.getQueryState(homeKey)?.isInvalidated).toBe(true);
    expect(queryClient.getQueryState(otherRoomMessages)?.isInvalidated).toBe(false);
  });

  test("a refused deletion stays actionable and succeeds when retried", async () => {
    const user = userEvent.setup();
    const onDeleted = vi.fn();
    let refused = true;
    request.mockImplementation(() => Promise.resolve(refused
      ? jsonResponse(403, { title: "Forbidden", status: 403 })
      : new Response(null, { status: 204 })));
    renderWithProviders(<ThreadLifecycleActions room={makeRoom()} thread={thread()} onDeleted={onDeleted} onMerged={vi.fn()} />);
    await user.click(screen.getByRole("button", { name: "Thread actions" }));
    await user.click(screen.getByRole("menuitem", { name: "Delete thread" }));
    const dialog = await screen.findByRole("dialog", { name: "Delete #42?" });
    await user.click(within(dialog).getByRole("button", { name: "Delete thread" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("no longer have permission");
    expect(onDeleted).not.toHaveBeenCalled();

    refused = false;
    await user.click(within(dialog).getByRole("button", { name: "Delete thread" }));
    await waitFor(() => expect(onDeleted).toHaveBeenCalledOnce());
  });

  test("an in-flight merge cannot be dismissed before its outcome is known", async () => {
    const user = userEvent.setup();
    let resolveMerge!: (response: Response) => void;
    request.mockImplementation((url: string, init?: RequestInit) => {
      if (url.endsWith("/7") && init?.method === undefined) {
        return Promise.resolve(jsonResponse(200, thread({ number: 7, title: "Canonical" })));
      }
      return new Promise<Response>((resolve) => { resolveMerge = resolve; });
    });
    renderWithProviders(<ThreadLifecycleActions room={makeRoom()} thread={thread()} onDeleted={vi.fn()} onMerged={vi.fn()} />);
    await user.click(screen.getByRole("button", { name: "Thread actions" }));
    await user.click(screen.getByRole("menuitem", { name: "Merge duplicate" }));
    const dialog = await screen.findByRole("dialog", { name: "Merge duplicate thread" });
    await user.type(within(dialog).getByRole("textbox", { name: "Target thread number" }), "7");
    await user.click(await within(dialog).findByRole("button", { name: "Merge into #7" }));
    await waitFor(() => expect(within(dialog).getByRole("button", { name: "Cancel" })).toBeDisabled());
    await user.keyboard("{Escape}");
    expect(screen.getByRole("dialog", { name: "Merge duplicate thread" })).toBeInTheDocument();
    resolveMerge(new Response(null, { status: 204 }));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Merge duplicate thread" })).not.toBeInTheDocument());
  });

  test("an in-flight deletion cannot be dismissed before its outcome is known", async () => {
    const user = userEvent.setup();
    let resolveDelete!: (response: Response) => void;
    request.mockReturnValue(new Promise<Response>((resolve) => { resolveDelete = resolve; }));
    renderWithProviders(<ThreadLifecycleActions room={makeRoom()} thread={thread()} onDeleted={vi.fn()} onMerged={vi.fn()} />);
    await user.click(screen.getByRole("button", { name: "Thread actions" }));
    await user.click(screen.getByRole("menuitem", { name: "Delete thread" }));
    const dialog = await screen.findByRole("dialog", { name: "Delete #42?" });
    await user.click(within(dialog).getByRole("button", { name: "Delete thread" }));
    await waitFor(() => expect(within(dialog).getByRole("button", { name: "Cancel" })).toBeDisabled());
    await user.keyboard("{Escape}");
    expect(screen.getByRole("dialog", { name: "Delete #42?" })).toBeInTheDocument();
    resolveDelete(new Response(null, { status: 204 }));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "Delete #42?" })).not.toBeInTheDocument());
  });

  test("a merged source links to its target while preserving thread-list parameters", () => {
    renderWithProviders(<MergedThreadBanner roomId={12} targetNumber={7} />, {
      route: "/rooms/12/threads/42?scope=closed&order=number&page=2&message=9",
    });
    expect(screen.getByRole("link", { name: "Open #7" })).toHaveAttribute(
      "href",
      "/rooms/12/threads/7?scope=closed&order=number&page=2",
    );
  });

  test("the target opens a paged list of merged histories with list parameters preserved", async () => {
    const user = userEvent.setup();
    request.mockResolvedValue(jsonResponse(200, {
      items: [thread({ number: 8, title: "Duplicate alert" })], page: 1, pageSize: 20, total: 1,
    }));
    renderWithProviders(<MergedThreadSources roomId={12} targetNumber={42} count={1} />, {
      route: "/rooms/12/threads/42?scope=all&order=number&page=3",
    });
    await user.click(screen.getByRole("button", { name: "Merged conversations (1)" }));
    const dialog = await screen.findByRole("dialog", { name: "Merged conversations" });
    expect(await within(dialog).findByRole("link", { name: "#8 Duplicate alert" })).toHaveAttribute(
      "href",
      "/rooms/12/threads/8?scope=all&order=number&page=3",
    );
    expect(request.mock.calls[0][0]).toBe("/api/v1/rooms/12/threads?page=1&pageSize=20&sort=number&mergedInto=42");
  });

  test("merged histories hide at zero, page through results, report failures and reset to page one after closing", async () => {
    const user = userEvent.setup();
    const firstPage = {
      items: [thread({ number: 8, title: "First duplicate" })], page: 1, pageSize: 20, total: 21,
    };
    request.mockImplementation((url: string) => Promise.resolve(
      url.includes("page=2")
        ? jsonResponse(500, { title: "Boom", status: 500 })
        : jsonResponse(200, firstPage),
    ));
    const { rerender } = renderWithProviders(<MergedThreadSources roomId={12} targetNumber={42} count={0} />);
    expect(screen.queryByRole("button", { name: /Merged conversations/ })).not.toBeInTheDocument();

    rerender(<MergedThreadSources roomId={12} targetNumber={42} count={21} />);
    await user.click(screen.getByRole("button", { name: "Merged conversations (21)" }));
    const dialog = await screen.findByRole("dialog", { name: "Merged conversations" });
    expect(await within(dialog).findByRole("link", { name: "#8 First duplicate" })).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Next page" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("Merged conversations could not be loaded");
    await user.click(within(dialog).getByRole("button", { name: "Close" }));

    await user.click(screen.getByRole("button", { name: "Merged conversations (21)" }));
    expect(await screen.findByRole("link", { name: "#8 First duplicate" })).toBeInTheDocument();
    const urls = request.mock.calls.map(([url]) => String(url));
    expect(urls.filter((url) => url.includes("page=1"))).toHaveLength(2);
    expect(urls.filter((url) => url.includes("page=2"))).toHaveLength(1);
  });

  test("an empty merged-history page explains that no sources remain", async () => {
    const user = userEvent.setup();
    request.mockResolvedValue(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
    renderWithProviders(<MergedThreadSources roomId={12} targetNumber={42} count={1} />);
    await user.click(screen.getByRole("button", { name: "Merged conversations (1)" }));
    expect(await screen.findByText("No merged conversations remain.")).toBeInTheDocument();
  });
});
