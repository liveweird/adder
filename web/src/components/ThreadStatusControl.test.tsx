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
    return renderWithProviders(
      <ThreadStatusControl room={makeRoom(opts.room)} thread={makeThread({ number: 42, ...opts.thread })} statuses={STATUSES} steward={opts.steward} />,
      { queryClient: opts.queryClient },
    );
  }
  const openList = async (user: ReturnType<typeof userEvent.setup>) => {
    await user.click(screen.getByRole("combobox", { name: "Thread status" }));
    return screen.findByRole("listbox");
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

  test("shows the current status, and lists the workflow's statuses grouped Open and Closed", async () => {
    const user = userEvent.setup();
    respond(204);
    renderControl({ steward: true });
    expect(screen.getByRole("combobox", { name: "Thread status" })).toHaveValue("Triage");
    const list = await openList(user);
    expect(within(list).getByText("Open")).toBeInTheDocument();
    expect(within(list).getByText("Closed")).toBeInTheDocument();
    expect(within(list).getAllByRole("option").map((option) => option.textContent)).toEqual(["Triage", "In progress", "Done", "Won't do"]);
  });

  test("a plain member moves between OPEN statuses at once; the CLOSED ones are disabled with the reason", async () => {
    const user = userEvent.setup();
    respond(204);
    renderControl({ steward: false });
    expect(screen.getByText(/is for the thread owner, a room owner or an administrator/)).toBeInTheDocument();
    const list = await openList(user);
    const done = within(list).getByRole("option", { name: /Done/ });
    expect(done).toHaveAttribute("data-combobox-disabled", "true");
    expect(done).toHaveTextContent("Needs the thread owner, a room owner or an administrator");
    expect(within(list).getByRole("option", { name: /In progress/ })).not.toHaveAttribute("data-combobox-disabled");
    await user.click(within(list).getByRole("option", { name: /In progress/ }));
    await waitFor(() => expect(puts()).toEqual([["/api/v1/rooms/12/threads/42/status", { status: "working" }]]));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  test("a successful move re-reads the room's threads, the rail and the workflow's counts, not the whole prefix", async () => {
    const user = userEvent.setup();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    respond(204);
    renderControl({ steward: false, queryClient });
    await user.click(within(await openList(user)).getByRole("option", { name: /In progress/ }));
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms", "workflow", 12] }));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms", "threads", 12] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms", "rail"] });
    expect(invalidate).not.toHaveBeenCalledWith({ queryKey: ["rooms"] });
  });

  test("a steward closing an open thread is asked first; confirming sends the move, cancelling sends nothing", async () => {
    const user = userEvent.setup();
    respond(204);
    renderControl({ steward: true });
    await user.click(within(await openList(user)).getByRole("option", { name: /Done/ }));
    const dialog = await screen.findByRole("dialog", { name: "Close this thread?" });
    expect(dialog).toHaveTextContent("Moving #42 to Done closes it");
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(puts()).toEqual([]);
    await user.click(within(await openList(user)).getByRole("option", { name: /Done/ }));
    await user.click(within(await screen.findByRole("dialog", { name: "Close this thread?" })).getByRole("button", { name: "Close thread" }));
    await waitFor(() => expect(puts()).toEqual([["/api/v1/rooms/12/threads/42/status", { status: "done" }]]));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  test("reopening, and a CLOSED to CLOSED move, go straight through for a steward (no confirm)", async () => {
    const user = userEvent.setup();
    respond(204);
    renderControl({ steward: true, thread: { status: "done", closed: true } });
    await user.click(within(await openList(user)).getByRole("option", { name: /Won't do/ }));
    await waitFor(() => expect(puts()).toEqual([["/api/v1/rooms/12/threads/42/status", { status: "wontdo" }]]));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  test("on a closed thread a plain member can pick no other status", async () => {
    const user = userEvent.setup();
    respond(204);
    renderControl({ steward: false, thread: { status: "done", closed: true } });
    const list = await openList(user);
    for (const name of [/Triage/, /In progress/, /Won't do/]) expect(within(list).getByRole("option", { name })).toHaveAttribute("data-combobox-disabled", "true");
  });

  test("an archived room disables the select and says why", () => {
    respond(204);
    renderControl({ steward: true, room: { archived: true } });
    expect(screen.getByRole("combobox", { name: "Thread status" })).toBeDisabled();
    expect(screen.getByText("This room is archived, so the status can no longer change.")).toBeInTheDocument();
  });

  test("a refused move (403) says who may, stays under the control and re-reads the room", async () => {
    const user = userEvent.setup();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    respond(403, { title: "Forbidden", status: 403 });
    renderControl({ steward: true, thread: { status: "done", closed: true }, queryClient });
    await user.click(within(await openList(user)).getByRole("option", { name: /Triage/ }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Only the thread owner, a room owner or an administrator can close or reopen a thread");
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms"] }));
  });

  test("a 400 (the status was retired meanwhile) says so and re-reads the workflow; a network failure just says it failed", async () => {
    const user = userEvent.setup();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    respond(400, { title: "Bad", status: 400 });
    const { unmount } = renderControl({ steward: false, queryClient });
    await user.click(within(await openList(user)).getByRole("option", { name: /In progress/ }));
    expect(await screen.findByRole("alert")).toHaveTextContent("That status is no longer available in this room. Pick another one.");
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms"] }));
    unmount();
    invalidate.mockClear();
    mockFetch.mockRejectedValue(new TypeError("offline"));
    renderControl({ steward: false, queryClient });
    await user.click(within(await openList(user)).getByRole("option", { name: /In progress/ }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Action failed. Check your connection and try again.");
    expect(invalidate).not.toHaveBeenCalled();
  });

  test("a status the workflow no longer lists still shows as the current choice", () => {
    respond(204);
    renderControl({ steward: true, thread: { status: "retired", closed: false } });
    expect(screen.getByRole("combobox", { name: "Thread status" })).toHaveValue("retired");
  });
});
