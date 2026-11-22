import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import ThreadStatusControl from "./ThreadStatusControl";
import { jsonResponse } from "../test/http";
import { makeRoom, makeThread } from "../test/messageFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import { SIDE_WORKFLOW } from "../test/sideFixtures";

const STATUSES = SIDE_WORKFLOW.statuses;

describe("ThreadStatusControl", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  const puts = () => mockFetch.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === "PUT").map(([url, init]) => [url, JSON.parse((init as RequestInit).body as string)]);

  function respond(status: number, body?: unknown) {
    mockFetch.mockImplementation((_url: string, init?: RequestInit) =>
      Promise.resolve(init?.method === "PUT" ? (status === 204 ? new Response(null, { status }) : jsonResponse(status, body)) : jsonResponse(200, {})),
    );
  }
  function renderControl(opts: { steward: boolean; thread?: Partial<ReturnType<typeof makeThread>>; room?: Partial<ReturnType<typeof makeRoom>>; queryClient?: QueryClient }) {
    localStorage.setItem("adder.auth.userId", opts.steward ? "5" : "6");
    return renderWithProviders(
      <ThreadStatusControl room={makeRoom(opts.room)} thread={makeThread({ number: 42, ...opts.thread })} statuses={STATUSES} />,
      { queryClient: opts.queryClient },
    );
  }
  const current = () => screen.getByRole("status", { name: "Thread status" });
  const action = (name: string) => screen.getByRole("button", { name });
  const openMenu = async (user: ReturnType<typeof userEvent.setup>) => {
    await user.click(action("Change status"));
    return screen.findByRole("menu", { name: "Change status" });
  };
  const pick = async (user: ReturnType<typeof userEvent.setup>, name: string) => {
    await user.click(within(await openMenu(user)).getByRole("menuitem", { name }));
  };

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("shows the current status separately and labels each available move by its effect", async () => {
    const user = userEvent.setup();
    respond(204);
    renderControl({ steward: true });
    expect(current()).toHaveTextContent("Triage");
    const menu = await openMenu(user);
    expect(within(menu).getAllByRole("menuitem").map((item) => item.textContent)).toEqual([
      "Move to In progress", "Close as Done", "Close as Won't do",
    ]);
  });

  test("a plain member gets one direct OPEN move and no close actions", async () => {
    const user = userEvent.setup();
    respond(204);
    renderControl({ steward: false });
    expect(screen.queryByRole("button", { name: "Change status" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Close as/ })).not.toBeInTheDocument();
    await user.click(action("Move to In progress"));
    await waitFor(() => expect(puts()).toEqual([["/api/v1/rooms/12/threads/42/status", { status: "working" }]]));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  test("a successful move re-reads the room's threads, the rail and the workflow's counts, not the whole prefix", async () => {
    const user = userEvent.setup();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    respond(204);
    renderControl({ steward: false, queryClient });
    await user.click(action("Move to In progress"));
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms", "workflow", 12] }));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms", "threads", 12] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms", "rail"] });
    expect(invalidate).not.toHaveBeenCalledWith({ queryKey: ["rooms"] });
  });

  test("a steward closing an open thread is asked first; confirming sends the move, cancelling sends nothing", async () => {
    const user = userEvent.setup();
    respond(204);
    renderControl({ steward: true });
    await pick(user, "Close as Done");
    const dialog = await screen.findByRole("dialog", { name: "Close this thread?" });
    expect(dialog).toHaveTextContent("Moving #42 to Done closes it");
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(puts()).toEqual([]);
    await pick(user, "Close as Done");
    await user.click(within(await screen.findByRole("dialog", { name: "Close this thread?" })).getByRole("button", { name: "Close thread" }));
    await waitFor(() => expect(puts()).toEqual([["/api/v1/rooms/12/threads/42/status", { status: "done" }]]));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  test.each([
    ["Reopen as Triage", "triage"],
    ["Move to Won't do", "wontdo"],
  ])("a steward can choose %s immediately on a closed thread", async (name, status) => {
    const user = userEvent.setup();
    respond(204);
    renderControl({ steward: true, thread: { status: "done", closed: true } });
    await pick(user, name);
    await waitFor(() => expect(puts()).toEqual([["/api/v1/rooms/12/threads/42/status", { status }]]));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  test("a closed thread shows a plain member the current status and no actions", () => {
    respond(204);
    renderControl({ steward: false, thread: { status: "done", closed: true } });
    expect(current()).toHaveTextContent("Done");
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  test("an archived room shows the current status and no actions", () => {
    respond(204);
    renderControl({ steward: true, room: { archived: true } });
    expect(current()).toHaveTextContent("Triage");
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  test("a pending status write keeps focus and ignores another activation", async () => {
    const user = userEvent.setup();
    let complete!: (response: Response) => void;
    mockFetch.mockReturnValue(new Promise<Response>((resolve) => { complete = resolve; }));
    renderControl({ steward: false });
    const button = action("Move to In progress");
    await user.click(button);
    expect(button).toHaveFocus();
    expect(button).toHaveAttribute("aria-busy", "true");
    await user.keyboard("{Enter}");
    expect(puts()).toHaveLength(1);
    complete(new Response(null, { status: 204 }));
    await waitFor(() => expect(button).not.toHaveAttribute("aria-busy"));
    expect(button).toHaveFocus();
  });

  test("a close confirmation is dismissed if a live update changes the source status", async () => {
    const user = userEvent.setup();
    respond(204);
    const { rerender } = renderControl({ steward: true });
    await pick(user, "Close as Done");
    expect(await screen.findByRole("dialog", { name: "Close this thread?" })).toBeInTheDocument();
    rerender(<ThreadStatusControl room={makeRoom()} thread={makeThread({ status: "working" })} statuses={STATUSES} />);
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(puts()).toEqual([]);
  });

  test("shows the status key while the workflow is loading, then explains when there are no other open statuses", () => {
    const thread = makeThread();
    const room = makeRoom();
    const { rerender } = renderWithProviders(<ThreadStatusControl room={room} thread={thread} statuses={undefined} />);
    expect(current()).toHaveTextContent("triage");
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    rerender(<ThreadStatusControl room={room} thread={thread} statuses={STATUSES.filter((status) => status.key !== "working")} />);
    expect(current()).toHaveTextContent("Triage");
    expect(screen.getByText("No other status is available to you.")).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  test("a refused move (403) says who may, stays under the control and re-reads the room", async () => {
    const user = userEvent.setup();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    respond(403, { title: "Forbidden", status: 403 });
    renderControl({ steward: true, thread: { status: "done", closed: true }, queryClient });
    await pick(user, "Reopen as Triage");
    expect(await screen.findByRole("alert")).toHaveTextContent("Only the thread owner, a room owner or an administrator can close or reopen a thread");
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms"] }));
  });

  test("a 400 (the status was retired meanwhile) says so and re-reads the workflow; a network failure just says it failed", async () => {
    const user = userEvent.setup();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    respond(400, { title: "Bad", status: 400 });
    const { unmount } = renderControl({ steward: false, queryClient });
    await user.click(action("Move to In progress"));
    expect(await screen.findByRole("alert")).toHaveTextContent("That status is no longer available in this room. Pick another one.");
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms"] }));
    unmount();
    invalidate.mockClear();
    mockFetch.mockRejectedValue(new TypeError("offline"));
    renderControl({ steward: false, queryClient });
    await user.click(action("Move to In progress"));
    expect(await screen.findByRole("alert")).toHaveTextContent("Action failed. Check your connection and try again.");
    expect(invalidate).not.toHaveBeenCalled();
  });

  test("a status the workflow no longer lists still shows as the current choice", () => {
    respond(204);
    renderControl({ steward: true, thread: { status: "retired", closed: false } });
    expect(current()).toHaveTextContent("retired");
  });
});
