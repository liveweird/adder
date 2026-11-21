import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import ThreadOwnerSelect from "./ThreadOwnerSelect";
import { jsonResponse } from "../test/http";
import { makeRoom, makeThread } from "../test/messageFixtures";
import { renderWithProviders, screen, waitFor } from "../test/render";

const MEMBERS = {
  items: [
    { userId: 5, name: "Ann Owner", email: "ann@example.test", role: "MEMBER", addedAt: 1 },
    { userId: 9, name: "Bob Next", email: "bob@example.test", role: "MEMBER", addedAt: 1 },
  ],
  page: 1,
  pageSize: 50,
  total: 2,
};

describe("ThreadOwnerSelect", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  const puts = () => mockFetch.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === "PUT").map(([url, init]) => [url, JSON.parse((init as RequestInit).body as string)]);

  function respond(putStatus: number, body?: unknown) {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (init?.method === "PUT") return Promise.resolve(putStatus === 204 ? new Response(null, { status: 204 }) : jsonResponse(putStatus, body));
      if (url.includes("/members?")) return Promise.resolve(jsonResponse(200, MEMBERS));
      return Promise.resolve(jsonResponse(200, {}));
    });
  }
  function renderOwner(steward: boolean, opts: { room?: Partial<ReturnType<typeof makeRoom>>; thread?: Partial<ReturnType<typeof makeThread>>; queryClient?: QueryClient } = {}) {
    return renderWithProviders(<ThreadOwnerSelect room={makeRoom(opts.room)} thread={makeThread({ number: 42, ...opts.thread })} steward={steward} />, { queryClient: opts.queryClient });
  }

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("the picker's label is its accessible name only: the section title above it is the one visible \"Owner\"", () => {
    respond(204);
    const { container } = renderOwner(true);
    expect(screen.getByRole("combobox", { name: "Owner" })).toBeInTheDocument();
    expect(container.querySelector("label")).toBeNull();
    expect(screen.getByText("Search the room's members to pass the thread on.")).toBeInTheDocument();
  });

  test("anyone who is not a steward just reads the owner, with the rule", () => {
    respond(204);
    renderOwner(false);
    // The owner's name is a link to their profile (1.2 D6).
    expect(screen.getByRole("link", { name: "Ann Owner" })).toHaveAttribute("href", "/people/5");
    expect(screen.getByText("Only the thread owner, a room owner or an administrator can pass the thread on.")).toBeInTheDocument();
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Pass on" })).not.toBeInTheDocument();
  });

  test("a plain reader on a closed thread gets the plain rule, not advice to reopen it (which only a steward can follow)", () => {
    respond(204);
    renderOwner(false, { thread: { closed: true } });
    expect(screen.getByText("Only the thread owner, a room owner or an administrator can pass the thread on.")).toBeInTheDocument();
    expect(screen.queryByText(/Reopen it first/)).not.toBeInTheDocument();
  });

  test("a closed thread and an archived room refuse the hand-over: the owner is a name under the reason, even for a steward", () => {
    respond(204);
    const { unmount } = renderOwner(true, { thread: { closed: true } });
    expect(screen.getByText("A closed thread cannot be passed on. Reopen it first.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Ann Owner" })).toHaveAttribute("href", "/people/5");
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    unmount();
    renderOwner(true, { room: { archived: true } });
    expect(screen.getByText("This room is archived, so the thread cannot be passed on.")).toBeInTheDocument();
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
  });

  test("a steward sees the current owner in a member picker; Pass on waits for a different person", async () => {
    respond(204);
    renderOwner(true);
    expect(screen.getByRole("combobox", { name: "Owner" })).toHaveValue("Ann Owner");
    expect(screen.getByRole("button", { name: "Pass on" })).toBeDisabled();
  });

  test("choosing a member does not hand the thread over by itself; Pass on does, then the pick resets", async () => {
    const user = userEvent.setup();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    respond(204);
    renderOwner(true, { queryClient });
    await user.click(screen.getByRole("combobox", { name: "Owner" }));
    await user.click(await screen.findByRole("option", { name: "Bob Next (bob@example.test)" }));
    expect(puts()).toEqual([]);
    await user.click(screen.getByRole("button", { name: "Pass on" }));
    await waitFor(() => expect(puts()).toEqual([["/api/v1/rooms/12/threads/42/owner", { ownerId: 9 }]]));
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms", "threads", 12] }));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms", "rail"] });
    // A hand-over moves no workflow count and changes no room header.
    expect(invalidate).not.toHaveBeenCalledWith({ queryKey: ["rooms"] });
    expect(invalidate).not.toHaveBeenCalledWith({ queryKey: ["rooms", "workflow", 12] });
    // The thread prop is the caller's (still Ann here); the picker is back on the thread's own owner and the button waits again.
    await waitFor(() => expect(screen.getByRole("combobox", { name: "Owner" })).toHaveValue("Ann Owner"));
    expect(screen.getByRole("button", { name: "Pass on" })).toBeDisabled();
  });

  test("picking the current owner again keeps the button off", async () => {
    const user = userEvent.setup();
    respond(204);
    renderOwner(true);
    await user.click(screen.getByRole("combobox", { name: "Owner" }));
    await user.click(await screen.findByRole("option", { name: "Bob Next (bob@example.test)" }));
    expect(screen.getByRole("button", { name: "Pass on" })).toBeEnabled();
    await user.click(screen.getByRole("combobox", { name: "Owner" }));
    await user.click(await screen.findByRole("option", { name: "Ann Owner (ann@example.test)" }));
    expect(screen.getByRole("button", { name: "Pass on" })).toBeDisabled();
  });

  test("a refusal shows under the control and re-reads the room: a person who left (400), a thread closed meanwhile (409 thread-closed)", async () => {
    const user = userEvent.setup();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    respond(400, { title: "Bad", status: 400 });
    const { unmount } = renderOwner(true, { queryClient });
    await user.click(screen.getByRole("combobox", { name: "Owner" }));
    await user.click(await screen.findByRole("option", { name: "Bob Next (bob@example.test)" }));
    await user.click(screen.getByRole("button", { name: "Pass on" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("That person is not an active member of this room. Pick another member.");
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ["rooms"] }));
    unmount();
    respond(409, { type: "urn:adder:problem:thread-closed", title: "Closed", status: 409 });
    renderOwner(true, { queryClient });
    await user.click(screen.getByRole("combobox", { name: "Owner" }));
    await user.click(await screen.findByRole("option", { name: "Bob Next (bob@example.test)" }));
    await user.click(screen.getByRole("button", { name: "Pass on" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("This thread was closed in the meantime, so it cannot be passed on. Reopen it first.");
  });
});
